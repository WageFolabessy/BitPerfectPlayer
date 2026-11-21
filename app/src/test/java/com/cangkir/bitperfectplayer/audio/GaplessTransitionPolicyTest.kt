package com.cangkir.bitperfectplayer.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GaplessTransitionPolicyTest {

    private val flac96Float = PcmStreamSignature(
        sampleRateHz = 96_000,
        channelCount = 2,
        encoding = PcmEncodingType.PCM_FLOAT,
    )
    private val flac44Int16 = PcmStreamSignature(
        sampleRateHz = 44_100,
        channelCount = 2,
        encoding = PcmEncodingType.PCM_16BIT,
    )

    @Test
    fun identicalFormatWithGaplessEnabledAndPreloadedNext_selectsSeamlessReuse() {
        val action = GaplessTransitionPolicy.decide(
            gaplessEnabled = true,
            hasNextTrack = true,
            currentFormat = flac96Float,
            nextFormat = flac96Float.copy(),
        )
        assertEquals(GaplessTransitionAction.SEAMLESS_REUSE, action)
    }

    @Test
    fun differingSampleRateChannelOrEncoding_selectsDrainAndRebuild() {
        assertEquals(
            GaplessTransitionAction.DRAIN_AND_REBUILD,
            GaplessTransitionPolicy.decide(
                gaplessEnabled = true,
                hasNextTrack = true,
                currentFormat = flac96Float,
                nextFormat = flac44Int16,
            ),
        )
        assertEquals(
            GaplessTransitionAction.DRAIN_AND_REBUILD,
            GaplessTransitionPolicy.decide(
                gaplessEnabled = true,
                hasNextTrack = true,
                currentFormat = flac96Float,
                nextFormat = flac96Float.copy(channelCount = 1),
            ),
        )
        assertEquals(
            GaplessTransitionAction.DRAIN_AND_REBUILD,
            GaplessTransitionPolicy.decide(
                gaplessEnabled = true,
                hasNextTrack = true,
                currentFormat = flac96Float,
                nextFormat = flac96Float.copy(encoding = PcmEncodingType.PCM_24BIT_PACKED),
            ),
        )
    }

    @Test
    fun gaplessDisabledOrNextNotPreloaded_selectsDrainAndRebuildNeverTruncates() {
        assertEquals(
            GaplessTransitionAction.DRAIN_AND_REBUILD,
            GaplessTransitionPolicy.decide(
                gaplessEnabled = false,
                hasNextTrack = true,
                currentFormat = flac96Float,
                nextFormat = flac96Float,
            ),
        )
        assertEquals(
            GaplessTransitionAction.DRAIN_AND_REBUILD,
            GaplessTransitionPolicy.decide(
                gaplessEnabled = true,
                hasNextTrack = true,
                currentFormat = flac96Float,
                nextFormat = null,
            ),
        )
    }

    @Test
    fun endOfQueue_selectsStopEndOfQueue() {
        assertEquals(
            GaplessTransitionAction.STOP_END_OF_QUEUE,
            GaplessTransitionPolicy.decide(
                gaplessEnabled = true,
                hasNextTrack = false,
                currentFormat = flac96Float,
                nextFormat = null,
            ),
        )
    }

    @Test
    fun shouldStartPreload_triggersWithinLeadWindowAndImmediatelyForShortTracks() {
        // 3-minute track: too early at 2:00, triggers at 2:52 (8s left)
        assertFalse(
            GaplessTransitionPolicy.shouldStartPreload(
                gaplessEnabled = true,
                hasNextTrack = true,
                isAlreadyPreloadingOrReady = false,
                currentPositionMs = 120_000L,
                durationMs = 180_000L,
            ),
        )
        assertTrue(
            GaplessTransitionPolicy.shouldStartPreload(
                gaplessEnabled = true,
                hasNextTrack = true,
                isAlreadyPreloadingOrReady = false,
                currentPositionMs = 172_000L,
                durationMs = 180_000L,
            ),
        )
        // Short 6s track: preloads immediately at 0 ms
        assertTrue(
            GaplessTransitionPolicy.shouldStartPreload(
                gaplessEnabled = true,
                hasNextTrack = true,
                isAlreadyPreloadingOrReady = false,
                currentPositionMs = 0L,
                durationMs = 6_000L,
            ),
        )
        // Disabled or already preloading -> never triggers
        assertFalse(
            GaplessTransitionPolicy.shouldStartPreload(
                gaplessEnabled = false,
                hasNextTrack = true,
                isAlreadyPreloadingOrReady = false,
                currentPositionMs = 175_000L,
                durationMs = 180_000L,
            ),
        )
        assertFalse(
            GaplessTransitionPolicy.shouldStartPreload(
                gaplessEnabled = true,
                hasNextTrack = true,
                isAlreadyPreloadingOrReady = true,
                currentPositionMs = 175_000L,
                durationMs = 180_000L,
            ),
        )
    }

    @Test
    fun computeDrainTimeoutMs_staysBoundedWithinSafetyRange() {
        assertEquals(
            GaplessTransitionPolicy.MIN_DRAIN_WAIT_MS,
            GaplessTransitionPolicy.computeDrainTimeoutMs(0L, 48_000),
        )
        // 24,000 frames @ 48,000 Hz = 500 ms + 250 ms margin = 750 ms
        assertEquals(
            750L,
            GaplessTransitionPolicy.computeDrainTimeoutMs(24_000L, 48_000),
        )
        // Huge buffered estimate is strictly capped at MAX_DRAIN_WAIT_MS (1,500 ms)
        assertEquals(
            GaplessTransitionPolicy.MAX_DRAIN_WAIT_MS,
            GaplessTransitionPolicy.computeDrainTimeoutMs(500_000L, 44_100),
        )
    }

    @Test
    fun resolvePostTransitionDirective_pausesAndPersistsWhenEndOfTrackSleepTimerTriggers() {
        // Normal gapless handoff when End-of-Track sleep timer triggered right at boundary -> MUST pause & persist
        assertEquals(
            PostGaplessTransitionDirective.PAUSE_AND_PERSIST,
            GaplessTransitionPolicy.resolvePostTransitionDirective(
                hasNextTrack = true,
                nextTrackMatchesTransitioned = true,
                pauseAfterTransition = true,
            ),
        )
        // Normal gapless handoff without End-of-Track trigger -> continue & sync subsequent track
        assertEquals(
            PostGaplessTransitionDirective.CONTINUE_AND_SYNC_NEXT,
            GaplessTransitionPolicy.resolvePostTransitionDirective(
                hasNextTrack = true,
                nextTrackMatchesTransitioned = true,
                pauseAfterTransition = false,
            ),
        )
        // Queue mutated during transition -> reload actual next track
        assertEquals(
            PostGaplessTransitionDirective.RESYNC_VIA_RELOAD,
            GaplessTransitionPolicy.resolvePostTransitionDirective(
                hasNextTrack = true,
                nextTrackMatchesTransitioned = false,
                pauseAfterTransition = true,
            ),
        )
        // No next track -> stop cleanly at end of queue
        assertEquals(
            PostGaplessTransitionDirective.STOP_END_OF_QUEUE,
            GaplessTransitionPolicy.resolvePostTransitionDirective(
                hasNextTrack = false,
                nextTrackMatchesTransitioned = false,
                pauseAfterTransition = false,
            ),
        )
    }
}
