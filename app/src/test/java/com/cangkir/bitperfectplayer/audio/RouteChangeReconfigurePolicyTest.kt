package com.cangkir.bitperfectplayer.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteChangeReconfigurePolicyTest {

    @Test
    fun routeChangeWhilePaused_defersReconfigureWithoutReloadLoopAndRefreshesOnNextPlay() {
        // Repeated virtual route changes (e.g., screen recorder start + stop) while paused must defer
        val startRecordingAction = RouteChangeReconfigurePolicy.decideAction(
            commandType = SafetyEventType.AUTO_PAUSE,
            isActivelyPlayingOrPreparing = false,
            hasLoadedTrack = true,
        )
        val stopRecordingAction = RouteChangeReconfigurePolicy.decideAction(
            commandType = SafetyEventType.AUTO_PAUSE,
            isActivelyPlayingOrPreparing = false,
            hasLoadedTrack = true,
        )
        assertEquals(SafetyReconfigureAction.DEFER_RECONFIGURE_UNTIL_PLAY, startRecordingAction)
        assertEquals(SafetyReconfigureAction.DEFER_RECONFIGURE_UNTIL_PLAY, stopRecordingAction)

        // Subsequent play() must reload once to refresh route and USB mixer attributes
        assertTrue(
            RouteChangeReconfigurePolicy.shouldReloadOnPlay(
                hasPendingRouteReconfigure = true,
                hasActiveDecoder = true,
                hasActiveAudioTrack = true,
                isCompleted = false,
            ),
        )
    }

    @Test
    fun normalPausedResumeWithoutRouteChange_resumesExistingTrackWithoutReload() {
        assertFalse(
            RouteChangeReconfigurePolicy.shouldReloadOnPlay(
                hasPendingRouteReconfigure = false,
                hasActiveDecoder = true,
                hasActiveAudioTrack = true,
                isCompleted = false,
            ),
        )
    }

    @Test
    fun routeChangeWhileActivelyPlayingOrPreparing_tearsDownAndRebindsOnNextPlay() {
        assertEquals(
            SafetyReconfigureAction.TEARDOWN_AND_REBIND_ON_PLAY,
            RouteChangeReconfigurePolicy.decideAction(
                commandType = SafetyEventType.AUTO_PAUSE,
                isActivelyPlayingOrPreparing = true,
                hasLoadedTrack = true,
            ),
        )
    }

    @Test
    fun explicitModeSwitchesWithLoadedTrack_reconfigureImmediatelyEvenWhenPaused() {
        assertEquals(
            SafetyReconfigureAction.RECONFIGURE_NOW,
            RouteChangeReconfigurePolicy.decideAction(
                commandType = SafetyEventType.MODE_CHANGED_TO_BIT_PERFECT,
                isActivelyPlayingOrPreparing = false,
                hasLoadedTrack = true,
            ),
        )
        assertEquals(
            SafetyReconfigureAction.RECONFIGURE_NOW,
            RouteChangeReconfigurePolicy.decideAction(
                commandType = SafetyEventType.MODE_CHANGED_TO_SAFE,
                isActivelyPlayingOrPreparing = false,
                hasLoadedTrack = true,
            ),
        )
    }

    @Test
    fun idleEngineOrWarningOnlyCommands_refreshDiagnosticsOnly() {
        assertEquals(
            SafetyReconfigureAction.DIAGNOSTICS_ONLY,
            RouteChangeReconfigurePolicy.decideAction(
                commandType = SafetyEventType.AUTO_PAUSE,
                isActivelyPlayingOrPreparing = false,
                hasLoadedTrack = false,
            ),
        )
        assertEquals(
            SafetyReconfigureAction.DIAGNOSTICS_ONLY,
            RouteChangeReconfigurePolicy.decideAction(
                commandType = SafetyEventType.MODE_CHANGED_TO_BIT_PERFECT,
                isActivelyPlayingOrPreparing = false,
                hasLoadedTrack = false,
            ),
        )
        assertEquals(
            SafetyReconfigureAction.DIAGNOSTICS_ONLY,
            RouteChangeReconfigurePolicy.decideAction(
                commandType = SafetyEventType.REQUIRES_SAFETY_WARNING,
                isActivelyPlayingOrPreparing = true,
                hasLoadedTrack = true,
            ),
        )
        assertEquals(
            SafetyReconfigureAction.DIAGNOSTICS_ONLY,
            RouteChangeReconfigurePolicy.decideAction(
                commandType = SafetyEventType.NONE,
                isActivelyPlayingOrPreparing = true,
                hasLoadedTrack = true,
            ),
        )
    }

    @Test
    fun playAfterCompletionErrorUnhealthyTrackOrMissingResources_requiresReload() {
        assertTrue(
            RouteChangeReconfigurePolicy.shouldReloadOnPlay(
                hasPendingRouteReconfigure = false,
                hasActiveDecoder = false,
                hasActiveAudioTrack = true,
                isCompleted = false,
            ),
        )
        assertTrue(
            RouteChangeReconfigurePolicy.shouldReloadOnPlay(
                hasPendingRouteReconfigure = false,
                hasActiveDecoder = true,
                hasActiveAudioTrack = false,
                isCompleted = false,
            ),
        )
        assertTrue(
            RouteChangeReconfigurePolicy.shouldReloadOnPlay(
                hasPendingRouteReconfigure = false,
                hasActiveDecoder = true,
                hasActiveAudioTrack = true,
                isCompleted = true,
            ),
        )
        assertTrue(
            RouteChangeReconfigurePolicy.shouldReloadOnPlay(
                hasPendingRouteReconfigure = false,
                hasActiveDecoder = true,
                hasActiveAudioTrack = true,
                isCompleted = false,
                isError = true,
            ),
        )
        assertTrue(
            RouteChangeReconfigurePolicy.shouldReloadOnPlay(
                hasPendingRouteReconfigure = false,
                hasActiveDecoder = true,
                hasActiveAudioTrack = true,
                isCompleted = false,
                isAudioTrackHealthy = false,
            ),
        )
    }

    @Test
    fun safeModeUnpinnedHealthyTrack_pausesInPlaceWithoutTeardown() {
        val canPauseInPlace = RouteChangeReconfigurePolicy.canKeepSafeModeTrackOnRouteChange(
            wasBitPerfectMode = false,
            hasActivePreferredMixerOrPinnedRoute = false,
            isPreparing = false,
            hasActiveDecoder = true,
            hasHealthyAudioTrack = true,
        )
        assertTrue(canPauseInPlace)

        assertEquals(
            SafetyReconfigureAction.PAUSE_IN_PLACE_KEEP_TRACK,
            RouteChangeReconfigurePolicy.decideAction(
                commandType = SafetyEventType.AUTO_PAUSE,
                isActivelyPlayingOrPreparing = true,
                hasLoadedTrack = true,
                canPauseInPlaceOnSafeRoute = canPauseInPlace,
            ),
        )
        assertEquals(
            SafetyReconfigureAction.PAUSE_IN_PLACE_KEEP_TRACK,
            RouteChangeReconfigurePolicy.decideAction(
                commandType = SafetyEventType.AUTO_PAUSE,
                isActivelyPlayingOrPreparing = false,
                hasLoadedTrack = true,
                canPauseInPlaceOnSafeRoute = canPauseInPlace,
            ),
        )
    }

    @Test
    fun bitPerfectOrPreferredOrUnhealthyTrack_stillTearsDownOnAutoPause() {
        assertFalse(
            RouteChangeReconfigurePolicy.canKeepSafeModeTrackOnRouteChange(
                wasBitPerfectMode = true,
                hasActivePreferredMixerOrPinnedRoute = false,
                isPreparing = false,
                hasActiveDecoder = true,
                hasHealthyAudioTrack = true,
            ),
        )
        assertFalse(
            RouteChangeReconfigurePolicy.canKeepSafeModeTrackOnRouteChange(
                wasBitPerfectMode = false,
                hasActivePreferredMixerOrPinnedRoute = true,
                isPreparing = false,
                hasActiveDecoder = true,
                hasHealthyAudioTrack = true,
            ),
        )
        assertFalse(
            RouteChangeReconfigurePolicy.canKeepSafeModeTrackOnRouteChange(
                wasBitPerfectMode = false,
                hasActivePreferredMixerOrPinnedRoute = false,
                isPreparing = true,
                hasActiveDecoder = true,
                hasHealthyAudioTrack = true,
            ),
        )
        assertFalse(
            RouteChangeReconfigurePolicy.canKeepSafeModeTrackOnRouteChange(
                wasBitPerfectMode = false,
                hasActivePreferredMixerOrPinnedRoute = false,
                isPreparing = false,
                hasActiveDecoder = false,
                hasHealthyAudioTrack = true,
            ),
        )
        assertFalse(
            RouteChangeReconfigurePolicy.canKeepSafeModeTrackOnRouteChange(
                wasBitPerfectMode = false,
                hasActivePreferredMixerOrPinnedRoute = false,
                isPreparing = false,
                hasActiveDecoder = true,
                hasHealthyAudioTrack = false,
            ),
        )

        assertEquals(
            SafetyReconfigureAction.TEARDOWN_AND_REBIND_ON_PLAY,
            RouteChangeReconfigurePolicy.decideAction(
                commandType = SafetyEventType.AUTO_PAUSE,
                isActivelyPlayingOrPreparing = true,
                hasLoadedTrack = true,
                canPauseInPlaceOnSafeRoute = false,
            ),
        )
    }

    @Test
    fun computeRouteHeadRebase_handlesMonotonicResetAndAsyncHalReset() {
        // Case 1: HAL already reset playback head to 0 on route switch
        val resetRebase = RouteChangeReconfigurePolicy.computeRouteHeadRebase(
            seekBaseMs = 10_000L,
            headBaseFrames = 0L,
            countHeadFrames = 44_100L, // +1,000 ms played -> 11,000 ms
            totalFramesWrittenToTrack = 50_000L,
            observedHeadFrames = 0L,
            sampleRateHz = 44_100,
        )
        assertEquals(11_000L, resetRebase.seekBaseMs)
        assertEquals(0L, resetRebase.headBaseFrames)
        assertEquals(0L, resetRebase.totalFramesWrittenToTrack)
        assertEquals(0L, resetRebase.streamFramesWrittenToTrack)
        assertEquals(0L, resetRebase.countHeadFrames)
        assertFalse(resetRebase.armAsyncFlushResetGuard)

        // Case 2: HAL kept old head count initially and may reset asynchronously on next write
        val asyncGuardRebase = RouteChangeReconfigurePolicy.computeRouteHeadRebase(
            seekBaseMs = 20_000L,
            headBaseFrames = 10_000L,
            countHeadFrames = 106_000L, // +2,000 ms at 48 kHz -> 22,000 ms
            totalFramesWrittenToTrack = 118_000L,
            observedHeadFrames = 106_000L,
            sampleRateHz = 48_000,
        )
        assertEquals(22_000L, asyncGuardRebase.seekBaseMs)
        assertEquals(106_000L, asyncGuardRebase.headBaseFrames)
        assertEquals(118_000L, asyncGuardRebase.totalFramesWrittenToTrack)
        assertEquals(12_000L, asyncGuardRebase.streamFramesWrittenToTrack)
        assertEquals(106_000L, asyncGuardRebase.countHeadFrames)
        assertTrue(asyncGuardRebase.armAsyncFlushResetGuard)
    }
}
