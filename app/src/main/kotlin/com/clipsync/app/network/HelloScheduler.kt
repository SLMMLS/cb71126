package com.clipsync.app.network

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** What one attempt to say hello came to. */
enum class HelloOutcome {
    /** The PC took it. */
    OK,

    /** The PC could not be reached (no network yet, PC asleep, ...): worth another try shortly. */
    RETRY,

    /** The PC answered with a refusal, or there is nobody to tell: repeating the same message changes nothing. */
    GIVE_UP,
}

/**
 * Decides when "I'm here" messages (PROTOCOL.md §3b) actually go out.
 *
 * Android reports network changes in bursts - the same Wi-Fi announces itself two or three
 * times within a second, the first report often arrives before the connection is usable - and
 * the screen is opened many times a day. So:
 *
 * * requests are **coalesced**: whatever arrives within [debounceMs] of the first becomes one hello;
 * * a hello that just succeeded suppresses routine requests for [minIntervalMs];
 * * a failed hello is **retried** after each of [retryDelaysMs] and then given up on - the
 *   next network change or app start tries again, so nothing loops forever;
 * * a request that arrives **while a hello is being sent** is not lost: it is sent afterwards,
 *   because what just happened (a new network) may not be in the message already on its way;
 * * an **urgent** request (the user switched receiving on or off) goes out at once and replaces
 *   whatever was waiting.
 *
 * [executor] must run one task at a time (a single-thread scheduled executor): this class relies
 * on attempts never overlapping. It is not shut down by [shutdown]; its owner does that.
 * Everything here is plain JDK, so the timing rules are tested on a JVM without a device.
 */
class HelloScheduler(
    private val executor: ScheduledExecutorService,
    private val send: (reason: String) -> HelloOutcome,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val debounceMs: Long = 1_500,
    private val retryDelaysMs: List<Long> = listOf(3_000L, 10_000L, 30_000L),
    private val minIntervalMs: Long = 15_000,
) {
    private val lock = Any()
    private var pending: ScheduledFuture<*>? = null
    private var running = false
    private var generation = 0L
    private var reason = ""
    private var dirtyReason: String? = null
    private var lastOkAt: Long? = null
    private var closed = false

    /** Asks for a hello. Cheap and safe to call from any thread, as often as you like. */
    fun request(reason: String, urgent: Boolean = false) {
        synchronized(lock) {
            if (closed) return
            if (urgent) {
                schedule(reason, 0L, 0)
                return
            }
            if (running) {
                dirtyReason = reason
                return
            }
            if (pending != null) {
                this.reason = reason // the waiting attempt carries the newest reason
                return
            }
            val ok = lastOkAt
            if (ok != null && clock() - ok < minIntervalMs) return
            schedule(reason, debounceMs, 0)
        }
    }

    /** Stops everything that is still waiting. Further [request]s are ignored. */
    fun shutdown() {
        synchronized(lock) {
            closed = true
            pending?.cancel(false)
            pending = null
        }
    }

    /** Callers hold [lock]. */
    private fun schedule(reason: String, delayMs: Long, attempt: Int) {
        pending?.cancel(false)
        generation++
        val mine = generation
        this.reason = reason
        pending = try {
            executor.schedule({ run(attempt, mine) }, delayMs, TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            null // the owner shut the executor down: nothing left to do
        }
    }

    private fun run(attempt: Int, mine: Long) {
        var sendReason = ""
        synchronized(lock) {
            if (closed || mine != generation) return
            running = true
            dirtyReason = null
            sendReason = reason
        }
        val outcome = try {
            send(sendReason)
        } catch (e: Exception) {
            HelloOutcome.RETRY
        }
        synchronized(lock) {
            running = false
            if (closed) return
            if (outcome == HelloOutcome.OK) lastOkAt = clock()
            if (mine != generation) return // an urgent request took over while this one was sending
            pending = null
            val again = dirtyReason
            dirtyReason = null
            if (outcome == HelloOutcome.RETRY) {
                val delay = retryDelaysMs.getOrNull(attempt)
                if (delay != null) {
                    schedule(again ?: reason, delay, attempt + 1)
                } else if (again != null) {
                    schedule(again, debounceMs, 0)
                }
            } else if (again != null) {
                schedule(again, debounceMs, 0)
            }
        }
    }
}
