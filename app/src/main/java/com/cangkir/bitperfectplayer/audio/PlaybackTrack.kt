package com.cangkir.bitperfectplayer.audio

/**
 * Pure-Kotlin representation of a local playable audio track shared across library, playback,
 * lyrics, artwork caching, and UI layers.
 *
 * Precomputes UI display strings (`formatBadge`, `durationLabel`, `artistAlbumLine`) once upon
 * construction so `LazyColumn` rows perform zero formatting allocations while scrolling.
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
    val discNumber: Int = 0,
    val trackNumber: Int = 0,
    val year: Int = 0,
) {
    /**
     * Precomputed subtle non-redundant metadata line using exact Hz units (e.g., `FLAC · 24-bit · 96,000 Hz`).
     */
    val formatBadge: String = AudioFormatFormatter.formatTrackMetadataLine(
        container = container,
        codec = codec,
        bitDepth = bitDepth,
        sampleRateHz = sampleRateHz,
        separator = " · ",
    )

    /**
     * Precomputed non-wrapping duration label (`mm:ss` / `h:mm:ss`), or empty when unknown.
     */
    val durationLabel: String = if (durationMs > 0L) {
        AudioFormatFormatter.formatDurationMs(durationMs)
    } else {
        ""
    }

    /**
     * Precomputed secondary artist/album line for smooth list scrolling.
     */
    val artistAlbumLine: String = run {
        val cleanArtist = artist.trim().ifEmpty { "Unknown Artist" }
        val cleanAlbum = album.trim().ifEmpty { "Unknown Album" }
        "$cleanArtist · $cleanAlbum"
    }

    /**
     * Precomputed concise track-position badge for Album detail rows (e.g., `"3"`, `"2·04"`),
     * or empty when track number is absent.
     */
    val trackNumberLabel: String = when {
        trackNumber <= 0 -> ""
        discNumber > 1 -> "$discNumber·$trackNumber"
        else -> "$trackNumber"
    }
}
