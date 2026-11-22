package com.cangkir.bitperfectplayer.audio

import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Direct [MediaExtractor] PCM reader for `MediaFormat.MIMETYPE_AUDIO_RAW` (`audio/raw`) tracks.
 *
 * On Android 16 (API 36) and several Custom ROMs, `MediaExtractor`'s `FlacExtractor` decodes
 * lossless FLAC frames internally and exposes raw PCM (`audio/raw`) rather than compressed
 * `audio/flac`. Routing `audio/raw` through `c2.android.raw.decoder` with an overridden
 * `KEY_PCM_ENCODING = ENCODING_PCM_FLOAT` causes `c2.android.raw.decoder` to pass raw integer PCM
 * bytes through unchanged while tagging the output as float32 — producing full-scale white noise.
 *
 * This decoder bypasses `MediaCodec` completely for `audio/raw`:
 * - Resolves the true source PCM encoding from `trackFormat` (`KEY_PCM_ENCODING`, falling back to
 *   `"bits-per-sample"` / `"bit-depth"` / verified FLAC header, then `PCM_16BIT`).
 * - Passes through `PCM_16BIT`, `PCM_FLOAT`, and (on API 31+ when confirmed by
 *   `AudioTrack.getMinBufferSize`) `PCM_24BIT_PACKED` / `PCM_32BIT` bit-identically with zero
 *   intermediate codec overhead.
 * - Losslessly converts `PCM_24BIT_PACKED` / `PCM_32BIT` -> `PCM_FLOAT` (or `PCM_8BIT` ->
 *   `PCM_16BIT`) via zero-allocation pure-Kotlin [PcmStreamRules] converters when native integer
 *   playback is unavailable (API 26–30 or unsupported HAL encoding).
 */
