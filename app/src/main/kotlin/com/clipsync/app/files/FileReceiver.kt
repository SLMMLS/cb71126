package com.clipsync.app.files

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest

/** Who is on the other end of a request: already authenticated as the paired PC by the caller, with the transport key shared with it. */
class Peer(val deviceId: String, val key: ByteArray)

/** Where a finished file goes. On the phone this is the public Downloads folder; the tests use a plain folder. */
interface FinalSink {
    /**
     * Puts the finished [partial] file in its final place, under a free name based on [name], and
     * returns the name used and an address the screen can open. The receiver deletes [partial]
     * afterwards if it is still there, so a sink may move it or copy it.
     */
    fun publish(partial: File, name: String, modifiedAt: Long?): Published

    class Published(val name: String, val location: String)
}

/** The rules for the next incoming file, read on every offer so that a change in the settings applies at once. */
class ReceivePolicy(
    /** `false`: refuse every offer (the user switched receiving files off). */
    val acceptIncoming: Boolean,
    /** Largest file accepted (also capped by [FileProtocol.MAX_FILE_BYTES]). */
    val maxBytes: Long,
    /** Free space for the file, checked at the offer so that a full phone says so before the transfer, not after. */
    val freeBytes: Long = Long.MAX_VALUE,
)

/** Hears of finished receptions; the phone shows a notification. */
interface ReceiveListener {
    fun received(name: String, location: String)
    fun failed(name: String, reason: String)
}

/**
 * Receiving files - the Kotlin twin of the receiving half of `filehub.rs` (PROTOCOL.md, section 3c).
 *
 * The sender announces a file (`offer`), sends it in chunks (`chunk`) and closes with the SHA-256
 * of the whole file (`finish`). The bytes are kept in a *partial file* in the app's own folder, so
 * a half-received file never shows up under its real name, and are published to Downloads only
 * when the hash matched. Everything a sender controls - the name, the sizes, the order of the
 * chunks - is checked before it is used.
 *
 * Every request can be repeated (replies get lost on Wi-Fi): a chunk that is stored already is
 * answered with the current position, a `finish` for a completed transfer with the same result,
 * an `offer` for a partial transfer with how much of it is there - which is how a transfer resumes.
 *
 * Locking: one lock guards the bookkeeping; each reception has its own monitor for the data that
 * changes with every chunk. The rule that keeps them from deadlocking: **never wait for a
 * reception's monitor while holding the bookkeeping lock** (the other order is used when a
 * reception starts).
 */
