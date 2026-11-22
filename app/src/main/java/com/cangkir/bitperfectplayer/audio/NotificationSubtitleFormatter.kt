package com.cangkir.bitperfectplayer.audio

import java.util.Locale

/**
 * Pure-Kotlin formatter building concise audiophile media-notification & lock-screen subtitles
 * (`Artist · Codec Bit-Depth · Sample Rate`) with zero `android.*` imports and zero locale-dependent
 * decimal separators.
 *
 * Examples:
 * - `Green Day · FLAC 16-bit · 44.1 kHz`
 * - `The Beatles · FLAC 24-bit · 96 kHz`
 * - `Artist Name · WAV 24-bit · 48 kHz`
 * - `Artist Name · MP3 · 44.1 kHz`
 * - `Artist Name` (when audio format metadata is unavailable)
 */
object NotificationSubtitleFormatter {
    const val DEFAULT_IDLE_SUBTITLE = "Offline Bit-Perfect Audio"
    const val UNKNOWN_ARTIST_LABEL = "Unknown Artist"

    fun format(
        track: PlaybackTrack?,
        runtimeSampleRateHz: Int = 0,
        runtimeBitDepth: Int? = null,
        fallbackIdleSubtitle: String = DEFAULT_IDLE_SUBTITLE,
    ): String {
        if (track == null) return fallbackIdleSubtitle
        val effectiveRate = runtimeSampleRateHz.takeIf { it > 0 } ?: track.sampleRateHz
        val effectiveBitDepth = runtimeBitDepth?.takeIf { it > 0 } ?: track.bitDepth
        return format(
            artist = track.artist,
            container = track.container,
            codec = track.codec,
            bitDepth = effectiveBitDepth,
            sampleRateHz = effectiveRate,
        )
    }

    fun format(artist: String?, container: String?, codec: String?, bitDepth: Int? = null, sampleRateHz: Int = 0): String {
        val cleanArtist = sanitizeArtist(artist)
        val formatLabel = resolveConciseFormatLabel(container = container, codec = codec)
        val validBitDepth = bitDepth?.takeIf { it > 0 }
        val formatWithDepth = when {
            formatLabel != null && validBitDepth != null -> "$formatLabel $validBitDepth-bit"
            formatLabel != null -> formatLabel
            validBitDepth != null -> "$validBitDepth-bit"
            else -> null
        }
        val rateLabel = formatCompactSampleRateKhz(sampleRateHz)

        val parts = ArrayList<String>(3)
        parts.add(cleanArtist)
        if (formatWithDepth != null) parts.add(formatWithDepth)
        if (rateLabel != null) parts.add(rateLabel)
        return parts.joinToString(separator = " · ")
    }

    /**
     * Locale-independent compact `kHz` formatter strictly for media notifications:
     * `44_100 -> "44.1 kHz"`, `48_000 -> "48 kHz"`, `96_000 -> "96 kHz"`, `176_400 -> "176.4 kHz"`.
     */
    fun formatCompactSampleRateKhz(sampleRateHz: Int): String? {
        if (sampleRateHz <= 0) return null
        if (sampleRateHz < 1_000) return "$sampleRateHz Hz"
        val wholeKhz = sampleRateHz / 1_000
        val remainderHz = sampleRateHz % 1_000
        if (remainderHz == 0) return "$wholeKhz kHz"

        val paddedFraction = remainderHz.toString().padStart(3, '0').trimEnd('0')
        return "$wholeKhz.$paddedFraction kHz"
    }

    private fun sanitizeArtist(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed.equals("<unknown>", ignoreCase = true)) {
            return UNKNOWN_ARTIST_LABEL
        }
        return trimmed
    }

    private fun resolveConciseFormatLabel(container: String?, codec: String?): String? {
        val cleanContainer = container?.trim().orEmpty()
        val upperContainer = cleanContainer.uppercase(Locale.ROOT)
        val cleanCodec = codec?.trim().orEmpty()
        val upperCodec = cleanCodec.uppercase(Locale.ROOT)

        val validContainer = cleanContainer.takeIf {
            it.isNotEmpty() && upperContainer != "AUDIO" && upperContainer != "UNKNOWN"
        }
        val validCodec = cleanCodec.takeIf {
            it.isNotEmpty() && upperCodec != "UNKNOWN" && upperCodec != "AUDIO"
        }

        return when (upperContainer) {
            "FLAC" -> "FLAC"
            "WAV" -> "WAV"
            "MP3" -> "MP3"
            "M4A" -> when {
                upperCodec.contains("AAC") -> "AAC"
                validCodec != null -> validCodec
                else -> "M4A"
            }
            "OGG" -> when {
                upperCodec.contains("OPUS") -> "Opus"
                upperCodec.contains("VORBIS") -> "Vorbis"
                else -> "OGG"
            }
            else -> validContainer ?: validCodec
        }
    }
}
