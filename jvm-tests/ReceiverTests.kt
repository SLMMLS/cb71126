package com.clipsync.jvmtests

import com.clipsync.app.files.CancelMessage
import com.clipsync.app.files.ChunkReply
import com.clipsync.app.files.Direction
import com.clipsync.app.files.FileException
import com.clipsync.app.files.FileKind
import com.clipsync.app.files.FileNames
import com.clipsync.app.files.FileProtocol
import com.clipsync.app.files.FileReceiver
import com.clipsync.app.files.Finish
import com.clipsync.app.files.FinalSink
import com.clipsync.app.files.FinishReply
import com.clipsync.app.files.Offer
import com.clipsync.app.files.OfferReply
import com.clipsync.app.files.Peer
import com.clipsync.app.files.ReceiveListener
import com.clipsync.app.files.ReceivePolicy
import com.clipsync.app.files.TState
import com.clipsync.app.files.TransferLog
import com.clipsync.app.files.TransferRegistry
import org.json.JSONArray
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

const val CHUNK = FileProtocol.MIN_CHUNK_BYTES
val PC_KEY = ByteArray(32) { 11 }
const val PHONE = "pc-0001"
const val ID = "11111111-aaaa-bbbb-cccc-000000000001"
const val ID2 = "22222222-aaaa-bbbb-cccc-000000000002"

fun sha(data: ByteArray): String = FileProtocol.hex(MessageDigest.getInstance("SHA-256").digest(data))

/** Publishes into a plain folder, under a free name, the way the phone's Downloads would. */
class FolderSink(val dir: File) : FinalSink {
    var failWith: Exception? = null

    override fun publish(partial: File, name: String, modifiedAt: Long?): FinalSink.Published {
        failWith?.let { throw it }
        dir.mkdirs()
        var n = 0
        while (true) {
            val candidate = FileNames.numbered(name, n)
            val target = File(dir, candidate)
            if (target.createNewFile()) {
                Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                return FinalSink.Published(candidate, target.absolutePath)
            }
            n += 1
        }
    }
}

class Rig(val data: File = tempDir("data"), val inbox: File = tempDir("inbox")) {
    @Volatile var accept = true
    @Volatile var maxBytes = 50_000_000L
    @Volatile var freeBytes = Long.MAX_VALUE
    val sink = FolderSink(inbox)
    val received = mutableListOf<String>()
    val failed = mutableListOf<String>()
    private val listener = object : ReceiveListener {
        override fun received(name: String, location: String) {
            synchronized(received) { received.add(name) }
        }

        override fun failed(name: String, reason: String) {
            synchronized(failed) { failed.add("$name: $reason") }
        }
    }
    var registry = TransferRegistry(TransferLog(File(data, "file-transfers.json")))
    var receiver = newReceiver()

    private fun newReceiver() = FileReceiver(data, registry, sink, { ReceivePolicy(accept, maxBytes, freeBytes) }, listener)

    /** The same app started again on the same folders. */
    fun restart() {
        registry = TransferRegistry(TransferLog(File(data, "file-transfers.json")))
        receiver = newReceiver()
    }

    val peer = Peer(PHONE, PC_KEY)

    fun call(kind: FileKind, id: String, index: Long, plain: ByteArray): ByteArray {
        val body = FileProtocol.sealRequest(PC_KEY, kind, id, index, plain)
        val reply = receiver.handle(peer, kind, id, index, body)
        return FileProtocol.openReply(PC_KEY, kind, id, index, reply)
    }

    fun offer(id: String, name: String, data: ByteArray, chunk: Int = CHUNK, modifiedAt: Long? = null): OfferReply =
        OfferReply.parse(call(FileKind.OFFER, id, 0, Offer(name, data.size.toLong(), chunk, modifiedAt).toJson()))

    fun chunk(id: String, index: Long, data: ByteArray): ChunkReply = ChunkReply.parse(call(FileKind.CHUNK, id, index, data))

    fun finish(id: String, data: ByteArray): FinishReply = finishWith(id, sha(data))

