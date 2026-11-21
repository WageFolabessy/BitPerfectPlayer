package com.cangkir.bitperfectplayer.audio

import com.cangkir.bitperfectplayer.lyrics.SeekableByteSource
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parsed RIFF/WAVE header metadata verified directly from the file bytes.
 */
data class WavHeaderInfo(
    val sampleRateHz: Int,
    val channelCount: Int,
    val bitsPerSample: Int,
    val blockAlign: Int,
    val isFloatPcm: Boolean,
    val dataOffsetBytes: Long,
    val dataSizeBytes: Long,
) {
    val totalFrames: Long
        get() = if (blockAlign > 0) dataSizeBytes / blockAlign.toLong() else 0L

    val durationMs: Long
        get() = if (sampleRateHz > 0) (totalFrames * 1000L) / sampleRateHz.toLong() else 0L
}

/**
 * Pure-Kotlin RIFF/WAVE header parser and lossless 24-bit PCM unpacker (zero `android.*` imports).
 *
 * Ensures 24-bit WAV files are never silently truncated to 16-bit:
 * - On API 31+: outputs unmodified 24-bit packed integer PCM.
 * - On API 26–30: unpacks 24-bit integer PCM losslessly to 32-bit IEEE float (full 24-bit mantissa precision).
 */
object WavHeaderParser {
    private const val WAVE_FORMAT_PCM = 0x0001
    private const val WAVE_FORMAT_IEEE_FLOAT = 0x0003
    private const val WAVE_FORMAT_EXTENSIBLE = 0xFFFE
    private const val MAX_CHUNK_ITERATIONS = 1024

    // KSDATAFORMAT_SUBTYPE_PCM & KSDATAFORMAT_SUBTYPE_IEEE_FLOAT trailing 14 bytes
    private val SUBFORMAT_GUID_TAIL = byteArrayOf(
        0x00, 0x00, 0x10, 0x00,
        0x80.toByte(), 0x00, 0x00, 0xAA.toByte(),
        0x00, 0x38, 0x9B.toByte(), 0x71,
    )

    private data class ParsedFmtChunk(
        val audioFormat: Int,
        val channels: Int,
        val sampleRate: Int,
        val blockAlign: Int,
        val bitsPerSample: Int,
        val isFloatPcm: Boolean,
    )

    @Throws(AudioDecodeException::class)
    fun parse(source: SeekableByteSource): WavHeaderInfo {
        val totalSize = source.size
        if (totalSize < 44L) {
            throw AudioDecodeException.CorruptedFile("WAV file too small ($totalSize bytes)")
        }

        val riffHeader = ByteArray(12)
        readExact(source, 0L, riffHeader, 12, "RIFF header")

        if (!matchesAscii(riffHeader, 0, "RIFF") || !matchesAscii(riffHeader, 8, "WAVE")) {
            throw AudioDecodeException.UnsupportedFormat("Not a valid RIFF/WAVE container")
        }

        var fmtChunk: ParsedFmtChunk? = null
        var dataOffset = -1L
        var dataSize = -1L

        val chunkHeader = ByteArray(8)
        var cursor = 12L
        var iterations = 0

        while (cursor + 8L <= totalSize && iterations < MAX_CHUNK_ITERATIONS) {
            iterations++
            if (readUpTo(source, cursor, chunkHeader, 8) < 8) break

            val chunkId = String(chunkHeader, 0, 4, Charsets.US_ASCII)
            val chunkSize = readUint32Le(chunkHeader, 4)
            val chunkBodyOffset = cursor + 8L

            if (chunkBodyOffset > totalSize) {
                throw AudioDecodeException.CorruptedFile("Chunk '$chunkId' starts beyond EOF")
            }

            when (chunkId) {
                "fmt " -> fmtChunk = parseFmtChunk(source, chunkBodyOffset, chunkSize, totalSize)
                "data" -> {
                    dataOffset = chunkBodyOffset
                    val available = (totalSize - chunkBodyOffset).coerceAtLeast(0L)
                    dataSize = if (chunkSize == 0L || chunkSize > available) available else chunkSize
                    if (fmtChunk != null) break
                }
            }

            // RIFF chunks are word-aligned (padded by 1 byte when chunkSize is odd).
            val paddedSize = chunkSize + (chunkSize and 1L)
            val nextCursor = chunkBodyOffset + paddedSize
            if (nextCursor <= cursor) {
                throw AudioDecodeException.CorruptedFile("Malformed RIFF chunk size caused non-advancing cursor")
            }
            cursor = nextCursor
        }

        return buildValidatedHeader(fmtChunk, dataOffset, dataSize)
    }

