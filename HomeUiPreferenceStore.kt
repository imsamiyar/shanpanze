package com.cat.client

import android.content.Context
import android.content.SharedPreferences

/**
 * Which surface owns the middle of the home dashboard: the VPN-UI power ring
 * (Nord/ZenMate genre, default) or the world-map connection globe.
 *
 * Kept tiny and self-contained: one boolean in a private SharedPreferences
 * file, no migration and no schema bump.
 */
object HomeUiPreferenceStore {

    private const val PREFS = "cat_client_home_ui"
    private const val KEY_MAP_VIEW = "map_view"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** true = classic world-map globe, false = VPN-UI power ring (default). */
    fun readMapView(context: Context): Boolean = prefs(context).getBoolean(KEY_MAP_VIEW, false)

    fun saveMapView(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_MAP_VIEW, value).apply()
    }
}
