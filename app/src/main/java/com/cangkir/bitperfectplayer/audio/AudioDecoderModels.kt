package com.cangkir.bitperfectplayer.audio

import com.cangkir.bitperfectplayer.lyrics.SeekableByteSource
import java.io.Closeable
import java.io.IOException

/**
 * Pure-Kotlin PCM encoding representation mapped to Android [android.media.AudioFormat] constants
 * with API-level guards at the Android engine boundary.
 */
enum class PcmEncodingType(
    val displayLabel: String,
    val bytesPerSample: Int,
    val effectiveBitDepth: Int,
) {
    PCM_16BIT("PCM_16BIT", bytesPerSample = 2, effectiveBitDepth = 16),
    PCM_24BIT_PACKED("PCM_24BIT_PACKED", bytesPerSample = 3, effectiveBitDepth = 24),
    PCM_32BIT("PCM_32BIT", bytesPerSample = 4, effectiveBitDepth = 32),
    PCM_FLOAT("PCM_FLOAT (32-bit IEEE)", bytesPerSample = 4, effectiveBitDepth = 24),
}

/**
 * Verified runtime format produced by an active [PcmAudioDecoder].
 */
data class DecodedPcmFormat(
    val sampleRateHz: Int,
    val channelCount: Int,
    val encoding: PcmEncodingType,
    val effectiveBitDepth: Int,
    val decoderName: String,
    val durationMs: Long,
    val sourceContainer: String,
    val sourceCodec: String,
    val sourceSampleRateHz: Int,
    val sourceBitDepth: Int?,
    val sourceHeaderVerified: Boolean,
) {
    val bytesPerFrame: Int
        get() = channelCount * encoding.bytesPerSample
}

/**
 * Typed decoder failure hierarchy so corrupted or unsupported audio files never crash playback.
 */
sealed class AudioDecodeException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause) {
    class UnsupportedFormat(message: String, cause: Throwable? = null) :
        AudioDecodeException(message, cause)

    class CorruptedFile(message: String, cause: Throwable? = null) :
        AudioDecodeException(message, cause)

    class IoError(message: String, cause: Throwable? = null) :
        AudioDecodeException(message, cause)

    class DecoderInitFailed(message: String, cause: Throwable? = null) :
        AudioDecodeException(message, cause)
}

/**
 * Streaming PCM audio decoder abstraction shared by [WavPcmDecoder] and [MediaCodecAudioDecoder].
 */
interface PcmAudioDecoder : Closeable {
    val format: DecodedPcmFormat
    val currentPositionMs: Long

    /**
     * Reads decoded PCM bytes into [destination]. Returns bytes written, `0` if temporarily
     * waiting for codec buffers, or `-1` on end-of-stream.
     */
    @Throws(AudioDecodeException::class)
    fun readPcm(destination: ByteArray): Int

    /**
     * Seeks playback to [positionMs] and returns the actual resulting position in milliseconds.
     */
    @Throws(AudioDecodeException::class)
    fun seekToMs(positionMs: Long): Long
}

/**
 * Supported MVP audio container formats (SPEC Section 3.4).
 */
enum class SupportedAudioFormat(
    val containerLabel: String,
    val defaultCodecLabel: String,
    val isLossless: Boolean,
) {
    FLAC("FLAC", "FLAC", isLossless = true),
    WAV("WAV", "PCM", isLossless = true),
    MP3("MP3", "MP3", isLossless = false),
    M4A("M4A", "AAC", isLossless = false),
    OGG("OGG", "Vorbis/Opus", isLossless = false),
}

/**
 * Pure-Kotlin classifier strictly limiting library scanning and playback to the 5 MVP formats
 * (FLAC, WAV, MP3, M4A/AAC, OGG Vorbis/Opus) while excluding DSD, ALAC, APE, WavPack, and `.cue`.
 */
object AudioFormatClassifier {
    private val EXTENSION_MAP = mapOf(
        "flac" to SupportedAudioFormat.FLAC,
        "wav" to SupportedAudioFormat.WAV,
        "wave" to SupportedAudioFormat.WAV,
        "mp3" to SupportedAudioFormat.MP3,
        "m4a" to SupportedAudioFormat.M4A,
        "ogg" to SupportedAudioFormat.OGG,
        "oga" to SupportedAudioFormat.OGG,
        "opus" to SupportedAudioFormat.OGG,
    )

    private val EXCLUDED_EXTENSIONS = setOf(
        "dsf", "dff", "ape", "wv", "wvc", "alac", "cue", "lrc", "txt", "jpg", "png", "mp4", "mkv",
    )

