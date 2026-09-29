package com.cat.client

import org.json.JSONObject

/**
 * Single-config editing on top of [SubscriptionStore] catalogs.
 *
 * A "config" is one [ConnectionProfile] inside a subscription catalog. The user
 * asked for the ability to edit (rename, change server/port/SNI) and delete
 * individual configs from inside the app — previously only whole subscriptions
 * could be edited.
 *
 * Design notes:
 * - Edits mutate the catalog JSON on disk via [SubscriptionStore.saveCatalog].
 * - [ConnectionProfile.fingerprint] is derived from (type, server, port,
 *   validationHost) — see [ProfileFingerprint.from]. When one of those fields
 *   changes we MUST recompute it, otherwise cached delays and selections break
 *   (the fingerprint is the join key everywhere).
 * - Renaming only changes [ConnectionProfile.tag] and keeps the fingerprint
 *   stable so delay history and the selected node survive a rename.
 * - The edited profile keeps its [ConnectionProfile.shareLink] only when the
 *   edit is a pure rename; structural edits rebuild the link when possible.
 */
object ConfigEditor {

    data class EditResult(
        val ok: Boolean,
        val messageRes: Int? = null,
    )

    /** Returns true when any fingerprint-affecting field differs. */
    fun isStructuralChange(old: ConnectionProfile, new: ConnectionProfile): Boolean =
        old.type != new.type ||
            old.server != new.server ||
            old.port != new.port ||
            old.validationHost != new.validationHost

    /** Recompute the fingerprint for a structurally edited profile. */
    private fun withFreshFingerprint(p: ConnectionProfile): ConnectionProfile = p.copy(
        fingerprint = ProfileFingerprint.from(
            type = p.type,
            server = p.server,
            port = p.port,
            validationHost = p.validationHost,
            outboundJson = p.outboundJson,
        ),
    )

    fun list(store: SubscriptionStore, subscriptionId: String): List<ConnectionProfile> =
        store.readCatalog(subscriptionId)?.profiles ?: emptyList()

    fun findByFingerprint(
        store: SubscriptionStore,
        subscriptionId: String,
        fingerprint: String,
    ): ConnectionProfile? = list(store, subscriptionId).firstOrNull { it.fingerprint == fingerprint }

    /**
     * Apply [transform] to the profile identified by [fingerprint] and persist.
     * Returns null when the subscription/catalog or profile cannot be found.
     */
    private fun mutate(
        store: SubscriptionStore,
        subscriptionId: String,
        fingerprint: String,
        transform: (ConnectionProfile) -> ConnectionProfile?,
    ): ConnectionProfile? {
        val catalog = store.readCatalog(subscriptionId) ?: return null
        val profiles = catalog.profiles
        val index = profiles.indexOfFirst { it.fingerprint == fingerprint }
        if (index < 0) return null
        val updated = transform(profiles[index]) ?: return null
        val next = profiles.toMutableList().apply { this[index] = updated }
        store.saveCatalog(subscriptionId, catalog.copy(profiles = next, fetchedAt = catalog.fetchedAt))
        return updated
    }

    /** Rename only — keeps fingerprint, delay history and current selection. */
    fun rename(
        store: SubscriptionStore,
        subscriptionId: String,
        fingerprint: String,
        newTag: String,
    ): ConnectionProfile? {
        val tag = newTag.trim()
        if (tag.isEmpty()) return null
        return mutate(store, subscriptionId, fingerprint) { it.copy(tag = tag) }
    }

    /**
     * Structural edit: server / port / SNI (validationHost) / transport.
     * Recomputes the fingerprint so delay caches stay consistent.
     */
    fun edit(
        store: SubscriptionStore,
        subscriptionId: String,
        fingerprint: String,
        server: String = "",
        port: Int = -1,
        validationHost: String? = null,
        transport: String? = null,
    ): ConnectionProfile? {
        val host = server.trim()
        if (host.isEmpty()) return null
        if (port <= 0 || port > 65535) return null
        val edited = mutate(store, subscriptionId, fingerprint) { old ->
            val next = old.copy(
                server = host,
                port = port,
                validationHost = (validationHost ?: old.validationHost).trim(),
                transport = (transport ?: old.transport).trim(),
            )
            if (isStructuralChange(old, next)) withFreshFingerprint(next) else next
        } ?: return null
        return edited
    }

    /** Delete a single config. Returns false when it was not found. */
    fun delete(
        store: SubscriptionStore,
        subscriptionId: String,
        fingerprint: String,
    ): Boolean {
        val catalog = store.readCatalog(subscriptionId) ?: return false
        val profiles = catalog.profiles
        if (profiles.none { it.fingerprint == fingerprint }) return false
        val next = profiles.filterNot { it.fingerprint == fingerprint }
        if (next.isEmpty()) return false // never leave a subscription empty
        store.saveCatalog(subscriptionId, catalog.copy(profiles = next, fetchedAt = catalog.fetchedAt))
        return true
    }

    /** Duplicate an existing config under a new tag ("name (copy)"). */
    fun duplicate(
        store: SubscriptionStore,
        subscriptionId: String,
        fingerprint: String,
    ): ConnectionProfile? {
        val catalog = store.readCatalog(subscriptionId) ?: return null
        val source = catalog.profiles.firstOrNull { it.fingerprint == fingerprint } ?: return null
        var copy = source.copy(tag = source.tag + " (copy)")
        copy = copy.copy(
            fingerprint = ProfileFingerprint.from(
                type = copy.type,
                server = copy.server,
                port = copy.port,
                validationHost = copy.validationHost,
                outboundJson = copy.outboundJson,
            ) + "#dup" + (System.currentTimeMillis() % 100000),
        )
        store.saveCatalog(
            subscriptionId,
            catalog.copy(profiles = catalog.profiles + copy, fetchedAt = catalog.fetchedAt),
        )
        return copy
    }
}
