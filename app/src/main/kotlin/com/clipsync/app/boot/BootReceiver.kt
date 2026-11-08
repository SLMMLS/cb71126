package com.clipsync.app.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.clipsync.app.data.AppDatabase
import com.clipsync.app.service.ReceiverControl
import com.clipsync.app.service.ReceiverService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * BOOT_COMPLETED is one of the few situations Android still allows a
 * background-started foreground service for, which is why this can start
 * ReceiverService directly instead of needing the app to be opened first.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val hasPairedPc = AppDatabase.get(context.applicationContext).pairedPcDao().getPc() != null
                // Not when the user switched receiving off (or chose "stop on close", which means
                // "receive only while I use the app"): a reboot must not undo that.
                if (hasPairedPc && ReceiverControl.shouldStartAtBoot(context)) {
                    ContextCompat.startForegroundService(context, Intent(context, ReceiverService::class.java))
                }
            } catch (e: Exception) {
                // A refused background start must never crash the boot broadcast.
                Log.w("ClipSync", "could not start ReceiverService after boot", e)
            } finally {
                pending.finish()
            }
        }
    }
}
