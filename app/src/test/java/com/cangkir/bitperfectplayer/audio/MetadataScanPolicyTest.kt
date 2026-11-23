package com.cangkir.bitperfectplayer.audio

import com.cangkir.bitperfectplayer.lyrics.ByteArraySeekableByteSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataScanPolicyTest {

    @Test
    fun sanitizeCursorTag_rejectsBlankAndUnknownPlaceholder_andCleansControlChars() {
        assertNull(MetadataScanPolicy.sanitizeCursorTag(null))
        assertNull(MetadataScanPolicy.sanitizeCursorTag("   "))
        assertNull(MetadataScanPolicy.sanitizeCursorTag("<unknown>"))
        assertNull(MetadataScanPolicy.sanitizeCursorTag("<UNKNOWN>"))
        assertNull(MetadataScanPolicy.sanitizeCursorTag("\u0000\t\n "))
        assertEquals("So What", MetadataScanPolicy.sanitizeCursorTag("  So   \t\n What\u0007  "))
    }

    @Test
    fun canSkipMediaMetadataRetriever_skipsOnlyWhenCursorAndDirectHeaderAreComplete() {
        // MediaStore FLAC/WAV with cursor tags + direct header sampleRate & duration -> SKIP MediaMetadataRetriever
        assertTrue(
            MetadataScanPolicy.canSkipMediaMetadataRetriever(
                hasCursorMetadata = true,
                sampleRateHz = 96_000,
                durationMs = 351_000L,
            ),
        )
        // Missing sampleRate (e.g., lossy MP3/M4A/OGG before retriever) -> do NOT skip
        assertFalse(
            MetadataScanPolicy.canSkipMediaMetadataRetriever(
                hasCursorMetadata = true,
                sampleRateHz = 0,
                durationMs = 210_000L,
            ),
        )
        // Missing duration -> do NOT skip
        assertFalse(
            MetadataScanPolicy.canSkipMediaMetadataRetriever(
                hasCursorMetadata = true,
                sampleRateHz = 44_100,
                durationMs = 0L,
            ),
        )
        // SAF files (no MediaStore cursor metadata) -> do NOT skip
        assertFalse(
            MetadataScanPolicy.canSkipMediaMetadataRetriever(
                hasCursorMetadata = false,
                sampleRateHz = 96_000,
                durationMs = 351_000L,
            ),
        )
    }

    @Test
    fun flacDirectHeaderParser_extracts24Bit96000HzAndDurationEnablingFastMediaStoreScan() {
        val sampleRate = 96_000
        val channels = 2
        val bitsPerSample = 24
        val totalSamples = 96_000L * 240L // 4 minutes exact

        val header = buildSyntheticFlacHeader(
            sampleRate = sampleRate,
            channels = channels,
            bitsPerSample = bitsPerSample,
            totalSamples = totalSamples,
        )
        val info = FlacStreamInfoParser.parse(ByteArraySeekableByteSource(header))
        assertNotNull(info)
        assertEquals(96_000, info!!.sampleRateHz)
        assertEquals(2, info.channelCount)
        assertEquals(24, info.bitsPerSample)
        assertEquals(240_000L, info.durationMs)
        assertTrue(
            MetadataScanPolicy.canSkipMediaMetadataRetriever(
                hasCursorMetadata = true,
                sampleRateHz = info.sampleRateHz,
                durationMs = info.durationMs,
            ),
        )
    }

    private fun buildSyntheticFlacHeader(sampleRate: Int, channels: Int, bitsPerSample: Int, totalSamples: Long): ByteArray {
        val bytes = ByteArray(42)
        // "fLaC" magic
        bytes[0] = 'f'.code.toByte()
        bytes[1] = 'L'.code.toByte()
        bytes[2] = 'a'.code.toByte()
        bytes[3] = 'C'.code.toByte()
        // Block 0: last-metadata-block=1 (0x80) | STREAMINFO type 0, length = 34
        bytes[4] = 0x80.toByte()
        bytes[5] = 0
        bytes[6] = 0
        bytes[7] = 34

        // Pack 64-bit STREAMINFO field at bytes[18..25]:
        // [sampleRate:20][channels-1:3][bps-1:5][totalSamples:36]
        val packed = (sampleRate.toLong() shl 44) or
            (((channels - 1).toLong() and 0x7L) shl 41) or
            (((bitsPerSample - 1).toLong() and 0x1FL) shl 36) or
            (totalSamples and 0xFFFFFFFFFL)
        for (i in 0 until 8) {
            bytes[18 + i] = ((packed ushr ((7 - i) * 8)) and 0xFFL).toByte()
        }
        return bytes
    }
}
