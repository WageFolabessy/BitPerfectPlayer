package com.cangkir.bitperfectplayer.audio

import com.cangkir.bitperfectplayer.lyrics.ByteArraySeekableByteSource
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class TrackNumberParserTest {

    @Test
    fun parseMediaStorePackedTrack_unpacksDiscAndTrackProperly() {
        assertEquals(ParsedTrackPosition(discNumber = 1, trackNumber = 3), TrackNumberParser.parseMediaStorePackedTrack(1003))
        assertEquals(ParsedTrackPosition(discNumber = 2, trackNumber = 4), TrackNumberParser.parseMediaStorePackedTrack(2004))
        assertEquals(ParsedTrackPosition(discNumber = 0, trackNumber = 7), TrackNumberParser.parseMediaStorePackedTrack(7))
        assertEquals(ParsedTrackPosition(discNumber = 1, trackNumber = 18), TrackNumberParser.parseMediaStorePackedTrack(1018))
        assertEquals(ParsedTrackPosition(discNumber = 0, trackNumber = 0), TrackNumberParser.parseMediaStorePackedTrack(0))
        assertEquals(ParsedTrackPosition(discNumber = 0, trackNumber = 0), TrackNumberParser.parseMediaStorePackedTrack(-5))
    }

    @Test
    fun parseTagNumber_handlesFractionsLeadingZerosAndMalformedTextSafely() {
        assertEquals(3, TrackNumberParser.parseTagNumber("3/12"))
        assertEquals(3, TrackNumberParser.parseTagNumber("03"))
        assertEquals(1, TrackNumberParser.parseTagNumber(" 1 / 2 "))
        assertEquals(2, TrackNumberParser.parseTagNumber("2 of 3"))
        assertEquals(14, TrackNumberParser.parseTagNumber("14"))

        // Malformed / invalid inputs never crash and return 0
        assertEquals(0, TrackNumberParser.parseTagNumber(null))
        assertEquals(0, TrackNumberParser.parseTagNumber(""))
        assertEquals(0, TrackNumberParser.parseTagNumber("   "))
        assertEquals(0, TrackNumberParser.parseTagNumber("abc"))
        assertEquals(0, TrackNumberParser.parseTagNumber("-4"))
        assertEquals(0, TrackNumberParser.parseTagNumber("/12"))
        assertEquals(0, TrackNumberParser.parseTagNumber("21st"))
        assertEquals(0, TrackNumberParser.parseTagNumber("9999999999999999999"))
    }

    @Test
    fun parseFilenamePosition_extractsLeadingNumbersAndRejectsYearsOrOrdinals() {
        assertEquals(ParsedTrackPosition(0, 4), TrackNumberParser.parseFilenamePosition("04 - Acid Rain.flac"))
        assertEquals(ParsedTrackPosition(0, 1), TrackNumberParser.parseFilenamePosition("01. Intro.wav"))
        assertEquals(ParsedTrackPosition(0, 12), TrackNumberParser.parseFilenamePosition("12_Finale.mp3"))
        assertEquals(ParsedTrackPosition(1, 4), TrackNumberParser.parseFilenamePosition("1-04 - Know Your Enemy.flac"))
        assertEquals(ParsedTrackPosition(2, 9), TrackNumberParser.parseFilenamePosition("02-09 Encore.m4a"))

        // Ordinals & 4-digit years must NOT be misclassified as track numbers
        assertEquals(ParsedTrackPosition(0, 0), TrackNumberParser.parseFilenamePosition("21st Century Breakdown.flac"))
        assertEquals(ParsedTrackPosition(0, 0), TrackNumberParser.parseFilenamePosition("2024 - Live Session.flac"))
        assertEquals(ParsedTrackPosition(0, 0), TrackNumberParser.parseFilenamePosition("1999_Remaster.wav"))
        assertEquals(ParsedTrackPosition(0, 0), TrackNumberParser.parseFilenamePosition("Bohemian Rhapsody.flac"))
    }

    @Test
    fun resolve_respectsStrictPrecedenceAndUsesFilenameFallbackOnlyWhenMissing() {
        // 1. Explicit tags override packed & filename
        assertEquals(
            ParsedTrackPosition(discNumber = 2, trackNumber = 5),
            TrackNumberParser.resolve(
                mediaStorePackedTrack = 1001,
                trackNumberTag = "5/10",
                discNumberTag = "2/2",
                fileName = "09 - Ignored.flac",
            ),
        )

        // 2. MediaStore packed wins over filename fallback
        assertEquals(
            ParsedTrackPosition(discNumber = 1, trackNumber = 3),
            TrackNumberParser.resolve(
                mediaStorePackedTrack = 1003,
                fileName = "09 - Ignored.flac",
            ),
        )

        // 3. Header FLAC Vorbis tags win over filename fallback
        assertEquals(
            ParsedTrackPosition(discNumber = 1, trackNumber = 8),
            TrackNumberParser.resolve(
                headerDiscNumber = 1,
                headerTrackNumber = 8,
                fileName = "02 - Ignored.flac",
            ),
        )

        // 4. Filename fallback activates ONLY when MediaStore & tags are both missing
        assertEquals(
            ParsedTrackPosition(discNumber = 0, trackNumber = 4),
            TrackNumberParser.resolve(
                mediaStorePackedTrack = 0,
                trackNumberTag = null,
                discNumberTag = null,
                fileName = "04 - Acid Rain.flac",
            ),
        )
    }

    @Test
    fun flacStreamInfoParser_readsVorbisCommentTrackAndDiscNumbersSkippingPictureBlocks() {
        val flacBytes = buildSyntheticFlacWithVorbisComments(
            sampleRateHz = 96_000,
            channels = 2,
            bitsPerSample = 24,
            totalSamples = 96_000L * 180L,
            comments = listOf(
                "TITLE=Peacemaker",
                "TRACKNUMBER=7/18",
                "DISCNUMBER=2/2",
            ),
            includeDummyPictureBlockBeforeComments = true,
        )

        val info = FlacStreamInfoParser.parse(ByteArraySeekableByteSource(flacBytes))
        assertNotNull(info)
        assertEquals(96_000, info!!.sampleRateHz)
        assertEquals(2, info.channelCount)
        assertEquals(24, info.bitsPerSample)
        assertEquals(180_000L, info.durationMs)
        assertEquals(2, info.discNumber)
        assertEquals(7, info.trackNumber)
    }

    private fun buildSyntheticFlacWithVorbisComments(
        sampleRateHz: Int,
        channels: Int,
        bitsPerSample: Int,
        totalSamples: Long,
        comments: List<String>,
        includeDummyPictureBlockBeforeComments: Boolean,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray(Charsets.US_ASCII))

        // Block 0: STREAMINFO (not last -> bit 7 = 0, type = 0, length = 34)
        out.write(0x00)
        out.write(0x00)
        out.write(0x00)
        out.write(34)
        val streamInfo = ByteArray(34)
        val sr = sampleRateHz.toLong()
        val chMinus1 = (channels - 1).toLong() and 0x07L
        val bpsMinus1 = (bitsPerSample - 1).toLong() and 0x1FL
        streamInfo[10] = ((sr ushr 12) and 0xFFL).toByte()
        streamInfo[11] = ((sr ushr 4) and 0xFFL).toByte()
        streamInfo[12] = (((sr and 0x0FL) shl 4) or (chMinus1 shl 1) or ((bpsMinus1 ushr 4) and 0x01L)).toByte()
        streamInfo[13] = (((bpsMinus1 and 0x0FL) shl 4) or ((totalSamples ushr 32) and 0x0FL)).toByte()
        streamInfo[14] = ((totalSamples ushr 24) and 0xFFL).toByte()
        streamInfo[15] = ((totalSamples ushr 16) and 0xFFL).toByte()
        streamInfo[16] = ((totalSamples ushr 8) and 0xFFL).toByte()
        streamInfo[17] = (totalSamples and 0xFFL).toByte()
        out.write(streamInfo)

        if (includeDummyPictureBlockBeforeComments) {
            // Block type 6 (PICTURE), not last, 64 dummy bytes
            out.write(0x06)
            out.write(0x00)
            out.write(0x00)
            out.write(64)
            out.write(ByteArray(64))
        }

        // Block type 4 (VORBIS_COMMENT), marked as last (0x84)
        val vc = ByteArrayOutputStream()
        val vendor = "BitPerfectTest".toByteArray(Charsets.UTF_8)
        writeInt32Le(vc, vendor.size)
        vc.write(vendor)
        writeInt32Le(vc, comments.size)
        for (c in comments) {
            val bytes = c.toByteArray(Charsets.UTF_8)
            writeInt32Le(vc, bytes.size)
            vc.write(bytes)
        }
        val vcBytes = vc.toByteArray()
        out.write(0x84)
        out.write((vcBytes.size ushr 16) and 0xFF)
        out.write((vcBytes.size ushr 8) and 0xFF)
        out.write(vcBytes.size and 0xFF)
        out.write(vcBytes)

        return out.toByteArray()
    }

    private fun writeInt32Le(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 24) and 0xFF)
    }
}
