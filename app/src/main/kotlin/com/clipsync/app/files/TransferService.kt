package com.clipsync.app.files

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat

private const val TAG = "ClipSync"

/**
 * Keeps the app alive while files are being sent to the PC - with the screen off, with another app in
 * front. It does no work itself: the sending runs in [TransferManager]; this only holds the foreground
 * notification (with progress) that tells Android the process is doing something the person asked for.
 * [TransferManager] starts it when the first file is queued and stops it when the queue is empty.
 *
 * Same failure policy as [com.clipsync.app.service.ReceiverService]: if Android refuses the foreground
 * start (Android 12+ restricts it in the background), the service leaves cleanly, and the transfer
 * carries on in the process as long as the system allows.
 */
class TransferService : Service() {
    override fun onCreate() {
        super.onCreate()
        isRunning = enterForeground()
        if (!isRunning) stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
    }

    private fun enterForeground(): Boolean {
        TransferNotifications.ensureChannels(this)
        val notification = TransferNotifications.sendingNotification(this, Transfers.registry.snapshot())
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(TransferNotifications.SENDING_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(TransferNotifications.SENDING_ID, notification)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "startForeground was refused for the file transfer service", e)
            false
        }
    }

    companion object {
        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, TransferService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "could not start TransferService", e)
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, TransferService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "could not stop TransferService", e)
            }
        }
    }
}
