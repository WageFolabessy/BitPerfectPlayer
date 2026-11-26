package com.cangkir.bitperfectplayer.audio

/**
 * Pure-Kotlin playback position, hardware playback-head rebase, async-flush reconciliation, and
 * active-play-time accumulation tracker (zero `android.*` imports).
 *
 * Keeps all frame-to-millisecond arithmetic deterministic and unit-testable independently of
 * Android `AudioTrack` hardware.
 */
internal class PlaybackPositionTracker(sampleRateHz: Int, durationMs: Long, initialSeekMs: Long, initialHeadFrames: Long) {
    var sampleRateHz: Int = sampleRateHz.coerceAtLeast(1)
        private set
    var durationMs: Long = durationMs.coerceAtLeast(0L)
        private set
    var seekBaseMs: Long = initialSeekMs.coerceAtLeast(0L)
    var streamOriginMs: Long = seekBaseMs
    var headBaseFrames: Long = initialHeadFrames.coerceAtLeast(0L)
    var totalFramesWrittenToTrack: Long = headBaseFrames
    var streamFramesWrittenToTrack: Long = 0L
    var countHeadFrames: Long = headBaseFrames
    var pendingPlayCountMs: Long = 0L
    var pendingFlushResetGuard: Boolean = false

    /**
     * If `AudioTrack.flush()` completed asynchronously after `play()` (dropping [hwHead] below
     * the stale pre-flush [headBaseFrames]), rebases all track-head counters cleanly to [hwHead].
     * Never fires during gapless transitions ([pendingFlushResetGuard] == `false`).
     */
    fun reconcileAsyncFlushReset(hwHead: Long) {
        if (!pendingFlushResetGuard) return
        if (hwHead < headBaseFrames) {
            val safeHead = hwHead.coerceAtLeast(0L)
            headBaseFrames = safeHead
            totalFramesWrittenToTrack = safeHead + streamFramesWrittenToTrack
            countHeadFrames = safeHead
            pendingFlushResetGuard = false
        } else if (hwHead > headBaseFrames) {
            // HAL advanced monotonically past headBaseFrames without resetting to 0.
            pendingFlushResetGuard = false
        }
    }

    /**
     * Computes clamped track position in milliseconds from the hardware playback head [hwHead],
     * reconciling any pending asynchronous flush reset first.
     */
    fun computePlaybackPositionMs(hwHead: Long): Long {
        reconcileAsyncFlushReset(hwHead)
        val deltaFrames = (hwHead - headBaseFrames).coerceAtLeast(0L)
        val computedPos = seekBaseMs + (deltaFrames * 1000L) / sampleRateHz.toLong()
        return if (durationMs > 0L) computedPos.coerceIn(0L, durationMs) else computedPos
    }

    /**
     * Accumulates real active playback elapsed time from [headFrames] deltas and returns the batch
     * of milliseconds ready to emit (either `>= 500 ms` or any non-zero amount when [forceFlush] is
     * `true`), or `0L` when nothing should be emitted yet.
     */
    fun accumulateActivePlayTimeMs(headFrames: Long, forceFlush: Boolean): Long {
        reconcileAsyncFlushReset(headFrames)
        val stepFrames = (headFrames - countHeadFrames).coerceAtLeast(0L)
        if (stepFrames > 0L) {
            val stepMs = (stepFrames * 1000L) / sampleRateHz.toLong()
            if (stepMs > 0L) {
                val consumedFrames = (stepMs * sampleRateHz.toLong()) / 1000L
                countHeadFrames += consumedFrames
                pendingPlayCountMs += stepMs
            }
        }
        if ((forceFlush && pendingPlayCountMs > 0L) || pendingPlayCountMs >= ACTIVE_PLAY_EMIT_THRESHOLD_MS) {
            val toEmit = pendingPlayCountMs
            pendingPlayCountMs = 0L
            return toEmit
        }
        return 0L
    }

