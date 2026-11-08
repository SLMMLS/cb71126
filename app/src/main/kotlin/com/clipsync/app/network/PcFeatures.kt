package com.clipsync.app.network

import android.content.Context

/**
 * What the paired PC said it can do: the `features` in its answer to /hello (PROTOCOL.md §3b). A side
 * relies on an optional ability only after the other side listed it, so a PC that never answered
 * (an older ClipSync) is treated as having none.
 *
 * Kept in SharedPreferences, not in Room: the database schema is not touched by this feature (the
 * first change to it must be a step of its own, see ROADMAP). The PC's id is stored with the list,
 * so a phone that is paired with another PC later does not inherit what the first one could do.
 */
object PcFeatures {
    private const val PREFS = "clipsync_pc_features"
    private const val KEY_PC = "pc"
    private const val KEY_FEATURES = "features"

    fun save(context: Context, pcDeviceId: String, features: List<String>) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PC, pcDeviceId)
            .putStringSet(KEY_FEATURES, features.toSet())
            .apply()
    }

    fun has(context: Context, pcDeviceId: String, feature: String): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_PC, null) != pcDeviceId) return false
        return prefs.getStringSet(KEY_FEATURES, emptySet())?.contains(feature) == true
    }

    /** Forgets everything (the PC was unpaired). */
    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
