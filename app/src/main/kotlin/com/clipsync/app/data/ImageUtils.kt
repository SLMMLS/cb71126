package com.clipsync.app.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream

/** The image could not be used; the message is meant for the user. */
class ImageRejected(message: String) : Exception(message)

/** Decoding / re-encoding of images that come from outside the app (share sheet, clipboard, the PC). */
object ImageUtils {
    /** Input cap: whatever another app hands over is untrusted, never buffer it without a limit. */
    const val MAX_INPUT_BYTES = 40 * 1024 * 1024

    /** Longest side of an image that is stored/sent; bigger ones are subsampled instead of exhausting the heap. */
    const val MAX_SIDE = 4096

    /** What is sent: the receiving side rejects bodies above ~24 MB of base64. */
    const val MAX_PNG_BYTES = 12 * 1024 * 1024

    class Prepared(val png: ByteArray, val width: Int, val height: Int)

    /**
     * Largest power-of-two subsampling that still leaves the longest side <= [maxSide]
     * (used before storing / showing something full-size, so memory stays bounded).
     */
    fun sampleSizeToFit(width: Int, height: Int, maxSide: Int): Int {
        var sample = 1
        while (width / sample > maxSide || height / sample > maxSide) sample *= 2
        return sample
    }

    /**
     * Largest power-of-two subsampling that still leaves the longest side >= [targetSide]
     * (used for thumbnails: as small as is cheap, never blurrier than the target).
     */
    fun sampleSizeToCover(width: Int, height: Int, targetSide: Int): Int {
        val longest = maxOf(width, height)
        var sample = 1
        while (longest / (sample * 2) >= targetSide) sample *= 2
        return sample
    }

    /** Width and height read from the PNG header; null if this is not a PNG. */
    fun pngDimensions(png: ByteArray): Pair<Int, Int>? {
        if (png.size < 24) return null
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        for (i in signature.indices) if (png[i] != signature[i]) return null
        fun be(at: Int) = ((png[at].toInt() and 0xFF) shl 24) or ((png[at + 1].toInt() and 0xFF) shl 16) or
            ((png[at + 2].toInt() and 0xFF) shl 8) or (png[at + 3].toInt() and 0xFF)
        val w = be(16)
        val h = be(20)
        return if (w > 0 && h > 0) w to h else null
    }

    /** Reads at most [limit] bytes; null if the stream is missing or larger than that. */
    fun readLimited(resolver: ContentResolver, uri: Uri, limit: Int = MAX_INPUT_BYTES): ByteArray? {
        resolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val n = input.read(buffer)
                if (n == -1) break
                total += n
                if (total > limit) return null
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        }
        return null
    }

    private fun boundsOf(bytes: ByteArray): Pair<Int, Int>? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return if (bounds.outWidth > 0 && bounds.outHeight > 0) bounds.outWidth to bounds.outHeight else null
    }

    /** Decodes so the longest side is <= [maxSide]. */
    fun decodeToFit(bytes: ByteArray, maxSide: Int): Bitmap? {
        val (w, h) = boundsOf(bytes) ?: return null
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSizeToFit(w, h, maxSide) }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    /** Decodes cheaply, but not smaller than [targetSide] on the longest side. */
    fun decodeToCover(bytes: ByteArray, targetSide: Int): Bitmap? {
        val (w, h) = boundsOf(bytes) ?: return null
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSizeToCover(w, h, targetSide) }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    fun encodePng(bitmap: Bitmap): ByteArray =
        ByteArrayOutputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            stream.toByteArray()
        }

    /**
     * Content URI -> PNG ready to store and send. Only `content://` is accepted (a `file://`
     * URI handed over by another app could point at this app's own private files).
     * Throws [ImageRejected] with a user-facing message.
     */
    fun prepareFromUri(resolver: ContentResolver, uri: Uri): Prepared {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
            throw ImageRejected("Не удалось прочитать изображение")
        }
        val bytes = readLimited(resolver, uri) ?: throw ImageRejected("Не удалось прочитать изображение (повреждено или слишком большое)")
        val bitmap = decodeToFit(bytes, MAX_SIDE) ?: throw ImageRejected("Не удалось прочитать изображение (повреждено или слишком большое)")
        val png = encodePng(bitmap)
        if (png.size > MAX_PNG_BYTES) throw ImageRejected("Изображение слишком большое для отправки")
        return Prepared(png, bitmap.width, bitmap.height)
    }
}
