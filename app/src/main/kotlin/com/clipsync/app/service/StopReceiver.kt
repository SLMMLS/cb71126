package com.clipsync.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Handles the "Выключить" button of the receiver's notification. A broadcast rather than a
 * service intent on purpose: it works even when the app is in the background and the service
 * is the only thing alive, and stopping a service is never refused the way starting one can be.
 */
class StopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReceiverControl.ACTION_STOP) return
        // goAsync(): the PC is told about it on a background thread, which needs the broadcast alive.
        val pending = goAsync()
        try {
            ReceiverControl.turnOff(context, pending)
        } catch (e: Exception) {
            pending.finish() // never leave a broadcast hanging: the system would flag the app as not responding
            throw e
        }
    }
}