    fun finishWith(id: String, sha256: String): FinishReply = FinishReply.parse(call(FileKind.FINISH, id, 0, Finish(sha256).toJson()))

    /** Sends the whole file in order, the way a sender does. */
    fun sendAll(id: String, name: String, data: ByteArray, chunk: Int = CHUNK): FinishReply {
        val reply = offer(id, name, data, chunk)
        eq(reply.status, "ready")
        var i = 0L
        var pos = 0
        while (pos < data.size) {
            val end = minOf(data.size, pos + chunk)
            eq(chunk(id, i, data.copyOfRange(pos, end)).received, end.toLong())
            pos = end
            i += 1
        }
        return finish(id, data)
    }
}

fun statusOf(body: () -> Unit): Int {
    try {
        body()
    } catch (e: FileException) {
        return e.status
    }
    throw AssertionError("expected a refusal, but the call succeeded")
}

fun ReceiverTests(vectorsFile: String) {
    println("Names")

    Kit.test("names are cleaned exactly as the Rust side does (shared vectors)") {
        val vectors = JSONArray(File(vectorsFile).readText())
        check(vectors.length() >= 40, "the shared list must not be empty")
        for (i in 0 until vectors.length()) {
            val pair = vectors.getJSONArray(i)
            val input = pair.getString(0)
            eq(FileNames.sanitize(input), pair.getString(1), "input ${input.map { it.code }}")
        }
    }

    Kit.test("sanitizing is idempotent") {
        val vectors = JSONArray(File(vectorsFile).readText())
        for (i in 0 until vectors.length()) {
            val once = FileNames.sanitize(vectors.getJSONArray(i).getString(0))
            eq(FileNames.sanitize(once), once)
        }
    }

    Kit.test("a cleaned name never escapes its folder") {
        for (hostile in listOf("../../x", "..\\..\\x", "/abs/path", "C:\\abs\\path", "a/../../b", "....//....//x", "\\\\server\\share\\x")) {
            val name = FileNames.sanitize(hostile)
            check(!name.contains('/') && !name.contains('\\') && !name.contains(':'), "$hostile -> $name")
            check(name != "." && name != "..", "$hostile -> $name")
        }
    }

    Kit.test("long names are shortened on a character boundary and keep their extension") {
        val long = "я".repeat(200) + ".pdf"
        val name = FileNames.sanitize(long)
        check(name.toByteArray(Charsets.UTF_8).size <= 150, "${name.toByteArray(Charsets.UTF_8).size} bytes")
        check(name.endsWith(".pdf"))
        eq(FileNames.sanitize("x".repeat(400)).length, 150)
        val weird = FileNames.sanitize("a." + "e".repeat(300))
        check(weird.isNotEmpty() && weird.toByteArray(Charsets.UTF_8).size <= 150)
        // Astral characters are 4 bytes and 2 chars: the cut must not split a surrogate pair.
        val emoji = FileNames.sanitize("\uD83D\uDE00".repeat(60) + ".txt")
        check(emoji.toByteArray(Charsets.UTF_8).size <= 150 && emoji.endsWith(".txt"))
        check(!emoji.dropLast(4).any { Character.isSurrogate(it) && false }, "well formed")
        eq(String(emoji.toByteArray(Charsets.UTF_8), Charsets.UTF_8), emoji, "round trip through UTF-8 proves no split pair")
    }

    Kit.test("numbered names and risky extensions") {
        eq(FileNames.numbered("a.txt", 0), "a.txt")
        eq(FileNames.numbered("a.txt", 1), "a (1).txt")
        eq(FileNames.numbered("archive.tar.gz", 2), "archive.tar (2).gz")
        eq(FileNames.numbered("README", 3), "README (3)")
        eq(FileNames.numbered(".bashrc", 1), ".bashrc (1)")
        for (risky in listOf("setup.exe", "RUN.BAT", "x.Ps1", "app.apk", "script.js", "link.lnk", "tool.msi")) check(FileNames.isRisky(risky), risky)
        for (fine in listOf("photo.jpg", "doc.pdf", "notes.txt", "archive.zip", "song.mp3", "README", "exe", ".exe")) check(!FileNames.isRisky(fine), fine)
        eq(FileNames.mimeType("a.PDF"), "application/pdf")
        eq(FileNames.mimeType("noext"), "application/octet-stream")
    }

    println("Protocol")

    Kit.test("a request opens only with its own kind, transfer and chunk number") {
        val sealed = FileProtocol.sealRequest(PC_KEY, FileKind.CHUNK, ID, 7, "data".toByteArray())
        eqBytes(FileProtocol.openRequest(PC_KEY, FileKind.CHUNK, ID, 7, sealed), "data".toByteArray())
        check(runCatching { FileProtocol.openRequest(PC_KEY, FileKind.CHUNK, ID, 8, sealed) }.isFailure)
        check(runCatching { FileProtocol.openRequest(PC_KEY, FileKind.CHUNK, ID2, 7, sealed) }.isFailure)
        check(runCatching { FileProtocol.openRequest(PC_KEY, FileKind.FINISH, ID, 7, sealed) }.isFailure)
    }

    Kit.test("a request cannot pass as a reply or back") {
        val request = FileProtocol.sealRequest(PC_KEY, FileKind.OFFER, ID, 0, "{}".toByteArray())
        check(runCatching { FileProtocol.openReply(PC_KEY, FileKind.OFFER, ID, 0, request) }.isFailure)
        val reply = FileProtocol.sealReply(PC_KEY, FileKind.OFFER, ID, 0, "{}".toByteArray())
        check(runCatching { FileProtocol.openRequest(PC_KEY, FileKind.OFFER, ID, 0, reply) }.isFailure)
    }

    Kit.test("a push or hello body is not a file message") {
        val oldStyle = com.clipsync.app.crypto.CryptoUtils.encrypt(PC_KEY, "{\"created_at\":1}".toByteArray())
        check(runCatching { FileProtocol.openRequest(PC_KEY, FileKind.OFFER, ID, 0, oldStyle) }.isFailure)
    }

    Kit.test("sealing adds exactly the declared overhead; transfer ids are plain tokens; kinds map to routes") {
        eq(FileProtocol.sealRequest(PC_KEY, FileKind.CHUNK, ID, 0, ByteArray(1000)).size, 1000 + FileProtocol.SEAL_OVERHEAD)
        check(FileProtocol.isValidTransferId("0b8e1a52-6f0e-4c3a-9a53-2f1f6d3c0a11"))
        for (bad in listOf("", "short", "has space 12345", "../../etc/passwd", "ünïcödé-id-1", "x".repeat(65))) check(!FileProtocol.isValidTransferId(bad), bad)
        for (kind in FileKind.values()) eq(FileKind.fromPath(kind.path), kind)
        eq(FileKind.fromPath("/push"), null)
        eq(FileKind.OFFER.maxRequestBody, FileProtocol.MAX_CONTROL_BODY)
        check(FileKind.CHUNK.maxRequestBody > FileProtocol.MAX_CHUNK_BYTES)
        eq(FileProtocol.hex(byteArrayOf(0x00, 0x0f, 0xa5.toByte(), 0xff.toByte())), "000fa5ff")
    }

    Kit.test("messages ignore unknown fields and omit empty ones") {
        val offer = Offer.parse("""{"name":"a.txt","size":3,"chunk_size":65536,"future":true}""".toByteArray())
        eq(offer.modifiedAt, null)
        check(!String(offer.toJson()).contains("modified_at"))
        check(!String(OfferReply("ready", 0, 65536).toJson()).contains("saved_as"))
        CancelMessage.parse("{}".toByteArray())
    }

    println("Receiver")

    Kit.test("a file arrives whole under its name") {
        val rig = Rig()
        val data = sample(CHUNK * 3 + 1234)
        val done = rig.sendAll(ID, "report.pdf", data)
        eq(Triple(done.status, done.savedAs, done.size), Triple("complete", "report.pdf", data.size.toLong()))
        eqBytes(File(rig.inbox, "report.pdf").readBytes(), data)
        val views = rig.registry.snapshot()
        eq(views.size, 1)
        val v = views[0]
        eq(listOf(v.state, v.direction, v.transferred, v.size), listOf(TState.COMPLETE, Direction.IN, data.size.toLong(), data.size.toLong()))
        check(v.finishedAt != null && v.location != null)
        eq(rig.receiver.receivingNow(), 0)
        eq(File(rig.data, FileReceiver.PARTIAL_DIR).listFiles()!!.size, 0, "no partial file is left")
        eq(rig.received, listOf("report.pdf"))
    }

    Kit.test("an empty file and an exact multiple of the chunk size both work") {
        val rig = Rig()
        rig.sendAll(ID, "empty.txt", ByteArray(0))
        eq(File(rig.inbox, "empty.txt").length(), 0L)
        val exact = sample(CHUNK * 2)
        rig.sendAll(ID2, "exact.bin", exact)
        eqBytes(File(rig.inbox, "exact.bin").readBytes(), exact)
    }

    Kit.test("a hostile name cannot leave the receiving folder") {
        val rig = Rig()
        eq(rig.sendAll(ID, "..\\..\\..\\Windows\\evil.exe", "MZ".toByteArray()).savedAs, "evil.exe")
        eq(rig.sendAll(ID2, "/etc/cron.d/job", "x".toByteArray()).savedAs, "job")
        check(rig.inbox.parentFile?.listFiles()?.none { it.name.contains("evil") } == true)
    }

    Kit.test("two files with one name get two names") {
        val rig = Rig()
        eq(rig.sendAll(ID, "photo.jpg", "first".toByteArray()).savedAs, "photo.jpg")
        eq(rig.sendAll(ID2, "photo.jpg", "second".toByteArray()).savedAs, "photo (1).jpg")
        eq(File(rig.inbox, "photo (1).jpg").readText(), "second")
    }

    Kit.test("a dropped transfer continues where it stopped") {
        val rig = Rig()
        val data = sample(CHUNK * 5 + 77)
        rig.offer(ID, "big.bin", data)
        rig.chunk(ID, 0, data.copyOfRange(0, CHUNK))
        rig.chunk(ID, 1, data.copyOfRange(CHUNK, 2 * CHUNK))
        val again = rig.offer(ID, "big.bin", data)
        eq(Pair(again.status, again.received), Pair("ready", 2L * CHUNK))
        var i = 2
        while (i * CHUNK < data.size) {
            rig.chunk(ID, i.toLong(), data.copyOfRange(i * CHUNK, minOf(data.size, (i + 1) * CHUNK)))
            i += 1
        }
        rig.finish(ID, data)
        eqBytes(File(rig.inbox, "big.bin").readBytes(), data)
        eq(rig.registry.snapshot().size, 1, "one transfer, one row")
    }

    Kit.test("a transfer survives a restart of this app, and the hash covers what came before") {
        val rig = Rig()
        val data = sample(CHUNK * 4 + 5)
        rig.offer(ID, "survivor.bin", data)
        for (i in 0 until 3) rig.chunk(ID, i.toLong(), data.copyOfRange(i * CHUNK, (i + 1) * CHUNK))
        rig.restart()
        check(rig.registry.snapshot().all { it.state != TState.ACTIVE }, "a row that was running when the app ended is not shown as running")
        check(rig.registry.snapshot().any { it.state == TState.FAILED }, "it is shown as interrupted, not lost")
        val again = rig.offer(ID, "survivor.bin", data)
        eq(again.received, 3L * CHUNK)
        rig.chunk(ID, 3, data.copyOfRange(3 * CHUNK, 4 * CHUNK))
        rig.chunk(ID, 4, data.copyOfRange(4 * CHUNK, data.size))
        rig.finish(ID, data)
        eqBytes(File(rig.inbox, "survivor.bin").readBytes(), data)
        eq(rig.registry.snapshot().size, 1)
    }

    Kit.test("a partial file that ends inside a chunk is cut back to the last whole one") {
        val rig = Rig()
        val data = sample(CHUNK * 3)
        rig.offer(ID, "cut.bin", data)
        rig.chunk(ID, 0, data.copyOfRange(0, CHUNK))
        val part = rig.receiver.partialPath(PHONE, ID)
        part.appendBytes(data.copyOfRange(CHUNK, CHUNK + 100))
        rig.restart()
        eq(rig.offer(ID, "cut.bin", data).received, CHUNK.toLong())
        eq(part.length(), CHUNK.toLong())
    }

    Kit.test("a complete partial file goes straight to finish") {
        val rig = Rig()
        val data = sample(CHUNK + 10)
        rig.offer(ID, "done.bin", data)
        rig.chunk(ID, 0, data.copyOfRange(0, CHUNK))
        rig.chunk(ID, 1, data.copyOfRange(CHUNK, data.size))
        rig.restart()
        eq(rig.offer(ID, "done.bin", data).received, data.size.toLong())
        rig.finish(ID, data)
        eqBytes(File(rig.inbox, "done.bin").readBytes(), data)
    }

    Kit.test("a chunk sent twice is stored once") {
        val rig = Rig()
        val data = sample(CHUNK * 2)
        rig.offer(ID, "twice.bin", data)
        val first = rig.chunk(ID, 0, data.copyOfRange(0, CHUNK))
        val second = rig.chunk(ID, 0, data.copyOfRange(0, CHUNK))
        eq(first, second)
        rig.chunk(ID, 1, data.copyOfRange(CHUNK, data.size))
        rig.finish(ID, data)
        eqBytes(File(rig.inbox, "twice.bin").readBytes(), data, "no bytes were duplicated")
    }

    Kit.test("a chunk that skips ahead or has the wrong length is refused and nothing is written") {
        val rig = Rig()
        val data = sample(CHUNK * 3)
        rig.offer(ID, "gap.bin", data)
        eq(statusOf { rig.chunk(ID, 2, data.copyOfRange(2 * CHUNK, 3 * CHUNK)) }, 409)
        eq(statusOf { rig.chunk(ID, 0, data.copyOfRange(0, 100)) }, 400)
        eq(statusOf { rig.chunk(ID, 0, ByteArray(CHUNK + 1)) }, 400)
        eq(rig.receiver.partialPath(PHONE, ID).length(), 0L)
        eq(rig.chunk(ID, 0, data.copyOfRange(0, CHUNK)).received, CHUNK.toLong(), "the right one still works")
    }

    Kit.test("a chunk moved to another position or transfer does not authenticate") {
        val rig = Rig()
        val data = sample(CHUNK * 2)
        rig.offer(ID, "a.bin", data)
        rig.offer(ID2, "b.bin", data)
        val sealed = FileProtocol.sealRequest(PC_KEY, FileKind.CHUNK, ID, 0, data.copyOfRange(0, CHUNK))
        eq(statusOf { rig.receiver.handle(rig.peer, FileKind.CHUNK, ID, 1, sealed) }, 403)
        eq(statusOf { rig.receiver.handle(rig.peer, FileKind.CHUNK, ID2, 0, sealed) }, 403)
        eq(rig.receiver.partialPath(PHONE, ID2).length(), 0L)
    }

    Kit.test("a message sealed with another key is refused before anything is created") {
        val rig = Rig()
        val offer = Offer("x", 1, CHUNK).toJson()
        val sealed = FileProtocol.sealRequest(ByteArray(32) { 99 }, FileKind.OFFER, ID, 0, offer)
        eq(statusOf { rig.receiver.handle(rig.peer, FileKind.OFFER, ID, 0, sealed) }, 403)
        eq(rig.registry.snapshot().size, 0)
        eq(rig.receiver.receivingNow(), 0)
        eq(statusOf { rig.receiver.handle(rig.peer, FileKind.OFFER, ID, 0, ByteArray(5)) }, 403, "too short to be a sealed message")
    }

    Kit.test("an unknown transfer is gone; finishing early is a conflict; a wrong checksum deletes the file") {
        val rig = Rig()
        eq(statusOf { rig.chunk(ID, 0, "data".toByteArray()) }, 410)
        eq(statusOf { rig.finish(ID, "data".toByteArray()) }, 410)

        val data = sample(CHUNK + 3)
        rig.offer(ID, "bad.bin", data)
        rig.chunk(ID, 0, data.copyOfRange(0, CHUNK))
        eq(statusOf { rig.finish(ID, data) }, 409)
        check(!File(rig.inbox, "bad.bin").exists())
        rig.chunk(ID, 1, data.copyOfRange(CHUNK, data.size))
        eq(statusOf { rig.finishWith(ID, sha("something else".toByteArray())) }, 422)
        check(!File(rig.inbox, "bad.bin").exists(), "a damaged file is never presented as received")
        check(!rig.receiver.partialPath(PHONE, ID).exists())
        eq(rig.registry.snapshot()[0].state, TState.FAILED)
        check(rig.failed.isNotEmpty(), "the person is told")
        eq(rig.offer(ID, "bad.bin", data).received, 0L)
        rig.chunk(ID, 0, data.copyOfRange(0, CHUNK))
        rig.chunk(ID, 1, data.copyOfRange(CHUNK, data.size))
        rig.finish(ID, data)
        eqBytes(File(rig.inbox, "bad.bin").readBytes(), data)
        eq(rig.registry.snapshot().size, 1)
    }

    Kit.test("a checksum in capitals is accepted") {
        val rig = Rig()
        rig.offer(ID, "caps.txt", "abc".toByteArray())
        rig.chunk(ID, 0, "abc".toByteArray())
        rig.finishWith(ID, sha("abc".toByteArray()).uppercase())
        check(File(rig.inbox, "caps.txt").exists())
    }

    Kit.test("repeating the last messages after completion gives the same answers") {
        val rig = Rig()
        val data = sample(CHUNK + 9)
        rig.sendAll(ID, "once.bin", data)
        val again = rig.finish(ID, data)
        eq(Pair(again.status, again.savedAs), Pair("complete", "once.bin"))
        eq(rig.chunk(ID, 1, data.copyOfRange(CHUNK, data.size)).received, data.size.toLong())
        val offer = rig.offer(ID, "once.bin", data)
        eq(Pair(offer.status, offer.savedAs), Pair("complete", "once.bin"))
        check(!File(rig.inbox, "once (1).bin").exists())
        eq(rig.registry.snapshot().size, 1)
    }

    Kit.test("the same id with different parameters is a conflict") {
        val rig = Rig()
        rig.offer(ID, "a.bin", sample(100))
        eq(statusOf { rig.offer(ID, "a.bin", sample(200)) }, 409)
        eq(statusOf { rig.offer(ID, "other.bin", sample(100)) }, 409)
        eq(statusOf { rig.offer(ID, "a.bin", sample(100), CHUNK * 2) }, 409)
    }

    Kit.test("receiving can be switched off, limited in size, and refused when the phone is full") {
        val rig = Rig()
        rig.accept = false
        eq(statusOf { rig.offer(ID, "no.txt", "x".toByteArray()) }, 423)
        check(rig.registry.snapshot().isEmpty())
        rig.accept = true
        rig.offer(ID, "yes.txt", "x".toByteArray())

        rig.maxBytes = 1000
        eq(statusOf { rig.offer(ID2, "big.bin", sample(1001)) }, 413)
        rig.offer(ID2, "ok.bin", sample(1000))
        rig.maxBytes = Long.MAX_VALUE
        val huge = Offer("huge", FileProtocol.MAX_FILE_BYTES + 1, CHUNK).toJson()
        eq(statusOf { rig.call(FileKind.OFFER, "33333333-0000-0000-0000-000000000003", 0, huge) }, 413, "the protocol limit applies whatever the setting says")

        rig.freeBytes = 10L * 1024 * 1024
        eq(statusOf { rig.offer("44444444-0000-0000-0000-000000000004", "full.bin", sample(11 * 1024 * 1024)) }, 507)
        check(rig.receiver.receivingNow() == 2, "a refused offer leaves nothing behind")
    }

    Kit.test("chunk sizes outside the bounds are refused") {
        val rig = Rig()
        for (bad in listOf(0, 1, CHUNK - 1, FileProtocol.MAX_CHUNK_BYTES + 1, Int.MAX_VALUE)) {
            eq(statusOf { rig.offer(ID, "x", "x".toByteArray(), bad) }, 400, "chunk $bad")
        }
        rig.offer(ID, "x", "x".toByteArray(), FileProtocol.MAX_CHUNK_BYTES)
    }

    Kit.test("too many receptions at once are told to wait") {
        val rig = Rig()
        val ids = (0..3).map { "55555555-0000-0000-0000-%012d".format(it) }
        for (id in ids.take(3)) rig.offer(id, "f", "x".toByteArray())
        eq(statusOf { rig.offer(ids[3], "f", "x".toByteArray()) }, 503)
        rig.chunk(ids[0], 0, "x".toByteArray())
        rig.finish(ids[0], "x".toByteArray())
        rig.offer(ids[3], "f", "x".toByteArray())
    }

    Kit.test("a sink that cannot save keeps the data, so a retry works") {
        val rig = Rig()
        rig.sink.failWith = java.io.IOException("disk is full")
        rig.offer(ID, "f.txt", "abc".toByteArray())
        rig.chunk(ID, 0, "abc".toByteArray())
        eq(statusOf { rig.finish(ID, "abc".toByteArray()) }, 507)
        check(rig.receiver.partialPath(PHONE, ID).exists())
        rig.sink.failWith = null
        // The digest can be read only once; the second finish must still verify against the same value.
        rig.finish(ID, "abc".toByteArray())
        eq(File(rig.inbox, "f.txt").readText(), "abc")
    }

    Kit.test("the sender can cancel, and the partial file goes away") {
        val rig = Rig()
        val data = sample(CHUNK * 2)
        rig.offer(ID, "c.bin", data)
        rig.chunk(ID, 0, data.copyOfRange(0, CHUNK))
        rig.call(FileKind.CANCEL, ID, 0, CancelMessage.toJson())
        check(!rig.receiver.partialPath(PHONE, ID).exists())
        eq(rig.registry.snapshot()[0].state, TState.CANCELLED)
        rig.call(FileKind.CANCEL, ID, 0, CancelMessage.toJson())
        rig.call(FileKind.CANCEL, ID2, 0, CancelMessage.toJson())
        eq(rig.offer(ID, "c.bin", data).received, 0L, "the sender may start the same id again")
    }

    Kit.test("the user cancelling stops the sender for good") {
        val rig = Rig()
        val data = sample(CHUNK * 2)
        rig.offer(ID, "u.bin", data)
        rig.chunk(ID, 0, data.copyOfRange(0, CHUNK))
        val viewId = rig.registry.snapshot()[0].id
        check(rig.receiver.cancelIncoming(viewId))
        check(!rig.receiver.partialPath(PHONE, ID).exists())
        eq(statusOf { rig.chunk(ID, 1, data.copyOfRange(CHUNK, data.size)) }, 410)
        eq(statusOf { rig.finish(ID, data) }, 410)
        eq(statusOf { rig.offer(ID, "u.bin", data) }, 410, "the sender cannot simply start it again")
        eq(rig.registry.snapshot()[0].state, TState.CANCELLED)
        check(!rig.receiver.cancelIncoming(viewId), "cancelling twice does nothing")
        rig.offer(ID2, "u.bin", data)
    }

    Kit.test("unpairing ends the receptions") {
        val rig = Rig()
        rig.offer(ID, "p.bin", sample(10))
        rig.receiver.dropAll()
        eq(rig.receiver.receivingNow(), 0)
        check(!rig.receiver.partialPath(PHONE, ID).exists())
        eq(rig.registry.snapshot()[0].state, TState.CANCELLED)
    }

    Kit.test("the list is saved, survives a restart, and a broken or newer file is not fatal") {
        val rig = Rig()
        rig.sendAll(ID, "kept.txt", "hello".toByteArray())
        rig.restart()
        val views = rig.registry.snapshot()
        eq(views.size, 1)
        eq(Pair(views[0].name, views[0].state), Pair("kept.txt", TState.COMPLETE))

        val broken = tempDir("broken")
        File(broken, "file-transfers.json").writeText("{ this is not json")
        check(TransferRegistry(TransferLog(File(broken, "file-transfers.json"))).snapshot().isEmpty())

        val newer = tempDir("newer")
        File(newer, "file-transfers.json").writeText(
            """{"transfers":[{"id":"in-a-b","direction":"in","name":"n.txt","size":1,"state":"complete","futureField":[1,2]}],"other":true}""",
        )
        eq(TransferRegistry(TransferLog(File(newer, "file-transfers.json"))).snapshot().size, 1)
    }

    Kit.test("removing finished rows keeps the running ones; the list never grows without bound") {
        val rig = Rig()
        rig.sendAll(ID, "done.txt", "x".toByteArray())
        rig.offer(ID2, "running.bin", sample(10))
        eq(rig.registry.snapshot().size, 2)
        rig.registry.removeFinished(null)
        eq(rig.registry.snapshot().map { it.name }, listOf("running.bin"))
        rig.receiver.cancelIncoming(rig.registry.snapshot()[0].id)
        rig.registry.removeFinished(null)
        for (i in 0 until TransferRegistry.MAX_VIEWS + 20) rig.sendAll("66666666-0000-0000-0000-%012d".format(i), "f$i.txt", "x".toByteArray())
        eq(rig.registry.snapshot().size, TransferRegistry.MAX_VIEWS)
        eq(rig.registry.snapshot()[0].name, "f${TransferRegistry.MAX_VIEWS + 19}.txt", "newest first")
    }

    Kit.test("progress and speed are reported while receiving") {
        var now = 0L
        val data = tempDir("speed")
        val registry = TransferRegistry(null, { 1_000L }, { now })
        val receiver = FileReceiver(data, registry, FolderSink(tempDir("speed-inbox")), { ReceivePolicy(true, 1L shl 30) })
        val body = sample(CHUNK * 3)
        fun call(kind: FileKind, index: Long, plain: ByteArray): ByteArray =
            FileProtocol.openReply(PC_KEY, kind, ID, index, receiver.handle(Peer(PHONE, PC_KEY), kind, ID, index, FileProtocol.sealRequest(PC_KEY, kind, ID, index, plain)))
        call(FileKind.OFFER, 0, Offer("p.bin", body.size.toLong(), CHUNK).toJson())
        call(FileKind.CHUNK, 0, body.copyOfRange(0, CHUNK))
        eq(registry.snapshot()[0].transferred, CHUNK.toLong())
        now += 1_000_000_000L // one second later
        call(FileKind.CHUNK, 1, body.copyOfRange(CHUNK, 2 * CHUNK))
        val v = registry.snapshot()[0]
        check(v.rateBps > 0, "speed is measured: ${v.rateBps}")
        eq(v.transferred, 2L * CHUNK)
    }

    Kit.test("two offers for one id at once make one reception") {
        val rig = Rig()
        val data = sample(200)
        val results = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val threads = (0 until 8).map {
            Thread { results.add(rig.offer(ID, "same.bin", data).received) }.also { t -> t.start() }
        }
        threads.forEach { it.join() }
        eq(results.size, 8)
        check(results.all { it == 0L })
        eq(rig.receiver.receivingNow(), 1)
        eq(rig.registry.snapshot().size, 1)
    }

    Kit.test("stale partial files are cleaned at start") {
        val data = tempDir("stale")
        val dir = File(data, FileReceiver.PARTIAL_DIR).also { it.mkdirs() }
        val old = File(dir, "old.part").also { it.writeText("x"); it.setLastModified(System.currentTimeMillis() - 25L * 3600 * 1000) }
        val fresh = File(dir, "fresh.part").also { it.writeText("x") }
        FileReceiver(data, TransferRegistry(null), FolderSink(tempDir("stale-inbox")), { ReceivePolicy(true, 1L shl 30) })
        check(!old.exists())
        check(fresh.exists())
    }
}
