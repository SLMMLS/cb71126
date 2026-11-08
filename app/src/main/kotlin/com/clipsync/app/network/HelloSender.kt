package com.clipsync.app.network

import android.content.Context
import android.util.Log
import com.clipsync.app.crypto.Identity
import com.clipsync.app.data.AppDatabase
import com.clipsync.app.data.Repository
import com.clipsync.app.service.SYNC_PORT
import kotlinx.coroutines.runBlocking

private const val TAG = "ClipSync"

/**
 * Builds and sends one hello (PROTOCOL.md §3b). Used by the receiver service (start, network
 * change, app open) and by [com.clipsync.app.service.ReceiverControl] (the "goodbye" when the
 * user switches receiving off). Blocking: call from a background thread only.
 */
object HelloSender {
    @Volatile
    var last: HelloState? = null
        private set

    /** "Проверить связь" line about the last hello, or null when none was sent in this process. */
    fun describeLast(now: Long = System.currentTimeMillis()): String? = last?.let { describeHello(it, now) }

    /**
     * @param receiver whether this phone's receiver is listening right now
     * @param quick short timeouts, for messages the user is waiting for (switching off)
     */
    fun send(context: Context, reason: String, receiver: Boolean, quick: Boolean = false): HelloOutcome {
        val app = context.applicationContext
        val pc = runBlocking { AppDatabase.get(app).pairedPcDao().getPc() } ?: return HelloOutcome.GIVE_UP
        val identity = Identity.loadOrCreate(app)
        val deviceId = Repository.getOrCreateDeviceId(app)
        val now = System.currentTimeMillis()
        val payload = HelloPayload(
            protocol = ProtocolInfo.VERSION,
            features = ProtocolInfo.FEATURES,
            listenPort = SYNC_PORT,
            receiver = receiver,
            reason = reason,
            createdAt = now,
        )
        return try {
            val reply = SyncClient.hello(pc, identity, deviceId, payload, if (quick) 4 else 10)
            val skew = if (reply.serverTime > 0) reply.serverTime - System.currentTimeMillis() else null
            // What the PC can do (e.g. receive files) is relied on only after the PC itself listed it.
            PcFeatures.save(app, pc.deviceId, reply.features)
            last = HelloState(now, true, "принято", skew)
            Log.i(TAG, "hello ($reason, receiver=$receiver) accepted")
            HelloOutcome.OK
        } catch (e: PcRejectedException) {
            last = HelloState(now, false, e.message ?: "отказ", null)
            Log.w(TAG, "hello ($reason) refused: ${e.message}")
            HelloOutcome.GIVE_UP
        } catch (e: Exception) {
            last = HelloState(now, false, NetworkErrors.explain(e), null)
            Log.w(TAG, "hello ($reason) failed", e)
            HelloOutcome.RETRY
        }
    }
}
