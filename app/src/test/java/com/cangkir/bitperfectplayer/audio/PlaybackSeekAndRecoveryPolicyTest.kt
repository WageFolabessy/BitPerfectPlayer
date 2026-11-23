package com.cangkir.bitperfectplayer.audio

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSeekAndRecoveryPolicyTest {

    @Test
    fun pendingSeekCoalescer_coalescesRapidMultiThreadSeeksAndConsumesAtomically() {
        val coalescer = PendingSeekCoalescer()
        assertFalse(coalescer.hasPendingSeek())
        assertNull(coalescer.pollPendingSeek())

        // Negative values clamp cleanly to 0 ms
        coalescer.requestSeek(-250L)
        assertTrue(coalescer.hasPendingSeek())
        assertEquals(0L, coalescer.peekPendingSeek())
        assertEquals(0L, coalescer.pollPendingSeek())
        assertNull(coalescer.pollPendingSeek())

        // Rapid sequential seeks coalesce strictly to the latest target position
        coalescer.requestSeek(12_000L)
        coalescer.requestSeek(45_000L)
        coalescer.requestSeek(91_500L)
        assertEquals(91_500L, coalescer.pollPendingSeek())
        assertFalse(coalescer.hasPendingSeek())

        // Concurrent threads hammering requestSeek never throw or lose the valid non-negative invariant
        val pool = Executors.newFixedThreadPool(4)
        val latch = CountDownLatch(4)
        try {
            repeat(4) { worker ->
                pool.execute {
                    try {
                        for (step in 1..250) {
                            coalescer.requestSeek((worker * 1_000L) + step)
                        }
                    } finally {
                        latch.countDown()
                    }
                }
            }
            assertTrue(latch.await(5, TimeUnit.SECONDS))
            val latest = coalescer.pollPendingSeek()
            assertTrue("Expected coalesced seek target >= 1", latest != null && latest >= 1L)
            assertNull(coalescer.pollPendingSeek())
        } finally {
            pool.shutdownNow()
        }

        // clear() discards any unconsumed seek cleanly (e.g. on track switch)
        coalescer.requestSeek(30_000L)
        coalescer.clear()
        assertFalse(coalescer.hasPendingSeek())
        assertNull(coalescer.pollPendingSeek())
    }

    @Test
    fun mediaCodecLifecycleGuard_enforcesSingleOwnerExecutingFlushingStoppingReleasedTransitions() {
        val guard = MediaCodecLifecycleGuard()
        assertEquals(MediaCodecLifecycleState.EXECUTING, guard.state())
        assertTrue(guard.canDecode())
        assertFalse(guard.isStoppedOrReleased())

        // Entering FLUSHING blocks decode and blocks nested/concurrent flush calls
        assertTrue(guard.beginFlush())
        assertEquals(MediaCodecLifecycleState.FLUSHING, guard.state())
        assertFalse(guard.canDecode())
        assertFalse(guard.beginFlush())

        // Completing flush returns cleanly to EXECUTING
        guard.endFlush()
        assertEquals(MediaCodecLifecycleState.EXECUTING, guard.state())
        assertTrue(guard.canDecode())

        // Stopping is idempotent and blocks any subsequent decode or flush
        assertTrue(guard.beginStop())
        assertFalse(guard.beginStop())
        assertTrue(guard.isStoppedOrReleased())
        assertFalse(guard.canDecode())
        assertFalse(guard.beginFlush())

        // Releasing permanently locks the guard in RELEASED
        guard.markReleased()
        assertEquals(MediaCodecLifecycleState.RELEASED, guard.state())
        assertTrue(guard.isStoppedOrReleased())
        assertFalse(guard.beginStop())
        assertFalse(guard.canDecode())
        assertFalse(guard.beginFlush())
    }

    @Test
    fun seekDispatchPolicy_defersToLiveWorkerAndRecordsPausedSeekWhenPendingReconfigure() {
        assertTrue(PlaybackSeekAndRecoveryPolicy.shouldDeferSeekToWorkerThread(isWorkerThreadAlive = true))
        assertFalse(PlaybackSeekAndRecoveryPolicy.shouldDeferSeekToWorkerThread(isWorkerThreadAlive = false))

        assertTrue(
            PlaybackSeekAndRecoveryPolicy.shouldRecordPositionOnlyWhilePaused(
                hasPendingRouteReconfigure = true,
                hasActiveDecoder = true,
            ),
        )
        assertTrue(
            PlaybackSeekAndRecoveryPolicy.shouldRecordPositionOnlyWhilePaused(
                hasPendingRouteReconfigure = false,
                hasActiveDecoder = false,
            ),
        )
        assertFalse(
            PlaybackSeekAndRecoveryPolicy.shouldRecordPositionOnlyWhilePaused(
                hasPendingRouteReconfigure = false,
                hasActiveDecoder = true,
            ),
        )
    }

    @Test
    fun internalRecoveryPolicy_isBoundedAndRejectsPausedStaleOrNonRecoverableFailures() {
        assertTrue(
            PlaybackSeekAndRecoveryPolicy.shouldAttemptInternalRecovery(
                isGenerationCurrent = true,
                isStoppingOrReleased = false,
                isPaused = false,
                isTransientRecoverableError = true,
                priorRecoveryAttempts = 0,
            ),
        )
        assertTrue(
            PlaybackSeekAndRecoveryPolicy.shouldAttemptInternalRecovery(
                isGenerationCurrent = true,
                isStoppingOrReleased = false,
                isPaused = false,
                isTransientRecoverableError = true,
                priorRecoveryAttempts = PlaybackSeekAndRecoveryPolicy.MAX_AUTO_RECOVERY_ATTEMPTS - 1,
            ),
        )
        // Bounded: never loop endlessly after MAX_AUTO_RECOVERY_ATTEMPTS
        assertFalse(
            PlaybackSeekAndRecoveryPolicy.shouldAttemptInternalRecovery(
                isGenerationCurrent = true,
                isStoppingOrReleased = false,
                isPaused = false,
                isTransientRecoverableError = true,
                priorRecoveryAttempts = PlaybackSeekAndRecoveryPolicy.MAX_AUTO_RECOVERY_ATTEMPTS,
            ),
        )
        // Never recover for stale track generation, paused state, shutdown, or hard decode errors
        assertFalse(
            PlaybackSeekAndRecoveryPolicy.shouldAttemptInternalRecovery(
                isGenerationCurrent = false,
                isStoppingOrReleased = false,
                isPaused = false,
                isTransientRecoverableError = true,
                priorRecoveryAttempts = 0,
            ),
        )
        assertFalse(
            PlaybackSeekAndRecoveryPolicy.shouldAttemptInternalRecovery(
                isGenerationCurrent = true,
                isStoppingOrReleased = true,
                isPaused = false,
                isTransientRecoverableError = true,
                priorRecoveryAttempts = 0,
            ),
        )
        assertFalse(
            PlaybackSeekAndRecoveryPolicy.shouldAttemptInternalRecovery(
                isGenerationCurrent = true,
                isStoppingOrReleased = false,
                isPaused = true,
                isTransientRecoverableError = true,
                priorRecoveryAttempts = 0,
            ),
        )
        assertFalse(
            PlaybackSeekAndRecoveryPolicy.shouldAttemptInternalRecovery(
                isGenerationCurrent = true,
                isStoppingOrReleased = false,
                isPaused = false,
                isTransientRecoverableError = false,
                priorRecoveryAttempts = 0,
            ),
        )
    }

    @Test
    fun resolveStatusOnWorkerExit_enforcesHonestIsPlayingWhenWorkerTerminates() {
        // Worker exited unexpectedly on active generation with no recovery queued -> must drop PLAYING to PAUSED
        assertEquals(
            PlaybackStatus.PAUSED,
            PlaybackSeekAndRecoveryPolicy.resolveStatusOnWorkerExit(
                currentStatus = PlaybackStatus.PLAYING,
                isGenerationCurrent = true,
                isRecoveryScheduled = false,
            ),
        )
        // If recovery is scheduled or a newer track generation already took over, do not clobber PLAYING
        assertEquals(
            PlaybackStatus.PLAYING,
            PlaybackSeekAndRecoveryPolicy.resolveStatusOnWorkerExit(
                currentStatus = PlaybackStatus.PLAYING,
                isGenerationCurrent = true,
                isRecoveryScheduled = true,
            ),
        )
        assertEquals(
            PlaybackStatus.PLAYING,
            PlaybackSeekAndRecoveryPolicy.resolveStatusOnWorkerExit(
                currentStatus = PlaybackStatus.PLAYING,
                isGenerationCurrent = false,
                isRecoveryScheduled = false,
            ),
        )
        // Non-PLAYING statuses remain untouched
        assertEquals(
            PlaybackStatus.COMPLETED,
            PlaybackSeekAndRecoveryPolicy.resolveStatusOnWorkerExit(
                currentStatus = PlaybackStatus.COMPLETED,
                isGenerationCurrent = true,
                isRecoveryScheduled = false,
            ),
        )
        assertEquals(
            PlaybackStatus.ERROR,
            PlaybackSeekAndRecoveryPolicy.resolveStatusOnWorkerExit(
                currentStatus = PlaybackStatus.ERROR,
                isGenerationCurrent = true,
                isRecoveryScheduled = false,
            ),
        )
    }
}
