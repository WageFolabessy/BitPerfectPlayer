package com.cangkir.bitperfectplayer.audio

import java.nio.ByteBuffer

/**
 * Raw source PCM encoding reported by Android's `MediaExtractor` when a container extractor
 * (such as `FlacExtractor` on Android 16 / Custom ROMs) exposes `audio/raw` (`MIMETYPE_AUDIO_RAW`)
 * directly instead of compressed frames.
 */
enum class RawSourcePcmEncoding(val bytesPerSample: Int, val effectiveBitDepth: Int, val displayLabel: String) {
    PCM_8BIT(bytesPerSample = 1, effectiveBitDepth = 8, displayLabel = "PCM 8-bit Unsigned"),
    PCM_16BIT(bytesPerSample = 2, effectiveBitDepth = 16, displayLabel = "PCM 16-bit"),
    PCM_24BIT_PACKED(bytesPerSample = 3, effectiveBitDepth = 24, displayLabel = "PCM 24-bit Packed"),
    PCM_32BIT(bytesPerSample = 4, effectiveBitDepth = 32, displayLabel = "PCM 32-bit Int"),
    PCM_FLOAT(bytesPerSample = 4, effectiveBitDepth = 24, displayLabel = "PCM 32-bit Float"),
}

/**
 * Pure-Kotlin PCM negotiation, framing, and byte-slice rules (zero `android.*` imports).
 *
 * Centralizes critical Android `MediaExtractor` / `MediaCodec` -> `AudioTrack` invariants verified
 * by JVM unit tests:
 * 1. An omitted `MediaFormat.KEY_PCM_ENCODING` in `codec.outputFormat` ALWAYS means
 *    [PcmEncodingType.PCM_16BIT] (never the requested encoding).
 * 2. When `MediaExtractor` exposes `audio/raw`, the true source PCM encoding MUST be resolved
 *    from `KEY_PCM_ENCODING` (falling back to `"bits-per-sample"` / `"bit-depth"`, then `PCM_16BIT`)
 *    and NEVER overwritten with `ENCODING_PCM_FLOAT` before `c2.android.raw.decoder`.
 * 3. High-bit-depth (>16-bit) compressed `MediaCodec` decoding requests [PcmEncodingType.PCM_FLOAT]
 *    (losslessly preserves full 24-bit integer precision inside IEEE-754 float32 mantissa and is
 *    natively supported by AOSP `c2.android.flac.decoder` and AudioFlinger).
 * 4. Buffer sizes and write lengths always align strictly to whole PCM frames
 *    (`channelCount * bytesPerSample`).
 */
object PcmStreamRules {

    // Mirrors android.media.AudioFormat integer constants without importing android.*
    const val ANDROID_ENCODING_PCM_16BIT: Int = 2
    const val ANDROID_ENCODING_PCM_8BIT: Int = 3
    const val ANDROID_ENCODING_PCM_FLOAT: Int = 4
    const val ANDROID_ENCODING_PCM_24BIT_PACKED: Int = 21
    const val ANDROID_ENCODING_PCM_32BIT: Int = 22

    private const val INT24_FLOAT_DIVISOR = 8_388_608.0f
    private const val INT32_DOUBLE_DIVISOR = 2_147_483_648.0

