package com.cangkir.bitperfectplayer.audio

import com.cangkir.bitperfectplayer.library.ReleaseYearParser
import com.cangkir.bitperfectplayer.lyrics.SeekableByteSource
import java.io.Closeable
import java.io.IOException

/**
 * Pure-Kotlin PCM encoding representation mapped to Android [android.media.AudioFormat] constants
 * with API-level guards at the Android engine boundary.
 */
enum class PcmEncodingType(val displayLabel: String, val bytesPerSample: Int, val effectiveBitDepth: Int) {
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
sealed class AudioDecodeException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    class UnsupportedFormat(message: String, cause: Throwable? = null) : AudioDecodeException(message, cause)

    class CorruptedFile(message: String, cause: Throwable? = null) : AudioDecodeException(message, cause)

    class IoError(message: String, cause: Throwable? = null) : AudioDecodeException(message, cause)

    class DecoderInitFailed(message: String, cause: Throwable? = null) : AudioDecodeException(message, cause)

    class TransientCodecState(message: String, cause: Throwable? = null) : AudioDecodeException(message, cause)

    class DeadAudioTrack(message: String, cause: Throwable? = null) : AudioDecodeException(message, cause)
}

/**
 * Streaming PCM audio decoder abstraction shared by [WavPcmDecoder] and [MediaCodecAudioDecoder].
 */
interface PcmAudioDecoder : Closeable {
    val format: DecodedPcmFormat
    val formatVersion: Int
        get() = 0
    val currentPositionMs: Long

    /**
     * Reads decoded PCM bytes into [destination]. Returns bytes written (always aligned to whole
     * PCM frames), `0` if temporarily waiting for codec buffers, or `-1` on end-of-stream.
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
enum class SupportedAudioFormat(val containerLabel: String, val defaultCodecLabel: String, val isLossless: Boolean) {
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

    fun isSupported(fileName: String?, mimeType: String? = null): Boolean = classify(fileName, mimeType) != null

    fun baseNameWithoutExtension(fileName: String): String {
        val cleaned = fileName.trim()
        val lastDot = cleaned.lastIndexOf('.')
        return if (lastDot > 0) cleaned.substring(0, lastDot) else cleaned
    }
}

/**
 * Pure-Kotlin FLAC `STREAMINFO` + optional `VORBIS_COMMENT` (`TRACKNUMBER`/`DISCNUMBER`) metadata
 * block parser so true FLAC sample rate, channel count, bit depth (16-bit vs 24-bit), and disc/track
 * position are verified directly from the file header in a single fast pass.
 */
data class FlacStreamInfo(
    val sampleRateHz: Int,
    val channelCount: Int,
    val bitsPerSample: Int,
    val totalSamples: Long,
    val durationMs: Long,
    val discNumber: Int = 0,
    val trackNumber: Int = 0,
    val year: Int = 0,
)

object FlacStreamInfoParser {
    private const val MIN_FLAC_HEADER_BYTES = 42 // 4 ("fLaC") + 4 (block header) + 34 (STREAMINFO)
    private const val MAX_VORBIS_COMMENT_BYTES = 256 * 1024 // 256 KB OOM safety cap
    private const val MAX_METADATA_BLOCKS_TO_SCAN = 32

    private data class ParsedVorbisMeta(val discNumber: Int = 0, val trackNumber: Int = 0, val year: Int = 0)

    private val FLAC_MAGIC = byteArrayOf(
        'f'.code.toByte(),
        'L'.code.toByte(),
        'a'.code.toByte(),
        'C'.code.toByte(),
    )

    fun parse(source: SeekableByteSource): FlacStreamInfo? {
        val offset = resolveFlacHeaderOffset(source) ?: return null
        val header = ByteArray(MIN_FLAC_HEADER_BYTES)
        val hasValidHeader = readFully(source, offset, header, MIN_FLAC_HEADER_BYTES) == MIN_FLAC_HEADER_BYTES &&
            hasFlacMagic(header)
        if (!hasValidHeader) return null

        // Metadata block 0 must be STREAMINFO (type == 0) with length >= 34 bytes
        val streamInfoIsLast = (header[4].toInt() and 0x80) != 0
        val blockType = header[4].toInt() and 0x7F
        val blockLength = ((header[5].toInt() and 0xFF) shl 16) or
            ((header[6].toInt() and 0xFF) shl 8) or
            (header[7].toInt() and 0xFF)
        if (blockType != 0 || blockLength < 34) return null

        val baseInfo = decodeStreamInfoHeader(header) ?: return null
        val parsedMeta = if (!streamInfoIsLast) {
            readVorbisMetadataSafely(source, offset + 8L + blockLength.toLong())
        } else {
            ParsedVorbisMeta()
        }

        val resolvedYear = if (parsedMeta.year > 0) {
            parsedMeta.year
        } else if (offset > 0L) {
            ReleaseYearParser.extractId3v2Year(source)
        } else {
            0
        }

        return baseInfo.copy(
            discNumber = parsedMeta.discNumber,
            trackNumber = parsedMeta.trackNumber,
            year = resolvedYear,
        )
    }

