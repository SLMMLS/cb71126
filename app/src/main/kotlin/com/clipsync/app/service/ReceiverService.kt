package com.clipsync.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import com.clipsync.app.crypto.Identity
import com.clipsync.app.data.AppDatabase
import com.clipsync.app.data.Repository
import com.clipsync.app.network.HelloScheduler
import com.clipsync.app.network.HelloSender
import com.clipsync.app.network.NetworkWatcher
import com.clipsync.app.network.PushPayload
import com.clipsync.app.network.SyncServer
import com.clipsync.app.ui.MainActivity
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

const val SYNC_PORT = 47990
private const val TAG = "ClipSync"
private const val CHANNEL_ID = "clipsync_receiver"
private const val PERSISTENT_NOTIFICATION_ID = 1
private const val NEW_ITEM_NOTIFICATION_ID = 2

/**
 * Keeps the embedded HTTP receiver alive so the PC can push to this phone
 * at any time, not just while the app happens to be open. Declared with
 * foregroundServiceType="connectedDevice" in the manifest (an ongoing
 * network connection to an external device) rather than "dataSync", which
 * Google has been steering apps away from for exactly this kind of
 * always-on network listener and which carries a hard runtime cap on
 * newer Android versions.
 *
 * Failure policy: nothing in here is allowed to take the whole app down.
 * startForeground() can be refused by the system (Android 14+ checks the
 * foreground-service-type prerequisites at runtime; Android 12+ can refuse a
 * background start) and the listening port can be taken; both are caught,
 * logged under the tag "ClipSync", surfaced in the notification and in
 * Settings -> "Проверить связь", and retried the next time the app starts
 * the service.
 */
class ReceiverService : Service() {
    private var server: SyncServer? = null
    private var foregroundOk = false

    /** False when the user has switched receiving off: the service then stops itself at once. */
    private var active = false

    // "I'm here" messages to the PC (PROTOCOL.md §3b): when the receiver starts, when the network
    // changes, when the app is opened.
    private var helloExecutor: ScheduledExecutorService? = null
    private var hello: HelloScheduler? = null
    private var networkWatcher: NetworkWatcher? = null

    override fun onCreate() {
        super.onCreate()
        foregroundOk = enterForeground()
        if (!foregroundOk) {
            stopSelf()
            return
        }
        if (!ReceiverControl.isEnabled(this)) {
            // The user switched receiving off (the notification button, the switch in the app). A
            // start request that raced with that - a boot broadcast, an old pending intent - must
            // not bring the receiver back. startForeground was already called, as Android demands
            // of a service started with startForegroundService, so leave cleanly.
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        active = true
        startServerIfNeeded()
        startHello()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundOk || !active) return START_NOT_STICKY
        // MainActivity starts this service on every launch, so a receiver that
        // failed earlier (port busy, ...) gets another chance here - and the PC
        // hears that the app is in use again.
        startServerIfNeeded()
        hello?.request("app_open")
        return START_STICKY
    }

