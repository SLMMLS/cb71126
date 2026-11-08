package com.clipsync.app.files

/**
 * What a file name that arrives over the network may look like - the Kotlin twin of
 * `sanitize_file_name` in `fileproto.rs`. Both must give the same answer for the same input (the
 * tests on both sides run the same list of vectors), because the name a person sees on one device
 * should be the name they find on the other.
 *
 * The rules, in short: only the last path component is kept (`..\..\evil.exe` and `/etc/passwd`
 * lose their folders); control characters and `< > : " | ? *` become `_`; look-alike tricks
 * (the right-to-left override, zero-width characters) are removed; trailing dots and spaces go;
 * Windows device names (`CON`, `nul.txt`) get a `_` in front; the length is limited, keeping the
 * extension; an empty result becomes `file`.
 */
object FileNames {
    /** Longest name, in UTF-8 bytes. The folder path comes on top of it, and Windows stops at ~260 characters. */
    private const val MAX_NAME_BYTES = 150

    /** An "extension" longer than this is not treated as one when a long name is shortened. */
    private const val MAX_EXTENSION_BYTES = 16

    private val RESERVED: Set<String> = buildSet {
        addAll(listOf("CON", "PRN", "AUX", "NUL"))
        for (i in 1..9) {
            add("COM$i")
            add("LPT$i")
        }
    }

    private val RISKY_EXTENSIONS: Set<String> = setOf(
        "exe", "msi", "msp", "bat", "cmd", "com", "scr", "pif", "ps1", "psm1", "psd1", "vbs", "vbe", "js", "jse", "wsf",
        "wsh", "hta", "lnk", "url", "reg", "jar", "dll", "cpl", "msc", "apk", "xapk", "appx", "msix", "application",
        "gadget", "inf", "ins", "isp", "sct", "shb", "ws", "py", "pyw", "sh", "bash", "command", "workflow", "app",
        "dmg", "iso", "vhd", "vhdx", "chm",
    )

    /**
     * Rust's `char::is_whitespace` (the Unicode White_Space property). Kotlin's own `isWhitespace()`
     * differs at the edges (it counts U+001C..U+001F and not U+00A0), so the exact list is spelled out.
     */
    private fun isWhiteSpace(cp: Int): Boolean =
        cp in 0x09..0x0D || cp == 0x20 || cp == 0x85 || cp == 0xA0 || cp == 0x1680 || cp in 0x2000..0x200A ||
            cp == 0x2028 || cp == 0x2029 || cp == 0x202F || cp == 0x205F || cp == 0x3000

    /** Characters that make a name look like something it is not (see `is_deceptive` in `fileproto.rs`). */
    private fun isDeceptive(cp: Int): Boolean =
        cp in 0x202A..0x202E || cp in 0x2066..0x2069 || cp in 0x200B..0x200F || cp == 0x2060 || cp == 0xFEFF

    private fun isControl(cp: Int): Boolean = Character.getType(cp) == Character.CONTROL.toInt()