    /**
     * Records [framesWritten] newly written frames to the sink and returns the frame offset at
     * which those frames began.
     */
    fun recordFramesWritten(framesWritten: Long, advanceStreamCursor: Boolean = true): Long {
        val frameStart = totalFramesWrittenToTrack
        if (framesWritten > 0L) {
            totalFramesWrittenToTrack += framesWritten
            if (advanceStreamCursor) {
                streamFramesWrittenToTrack += framesWritten
            }
        }
        return frameStart
    }

    fun resetAfterSeek(actualSeekMs: Long, headFrames: Long) {
        val safeSeekMs = actualSeekMs.coerceAtLeast(0L)
        val safeHead = headFrames.coerceAtLeast(0L)
        seekBaseMs = safeSeekMs
        streamOriginMs = safeSeekMs
        streamFramesWrittenToTrack = 0L
        pendingFlushResetGuard = safeHead > 0L
        headBaseFrames = safeHead
        totalFramesWrittenToTrack = safeHead
        countHeadFrames = safeHead
    }

    fun applyRouteHeadRebase(observedHeadFrames: Long): RouteHeadRebasePlan {
        val rebase = RouteChangeReconfigurePolicy.computeRouteHeadRebase(
            seekBaseMs = seekBaseMs,
            headBaseFrames = headBaseFrames,
            countHeadFrames = countHeadFrames,
            totalFramesWrittenToTrack = totalFramesWrittenToTrack,
            observedHeadFrames = observedHeadFrames,
            sampleRateHz = sampleRateHz,
        )
        seekBaseMs = rebase.seekBaseMs
        streamOriginMs = rebase.seekBaseMs
        headBaseFrames = rebase.headBaseFrames
        totalFramesWrittenToTrack = rebase.totalFramesWrittenToTrack
        streamFramesWrittenToTrack = rebase.streamFramesWrittenToTrack
        countHeadFrames = rebase.countHeadFrames
        pendingFlushResetGuard = rebase.armAsyncFlushResetGuard
        return rebase
    }

    fun resumeAfterNormalPause(headFrames: Long) {
        reconcileAsyncFlushReset(headFrames)
        countHeadFrames = maxOf(headFrames, headBaseFrames)
    }

    fun transitionToSeamlessNextTrack(nextSampleRateHz: Int, nextDurationMs: Long, currentHeadFrames: Long) {
        sampleRateHz = nextSampleRateHz.coerceAtLeast(1)
        durationMs = nextDurationMs.coerceAtLeast(0L)
        seekBaseMs = 0L
        streamOriginMs = 0L
        streamFramesWrittenToTrack = 0L
        pendingFlushResetGuard = false
        // Track B begins playing at the exact hardware frame count already queued for Track A.
        headBaseFrames = totalFramesWrittenToTrack
        countHeadFrames = maxOf(currentHeadFrames, headBaseFrames)
    }

    fun resetForRebuiltTrack(newSampleRateHz: Int, newDurationMs: Long, currentPositionMs: Long, headFrames: Long) {
        val safeHead = headFrames.coerceAtLeast(0L)
        sampleRateHz = newSampleRateHz.coerceAtLeast(1)
        durationMs = newDurationMs.coerceAtLeast(0L)
        seekBaseMs = currentPositionMs.coerceAtLeast(0L)
        streamOriginMs = seekBaseMs
        streamFramesWrittenToTrack = 0L
        pendingFlushResetGuard = false
        headBaseFrames = safeHead
        totalFramesWrittenToTrack = safeHead
        countHeadFrames = safeHead
    }

    fun resetForScratchHeadOrigin(headOrigin: Long, currentTrackWrittenFrame: Long) {
        pendingFlushResetGuard = false
        headBaseFrames = headOrigin
        totalFramesWrittenToTrack = currentTrackWrittenFrame
        countHeadFrames = headOrigin
    }

    fun anchorAfterScratchExit(targetMs: Long, currentHeadFrames: Long) {
        seekBaseMs = targetMs.coerceAtLeast(0L)
        headBaseFrames = totalFramesWrittenToTrack
        countHeadFrames = maxOf(currentHeadFrames, headBaseFrames)
    }

    companion object {
        const val ACTIVE_PLAY_EMIT_THRESHOLD_MS = 500L
    }
}
