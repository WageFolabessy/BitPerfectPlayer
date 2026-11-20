package com.cangkir.bitperfectplayer.audio

/**
 * Pure-Kotlin representation of a local playable audio track shared across library, playback,
 * lyrics, artwork caching, and UI layers.
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
    val dateModifiedSeconds: Long = 0L,
    val durationMs: Long = 0L,
    val sampleRateHz: Int = 0,
    val bitDepth: Int? = null,
    val container: String = "AUDIO",
    val codec: String = "Unknown",
    val contentFingerprint: String? = null,
    val parentTreeUriString: String? = null,
    val relativePath: String? = null,
) {
    /**
     * Subtle non-redundant metadata line using exact Hz units (e.g., `FLAC · 24-bit · 96,000 Hz`).
     */
    val formatBadge: String
        get() = AudioFormatFormatter.formatTrackMetadataLine(
            container = container,
            codec = codec,
            bitDepth = bitDepth,
            sampleRateHz = sampleRateHz,
            separator = " · ",
        )
}
