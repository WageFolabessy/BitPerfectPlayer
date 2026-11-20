package com.cangkir.bitperfectplayer.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pure-Kotlin PCM negotiation, framing, and byte-slice rules (zero `android.*` imports).
 *
 * Centralizes critical Android `MediaCodec` -> `AudioTrack` invariants verified by JVM unit tests:
 * 1. An omitted `MediaFormat.KEY_PCM_ENCODING` in `codec.outputFormat` ALWAYS means
 *    [PcmEncodingType.PCM_16BIT] (never the requested encoding).
 * 2. High-bit-depth (>16-bit) `MediaCodec` decoding requests [PcmEncodingType.PCM_FLOAT]
 *    (losslessly preserves full 24-bit integer precision inside IEEE-754 float32 mantissa and is
 *    natively supported by AOSP `c2.android.flac.decoder` and AudioFlinger).
 * 3. Buffer sizes and write lengths always align strictly to whole PCM frames
 *    (`channelCount * bytesPerSample`).
 */
object PcmStreamRules {

    // Mirrors android.media.AudioFormat integer constants without importing android.*
    const val ANDROID_ENCODING_PCM_16BIT: Int = 2
    const val ANDROID_ENCODING_PCM_FLOAT: Int = 4
    const val ANDROID_ENCODING_PCM_24BIT_PACKED: Int = 21
    const val ANDROID_ENCODING_PCM_32BIT: Int = 22

    /**
     * Selects the PCM encoding to request when configuring Android [android.media.MediaCodec].
     * Requests [PcmEncodingType.PCM_FLOAT] for >16-bit lossless sources (e.g. 24-bit FLAC) to
     * preserve full 24-bit mantissa precision without risking broken 24-bit packed codec paths.
     */
    fun selectMediaCodecRequestEncoding(sourceBitDepth: Int?): PcmEncodingType =
        if (sourceBitDepth != null && sourceBitDepth > 16) {
            PcmEncodingType.PCM_FLOAT
        } else {
            PcmEncodingType.PCM_16BIT
        }

    /**
     * Returns `true` only when a non-default PCM encoding (e.g., [PcmEncodingType.PCM_FLOAT] for
     * 24-bit sources) must be requested on the input `MediaFormat`. For 16-bit sources, Android
     * `MediaCodec` natively defaults to 16-bit PCM without force-setting `KEY_PCM_ENCODING`.
     */
    fun shouldSetMediaCodecPcmEncodingKey(requested: PcmEncodingType): Boolean =
        requested != PcmEncodingType.PCM_16BIT

    /**
     * Resolves the ACTUAL PCM encoding emitted by `MediaCodec.getOutputFormat()`.
     *
     * CRITICAL: When [hasPcmEncodingKey] is `false` (i.e. `KEY_PCM_ENCODING` is absent from
     * `codec.outputFormat`), Android's MediaCodec contract specifies 16-bit linear PCM
     * ([PcmEncodingType.PCM_16BIT]). This function NEVER falls back to any requested encoding.
     */
    fun resolveMediaCodecOutputEncoding(
        hasPcmEncodingKey: Boolean,
        reportedEncoding: Int?,
        supportsApi31Encodings: Boolean,
    ): PcmEncodingType {
        if (!hasPcmEncodingKey || reportedEncoding == null) {
            return PcmEncodingType.PCM_16BIT
        }
        return mapAndroidPcmEncoding(reportedEncoding, supportsApi31Encodings)
    }

    fun mapAndroidPcmEncoding(
        androidEncoding: Int,
        supportsApi31Encodings: Boolean,
    ): PcmEncodingType {
        if (supportsApi31Encodings) {
            when (androidEncoding) {
                ANDROID_ENCODING_PCM_24BIT_PACKED -> return PcmEncodingType.PCM_24BIT_PACKED
                ANDROID_ENCODING_PCM_32BIT -> return PcmEncodingType.PCM_32BIT
            }
        }
        return when (androidEncoding) {
            ANDROID_ENCODING_PCM_FLOAT -> PcmEncodingType.PCM_FLOAT
            ANDROID_ENCODING_PCM_16BIT -> PcmEncodingType.PCM_16BIT
            else -> PcmEncodingType.PCM_16BIT
        }
    }

    /**
     * Rounds [byteCount] DOWN to the largest whole multiple of [bytesPerFrame].
     */
    fun alignByteCountToFrame(byteCount: Int, bytesPerFrame: Int): Int {
        if (byteCount <= 0 || bytesPerFrame <= 0) return 0
        return (byteCount / bytesPerFrame) * bytesPerFrame
    }

    /**
     * Rounds [minBytes] UP to a whole multiple of [bytesPerFrame] (at least one frame).
     */
    fun computeAlignedBufferSize(minBytes: Int, bytesPerFrame: Int): Int {
        val frame = bytesPerFrame.coerceAtLeast(1)
        val clampedMin = minBytes.coerceAtLeast(frame)
        return ((clampedMin + frame - 1) / frame) * frame
    }

    /**
     * Extracts an exact slice `[offset, offset + size)` from [source] without reading outside the
     * codec-reported bounds and without leaving residual position/limit corruption on [source].
     */
    fun extractBufferSlice(
        source: ByteBuffer,
        offset: Int,
        size: Int,
    ): ByteArray {
        if (size <= 0 || offset < 0 || offset >= source.capacity()) return ByteArray(0)
        val safeSize = minOf(size, source.capacity() - offset)
        if (safeSize <= 0) return ByteArray(0)
        val duplicate = source.duplicate()
        duplicate.limit(offset + safeSize)
        duplicate.position(offset)
        val result = ByteArray(safeSize)
        duplicate.get(result)
        return result
    }

    /**
     * Converts [byteCount] little-endian IEEE-754 float32 PCM bytes from [sourceBytes] starting at
     * [sourceOffset] into [destFloats]. Returns the exact number of floats written.
     */
    fun convertLittleEndianBytesToFloats(
        sourceBytes: ByteArray,
        sourceOffset: Int,
        byteCount: Int,
        destFloats: FloatArray,
    ): Int {
        val alignedBytes = alignByteCountToFrame(byteCount, 4)
        val floatCount = minOf(alignedBytes / 4, destFloats.size)
        if (floatCount <= 0) return 0
        val bb = ByteBuffer.wrap(sourceBytes, sourceOffset, floatCount * 4)
            .order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until floatCount) {
            destFloats[i] = bb.float
        }
        return floatCount
    }
}
