package com.clipsync.app.files

import com.clipsync.app.crypto.CryptoUtils
import org.json.JSONObject

/**
 * The shared half of file transfer (PROTOCOL.md, section 3c) - the Kotlin twin of `fileproto.rs`:
 * limits, how a message is sealed, and the JSON bodies. Nothing here touches the disk or the
 * network, so all of it is tested on a plain JVM.
 */
object FileProtocol {
    /** The name under which this ability is announced in `features` (PROTOCOL.md, section 3b). */
    const val FEATURE = "files"

    const val MIN_CHUNK_BYTES = 64 * 1024
    const val DEFAULT_CHUNK_BYTES = 1024 * 1024
    const val MAX_CHUNK_BYTES = 4 * 1024 * 1024

    /** What sealing adds: the 12-byte nonce in front and the 16-byte GCM tag behind. */
    const val SEAL_OVERHEAD = 12 + 16

    /** The most any build accepts, whatever its settings say. */
    const val MAX_FILE_BYTES: Long = 4L * 1024 * 1024 * 1024

    /** Largest body of a message that is not a chunk. */
    const val MAX_CONTROL_BODY = 16 * 1024

    /**
     * The context that is authenticated together with a message: protocol, direction, kind, the
     * transfer and the chunk number. Never transmitted - both sides rebuild it - so a message
     * cannot be taken to another transfer or position, and a request cannot pass as a reply.
     * The index is written in decimal so that Kotlin and Rust cannot disagree about byte order.
     */
    fun context(label: String, reply: Boolean, transferId: String, index: Long): ByteArray {
        val direction = if (reply) "reply" else "request"
        return "clipsync-file-v1|$direction|$label|$transferId|$index".toByteArray(Charsets.UTF_8)
    }

    fun sealRequest(key: ByteArray, kind: FileKind, transferId: String, index: Long, plaintext: ByteArray): ByteArray =
        CryptoUtils.encryptAad(key, plaintext, context(kind.label, false, transferId, index))

    fun openRequest(key: ByteArray, kind: FileKind, transferId: String, index: Long, sealed: ByteArray): ByteArray =
        CryptoUtils.decryptAad(key, sealed, context(kind.label, false, transferId, index))

    fun sealReply(key: ByteArray, kind: FileKind, transferId: String, index: Long, plaintext: ByteArray): ByteArray =
        CryptoUtils.encryptAad(key, plaintext, context(kind.label, true, transferId, index))

    fun openReply(key: ByteArray, kind: FileKind, transferId: String, index: Long, sealed: ByteArray): ByteArray =
        CryptoUtils.decryptAad(key, sealed, context(kind.label, true, transferId, index))

    /** A transfer id is chosen by the sender (a UUID); the receiver uses it in file names and in the context above. */
    fun isValidTransferId(id: String): Boolean =
        id.length in 8..64 && id.all { (it in 'a'..'z') || (it in 'A'..'Z') || (it in '0'..'9') || it == '-' }

    fun hex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(digits[v shr 4]).append(digits[v and 15])
        }
        return sb.toString()
    }
}

/** Which of the four requests a message is. */
enum class FileKind(val path: String, val label: String) {
    OFFER("/file/offer", "offer"),
    CHUNK("/file/chunk", "chunk"),
    FINISH("/file/finish", "finish"),
    CANCEL("/file/cancel", "cancel");

    /** Largest request body (still sealed) a receiver reads for this kind of message. */
    val maxRequestBody: Int
        get() = if (this == CHUNK) FileProtocol.MAX_CHUNK_BYTES + FileProtocol.SEAL_OVERHEAD else FileProtocol.MAX_CONTROL_BODY

    companion object {
        fun fromPath(path: String): FileKind? = values().firstOrNull { it.path == path }
    }
}

/** An HTTP status and a short English explanation; the sender maps the status to what it tells the user. */
class FileException(val status: Int, message: String) : Exception(message)

// ---------------------------------------------------------------------------------------------
// Messages (the plaintext inside a sealed body). Unknown fields are ignored, so a later version can add to them.
// ---------------------------------------------------------------------------------------------

/** `POST /file/offer`: "I want to send you this file." */
data class Offer(val name: String, val size: Long, val chunkSize: Int, val modifiedAt: Long? = null) {
    fun toJson(): ByteArray = JSONObject().apply {
        put("name", name)
        put("size", size)
        put("chunk_size", chunkSize)
        if (modifiedAt != null) put("modified_at", modifiedAt)
    }.toString().toByteArray(Charsets.UTF_8)

    companion object {
        fun parse(json: ByteArray): Offer {
            val o = JSONObject(String(json, Charsets.UTF_8))
            return Offer(
                name = o.getString("name"),
                size = o.getLong("size"),
                chunkSize = o.getInt("chunk_size"),
                modifiedAt = if (o.has("modified_at") && !o.isNull("modified_at")) o.getLong("modified_at") else null,
            )
        }
    }
}

/** The answer to an offer: how many bytes (whole chunks, or all) the receiver already holds for this transfer id. */
data class OfferReply(val status: String, val received: Long, val chunkSize: Int, val savedAs: String? = null) {
    fun toJson(): ByteArray = JSONObject().apply {
        put("status", status)
        put("received", received)
        put("chunk_size", chunkSize)
        if (savedAs != null) put("saved_as", savedAs)
    }.toString().toByteArray(Charsets.UTF_8)

    companion object {
        fun parse(json: ByteArray): OfferReply {
            val o = JSONObject(String(json, Charsets.UTF_8))
            return OfferReply(
                status = o.getString("status"),
                received = o.getLong("received"),
                chunkSize = o.getInt("chunk_size"),
                savedAs = if (o.has("saved_as") && !o.isNull("saved_as")) o.getString("saved_as") else null,
            )
        }
    }
}

/** The answer to a chunk: how many bytes are stored now, in a row from the start. */
data class ChunkReply(val received: Long) {
    fun toJson(): ByteArray = JSONObject().put("received", received).toString().toByteArray(Charsets.UTF_8)

    companion object {
        fun parse(json: ByteArray): ChunkReply = ChunkReply(JSONObject(String(json, Charsets.UTF_8)).getLong("received"))
    }
}

/** `POST /file/finish`: "that was all; the whole file hashes to this." */
data class Finish(val sha256: String) {
    fun toJson(): ByteArray = JSONObject().put("sha256", sha256).toString().toByteArray(Charsets.UTF_8)

    companion object {
        fun parse(json: ByteArray): Finish = Finish(JSONObject(String(json, Charsets.UTF_8)).getString("sha256"))
    }
}

data class FinishReply(val status: String, val savedAs: String, val size: Long) {
    fun toJson(): ByteArray = JSONObject().apply {
        put("status", status)
        put("saved_as", savedAs)
        put("size", size)
    }.toString().toByteArray(Charsets.UTF_8)

    companion object {
        fun parse(json: ByteArray): FinishReply {
            val o = JSONObject(String(json, Charsets.UTF_8))
            return FinishReply(o.getString("status"), o.getString("saved_as"), o.getLong("size"))
        }
    }
}

/** `POST /file/cancel`: "stop and throw away what you have." The body carries nothing that matters. */
object CancelMessage {
    fun toJson(): ByteArray = "{}".toByteArray(Charsets.UTF_8)

    fun parse(json: ByteArray) {
        JSONObject(String(json, Charsets.UTF_8)) // must still be a JSON object
    }
}