    @Throws(AudioDecodeException::class)
    private fun parseFmtChunk(source: SeekableByteSource, chunkBodyOffset: Long, chunkSize: Long, totalSize: Long): ParsedFmtChunk {
        if (chunkSize < 16L || chunkBodyOffset + 16L > totalSize) {
            throw AudioDecodeException.CorruptedFile("Truncated WAV 'fmt ' chunk ($chunkSize bytes)")
        }
        val fmtReadSize = minOf(chunkSize, 40L).toInt()
        val fmtBytes = ByteArray(fmtReadSize)
        readExact(source, chunkBodyOffset, fmtBytes, fmtReadSize, "fmt chunk")

        val rawFormat = readUint16Le(fmtBytes, 0)
        val channels = readUint16Le(fmtBytes, 2)
        val sampleRate = readUint32Le(fmtBytes, 4).toInt()
        val blockAlign = readUint16Le(fmtBytes, 12)
        val bitsPerSample = readUint16Le(fmtBytes, 14)

        val (audioFormat, isFloatPcm) = when (rawFormat) {
            WAVE_FORMAT_PCM -> WAVE_FORMAT_PCM to false
            WAVE_FORMAT_IEEE_FLOAT -> WAVE_FORMAT_IEEE_FLOAT to true
            WAVE_FORMAT_EXTENSIBLE -> parseExtensibleSubFormat(fmtBytes)
            else -> throw AudioDecodeException.UnsupportedFormat(
                "Unsupported WAV compression format tag: 0x${rawFormat.toString(16)}",
            )
        }

        return ParsedFmtChunk(
            audioFormat = audioFormat,
            channels = channels,
            sampleRate = sampleRate,
            blockAlign = blockAlign,
            bitsPerSample = bitsPerSample,
            isFloatPcm = isFloatPcm,
        )
    }

    @Throws(AudioDecodeException::class)
    private fun parseExtensibleSubFormat(fmtBytes: ByteArray): Pair<Int, Boolean> {
        if (fmtBytes.size < 40) {
            throw AudioDecodeException.CorruptedFile("Truncated WAVE_FORMAT_EXTENSIBLE chunk")
        }
        val subFormatCode = readUint16Le(fmtBytes, 24)
        val subFormatHigh = readUint16Le(fmtBytes, 26)
        val guidMatches = subFormatHigh == 0 && matchesBytes(fmtBytes, 28, SUBFORMAT_GUID_TAIL)
        val isSupportedSubFormat = subFormatCode == WAVE_FORMAT_PCM || subFormatCode == WAVE_FORMAT_IEEE_FLOAT
        if (!guidMatches || !isSupportedSubFormat) {
            throw AudioDecodeException.UnsupportedFormat(
                "Unsupported WAVE_FORMAT_EXTENSIBLE subformat: 0x${subFormatCode.toString(16)}",
            )
        }
        return subFormatCode to (subFormatCode == WAVE_FORMAT_IEEE_FLOAT)
    }

    @Throws(AudioDecodeException::class)
    private fun buildValidatedHeader(fmt: ParsedFmtChunk?, dataOffset: Long, dataSize: Long): WavHeaderInfo {
        if (fmt == null) {
            throw AudioDecodeException.CorruptedFile("Missing 'fmt ' chunk in WAV file")
        }
        if (dataOffset < 0L || dataSize <= 0L) {
            throw AudioDecodeException.CorruptedFile("Missing or empty 'data' chunk in WAV file")
        }
        validateFmtParams(fmt)

        val expectedBlockAlign = fmt.channels * (fmt.bitsPerSample / 8)
        val effectiveBlockAlign = if (fmt.blockAlign == expectedBlockAlign) fmt.blockAlign else expectedBlockAlign
        val alignedDataSize = (dataSize / effectiveBlockAlign.toLong()) * effectiveBlockAlign.toLong()
        if (alignedDataSize <= 0L) {
            throw AudioDecodeException.CorruptedFile("WAV 'data' chunk contains zero complete audio frames")
        }

        return WavHeaderInfo(
            sampleRateHz = fmt.sampleRate,
            channelCount = fmt.channels,
            bitsPerSample = fmt.bitsPerSample,
            blockAlign = effectiveBlockAlign,
            isFloatPcm = fmt.isFloatPcm,
            dataOffsetBytes = dataOffset,
            dataSizeBytes = alignedDataSize,
        )
    }

