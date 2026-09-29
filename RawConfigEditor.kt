package com.cat.client

import org.json.JSONObject

/**
 * v2rayNG/v2Box-style raw config editing, backed by the subscription's own YAML.
 *
 * Why YAML, not the cached catalog: [SubscriptionStore.saveCatalog] writes the
 * derived cache, and the next subscription refresh would silently overwrite it.
 * The durable source of truth for a user subscription is the stored Mihomo YAML
 * (see [SubscriptionStore.readUserSubscriptionYaml]). Editing that YAML means
 * the edit survives refreshes of the panel's other fields and appears in share
 * links, delays, and the core config alike — same behaviour as v2rayNG's
 * "Edit config" which rewrites the underlying profile record.
 *
 * A config is identified by its proxy `name:` (the YAML key) plus its
 * fingerprint for safety. The YAML is line-based (our own emitted YAML is
 * line-structured: `  - name: ...` then `    key: value`), so the editor
 * replaces values on the matching proxy block only. A malformed or
 * foreign-format YAML (deeply indented flow style) fails safely with null
 * rather than corrupting the file.
 */
internal object RawConfigEditor {

    data class ProxyBlock(
        val name: String,
        val startLine: Int, // index into source lines, 0-based, the "- name:" line
        val endLine: Int,   // exclusive
    )

    /** Parse the `proxies:` list of a Mihomo YAML into line-ranged blocks. */
    fun blocks(yaml: String): List<ProxyBlock> {
        val lines = yaml.lines()
        val out = mutableListOf<ProxyBlock>()
        var inProxies = false
        var current: ProxyBlock? = null
        lines.forEachIndexed { i, raw ->
            val line = raw.trimEnd()
            val trimmed = line.trim()
            when {
                trimmed == "proxies:" -> { inProxies = true; return@forEachIndexed }
                inProxies && trimmed.isNotEmpty() && !trimmed.startsWith("-") && !line.startsWith(" ") -> {
                    // a top-level key at column 0 ends the proxies list
                    inProxies = false
                }
            }
            if (!inProxies) { current?.let { out.add(it.copy(endLine = i)); current = null }; return@forEachIndexed }
            if (trimmed.startsWith("- ")) {
                current?.let { out.add(it.copy(endLine = i)) }
                val name = Regex("""^-\s*name:\s*(.+)$""").find(trimmed)?.groupValues?.get(1)
                    ?.trim()?.removeSurrounding("'")?.removeSurrounding("\"")
                current = ProxyBlock(name = name.orEmpty(), startLine = i, endLine = lines.size)
            }
        }
        current?.let { out.add(it.copy(endLine = lines.size)) }
        return out.filter { it.name.isNotBlank() }
    }

    private fun block(yaml: String, name: String): ProxyBlock? =
        blocks(yaml).firstOrNull { it.name == name }

    /** Read one scalar `key: value` inside a proxy block. */
    fun readField(yaml: String, name: String, key: String): String? {
        val b = block(yaml, name) ?: return null
        val lines = yaml.lines()
        val re = Regex("""^\s*-?\s*$key:\s*(.+)$""")
        for (i in b.startLine until b.endLine) {
            re.find(lines[i].trimEnd())?.let { return it.groupValues[1].trim().removeSurrounding("'").removeSurrounding("\"") }
        }
        return null
    }

    /**
     * Write one scalar field inside the named proxy block. Creates the line
     * after `- name:` when the key does not exist yet. Returns the new YAML or
     * null when the proxy is missing.
     */
    fun writeField(yaml: String, name: String, key: String, value: String): String? {
        val b = block(yaml, name) ?: return null
        val lines = yaml.lines().toMutableList()
        val re = Regex("""^(\s*-?\s*)$key:\s*(.+)$""")
        for (i in b.startLine + 1 until b.endLine) {
            val m = re.find(lines[i]) ?: continue
            lines[i] = m.groupValues[1] + key + ": " + MihomoLinkConfigBuilder.quoteFor(value)
            return lines.joinToString("\n")
        }
        // not present: insert right after the `- name:` line, same indent
        val indent = Regex("""^(\s*)-""").find(lines[b.startLine])?.groupValues?.get(1)?.length ?: 4
        lines.add(b.startLine + 1, " ".repeat(indent) + "$key: " + MihomoLinkConfigBuilder.quoteFor(value))
        return lines.joinToString("\n")
    }