    /**
     * Resolves the true PCM encoding emitted by `MediaExtractor` for a `MediaFormat.MIMETYPE_AUDIO_RAW`
     * track, checking `KEY_PCM_ENCODING` first (independent of API-31 AudioTrack support so true
     * 24/32-bit source bytes are never misclassified as 16-bit), then falling back to
     * `"bits-per-sample"` / `"bit-depth"`, and finally defaulting to [RawSourcePcmEncoding.PCM_16BIT].
     */
    fun resolveRawExtractorSourceEncoding(
        hasPcmEncodingKey: Boolean,
        reportedPcmEncoding: Int?,
        reportedBitsPerSample: Int?,
    ): RawSourcePcmEncoding {
        val fromKey = if (hasPcmEncodingKey && reportedPcmEncoding != null) {
            when (reportedPcmEncoding) {
                ANDROID_ENCODING_PCM_8BIT -> RawSourcePcmEncoding.PCM_8BIT
                ANDROID_ENCODING_PCM_16BIT -> RawSourcePcmEncoding.PCM_16BIT
                ANDROID_ENCODING_PCM_24BIT_PACKED -> RawSourcePcmEncoding.PCM_24BIT_PACKED
                ANDROID_ENCODING_PCM_32BIT -> RawSourcePcmEncoding.PCM_32BIT
                ANDROID_ENCODING_PCM_FLOAT -> RawSourcePcmEncoding.PCM_FLOAT
                else -> null
            }
        } else {
            null
        }
        return fromKey ?: when (reportedBitsPerSample) {
            8 -> RawSourcePcmEncoding.PCM_8BIT
            16 -> RawSourcePcmEncoding.PCM_16BIT
            24 -> RawSourcePcmEncoding.PCM_24BIT_PACKED
            32 -> RawSourcePcmEncoding.PCM_32BIT
            else -> RawSourcePcmEncoding.PCM_16BIT
        }
    }

    /**
     * Selects the safest lossless [PcmEncodingType] for delivering an `audio/raw` stream to
     * `AudioTrack`. Uses bit-identical native passthrough for 16-bit, float32, and (when supported
     * by API 31+ `AudioTrack`) 24-bit packed / 32-bit int; otherwise converts losslessly to
     * [PcmEncodingType.PCM_FLOAT] (or [PcmEncodingType.PCM_16BIT] for 8-bit unsigned).
     */
    fun selectRawOutputEncoding(sourceEncoding: RawSourcePcmEncoding, supportsNative24And32: Boolean): PcmEncodingType =
        when (sourceEncoding) {
            RawSourcePcmEncoding.PCM_8BIT -> PcmEncodingType.PCM_16BIT
            RawSourcePcmEncoding.PCM_16BIT -> PcmEncodingType.PCM_16BIT
            RawSourcePcmEncoding.PCM_24BIT_PACKED ->
                if (supportsNative24And32) PcmEncodingType.PCM_24BIT_PACKED else PcmEncodingType.PCM_FLOAT
            RawSourcePcmEncoding.PCM_32BIT ->
                if (supportsNative24And32) PcmEncodingType.PCM_32BIT else PcmEncodingType.PCM_FLOAT
            RawSourcePcmEncoding.PCM_FLOAT -> PcmEncodingType.PCM_FLOAT
        }

    fun requiresRawPcmConversion(sourceEncoding: RawSourcePcmEncoding, outputEncoding: PcmEncodingType): Boolean = when {
        sourceEncoding == RawSourcePcmEncoding.PCM_16BIT && outputEncoding == PcmEncodingType.PCM_16BIT -> false
        sourceEncoding == RawSourcePcmEncoding.PCM_24BIT_PACKED && outputEncoding == PcmEncodingType.PCM_24BIT_PACKED -> false
        sourceEncoding == RawSourcePcmEncoding.PCM_32BIT && outputEncoding == PcmEncodingType.PCM_32BIT -> false
        sourceEncoding == RawSourcePcmEncoding.PCM_FLOAT && outputEncoding == PcmEncodingType.PCM_FLOAT -> false
        else -> true
    }

    fun buildRawExtractorDecoderLabel(sourceEncoding: RawSourcePcmEncoding, outputEncoding: PcmEncodingType): String =
        if (!requiresRawPcmConversion(sourceEncoding, outputEncoding)) {
            "MediaExtractor Direct PCM (${sourceEncoding.displayLabel})"
        } else {
            "MediaExtractor Direct PCM (${sourceEncoding.displayLabel} -> ${outputEncoding.displayLabel})"
        }

