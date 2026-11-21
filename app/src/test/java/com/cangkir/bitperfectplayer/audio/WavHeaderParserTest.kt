package com.cangkir.bitperfectplayer.audio

import com.cangkir.bitperfectplayer.lyrics.ByteArraySeekableByteSource
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WavHeaderParserTest {

    private fun le16(v: Int): ByteArray = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()

    private fun le32(v: Int): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()

    private fun buildStandardWav(
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int,
        pcmData: ByteArray,
        extraChunksBeforeFmt: List<Pair<String, ByteArray>> = emptyList(),
        extraChunksBeforeData: List<Pair<String, ByteArray>> = emptyList(),
        formatTag: Int = 1,
    ): ByteArray {
        val body = ByteArrayOutputStream()
        body.write("WAVE".toByteArray(Charsets.US_ASCII))

        for ((id, payload) in extraChunksBeforeFmt) {
            body.write(id.toByteArray(Charsets.US_ASCII))
            body.write(le32(payload.size))
            body.write(payload)
            if (payload.size % 2 == 1) body.write(0) // Word alignment pad byte
        }

        val blockAlign = channels * (bitsPerSample / 8)
        val byteRate = sampleRate * blockAlign
        val fmtPayload = ByteArrayOutputStream().apply {
            write(le16(formatTag))
            write(le16(channels))
            write(le32(sampleRate))
            write(le32(byteRate))
            write(le16(blockAlign))
            write(le16(bitsPerSample))
        }.toByteArray()

        body.write("fmt ".toByteArray(Charsets.US_ASCII))
        body.write(le32(fmtPayload.size))
        body.write(fmtPayload)

        for ((id, payload) in extraChunksBeforeData) {
            body.write(id.toByteArray(Charsets.US_ASCII))
            body.write(le32(payload.size))
            body.write(payload)
            if (payload.size % 2 == 1) body.write(0)
        }

        body.write("data".toByteArray(Charsets.US_ASCII))
        body.write(le32(pcmData.size))
        body.write(pcmData)

        val bodyBytes = body.toByteArray()
        return ByteArrayOutputStream().apply {
            write("RIFF".toByteArray(Charsets.US_ASCII))
            write(le32(bodyBytes.size))
            write(bodyBytes)
        }.toByteArray()
    }

    @Test
    fun parseValid16BitWav_andStreamDecodeAndSeek() {
        // 44100 Hz stereo 16-bit -> 1 second = 44100 frames * 4 bytes = 176400 bytes
        val pcm = ByteArray(176_400) { idx -> (idx and 0xFF).toByte() }
        val wavBytes = buildStandardWav(44_100, 2, 16, pcm)

        val header = WavHeaderParser.parse(ByteArraySeekableByteSource(wavBytes))
        assertEquals(44_100, header.sampleRateHz)
        assertEquals(2, header.channelCount)
        assertEquals(16, header.bitsPerSample)
        assertEquals(4, header.blockAlign)
        assertFalse(header.isFloatPcm)
        assertEquals(44L, header.dataOffsetBytes)
        assertEquals(176_400L, header.dataSizeBytes)
        assertEquals(1_000L, header.durationMs)

        WavPcmDecoder(ByteArraySeekableByteSource(wavBytes), supportsNative24And32BitPcm = true).use { decoder ->
            assertEquals(PcmEncodingType.PCM_16BIT, decoder.format.encoding)
            assertEquals(16, decoder.format.effectiveBitDepth)

            val firstRead = ByteArray(400)
            assertEquals(400, decoder.readPcm(firstRead))
            assertArrayEquals(pcm.copyOfRange(0, 400), firstRead)

            // Seek to 500 ms (frame 22050 -> byte offset 88200)
            val actualSeekMs = decoder.seekToMs(500L)
            assertEquals(500L, actualSeekMs)
            val midRead = ByteArray(16)
            assertEquals(16, decoder.readPcm(midRead))
            assertArrayEquals(pcm.copyOfRange(88_200, 88_216), midRead)
        }
    }

    @Test
    fun parseValid24BitWav_preservesFull24BitPrecisionOnApi31Plus_andUnpacksToFloat32OnApi26To30() {
        // 4 stereo frames of 24-bit PCM = 4 * 2 * 3 = 24 bytes
        // Sample values: 0, +8388607 (0x7FFFFF), -8388608 (0x800000), -1 (0xFFFFFF), +1 (0x000001), -4194304 (0xC00000), +4194304 (0x400000), +123456
        val pcm24 = byteArrayOf(
            0x00, 0x00, 0x00, // 0
            0xFF.toByte(), 0xFF.toByte(), 0x7F, // +8388607
            0x00, 0x00, 0x80.toByte(), // -8388608 (-1.0f exact)
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), // -1
            0x01, 0x00, 0x00, // +1 (LSB preserved!)
            0x00, 0x00, 0xC0.toByte(), // -4194304 (-0.5f exact)
            0x00, 0x00, 0x40, // +4194304 (+0.5f exact)
            0x40, 0xE2.toByte(), 0x01, // +123456
        )
        val wavBytes = buildStandardWav(96_000, 2, 24, pcm24)

        // 1. Direct 24-bit unpack helper accuracy test (all 24 bits exact in float32 mantissa)
        val floats = WavHeaderParser.unpackPcm24LeToFloat32(pcm24)
        assertEquals(8, floats.size)
        assertEquals(0.0f, floats[0], 0.0f)
        assertEquals(8_388_607.0f / 8_388_608.0f, floats[1], 0.0f)
        assertEquals(-1.0f, floats[2], 0.0f)
        assertEquals(-1.0f / 8_388_608.0f, floats[3], 0.0f)
        assertEquals(1.0f / 8_388_608.0f, floats[4], 0.0f)
        assertEquals(-0.5f, floats[5], 0.0f)
        assertEquals(0.5f, floats[6], 0.0f)
        assertEquals(123_456.0f / 8_388_608.0f, floats[7], 0.0f)

        // 2. API 31+ decoder outputs untouched packed 24-bit PCM bytes
        WavPcmDecoder(ByteArraySeekableByteSource(wavBytes), supportsNative24And32BitPcm = true).use { decoder ->
            assertEquals(PcmEncodingType.PCM_24BIT_PACKED, decoder.format.encoding)
            assertEquals(24, decoder.format.effectiveBitDepth)
            val out = ByteArray(24)
            assertEquals(24, decoder.readPcm(out))
            assertArrayEquals(pcm24, out)
            assertEquals(-1, decoder.readPcm(out))
        }

        // 3. API 26–30 decoder unpacks 24-bit integer PCM losslessly to float32 bytes (8 samples * 4 bytes = 32 bytes)
        WavPcmDecoder(ByteArraySeekableByteSource(wavBytes), supportsNative24And32BitPcm = false).use { decoder ->
            assertEquals(PcmEncodingType.PCM_FLOAT, decoder.format.encoding)
            assertEquals(24, decoder.format.effectiveBitDepth)
            val out = ByteArray(64)
            assertEquals(32, decoder.readPcm(out))
            val bb = ByteBuffer.wrap(out, 0, 32).order(ByteOrder.LITTLE_ENDIAN)
            for (expected in floats) {
                assertEquals(expected, bb.float, 0.0f)
            }
            assertEquals(-1, decoder.readPcm(out))
        }
    }

    @Test
    fun parseWavWithExtraOddLengthAndNonStandardRiffChunks_succeeds() {
        val pcm = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88.toByte())
        val wavBytes = buildStandardWav(
            sampleRate = 48_000,
            channels = 2,
            bitsPerSample = 16,
            pcmData = pcm,
            extraChunksBeforeFmt = listOf(
                "JUNK" to byteArrayOf(1, 2, 3), // Odd length -> requires +1 pad byte skip!
            ),
            extraChunksBeforeData = listOf(
                "LIST" to "INFOINAMTestTrack".toByteArray(Charsets.US_ASCII), // Odd length (17)
                "bext" to ByteArray(24) { 0x42 },
            ),
        )

        val header = WavHeaderParser.parse(ByteArraySeekableByteSource(wavBytes))
        assertEquals(48_000, header.sampleRateHz)
        assertEquals(16, header.bitsPerSample)
        assertEquals(8L, header.dataSizeBytes)

        WavPcmDecoder(ByteArraySeekableByteSource(wavBytes), supportsNative24And32BitPcm = true).use { decoder ->
            val out = ByteArray(8)
            assertEquals(8, decoder.readPcm(out))
            assertArrayEquals(pcm, out)
        }
    }

    @Test
    fun truncatedAndMalformedWavHeaders_throwTypedAudioDecodeException() {
        // Truncated (< 44 bytes)
        try {
            WavHeaderParser.parse(ByteArraySeekableByteSource(ByteArray(20)))
            fail("Expected CorruptedFile for truncated WAV")
        } catch (_: AudioDecodeException.CorruptedFile) {
            // Expected
        }

        // Wrong magic ("OggS" instead of "RIFF")
        val nonRiff = ByteArray(64).also {
            "OggS".toByteArray(Charsets.US_ASCII).copyInto(it, 0)
        }
        try {
            WavHeaderParser.parse(ByteArraySeekableByteSource(nonRiff))
            fail("Expected UnsupportedFormat for non-RIFF data")
        } catch (_: AudioDecodeException.UnsupportedFormat) {
            // Expected
        }

        // Unsupported compressed WAV format tag (e.g., IMA ADPCM = 0x0011)
        val adpcmWav = buildStandardWav(44_100, 2, 16, ByteArray(16), formatTag = 0x0011)
        try {
            WavHeaderParser.parse(ByteArraySeekableByteSource(adpcmWav))
            fail("Expected UnsupportedFormat for ADPCM WAV")
        } catch (_: AudioDecodeException.UnsupportedFormat) {
            // Expected
        }

        // Missing data chunk
        val missingData = ByteArrayOutputStream().apply {
            write("RIFF".toByteArray(Charsets.US_ASCII))
            write(le32(36))
            write("WAVE".toByteArray(Charsets.US_ASCII))
            write("fmt ".toByteArray(Charsets.US_ASCII))
            write(le32(16))
            write(le16(1))
            write(le16(2))
            write(le32(44_100))
            write(le32(176_400))
            write(le16(4))
            write(le16(16))
            write("JUNK".toByteArray(Charsets.US_ASCII))
            write(le32(8))
            write(ByteArray(8))
        }.toByteArray()
        try {
            WavHeaderParser.parse(ByteArraySeekableByteSource(missingData))
            fail("Expected CorruptedFile for missing data chunk")
        } catch (_: AudioDecodeException.CorruptedFile) {
            // Expected
        }
    }

    @Test
    fun flacStreamInfoParser_reads24Bit96kHzMetadataAccurately() {
        // Construct minimal valid fLaC + STREAMINFO header:
        // sampleRate = 96000 (0x17700), channels = 2 (stored as 1), bps = 24 (stored as 23 = 0x17), totalSamples = 192000 (2s)
        val header = ByteArray(42)
        "fLaC".toByteArray(Charsets.US_ASCII).copyInto(header, 0)
        header[4] = 0x80.toByte() // last-metadata-block=1, type=0 (STREAMINFO)
        header[5] = 0x00
        header[6] = 0x00
        header[7] = 34 // length = 34 bytes

        val sr = 96_000L
        val chMinus1 = 1L
        val bpsMinus1 = 23L
        val totalSamples = 192_000L

        header[18] = ((sr ushr 12) and 0xFF).toByte()
        header[19] = ((sr ushr 4) and 0xFF).toByte()
        header[20] = (((sr and 0x0F) shl 4) or (chMinus1 shl 1) or (bpsMinus1 ushr 4)).toByte()
        header[21] = (((bpsMinus1 and 0x0F) shl 4) or ((totalSamples ushr 32) and 0x0F)).toByte()
        header[22] = ((totalSamples ushr 24) and 0xFF).toByte()
        header[23] = ((totalSamples ushr 16) and 0xFF).toByte()
        header[24] = ((totalSamples ushr 8) and 0xFF).toByte()
        header[25] = (totalSamples and 0xFF).toByte()

        val info = FlacStreamInfoParser.parse(ByteArraySeekableByteSource(header))
        assertNotNull(info)
        assertEquals(96_000, info!!.sampleRateHz)
        assertEquals(2, info.channelCount)
        assertEquals(24, info.bitsPerSample)
        assertEquals(192_000L, info.totalSamples)
        assertEquals(2_000L, info.durationMs)
    }

    @Test
    fun audioFormatClassifier_acceptsOnly5MvpFormats_andRejectsExcludedFormats() {
        assertEquals(SupportedAudioFormat.FLAC, AudioFormatClassifier.classify("song.flac"))
        assertEquals(SupportedAudioFormat.WAV, AudioFormatClassifier.classify("master.WAV"))
        assertEquals(SupportedAudioFormat.MP3, AudioFormatClassifier.classify("track.mp3"))
        assertEquals(SupportedAudioFormat.M4A, AudioFormatClassifier.classify("album.m4a"))
        assertEquals(SupportedAudioFormat.OGG, AudioFormatClassifier.classify("podcast.ogg"))
        assertEquals(SupportedAudioFormat.OGG, AudioFormatClassifier.classify("voice.opus"))

        // Out-of-scope formats strictly rejected
        assertNull(AudioFormatClassifier.classify("dsd.dsf"))
        assertNull(AudioFormatClassifier.classify("dsd.dff"))
        assertNull(AudioFormatClassifier.classify("monkey.ape"))
        assertNull(AudioFormatClassifier.classify("pack.wv"))
        assertNull(AudioFormatClassifier.classify("apple.alac"))
        assertNull(AudioFormatClassifier.classify("disc.cue"))
        assertTrue(AudioFormatClassifier.isSupported("file.flac"))
        assertFalse(AudioFormatClassifier.isSupported("sheet.cue"))
    }
}
