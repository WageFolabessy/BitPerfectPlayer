package com.cangkir.bitperfectplayer.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioStartPathAndCoalescerTest {

    @Test
    fun audioStartPathPolicy_neverQueriesUsbMixerInSafeModeOrNoisyCooldown() {
        assertEquals(
            MixerNegotiationStrategy.SKIP_IN_SAFE_MODE,
            AudioStartPathPolicy.decideMixerNegotiationStrategy(
                sdkInt = 35,
                mode = AudioOutputMode.SAFE_VOLUME_MODE,
                isNoisyCooldownActive = false,
                hasUsbSink = true,
            ),
        )
        assertEquals(
            MixerNegotiationStrategy.SKIP_DURING_NOISY_COOLDOWN,
            AudioStartPathPolicy.decideMixerNegotiationStrategy(
                sdkInt = 35,
                mode = AudioOutputMode.BIT_PERFECT_OPT_IN,
                isNoisyCooldownActive = true,
                hasUsbSink = true,
            ),
        )
        assertEquals(
            MixerNegotiationStrategy.SKIP_IN_SAFE_MODE,
            AudioStartPathPolicy.decideMixerNegotiationStrategy(
                sdkInt = 33,
                mode = AudioOutputMode.BIT_PERFECT_OPT_IN,
                isNoisyCooldownActive = false,
                hasUsbSink = true,
            ),
        )
        assertEquals(
            MixerNegotiationStrategy.SKIP_IN_SAFE_MODE,
            AudioStartPathPolicy.decideMixerNegotiationStrategy(
                sdkInt = 35,
                mode = AudioOutputMode.BIT_PERFECT_OPT_IN,
                isNoisyCooldownActive = false,
                hasUsbSink = false,
            ),
        )
        assertEquals(
            MixerNegotiationStrategy.APPLY_BIT_PERFECT_USB,
            AudioStartPathPolicy.decideMixerNegotiationStrategy(
                sdkInt = 34,
                mode = AudioOutputMode.BIT_PERFECT_OPT_IN,
                isNoisyCooldownActive = false,
                hasUsbSink = true,
            ),
        )
    }

    @Test
    fun audioStartPathPolicy_logsSlowStageStrictlyInDebugAboveThreshold() {
        assertFalse(AudioStartPathPolicy.shouldLogSlowAudioStage(isDebugBuild = false, elapsedMs = 2_500L))
        assertFalse(AudioStartPathPolicy.shouldLogSlowAudioStage(isDebugBuild = true, elapsedMs = 150L))
        assertTrue(AudioStartPathPolicy.shouldLogSlowAudioStage(isDebugBuild = true, elapsedMs = 151L))
    }

    @Test
    fun audioStartPathPolicy_coalescesRepeatedPlayTapsWhilePreparing() {
        assertEquals(
            TogglePlayPauseAction.PLAY,
            AudioStartPathPolicy.resolveTogglePlayPauseAction(
                isPlaying = false,
                isPreparingPlayback = true,
                isPreservingPlayingUi = false,
            ),
        )
        assertEquals(
            TogglePlayPauseAction.PAUSE,
            AudioStartPathPolicy.resolveTogglePlayPauseAction(
                isPlaying = false,
                isPreparingPlayback = true,
                isPreservingPlayingUi = true,
            ),
        )
        assertEquals(
            TogglePlayPauseAction.PAUSE,
            AudioStartPathPolicy.resolveTogglePlayPauseAction(
                isPlaying = true,
                isPreparingPlayback = false,
                isPreservingPlayingUi = false,
            ),
        )
    }

    @Test
    fun commandCoalescer_collapsesRapidTapsAndLoadSupersedingToLatestIntent() {
        val coalescer = PlaybackCommandCoalescer<String>()

        assertTrue(coalescer.submitPlay())
        assertFalse(coalescer.submitPlay())
        assertFalse(coalescer.submitPause())
        assertFalse(coalescer.submitPlay())

        assertEquals(CoalescedControlIntent.Play, coalescer.pollNextIntent())
        coalescer.markExecutionFinished()
        assertNull(coalescer.pollNextIntent())
        assertFalse(coalescer.onDrainExited())

        assertTrue(coalescer.submitLoadTrack("trackA", startAfterLoad = true))
        assertFalse(coalescer.submitLoadTrack("trackB", startAfterLoad = false))
        assertFalse(coalescer.submitPlay())
        assertFalse(coalescer.submitLoadTrack("trackC", startAfterLoad = false))
        assertFalse(coalescer.submitPlay())

        val polled = coalescer.pollNextIntent()
        assertEquals(CoalescedControlIntent.LoadTrack("trackC", startAfterLoad = true), polled)
        assertTrue(coalescer.isStartOrLoadInFlight())
        coalescer.markExecutionFinished()
        assertFalse(coalescer.isStartOrLoadInFlight())
    }

    @Test
    fun commandCoalescer_cancelsPendingStartOnExternalPause() {
        val coalescer = PlaybackCommandCoalescer<String>()
        coalescer.submitLoadTrack("trackA", startAfterLoad = true)
        coalescer.cancelPendingStartOnExternalPause()

        assertEquals(
            CoalescedControlIntent.LoadTrack("trackA", startAfterLoad = false),
            coalescer.pollNextIntent(),
        )
        coalescer.markExecutionFinished()
        assertNull(coalescer.pollNextIntent())

        coalescer.submitPlay()
        coalescer.cancelPendingStartOnExternalPause()
        assertNull(coalescer.pollNextIntent())
    }

    @Test
    fun routeEventFilter_exposesNoisyCooldownWindowWithAndWithoutPreviousDevice() {
        val filter = AudioRouteEventFilter(coalesceWindowMs = 8_000L)
        assertFalse(filter.isWithinNoisyCooldown(nowElapsedMs = 1_000L))

        filter.onBecomingNoisy(nowElapsedMs = 1_000L, resolvedFallbackDevice = null)
        assertTrue(filter.isWithinNoisyCooldown(nowElapsedMs = 8_999L))
        assertFalse(filter.isWithinNoisyCooldown(nowElapsedMs = 9_001L))
    }

    @Test
    fun trackTransitionPolicy_preservesPlayingUiOnlyOnPlayingTrackSwitch() {
        assertTrue(TrackTransitionStatePolicy.shouldPreservePlayingUiOnTrackSwitch(wasPlaying = true, autoPlay = true))
        assertFalse(TrackTransitionStatePolicy.shouldPreservePlayingUiOnTrackSwitch(wasPlaying = false, autoPlay = true))
        assertFalse(TrackTransitionStatePolicy.shouldPreservePlayingUiOnTrackSwitch(wasPlaying = true, autoPlay = false))

        assertTrue(
            TrackTransitionStatePolicy.isEffectivelyPlayingForUi(
                status = PlaybackStatus.PREPARING,
                preservePlayingUiDuringTransition = true,
            ),
        )
        assertFalse(
            TrackTransitionStatePolicy.isEffectivelyPlayingForUi(
                status = PlaybackStatus.PREPARING,
                preservePlayingUiDuringTransition = false,
            ),
        )
        assertFalse(
            TrackTransitionStatePolicy.isEffectivelyPlayingForUi(
                status = PlaybackStatus.PAUSED,
                preservePlayingUiDuringTransition = true,
            ),
        )
    }

    @Test
    fun audioStartPathPolicy_onlyPinsUsbDeviceInBitPerfectAndSkipsReplayGainScanWhenOff() {
        assertFalse(AudioStartPathPolicy.shouldBindPreferredUsbDevice(AudioOutputMode.SAFE_VOLUME_MODE, hasUsbSink = true))
        assertFalse(AudioStartPathPolicy.shouldBindPreferredUsbDevice(AudioOutputMode.BIT_PERFECT_OPT_IN, hasUsbSink = false))
        assertTrue(AudioStartPathPolicy.shouldBindPreferredUsbDevice(AudioOutputMode.BIT_PERFECT_OPT_IN, hasUsbSink = true))

        assertFalse(AudioStartPathPolicy.shouldScanReplayGainMetadata(ReplayGainMode.OFF))
        assertTrue(AudioStartPathPolicy.shouldScanReplayGainMetadata(ReplayGainMode.TRACK))
        assertTrue(AudioStartPathPolicy.shouldScanReplayGainMetadata(ReplayGainMode.ALBUM))
    }
}