    /**
     * Zero-allocation lossless conversion of packed signed 24-bit little-endian integer PCM bytes
     * into IEEE-754 32-bit little-endian float PCM bytes (`[-1.0f, 1.0f)`). Every 24-bit signed
     * integer is exact in float32's 24-bit significand. Returns exact destination bytes written.
     */
    fun convertPcm24LeToFloat32Bytes(
        srcBytes: ByteArray,
        srcOffset: Int,
        srcByteCount: Int,
        destBytes: ByteArray,
        destOffset: Int = 0,
    ): Int {
        if (srcByteCount <= 0 || srcOffset < 0 || destOffset < 0) return 0
        if (srcOffset >= srcBytes.size || destOffset >= destBytes.size) return 0
        val availableSrc = minOf(srcByteCount, srcBytes.size - srcOffset)
        val availableDest = destBytes.size - destOffset
        val sampleCount = minOf(availableSrc / 3, availableDest / 4)
        if (sampleCount <= 0) return 0

        var sIdx = srcOffset
        var dIdx = destOffset
        for (i in 0 until sampleCount) {
            val b0 = srcBytes[sIdx].toInt() and 0xFF
            val b1 = srcBytes[sIdx + 1].toInt() and 0xFF
            val b2 = srcBytes[sIdx + 2].toInt() // Signed MSB preserves 24-bit sign extension
            val sample24 = (b2 shl 16) or (b1 shl 8) or b0
            val floatBits = (sample24.toFloat() / INT24_FLOAT_DIVISOR).toRawBits()
            destBytes[dIdx] = (floatBits and 0xFF).toByte()
            destBytes[dIdx + 1] = ((floatBits ushr 8) and 0xFF).toByte()
            destBytes[dIdx + 2] = ((floatBits ushr 16) and 0xFF).toByte()
            destBytes[dIdx + 3] = ((floatBits ushr 24) and 0xFF).toByte()
            sIdx += 3
            dIdx += 4
        }
        return sampleCount * 4
    }

