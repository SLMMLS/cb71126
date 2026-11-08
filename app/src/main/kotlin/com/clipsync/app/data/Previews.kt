package com.clipsync.app.data

object Previews {
    /** One-line-ish preview for a list row: whitespace (including line breaks) collapsed, cut at [maxChars]. */
    fun make(text: String, maxChars: Int = 280): String {
        // Look at a bounded prefix only: a 5 MB log must not be regex-processed just to draw two lines of it.
        val head = text.take(maxChars * 4)
        val inputWasCut = head.length < text.length
        val collapsed = head.trim().replace(Regex("\\s+"), " ")
        return when {
            collapsed.length > maxChars -> collapsed.take(maxChars).trimEnd() + "\u2026"
            inputWasCut -> collapsed + "\u2026"
            else -> collapsed
        }
    }
}
