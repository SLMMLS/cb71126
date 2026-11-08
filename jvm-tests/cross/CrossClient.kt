package com.clipsync.jvmcross

import com.clipsync.app.crypto.CryptoUtils
import com.clipsync.app.crypto.Identity
import com.clipsync.app.files.Direction
import com.clipsync.app.files.FileKind
import com.clipsync.app.files.FileSender
import com.clipsync.app.files.FileSource
import com.clipsync.app.files.FileTransport
import com.clipsync.app.files.SendJob
import com.clipsync.app.files.SendOutcome
import com.clipsync.app.files.TState
import com.clipsync.app.files.TransferRegistry
import com.clipsync.app.files.TransferView
import com.clipsync.app.network.PinnedHttp
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlin.system.exitProcess

fun unhex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

/** The phone's real transport: HTTPS with the certificate pinned by hash, and connections kept alive between chunks. */
class TlsTransport(private val base: String, certHash: ByteArray, private val myId: String) : FileTransport {
    private val factory = PinnedHttp.socketFactory(certHash)

    override fun post(kind: FileKind, transferId: String, index: Long, body: ByteArray): FileTransport.FileResponse {
        val conn = URL(base + kind.path).openConnection() as HttpsURLConnection
        conn.sslSocketFactory = factory
        conn.hostnameVerifier = PinnedHttp.trustAnyHostname
        conn.requestMethod = "POST"
        conn.connectTimeout = 5000
        conn.readTimeout = 30000
        conn.doOutput = true
        conn.setFixedLengthStreamingMode(body.size)
        conn.setRequestProperty("Content-Type", "application/octet-stream")
        conn.setRequestProperty("X-Device-Id", myId)
        conn.setRequestProperty("X-Transfer-Id", transferId)
        if (kind == FileKind.CHUNK) conn.setRequestProperty("X-Chunk-Index", index.toString())
        conn.outputStream.use { it.write(body) }
        val status = conn.responseCode
        val stream: InputStream? = if (status >= 400) conn.errorStream else conn.inputStream
        // Reading to the end (and not disconnecting) lets the JDK reuse the TLS connection for the next chunk.
        return FileTransport.FileResponse(status, stream?.use { it.readBytes() } ?: ByteArray(0))
    }
}

class DiskSource(private val file: File, override val name: String, override val modifiedAt: Long?) : FileSource {
    override val size: Long get() = file.length()
    override fun open(): InputStream = file.inputStream()
}

fun main(args: Array<String>) {
    val dir = File(args[0])
    val port = File(dir, "port").readText().trim()
    val certHash = unhex(File(dir, "certhash.hex").readText().trim())
    val pcPublic = unhex(File(dir, "pc-public.hex").readText().trim())
    val phonePrivate = ByteArray(32) { 2 }
    val key = CryptoUtils.deriveTransportKey(Identity.sharedSecret(phonePrivate, pcPublic))

    val registry = TransferRegistry(null)
    val sender = FileSender(TlsTransport("https://127.0.0.1:$port", certHash, "phone-1"), key, registry, pausesMs = listOf(50, 50, 50), explain = { it.message ?: it.toString() })

    // (display name on the wire, file on disk, modified time)
    val items = File(dir, "kt-send").listFiles()!!.sortedBy { it.name }.map { f ->
        val wireName = File(f.path + ".name").takeIf { it.exists() }?.readText() ?: f.name
        Triple(wireName, f, if (f.name.contains("dated")) 1_700_000_000_000L else null)
    }.filter { !it.second.name.endsWith(".name") }

    var failures = 0
    var n = 0
    for ((wireName, file, modified) in items) {
        val tid = "cccccccc-0000-0000-0000-%012d".format(n++)
        val view = "out-$tid"
        val now = System.currentTimeMillis()
        registry.add(TransferView(id = view, transferId = tid, direction = Direction.OUT, name = wireName, size = file.length(), state = TState.QUEUED, startedAt = now, updatedAt = now))
        val started = System.nanoTime()
        val outcome = sender.run(SendJob(view, tid, DiskSource(file, wireName, modified)))
        val ms = (System.nanoTime() - started) / 1_000_000
        val v = registry.get(view)!!
        println("sent ${wireName.map { if (it.code < 128) it.toString() else "?" }.joinToString("")} (${file.length()} bytes) -> $outcome in $ms ms ${v.error ?: ""}")
        if (outcome != SendOutcome.DONE) failures++
    }
    exitProcess(if (failures == 0) 0 else 1)
}
