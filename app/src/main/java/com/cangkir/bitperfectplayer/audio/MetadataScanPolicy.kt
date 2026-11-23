package com.cangkir.bitperfectplayer.audio

/**
 * Pure-Kotlin metadata scan fast-path policy (zero `android.*` imports) governing when
 * heavyweight `MediaMetadataRetriever` Binder IPC calls can be safely skipped during
 * MediaStore scans.
 */
object MetadataScanPolicy {

    /**
     * Normalizes a raw MediaStore cursor tag (`TITLE`, `ARTIST`, `ALBUM`), stripping control
     * characters, collapsing embedded newlines/tabs, and rejecting blanks and `<unknown>`.
     */
    fun sanitizeCursorTag(raw: String?): String? {
        if (raw == null) return null
        val cleaned = buildString(raw.length) {
            var lastWasSpace = true
            for (ch in raw) {
                val mapped = when {
                    ch in '\u0000'..'\u001F' || ch == '\u007F' -> {
                        if (ch == '\t' || ch == '\n' || ch == '\r') ' ' else continue
                    }
                    ch.isWhitespace() -> ' '
                    else -> ch
                }
                if (mapped == ' ') {
                    if (!lastWasSpace) {
                        append(' ')
                        lastWasSpace = true
                    }
                } else {
                    append(mapped)
                    lastWasSpace = false
                }
            }
        }.trim()
        return cleaned.takeIf { it.isNotEmpty() && !it.equals("<unknown>", ignoreCase = true) }
    }

    /**
     * Returns `true` when `MediaMetadataRetriever` can be completely skipped for a MediaStore
     * track because essential metadata (`sampleRateHz > 0` and `durationMs > 0`) is already
     * resolved via fast direct `FileChannel` header parsing (`FlacStreamInfoParser` /
     * `WavHeaderParser`) and MediaStore cursor columns.
     */
    fun canSkipMediaMetadataRetriever(hasCursorMetadata: Boolean, sampleRateHz: Int, durationMs: Long): Boolean =
        hasCursorMetadata && sampleRateHz > 0 && durationMs > 0L
}
