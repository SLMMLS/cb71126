package com.clipsync.app.files

import com.clipsync.app.network.NetworkErrors
import com.clipsync.app.network.PinMismatchException
import org.json.JSONException
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** One HTTP exchange with the PC. The phone uses OkHttp with the pinned certificate; the tests use the JDK. */
interface FileTransport {
    /** Posts the sealed [body] to the route of [kind]. Throws [IOException] when there was no answer at all (no route, timeout, TLS). */
    fun post(kind: FileKind, transferId: String, index: Long, body: ByteArray): FileResponse

    class FileResponse(val status: Int, val body: ByteArray)
}

/** A file to send. Reading is sequential from the start, so any source works - a file, a content:// address, a pipe. */
interface FileSource {
    val name: String
    val size: Long
    val modifiedAt: Long? get() = null

    /** Opens the file from its first byte. The caller closes the stream. */
    fun open(): InputStream
}

/** One file on its way to the PC: what to send, and the switches the screen can flip. */
class SendJob(
    val viewId: String,
    transferId: String,
    val source: FileSource,
) {
    /** Changes with a retry: a cancelled transfer starts again under a new id, a failed one continues under the same. */
    @Volatile
    var transferId: String = transferId

    val cancel = AtomicBoolean(false)

    /** May a retry continue the same transfer id (the PC may still hold part of it)? */
    @Volatile
    var canResume = true
}

enum class SendOutcome { DONE, FAILED, CANCELLED }

/**
 * Sending one file to the PC - the Kotlin twin of `filesend.rs` (PROTOCOL.md, section 3c): `offer`
 * (the answer says how much the PC already has), the missing chunks in order, `finish` with the
 * SHA-256 of the whole file. Every reply is opened with the pair's key before it is believed.
 *
 * * **A bad connection is not the end.** A request that fails for a reason that may pass (no
 *   answer, a timeout, a 5xx) is repeated after a short pause, and every repeat starts with a new
 *   `offer`, which makes the PC say how far it got - so no byte is sent twice and none is skipped.
 *   After the last attempt the row says what went wrong and offers "Повторить", which continues
 *   the same transfer.
 * * **A refusal is final.** A "no" that sending again cannot change (unknown device, receiving
 *   switched off, file too big, disk full) stops at once with a sentence the person can act on.
 */
