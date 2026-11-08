package com.clipsync.app.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/** A file read through a content:// address (the file picker). Reading is sequential, so any provider works. */
class UriSource(
    private val context: Context,
    val uri: Uri,
    override val name: String,
    override val size: Long,
    override val modifiedAt: Long? = null,
) : FileSource {
    override fun open(): InputStream =
        context.contentResolver.openInputStream(uri) ?: throw IOException("приложение, отдавшее файл, не открыло его")
}

/**
 * A private copy of a file, made so that sending does not depend on the app that handed the file over:
 * the access a "Share" gives lasts only as long as the receiving screen, and a transfer (and its retries)
 * outlives that. Deleted when the transfer is done or its row is removed.
 */
class StagedSource(val file: File, override val name: String, override val modifiedAt: Long? = null) : FileSource {
    override val size: Long get() = file.length()

    override fun open(): InputStream = FileInputStream(file)

    fun delete() {
        file.delete()
    }
}

/** Turns what the system gives us (picked or shared addresses) into [FileSource]s - without ever trusting the address blindly. */
object FileSources {
    private const val STAGING_DIR = "outgoing"
    private const val STALE_MS = 24L * 60 * 60 * 1000

    class Described(val name: String, /** -1 when the app that owns the file does not say. */ val size: Long)

    /** Only content:// is opened. A file:// address from another app could point at this app's own private files. */
    fun isAcceptable(uri: Uri): Boolean = uri.scheme == "content"

    fun describe(context: Context, uri: Uri): Described {
        var name: String? = null
        var size = -1L
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val s = c.getColumnIndex(OpenableColumns.SIZE)
                    if (n >= 0 && !c.isNull(n)) name = c.getString(n)
                    if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
                }
            }
        } catch (_: Exception) {
            // The provider does not answer queries: fall back to what the address says.
        }
        return Described(name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file", size)
    }

    /**
     * A file chosen in the picker: read from where it is (the picker's grant is asked to be kept, so a retry
     * later still works). When the owner does not know the size, a copy is made - the protocol needs the size first.
     */
    fun fromPicker(context: Context, uri: Uri): FileSource {
        require(isAcceptable(uri)) { "этот адрес файла не поддерживается" }
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
            // Not every provider allows it; the grant of the picker still lasts while the app is running.
        }
        val described = describe(context, uri)
        if (described.size < 0) return stage(context, uri, described.name)
        if (described.size > FileProtocol.MAX_FILE_BYTES) throw IOException("«${described.name}» больше 4 ГБ")
        return UriSource(context, uri, described.name, described.size)
    }

    /** Copies [uri] into the app's cache. Throws a sentence the person can read when it cannot. */
    fun stage(context: Context, uri: Uri, knownName: String? = null): StagedSource {
        require(isAcceptable(uri)) { "этот адрес файла не поддерживается" }
        val described = describe(context, uri)
        val name = knownName ?: described.name
        val declared = described.size
        val dir = File(context.cacheDir, STAGING_DIR).also { it.mkdirs() }
        val copy = File(dir, UUID.randomUUID().toString())
        if (declared > FileProtocol.MAX_FILE_BYTES) throw IOException("«$name» больше 4 ГБ")
        if (declared > 0 && dir.usableSpace < declared + 64L * 1024 * 1024) throw IOException("на телефоне не хватает места, чтобы подготовить «$name»")
        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("не удалось открыть «$name»")
            var total = 0L
            input.use { src ->
                copy.outputStream().use { dst ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val n = src.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > FileProtocol.MAX_FILE_BYTES) throw IOException("«$name» больше 4 ГБ")
                        dst.write(buffer, 0, n)
                    }
                }
            }
        } catch (e: Exception) {
            copy.delete()
            throw if (e is IOException) e else IOException("не удалось прочитать «$name»: ${e.message ?: e.javaClass.simpleName}", e)
        }
        return StagedSource(copy, name)
    }

    /** At start-up: copies that nobody deleted (the app was killed mid-transfer) are removed after a day. */
    fun cleanStale(context: Context) {
        val now = System.currentTimeMillis()
        File(context.cacheDir, STAGING_DIR).listFiles()?.forEach { f ->
            if (f.isFile && now - f.lastModified() > STALE_MS) f.delete()
        }
    }
}
