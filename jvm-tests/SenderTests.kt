package com.clipsync.jvmtests

import com.clipsync.app.files.FileKind
import com.clipsync.app.files.FileException
import com.clipsync.app.files.FileProtocol
import com.clipsync.app.files.FileReceiver
import com.clipsync.app.files.FileSender
import com.clipsync.app.files.FileSource
import com.clipsync.app.files.FileTransport
import com.clipsync.app.files.Direction
import com.clipsync.app.files.ReceivePolicy
import com.clipsync.app.files.SendJob
import com.clipsync.app.files.SendOutcome
import com.clipsync.app.files.TState
import com.clipsync.app.files.TransferLog
import com.clipsync.app.files.TransferRegistry
import com.clipsync.app.files.TransferView
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** The JDK's HTTP client as the transport: plain HTTP to the test server, with the same wire format as OkHttp would produce. */
class JdkTransport(private val base: String, private val myDeviceId: String = "phone-1") : FileTransport {
    override fun post(kind: FileKind, transferId: String, index: Long, body: ByteArray): FileTransport.FileResponse {
        val conn = URL(base + kind.path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 3000
            conn.readTimeout = 20000
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(body.size)
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            conn.setRequestProperty("X-Device-Id", myDeviceId)
            conn.setRequestProperty("X-Transfer-Id", transferId)
            if (kind == FileKind.CHUNK) conn.setRequestProperty("X-Chunk-Index", index.toString())
            conn.outputStream.use { it.write(body) }
            val status = conn.responseCode
            val stream: InputStream? = if (status >= 400) conn.errorStream else conn.inputStream
            return FileTransport.FileResponse(status, stream?.use { it.readBytes() } ?: ByteArray(0))
        } finally {
            conn.disconnect()
        }
    }
}

/** What the fake PC does with the n-th request. */
sealed class Fault {
    object None : Fault()
    class Status(val code: Int) : Fault()
    /** Answers 200 with a reply sealed correctly but whose plaintext is not what the protocol says. */
    class Garbage(val plain: String) : Fault()
}

/**
 * A PC: a real HTTP server in front of a real [FileReceiver], speaking the protocol as the PC's app does
 * (headers, raw binary bodies, status codes). The phone's sender talks to it over a real socket.
 */
class FakePc(val key: ByteArray = PC_KEY, val receiverId: String = "phone-1") {
    val data = tempDir("pc-data")
    val inbox = tempDir("pc-inbox")
    @Volatile var accept = true
    @Volatile var faults: (FileKind, Long, Int) -> Fault = { _, _, _ -> Fault.None }
    val registry = TransferRegistry(TransferLog(File(data, "file-transfers.json")))
    val sink = FolderSink(inbox)
    val receiver = FileReceiver(data, registry, sink, { ReceivePolicy(accept, 1L shl 30) })
    val seen = java.util.Collections.synchronizedList(mutableListOf<Pair<FileKind, Long>>())
    private val count = AtomicInteger()
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    val base: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/") { exchange ->
            val n = count.getAndIncrement()
            val kind = FileKind.fromPath(exchange.requestURI.path)
            val body = exchange.requestBody.readBytes()
            fun respond(status: Int, payload: ByteArray) {
                exchange.responseHeaders.add("Content-Type", "application/octet-stream")
                exchange.sendResponseHeaders(status, if (payload.isEmpty()) -1 else payload.size.toLong())
                if (payload.isNotEmpty()) exchange.responseBody.use { it.write(payload) } else exchange.close()
            }
            if (kind == null) return@createContext respond(404, "not found".toByteArray())
            val id = exchange.requestHeaders.getFirst("X-Transfer-Id") ?: ""
            val index = exchange.requestHeaders.getFirst("X-Chunk-Index")?.toLongOrNull() ?: 0L
            seen.add(kind to index)
            when (val fault = faults(kind, index, n)) {
                is Fault.Status -> return@createContext respond(fault.code, "injected".toByteArray())
                is Fault.Garbage -> return@createContext respond(200, FileProtocol.sealReply(key, kind, id, index, fault.plain.toByteArray()))
                Fault.None -> {}
            }
            try {
                val reply = receiver.handle(com.clipsync.app.files.Peer(receiverId, key), kind, id, index, body)
                respond(200, reply)
            } catch (e: FileException) {
                respond(e.status, (e.message ?: "").toByteArray())
            }
        }
        server.start()
    }

    fun requests(kind: FileKind) = seen.count { it.first == kind }

    fun stop() = server.stop(0)
}

