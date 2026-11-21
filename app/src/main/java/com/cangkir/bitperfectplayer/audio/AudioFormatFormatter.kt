package com.cangkir.bitperfectplayer.audio

/**
 * Pure-Kotlin audio formatting helpers shared across domain models, diagnostics, and Compose UI.
 *
 * Enforces exact `Hz` sample-rate notation (e.g., `96,000 Hz`, `48,000 Hz`, `44,100 Hz`),
 * eliminates redundant container/codec labels (`FLAC (FLAC)` -> `FLAC`), and avoids heavyweight
 * `String.format` allocations during fast list scrolling.
 */
object AudioFormatFormatter {

    fun formatSampleRateHz(sampleRateHz: Int): String {
        if (sampleRateHz <= 0) return "Unknown Hz"
        val digits = sampleRateHz.toString()
        val len = digits.length
        val sb = StringBuilder(len + (len - 1) / 3 + 3)
        for (i in 0 until len) {
            if (i > 0 && (len - i) % 3 == 0) {
                sb.append(',')
            }
            sb.append(digits[i])
        }
        sb.append(" Hz")
        return sb.toString()
    }

    /**
     * Lightweight allocation-minimal `mm:ss` / `h:mm:ss` formatter avoiding `String.format`.
     */
    fun formatDurationMs(durationMs: Long): String {
        val totalSeconds = durationMs.coerceAtLeast(0L) / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return buildString(8) {
            if (hours > 0L) {
                append(hours)
                append(':')
            }
            if (minutes < 10L) append('0')
            append(minutes)
            append(':')
            if (seconds < 10L) append('0')
            append(seconds)
        }
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
            parts.add("$bitDepth-bit")
        }
        if (sampleRateHz > 0) {
            parts.add(formatSampleRateHz(sampleRateHz))
        }
        return parts.joinToString(separator)
    }
}
