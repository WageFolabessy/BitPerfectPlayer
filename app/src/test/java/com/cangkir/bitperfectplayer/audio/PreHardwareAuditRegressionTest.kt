package com.cangkir.bitperfectplayer.audio

import com.cangkir.bitperfectplayer.data.BackupPlaylist
import com.cangkir.bitperfectplayer.data.BackupPlaylistEntry
import com.cangkir.bitperfectplayer.data.FavoriteTrackRecord
import com.cangkir.bitperfectplayer.data.LocalBackupCodec
import com.cangkir.bitperfectplayer.data.LocalBackupPayload
import com.cangkir.bitperfectplayer.data.LyricsRecord
import com.cangkir.bitperfectplayer.data.LyricsSourceType
import com.cangkir.bitperfectplayer.data.TrackStatsRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused regression tests for edge cases uncovered during the Pre-Hardware Deep Codebase Audit:
 * - [PlayCountTracker] across pause/resume, manual seek, `RepeatMode.ONE` replay, and seamless
 *   gapless transitions.
 * - [LocalBackupCodec] special-character/Unicode escaping (`"`, `\`, `\n`, `\r`, `\t`, control chars,
 *   CJK/musical symbols) and round-trip integrity.
 * - [PlaybackQueueManager] duplicate-track queue slot removal when the currently playing slot is removed.
 * - [GaplessTransitionPolicy] preload trigger right after seeking into the final 8 seconds.
 */
class PreHardwareAuditRegressionTest {

    @Test
    fun playCountTracker_seamlessGaplessTransitionFlushesOutgoingAndResetsForIncomingTrack() {
        val tracker = PlayCountTracker()
        val trackAUri = "content://test/audio/trackA.flac"
        val trackBUri = "content://test/audio/trackB.flac"

        // Track A (200s -> threshold 30s): play 20s, seek (ignored), pause/resume, play 12s -> qualifies
        assertNull(tracker.startSession(trackAUri, durationMs = 200_000L))
        repeat(10) {
            assertNull(tracker.onActivePlaybackElapsed(2_000L)) // 20s total
        }
        tracker.onSeek() // Seeking must add 0 ms
        assertEquals(20_000L, tracker.currentSessionAccumulatedMs())

        val pauseFlush = tracker.flushPendingPlayTime()
        assertNotNull(pauseFlush)
        assertFalse(pauseFlush!!.incrementPlayCount)
        assertEquals(20_000L, pauseFlush.playTimeDeltaMs)

        // Resume Track A for 10s -> crosses 30s threshold and increments play count once
        repeat(4) {
            assertNull(tracker.onActivePlaybackElapsed(2_000L)) // 28s
        }
        val qualifiedEvent = tracker.onActivePlaybackElapsed(2_000L) // 30s
        assertNotNull(qualifiedEvent)
        assertTrue(qualifiedEvent!!.incrementPlayCount)
        assertEquals(trackAUri, qualifiedEvent.trackUri)
        assertEquals(10_000L, qualifiedEvent.playTimeDeltaMs)

        // Play 4s more of Track A before natural gapless transition into Track B
        repeat(2) {
            assertNull(tracker.onActivePlaybackElapsed(2_000L))
        }
        val naturalEosFlush = tracker.onNaturalCompletion()
        assertNotNull(naturalEosFlush)
        assertFalse(naturalEosFlush!!.incrementPlayCount) // Already counted once in session
        assertEquals(4_000L, naturalEosFlush.playTimeDeltaMs)

        // Seamless gapless transition starts Track B session cleanly at 0 ms
        assertNull(tracker.startSession(trackBUri, durationMs = 180_000L))
        assertEquals(0L, tracker.currentSessionAccumulatedMs())
        assertFalse(tracker.hasQualifiedCurrentSession())

        repeat(15) {
            val ev = tracker.onActivePlaybackElapsed(2_000L)
            if (it < 14) {
                assertNull(ev)
            } else {
                assertNotNull(ev)
                assertEquals(trackBUri, ev!!.trackUri)
                assertTrue(ev.incrementPlayCount)
                assertEquals(30_000L, ev.playTimeDeltaMs)
            }
        }
    }

    @Test
    fun playCountTracker_repeatOneResetAllowsSecondPlayCountOnReplay() {
        val tracker = PlayCountTracker()
        val uri = "content://test/audio/repeat_one.flac"

        tracker.startSession(uri, durationMs = 24_000L) // threshold = 12s
        repeat(6) { tracker.onActivePlaybackElapsed(2_000L) }
        assertTrue(tracker.hasQualifiedCurrentSession())

        // Reset session on RepeatMode.ONE loop or post-completion replay
        tracker.resetSessionForReplay()
        assertEquals(0L, tracker.currentSessionAccumulatedMs())
        assertFalse(tracker.hasQualifiedCurrentSession())

        var secondQualified: PlayCountFlushEvent? = null
        repeat(6) {
            val ev = tracker.onActivePlaybackElapsed(2_000L)
            if (ev != null) secondQualified = ev
        }
        assertNotNull(secondQualified)
        assertTrue(secondQualified!!.incrementPlayCount)
    }

    @Test
    fun localBackupCodec_roundTripsSpecialCharactersControlCharsAndUnicodeLosslessly() {
        val fp = "1234567890abcdef".repeat(4)
        val trickyTitle = "Song \"Live\" \\ Backslash \t Tab & ♪ 音楽 — Café"
        val trickyLyrics = "[00:01.00]Line with \"quotes\" and \\backslash\\\n[00:05.50]Second line ♪ 歌詞\n[00:09.00]Control:\u0007bell"

        val payload = LocalBackupPayload(
             exportedAtEpochMs = 1_760_000_000_000L,
            playlists = listOf(
                BackupPlaylist(
                    name = "Audiophile \"Reference\" \\ Mix ♪",
                    createdAtEpochMs = 1000L,
                    updatedAtEpochMs = 2000L,
                    tracks = listOf(
                        BackupPlaylistEntry(
                            contentFingerprint = fp,
                            trackUri = "content://media/external/audio/media/77",
                            fileName = "01 - \"Live\" Track.flac",
                            positionIndex = 0,
                            addedAtEpochMs = 1500L,
                        ),
                    ),
                ),
            ),
            lyrics = listOf(
                LyricsRecord(
                    contentFingerprint = fp,
                    rawLyricsText = trickyLyrics,
                    isSyncedLrc = true,
                    sourceType = LyricsSourceType.MANUAL,
                    fileName = "01 - \"Live\" Track.flac",
                    fileSizeBytes = 50_000_000L,
                    durationMs = 240_000L,
                    updatedAtEpochMs = 3000L,
                ),
            ),
            favorites = listOf(
                FavoriteTrackRecord(
                    contentFingerprint = fp,
                    trackUri = "content://media/external/audio/media/77",
                    title = trickyTitle,
                    artist = "AC/DC & \"Friends\"",
                    album = "Back\\In\\Black",
                    fileName = "01 - \"Live\" Track.flac",
                    durationMs = 240_000L,
                    addedAtEpochMs = 4000L,
                ),
            ),
            trackStats = listOf(
                TrackStatsRecord(
                    contentFingerprint = fp,
                    trackUri = "content://media/external/audio/media/77",
                    title = trickyTitle,
                    artist = "AC/DC & \"Friends\"",
                    album = "Back\\In\\Black",
                    fileName = "01 - \"Live\" Track.flac",
                    playCount = 19,
                    totalPlayTimeMs = 4_560_000L,
                    lastPlayedAtEpochMs = 5000L,
                ),
            ),
        )

        val json = LocalBackupCodec.encode(payload)
        val decoded = LocalBackupCodec.decode(json).getOrThrow()

        assertEquals(1, decoded.playlists.size)
        assertEquals("Audiophile \"Reference\" \\ Mix ♪", decoded.playlists.first().name)
        assertEquals(trickyLyrics, decoded.lyrics.first().rawLyricsText)
        assertEquals(trickyTitle, decoded.favorites.first().title)
        assertEquals("Back\\In\\Black", decoded.favorites.first().album)
        assertEquals(19, decoded.trackStats.first().playCount)
    }

    @Test
    fun playbackQueueManager_removingActiveDuplicateTrackSlotAdvancesToSecondCopyCleanly() {
        val queue = PlaybackQueueManager<String>()
        queue.setQueue(listOf("content://songA", "content://songA", "content://songB"), startIndex = 0)

        val removed = queue.removeAt(0)
        assertEquals("content://songA", removed)

        val snap = queue.snapshot()
        assertEquals(2, snap.size)
        assertEquals(0, snap.currentIndex)
        assertEquals("content://songA", snap.currentItem)
        assertEquals("content://songB", queue.peekNext(userInitiated = false))
    }

    @Test
    fun gaplessTransitionPolicy_triggersPreloadImmediatelyAfterSeekingIntoFinal8Seconds() {
        assertTrue(
            GaplessTransitionPolicy.shouldStartPreload(
                gaplessEnabled = true,
                hasNextTrack = true,
                isAlreadyPreloadingOrReady = false,
                currentPositionMs = 197_500L,
                durationMs = 204_000L,
            ),
        )
    }
}
