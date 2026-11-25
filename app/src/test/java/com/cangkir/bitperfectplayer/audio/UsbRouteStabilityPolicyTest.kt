package com.cangkir.bitperfectplayer.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbRouteStabilityPolicyTest {

    private val internalSpeaker = RouteDeviceDescriptor(
        id = 1,
        name = "Internal Speaker",
        isSink = true,
        isUsbOutput = false,
        isExternalOutput = false,
    )

    @Test
    fun pausedSafeMode_usbFlappingBurst_coalescesWithoutAutoPauseAlerts() {
        val filter = AudioRouteEventFilter(coalesceWindowMs = 8_000L)
        val safety = AudioSafetyController()
        filter.initialize(internalSpeaker)
        filter.onDevicesAdded(listOf(internalSpeaker), preferredActiveAfterAdd = internalSpeaker, nowElapsedMs = 100L)
        safety.syncInitialOutputDevice(internalSpeaker.name, isUsbAudio = false)

        val tracker = UsbRouteStabilityTracker()
        val dispositions = mutableListOf<RouteAutoPauseDisposition>()
        val attachDetachTimes = listOf(
            1_000L to UsbTransitionKind.ATTACH,
            1_003L to UsbTransitionKind.DETACH,
            1_006L to UsbTransitionKind.ATTACH,
            1_009L to UsbTransitionKind.DETACH,
            1_012L to UsbTransitionKind.ATTACH,
            1_015L to UsbTransitionKind.DETACH,
        )

        for ((ts, kind) in attachDetachTimes) {
            val decision = if (kind == UsbTransitionKind.ATTACH) {
                filter.onUsbAudioAttachedEarly("USB-C DAC", ts)
            } else {
                filter.onUsbAudioDetachedEarly(ts, internalSpeaker)
            }
            val expectedClass = if (kind == UsbTransitionKind.ATTACH) {
                RouteFilterDecision.DeviceConnected::class.java
            } else {
                RouteFilterDecision.UsbDisconnected::class.java
            }
            assertEquals(expectedClass, decision.javaClass)

            val flapping = tracker.recordUsbTransition(kind, ts)
            val disposition = UsbRouteStabilityPolicy.decideRouteAutoPauseDisposition(
                isActivelyPlayingOrStarting = false,
                safetyMode = safety.state().mode,
                isPendingSafetyWarning = safety.state().isPendingSafetyWarning,
                isRapidFlappingBurst = flapping,
            )
            dispositions += disposition

            // Simulate silent metadata refresh without triggering AUTO_PAUSE alerts
            val targetName = if (kind == UsbTransitionKind.ATTACH) "USB-C DAC" else internalSpeaker.name
            safety.syncActiveOutputDeviceMetadata(targetName, isUsbAudio = kind == UsbTransitionKind.ATTACH)
        }

        assertEquals(RouteAutoPauseDisposition.SILENT_ROUTE_REFRESH_IMMEDIATE, dispositions.first())
        assertTrue(dispositions.drop(1).all { it == RouteAutoPauseDisposition.SILENT_ROUTE_REFRESH_COALESCED })
        assertTrue(dispositions.none { it == RouteAutoPauseDisposition.FULL_SAFETY_AUTO_PAUSE })
        assertEquals(internalSpeaker, filter.currentActiveDevice())
        assertFalse(safety.state().isUsbAudioConnected)
        assertEquals(null, safety.state().lastAutoPauseReason)
    }

    @Test
    fun activePlayback_orPreparing_orBitPerfect_orPendingWarning_alwaysEnforcesFullSafetyAutoPause() {
        assertEquals(
            RouteAutoPauseDisposition.FULL_SAFETY_AUTO_PAUSE,
            UsbRouteStabilityPolicy.decideRouteAutoPauseDisposition(
                isActivelyPlayingOrStarting = true,
                safetyMode = AudioOutputMode.SAFE_VOLUME_MODE,
                isPendingSafetyWarning = false,
                isRapidFlappingBurst = false,
            ),
        )
        assertEquals(
            RouteAutoPauseDisposition.FULL_SAFETY_AUTO_PAUSE,
            UsbRouteStabilityPolicy.decideRouteAutoPauseDisposition(
                isActivelyPlayingOrStarting = true,
                safetyMode = AudioOutputMode.SAFE_VOLUME_MODE,
                isPendingSafetyWarning = false,
                isRapidFlappingBurst = true,
            ),
        )
        assertEquals(
            RouteAutoPauseDisposition.FULL_SAFETY_AUTO_PAUSE,
            UsbRouteStabilityPolicy.decideRouteAutoPauseDisposition(
                isActivelyPlayingOrStarting = false,
                safetyMode = AudioOutputMode.BIT_PERFECT_OPT_IN,
                isPendingSafetyWarning = false,
                isRapidFlappingBurst = true,
            ),
        )
        assertEquals(
            RouteAutoPauseDisposition.FULL_SAFETY_AUTO_PAUSE,
            UsbRouteStabilityPolicy.decideRouteAutoPauseDisposition(
                isActivelyPlayingOrStarting = false,
                safetyMode = AudioOutputMode.SAFE_VOLUME_MODE,
                isPendingSafetyWarning = true,
                isRapidFlappingBurst = false,
            ),
        )
    }

    @Test
    fun singleCleanPlugOrUnplug_andSameDirectionCallbacks_neverTriggerFlappingOrPlayDeferral() {
        val plugOnly = UsbRouteStabilityTracker()
        assertFalse(plugOnly.recordUsbTransition(UsbTransitionKind.ATTACH, 1_000L))
        assertFalse(plugOnly.recordUsbTransition(UsbTransitionKind.ATTACH, 1_050L))
        assertFalse(plugOnly.isActivelyFlapping(1_060L))
        assertEquals(
            PlayResumeRouteGuardResult(PlayResumeRouteDecision.PROCEED_NOW, 0L),
            plugOnly.evaluatePlayResume(nowElapsedMs = 1_060L, firstPlayRequestElapsedMs = 1_060L),
        )

        val unplugOnly = UsbRouteStabilityTracker()
        assertFalse(unplugOnly.recordUsbTransition(UsbTransitionKind.DETACH, 2_000L))
        assertFalse(unplugOnly.recordUsbTransition(UsbTransitionKind.DETACH, 2_030L))
        assertFalse(unplugOnly.isActivelyFlapping(2_040L))
        assertEquals(
            PlayResumeRouteGuardResult(PlayResumeRouteDecision.PROCEED_NOW, 0L),
            unplugOnly.evaluatePlayResume(nowElapsedMs = 2_040L, firstPlayRequestElapsedMs = 2_040L),
        )
    }

    @Test
    fun rapidUsbBounceStorm_defersPlayUntilSettled_andAbortsAfterDeferBudgetExhausted() {
        val tracker = UsbRouteStabilityTracker(
            flapWindowMs = 1_500L,
            flapSettleMs = 450L,
            maxDeferBudgetMs = 900L,
        )
        assertFalse(tracker.recordUsbTransition(UsbTransitionKind.ATTACH, 1_000L))
        assertTrue(tracker.recordUsbTransition(UsbTransitionKind.DETACH, 1_004L))
        assertTrue(tracker.recordUsbTransition(UsbTransitionKind.ATTACH, 1_008L))

        val initialPlayGuard = tracker.evaluatePlayResume(
            nowElapsedMs = 1_050L,
            firstPlayRequestElapsedMs = 1_050L,
        )
        assertEquals(PlayResumeRouteDecision.DEFER_FOR_ROUTE_SETTLE, initialPlayGuard.decision)
        assertEquals(408L, initialPlayGuard.deferDelayMs)

        // Once quiet for 450 ms after the last transition (1,008 + 450 = 1,458), play proceeds immediately
        assertFalse(tracker.isActivelyFlapping(1_458L))
        assertEquals(
            PlayResumeRouteGuardResult(PlayResumeRouteDecision.PROCEED_NOW, 0L),
            tracker.evaluatePlayResume(nowElapsedMs = 1_458L, firstPlayRequestElapsedMs = 1_050L),
        )

        // Continuous flapping exceeding the 900 ms bounded defer budget aborts cleanly instead of starving
        tracker.reset()
        tracker.recordUsbTransition(UsbTransitionKind.ATTACH, 1_980L)
        tracker.recordUsbTransition(UsbTransitionKind.DETACH, 1_990L)
        assertEquals(
            PlayResumeRouteDecision.DEFER_FOR_ROUTE_SETTLE,
            tracker.evaluatePlayResume(nowElapsedMs = 2_000L, firstPlayRequestElapsedMs = 2_000L).decision,
        )
        tracker.recordUsbTransition(UsbTransitionKind.ATTACH, 2_450L)
        assertEquals(
            PlayResumeRouteDecision.DEFER_FOR_ROUTE_SETTLE,
            tracker.evaluatePlayResume(nowElapsedMs = 2_460L, firstPlayRequestElapsedMs = 2_000L).decision,
        )
        tracker.recordUsbTransition(UsbTransitionKind.DETACH, 2_500L)
        val exhaustedGuard = tracker.evaluatePlayResume(
            nowElapsedMs = 2_510L,
            firstPlayRequestElapsedMs = 2_000L,
        )
        assertEquals(PlayResumeRouteGuardResult(PlayResumeRouteDecision.ABORT_ROUTE_UNSTABLE, 0L), exhaustedGuard)
    }

    @Test
    fun playResumeAbortGuard_cancelsInFlightPlayOnAutoPause_andAllowsFreshPlayAfterward() {
        val guard = PlayResumeAbortGuard()
        assertFalse(guard.hasUncancelledResumeRequest())
        assertFalse(guard.isResumeAttemptValid(0L))

        val slowAttemptSeq = guard.beginResumeAttempt()
        assertTrue(guard.isResumeAttemptValid(slowAttemptSeq))
        assertTrue(guard.hasUncancelledResumeRequest())
        assertEquals(slowAttemptSeq, guard.currentResumeAttemptSeq())

        // AUTO_PAUSE or user Pause arrives while controlExecutor is blocked inside slow AudioTrack.play()
        guard.cancelInFlightResumes()
        assertFalse(guard.isResumeAttemptValid(slowAttemptSeq))
        assertFalse(guard.hasUncancelledResumeRequest())

        // Subsequent fresh user Play receives a valid monotonic generation while old attempt stays invalid
        val freshAttemptSeq = guard.beginResumeAttempt()
        assertTrue(freshAttemptSeq > slowAttemptSeq)
        assertTrue(guard.isResumeAttemptValid(freshAttemptSeq))
        assertFalse(guard.isResumeAttemptValid(slowAttemptSeq))
        assertTrue(guard.hasUncancelledResumeRequest())
    }
}
