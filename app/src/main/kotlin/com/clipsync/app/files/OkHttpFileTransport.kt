package com.clipsync.app.files

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * The phone's real transport to the PC: HTTPS with the PC's certificate pinned (the client comes from
 * `SyncClient.filesClient`, built once per transfer so that the connection is reused for every chunk).
 * A network failure is an `IOException` from OkHttp, which [FileSender] treats as "try again".
 */
class OkHttpFileTransport(
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val myDeviceId: String,
) : FileTransport {
    private val octetStream = "application/octet-stream".toMediaType()

    /** Telling the PC "cancelled" must not keep the person waiting. */
    private val shortClient: OkHttpClient by lazy { client.newBuilder().callTimeout(4, TimeUnit.SECONDS).build() }

    override fun post(kind: FileKind, transferId: String, index: Long, body: ByteArray): FileTransport.FileResponse {
        val request = Request.Builder()
            .url(baseUrl + kind.path)
            .header("X-Device-Id", myDeviceId)
            .header("X-Transfer-Id", transferId)
            .apply { if (kind == FileKind.CHUNK) header("X-Chunk-Index", index.toString()) }
            .post(body.toRequestBody(octetStream))
            .build()
        val chosen = if (kind == FileKind.CANCEL) shortClient else client
        chosen.newCall(request).execute().use { response ->
            return FileTransport.FileResponse(response.code, response.body?.bytes() ?: ByteArray(0))
        }
    }
}