    fun classify(fileName: String?, mimeType: String? = null): SupportedAudioFormat? {
        val ext = fileName
            ?.substringAfterLast('.', missingDelimiterValue = "")
            ?.trim()
            ?.lowercase()
            .orEmpty()

        if (ext in EXCLUDED_EXTENSIONS) return null
        EXTENSION_MAP[ext]?.let { return it }

        val mime = mimeType?.trim()?.lowercase().orEmpty()
        return when {
            mime == "audio/flac" || mime == "audio/x-flac" -> SupportedAudioFormat.FLAC
            mime == "audio/wav" || mime == "audio/x-wav" || mime == "audio/vnd.wave" -> SupportedAudioFormat.WAV
            mime == "audio/mpeg" || mime == "audio/mp3" -> SupportedAudioFormat.MP3
            mime == "audio/mp4" || mime == "audio/aac" || mime == "audio/x-m4a" || mime == "audio/m4a" -> SupportedAudioFormat.M4A
            mime == "audio/ogg" || mime == "audio/opus" || mime == "application/ogg" -> SupportedAudioFormat.OGG
            else -> null
        }
    }

    fun isSupported(fileName: String?, mimeType: String? = null): Boolean =
        classify(fileName, mimeType) != null

    fun baseNameWithoutExtension(fileName: String): String {
        val cleaned = fileName.trim()
        val lastDot = cleaned.lastIndexOf('.')
        return if (lastDot > 0) cleaned.substring(0, lastDot) else cleaned
    }
}

/**
 * Pure-Kotlin FLAC `STREAMINFO` metadata block parser so true FLAC sample rate, channel count,
 * and bit depth (16-bit vs 24-bit) are verified directly from the file header.
 */
data class FlacStreamInfo(
    val sampleRateHz: Int,
    val channelCount: Int,
    val bitsPerSample: Int,
    val totalSamples: Long,
    val durationMs: Long,
)

object FlacStreamInfoParser {
    private const val MIN_FLAC_HEADER_BYTES = 42 // 4 ("fLaC") + 4 (block header) + 34 (STREAMINFO)

    fun parse(source: SeekableByteSource): FlacStreamInfo? {
        if (source.size < MIN_FLAC_HEADER_BYTES) return null
        val header = ByteArray(MIN_FLAC_HEADER_BYTES)
        var offset = 0L

        // Optional ID3v2 prefix before "fLaC" magic
        val probe = ByteArray(10)
        if (readFully(source, 0L, probe, 10) < 10) return null
        if (probe[0] == 'I'.code.toByte() && probe[1] == 'D'.code.toByte() && probe[2] == '3'.code.toByte()) {
            val tagSize = ((probe[6].toInt() and 0x7F) shl 21) or
                ((probe[7].toInt() and 0x7F) shl 14) or
                ((probe[8].toInt() and 0x7F) shl 7) or
                (probe[9].toInt() and 0x7F)
            offset = 10L + tagSize.toLong()
        }

        if (offset + MIN_FLAC_HEADER_BYTES > source.size) return null
        if (readFully(source, offset, header, MIN_FLAC_HEADER_BYTES) < MIN_FLAC_HEADER_BYTES) return null

        // Verify "fLaC" magic
        if (header[0] != 'f'.code.toByte() ||
            header[1] != 'L'.code.toByte() ||
            header[2] != 'a'.code.toByte() ||
            header[3] != 'C'.code.toByte()
        ) {
            return null
        }

        // Metadata block 0 must be STREAMINFO (type == 0) with length >= 34 bytes
        val blockType = header[4].toInt() and 0x7F
        val blockLength = ((header[5].toInt() and 0xFF) shl 16) or
            ((header[6].toInt() and 0xFF) shl 8) or
            (header[7].toInt() and 0xFF)
        if (blockType != 0 || blockLength < 34) return null

        // STREAMINFO starts at byte 8; bytes 18..25 hold sampleRate(20b), channels-1(3b), bps-1(5b), totalSamples(36b)
        val b18 = header[18].toLong() and 0xFF
        val b19 = header[19].toLong() and 0xFF
        val b20 = header[20].toLong() and 0xFF
        val b21 = header[21].toLong() and 0xFF
        val b22 = header[22].toLong() and 0xFF
        val b23 = header[23].toLong() and 0xFF
        val b24 = header[24].toLong() and 0xFF
        val b25 = header[25].toLong() and 0xFF

        val sampleRate = ((b18 shl 12) or (b19 shl 4) or (b20 ushr 4)).toInt()
        val channels = (((b20 ushr 1) and 0x07L) + 1L).toInt()
        val bitsPerSample = ((((b20 and 0x01L) shl 4) or (b21 ushr 4)) + 1L).toInt()
        val totalSamples = ((b21 and 0x0FL) shl 32) or
            (b22 shl 24) or
            (b23 shl 16) or
            (b24 shl 8) or
            b25

        if (sampleRate <= 0 || channels <= 0 || bitsPerSample <= 0) return null
        val durationMs = if (totalSamples > 0L) (totalSamples * 1000L) / sampleRate.toLong() else 0L

        return FlacStreamInfo(
            sampleRateHz = sampleRate,
            channelCount = channels,
            bitsPerSample = bitsPerSample,
            totalSamples = totalSamples,
            durationMs = durationMs,
        )
    }

    private fun readFully(source: SeekableByteSource, startOffset: Long, buf: ByteArray, len: Int): Int {
        var total = 0
        while (total < len) {
            val read = source.readAt(startOffset + total, buf, total, len - total)
            if (read <= 0) break
            total += read
        }
        return total
    }
}