class FileSender(
    private val transport: FileTransport,
    private val key: ByteArray,
    private val registry: TransferRegistry,
    private val pausesMs: List<Long> = listOf(1_000, 2_000, 4_000, 8_000, 15_000),
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val explain: (Throwable) -> String = { NetworkErrors.explain(it) },
) {
    /** Why one attempt ended. */
    private sealed class Attempt {
        object Done : Attempt()

        /** May pass: try again after a pause. */
        class Retry(val why: String) : Attempt()

        /** Will not pass: tell the person. */
        class Fatal(val why: String) : Attempt()

        /** The PC and this phone disagree about where we are: ask again with an `offer`. */
        class Resync(val why: String) : Attempt()

        /** Stopped on purpose: by the person here, or by the PC's person. */
        class Cancelled(val byUs: Boolean, val text: String) : Attempt()
    }

    /** Carries an [Attempt] out of a nested call (the Kotlin way to write Rust's `?`). No stack trace: it is control flow. */
    private class Stop(val attempt: Attempt) : Exception(null, null, false, false)

    /** Runs the job to its end and records the result in the list. Blocks. */
    fun run(job: SendJob): SendOutcome {
        if (job.cancel.get()) return cancelled(job, "Отменено вами", tellPc = false)

        registry.clearSamples(job.viewId)
        registry.update(job.viewId) {
            it.copy(transferId = job.transferId, state = TState.ACTIVE, error = null, finishedAt = null, startedAt = System.currentTimeMillis())
        }

        val outcome = attemptsUntilDone(job)

        return when (outcome) {
            is Attempt.Done -> {
                registry.finish(job.viewId, TState.COMPLETE)
                SendOutcome.DONE
            }
            is Attempt.Cancelled -> cancelled(job, outcome.text, tellPc = outcome.byUs)
            is Attempt.Fatal -> failed(job, outcome.why)
            is Attempt.Retry -> failed(job, outcome.why)
            is Attempt.Resync -> failed(job, outcome.why)
        }
    }

    /** Repeats [attempt] while the reason may pass; returns how the last one ended. */
    private fun attemptsUntilDone(job: SendJob): Attempt {
        var resyncs = 0
        var attemptNo = 0
        while (true) {
            if (job.cancel.get()) return Attempt.Cancelled(true, "Отменено вами")
            when (val a = attempt(job)) {
                is Attempt.Resync -> {
                    resyncs += 1
                    if (resyncs > MAX_RESYNCS) return Attempt.Fatal("ПК и телефон не могут согласовать передачу (${a.why})")
                }
                is Attempt.Retry -> {
                    if (attemptNo >= pausesMs.size) return Attempt.Fatal(a.why)
                    val pause = pausesMs[attemptNo]
                    attemptNo += 1
                    if (!wait(job, pause, a.why, attemptNo, pausesMs.size)) return Attempt.Cancelled(true, "Отменено вами")
                }
                else -> return a
            }
        }
    }

    private fun failed(job: SendJob, text: String): SendOutcome {
        job.canResume = true
        registry.finish(job.viewId, TState.FAILED, text)
        registry.markRetryable(job.viewId, true)
        return SendOutcome.FAILED
    }

    private fun cancelled(job: SendJob, text: String, tellPc: Boolean): SendOutcome {
        job.canResume = false
        // Tell the PC, so that it deletes what it has instead of keeping it for a day.
        if (tellPc) sendCancel(job)
        registry.finish(job.viewId, TState.CANCELLED, text)
        registry.markRetryable(job.viewId, true)
        return SendOutcome.CANCELLED
    }

    /** Sleeps [pauseMs] showing a note on the row; false when the person cancelled meanwhile. */
    private fun wait(job: SendJob, pauseMs: Long, why: String, attemptNo: Int, of: Int): Boolean {
        var left = pauseMs
        while (left > 0) {
            if (job.cancel.get()) return false
            val secs = (left + 999) / 1000
            registry.update(job.viewId) { it.copy(error = "$why Повтор через $secs с (попытка $attemptNo из $of)") }
            val step = minOf(left, 250L)
            sleep(step)
            left -= step
        }
        registry.update(job.viewId) { it.copy(error = null) }
        return !job.cancel.get()
    }

    /** One pass: offer, the missing chunks, finish. */
    private fun attempt(job: SendJob): Attempt = try {
        attemptOrStop(job)
    } catch (stop: Stop) {
        stop.attempt
    } catch (_: JSONException) {
        Attempt.Fatal("ПК ответил в неожиданном формате")
    } catch (e: Exception) {
        // Whatever it is, it must end this transfer with a sentence, not take the worker thread down.
        Attempt.Fatal("Неожиданная ошибка: ${e.message ?: e.javaClass.simpleName}")
    }

    private fun attemptOrStop(job: SendJob): Attempt {
        val source = job.source
        val size = source.size

        // ---- offer ----
        val offer = Offer(source.name, size, FileProtocol.DEFAULT_CHUNK_BYTES, source.modifiedAt)
        val reply = OfferReply.parse(exchange(job, FileKind.OFFER, 0, offer.toJson()))
        if (reply.status == "complete") return Attempt.Done
        if (reply.chunkSize != FileProtocol.DEFAULT_CHUNK_BYTES) {
            return Attempt.Fatal("ПК выбрал другой размер блока, это не поддерживается")
        }
        val chunk = reply.chunkSize.toLong()
        val received = reply.received
        if (received < 0 || received > size || (received != size && received % chunk != 0L)) {
            return Attempt.Fatal("ПК назвал странное место, с которого продолжить")
        }
        registry.progress(job.viewId, received)

        // ---- the file ----
        val sha = MessageDigest.getInstance("SHA-256")
        val input = try {
            source.open()
        } catch (e: Exception) {
            return Attempt.Fatal("Не удалось открыть файл: ${e.message ?: e.javaClass.simpleName}")
        }
        input.use { stream ->
            // The hash must cover everything the PC already has, so that part is read again (not sent).
            val buffer = ByteArray(FileProtocol.DEFAULT_CHUNK_BYTES)
            var left = received
            while (left > 0) {
                if (job.cancel.get()) return Attempt.Cancelled(true, "Отменено вами")
                val n = stream.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                if (n < 0) return Attempt.Fatal("Файл стал короче во время отправки: отправьте его заново")
                sha.update(buffer, 0, n)
                left -= n
            }

            // ---- chunks ----
            var position = received
            var index = received / chunk
            while (position < size) {
                if (job.cancel.get()) return Attempt.Cancelled(true, "Отменено вами")
                val want = minOf(chunk, size - position).toInt()
                var filled = 0
                while (filled < want) {
                    val n = try {
                        stream.read(buffer, filled, want - filled)
                    } catch (e: IOException) {
                        return Attempt.Fatal("Не удалось прочитать файл: ${e.message ?: e.javaClass.simpleName}")
                    }
                    if (n < 0) return Attempt.Fatal("Файл стал короче во время отправки: отправьте его заново")
                    filled += n
                }
                sha.update(buffer, 0, want)
                val plain = if (want == buffer.size) buffer else buffer.copyOf(want)
                val ack = ChunkReply.parse(exchange(job, FileKind.CHUNK, index, plain))
                position += want
                if (ack.received != position) {
                    return Attempt.Resync("ПК хранит ${ack.received} байт, ожидалось $position")
                }
                index += 1
                registry.progress(job.viewId, position)
            }
        }

        // ---- finish ----
        val done = FinishReply.parse(exchange(job, FileKind.FINISH, 0, Finish(FileProtocol.hex(sha.digest())).toJson()))
        return if (done.status == "complete") Attempt.Done else Attempt.Fatal("ПК ответил на завершение непонятно")
    }

    /** Seals [plain], posts it and opens the reply. Throws [Stop] with the reason when it cannot. */
    private fun exchange(job: SendJob, kind: FileKind, index: Long, plain: ByteArray): ByteArray {
        val sealed = FileProtocol.sealRequest(key, kind, job.transferId, index, plain)
        val response = try {
            transport.post(kind, job.transferId, index, sealed)
        } catch (e: IOException) {
            // A certificate that is not the one from the QR is not a bad moment on the network: retrying cannot fix it.
            if (generateSequence<Throwable>(e) { it.cause?.takeIf { cause -> cause !== it } }.take(8).any { it is PinMismatchException }) {
                throw Stop(Attempt.Fatal(explain(e)))
            }
            throw Stop(Attempt.Retry(describeTransport(e)))
        }
        if (response.status != 200) throw Stop(Companion.classify(kind, response.status, String(response.body, Charsets.UTF_8)))
        // A reply that does not open with the pair's key is not from the PC we think it is.
        val opened = try {
            FileProtocol.openReply(key, kind, job.transferId, index, response.body)
        } catch (_: Exception) {
            throw Stop(Attempt.Fatal("Ответ ПК не удалось проверить: это не тот компьютер или сопряжение устарело"))
        }
        return opened
    }

    private fun describeTransport(e: IOException): String =
        if (e is SocketTimeoutException) "ПК не ответил вовремя." else "Нет связи с ПК (${explain(e)})."

    /** Best effort and short: the person already pressed cancel. */
    private fun sendCancel(job: SendJob) {
        try {
            val sealed = FileProtocol.sealRequest(key, FileKind.CANCEL, job.transferId, 0, CancelMessage.toJson())
            transport.post(FileKind.CANCEL, job.transferId, 0, sealed)
        } catch (_: Exception) {
        }
    }

    companion object {
        /** How often a transfer may be re-synchronised (`409`, `410`) before it is given up on. */
        const val MAX_RESYNCS = 6

        /**
         * What an answer other than 200 means for the transfer (PROTOCOL.md, section 3c), as a word for the
         * tests: `retry`, `fatal`, `resync` or `cancelled-by-pc`.
         */
        internal fun classifyForTest(kind: FileKind, status: Int): String = when (classify(kind, status, "")) {
            is Attempt.Retry -> "retry"
            is Attempt.Fatal -> "fatal"
            is Attempt.Resync -> "resync"
            is Attempt.Cancelled -> "cancelled-by-pc"
            is Attempt.Done -> "done"
        }

        private fun classify(kind: FileKind, status: Int, body: String): Attempt {
            val detail = body.take(160)
            return when {
                status == 408 || status == 429 || status == 500 || status == 502 || status == 503 || status == 504 ->
                    Attempt.Retry(if (status == 503) "ПК занят другими файлами." else "ПК ответил «$status».")
                status == 409 && kind != FileKind.OFFER -> Attempt.Resync(detail)
                status == 410 && kind == FileKind.OFFER -> Attempt.Cancelled(false, "ПК отменил эту передачу")
                status == 410 -> Attempt.Resync("ПК потерял передачу")
                status == 413 -> Attempt.Fatal("Файл слишком большой для ПК")
                status == 422 -> Attempt.Retry("Файл дошёл с повреждением (не сошлась контрольная сумма), пробую заново.")
                status == 423 -> Attempt.Fatal("На ПК выключен приём файлов")
                status == 403 -> Attempt.Fatal("ПК не узнал этот телефон: сопряжение потеряно. Сопрягите устройства заново.")
                status == 404 -> Attempt.Fatal("ClipSync на ПК не умеет принимать файлы: обновите его")
                status == 507 -> Attempt.Fatal("На ПК не удалось сохранить файл (нет места?) $detail")
                status == 400 -> Attempt.Fatal("ПК не принял запрос: $detail")
                else -> Attempt.Fatal("ПК ответил кодом $status $detail")
            }
        }
    }
}