    /**
     * Zero-allocation conversion of signed 32-bit little-endian integer PCM bytes into IEEE-754
     * 32-bit little-endian float PCM bytes (`[-1.0f, 1.0f]`). Zero overflow even at `Int.MIN_VALUE`.
     * Returns exact destination bytes written.
     */
    fun convertPcm32LeToFloat32Bytes(
        srcBytes: ByteArray,
        srcOffset: Int,
        srcByteCount: Int,
        destBytes: ByteArray,
        destOffset: Int = 0,
    ): Int {
        if (srcByteCount <= 0 || srcOffset < 0 || destOffset < 0) return 0
        if (srcOffset >= srcBytes.size || destOffset >= destBytes.size) return 0
        val availableSrc = minOf(srcByteCount, srcBytes.size - srcOffset)
        val availableDest = destBytes.size - destOffset
        val sampleCount = minOf(availableSrc / 4, availableDest / 4)
        if (sampleCount <= 0) return 0

        var sIdx = srcOffset
        var dIdx = destOffset
        for (i in 0 until sampleCount) {
            val b0 = srcBytes[sIdx].toInt() and 0xFF
            val b1 = srcBytes[sIdx + 1].toInt() and 0xFF
            val b2 = srcBytes[sIdx + 2].toInt() and 0xFF
            val b3 = srcBytes[sIdx + 3].toInt()
            val sample32 = (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
            val floatBits = (sample32.toDouble() / INT32_DOUBLE_DIVISOR).toFloat().toRawBits()
            destBytes[dIdx] = (floatBits and 0xFF).toByte()
            destBytes[dIdx + 1] = ((floatBits ushr 8) and 0xFF).toByte()
            destBytes[dIdx + 2] = ((floatBits ushr 16) and 0xFF).toByte()
            destBytes[dIdx + 3] = ((floatBits ushr 24) and 0xFF).toByte()
            sIdx += 4
            dIdx += 4
        }
        return sampleCount * 4
    }

    /**
     * Zero-allocation lossless promotion of unsigned 8-bit PCM bytes (`0..255`, center `128`) into
     * signed 16-bit little-endian PCM bytes. Returns exact destination bytes written.
     */
    fun convertPcm8UnsignedToPcm16LeBytes(
        srcBytes: ByteArray,
        srcOffset: Int,
        srcByteCount: Int,
        destBytes: ByteArray,
        destOffset: Int = 0,
    ): Int {
        if (srcByteCount <= 0 || srcOffset < 0 || destOffset < 0) return 0
        if (srcOffset >= srcBytes.size || destOffset >= destBytes.size) return 0
        val availableSrc = minOf(srcByteCount, srcBytes.size - srcOffset)
        val availableDest = destBytes.size - destOffset
        val sampleCount = minOf(availableSrc, availableDest / 2)
        if (sampleCount <= 0) return 0

        var sIdx = srcOffset
        var dIdx = destOffset
        for (i in 0 until sampleCount) {
            val u8 = srcBytes[sIdx].toInt() and 0xFF
            val s16 = (u8 - 128) shl 8
            destBytes[dIdx] = (s16 and 0xFF).toByte()
            destBytes[dIdx + 1] = ((s16 ushr 8) and 0xFF).toByte()
            sIdx += 1
            dIdx += 2
        }
        return sampleCount * 2
    }

    /**
     * Selects the PCM encoding to request when configuring Android [android.media.MediaCodec].
     * Requests [PcmEncodingType.PCM_FLOAT] for >16-bit lossless sources (e.g. 24-bit FLAC) to
     * preserve full 24-bit mantissa precision without risking broken 24-bit packed codec paths.
     */
    fun selectMediaCodecRequestEncoding(sourceBitDepth: Int?): PcmEncodingType = if (sourceBitDepth != null && sourceBitDepth > 16) {
        PcmEncodingType.PCM_FLOAT
    } else {
        PcmEncodingType.PCM_16BIT
    }

    /**
     * Returns `true` only when a non-default PCM encoding (e.g., [PcmEncodingType.PCM_FLOAT] for
     * 24-bit sources) must be requested on the input `MediaFormat`. For 16-bit sources, Android
     * `MediaCodec` natively defaults to 16-bit PCM without force-setting `KEY_PCM_ENCODING`.
     */
    fun shouldSetMediaCodecPcmEncodingKey(requested: PcmEncodingType): Boolean = requested != PcmEncodingType.PCM_16BIT

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

    fun mapAndroidPcmEncoding(androidEncoding: Int, supportsApi31Encodings: Boolean): PcmEncodingType {
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
     * Computes a frame-aligned `AudioTrack` stream buffer size in bytes that guarantees at least
     * `2x` the OS minimum buffer (with a `1024`-frame floor) or `~50 ms` (`sampleRateHz / 20`),
     * rounded up to an exact multiple of [bytesPerFrame].
     */
    fun computeAudioTrackBufferSizeBytes(reportedMinBufferSize: Int, sampleRateHz: Int, bytesPerFrame: Int): Int {
        val frame = bytesPerFrame.coerceAtLeast(1)
        val safeRate = sampleRateHz.coerceAtLeast(1)
        val minBuf = reportedMinBufferSize.coerceAtLeast(frame * 1024)
        val rawBufSize = maxOf(minBuf * 2, frame * (safeRate / 20))
        return computeAlignedBufferSize(rawBufSize, frame)
    }

    /**
     * Extracts an exact slice `[offset, offset + size)` from [source] without reading outside the
     * codec-reported bounds and without leaving residual position/limit corruption on [source].
     */
    fun extractBufferSlice(source: ByteBuffer, offset: Int, size: Int): ByteArray {
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
     * Zero-allocation conversion of [byteCount] little-endian IEEE-754 float32 PCM bytes from
     * [sourceBytes] starting at [sourceOffset] into [destFloats]. Returns the exact number of
     * floats written.
     */
    fun convertLittleEndianBytesToFloats(sourceBytes: ByteArray, sourceOffset: Int, byteCount: Int, destFloats: FloatArray): Int {
        if (byteCount <= 0 || sourceOffset < 0 || sourceOffset >= sourceBytes.size) return 0
        val availableBytes = minOf(byteCount, sourceBytes.size - sourceOffset)
        val alignedBytes = alignByteCountToFrame(availableBytes, 4)
        val floatCount = minOf(alignedBytes / 4, destFloats.size)
        if (floatCount <= 0) return 0
        var idx = sourceOffset
        for (i in 0 until floatCount) {
            val bits = (sourceBytes[idx].toInt() and 0xFF) or
                ((sourceBytes[idx + 1].toInt() and 0xFF) shl 8) or
                ((sourceBytes[idx + 2].toInt() and 0xFF) shl 16) or
                (sourceBytes[idx + 3].toInt() shl 24)
            destFloats[i] = Float.fromBits(bits)
            idx += 4
        }
        return floatCount
    }
}
