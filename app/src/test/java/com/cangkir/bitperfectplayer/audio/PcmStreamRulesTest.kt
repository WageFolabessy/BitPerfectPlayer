package com.cangkir.bitperfectplayer.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
    }
}
