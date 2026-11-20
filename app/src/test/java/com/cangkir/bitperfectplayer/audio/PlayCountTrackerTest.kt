package com.cangkir.bitperfectplayer.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayCountTrackerTest {

    @Test
    fun skipsUnderFiveSeconds_neverIncrementPlayCount_butStillFlushListeningTime() {
        val tracker = PlayCountTracker()
        assertNull(tracker.startSession("content://track/1", durationMs = 240_000L))

        // Listen for 4 seconds in 500 ms chunks then switch tracks
        repeat(8) {
            assertNull(tracker.onActivePlaybackElapsed(500L))
        }
        assertFalse(tracker.hasQualifiedCurrentSession())

        val flushOnSwitch = tracker.startSession("content://track/2", durationMs = 200_000L)
        assertNotNull(flushOnSwitch)
        assertEquals("content://track/1", flushOnSwitch!!.trackUri)
        assertFalse(flushOnSwitch.incrementPlayCount)
        assertEquals(4_000L, flushOnSwitch.playTimeDeltaMs)
    }

    @Test
    fun seekToEnd_cannotFoolTrackerIntoCountingPlay() {
        val tracker = PlayCountTracker()
        tracker.startSession("content://track/long", durationMs = 240_000L)

        // Play 2s, seek to 99%, play 2s until natural completion -> total 4s < 30s
        repeat(4) { assertNull(tracker.onActivePlaybackElapsed(500L)) }
        tracker.onSeek()
        repeat(4) { assertNull(tracker.onActivePlaybackElapsed(500L)) }

        val eosFlush = tracker.onNaturalCompletion()
        assertNotNull(eosFlush)
        assertFalse(eosFlush!!.incrementPlayCount)
        assertEquals(4_000L, eosFlush.playTimeDeltaMs)
        assertFalse(tracker.hasQualifiedCurrentSession())
    }

    @Test
    fun pauseAndResume_countsAtMostOncePerSession_andAccumulatesTotalPlayTimeAccurately() {
        val tracker = PlayCountTracker()
        tracker.startSession("content://track/jazz", durationMs = 240_000L)

        // Play 15 seconds, then pause
        repeat(30) { assertNull(tracker.onActivePlaybackElapsed(500L)) }
        val pauseFlush1 = tracker.flushPendingPlayTime()
        assertNotNull(pauseFlush1)
        assertFalse(pauseFlush1!!.incrementPlayCount)
        assertEquals(15_000L, pauseFlush1.playTimeDeltaMs)

        // Resume and play 14.5 more seconds (29.5s total -> not yet qualified)
        repeat(29) { assertNull(tracker.onActivePlaybackElapsed(500L)) }
        assertFalse(tracker.hasQualifiedCurrentSession())

        // Next 500 ms crosses 30,000 ms threshold -> qualifies ONCE
        val qualifiedEvent = tracker.onActivePlaybackElapsed(500L)
        assertNotNull(qualifiedEvent)
        assertTrue(qualifiedEvent!!.incrementPlayCount)
        assertEquals(15_000L, qualifiedEvent.playTimeDeltaMs)
        assertTrue(tracker.hasQualifiedCurrentSession())

        // Continue playing 60 more seconds across another pause/resume -> MUST NOT double-count
        repeat(60) { assertNull(tracker.onActivePlaybackElapsed(500L)) }
        val pauseFlush2 = tracker.flushPendingPlayTime()
        assertNotNull(pauseFlush2)
        assertFalse(pauseFlush2!!.incrementPlayCount)
        assertEquals(30_000L, pauseFlush2.playTimeDeltaMs)

        repeat(60) { assertNull(tracker.onActivePlaybackElapsed(500L)) }
        val eosFlush = tracker.onNaturalCompletion()
        assertNotNull(eosFlush)
        assertFalse(eosFlush!!.incrementPlayCount)
        assertEquals(30_000L, eosFlush.playTimeDeltaMs)
    }

    @Test
    fun qualificationThreshold_follows30sOr50PercentMin10sAndShortTrackRules() {
        // Long track (>= 60s): capped at 30s
        assertEquals(30_000L, PlayCountTracker.qualificationThresholdMs(240_000L))
        assertEquals(30_000L, PlayCountTracker.qualificationThresholdMs(60_000L))

        // Mid track (20s..60s): 50% of duration
        assertEquals(20_000L, PlayCountTracker.qualificationThresholdMs(40_000L))

        // Short-ish track (10s..20s): clamped to minimum 10s
        assertEquals(10_000L, PlayCountTracker.qualificationThresholdMs(14_000L))
        assertEquals(10_000L, PlayCountTracker.qualificationThresholdMs(10_000L))

        // Very short track (1..9,999 ms): max(5s, 80%)
        assertEquals(6_400L, PlayCountTracker.qualificationThresholdMs(8_000L))
        assertEquals(5_000L, PlayCountTracker.qualificationThresholdMs(6_000L))

        // Unknown duration: 30s
        assertEquals(30_000L, PlayCountTracker.qualificationThresholdMs(0L))

        // Verify 40s track actually qualifies at 20s
        val midTracker = PlayCountTracker()
        midTracker.startSession("content://track/40s", durationMs = 40_000L)
        repeat(39) { assertNull(midTracker.onActivePlaybackElapsed(500L)) }
        val qual = midTracker.onActivePlaybackElapsed(500L)
        assertNotNull(qual)
        assertTrue(qual!!.incrementPlayCount)
        assertEquals(20_000L, qual.playTimeDeltaMs)
    }

    @Test
    fun shortTrackNaturalEosAfter5sQualifies_whileUnder5sStrictlyNeverQualifies() {
        // 8s track reaching natural EOS after 5.5s qualifies
        val shortTracker = PlayCountTracker()
        shortTracker.startSession("content://track/8s", durationMs = 8_000L)
        repeat(11) { assertNull(shortTracker.onActivePlaybackElapsed(500L)) } // 5,500 ms (< 6,400 ms)
        val eosQual = shortTracker.onNaturalCompletion()
        assertNotNull(eosQual)
        assertTrue(eosQual!!.incrementPlayCount)
        assertEquals(5_500L, eosQual.playTimeDeltaMs)

        // 4s interlude reaching natural EOS after 4s strictly NEVER qualifies (< 5s hard floor)
        val tinyTracker = PlayCountTracker()
        tinyTracker.startSession("content://track/4s", durationMs = 4_000L)
        repeat(8) { assertNull(tinyTracker.onActivePlaybackElapsed(500L)) } // 4,000 ms
        val tinyEos = tinyTracker.onNaturalCompletion()
        assertNotNull(tinyEos)
        assertFalse(tinyEos!!.incrementPlayCount)
        assertEquals(4_000L, tinyEos.playTimeDeltaMs)
    }

    @Test
    fun repeatOneOrReplayAfterCompletion_resetsSessionForSecondPlayCount() {
        val tracker = PlayCountTracker()
        tracker.startSession("content://track/repeat", durationMs = 20_000L) // threshold = 10s

        // First playthrough qualifies at 10s
        repeat(19) { assertNull(tracker.onActivePlaybackElapsed(500L)) }
        assertTrue(tracker.onActivePlaybackElapsed(500L)!!.incrementPlayCount)
        tracker.onNaturalCompletion()

        // Loop under RepeatMode.ONE / replay from start
        tracker.resetSessionForReplay()
        assertFalse(tracker.hasQualifiedCurrentSession())

        // Second playthrough qualifies again at 10s
        repeat(19) { assertNull(tracker.onActivePlaybackElapsed(500L)) }
        val secondQual = tracker.onActivePlaybackElapsed(500L)
        assertNotNull(secondQual)
        assertTrue(secondQual!!.incrementPlayCount)
    }

    @Test
    fun singleTickSpike_isBoundedToPreventWallClockSpoofing() {
        val tracker = PlayCountTracker()
        tracker.startSession("content://track/spike", durationMs = 240_000L)

        // A single bogus 60-second tick must be capped at 2,000 ms and MUST NOT qualify
        assertNull(tracker.onActivePlaybackElapsed(60_000L))
        assertEquals(2_000L, tracker.currentSessionAccumulatedMs())
        assertFalse(tracker.hasQualifiedCurrentSession())
    }
}
