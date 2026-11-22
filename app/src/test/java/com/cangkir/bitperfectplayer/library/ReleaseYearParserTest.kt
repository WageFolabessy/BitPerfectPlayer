package com.cangkir.bitperfectplayer.library

import com.cangkir.bitperfectplayer.lyrics.ByteArraySeekableByteSource
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseYearParserTest {

    @Test
    fun parse_handlesPlainYearsIsoSlashDotPrefixesAndCompactDates() {
        assertEquals(2007, ReleaseYearParser.parse("2007"))
        assertEquals(2007, ReleaseYearParser.parse("  2007  "))
        assertEquals(2007, ReleaseYearParser.parse("2007-10-30"))
        assertEquals(2007, ReleaseYearParser.parse("2007-10-30T08:15:00Z"))
        assertEquals(1967, ReleaseYearParser.parse("1967/06/01"))
        assertEquals(1991, ReleaseYearParser.parse("1991.09.24"))
        assertEquals(1994, ReleaseYearParser.parse("1994; 2009"))
        assertEquals(2003, ReleaseYearParser.parse("2003 (Deluxe Edition)"))
        assertEquals(2007, ReleaseYearParser.parse("20071030"))
        assertEquals(1900, ReleaseYearParser.parse("1900"))
        assertEquals(2100, ReleaseYearParser.parse("2100"))
    }

    @Test
    fun parse_rejectsOutOfRangeMalformedAndInvalidValues() {
        assertEquals(0, ReleaseYearParser.parse(null))
        assertEquals(0, ReleaseYearParser.parse(""))
        assertEquals(0, ReleaseYearParser.parse("   "))
        assertEquals(0, ReleaseYearParser.parse("0000"))
        assertEquals(0, ReleaseYearParser.parse("1899"))
        assertEquals(0, ReleaseYearParser.parse("2101"))
        assertEquals(0, ReleaseYearParser.parse("99"))
        assertEquals(0, ReleaseYearParser.parse("123456"))
        assertEquals(0, ReleaseYearParser.parse("99200799"))
        assertEquals(0, ReleaseYearParser.parse("20079999"))
        assertEquals(0, ReleaseYearParser.parse("unknown"))
    }

    @Test
    fun resolveAlbumReleaseYear_picksMostFrequentValidYearWithEarliestTieBreaker() {
        // Single untagged track must never zero out an album
        assertEquals(2007, ReleaseYearParser.resolveAlbumReleaseYear(listOf(0, 2007, 2007, 0)))
        // Most frequent valid year wins over remaster outlier
        assertEquals(2007, ReleaseYearParser.resolveAlbumReleaseYear(listOf(2020, 2007, 2007, 0)))
        // Tie-breaker picks earliest valid year
        assertEquals(2003, ReleaseYearParser.resolveAlbumReleaseYear(listOf(2010, 2003)))
        // All invalid/out-of-range -> 0
        assertEquals(0, ReleaseYearParser.resolveAlbumReleaseYear(listOf(0, 1850, 2500)))
    }

    @Test
    fun extractYearFromHeader_readsId3v23TyerId3v24TdrcTdorAndFlacVorbisComment() {
        val id3v23Tyer = buildId3v2(majorVersion = 3, frames = listOf("TYER" to id3TextPayload(0, "1994")))
        assertEquals(1994, ReleaseYearParser.extractYearFromHeader(ByteArraySeekableByteSource(id3v23Tyer)))

        val id3v24TdrcAndTyer = buildId3v2(
            majorVersion = 4,
            frames = listOf(
                "TYER" to id3TextPayload(3, "1999"),
                "TDRC" to id3TextPayload(3, "2007-10-30"),
            ),
        )
        // TDRC takes priority over TYER
        assertEquals(2007, ReleaseYearParser.extractYearFromHeader(ByteArraySeekableByteSource(id3v24TdrcAndTyer)))

        // UTF-16 LE with BOM inside TDRC
        val utf16Payload = ByteArrayOutputStream().apply {
            write(1) // UTF-16 with BOM
            write(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))
            write("2013-05-21".toByteArray(Charsets.UTF_16LE))
        }.toByteArray()
        val id3Utf16 = buildId3v2(majorVersion = 4, frames = listOf("TDRC" to utf16Payload))
        assertEquals(2013, ReleaseYearParser.extractYearFromHeader(ByteArraySeekableByteSource(id3Utf16)))

        // TDOR fallback when TDRC & TYER absent
        val id3Tdor = buildId3v2(majorVersion = 4, frames = listOf("TDOR" to id3TextPayload(3, "1973-03-01")))
        assertEquals(1973, ReleaseYearParser.extractYearFromHeader(ByteArraySeekableByteSource(id3Tdor)))

        // FLAC VORBIS_COMMENT DATE tag
        val flacBytes = buildMinimalFlacWithVorbisComments(listOf("TITLE=Track", "DATE=2005-09-12"))
        assertEquals(2005, ReleaseYearParser.extractYearFromHeader(ByteArraySeekableByteSource(flacBytes)))

        // ID3v2-prefixed FLAC falling back to FLAC VORBIS_COMMENT when ID3 has no year
        val id3NoYear = buildId3v2(majorVersion = 3, frames = listOf("TIT2" to id3TextPayload(0, "Song")))
        val prefixedFlac = id3NoYear + flacBytes
        assertEquals(2005, ReleaseYearParser.extractYearFromHeader(ByteArraySeekableByteSource(prefixedFlac)))
    }

    private fun id3TextPayload(encoding: Int, text: String): ByteArray {
        val charset = if (encoding == 3) Charsets.UTF_8 else Charsets.ISO_8859_1
        return byteArrayOf(encoding.toByte()) + text.toByteArray(charset)
    }

    private fun buildId3v2(majorVersion: Int, frames: List<Pair<String, ByteArray>>): ByteArray {
        val body = ByteArrayOutputStream()
        for ((frameId, payload) in frames) {
            body.write(frameId.toByteArray(Charsets.ISO_8859_1))
            if (majorVersion == 4) {
                body.write(encodeSynchsafeInt32(payload.size))
            } else {
                body.write(encodeRegularInt32Be(payload.size))
            }
            body.write(byteArrayOf(0, 0)) // flags
            body.write(payload)
        }
        val bodyBytes = body.toByteArray()
        return ByteArrayOutputStream().apply {
            write("ID3".toByteArray(Charsets.ISO_8859_1))
            write(majorVersion)
            write(0) // minor
            write(0) // flags
            write(encodeSynchsafeInt32(bodyBytes.size))
            write(bodyBytes)
        }.toByteArray()
    }

    private fun buildMinimalFlacWithVorbisComments(comments: List<String>): ByteArray {
        val streamInfo = ByteArray(34).apply {
            // 44100 Hz (20b) = 0x0AC44, channels-1 = 1 (stereo), bps-1 = 15 (16-bit), totalSamples = 44100
            this[10] = 0x0A
            this[11] = 0xC4.toByte()
            this[12] = 0x42
            this[13] = 0xF0.toByte()
            this[16] = 0xAC.toByte()
            this[17] = 0x44
        }
        val vorbis = ByteArrayOutputStream().apply {
            val vendor = "test".toByteArray(Charsets.UTF_8)
            write(encodeInt32Le(vendor.size))
            write(vendor)
            write(encodeInt32Le(comments.size))
            for (c in comments) {
                val b = c.toByteArray(Charsets.UTF_8)
                write(encodeInt32Le(b.size))
                write(b)
            }
        }.toByteArray()

        return ByteArrayOutputStream().apply {
            write("fLaC".toByteArray(Charsets.ISO_8859_1))
            // Block 0: STREAMINFO (not last -> 0x00)
            write(0x00)
            write(encodeUint24Be(streamInfo.size))
            write(streamInfo)
            // Block 4: VORBIS_COMMENT (last -> 0x84)
            write(0x84)
            write(encodeUint24Be(vorbis.size))
            write(vorbis)
        }.toByteArray()
    }

    private fun encodeRegularInt32Be(v: Int): ByteArray = byteArrayOf(
        ((v ushr 24) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        (v and 0xFF).toByte(),
    )

    private fun encodeSynchsafeInt32(v: Int): ByteArray = byteArrayOf(
        ((v ushr 21) and 0x7F).toByte(),
        ((v ushr 14) and 0x7F).toByte(),
        ((v ushr 7) and 0x7F).toByte(),
        (v and 0x7F).toByte(),
    )

    private fun encodeUint24Be(v: Int): ByteArray = byteArrayOf(
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        (v and 0xFF).toByte(),
    )

    private fun encodeInt32Le(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 24) and 0xFF).toByte(),
    )
}
