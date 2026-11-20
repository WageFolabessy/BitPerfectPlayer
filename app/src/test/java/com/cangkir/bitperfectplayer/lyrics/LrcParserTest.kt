package com.cangkir.bitperfectplayer.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserTest {

    @Test
    fun parseStandardCentisecondAndMillisecondTimestamps_andMetadata() {
        val lrc = """
            [ti:Midnight Pulse]
            [ar:Acoustic Ensemble]
            [al:Studio Sessions]
            [00:05.50]First line centiseconds
            [01:02.345]Second line milliseconds
            [02:15.5]Third line tenths
            [03:00]Fourth line whole seconds
        """.trimIndent()

        val parsed = LrcParser.parse(lrc)

        assertTrue(parsed.isSynced)
        assertEquals("Midnight Pulse", parsed.metadata.title)
        assertEquals("Acoustic Ensemble", parsed.metadata.artist)
        assertEquals("Studio Sessions", parsed.metadata.album)
        assertEquals(0L, parsed.metadata.offsetMs)
        assertEquals(4, parsed.lines.size)
        assertEquals(LrcLine(5_500L, "First line centiseconds"), parsed.lines[0])
        assertEquals(LrcLine(62_345L, "Second line milliseconds"), parsed.lines[1])
        assertEquals(LrcLine(135_500L, "Third line tenths"), parsed.lines[2])
        assertEquals(LrcLine(180_000L, "Fourth line whole seconds"), parsed.lines[3])
    }

    @Test
    fun parseMultiTimestampLine_expandsAndSortsChronologically() {
        val lrc = """
            [00:10.00][01:20.50]Chorus repeats
            [00:30.25]Verse one
            [02:00.00][00:05.00]Intro and outro
        """.trimIndent()

        val parsed = LrcParser.parse(lrc)

        assertTrue(parsed.isSynced)
        assertEquals(5, parsed.lines.size)
        assertEquals(
            listOf(
                LrcLine(5_000L, "Intro and outro"),
                LrcLine(10_000L, "Chorus repeats"),
                LrcLine(30_250L, "Verse one"),
                LrcLine(80_500L, "Chorus repeats"),
                LrcLine(120_000L, "Intro and outro"),
            ),
            parsed.lines,
        )
    }

    @Test
    fun parseOffsetTag_positiveShiftsEarlierClampedAtZero_negativeDelays() {
        val positiveOffsetLrc = """
            [offset:+500]
            [00:00.30]Clamped line
            [00:10.00]Shifted earlier
        """.trimIndent()

        val parsedPos = LrcParser.parse(positiveOffsetLrc)
        assertEquals(500L, parsedPos.metadata.offsetMs)
        assertEquals(0L, parsedPos.lines[0].timestampMs)
        assertEquals(9_500L, parsedPos.lines[1].timestampMs)

        val negativeOffsetLrc = """
            [offset:-750]
            [00:10.00]Delayed line
        """.trimIndent()

        val parsedNeg = LrcParser.parse(negativeOffsetLrc)
        assertEquals(-750L, parsedNeg.metadata.offsetMs)
        assertEquals(10_750L, parsedNeg.lines[0].timestampMs)
    }

    @Test
    fun decodeBytes_supportsUtf8_Utf8Bom_Utf16Le_Utf16Be_andIso88591Fallback() {
        val sample = "[00:01.00]Café — 東京"

        // Plain UTF-8
        val utf8Parsed = LrcParser.parseBytes(sample.toByteArray(Charsets.UTF_8))
        assertTrue(utf8Parsed.isSynced)
        assertEquals("Café — 東京", utf8Parsed.lines.single().text)

        // UTF-8 with BOM
        val utf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + sample.toByteArray(Charsets.UTF_8)
        val utf8BomParsed = LrcParser.parseBytes(utf8Bom)
        assertTrue(utf8BomParsed.isSynced)
        assertEquals("Café — 東京", utf8BomParsed.lines.single().text)

        // UTF-16LE with BOM
        val utf16Le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + sample.toByteArray(Charsets.UTF_16LE)
        val utf16LeParsed = LrcParser.parseBytes(utf16Le)
        assertTrue(utf16LeParsed.isSynced)
        assertEquals("Café — 東京", utf16LeParsed.lines.single().text)

        // UTF-16BE with BOM
        val utf16Be = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + sample.toByteArray(Charsets.UTF_16BE)
        val utf16BeParsed = LrcParser.parseBytes(utf16Be)
        assertTrue(utf16BeParsed.isSynced)
        assertEquals("Café — 東京", utf16BeParsed.lines.single().text)

        // Invalid UTF-8 bytes valid in ISO-8859-1 ("Café naïve")
        val latin1Text = "[00:02.50]Café naïve"
        val latin1Bytes = latin1Text.toByteArray(Charsets.ISO_8859_1)
        val latin1Parsed = LrcParser.parseBytes(latin1Bytes)
        assertTrue(latin1Parsed.isSynced)
        assertEquals("Café naïve", latin1Parsed.lines.single().text)
    }

    @Test
    fun malformedTimestampsAndCorruptedInput_neverCrash() {
        val messy = """
            [offset:not_a_number]
            [99:99.99]Invalid seconds
            [ab:cd.ef]Garbage timestamp
            [unclosed bracket line
            [00:12.34]Valid line survives
            []Empty brackets
            
        """.trimIndent()

        val parsed = LrcParser.parse(messy)
        assertTrue(parsed.isSynced)
        assertEquals(0L, parsed.metadata.offsetMs)
        assertEquals(1, parsed.lines.size)
        assertEquals(LrcLine(12_340L, "Valid line survives"), parsed.lines.single())
    }

    @Test
    fun plainUntimestampedLyrics_returnsStaticScrollableLines() {
        val plain = """
            Line one of static lyrics
            Line two of static lyrics

            Second stanza
        """.trimIndent()

        val parsed = LrcParser.parse(plain)
        assertFalse(parsed.isSynced)
        assertTrue(parsed.lines.isEmpty())
        assertEquals(
            listOf("Line one of static lyrics", "Line two of static lyrics", "", "Second stanza"),
            parsed.plainLines,
        )
        assertNull(parsed.metadata.title)
    }

    @Test
    fun emptyOrWhitespaceInput_returnsEmptyNonSyncedLyrics() {
        val emptyParsed = LrcParser.parse("   \r\n  \n ")
        assertFalse(emptyParsed.isSynced)
        assertTrue(emptyParsed.lines.isEmpty())
        assertTrue(emptyParsed.plainLines.isEmpty())
        assertEquals(-1, LrcParser.findActiveLineIndex(emptyParsed.lines, 0L))
    }

    @Test
    fun findActiveLineIndex_handlesPreStart_exactBoundaries_forwardAndBackwardSeeks() {
        val lines = listOf(
            LrcLine(5_000L, "Line 0"),
            LrcLine(10_000L, "Line 1"),
            LrcLine(10_000L, "Line 2 same timestamp"),
            LrcLine(25_500L, "Line 3"),
            LrcLine(40_000L, "Line 4"),
        )

        // Before first timestamp & negative clock
        assertEquals(-1, LrcParser.findActiveLineIndex(lines, -100L))
        assertEquals(-1, LrcParser.findActiveLineIndex(lines, 0L))
        assertEquals(-1, LrcParser.findActiveLineIndex(lines, 4_999L))

        // Exact boundary and mid-line
        assertEquals(0, LrcParser.findActiveLineIndex(lines, 5_000L))
        assertEquals(0, LrcParser.findActiveLineIndex(lines, 9_999L))

        // Duplicate timestamp selects latest matching line deterministically
        assertEquals(2, LrcParser.findActiveLineIndex(lines, 10_000L))
        assertEquals(2, LrcParser.findActiveLineIndex(lines, 25_499L))

        // Forward seek to end and past end
        assertEquals(3, LrcParser.findActiveLineIndex(lines, 25_500L))
        assertEquals(4, LrcParser.findActiveLineIndex(lines, 40_000L))
        assertEquals(4, LrcParser.findActiveLineIndex(lines, 999_999L))

        // Backward seek back to earlier lines and before start
        assertEquals(0, LrcParser.findActiveLineIndex(lines, 6_000L))
        assertEquals(-1, LrcParser.findActiveLineIndex(lines, 2_000L))
    }
}