internal class RawExtractorPcmDecoder private constructor(
    private val extractor: MediaExtractor,
    private val sourceEncoding: RawSourcePcmEncoding,
    override val format: DecodedPcmFormat,
) : PcmAudioDecoder {

    override val formatVersion: Int = 0

    @Volatile
    private var _currentPositionMs: Long = 0L
    override val currentPositionMs: Long
        get() = _currentPositionMs

    private val extractorLock = ReentrantLock()
    private val closed = AtomicBoolean(false)
    private var reachedEos = false

    private val rawBytesPerFrame: Int = (format.channelCount * sourceEncoding.bytesPerSample).coerceAtLeast(1)
    private val outBytesPerFrame: Int = format.bytesPerFrame.coerceAtLeast(1)
    private val needsConversion: Boolean = PcmStreamRules.requiresRawPcmConversion(sourceEncoding, format.encoding)

    private var rawReadBuf: ByteArray = ByteArray(INITIAL_READ_BUFFER_BYTES)
    private var rawReadBuffer: ByteBuffer = ByteBuffer.wrap(rawReadBuf)
    private val subFrameCarry: ByteArray = ByteArray(rawBytesPerFrame)
    private var subFrameCarryLen: Int = 0

    private var pendingOutBuf: ByteArray = ByteArray(0)
    private var pendingOutOffset: Int = 0
    private var pendingOutLength: Int = 0

    override fun readPcm(destination: ByteArray): Int {
        if (closed.get()) return -1
        return extractorLock.withLock {
            if (closed.get()) return@withLock -1

            val maxAlignedDest = PcmStreamRules.alignByteCountToFrame(destination.size, outBytesPerFrame)
            if (maxAlignedDest <= 0) return@withLock 0

            val drained = drainPendingOutput(destination, maxAlignedDest)
            if (drained > 0) return@withLock drained
            if (reachedEos) return@withLock -1

            var attempts = 0
            while (attempts < MAX_EMPTY_PACKET_ATTEMPTS && !closed.get()) {
                attempts++
                val sampleBytes = readNextExtractorPacketIntoRawBuffer()
                if (sampleBytes < 0) {
                    reachedEos = true
                    subFrameCarryLen = 0
                    return@withLock -1
                }
                val sampleTimeUs = extractor.sampleTime
                if (sampleTimeUs >= 0L) {
                    _currentPositionMs = sampleTimeUs / 1000L
                }
                extractor.advance()

                val totalRawBytes = subFrameCarryLen + sampleBytes
                val alignedRawBytes = PcmStreamRules.alignByteCountToFrame(totalRawBytes, rawBytesPerFrame)
                val trailingRawBytes = totalRawBytes - alignedRawBytes
                if (trailingRawBytes > 0) {
                    System.arraycopy(rawReadBuf, alignedRawBytes, subFrameCarry, 0, trailingRawBytes)
                }
                subFrameCarryLen = trailingRawBytes

                if (alignedRawBytes > 0) {
                    return@withLock emitAlignedRawBytes(alignedRawBytes, destination, maxAlignedDest)
                }
            }
            0
        }
    }

    override fun seekToMs(positionMs: Long): Long {
        if (closed.get()) return _currentPositionMs
        val clampedMs = positionMs.coerceIn(0L, format.durationMs.coerceAtLeast(0L))
        return extractorLock.withLock {
            if (closed.get()) return@withLock _currentPositionMs
            try {
                extractor.seekTo(clampedMs * 1000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                pendingOutOffset = 0
                pendingOutLength = 0
                subFrameCarryLen = 0
                reachedEos = false
                val sampleTimeUs = extractor.sampleTime
                _currentPositionMs = if (sampleTimeUs >= 0L) sampleTimeUs / 1000L else clampedMs
                _currentPositionMs
            } catch (e: IllegalStateException) {
                if (closed.get()) {
                    _currentPositionMs
                } else {
                    throw AudioDecodeException.TransientCodecState("Failed seeking raw audio stream: ${e.message}", e)
                }
            } catch (e: IllegalArgumentException) {
                throw AudioDecodeException.IoError("Failed seeking raw audio stream: ${e.message}", e)
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        extractorLock.withLock {
            runCatching { extractor.release() }
            pendingOutOffset = 0
            pendingOutLength = 0
            subFrameCarryLen = 0
        }
    }

    private fun drainPendingOutput(destination: ByteArray, maxAlignedDest: Int): Int {
        val available = pendingOutLength - pendingOutOffset
        if (available <= 0) return 0
        val toCopy = PcmStreamRules.alignByteCountToFrame(minOf(available, maxAlignedDest), outBytesPerFrame)
        if (toCopy <= 0) return 0
        System.arraycopy(pendingOutBuf, pendingOutOffset, destination, 0, toCopy)
        pendingOutOffset += toCopy
        if (pendingOutOffset >= pendingOutLength) {
            pendingOutOffset = 0
            pendingOutLength = 0
        }
        return toCopy
    }

    private fun readNextExtractorPacketIntoRawBuffer(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val expectedSampleSize = extractor.sampleSize
            if (expectedSampleSize > 0L) {
                val needed = (subFrameCarryLen.toLong() + expectedSampleSize)
                    .coerceAtMost(MAX_READ_BUFFER_BYTES.toLong())
                    .toInt()
                ensureRawReadCapacity(needed)
            }
        }
        while (true) {
            if (subFrameCarryLen > 0) {
                System.arraycopy(subFrameCarry, 0, rawReadBuf, 0, subFrameCarryLen)
            }
            rawReadBuffer.clear()
            try {
                return extractor.readSampleData(rawReadBuffer, subFrameCarryLen)
            } catch (e: IllegalArgumentException) {
                if (rawReadBuf.size >= MAX_READ_BUFFER_BYTES) {
                    throw AudioDecodeException.CorruptedFile("Raw PCM packet exceeds buffer limit: ${e.message}", e)
                }
                ensureRawReadCapacity(minOf(rawReadBuf.size * 2, MAX_READ_BUFFER_BYTES))
            } catch (e: IllegalStateException) {
                throw AudioDecodeException.IoError("Failed reading raw PCM sample: ${e.message}", e)
            }
        }
    }

    private fun ensureRawReadCapacity(neededBytes: Int) {
        if (rawReadBuf.size >= neededBytes) return
        val newSize = maxOf(neededBytes, rawReadBuf.size * 2).coerceAtMost(MAX_READ_BUFFER_BYTES)
        rawReadBuf = ByteArray(newSize)
        rawReadBuffer = ByteBuffer.wrap(rawReadBuf)
    }

    private fun emitAlignedRawBytes(alignedRawBytes: Int, destination: ByteArray, maxAlignedDest: Int): Int {
        if (!needsConversion) {
            val toCopy = PcmStreamRules.alignByteCountToFrame(minOf(alignedRawBytes, maxAlignedDest), outBytesPerFrame)
            if (toCopy > 0) {
                System.arraycopy(rawReadBuf, 0, destination, 0, toCopy)
            }
            val leftover = alignedRawBytes - toCopy
            if (leftover > 0) {
                ensurePendingOutputCapacity(leftover)
                System.arraycopy(rawReadBuf, toCopy, pendingOutBuf, 0, leftover)
                pendingOutOffset = 0
                pendingOutLength = leftover
            }
            return toCopy
        }

        val sampleCount = alignedRawBytes / sourceEncoding.bytesPerSample
        val outByteCount = sampleCount * format.encoding.bytesPerSample
        if (outByteCount <= maxAlignedDest) {
            return convertRawInto(rawReadBuf, alignedRawBytes, destination, destOffset = 0)
        }
        ensurePendingOutputCapacity(outByteCount)
        val written = convertRawInto(rawReadBuf, alignedRawBytes, pendingOutBuf, destOffset = 0)
        pendingOutOffset = 0
        pendingOutLength = written
        return drainPendingOutput(destination, maxAlignedDest)
    }

    private fun convertRawInto(srcBytes: ByteArray, alignedRawBytes: Int, destBytes: ByteArray, destOffset: Int): Int =
        when (sourceEncoding) {
            RawSourcePcmEncoding.PCM_24BIT_PACKED ->
                PcmStreamRules.convertPcm24LeToFloat32Bytes(srcBytes, 0, alignedRawBytes, destBytes, destOffset)
            RawSourcePcmEncoding.PCM_32BIT ->
                PcmStreamRules.convertPcm32LeToFloat32Bytes(srcBytes, 0, alignedRawBytes, destBytes, destOffset)
            RawSourcePcmEncoding.PCM_8BIT ->
                PcmStreamRules.convertPcm8UnsignedToPcm16LeBytes(srcBytes, 0, alignedRawBytes, destBytes, destOffset)
            RawSourcePcmEncoding.PCM_16BIT, RawSourcePcmEncoding.PCM_FLOAT -> {
                val copyLen = minOf(alignedRawBytes, destBytes.size - destOffset)
                System.arraycopy(srcBytes, 0, destBytes, destOffset, copyLen)
                copyLen
            }
        }

    private fun ensurePendingOutputCapacity(neededBytes: Int) {
        if (pendingOutBuf.size >= neededBytes) return
        pendingOutBuf = ByteArray(maxOf(neededBytes, pendingOutBuf.size * 2))
    }

    companion object {
        private const val TAG = "BitPerfectAudio"
        private const val INITIAL_READ_BUFFER_BYTES = 65_536
        private const val MAX_READ_BUFFER_BYTES = 4 * 1024 * 1024
        private const val MAX_EMPTY_PACKET_ATTEMPTS = 16

        fun open(
            extractor: MediaExtractor,
            trackFormat: MediaFormat,
            sourceSampleRate: Int,
            sourceChannels: Int,
            durationMs: Long,
            classifiedFormat: SupportedAudioFormat?,
            flacInfo: FlacStreamInfo?,
        ): RawExtractorPcmDecoder {
            val hasPcmKey = trackFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)
            val reportedPcmEncoding = if (hasPcmKey) {
                runCatching { trackFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) }.getOrNull()
            } else {
                null
            }
            // Resolve raw PCM bit-depth strictly from MediaExtractor's trackFormat (never from the
            // container FLAC header) so an extractor emitting down-converted 16-bit PCM without
            // KEY_PCM_ENCODING is never misframed as 24-bit packed PCM.
            val reportedBitsPerSample = readBitsPerSampleHint(trackFormat)
            val sourceEncoding = PcmStreamRules.resolveRawExtractorSourceEncoding(
                hasPcmEncodingKey = hasPcmKey,
                reportedPcmEncoding = reportedPcmEncoding,
                reportedBitsPerSample = reportedBitsPerSample,
            )

            val supportsNative24And32 = supportsNativeIntegerAudioTrack(
                sourceEncoding = sourceEncoding,
                sampleRateHz = sourceSampleRate,
                channelCount = sourceChannels,
            )
            val outputEncoding = PcmStreamRules.selectRawOutputEncoding(
                sourceEncoding = sourceEncoding,
                supportsNative24And32 = supportsNative24And32,
            )
            val decoderLabel = PcmStreamRules.buildRawExtractorDecoderLabel(sourceEncoding, outputEncoding)
            val containerLabel = classifiedFormat?.containerLabel ?: if (flacInfo != null) "FLAC" else "WAV"
            val codecLabel = if (flacInfo != null || classifiedFormat == SupportedAudioFormat.FLAC) "FLAC" else "PCM"

            val decodedFormat = DecodedPcmFormat(
                sampleRateHz = sourceSampleRate,
                channelCount = sourceChannels,
                encoding = outputEncoding,
                effectiveBitDepth = outputEncoding.effectiveBitDepth,
                decoderName = decoderLabel,
                durationMs = durationMs,
                sourceContainer = containerLabel,
                sourceCodec = codecLabel,
                sourceSampleRateHz = sourceSampleRate,
                sourceBitDepth = flacInfo?.bitsPerSample ?: sourceEncoding.effectiveBitDepth,
                sourceHeaderVerified = flacInfo != null,
            )

            Log.d(
                TAG,
                "RawExtractorPcmDecoder opened: sourceContainer=$containerLabel, codec=$codecLabel, " +
                    "hasPcmKey=$hasPcmKey, rawPcmKey=$reportedPcmEncoding, bitsHint=$reportedBitsPerSample, " +
                    "resolvedSource=$sourceEncoding, outputEncoding=$outputEncoding, " +
                    "native24And32=$supportsNative24And32, rate=${sourceSampleRate}Hz, ch=$sourceChannels",
            )
            return RawExtractorPcmDecoder(
                extractor = extractor,
                sourceEncoding = sourceEncoding,
                format = decodedFormat,
            )
        }

        private fun readBitsPerSampleHint(format: MediaFormat): Int? {
            for (key in listOf("bits-per-sample", "bit-depth")) {
                if (format.containsKey(key)) {
                    val value = runCatching { format.getInteger(key) }.getOrNull()
                    if (value != null && value > 0) return value
                }
            }
            return null
        }

        private fun supportsNativeIntegerAudioTrack(sourceEncoding: RawSourcePcmEncoding, sampleRateHz: Int, channelCount: Int): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
            val candidateAndroidEncoding = when (sourceEncoding) {
                RawSourcePcmEncoding.PCM_24BIT_PACKED -> AudioFormat.ENCODING_PCM_24BIT_PACKED
                RawSourcePcmEncoding.PCM_32BIT -> AudioFormat.ENCODING_PCM_32BIT
                else -> return true
            }
            val channelMask = when (channelCount) {
                1 -> AudioFormat.CHANNEL_OUT_MONO
                2 -> AudioFormat.CHANNEL_OUT_STEREO
                4 -> AudioFormat.CHANNEL_OUT_QUAD
                6 -> AudioFormat.CHANNEL_OUT_5POINT1
                8 -> AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
                else -> AudioFormat.CHANNEL_OUT_STEREO
            }
            return runCatching {
                AudioTrack.getMinBufferSize(sampleRateHz, channelMask, candidateAndroidEncoding) > 0
            }.getOrDefault(false)
        }
    }
}
