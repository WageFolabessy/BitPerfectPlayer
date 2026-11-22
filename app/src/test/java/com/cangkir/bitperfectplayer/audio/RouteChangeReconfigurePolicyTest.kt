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
}