    /** Delete the named proxy from the YAML. Returns null when missing, "" when last proxy. */
    fun deleteProxy(yaml: String, name: String): String? {
        val b = block(yaml, name) ?: return null
        val lines = yaml.lines().toMutableList()
        lines.subList(b.startLine, b.endLine).clear()
        val result = lines.joinToString("\n").trimEnd() + "\n"
        return if (block(result, name) == null && blocks(result).isEmpty()) "" else result
    }

    /** Add a new proxy block from a share link (vless://, vmess://, trojan://, ss://, hy2://). */
    fun addFromLink(yaml: String, link: String): Pair<String, String>? {
        val proxies = runCatching { SubConvConverter.convert(link.trim()) }.getOrNull().orEmpty()
        val proxy = proxies.firstOrNull() ?: return null
        val name = proxy.optString("name").takeIf(String::isNotBlank) ?: return null
        val block = MihomoLinkConfigBuilder.blockFor(proxy) ?: return null
        val lines = yaml.lines().toMutableList()
        val proxiesIdx = lines.indexOfFirst { it.trim() == "proxies:" }
        if (proxiesIdx < 0) return null
        lines.addAll(proxiesIdx + 1, block.lines())
        val newYaml = lines.joinToString("\n")
        // register the new name in every group that lists proxies
        return Pair(registerInGroups(newYaml, name), name)
    }

    /** Ensure [name] appears in each select/url-test group's proxies list. */
    fun registerInGroups(yaml: String, name: String): String {
        val lines = yaml.lines().toMutableList()
        var i = 0
        while (i < lines.size) {
            if (Regex("""^\s{2}-\s*name:""").containsMatchIn(lines[i]) || lines[i].trim().startsWith("- name:")) {
                // find this group's type + proxies list
                var j = i + 1
                var type = ""
                var lastProxyLine = -1
                var groupEnded = false
                while (j < lines.size && !groupEnded) {
                    val t = lines[j].trim()
                    when {
                        t.startsWith("type:") -> type = t.substringAfter(":").trim()
                        t.startsWith("proxies:") -> lastProxyLine = j
                        t.startsWith("- name:") && j > i -> groupEnded = true
                        !lines[j].startsWith(" ") && t.isNotEmpty() -> groupEnded = true
                    }
                    j++
                }
                if (type == "select" || type == "url-test") {
                    val insertAt = if (lastProxyLine >= 0) lastProxyLine + 1 else j
                    lines.add(insertAt, "      - " + MihomoLinkConfigBuilder.quoteFor(name))
                }
                i = j
            } else i++
        }
        return lines.joinToString("\n")
    }

    /** Duplicate the named proxy with " (copy)" suffix. Returns new YAML + new name. */
    fun duplicateProxy(yaml: String, name: String): Pair<String, String>? {
        val b = block(yaml, name) ?: return null
        val newName = "$name (copy)"
        val lines = yaml.lines()
        val blockLines = lines.subList(b.startLine, b.endLine).toMutableList()
        val nameRe = Regex("""^(.*name:\s*)(.+)$""")
        blockLines[0] = nameRe.replace(blockLines[0]) { m ->
            m.groupValues[1] + MihomoLinkConfigBuilder.quoteFor(newName)
        }
        val all = lines.toMutableList()
        all.addAll(b.endLine, blockLines)
        return Pair(all.joinToString("\n"), newName)
    }
}
