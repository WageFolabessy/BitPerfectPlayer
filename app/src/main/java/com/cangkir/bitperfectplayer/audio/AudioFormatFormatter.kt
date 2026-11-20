package com.cangkir.bitperfectplayer.audio

import java.util.Locale

/**
 * Pure-Kotlin audio formatting helpers shared across domain models, diagnostics, and Compose UI.
 *
 * Enforces exact `Hz` sample-rate notation (e.g., `96,000 Hz`, `48,000 Hz`, `44,100 Hz`) and
 * eliminates redundant container/codec labels such as `FLAC (FLAC)`.
 */
object AudioFormatFormatter {

    fun formatSampleRateHz(sampleRateHz: Int): String =
        if (sampleRateHz <= 0) {
            "Unknown Hz"
        } else {
            String.format(Locale.US, "%,d Hz", sampleRateHz)
        }

    fun formatContainerAndCodec(container: String, codec: String?): String {
        val cleanContainer = container.trim().ifEmpty { "AUDIO" }
        val cleanCodec = codec?.trim().orEmpty()
        if (cleanCodec.isEmpty() ||
            cleanCodec.equals("Unknown", ignoreCase = true) ||
            cleanCodec.equals(cleanContainer, ignoreCase = true)
        ) {
            return cleanContainer
        }
        return "$cleanContainer ($cleanCodec)"
    }

    fun formatTrackMetadataLine(
        container: String,
        codec: String? = null,
        bitDepth: Int? = null,
        sampleRateHz: Int = 0,
        separator: String = " · ",
    ): String {
        val parts = mutableListOf(formatContainerAndCodec(container, codec))
        if (bitDepth != null && bitDepth > 0) {
            parts.add("${bitDepth}-bit")
        }
        if (sampleRateHz > 0) {
            parts.add(formatSampleRateHz(sampleRateHz))
        }
        return parts.joinToString(separator)
    }
}