    private fun codePoints(text: String): IntArray {
        val out = IntArray(text.codePointCount(0, text.length))
        var i = 0
        var n = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            out[n++] = cp
            i += Character.charCount(cp)
        }
        return out
    }

    private fun fromCodePoints(cps: List<Int>): String {
        val sb = StringBuilder()
        for (cp in cps) sb.appendCodePoint(cp)
        return sb.toString()
    }

    private fun isBlank(text: String): Boolean = codePoints(text).all { isWhiteSpace(it) }

    private fun trim(text: String): String {
        val cps = codePoints(text)
        var start = 0
        var end = cps.size
        while (start < end && isWhiteSpace(cps[start])) start++
        while (end > start && isWhiteSpace(cps[end - 1])) end--
        return fromCodePoints(cps.slice(start until end))
    }

    private fun trimEnd(text: String): String {
        val cps = codePoints(text)
        var end = cps.size
        while (end > 0 && isWhiteSpace(cps[end - 1])) end--
        return fromCodePoints(cps.slice(0 until end))
    }

    private fun utf8Length(cp: Int): Int = when {
        cp < 0x80 -> 1
        cp < 0x800 -> 2
        cp < 0x10000 -> 3
        else -> 4
    }

    private fun utf8Length(text: String): Int = codePoints(text).sumOf { utf8Length(it) }

    private fun asciiUpper(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) sb.append(if (ch in 'a'..'z') (ch - 32) else ch)
        return sb.toString()
    }

    private fun asciiLower(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) sb.append(if (ch in 'A'..'Z') (ch + 32) else ch)
        return sb.toString()
    }

    /** The cleaned name: safe to create in any folder on Windows or Android. Idempotent. */
    fun sanitize(raw: String): String {
        // Only the last path component that is not empty.
        val segment = raw.split('/', '\\').asReversed().firstOrNull { !isBlank(it) } ?: ""

        val mapped = ArrayList<Int>()
        for (cp in codePoints(segment)) {
            if (isDeceptive(cp)) continue
            val forbidden = cp < 0x80 && cp.toChar() in "<>:\"|?*"
            mapped.add(if (isControl(cp) || forbidden) '_'.code else cp)
        }
        var name = trim(fromCodePoints(mapped))
        while (name.endsWith('.') || name.endsWith(' ')) name = name.dropLast(1)
        if (name.isEmpty()) return "file"

        // Windows reserves the device names with or without any extension ("con", "NUL.txt").
        val stem = trimEnd(name.substringBefore('.'))
        if (asciiUpper(stem) in RESERVED) name = "_$name"

        return shorten(name, MAX_NAME_BYTES)
    }

    private fun shorten(name: String, maxBytes: Int): String {
        if (utf8Length(name) <= maxBytes) return name
        val (stem, ext) = splitExtension(name)
        val extBytes = utf8Length(ext) + 1
        val extPart = if (extBytes <= MAX_EXTENSION_BYTES && extBytes < maxBytes) ".$ext" else ""
        val base = if (extPart.isEmpty()) name else stem
        val budget = maxBytes - utf8Length(extPart)

        val kept = ArrayList<Int>()
        var used = 0
        for (cp in codePoints(base)) {
            val next = utf8Length(cp)
            if (used + next > budget) break
            kept.add(cp)
            used += next
        }
        var short = trimEnd(fromCodePoints(kept))
        if (short.isEmpty()) short = "file"
        return short + extPart
    }

    /** `("archive.tar", "gz")`, `("README", "")`, `(".bashrc", "")`. */
    fun splitExtension(name: String): Pair<String, String> {
        val dot = name.lastIndexOf('.')
        return if (dot > 0 && dot + 1 < name.length) name.substring(0, dot) to name.substring(dot + 1) else name to ""
    }

    /** `name`, `name (1)`, `name (2)`... with the extension kept at the end. */
    fun numbered(name: String, n: Int): String {
        if (n == 0) return name
        val (stem, ext) = splitExtension(name)
        return if (ext.isEmpty()) "$stem ($n)" else "$stem ($n).$ext"
    }

    /**
     * Files that Windows (or Android) would *run* when opened. ClipSync saves them like any other
     * file but never opens them for you: you can still open the folder and decide.
     */
    fun isRisky(name: String): Boolean = asciiLower(splitExtension(name).second) in RISKY_EXTENSIONS

    /** A MIME type for the file manager and for "Открыть"; unknown extensions are plain bytes. */
    fun mimeType(name: String): String {
        val ext = asciiLower(splitExtension(name).second)
        return MIME[ext] ?: "application/octet-stream"
    }

    private val MIME: Map<String, String> = mapOf(
        "txt" to "text/plain", "md" to "text/markdown", "csv" to "text/csv", "html" to "text/html", "htm" to "text/html",
        "json" to "application/json", "xml" to "application/xml", "pdf" to "application/pdf",
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "gif" to "image/gif", "webp" to "image/webp",
        "bmp" to "image/bmp", "heic" to "image/heic", "svg" to "image/svg+xml",
        "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "wav" to "audio/wav", "ogg" to "audio/ogg", "flac" to "audio/flac",
        "mp4" to "video/mp4", "mkv" to "video/x-matroska", "webm" to "video/webm", "mov" to "video/quicktime",
        "avi" to "video/x-msvideo", "zip" to "application/zip", "gz" to "application/gzip", "7z" to "application/x-7z-compressed",
        "rar" to "application/vnd.rar", "tar" to "application/x-tar",
        "doc" to "application/msword", "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel", "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint", "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    )
}