    @Throws(AudioDecodeException::class)
    private fun validateFmtParams(fmt: ParsedFmtChunk) {
        if (fmt.channels !in 1..8) {
            throw AudioDecodeException.UnsupportedFormat("Unsupported WAV channel count: ${fmt.channels}")
        }
        if (fmt.sampleRate !in 8_000..768_000) {
            throw AudioDecodeException.UnsupportedFormat("Unsupported WAV sample rate: ${fmt.sampleRate} Hz")
        }
        val supportedBitDepth = if (fmt.isFloatPcm) {
            fmt.bitsPerSample == 32
        } else {
            fmt.bitsPerSample == 16 || fmt.bitsPerSample == 24 || fmt.bitsPerSample == 32
        }
        if (!supportedBitDepth) {
            throw AudioDecodeException.UnsupportedFormat(
                "Unsupported WAV bit depth: ${fmt.bitsPerSample}-bit (float=${fmt.isFloatPcm}, format=${fmt.audioFormat})",
            )
        }
    }

    /**
     * Losslessly unpacks packed 24-bit signed little-endian PCM bytes into normalized 32-bit IEEE floats
     * in `[-1.0f, +1.0f)`. Because float32 has a 24-bit significand and division by `2^23` (`8388608f`)
     * only adjusts the exponent, all 24 bits of integer sample precision are preserved without truncation.
     */
    fun unpackPcm24LeToFloat32(sourceBytes: ByteArray, byteCount: Int = sourceBytes.size): FloatArray {
        val sampleCount = byteCount / 3
        val out = FloatArray(sampleCount)
        var inIdx = 0
        for (i in 0 until sampleCount) {
            val b0 = sourceBytes[inIdx].toInt() and 0xFF
            val b1 = sourceBytes[inIdx + 1].toInt() and 0xFF
            val b2 = sourceBytes[inIdx + 2].toInt() // Preserve sign bit (bit 23 -> bits 24..31)
            val sample24 = b0 or (b1 shl 8) or (b2 shl 16)
            out[i] = sample24.toFloat() / 8_388_608.0f
            inIdx += 3
        }
        return out
    }

    /**
     * Unpacks packed 24-bit signed little-endian PCM bytes directly into little-endian IEEE float32 bytes
     * suitable for [android.media.AudioFormat.ENCODING_PCM_FLOAT] streaming buffers.
     */
    fun unpackPcm24LeToFloat32Bytes(sourceBytes: ByteArray, sourceByteCount: Int, destBytes: ByteArray): Int {
        val sampleCount = minOf(sourceByteCount / 3, destBytes.size / 4)
        var inIdx = 0
        var outIdx = 0
        for (i in 0 until sampleCount) {
            val b0 = sourceBytes[inIdx].toInt() and 0xFF
            val b1 = sourceBytes[inIdx + 1].toInt() and 0xFF
            val b2 = sourceBytes[inIdx + 2].toInt()
            val sample24 = b0 or (b1 shl 8) or (b2 shl 16)
            val floatBits = java.lang.Float.floatToRawIntBits(sample24.toFloat() / 8_388_608.0f)
            destBytes[outIdx] = (floatBits and 0xFF).toByte()
            destBytes[outIdx + 1] = ((floatBits ushr 8) and 0xFF).toByte()
            destBytes[outIdx + 2] = ((floatBits ushr 16) and 0xFF).toByte()
            destBytes[outIdx + 3] = ((floatBits ushr 24) and 0xFF).toByte()
            inIdx += 3
            outIdx += 4
        }
        return sampleCount * 4
    }

