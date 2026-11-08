package com.clipsync.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.clipsync.app.network.HelloSender
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private const val TAG = "ClipSync"

/**
 * The user's control over the background receiver ([ReceiverService]).
 *
 * The receiver runs as a foreground service so that the PC can push at any time - which also
 * means that closing the app does not stop it, and until now nothing could. There are three
 * ways to stop it now, all ending up here:
 *
 * * the "Выключить" button in the receiver's notification ([StopReceiver]),
 * * the "Приём" switch in the app (top bar and Settings),
 * * the "Останавливать при закрытии приложения" option: swiping the app away stops the receiver
 *   for now (see `ReceiverService.onTaskRemoved`) without changing the switch.
 *
 * The switch is remembered: while it is off, nothing starts the receiver - not opening the app,
 * not a reboot. Switching it off also tells the PC (a hello with `receiver: false`), which then
 * stops sending and shows "приём выключен" instead of piling up connection errors.
 */
object ReceiverControl {
    const val ACTION_STOP = "com.clipsync.app.action.STOP_RECEIVER"

    // The same preferences file Repository uses (its PREFS constant is private to it).
    private const val PREFS = "clipsync_prefs"
    private const val KEY_ENABLED = "receiver_enabled"
    private const val KEY_STOP_ON_CLOSE = "stop_on_close"

    private val enabledState = MutableStateFlow(true)
    private var enabledLoaded = false

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Default on: an update must not silently turn receiving off for anyone. */
    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    /** For the UI: follows the switch whoever flips it (the app, the notification button, a new pairing). */
    fun enabled(context: Context): StateFlow<Boolean> {
        synchronized(this) {
            if (!enabledLoaded) {
                enabledState.value = isEnabled(context)
                enabledLoaded = true
            }
        }
        return enabledState
    }

    fun stopOnClose(context: Context): Boolean = prefs(context).getBoolean(KEY_STOP_ON_CLOSE, false)

    fun setStopOnClose(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_STOP_ON_CLOSE, value).apply()
    }

    /** Should the receiver start by itself when the app is opened? */
    fun shouldAutoStart(context: Context): Boolean = isEnabled(context)

    /**
     * Should it start after a reboot? Not with "stop on close": that option means "receive only
     * while I use the app", so a reboot must not bring the receiver back on its own.
     */
    fun shouldStartAtBoot(context: Context): Boolean = isEnabled(context) && !stopOnClose(context)

    /**
     * Remembers the choice without starting or stopping anything. A fresh pairing uses this: pairing
     * is an explicit "make it work", so it must not stay silenced by an old switch position.
     */
    fun setEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, value).apply()
        enabledState.value = value
    }

    fun turnOn(context: Context) {
        setEnabled(context, true)
        start(context)
    }

    /**
     * Switches receiving off: remembers it, stops the service right away and - on a background
     * thread, bounded by short timeouts - tells the PC. [pendingResult] is the notification
     * button's broadcast, kept alive until the PC has been told.
     */
    fun turnOff(context: Context, pendingResult: BroadcastReceiver.PendingResult? = null) {
        val app = context.applicationContext
        setEnabled(app, false)
        app.stopService(Intent(app, ReceiverService::class.java))
        Thread(
            {
                try {
                    HelloSender.send(app, "receiver_off", receiver = false, quick = true)
                } catch (e: Exception) {
                    Log.w(TAG, "could not tell the PC that receiving is off", e)
                } finally {
                    pendingResult?.finish()
                }
            },
            "clipsync-goodbye",
        ).start()
    }

    /** Starts the foreground service; a refused start (Android 12+ from the background) is only logged. */
    fun start(context: Context) {
        val app = context.applicationContext
        try {
            ContextCompat.startForegroundService(app, Intent(app, ReceiverService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "could not start ReceiverService", e)
        }
    }
}