    private fun resolveFlacHeaderOffset(source: SeekableByteSource): Long? {
        if (source.size < MIN_FLAC_HEADER_BYTES) return null
        val probe = ByteArray(10)
        if (readFully(source, 0L, probe, 10) < 10) return null

        var offset = 0L
        // Optional ID3v2 prefix before "fLaC" magic
        if (probe[0] == 'I'.code.toByte() && probe[1] == 'D'.code.toByte() && probe[2] == '3'.code.toByte()) {
            val tagSize = ((probe[6].toInt() and 0x7F) shl 21) or
                ((probe[7].toInt() and 0x7F) shl 14) or
                ((probe[8].toInt() and 0x7F) shl 7) or
                (probe[9].toInt() and 0x7F)
            offset = 10L + tagSize.toLong()
        }
        return offset.takeIf { it + MIN_FLAC_HEADER_BYTES <= source.size }
    }

    private fun hasFlacMagic(header: ByteArray): Boolean = FLAC_MAGIC.indices.all { idx -> header[idx] == FLAC_MAGIC[idx] }

    private fun decodeStreamInfoHeader(header: ByteArray): FlacStreamInfo? {
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

    private fun readVorbisMetadataSafely(source: SeekableByteSource, startOffset: Long): ParsedVorbisMeta {
        var cursor = startOffset
        val blockHeader = ByteArray(4)
        var blocksVisited = 0
        var reachedLast = false
        while (!reachedLast && cursor + 4L <= source.size && blocksVisited < MAX_METADATA_BLOCKS_TO_SCAN) {
            blocksVisited++
            if (readFully(source, cursor, blockHeader, 4) < 4) return ParsedVorbisMeta()
            reachedLast = (blockHeader[0].toInt() and 0x80) != 0
            val blockType = blockHeader[0].toInt() and 0x7F
            val length = ((blockHeader[1].toInt() and 0xFF) shl 16) or
                ((blockHeader[2].toInt() and 0xFF) shl 8) or
                (blockHeader[3].toInt() and 0xFF)
            cursor += 4L
            if (length < 0 || cursor + length.toLong() > source.size) return ParsedVorbisMeta()

            if (blockType == 4 && length in 8..MAX_VORBIS_COMMENT_BYTES) {
                val payload = ByteArray(length)
                return if (readFully(source, cursor, payload, length) == length) {
                    parseVorbisCommentMetadata(payload)
                } else {
                    ParsedVorbisMeta()
                }
            }

            cursor += length.toLong()
        }
        return ParsedVorbisMeta()
    }

    private class MutableVorbisMeta(var disc: Int = 0, var track: Int = 0, var year: Int = 0) {
        fun isComplete(): Boolean = disc > 0 && track > 0 && year > 0
        fun toImmutable(): ParsedVorbisMeta = ParsedVorbisMeta(discNumber = disc, trackNumber = track, year = year)
    }

    private fun parseVorbisCommentMetadata(data: ByteArray): ParsedVorbisMeta {
        var pos = skipVorbisVendorHeader(data)
        if (pos < 0 || pos + 4 > data.size) return ParsedVorbisMeta()
        val count = readInt32Le(data, pos)
        pos += 4
        if (count <= 0 || count > 4096) return ParsedVorbisMeta()

        val meta = MutableVorbisMeta()
        for (i in 0 until count) {
            if (pos + 4 > data.size) break
            val entryLen = readInt32Le(data, pos)
            pos += 4
            if (entryLen <= 0 || pos + entryLen > data.size) break
            applyVorbisCommentEntry(data, pos, pos + entryLen, meta)
            pos += entryLen
            if (meta.isComplete()) break
        }
        return meta.toImmutable()
    }

    private fun skipVorbisVendorHeader(data: ByteArray): Int {
        if (data.size < 4) return -1
        val vendorLen = readInt32Le(data, 0)
        val endOffset = 4 + vendorLen
        return if (vendorLen < 0 || endOffset > data.size) -1 else endOffset
    }

    private fun applyVorbisCommentEntry(data: ByteArray, start: Int, end: Int, meta: MutableVorbisMeta) {
        val eqIdx = findAsciiEquals(data, start, end)
        val keyLen = eqIdx - start
        if (eqIdx <= start || keyLen !in 4..12) return
        val key = String(data, start, keyLen, Charsets.UTF_8).trim().uppercase()
        val value = String(data, eqIdx + 1, end - (eqIdx + 1), Charsets.UTF_8)
        when (key) {
            "TRACKNUMBER", "TRACK" -> if (meta.track == 0) {
                val parsed = TrackNumberParser.parseTagNumber(value)
                if (parsed > 0) meta.track = parsed
            }
            "DISCNUMBER", "DISC" -> if (meta.disc == 0) {
                val parsed = TrackNumberParser.parseTagNumber(value)
                if (parsed > 0) meta.disc = parsed
            }
            "DATE", "YEAR", "ORIGINALDATE" -> if (meta.year == 0) {
                val parsed = ReleaseYearParser.parse(value)
                if (parsed > 0) meta.year = parsed
            }
        }
    }

    private fun findAsciiEquals(data: ByteArray, start: Int, end: Int): Int {
        for (idx in start until end) {
            if (data[idx] == '='.code.toByte()) return idx
        }
        return -1
    }

    private fun readInt32Le(data: ByteArray, offset: Int): Int = (data[offset].toInt() and 0xFF) or
        ((data[offset + 1].toInt() and 0xFF) shl 8) or
        ((data[offset + 2].toInt() and 0xFF) shl 16) or
        ((data[offset + 3].toInt() and 0xFF) shl 24)

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