class BytesSource(override val name: String, private val bytes: ByteArray, override val modifiedAt: Long? = null) : FileSource {
    override val size: Long get() = bytes.size.toLong()
    override fun open(): InputStream = ByteArrayInputStream(bytes)
}

/** Claims a size it cannot deliver: the file shrank after it was chosen. */
class ShrunkenSource(override val name: String, private val bytes: ByteArray, private val claimed: Long) : FileSource {
    override val size: Long get() = claimed
    override fun open(): InputStream = ByteArrayInputStream(bytes)
}

class SenderRig(val pc: FakePc = FakePc(), key: ByteArray = PC_KEY) {
    val registry = TransferRegistry(null)
    val sender = FileSender(JdkTransport(pc.base), key, registry, pausesMs = listOf(2, 2, 2), sleep = { Thread.sleep(1) }, explain = { it.message ?: it.javaClass.simpleName })
    private var counter = 0

    fun job(name: String, data: ByteArray, transferId: String = "99999999-0000-0000-0000-%012d".format(counter++)): SendJob {
        val source = BytesSource(name, data)
        val now = System.currentTimeMillis()
        val viewId = "out-$transferId"
        registry.add(TransferView(id = viewId, transferId = transferId, direction = Direction.OUT, name = name, size = data.size.toLong(), state = TState.QUEUED, startedAt = now, updatedAt = now))
        return SendJob(viewId, transferId, source)
    }

    fun view(job: SendJob) = registry.get(job.viewId)!!
}

