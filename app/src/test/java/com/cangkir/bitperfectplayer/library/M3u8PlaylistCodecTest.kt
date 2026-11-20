package com.cangkir.bitperfectplayer.library

import com.cangkir.bitperfectplayer.audio.PlaybackTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class M3u8PlaylistCodecTest {

    private fun track(
        id: Int,
        title: String,
        artist: String = "Miles Davis",
        album: String = "Kind of Blue",
        fileName: String = "0$id - $title.flac",
        relativePath: String? = "Music/Jazz/",
        durationMs: Long = 240_000L,
        fingerprint: String? = null,
    ): PlaybackTrack = PlaybackTrack(
        uriString = "content://media/external/audio/media/$id",
        title = title,
        artist = artist,
        album = album,
        fileName = fileName,
        durationMs = durationMs,
        sampleRateHz = 96_000,
        bitDepth = 24,
        container = "FLAC",
        codec = "FLAC",
        contentFingerprint = fingerprint,
        relativePath = relativePath,
    )

    @Test
    fun exportAndParseRoundTrip_preservesPlaylistNameOrderAndMatchesBackToLibrary() {
        val fpA = "a".repeat(64)
        val fpB = "b".repeat(64)
        val t1 = track(1, "So What", fingerprint = fpA)
        val t2 = track(2, "Freddie Freeloader", fingerprint = fpB)
        val t3 = track(3, "Blue in Green", relativePath = null)

        val exported = M3u8PlaylistCodec.exportToM3u8("Late Night Hi-Res", listOf(t1, t2, t3))
        assertTrue(exported.startsWith("#EXTM3U"))
        assertTrue(exported.contains("#PLAYLIST:Late Night Hi-Res"))
        assertTrue(exported.contains("Music/Jazz/01 - So What.flac"))

        val parsed = M3u8PlaylistCodec.parseM3u8("\uFEFF$exported")
        assertEquals("Late Night Hi-Res", parsed.titleHint)
        assertEquals(3, parsed.entries.size)
        assertEquals(fpA, parsed.entries[0].hintFingerprint)

        // Simulate t1 being moved & renamed externally — fingerprint hint still resolves it!
        val movedT1 = t1.copy(
            uriString = "content://media/external/audio/media/999",
            fileName = "renamed_so_what.flac",
            relativePath = "Music/Archive/",
        )
        val match = M3u8PlaylistCodec.matchEntriesToLibrary(parsed.entries, listOf(movedT1, t2, t3))
        assertEquals(3, match.matchedTracks.size)
        assertTrue(match.unmatchedLocations.isEmpty())
        assertEquals(movedT1.uriString, match.matchedTracks[0].uriString)
        assertEquals(t2.uriString, match.matchedTracks[1].uriString)
        assertEquals(t3.uriString, match.matchedTracks[2].uriString)
    }

    @Test
    fun parseExternalM3u8_matchesByRelativePathWindowsBackslashesUrlEncodingAndFilename() {
        val t1 = track(10, "Autumn Leaves", fileName = "01 Autumn Leaves.flac", relativePath = "Music/Jazz/Classic/")
        val t2 = track(11, "Take Five", fileName = "Take Five.wav", relativePath = "Music/Brubeck/")
        val externalM3u8 = """
            #EXTM3U
            #EXTINF:312,Cannonball Adderley - Autumn Leaves
            /storage/emulated/0/Music/Jazz/Classic/01%20Autumn%20Leaves.flac
            #EXTINF:324,Dave Brubeck - Take Five
            Music\Brubeck\Take Five.wav
            /storage/emulated/0/Music/Missing/Deleted Track.mp3
        """.trimIndent()

        val parsed = M3u8PlaylistCodec.parseM3u8(externalM3u8)
        assertEquals(3, parsed.entries.size)

        val matched = M3u8PlaylistCodec.matchEntriesToLibrary(parsed.entries, listOf(t1, t2))
        assertEquals(listOf(t1.uriString, t2.uriString), matched.matchedTracks.map { it.uriString })
        assertEquals(listOf("/storage/emulated/0/Music/Missing/Deleted Track.mp3"), matched.unmatchedLocations)
    }

    @Test
    fun reorderList_movesEntriesDeterministicallyAndIgnoresOutOfBounds() {
        val ids = listOf(10L, 20L, 30L, 40L)
        assertEquals(listOf(20L, 10L, 30L, 40L), M3u8PlaylistCodec.reorderList(ids, 1, 0))
        assertEquals(listOf(10L, 30L, 40L, 20L), M3u8PlaylistCodec.reorderList(ids, 1, 3))
        assertEquals(ids, M3u8PlaylistCodec.reorderList(ids, 2, 2))
        assertEquals(ids, M3u8PlaylistCodec.reorderList(ids, -1, 2))
        assertEquals(ids, M3u8PlaylistCodec.reorderList(ids, 0, 99))
    }

    @Test
    fun suggestExportFileName_sanitizesIllegalCharacters() {
        assertEquals("Late_Night_Jazz.m3u8", M3u8PlaylistCodec.suggestExportFileName("Late/Night:Jazz"))
        assertEquals("already.m3u8", M3u8PlaylistCodec.suggestExportFileName("already.m3u8"))
        assertEquals("playlist.m3u8", M3u8PlaylistCodec.suggestExportFileName("   "))
    }
}