class FileReceiver(
    dataDir: File,
    private val registry: TransferRegistry,
    private val sink: FinalSink,
    private val policy: () -> ReceivePolicy,
    private val listener: ReceiveListener? = null,
    private val clockNanos: () -> Long = { System.nanoTime() },
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
) {
    private val partialDir = File(dataDir, PARTIAL_DIR)

    private data class Key(val device: String, val transfer: String)

    private class Completed(val savedAs: String, val size: Long)

    /** What changes with every chunk of one reception. */
    private class Incoming(
        val offeredName: String,
        val name: String,
        val size: Long,
        val chunkSize: Int,
        val modifiedAt: Long?,
        val part: File,
    ) {
        /** The transfer has ended (finished, cancelled, expired): late requests are answered "gone". */
        var dead = false
        var received = 0L
        var sha: MessageDigest = MessageDigest.getInstance("SHA-256")

        /** The hash of the whole file, computed once when the last chunk is in (the digest can be read only once). */
        var finalHash: String? = null
    }

    /** What the bookkeeping needs to know about a reception, without waiting for its monitor. */
    private class Slot(val inc: Incoming, val viewId: String, var lastActivity: Long)

    private val lock = Any()
    private val incoming = HashMap<Key, Slot>()
    private val completed = ArrayDeque<Pair<Key, Completed>>()

    /** Receptions the *user of this phone* cancelled: the sender must not be able to restart them. */
    private val cancelled = ArrayDeque<Key>()

    init {
        cleanOldPartials()
    }

    private fun partialFile(device: String, transfer: String) =
        File(partialDir, "${fileSafe(device)}-${fileSafe(transfer)}.part")

    /** Letters, digits and `-` only: a device id ends up in a file name. */
    private fun fileSafe(text: String): String =
        text.map { if (it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-') it else '_' }.joinToString("").take(48)

    private fun cleanOldPartials() {
        val now = clockMillis()
        partialDir.listFiles()?.forEach { f ->
            if (f.isFile && now - f.lastModified() > PARTIAL_TTL_MS) f.delete()
        }
    }

    /** How many receptions are in progress (tests and diagnostics). */
    fun receivingNow(): Int = synchronized(lock) { incoming.size }

    fun partialPath(device: String, transfer: String): File = partialFile(device, transfer)

    // -----------------------------------------------------------------------------------------
    // One request
    // -----------------------------------------------------------------------------------------

    /** Handles one authenticated request from [peer]; the reply is already sealed. Throws [FileException] for a refusal. */
    fun handle(peer: Peer, kind: FileKind, transferId: String, index: Long, body: ByteArray): ByteArray =
        when (kind) {
            FileKind.OFFER -> onOffer(peer, transferId, body)
            FileKind.CHUNK -> onChunk(peer, transferId, index, body)
            FileKind.FINISH -> onFinish(peer, transferId, body)
            FileKind.CANCEL -> onCancel(peer, transferId, body)
        }

    private fun open(peer: Peer, kind: FileKind, transferId: String, index: Long, body: ByteArray): ByteArray =
        try {
            FileProtocol.openRequest(peer.key, kind, transferId, index, body)
        } catch (_: Exception) {
            throw FileException(403, "could not authenticate payload")
        }

    private fun reply(peer: Peer, kind: FileKind, transferId: String, index: Long, json: ByteArray): ByteArray =
        FileProtocol.sealReply(peer.key, kind, transferId, index, json)

    private fun bad(message: String) = FileException(400, message)

    private fun viewId(device: String, transfer: String) = "in-$device-$transfer"

    private fun onOffer(peer: Peer, transferId: String, body: ByteArray): ByteArray {
        val plain = open(peer, FileKind.OFFER, transferId, 0, body)
        val offer = try {
            Offer.parse(plain)
        } catch (e: Exception) {
            throw bad("malformed offer: ${e.message}")
        }

        val rules = policy()
        if (!rules.acceptIncoming) throw FileException(423, "receiving files is switched off on this device")
        val limit = minOf(rules.maxBytes, FileProtocol.MAX_FILE_BYTES)
        if (offer.size < 0 || offer.size > limit) {
            throw FileException(413, "the file is larger than this device accepts (${limit / 1_000_000} MB)")
        }
        if (offer.chunkSize < FileProtocol.MIN_CHUNK_BYTES || offer.chunkSize > FileProtocol.MAX_CHUNK_BYTES) {
            throw bad("chunk_size is out of range")
        }
        if (offer.name.length > 4096) throw bad("name is too long")

        expireIdle()

        val key = Key(peer.deviceId, transferId)
        val safeName = FileNames.sanitize(offer.name)

        // 1. Outcomes that are remembered, and a reception that is already running.
        val existing: Incoming? = synchronized(lock) {
            if (key in cancelled) throw FileException(410, "this transfer was cancelled on the receiving device")
            completed.firstOrNull { it.first == key }?.let { (_, done) ->
                return reply(peer, FileKind.OFFER, transferId, 0, OfferReply("complete", done.size, offer.chunkSize, done.savedAs).toJson())
            }
            incoming[key]?.also { it.lastActivity = clockNanos() }?.inc
        }
        if (existing != null) {
            synchronized(existing) {
                if (existing.dead) throw FileException(503, "the transfer is being closed, try again")
                if (existing.offeredName != offer.name || existing.size != offer.size || existing.chunkSize != offer.chunkSize) {
                    throw FileException(409, "a transfer with this id exists with different parameters")
                }
                return reply(peer, FileKind.OFFER, transferId, 0, OfferReply("ready", existing.received, existing.chunkSize).toJson())
            }
        }

        // 2. A new reception. The slot is registered while its owner still holds its monitor, so a
        //    second offer for the same id waits instead of starting a second copy.
        val part = partialFile(peer.deviceId, transferId)
        val inc = Incoming(offer.name, safeName, offer.size, offer.chunkSize, offer.modifiedAt, part)
        synchronized(inc) {
            synchronized(lock) {
                val now = clockNanos()
                val active = incoming.filter { now - it.value.lastActivity < ACTIVE_WINDOW_NANOS }
                val own = active.count { it.key.device == peer.deviceId }
                if (own >= MAX_ACTIVE_PER_DEVICE || active.size >= MAX_ACTIVE_TOTAL) {
                    throw FileException(503, "too many files are being received right now, try again in a moment")
                }
                incoming[key] = Slot(inc, viewId(peer.deviceId, transferId), now)
            }

            // Space is checked for what is still to come, not for what an earlier attempt already holds.
            val already = if (part.isFile) minOf(part.length(), offer.size) else 0L
            if (rules.freeBytes < offer.size - already + FREE_SPACE_RESERVE) {
                inc.dead = true
                synchronized(lock) { incoming.remove(key) }
                throw FileException(507, "not enough free space on the phone for this file")
            }

            // 3. Pick up what is left of an earlier attempt (or start an empty file).
            try {
                val (received, sha) = initPartial(part, offer.size, offer.chunkSize)
                inc.received = received
                inc.sha = sha
            } catch (e: IOException) {
                inc.dead = true
                synchronized(lock) { incoming.remove(key) }
                throw FileException(507, "cannot store the file: ${e.message}")
            }

            // 4. The list: a new row, or the old row of this transfer, running again.
            val id = viewId(peer.deviceId, transferId)
            val now = clockMillis()
            registry.clearSamples(id)
            if (registry.get(id) != null) {
                registry.update(id, saveToDisk = true) {
                    it.copy(
                        name = safeName,
                        size = offer.size,
                        transferred = inc.received,
                        state = TState.ACTIVE,
                        error = null,
                        finishedAt = null,
                        rateBps = 0,
                    )
                }
            } else {
                registry.add(
                    TransferView(
                        id = id, transferId = transferId, deviceId = peer.deviceId, direction = Direction.IN, name = safeName, size = offer.size,
                        transferred = inc.received, state = TState.ACTIVE, startedAt = now, updatedAt = now,
                    ),
                )
            }
            return reply(peer, FileKind.OFFER, transferId, 0, OfferReply("ready", inc.received, inc.chunkSize).toJson())
        }
    }

    /** The reception that is running for this request, or why there is none. */
    private fun running(peer: Peer, transferId: String): Incoming {
        val key = Key(peer.deviceId, transferId)
        synchronized(lock) {
            incoming[key]?.let {
                it.lastActivity = clockNanos()
                return it.inc
            }
            if (key in cancelled) throw FileException(410, "this transfer was cancelled on the receiving device")
        }
        throw FileException(410, "unknown or expired transfer: offer it again")
    }

    private fun alreadyCompleted(peer: Peer, transferId: String): Completed? {
        val key = Key(peer.deviceId, transferId)
        return synchronized(lock) { completed.firstOrNull { it.first == key }?.second }
    }

    private fun onChunk(peer: Peer, transferId: String, index: Long, body: ByteArray): ByteArray {
        // Authenticate before anything else: the body is sealed together with the chunk number, so a
        // chunk moved to another position (or another transfer) fails right here.
        val data = open(peer, FileKind.CHUNK, transferId, index, body)

        val inc = try {
            running(peer, transferId)
        } catch (gone: FileException) {
            // The last chunk may have been stored and its reply lost; the sender then asks again.
            val done = alreadyCompleted(peer, transferId) ?: throw gone
            return reply(peer, FileKind.CHUNK, transferId, index, ChunkReply(done.size).toJson())
        }

        val received: Long
        synchronized(inc) {
            if (inc.dead) throw FileException(410, "this transfer is over")
            val chunk = inc.chunkSize.toLong()
            if (index < 0 || index > Long.MAX_VALUE / chunk) throw bad("chunk number is out of range")
            val start = index * chunk
            if (start > inc.received) {
                throw FileException(409, "out of order: ${inc.received} bytes are stored, this chunk starts at $start")
            }
            if (start >= inc.size && inc.size > 0 && start == inc.received) {
                throw FileException(409, "the file is already complete: finish it")
            }
            val expected = minOf(chunk, maxOf(0L, inc.size - start))
            if (data.size.toLong() != expected) throw bad("chunk has ${data.size} bytes, $expected expected")
            if (start < inc.received) {
                // Stored already (the reply got lost). The bytes are not compared: they were sealed for this
                // very position, and a sender that changed its mind would fail at `finish`.
                return reply(peer, FileKind.CHUNK, transferId, index, ChunkReply(inc.received).toJson())
            }

            if (inc.part.length() != inc.received) {
                // Somebody touched the partial file. Do not build on it: the sender starts again.
                inc.dead = true
                inc.part.delete()
                val gone = synchronized(lock) { incoming.remove(Key(peer.deviceId, transferId)) }
                if (gone != null) registry.finish(gone.viewId, TState.FAILED, "Файл частичной загрузки был изменён")
                throw FileException(410, "the partial file changed; offer the transfer again")
            }
            try {
                FileOutputStream(inc.part, true).use { it.write(data) }
            } catch (e: IOException) {
                throw FileException(507, "cannot write the file: ${e.message}")
            }
            inc.sha.update(data)
            inc.received += data.size
            received = inc.received
        }

        registry.progress(viewId(peer.deviceId, transferId), received)
        return reply(peer, FileKind.CHUNK, transferId, index, ChunkReply(received).toJson())
    }

    private fun onFinish(peer: Peer, transferId: String, body: ByteArray): ByteArray {
        val plain = open(peer, FileKind.FINISH, transferId, 0, body)
        val finish = try {
            Finish.parse(plain)
        } catch (e: Exception) {
            throw bad("malformed finish: ${e.message}")
        }

        val inc = try {
            running(peer, transferId)
        } catch (gone: FileException) {
            val done = alreadyCompleted(peer, transferId) ?: throw gone
            return reply(peer, FileKind.FINISH, transferId, 0, FinishReply("complete", done.savedAs, done.size).toJson())
        }

        synchronized(inc) {
            if (inc.dead) throw FileException(410, "this transfer is over")
            if (inc.received != inc.size) {
                throw FileException(409, "incomplete: ${inc.received} of ${inc.size} bytes are stored")
            }
            val key = Key(peer.deviceId, transferId)
            val id = viewId(peer.deviceId, transferId)
            val digest = inc.finalHash ?: FileProtocol.hex(inc.sha.digest()).also { inc.finalHash = it }
            if (!digest.equals(finish.sha256.trim(), ignoreCase = true)) {
                inc.dead = true
                inc.part.delete()
                synchronized(lock) { incoming.remove(key) }
                registry.finish(id, TState.FAILED, "Контрольная сумма не сошлась: файл повреждён при передаче")
                listener?.failed(inc.name, "контрольная сумма не сошлась")
                throw FileException(422, "the checksum does not match: the file was damaged on the way")
            }

            // The hash matched: put the file where the person will find it.
            val published = try {
                sink.publish(inc.part, inc.name, inc.modifiedAt)
            } catch (e: Exception) {
                // The partial file is kept, so a retry after the cause is gone (space, permission) works.
                throw FileException(507, "cannot save the file: ${e.message}")
            }
            if (inc.part.exists()) inc.part.delete()
            inc.dead = true
            synchronized(lock) {
                incoming.remove(key)
                completed.addLast(key to Completed(published.name, inc.size))
                while (completed.size > MAX_REMEMBERED) completed.removeFirst()
            }
            registry.update(id) { it.copy(name = published.name) }
            registry.finish(id, TState.COMPLETE, null, published.location)
            listener?.received(published.name, published.location)
            return reply(peer, FileKind.FINISH, transferId, 0, FinishReply("complete", published.name, inc.size).toJson())
        }
    }

    private fun onCancel(peer: Peer, transferId: String, body: ByteArray): ByteArray {
        val plain = open(peer, FileKind.CANCEL, transferId, 0, body)
        try {
            CancelMessage.parse(plain)
        } catch (e: Exception) {
            throw bad("malformed cancel: ${e.message}")
        }
        val slot = synchronized(lock) { incoming.remove(Key(peer.deviceId, transferId)) }
        if (slot != null) discard(slot, TState.CANCELLED, "Отменено отправителем", remember = null)
        // Cancelling something that is not there is fine: the sender only wants it gone.
        return reply(peer, FileKind.CANCEL, transferId, 0, CancelMessage.toJson())
    }

    // -----------------------------------------------------------------------------------------
    // The user of this phone acts on a reception
    // -----------------------------------------------------------------------------------------

    /** "Отменить" on an incoming transfer. The sender learns of it from its next request (410) and cannot start the same transfer again. */
    fun cancelIncoming(viewId: String): Boolean {
        val view = registry.get(viewId)
        if (view == null || view.direction != Direction.IN || view.state.isTerminal) return false
        val key = Key(view.deviceId, view.transferId)
        val slot = synchronized(lock) { incoming.remove(key) } ?: return false
        discard(slot, TState.CANCELLED, "Отменено вами", remember = key)
        return true
    }

    /** The PC was unpaired: stop everything that involves it. */
    fun dropAll() {
        val slots = synchronized(lock) {
            val all = incoming.values.toList()
            incoming.clear()
            all
        }
        for (slot in slots) discard(slot, TState.CANCELLED, "ПК отвязан", remember = null)
    }

    /** Ends a reception that was already taken out of the map. */
    private fun discard(slot: Slot, state: TState, error: String, remember: Key?) {
        synchronized(slot.inc) {
            slot.inc.dead = true
            slot.inc.part.delete()
        }
        if (remember != null) {
            synchronized(lock) {
                cancelled.addLast(remember)
                while (cancelled.size > MAX_REMEMBERED) cancelled.removeFirst()
            }
        }
        registry.finish(slot.viewId, state, error)
    }

    /** Receptions silent for longer than [PARTIAL_TTL_MS] are forgotten and their partial files deleted. */
    private fun expireIdle() {
        val now = clockNanos()
        val expired = synchronized(lock) {
            val keys = incoming.filter { now - it.value.lastActivity > PARTIAL_TTL_MS * 1_000_000L }.keys.toList()
            keys.mapNotNull { incoming.remove(it) }
        }
        for (slot in expired) discard(slot, TState.FAILED, "Передача давно не продолжалась и была удалена", remember = null)
    }

    companion object {
        const val PARTIAL_DIR = "incoming"
        private const val MAX_REMEMBERED = 256
        private const val MAX_ACTIVE_PER_DEVICE = 3
        private const val MAX_ACTIVE_TOTAL = 6
        private const val ACTIVE_WINDOW_NANOS = 5L * 60 * 1_000_000_000
        private const val PARTIAL_TTL_MS = 24L * 60 * 60 * 1000

        /** Kept free after a file, so that the phone is not filled to the last byte. */
        const val FREE_SPACE_RESERVE = 64L * 1024 * 1024

        /**
         * Opens (or creates) the partial file of a transfer and returns how much of it can be trusted and
         * the hash state of those bytes. A partial file longer than the offer says, or one that ends in
         * the middle of a chunk, is cut back to a chunk boundary: only whole chunks were ever
         * acknowledged, so the rest was never promised to the sender.
         */
        internal fun initPartial(part: File, size: Long, chunkSize: Int): Pair<Long, MessageDigest> {
            part.parentFile?.mkdirs()
            val sha = MessageDigest.getInstance("SHA-256")
            if (!part.isFile) {
                if (!part.createNewFile() && !part.isFile) throw IOException("cannot create ${part.name}")
                return 0L to sha
            }
            val chunk = chunkSize.toLong()
            val usable = minOf(part.length(), size)
            val received = if (usable == size) size else usable / chunk * chunk
            RandomAccessFile(part, "rw").use { it.setLength(received) }

            val buffer = ByteArray(256 * 1024)
            var left = received
            FileInputStream(part).use { input ->
                while (left > 0) {
                    val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                    if (n < 0) {
                        // The file is shorter than its length claimed: start from nothing.
                        RandomAccessFile(part, "rw").use { it.setLength(0) }
                        return 0L to MessageDigest.getInstance("SHA-256")
                    }
                    sha.update(buffer, 0, n)
                    left -= n
                }
            }
            return received to sha
        }
    }
}
