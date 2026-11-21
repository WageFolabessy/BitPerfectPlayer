package com.cangkir.bitperfectplayer.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PcmStreamRulesTest {

    @Test
    fun missingPcmEncodingKeyInOutputFormat_strictlyResolvesToPcm16Bit_neverRequestedEncoding() {
        // Even if caller requested PCM_FLOAT or PCM_24BIT_PACKED, omitted KEY_PCM_ENCODING MUST mean PCM_16BIT!
        assertEquals(
            PcmEncodingType.PCM_16BIT,
            PcmStreamRules.resolveMediaCodecOutputEncoding(
                hasPcmEncodingKey = false,
                reportedEncoding = null,
                supportsApi31Encodings = true,
            ),
        )
        assertEquals(
            PcmEncodingType.PCM_16BIT,
            PcmStreamRules.resolveMediaCodecOutputEncoding(
                hasPcmEncodingKey = false,
                reportedEncoding = PcmStreamRules.ANDROID_ENCODING_PCM_24BIT_PACKED,
                supportsApi31Encodings = true,
            ),
        )
        assertEquals(
            PcmEncodingType.PCM_16BIT,
            PcmStreamRules.resolveMediaCodecOutputEncoding(
                hasPcmEncodingKey = false,
                reportedEncoding = PcmStreamRules.ANDROID_ENCODING_PCM_FLOAT,
                supportsApi31Encodings = true,
            ),
        )
    }

    @Test
    fun explicitPcmEncodingKey_resolvesAccuratelyAcrossApiLevels() {
        assertEquals(
            PcmEncodingType.PCM_FLOAT,
            PcmStreamRules.resolveMediaCodecOutputEncoding(
                hasPcmEncodingKey = true,
                reportedEncoding = PcmStreamRules.ANDROID_ENCODING_PCM_FLOAT,
                supportsApi31Encodings = true,
            ),
        )
        assertEquals(
            PcmEncodingType.PCM_24BIT_PACKED,
            PcmStreamRules.resolveMediaCodecOutputEncoding(
                hasPcmEncodingKey = true,
                reportedEncoding = PcmStreamRules.ANDROID_ENCODING_PCM_24BIT_PACKED,
                supportsApi31Encodings = true,
            ),
        )
        assertEquals(
            PcmEncodingType.PCM_16BIT,
            PcmStreamRules.resolveMediaCodecOutputEncoding(
                hasPcmEncodingKey = true,
                reportedEncoding = PcmStreamRules.ANDROID_ENCODING_PCM_24BIT_PACKED,
                supportsApi31Encodings = false,
            ),
        )
    }

    @Test
    fun selectMediaCodecRequestEncoding_requestsFloatForHighBitDepthAnd16BitOtherwise() {
        assertEquals(PcmEncodingType.PCM_FLOAT, PcmStreamRules.selectMediaCodecRequestEncoding(24))
        assertEquals(PcmEncodingType.PCM_FLOAT, PcmStreamRules.selectMediaCodecRequestEncoding(32))
        assertEquals(PcmEncodingType.PCM_16BIT, PcmStreamRules.selectMediaCodecRequestEncoding(16))
        assertEquals(PcmEncodingType.PCM_16BIT, PcmStreamRules.selectMediaCodecRequestEncoding(null))

        // Only non-16-bit requests should explicitly set KEY_PCM_ENCODING on MediaFormat
        assertEquals(true, PcmStreamRules.shouldSetMediaCodecPcmEncodingKey(PcmEncodingType.PCM_FLOAT))
        assertEquals(false, PcmStreamRules.shouldSetMediaCodecPcmEncodingKey(PcmEncodingType.PCM_16BIT))
    }

    @Test
    fun frameAlignmentAndBufferSize_preserveWholeFramesAcross16Bit_24BitPacked_andFloat32() {
        // Stereo 16-bit: 4 bytes/frame
        assertEquals(8192, PcmStreamRules.alignByteCountToFrame(8195, 4))
        assertEquals(8192, PcmStreamRules.computeAlignedBufferSize(8192, 4))

        // Stereo 24-bit packed: 6 bytes/frame (8192 % 6 == 2 -> MUST round down to 8190 / up to 8196!)
        assertEquals(8190, PcmStreamRules.alignByteCountToFrame(8192, 6))
        assertEquals(8196, PcmStreamRules.computeAlignedBufferSize(8192, 6))
        assertEquals(0, PcmStreamRules.computeAlignedBufferSize(16_384, 6) % 6)

        // Stereo float32: 8 bytes/frame
        assertEquals(8192, PcmStreamRules.alignByteCountToFrame(8199, 8))
        assertEquals(16_384, PcmStreamRules.computeAlignedBufferSize(16_384, 8))
        assertEquals(0, PcmStreamRules.computeAlignedBufferSize(16_385, 8) % 8)

        // FloatArray(bufferSize / 4) MUST always be an exact multiple of channelCount for PCM_FLOAT
        for (channelCount in listOf(1, 2, 4, 6, 8)) {
            val bytesPerFrame = channelCount * 4
            val bufferSize = PcmStreamRules.computeAlignedBufferSize(16_384, bytesPerFrame)
            assertEquals(0, bufferSize % 4)
            assertEquals(0, (bufferSize / 4) % channelCount)
        }
    }

    @Test
    fun computeAudioTrackBufferSizeBytes_enforcesFrameAligned2xMinimumAnd1024FrameOr50MsFloor() {
        // Stereo 24-bit packed (6 bytes/frame) at 96 kHz: 2x reported min buffer size (30,724), rounded up to multiple of 6 (30,726)
        val buf24Packed = PcmStreamRules.computeAudioTrackBufferSizeBytes(
            reportedMinBufferSize = 15_362,
            sampleRateHz = 96_000,
            bytesPerFrame = 6,
        )
        assertEquals(0, buf24Packed % 6)
        assertEquals(30_726, buf24Packed)

        // Negative or zero AudioTrack.getMinBufferSize fallback enforces max(2 * 1024 frames, sampleRateHz / 20 frames)
        val fallback44k1Stereo16 = PcmStreamRules.computeAudioTrackBufferSizeBytes(
            reportedMinBufferSize = -2,
            sampleRateHz = 44_100,
            bytesPerFrame = 4,
        )
        assertEquals(0, fallback44k1Stereo16 % 4)
        assertEquals(4 * (44_100 / 20), fallback44k1Stereo16)
    }

    @Test
    fun extractBufferSlice_respectsExactOffsetAndSizeWithoutMutatingSource() {
        val raw = ByteArray(32) { it.toByte() }
        val buffer = ByteBuffer.wrap(raw)
        buffer.position(5)
        buffer.limit(29)

        val slice = PcmStreamRules.extractBufferSlice(buffer, offset = 7, size = 10)
        assertArrayEquals(raw.copyOfRange(7, 17), slice)
        // Original buffer position/limit untouched
        assertEquals(5, buffer.position())
        assertEquals(29, buffer.limit())
    }

    @Test
    fun convertLittleEndianBytesToFloats_decodesExactSamples() {
        val expected = floatArrayOf(-1.0f, -0.5f, 0.0f, 0.25f, 0.9999999f)
        val bb = ByteBuffer.allocate(expected.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        expected.forEach { bb.putFloat(it) }

        val out = FloatArray(expected.size)
        val count = PcmStreamRules.convertLittleEndianBytesToFloats(bb.array(), 0, bb.array().size, out)
        assertEquals(expected.size, count)
        for (i in expected.indices) {
            assertEquals(expected[i], out[i], 0.0f)
        }
    }

    @Test
    fun audioFormatFormatter_usesExactHzAndRemovesRedundantContainerCodec() {
        assertEquals("96,000 Hz", AudioFormatFormatter.formatSampleRateHz(96_000))
        assertEquals("48,000 Hz", AudioFormatFormatter.formatSampleRateHz(48_000))
        assertEquals("44,100 Hz", AudioFormatFormatter.formatSampleRateHz(44_100))
        assertEquals("192,000 Hz", AudioFormatFormatter.formatSampleRateHz(192_000))
        assertEquals("Unknown Hz", AudioFormatFormatter.formatSampleRateHz(0))

        assertEquals("FLAC", AudioFormatFormatter.formatContainerAndCodec("FLAC", "FLAC"))
        assertEquals("FLAC", AudioFormatFormatter.formatContainerAndCodec("FLAC", "flac"))
        assertEquals("MP3", AudioFormatFormatter.formatContainerAndCodec("MP3", "MP3"))
        assertEquals("WAV (PCM)", AudioFormatFormatter.formatContainerAndCodec("WAV", "PCM"))
        assertEquals("M4A (AAC)", AudioFormatFormatter.formatContainerAndCodec("M4A", "AAC"))

        assertEquals(
            "FLAC · 24-bit · 96,000 Hz",
            AudioFormatFormatter.formatTrackMetadataLine("FLAC", "FLAC", 24, 96_000),
        )
        assertEquals(
            "WAV (PCM) · 16-bit · 44,100 Hz",
            AudioFormatFormatter.formatTrackMetadataLine("WAV", "PCM", 16, 44_100),
        )

        // Allocation-minimal duration formatting & precomputed PlaybackTrack fields
        assertEquals("00:00", AudioFormatFormatter.formatDurationMs(0L))
        assertEquals("00:59", AudioFormatFormatter.formatDurationMs(59_000L))
        assertEquals("05:51", AudioFormatFormatter.formatDurationMs(351_000L))
        assertEquals("1:01:01", AudioFormatFormatter.formatDurationMs(3_661_000L))

        val track = PlaybackTrack(
            uriString = "content://media/external/audio/media/99",
            title = "Blue in Green",
            artist = "Miles Davis",
            album = "Kind of Blue",
            fileName = "03.flac",
            durationMs = 351_000L,
            sampleRateHz = 96_000,
            bitDepth = 24,
            container = "FLAC",
            codec = "FLAC",
        )
        assertEquals("05:51", track.durationLabel)
        assertEquals("Miles Davis · Kind of Blue", track.artistAlbumLine)
        assertEquals("FLAC · 24-bit · 96,000 Hz", track.formatBadge)
    }

    @Test
    fun resolveRawExtractorSourceEncoding_handlesAllPcmEncodingsAndBitsPerSampleFallbacks() {
        // Explicit KEY_PCM_ENCODING resolves every Android raw PCM encoding cleanly
        assertEquals(
            RawSourcePcmEncoding.PCM_8BIT,
            PcmStreamRules.resolveRawExtractorSourceEncoding(true, PcmStreamRules.ANDROID_ENCODING_PCM_8BIT, null),
        )
        assertEquals(
            RawSourcePcmEncoding.PCM_16BIT,
            PcmStreamRules.resolveRawExtractorSourceEncoding(true, PcmStreamRules.ANDROID_ENCODING_PCM_16BIT, 24),
        )
        assertEquals(
            RawSourcePcmEncoding.PCM_24BIT_PACKED,
            PcmStreamRules.resolveRawExtractorSourceEncoding(true, PcmStreamRules.ANDROID_ENCODING_PCM_24BIT_PACKED, null),
        )
        assertEquals(
            RawSourcePcmEncoding.PCM_32BIT,
            PcmStreamRules.resolveRawExtractorSourceEncoding(true, PcmStreamRules.ANDROID_ENCODING_PCM_32BIT, 24),
        )
        assertEquals(
            RawSourcePcmEncoding.PCM_FLOAT,
            PcmStreamRules.resolveRawExtractorSourceEncoding(true, PcmStreamRules.ANDROID_ENCODING_PCM_FLOAT, 24),
        )

        // Missing KEY_PCM_ENCODING falls back cleanly to bits-per-sample / bit-depth
        assertEquals(
            RawSourcePcmEncoding.PCM_8BIT,
            PcmStreamRules.resolveRawExtractorSourceEncoding(false, null, 8),
        )
        assertEquals(
            RawSourcePcmEncoding.PCM_16BIT,
            PcmStreamRules.resolveRawExtractorSourceEncoding(false, null, 16),
        )
        assertEquals(
            RawSourcePcmEncoding.PCM_24BIT_PACKED,
            PcmStreamRules.resolveRawExtractorSourceEncoding(false, null, 24),
        )
        assertEquals(
            RawSourcePcmEncoding.PCM_32BIT,
            PcmStreamRules.resolveRawExtractorSourceEncoding(false, null, 32),
        )

        // Both absent or unrecognized defaults safely to PCM_16BIT
        assertEquals(
            RawSourcePcmEncoding.PCM_16BIT,
            PcmStreamRules.resolveRawExtractorSourceEncoding(false, null, null),
        )
        assertEquals(
            RawSourcePcmEncoding.PCM_16BIT,
            PcmStreamRules.resolveRawExtractorSourceEncoding(true, 999, null),
        )
    }

    @Test
    fun selectRawOutputEncoding_usesNativePassthroughWhenSupportedAndLosslessConversionOtherwise() {
        assertEquals(
            PcmEncodingType.PCM_24BIT_PACKED,
            PcmStreamRules.selectRawOutputEncoding(RawSourcePcmEncoding.PCM_24BIT_PACKED, supportsNative24And32 = true),
        )
        assertEquals(
            PcmEncodingType.PCM_FLOAT,
            PcmStreamRules.selectRawOutputEncoding(RawSourcePcmEncoding.PCM_24BIT_PACKED, supportsNative24And32 = false),
        )
        assertEquals(
            PcmEncodingType.PCM_32BIT,
            PcmStreamRules.selectRawOutputEncoding(RawSourcePcmEncoding.PCM_32BIT, supportsNative24And32 = true),
        )
        assertEquals(
            PcmEncodingType.PCM_FLOAT,
            PcmStreamRules.selectRawOutputEncoding(RawSourcePcmEncoding.PCM_32BIT, supportsNative24And32 = false),
        )
        assertEquals(
            PcmEncodingType.PCM_16BIT,
            PcmStreamRules.selectRawOutputEncoding(RawSourcePcmEncoding.PCM_8BIT, supportsNative24And32 = true),
        )
        assertEquals(
            PcmEncodingType.PCM_16BIT,
            PcmStreamRules.selectRawOutputEncoding(RawSourcePcmEncoding.PCM_16BIT, supportsNative24And32 = false),
        )
        assertEquals(
            PcmEncodingType.PCM_FLOAT,
            PcmStreamRules.selectRawOutputEncoding(RawSourcePcmEncoding.PCM_FLOAT, supportsNative24And32 = false),
        )

        assertEquals(
            false,
            PcmStreamRules.requiresRawPcmConversion(RawSourcePcmEncoding.PCM_24BIT_PACKED, PcmEncodingType.PCM_24BIT_PACKED),
        )
        assertEquals(
            true,
            PcmStreamRules.requiresRawPcmConversion(RawSourcePcmEncoding.PCM_24BIT_PACKED, PcmEncodingType.PCM_FLOAT),
        )
        assertEquals(
            "MediaExtractor Direct PCM (PCM 24-bit Packed)",
            PcmStreamRules.buildRawExtractorDecoderLabel(RawSourcePcmEncoding.PCM_24BIT_PACKED, PcmEncodingType.PCM_24BIT_PACKED),
        )
        assertEquals(
            "MediaExtractor Direct PCM (PCM 24-bit Packed -> PCM_FLOAT (32-bit IEEE))",
            PcmStreamRules.buildRawExtractorDecoderLabel(RawSourcePcmEncoding.PCM_24BIT_PACKED, PcmEncodingType.PCM_FLOAT),
        )
    }

    @Test
    fun convertPcm24LeToFloat32Bytes_producesExactFiniteNormalizedFloatsWithZeroNoise() {
        val samples24 = intArrayOf(-8_388_608, -4_194_304, -1, 0, 1, 4_194_304, 8_388_607)
        val src = ByteArray(2 + samples24.size * 3) // Include non-zero srcOffset = 2
        for (i in samples24.indices) {
            val v = samples24[i]
            val idx = 2 + i * 3
            src[idx] = (v and 0xFF).toByte()
            src[idx + 1] = ((v ushr 8) and 0xFF).toByte()
            src[idx + 2] = ((v ushr 16) and 0xFF).toByte()
        }

        val dest = ByteArray(4 + samples24.size * 4) // Include non-zero destOffset = 4
        val written = PcmStreamRules.convertPcm24LeToFloat32Bytes(
            srcBytes = src,
            srcOffset = 2,
            srcByteCount = samples24.size * 3,
            destBytes = dest,
            destOffset = 4,
        )
        assertEquals(samples24.size * 4, written)

        val outFloats = FloatArray(samples24.size)
        assertEquals(
            samples24.size,
            PcmStreamRules.convertLittleEndianBytesToFloats(dest, 4, written, outFloats),
        )
        val expected = floatArrayOf(
            -1.0f,
            -0.5f,
            -1.0f / 8_388_608.0f,
            0.0f,
            1.0f / 8_388_608.0f,
            0.5f,
            8_388_607.0f / 8_388_608.0f,
        )
        for (i in expected.indices) {
            assertEquals(true, outFloats[i].isFinite())
            assertEquals(expected[i], outFloats[i], 0.0f)
        }
    }

    @Test
    fun convertPcm32LeToFloat32Bytes_producesExactFiniteFloatsWithoutOverflow() {
        val samples32 = intArrayOf(Int.MIN_VALUE, -1_073_741_824, 0, 1_073_741_824, Int.MAX_VALUE)
        val src = ByteArray(samples32.size * 4)
        val bb = ByteBuffer.wrap(src).order(ByteOrder.LITTLE_ENDIAN)
        samples32.forEach { bb.putInt(it) }

        val dest = ByteArray(samples32.size * 4)
        val written = PcmStreamRules.convertPcm32LeToFloat32Bytes(src, 0, src.size, dest, 0)
        assertEquals(samples32.size * 4, written)

        val outFloats = FloatArray(samples32.size)
        assertEquals(
            samples32.size,
            PcmStreamRules.convertLittleEndianBytesToFloats(dest, 0, written, outFloats),
        )
        val expected = floatArrayOf(-1.0f, -0.5f, 0.0f, 0.5f, 1.0f)
        for (i in expected.indices) {
            assertEquals(true, outFloats[i].isFinite())
            assertEquals(expected[i], outFloats[i], 1e-6f)
        }
    }

    @Test
    fun convertPcm8UnsignedToPcm16LeBytes_promotesLosslesslyAroundCenter128() {
        val src = byteArrayOf(0, 64, 128.toByte(), 192.toByte(), 255.toByte())
        val dest = ByteArray(src.size * 2)
        val written = PcmStreamRules.convertPcm8UnsignedToPcm16LeBytes(src, 0, src.size, dest, 0)
        assertEquals(src.size * 2, written)

        val bb = ByteBuffer.wrap(dest).order(ByteOrder.LITTLE_ENDIAN)
        val expected = shortArrayOf(-32_768, -16_384, 0, 16_384, 32_512)
        for (i in expected.indices) {
            assertEquals(expected[i], bb.short)
        }
    }
}
