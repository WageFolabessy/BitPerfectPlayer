package com.cangkir.bitperfectplayer.lyrics

import com.cangkir.bitperfectplayer.audio.PlaybackTrack
import com.cangkir.bitperfectplayer.data.LyricsRecord
import com.cangkir.bitperfectplayer.data.LyricsSourceType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsRepositoryTest {

    private val fpA = "a".repeat(64)

    private fun sampleTrack(fileName: String = "01. Midnight (Live).flac") = PlaybackTrack(
        uriString = "content://media/audio/1",
        title = "Midnight (Live)",
        artist = "Artist",
        album = "Album",
        fileName = fileName,
        fileSizeBytes = 250_000L,
        durationMs = 180_000L,
        container = "FLAC",
    )

    @Test
    fun lrcFileMatcher_matchesIdenticalBaseFilenameCaseInsensitively() {
        assertEquals("01. Midnight (Live).lrc", LrcFileMatcher.expectedLrcFileName("01. Midnight (Live).flac"))
        assertTrue(LrcFileMatcher.isMatchingSidecarLrc("01. Midnight (Live).flac", "01. Midnight (Live).lrc"))
        assertTrue(LrcFileMatcher.isMatchingSidecarLrc("Song.WAV", "song.LRC"))
        assertFalse(LrcFileMatcher.isMatchingSidecarLrc("Song.wav", "Song2.lrc"))
        assertFalse(LrcFileMatcher.isMatchingSidecarLrc("Song.wav", "Song.txt"))
        assertFalse(LrcFileMatcher.isMatchingSidecarLrc("Song.wav", null))
    }

    @Test
    fun lyricsCoordinator_autoDetectsPersistsEditsAndDeletesByContentFingerprint() = runTest {
        val db = mutableMapOf<String, LyricsRecord>()
        var autoDetectCalls = 0
        val sampleLrcBytes = "[00:05.00]Auto detected line\n[00:10.00]Second line".toByteArray(Charsets.UTF_8)

        val coordinator = LyricsCoordinator(
            loadStoredLyrics = { fp -> db[fp] },
            upsertStoredLyrics = { rec -> db[rec.contentFingerprint] = rec },
            deleteStoredLyrics = { fp -> db.remove(fp) != null },
            resolveFingerprint = { fpA },
            findAutoDetectLrcBytes = {
                autoDetectCalls++
                sampleLrcBytes
            },
            clockEpochMs = { 1_700_000_000_000L },
        )

        // 1. First load auto-detects sibling .lrc and persists it under SHA-256 fingerprint
        val firstLoad = coordinator.loadLyricsForTrack(sampleTrack())
        assertNotNull(firstLoad)
        assertEquals(fpA, firstLoad!!.contentFingerprint)
        assertTrue(firstLoad.hasPersistedLyrics)
        assertTrue(firstLoad.parsedLyrics.isSynced)
        assertEquals(LyricsSourceType.LRC_FILE, firstLoad.sourceType)
        assertEquals(1, autoDetectCalls)
        assertTrue(db.containsKey(fpA))

        // 2. Second load (even after track file was renamed!) hits persisted DB directly without re-scanning
        val renamedTrack = sampleTrack(fileName = "totally_different_name.flac")
        val secondLoad = coordinator.loadLyricsForTrack(renamedTrack)
        assertNotNull(secondLoad)
        assertEquals("Auto detected line", secondLoad!!.parsedLyrics.lines.first().text)
        assertEquals(1, autoDetectCalls)

        // 3. Manual edit replaces persisted lyrics with plain untimestamped text
        val edited = coordinator.saveManualLyrics(renamedTrack, "Custom line 1\nCustom line 2").getOrThrow()
        assertFalse(edited.parsedLyrics.isSynced)
        assertEquals(LyricsSourceType.MANUAL, edited.sourceType)
        assertEquals(listOf("Custom line 1", "Custom line 2"), edited.parsedLyrics.plainLines)

        // 4. Delete removes persisted entry cleanly
        val deleted = coordinator.deleteLyrics(renamedTrack).getOrThrow()
        assertFalse(deleted.hasPersistedLyrics)
        assertNull(deleted.sourceType)
        assertFalse(db.containsKey(fpA))
    }
}
