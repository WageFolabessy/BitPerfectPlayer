package com.cangkir.bitperfectplayer.audio

/**
 * Pure-Kotlin representation of a local playable audio track shared across library, playback,
 * lyrics, and UI layers.
 */
data class PlaybackTrack(
    val uriString: String,
    val title: String,
    val artist: String = "Unknown Artist",
    val album: String = "Unknown Album",
    val folderName: String = "Internal Storage",
    val fileName: String,
    val mimeType: String? = null,
    val fileSizeBytes: Long = 0L,
    val durationMs: Long = 0L,
    val sampleRateHz: Int = 0,
    val bitDepth: Int? = null,
    val container: String = "AUDIO",
    val codec: String = "Unknown",
    val contentFingerprint: String? = null,
    val parentTreeUriString: String? = null,
    val relativePath: String? = null,
) {
    val formatBadge: String
        get() {
            val parts = mutableListOf(container)
            if (bitDepth != null && bitDepth > 0) {
                parts.add("${bitDepth}-bit")
            }
            if (sampleRateHz > 0) {
                val khz = if (sampleRateHz % 1000 == 0) {
                    "${sampleRateHz / 1000} kHz"
                } else {
                    String.format(java.util.Locale.US, "%.1f kHz", sampleRateHz / 1000.0)
                }
                parts.add(khz)
            }
            return parts.joinToString(" • ")
        }
}
