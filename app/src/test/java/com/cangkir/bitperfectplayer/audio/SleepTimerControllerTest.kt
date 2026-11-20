package com.cangkir.bitperfectplayer.audio

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SleepTimerControllerTest {

    @Test
    fun safeModeCountdown_performsThreeSecondLinearFadeOutPausesAndRestoresUnityVolume() = runTest {
        val volumeHistory = mutableListOf<Float>()
        var pauseCalls = 0
        var completedPreset: SleepTimerPreset? = null

        val controller = SleepTimerController(
            scope = backgroundScope,
            isBitPerfectOptIn = { false },
            setSafeModeVolume = { volumeHistory.add(it) },
            pausePlayback = { pauseCalls++ },
            onTimerCompleted = { completedPreset = it },
        )

        // Start a 5-second countdown (2s coarse ticks + 3s linear fade)
        controller.startCustomCountdown(SleepTimerPreset.MIN_15, durationMs = 5_000L)
        assertTrue(controller.state.value.isActive)
        assertEquals("00:05", controller.state.value.compactBadgeLabel)

        // Advance 2 seconds: still above 3s fade window, zero volume changes yet
        advanceTimeBy(2_000L)
        runCurrent()
        assertTrue(volumeHistory.isEmpty())
        assertEquals(0, pauseCalls)
        assertEquals(3_000L, controller.state.value.remainingMs)

        // Advance 1.5 seconds into fade: volume smoothly attenuating in Safe Mode
        advanceTimeBy(1_500L)
        runCurrent()
        assertTrue(controller.state.value.isFadingOut)
        assertTrue(volumeHistory.isNotEmpty())
        assertTrue(volumeHistory.last() < 0.6f && volumeHistory.last() > 0.3f)
        assertEquals(0, pauseCalls)

        // Advance remaining 1.6 seconds to finish fade -> pauses and restores volume to 1.0f
        advanceTimeBy(1_600L)
        runCurrent()
        assertEquals(1, pauseCalls)
        assertEquals(SleepTimerPreset.MIN_15, completedPreset)
        assertFalse(controller.state.value.isActive)
        assertEquals(1.0f, volumeHistory.last(), 1e-6f)
        // Penultimate volume step reached 0.0f before pause + restore
        assertEquals(0.0f, volumeHistory[volumeHistory.lastIndex - 1], 1e-4f)
    }

    @Test
    fun bitPerfectModeCountdown_pausesCleanlyWithZeroVolumeScaling() = runTest {
        val volumeHistory = mutableListOf<Float>()
        var pauseCalls = 0
        var completedPreset: SleepTimerPreset? = null

        val controller = SleepTimerController(
            scope = backgroundScope,
            isBitPerfectOptIn = { true },
            setSafeModeVolume = { volumeHistory.add(it) },
            pausePlayback = { pauseCalls++ },
            onTimerCompleted = { completedPreset = it },
        )

        controller.startCustomCountdown(SleepTimerPreset.MIN_30, durationMs = 5_000L)
        advanceTimeBy(5_100L)
        runCurrent()

        assertEquals(1, pauseCalls)
        assertEquals(SleepTimerPreset.MIN_30, completedPreset)
        assertFalse(controller.state.value.isActive)
        // Mandatory Bit-Perfect invariant: NEVER call setSafeModeVolume in BIT_PERFECT_OPT_IN
        assertTrue("Expected zero volume scaling calls in Bit-Perfect Mode", volumeHistory.isEmpty())
    }

    @Test
    fun endOfCurrentTrackPreset_armsConsumesOnTrackCompletionAndPausesWithoutNextTrack() = runTest {
        var pauseCalls = 0
        val armedChanges = mutableListOf<Boolean>()
        var completedPreset: SleepTimerPreset? = null

        val controller = SleepTimerController(
            scope = backgroundScope,
            isBitPerfectOptIn = { false },
            setSafeModeVolume = { },
            pausePlayback = { pauseCalls++ },
            onEndOfTrackArmedChanged = { armedChanges.add(it) },
            onTimerCompleted = { completedPreset = it },
        )

        controller.setPreset(SleepTimerPreset.END_OF_TRACK)
        assertTrue(controller.state.value.isActive)
        assertTrue(controller.state.value.isEndOfTrackArmed)
        assertEquals("End", controller.state.value.compactBadgeLabel)
        assertEquals(listOf(true), armedChanges)

        // Track finishes -> consumes trigger once, pauses cleanly, resets to OFF
        assertTrue(controller.consumeEndOfTrackTrigger())
        assertEquals(1, pauseCalls)
        assertEquals(SleepTimerPreset.END_OF_TRACK, completedPreset)
        assertFalse(controller.state.value.isActive)
        assertEquals(listOf(true, false), armedChanges)

        // Subsequent completion does not trigger again
        assertFalse(controller.consumeEndOfTrackTrigger())
        assertEquals(1, pauseCalls)
    }

    @Test
    fun cancellingMidFadeInSafeMode_immediatelyRestoresUnityVolume() = runTest {
        val volumeHistory = mutableListOf<Float>()
        var pauseCalls = 0

        val controller = SleepTimerController(
            scope = backgroundScope,
            isBitPerfectOptIn = { false },
            setSafeModeVolume = { volumeHistory.add(it) },
            pausePlayback = { pauseCalls++ },
        )

        controller.startCustomCountdown(SleepTimerPreset.MIN_15, durationMs = 3_000L)
        advanceTimeBy(1_000L)
        runCurrent()
        assertTrue(controller.state.value.isFadingOut)
        assertTrue(volumeHistory.last() < 1.0f)

        // User cancels timer mid-fade -> volume immediately restored to 1.0f, never pauses
        controller.cancel()
        advanceTimeBy(5_000L)
        runCurrent()
        assertEquals(0, pauseCalls)
        assertFalse(controller.state.value.isActive)
        assertEquals(1.0f, volumeHistory.last(), 1e-6f)
    }
}