fun SenderTests() {
    println("Sender")
    val big = FileProtocol.DEFAULT_CHUNK_BYTES

    Kit.test("a file goes to the PC whole") {
        val rig = SenderRig()
        val data = sample(big * 2 + 4321)
        val job = rig.job("photo.jpg", data)
        eq(rig.sender.run(job), SendOutcome.DONE)
        val v = rig.view(job)
        eq(listOf(v.state, v.transferred, v.size), listOf(TState.COMPLETE, data.size.toLong(), data.size.toLong()))
        eqBytes(File(rig.pc.inbox, "photo.jpg").readBytes(), data)
        eq(rig.pc.requests(FileKind.OFFER), 1)
        eq(rig.pc.requests(FileKind.CHUNK), 3)
        eq(rig.pc.requests(FileKind.FINISH), 1)
        check(v.error == null)
        rig.pc.stop()
    }

    Kit.test("an empty file is sent too, with no chunk") {
        val rig = SenderRig()
        val job = rig.job("empty.txt", ByteArray(0))
        eq(rig.sender.run(job), SendOutcome.DONE)
        eq(File(rig.pc.inbox, "empty.txt").length(), 0L)
        eq(rig.pc.requests(FileKind.CHUNK), 0)
        rig.pc.stop()
    }

    Kit.test("a name with non-ASCII letters and a modification time arrive intact") {
        val pc = FakePc()
        val rig = SenderRig(pc)
        val data = sample(1000)
        val source = BytesSource("Отчёт за квартал №3.docx", data, modifiedAt = 1_700_000_000_000L)
        val now = System.currentTimeMillis()
        val tid = "88888888-0000-0000-0000-000000000001"
        rig.registry.add(TransferView(id = "out-$tid", transferId = tid, direction = Direction.OUT, name = source.name, size = 1000, state = TState.QUEUED, startedAt = now, updatedAt = now))
        eq(rig.sender.run(SendJob("out-$tid", tid, source)), SendOutcome.DONE)
        eqBytes(File(pc.inbox, "Отчёт за квартал №3.docx").readBytes(), data)
        pc.stop()
    }

    Kit.test("a hiccup in the middle is repeated without resending what arrived") {
        val rig = SenderRig()
        val data = sample(big * 4)
        val failedOnce = java.util.concurrent.atomic.AtomicBoolean(false)
        rig.pc.faults = { kind, index, _ -> if (kind == FileKind.CHUNK && index == 2L && !failedOnce.getAndSet(true)) Fault.Status(500) else Fault.None }
        val job = rig.job("hiccup.bin", data)
        eq(rig.sender.run(job), SendOutcome.DONE)
        eqBytes(File(rig.pc.inbox, "hiccup.bin").readBytes(), data)
        eq(rig.pc.seen.filter { it.first == FileKind.CHUNK }.map { it.second }, listOf(0L, 1L, 2L, 2L, 3L), "only the failed chunk was sent twice")
        eq(rig.pc.requests(FileKind.OFFER), 2, "the retry asked where to continue")
        rig.pc.stop()
    }

    Kit.test("after the last attempt the row fails, and a retry continues the same transfer") {
        val rig = SenderRig()
        val data = sample(big * 5 + 10)
        rig.pc.faults = { kind, index, _ -> if (kind == FileKind.CHUNK && index >= 3) Fault.Status(503) else Fault.None }
        val job = rig.job("resume.bin", data)
        eq(rig.sender.run(job), SendOutcome.FAILED)
        val failed = rig.view(job)
        eq(failed.state, TState.FAILED)
        check(failed.error!!.contains("занят"), failed.error!!)
        eq(failed.transferred, 3L * big, "what arrived is shown")
        check(rig.registry.snapshot().first().canRetry, "the screen may offer Повторить")
        check(job.canResume)

        rig.pc.faults = { _, _, _ -> Fault.None }
        val before = rig.pc.seen.size
        rig.registry.update(job.viewId) { it.copy(state = TState.QUEUED, error = null, finishedAt = null) }
        eq(rig.sender.run(job), SendOutcome.DONE)
        eqBytes(File(rig.pc.inbox, "resume.bin").readBytes(), data)
        eq(rig.pc.seen.drop(before).filter { it.first == FileKind.CHUNK }.map { it.second }, listOf(3L, 4L, 5L), "nothing before the break was sent again")
        eq(rig.registry.snapshot().size, 1)
        rig.pc.stop()
    }

    Kit.test("a PC that is not there gives a clear failure") {
        val pc = FakePc()
        val dead = java.net.ServerSocket(0).use { it.localPort }
        val registry = TransferRegistry(null)
        val sender = FileSender(JdkTransport("http://127.0.0.1:$dead"), PC_KEY, registry, pausesMs = listOf(2), sleep = { Thread.sleep(1) }, explain = { it.message ?: "" })
        val now = System.currentTimeMillis()
        registry.add(TransferView(id = "out-x", transferId = "77777777-0000-0000-0000-000000000001", direction = Direction.OUT, name = "n.txt", size = 3, state = TState.QUEUED, startedAt = now, updatedAt = now))
        eq(sender.run(SendJob("out-x", "77777777-0000-0000-0000-000000000001", BytesSource("n.txt", "abc".toByteArray()))), SendOutcome.FAILED)
        check(registry.get("out-x")!!.error!!.startsWith("Нет связи с ПК"), registry.get("out-x")!!.error!!)
        pc.stop()
    }

    Kit.test("a refusal that will not change stops at once with a clear reason") {
        for ((code, expected) in listOf(423 to "выключен приём файлов", 413 to "слишком большой", 403 to "сопряжение потеряно", 404 to "обновите", 507 to "не удалось сохранить")) {
            val rig = SenderRig()
            rig.pc.faults = { _, _, _ -> Fault.Status(code) }
            val job = rig.job("x.bin", sample(10))
            eq(rig.sender.run(job), SendOutcome.FAILED, "status $code")
            check(rig.view(job).error!!.contains(expected), "status $code: ${rig.view(job).error}")
            eq(rig.pc.requests(FileKind.OFFER), 1, "status $code must not be retried")
            rig.pc.stop()
        }
    }

    Kit.test("the PC switching receiving off is reported") {
        val rig = SenderRig()
        rig.pc.accept = false
        val job = rig.job("x.bin", sample(10))
        eq(rig.sender.run(job), SendOutcome.FAILED)
        check(rig.view(job).error!!.contains("выключен приём файлов"))
        rig.pc.stop()
    }

    Kit.test("a PC that cancelled stops the transfer, and a retry starts a new one") {
        val rig = SenderRig()
        val data = sample(big * 3)
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        rig.pc.faults = { kind, index, _ ->
            if (kind == FileKind.CHUNK && index == 1L && !done.getAndSet(true)) {
                rig.pc.receiver.cancelIncoming(rig.pc.registry.snapshot()[0].id)
            }
            Fault.None
        }
        val job = rig.job("cancelled.bin", data)
        eq(rig.sender.run(job), SendOutcome.CANCELLED)
        eq(rig.view(job).state, TState.CANCELLED)
        check(rig.view(job).error!!.contains("ПК отменил"))
        check(!job.canResume, "the PC refuses the cancelled id for good")

        rig.pc.faults = { _, _, _ -> Fault.None }
        val fresh = SendJob(job.viewId, "99999999-1111-0000-0000-000000000001", job.source)
        eq(rig.sender.run(fresh), SendOutcome.DONE)
        eqBytes(File(rig.pc.inbox, "cancelled.bin").readBytes(), data)
        rig.pc.stop()
    }

    Kit.test("the person can cancel a running transfer; the PC is told and drops its partial file") {
        val rig = SenderRig()
        val data = sample(big * 6)
        val holder = arrayOfNulls<SendJob>(1)
        rig.pc.faults = { kind, index, _ ->
            if (kind == FileKind.CHUNK && index == 2L) holder[0]?.cancel?.set(true)
            Fault.None
        }
        val job = rig.job("stop.bin", data)
        holder[0] = job
        eq(rig.sender.run(job), SendOutcome.CANCELLED)
        check(rig.pc.requests(FileKind.CHUNK) < 6, "it really stopped")
        eq(rig.pc.requests(FileKind.CANCEL), 1)
        check(!File(rig.pc.inbox, "stop.bin").exists())
        eq(rig.pc.receiver.receivingNow(), 0, "the PC dropped the reception")
        rig.pc.stop()
    }

    Kit.test("a job cancelled before it starts sends nothing") {
        val rig = SenderRig()
        val job = rig.job("never.bin", sample(10))
        job.cancel.set(true)
        eq(rig.sender.run(job), SendOutcome.CANCELLED)
        eq(rig.pc.seen.size, 0)
        rig.pc.stop()
    }

    Kit.test("a file that is shorter than it claimed is not sent as if complete") {
        val rig = SenderRig()
        val now = System.currentTimeMillis()
        val tid = "66666666-0000-0000-0000-000000000009"
        rig.registry.add(TransferView(id = "out-$tid", transferId = tid, direction = Direction.OUT, name = "short.bin", size = 5000, state = TState.QUEUED, startedAt = now, updatedAt = now))
        val job = SendJob("out-$tid", tid, ShrunkenSource("short.bin", sample(3000), 5000))
        eq(rig.sender.run(job), SendOutcome.FAILED)
        check(rig.registry.get("out-$tid")!!.error!!.contains("стал короче"))
        check(!File(rig.pc.inbox, "short.bin").exists())
        rig.pc.stop()
    }

    Kit.test("a reply that does not open with our key is not believed") {
        val impostor = FakePc(key = ByteArray(32) { 55 })
        val rig = SenderRig(impostor, key = PC_KEY)
        val job = rig.job("secret.txt", "secret".toByteArray())
        eq(rig.sender.run(job), SendOutcome.FAILED)
        check(rig.view(job).error!!.contains("не удалось проверить") || rig.view(job).error!!.contains("не узнал"), rig.view(job).error!!)
        check(!File(impostor.inbox, "secret.txt").exists(), "nothing was stored by the impostor either")
        impostor.stop()
    }

    Kit.test("a sealed reply with unexpected content ends the transfer with a sentence, not a crash") {
        val rig = SenderRig()
        rig.pc.faults = { _, _, _ -> Fault.Garbage("this is not json") }
        val job = rig.job("g.bin", sample(10))
        eq(rig.sender.run(job), SendOutcome.FAILED)
        check(rig.view(job).error!!.contains("неожиданном формате"), rig.view(job).error!!)
        rig.pc.stop()
    }

    Kit.test("the classification of answers matches the protocol") {
        fun kindOf(kind: FileKind, status: Int) = FileSender.classifyForTest(kind, status)
        eq(kindOf(FileKind.CHUNK, 409), "resync")
        eq(kindOf(FileKind.OFFER, 409), "fatal")
        eq(kindOf(FileKind.CHUNK, 410), "resync")
        eq(kindOf(FileKind.OFFER, 410), "cancelled-by-pc")
        for (code in listOf(408, 429, 500, 502, 503, 504, 422)) eq(kindOf(FileKind.CHUNK, code), "retry", "$code")
        for (code in listOf(400, 403, 404, 413, 423, 507, 418)) eq(kindOf(FileKind.CHUNK, code), "fatal", "$code")
    }

    Kit.test("two files in a row both arrive; the second does not inherit the first's progress") {
        val rig = SenderRig()
        val a = rig.job("a.bin", sample(big + 5, 1))
        val b = rig.job("b.bin", sample(big * 2 + 7, 2))
        eq(rig.sender.run(a), SendOutcome.DONE)
        eq(rig.sender.run(b), SendOutcome.DONE)
        eqBytes(File(rig.pc.inbox, "a.bin").readBytes(), sample(big + 5, 1))
        eqBytes(File(rig.pc.inbox, "b.bin").readBytes(), sample(big * 2 + 7, 2))
        eq(rig.pc.seen.map { it.first }, listOf(FileKind.OFFER, FileKind.CHUNK, FileKind.CHUNK, FileKind.FINISH, FileKind.OFFER, FileKind.CHUNK, FileKind.CHUNK, FileKind.CHUNK, FileKind.FINISH))
        rig.pc.stop()
    }
}
