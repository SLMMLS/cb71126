package com.clipsync.app.files

import android.content.Context

/**
 * The one setting of this feature on the phone: whether files from the PC are accepted. Kept apart from
 * the clipboard receiving switch ("Приём"): a person may want the PC's clipboard on the phone but not its
 * files. Stored in SharedPreferences (the database is not touched by this feature). On by default - an
 * offer from the paired PC is what the person asked for when they dropped a file on its window.
 */
object FilesSettings {
    private const val PREFS = "clipsync_files"
    private const val KEY_ACCEPT = "accept_incoming"

    fun acceptIncoming(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ACCEPT, true)

    fun setAcceptIncoming(context: Context, accept: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ACCEPT, accept).apply()
    }
}
