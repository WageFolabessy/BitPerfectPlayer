package com.cangkir.bitperfectplayer.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-Kotlin JVM unit tests for [PlaybackPositionTracker] and [EngineMetricMath]
 * (zero `android.*` dependencies).
 */
class PlaybackPositionTrackerTest {

    @Test
    fun computePlaybackPositionMs_advancesFromSeekBaseAndClampsAtDuration() {
        val tracker = PlaybackPositionTracker(
            sampleRateHz = 96_000,
            durationMs = 15_000L,
            initialSeekMs = 5_000L,
            initialHeadFrames = 10_000L,
        )

        assertEquals(5_000L, tracker.computePlaybackPositionMs(10_000L))
        assertEquals(7_500L, tracker.computePlaybackPositionMs(10_000L + 240_000L))
        // Clamped at duration ceiling even if hardware head reports past track duration.
        assertEquals(15_000L, tracker.computePlaybackPositionMs(10_000L + 2_000_000L))
    }

    @Test
    fun reconcileAsyncFlushReset_rebasesCountersWhenHalHeadDropsBelowHeadBase() {
        val tracker = PlaybackPositionTracker(
            sampleRateHz = 48_000,
            durationMs = 60_000L,
            initialSeekMs = 0L,
            initialHeadFrames = 0L,
        )

        tracker.resetAfterSeek(actualSeekMs = 20_000L, headFrames = 96_000L)
        assertTrue(tracker.pendingFlushResetGuard)
        tracker.recordFramesWritten(48_000L)
        assertEquals(144_000L, tracker.totalFramesWrittenToTrack)

        // HAL completes AudioTrack.flush() asynchronously and drops playbackHeadPosition to 0.
        val pos = tracker.computePlaybackPositionMs(hwHead = 0L)
        assertEquals(20_000L, pos)
        assertFalse(tracker.pendingFlushResetGuard)
        assertEquals(0L, tracker.headBaseFrames)
        assertEquals(48_000L, tracker.totalFramesWrittenToTrack)
        assertEquals(0L, tracker.countHeadFrames)
        assertEquals(21_000L, tracker.computePlaybackPositionMs(hwHead = 48_000L))
    }

    @Test
    fun applyRouteHeadRebase_preservesPositionAcrossBothHalResetAndPreservedHead() {
        val tracker = PlaybackPositionTracker(
            sampleRateHz = 48_000,
            durationMs = 60_000L,
            initialSeekMs = 0L,
            initialHeadFrames = 0L,
        )
        tracker.recordFramesWritten(480_000L)
        tracker.accumulateActivePlayTimeMs(headFrames = 480_000L, forceFlush = true)
        assertEquals(10_000L, tracker.computePlaybackPositionMs(480_000L))

        // Case 1: HAL reset playbackHeadPosition to 0 during route switch pause.
        val resetPlan = tracker.applyRouteHeadRebase(observedHeadFrames = 0L)
        assertEquals(10_000L, resetPlan.seekBaseMs)
        assertEquals(0L, resetPlan.headBaseFrames)
        assertFalse(resetPlan.armAsyncFlushResetGuard)
        assertEquals(12_000L, tracker.computePlaybackPositionMs(hwHead = 96_000L))

        // Case 2: HAL preserved head at pause, then asynchronously resets after resume.
        tracker.recordFramesWritten(192_000L)
        tracker.accumulateActivePlayTimeMs(headFrames = 96_000L, forceFlush = true)
        val keepPlan = tracker.applyRouteHeadRebase(observedHeadFrames = 96_000L)
        assertEquals(12_000L, keepPlan.seekBaseMs)
        assertEquals(96_000L, keepPlan.headBaseFrames)
        assertTrue(keepPlan.armAsyncFlushResetGuard)
        // Asynchronous reset to 0 after resume still preserves 12,000 ms continuity.
        assertEquals(12_000L, tracker.computePlaybackPositionMs(hwHead = 0L))
        assertEquals(13_000L, tracker.computePlaybackPositionMs(hwHead = 48_000L))
    }

    @Test
    fun accumulateActivePlayTimeMs_batchesUntil500msAndFlushesOnForce() {
        val tracker = PlaybackPositionTracker(
            sampleRateHz = 48_000,
            durationMs = 60_000L,
            initialSeekMs = 0L,
            initialHeadFrames = 0L,
        )

        // 250 ms < 500 ms threshold -> buffered, not emitted yet.
        assertEquals(0L, tracker.accumulateActivePlayTimeMs(headFrames = 12_000L, forceFlush = false))
        // Additional 300 ms -> total 550 ms >= 500 ms -> emitted and pending reset.
        assertEquals(550L, tracker.accumulateActivePlayTimeMs(headFrames = 26_400L, forceFlush = false))
        // Small 100 ms tail flushed on pause/EOS.
        assertEquals(100L, tracker.accumulateActivePlayTimeMs(headFrames = 31_200L, forceFlush = true))
        // Repeated flush at same head emits nothing.
        assertEquals(0L, tracker.accumulateActivePlayTimeMs(headFrames = 31_200L, forceFlush = true))
    }

    @Test
    fun transitionToSeamlessNextTrack_anchorsTrackBAtTotalFramesWritten() {
        val tracker = PlaybackPositionTracker(
            sampleRateHz = 48_000,
            durationMs = 4_000L,
            initialSeekMs = 0L,
            initialHeadFrames = 0L,
        )
        tracker.recordFramesWritten(192_000L) // 4 seconds queued for Track A

        tracker.transitionToSeamlessNextTrack(
            nextSampleRateHz = 48_000,
            nextDurationMs = 5_000L,
            currentHeadFrames = 180_000L,
        )

        assertEquals(0L, tracker.seekBaseMs)
        assertEquals(192_000L, tracker.headBaseFrames)
        assertEquals(0L, tracker.computePlaybackPositionMs(hwHead = 180_000L))
        assertEquals(0L, tracker.computePlaybackPositionMs(hwHead = 192_000L))
        assertEquals(1_000L, tracker.computePlaybackPositionMs(hwHead = 240_000L))
    }

    @Test
    fun engineMetricMath_computesNonNegativeElapsedAndMonotonicUnderrunDelta() {
        assertEquals(0L, EngineMetricMath.elapsedDurationMs(startMs = 500L, nowMs = 400L))
        assertEquals(125L, EngineMetricMath.elapsedDurationMs(startMs = 500L, nowMs = 625L))

        assertEquals(0, EngineMetricMath.computeUnderrunDelta(previousReported = 3, currentTotal = 3))
        assertEquals(0, EngineMetricMath.computeUnderrunDelta(previousReported = 3, currentTotal = 1))
        assertEquals(4, EngineMetricMath.computeUnderrunDelta(previousReported = 3, currentTotal = 7))
    }
}
