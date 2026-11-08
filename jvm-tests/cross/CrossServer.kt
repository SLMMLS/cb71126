package com.clipsync.jvmcross

import com.clipsync.app.crypto.CryptoUtils
import com.clipsync.app.crypto.Identity
import com.clipsync.app.files.FileException
import com.clipsync.app.files.FileKind
import com.clipsync.app.files.FileNames
import com.clipsync.app.files.FileReceiver
import com.clipsync.app.files.FinalSink
import com.clipsync.app.files.Peer
import com.clipsync.app.files.ReceivePolicy
import com.clipsync.app.files.TransferRegistry
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors

class FolderSink2(val dir: File) : FinalSink {
    override fun publish(partial: File, name: String, modifiedAt: Long?): FinalSink.Published {
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

/** A receiver written in Kotlin, on plain HTTP like the phone's NanoHTTPD, for the Rust sender to talk to. */
fun main(args: Array<String>) {
    val dir = File(args[0])
    val pcPrivate = ByteArray(32) { 1 }
    val phonePrivate = ByteArray(32) { 2 }
    val key = CryptoUtils.deriveTransportKey(Identity.sharedSecret(phonePrivate, Identity.publicKeyFor(pcPrivate)))

    val receiver = FileReceiver(File(dir, "kt-data"), TransferRegistry(null), FolderSink2(File(dir, "kt-inbox")), { ReceivePolicy(true, 1L shl 30) })
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.executor = Executors.newCachedThreadPool()
    server.createContext("/") { exchange ->
        val kind = FileKind.fromPath(exchange.requestURI.path)
        val body = exchange.requestBody.readBytes()
        fun respond(status: Int, payload: ByteArray) {
            exchange.sendResponseHeaders(status, if (payload.isEmpty()) -1 else payload.size.toLong())
            if (payload.isNotEmpty()) exchange.responseBody.use { it.write(payload) } else exchange.close()
        }
        if (kind == null) return@createContext respond(404, "not found".toByteArray())
        val id = exchange.requestHeaders.getFirst("X-Transfer-Id") ?: ""
        val index = exchange.requestHeaders.getFirst("X-Chunk-Index")?.toLongOrNull() ?: 0L
        val from = exchange.requestHeaders.getFirst("X-Device-Id") ?: ""
        if (from != "pc-1") return@createContext respond(403, "unknown device".toByteArray())
        try {
            respond(200, receiver.handle(Peer(from, key), kind, id, index, body))
        } catch (e: FileException) {
            respond(e.status, (e.message ?: "").toByteArray())
        }
    }
    server.start()
    File(dir, "kt-port").writeText(server.address.port.toString())
    val deadline = System.currentTimeMillis() + 120_000
    while (!File(dir, "kt-stop").exists() && System.currentTimeMillis() < deadline) Thread.sleep(100)
    server.stop(0)
}
