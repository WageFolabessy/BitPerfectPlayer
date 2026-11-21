package com.cangkir.bitperfectplayer.audio

/**
 * Pure-Kotlin metadata scan fast-path policy (zero `android.*` imports) governing when
 * heavyweight `MediaMetadataRetriever` Binder IPC calls can be safely skipped during
 * MediaStore scans.
 */
object MetadataScanPolicy {

    /**
     * Normalizes a raw MediaStore cursor tag (`TITLE`, `ARTIST`, `ALBUM`), rejecting blanks
     * and Android's `<unknown>` sentinel.
     */
    fun sanitizeCursorTag(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() && !it.equals("<unknown>", ignoreCase = true) }

    /**
     * Returns `true` when `MediaMetadataRetriever` can be completely skipped for a MediaStore
     * track because essential metadata (`sampleRateHz > 0` and `durationMs > 0`) is already
     * resolved via fast direct `FileChannel` header parsing (`FlacStreamInfoParser` /
     * `WavHeaderParser`) and MediaStore cursor columns.
     */
    fun canSkipMediaMetadataRetriever(hasCursorMetadata: Boolean, sampleRateHz: Int, durationMs: Long): Boolean =
        hasCursorMetadata && sampleRateHz > 0 && durationMs > 0L
}