    /**
     * Converts 32-bit signed little-endian integer PCM bytes to little-endian IEEE float32 bytes
     * (fallback for API 26–30 when `ENCODING_PCM_32BIT` is unavailable).
     */
    fun unpackPcm32LeToFloat32Bytes(sourceBytes: ByteArray, sourceByteCount: Int, destBytes: ByteArray): Int {
        val sampleCount = minOf(sourceByteCount / 4, destBytes.size / 4)
        var idx = 0
        for (i in 0 until sampleCount) {
            val sample32 = (sourceBytes[idx].toInt() and 0xFF) or
                ((sourceBytes[idx + 1].toInt() and 0xFF) shl 8) or
                ((sourceBytes[idx + 2].toInt() and 0xFF) shl 16) or
                (sourceBytes[idx + 3].toInt() shl 24)
            val floatBits = java.lang.Float.floatToRawIntBits((sample32.toDouble() / 2_147_483_648.0).toFloat())
            destBytes[idx] = (floatBits and 0xFF).toByte()
            destBytes[idx + 1] = ((floatBits ushr 8) and 0xFF).toByte()
            destBytes[idx + 2] = ((floatBits ushr 16) and 0xFF).toByte()
            destBytes[idx + 3] = ((floatBits ushr 24) and 0xFF).toByte()
            idx += 4
        }
        return sampleCount * 4
    }

    private fun readExact(source: SeekableByteSource, offset: Long, target: ByteArray, length: Int, label: String) {
        val read = readUpTo(source, offset, target, length)
        if (read < length) {
            throw AudioDecodeException.CorruptedFile("Unexpected EOF while reading WAV $label")
        }
    }

    private fun readUpTo(source: SeekableByteSource, offset: Long, target: ByteArray, length: Int): Int {
        var filled = 0
        try {
            while (filled < length) {
                val r = source.readAt(offset + filled, target, filled, length - filled)
                if (r <= 0) break
                filled += r
            }
        } catch (e: IOException) {
            throw AudioDecodeException.IoError("I/O error reading WAV stream: ${e.message}", e)
        }
        return filled
    }

    private fun readUint16Le(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun readUint32Le(bytes: ByteArray, offset: Int): Long =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL

    private fun matchesAscii(bytes: ByteArray, offset: Int, ascii: String): Boolean {
        if (offset + ascii.length > bytes.size) return false
        for (i in ascii.indices) {
            if (bytes[offset + i].toInt().toChar() != ascii[i]) return false
        }
        return true
    }

    private fun matchesBytes(bytes: ByteArray, offset: Int, expected: ByteArray): Boolean {
        if (offset + expected.size > bytes.size) return false
        for (i in expected.indices) {
            if (bytes[offset + i] != expected[i]) return false
        }
        return true
    }
}

/**
 * Direct pure-Kotlin WAV PCM stream decoder with frame-accurate seeking and zero 24-bit truncation.
 *
 * @param supportsNative24And32BitPcm `true` on Android 12+ (`Build.VERSION.SDK_INT >= Build.VERSION_CODES.S`),
 * `false` on API 26–30 where 24-bit/32-bit integer WAVs unpack losslessly to [PcmEncodingType.PCM_FLOAT].
 */
class WavPcmDecoder(private val source: SeekableByteSource, supportsNative24And32BitPcm: Boolean) : PcmAudioDecoder {

    val header: WavHeaderInfo = WavHeaderParser.parse(source)

    private val unpack24ToFloat: Boolean = !header.isFloatPcm && header.bitsPerSample == 24 && !supportsNative24And32BitPcm
    private val unpack32ToFloat: Boolean = !header.isFloatPcm && header.bitsPerSample == 32 && !supportsNative24And32BitPcm

    private val outputEncoding: PcmEncodingType = when {
        header.isFloatPcm -> PcmEncodingType.PCM_FLOAT
        header.bitsPerSample == 16 -> PcmEncodingType.PCM_16BIT
        header.bitsPerSample == 24 && supportsNative24And32BitPcm -> PcmEncodingType.PCM_24BIT_PACKED
        header.bitsPerSample == 24 -> PcmEncodingType.PCM_FLOAT
        header.bitsPerSample == 32 && supportsNative24And32BitPcm -> PcmEncodingType.PCM_32BIT
        else -> PcmEncodingType.PCM_FLOAT
    }

    private val effectiveBitDepth: Int = when {
        header.isFloatPcm -> 32
        header.bitsPerSample == 24 -> 24 // Full 24-bit preserved in both PCM_24BIT_PACKED and PCM_FLOAT!
        header.bitsPerSample == 32 && supportsNative24And32BitPcm -> 32
        header.bitsPerSample == 32 -> 24 // Float32 mantissa
        else -> 16
    }