    /**
     * The app was swiped away from the recent apps. By default the receiver keeps running (that
     * is its purpose); with "Останавливать при закрытии приложения" it stops - after telling the
     * PC. The on/off switch is left alone, so opening the app starts the receiver again.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!active || !ReceiverControl.stopOnClose(this)) return
        val app = applicationContext
        Thread(
            {
                try {
                    HelloSender.send(app, "app_closed", receiver = false, quick = true)
                } catch (e: Exception) {
                    Log.w(TAG, "could not tell the PC that the app was closed", e)
                } finally {
                    stopSelf()
                }
            },
            "clipsync-goodbye",
        ).start()
    }

    override fun onDestroy() {
        networkWatcher?.stop()
        networkWatcher = null
        hello?.shutdown()
        hello = null
        helloExecutor?.shutdownNow()
        helloExecutor = null
        server?.stop()
        server = null
        isRunning = false
        super.onDestroy()
    }

    /** Starts announcing this phone to the PC; see [HelloScheduler] for when a hello really goes out. */
    private fun startHello() {
        val executor = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "clipsync-hello").apply { isDaemon = true }
        }
        val scheduler = HelloScheduler(
            executor = executor,
            // `isRunning` is read when the message is built: if the port was taken and the receiver
            // never started, the PC is told that nothing is listening.
            send = { reason -> HelloSender.send(applicationContext, reason, receiver = isRunning) },
        )
        helloExecutor = executor
        hello = scheduler
        networkWatcher = NetworkWatcher(applicationContext) { scheduler.request("network_change") }.also { it.start() }
        scheduler.request("service_start")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun enterForeground(): Boolean {
        val notification = buildNotification("ClipSync активен", "Готов принимать буфер с ПК")
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    PERSISTENT_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
            } else {
                startForeground(PERSISTENT_NOTIFICATION_ID, notification)
            }
            true
        } catch (e: Exception) {
            // SecurityException = a prerequisite for the FGS type is missing (see the
            // manifest); ForegroundServiceStartNotAllowedException = Android refused a
            // background start. Stop cleanly instead of crashing the app.
            Log.e(TAG, "startForeground was refused by the system", e)
            isRunning = false
            lastError = "Android не разрешил фоновый сервис: ${e.message}"
            false
        }
    }

    @Synchronized
    private fun startServerIfNeeded() {
        if (server != null) return
        try {
            val identity = Identity.loadOrCreate(applicationContext)
            val db = AppDatabase.get(applicationContext)
            val repository = Repository(applicationContext)

            val candidate = SyncServer(
                port = SYNC_PORT,
                identity = identity,
                getPeerPublicKey = { deviceId ->
                    db.pairedPcDao().getPc()?.takeIf { it.deviceId == deviceId }?.publicKey
                },
                onPushReceived = { _, payload: PushPayload ->
                    val contentBytes = when (payload.entryType) {
                        "image" -> Base64.decode(payload.contentB64, Base64.NO_WRAP)
                        "text" -> payload.contentB64.toByteArray(Charsets.UTF_8)
                        else -> throw IllegalArgumentException("unsupported entry_type ${payload.entryType}")
                    }
                    // The PC re-pushes whatever the user re-copies there; an item that is already in
                    // the history only moves to the top, without a second row or a second notification.
                    val isNew = repository.storeIncoming(payload.entryType, contentBytes)
                    if (isNew) showNewItemNotification()
                },
                // Files from the PC (PROTOCOL.md §3c): /file/offer, /file/chunk, /file/finish, /file/cancel.
                files = com.clipsync.app.files.Transfers.receiver,
            )
            candidate.start() // throws IOException if the port is already taken
            server = candidate
            isRunning = true
            lastError = null
            Log.i(TAG, "receiver listening on port $SYNC_PORT")
            updateNotification("ClipSync активен", "Готов принимать буфер с ПК")
            // After an earlier failure (port busy) the PC was told nothing listens: tell it that it does.
            hello?.request("receiver_started")
        } catch (e: Exception) {
            isRunning = false
            lastError = e.message ?: e.javaClass.simpleName
            Log.e(TAG, "receiver failed to start", e)
            updateNotification("ClipSync: приёмник не запущен", lastError ?: "ошибка")
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        createChannelIfNeeded()
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getBroadcast(
            this,
            1,
            Intent(this, StopReceiver::class.java).setAction(ReceiverControl.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(openAppIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Выключить", stopIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(title: String, text: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(PERSISTENT_NOTIFICATION_ID, buildNotification(title, text))
    }

    private fun showNewItemNotification() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Новый элемент с ПК")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setAutoCancel(true)
            .build()
        manager.notify(NEW_ITEM_NOTIFICATION_ID, notification)
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "ClipSync", NotificationManager.IMPORTANCE_LOW),
                )
            }
        }
    }

    companion object {
        /** True while the NanoHTTPD receiver is actually listening. Read by the diagnostics screen. */
        @Volatile
        var isRunning: Boolean = false
            private set

        /** Why the receiver is not running, if it isn't; null when healthy. */
        @Volatile
        var lastError: String? = null
            private set
    }
}
