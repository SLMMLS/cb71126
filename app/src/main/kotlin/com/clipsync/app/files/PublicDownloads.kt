package com.clipsync.app.files

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.annotation.RequiresApi
import java.io.File
import java.io.IOException

/**
 * Where a received file ends up: the public Downloads folder, in a `ClipSync` subfolder, where the
 * person (and the Files app, and the Downloads screen) will look for it.
 *
 * * **Android 10 and later:** through MediaStore. No storage permission is needed for an app to add its own
 *   files there; the file is hidden (`IS_PENDING`) while it is being written, and MediaStore itself picks a
 *   free name (`name (1).ext`) when one is taken.
 * * **Android 9 (minSdk 28):** public Downloads would need a storage permission this app does not otherwise
 *   ask for, so the file goes to the app's own Downloads folder (`Android/data/<app>/files/Download/ClipSync`).
 *   It is saved all the same; it just cannot be opened from inside the app on that version.
 */
class PublicDownloads(private val context: Context) : FinalSink {
    override fun publish(partial: File, name: String, modifiedAt: Long?): FinalSink.Published =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) publishToDownloads(partial, name) else publishToAppFolder(partial, name, modifiedAt)

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun publishToDownloads(partial: File, name: String): FinalSink.Published {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, FileNames.mimeType(name))
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/ClipSync")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("не удалось создать файл в «Загрузках»")
        try {
            val out = resolver.openOutputStream(uri, "w") ?: throw IOException("не удалось открыть файл в «Загрузках» для записи")
            out.use { stream -> partial.inputStream().use { input -> input.copyTo(stream, 256 * 1024) } }
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        } catch (e: Exception) {
            // Never leave a half-written, invisible entry behind.
            runCatching { resolver.delete(uri, null, null) }
            throw if (e is IOException) e else IOException(e.message ?: e.javaClass.simpleName, e)
        }
        val savedName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: name
        return FinalSink.Published(savedName, uri.toString())
    }

    private fun publishToAppFolder(partial: File, name: String, modifiedAt: Long?): FinalSink.Published {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        val dir = File(base, "ClipSync").also { it.mkdirs() }
        for (n in 0..10_000) {
            val target = File(dir, FileNames.numbered(name, n))
            if (!target.createNewFile()) continue
            try {
                partial.inputStream().use { input -> target.outputStream().use { input.copyTo(it, 256 * 1024) } }
            } catch (e: IOException) {
                target.delete()
                throw e
            }
            if (modifiedAt != null && modifiedAt > 631_152_000_000L && modifiedAt < System.currentTimeMillis() + 86_400_000L) {
                target.setLastModified(modifiedAt)
            }
            return FinalSink.Published(target.name, target.absolutePath)
        }
        throw IOException("слишком много файлов с таким именем")
    }

    companion object {
        /** A sentence for the screen: where received files are. */
        fun whereToLook(): String =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "Загрузки/ClipSync" else "Android/data/com.clipsync.app/files/Download/ClipSync"
    }
}