    override val format: DecodedPcmFormat = DecodedPcmFormat(
        sampleRateHz = header.sampleRateHz,
        channelCount = header.channelCount,
        encoding = outputEncoding,
        effectiveBitDepth = effectiveBitDepth,
        decoderName = if (unpack24ToFloat) {
            "WavPcmDecoder (24-bit -> Float32 Lossless)"
        } else {
            "WavPcmDecoder (Direct PCM)"
        },
        durationMs = header.durationMs,
        sourceContainer = "WAV",
        sourceCodec = if (header.isFloatPcm) "IEEE Float PCM" else "Linear PCM",
        sourceSampleRateHz = header.sampleRateHz,
        sourceBitDepth = header.bitsPerSample,
        sourceHeaderVerified = true,
    )

    private var dataBytePosition: Long = 0L
    private var scratchRawBuffer: ByteArray = ByteArray(0)

    override val currentPositionMs: Long
        get() {
            val framesPlayed = dataBytePosition / header.blockAlign.toLong()
            return (framesPlayed * 1000L) / header.sampleRateHz.toLong()
        }

    override fun readPcm(destination: ByteArray): Int {
        if (dataBytePosition >= header.dataSizeBytes) return -1
        val remainingInputBytes = header.dataSizeBytes - dataBytePosition

        return try {
            if (unpack24ToFloat) {
                readUnpacked24ToFloat(destination, remainingInputBytes)
            } else {
                readNativeOr32ToFloat(destination, remainingInputBytes)
            }
        } catch (e: IOException) {
            throw AudioDecodeException.IoError("Failed reading WAV PCM frames: ${e.message}", e)
        }
    }

    private fun readUnpacked24ToFloat(destination: ByteArray, remainingInputBytes: Long): Int {
        val outFrameBytes = header.channelCount * 4
        val maxFrames = destination.size / outFrameBytes
        if (maxFrames <= 0) return 0
        val rawBytesNeeded = minOf(maxFrames.toLong() * header.blockAlign.toLong(), remainingInputBytes).toInt()
        val alignedRawBytes = (rawBytesNeeded / header.blockAlign) * header.blockAlign
        if (alignedRawBytes <= 0) return -1
        if (scratchRawBuffer.size < alignedRawBytes) {
            scratchRawBuffer = ByteArray(alignedRawBytes)
        }
        val read = readAligned(scratchRawBuffer, alignedRawBytes)
        if (read <= 0) return -1
        dataBytePosition += read.toLong()
        return WavHeaderParser.unpackPcm24LeToFloat32Bytes(scratchRawBuffer, read, destination)
    }

    private fun readNativeOr32ToFloat(destination: ByteArray, remainingInputBytes: Long): Int {
        val alignedBytes = minOf(
            ((destination.size / header.blockAlign) * header.blockAlign).toLong(),
            remainingInputBytes,
        ).toInt()
        if (alignedBytes <= 0) return -1
        val read = readAligned(destination, alignedBytes)
        if (read <= 0) return -1
        dataBytePosition += read.toLong()
        return if (unpack32ToFloat) {
            WavHeaderParser.unpackPcm32LeToFloat32Bytes(destination, read, destination)
        } else {
            read
        }
    }

    override fun seekToMs(positionMs: Long): Long {
        val clampedMs = positionMs.coerceIn(0L, header.durationMs)
        val targetFrame = ((clampedMs * header.sampleRateHz.toLong()) / 1000L)
            .coerceIn(0L, header.totalFrames)
        dataBytePosition = (targetFrame * header.blockAlign.toLong())
            .coerceIn(0L, header.dataSizeBytes)
        return currentPositionMs
    }

    override fun close() {
        source.close()
    }

    private fun readAligned(target: ByteArray, targetBytes: Int): Int {
        var filled = 0
        while (filled < targetBytes) {
            val r = source.readAt(
                header.dataOffsetBytes + dataBytePosition + filled,
                target,
                filled,
                targetBytes - filled,
            )
            if (r <= 0) break
            filled += r
        }
        return (filled / header.blockAlign) * header.blockAlign
    }
}
