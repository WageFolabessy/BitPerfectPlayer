package com.cangkir.bitperfectplayer.audio

import java.util.concurrent.atomic.AtomicLong

/**
 * Explicit lifecycle state machine guarding [MediaCodecAudioDecoder] against cross-thread
 * `flush()` / `dequeue*()` / `release()` races (pure Kotlin, zero `android.*` imports).
 */
enum class MediaCodecLifecycleState {
    EXECUTING,
    FLUSHING,
    STOPPING,
    RELEASED,
}

/**
 * Pure-Kotlin synchronized lifecycle guard enforcing single-owner `MediaCodec` state transitions
 * so `flush()`, `readPcm()`, and `close()` can never overlap illegally.
 */
class MediaCodecLifecycleGuard {
    private var currentState: MediaCodecLifecycleState = MediaCodecLifecycleState.EXECUTING

    @Synchronized
    fun state(): MediaCodecLifecycleState = currentState

    @Synchronized
    fun canDecode(): Boolean = currentState == MediaCodecLifecycleState.EXECUTING

    @Synchronized
    fun beginFlush(): Boolean {
        if (currentState != MediaCodecLifecycleState.EXECUTING) return false
        currentState = MediaCodecLifecycleState.FLUSHING
        return true
    }

    @Synchronized
    fun endFlush() {
        if (currentState == MediaCodecLifecycleState.FLUSHING) {
            currentState = MediaCodecLifecycleState.EXECUTING
        }
    }

    @Synchronized
    fun beginStop(): Boolean {
        if (currentState == MediaCodecLifecycleState.STOPPING || currentState == MediaCodecLifecycleState.RELEASED) {
            return false
        }
        currentState = MediaCodecLifecycleState.STOPPING
        return true
    }

    @Synchronized
    fun markReleased() {
        currentState = MediaCodecLifecycleState.RELEASED
    }

    @Synchronized
    fun isStoppedOrReleased(): Boolean =
        currentState == MediaCodecLifecycleState.STOPPING || currentState == MediaCodecLifecycleState.RELEASED
}

/**
 * Lock-free atomic pending-seek coalescer used to pass seek requests from UI/control threads
 * strictly to the single-owner decode loop thread (pure Kotlin, zero `android.*` imports).
 */
class PendingSeekCoalescer(private val backing: AtomicLong = AtomicLong(NO_PENDING_SEEK)) {

    fun requestSeek(positionMs: Long) {
        backing.set(positionMs.coerceAtLeast(0L))
    }

    fun hasPendingSeek(): Boolean = backing.get() >= 0L

    fun peekPendingSeek(): Long? = backing.get().takeIf { it >= 0L }

    fun pollPendingSeek(): Long? = backing.getAndSet(NO_PENDING_SEEK).takeIf { it >= 0L }

    fun clear() {
        backing.set(NO_PENDING_SEEK)
    }

    companion object {
        const val NO_PENDING_SEEK: Long = -1L
    }
}

/**
 * Pure-Kotlin policy governing single-owner seek dispatch, honest `isPlaying` reconciliation when
 * a decode worker terminates, and bounded automatic recovery from transient `MediaCodec` state
 * errors or invalidated `AudioTrack` handles during seek/hot-plug transitions.
 */
object PlaybackSeekAndRecoveryPolicy {

    const val MAX_AUTO_RECOVERY_ATTEMPTS: Int = 2
    const val MAX_ZERO_WRITE_STALL_MS: Long = 1_500L

    /**
     * Returns `true` when [AudioOutputEngine.seekTo] MUST defer execution to the live single-owner
     * decode thread via [PendingSeekCoalescer] rather than calling `decoder.seekToMs()` on the
     * control thread.
     */
    fun shouldDeferSeekToWorkerThread(isWorkerThreadAlive: Boolean): Boolean = isWorkerThreadAlive

    /**
     * Returns `true` when a seek issued while paused must merely store the target position (so the
     * next `play()` reopens and seeks cleanly) instead of touching a pending-reconfigure or absent
     * decoder.
     */
    fun shouldRecordPositionOnlyWhilePaused(hasPendingRouteReconfigure: Boolean, hasActiveDecoder: Boolean): Boolean =
        hasPendingRouteReconfigure || !hasActiveDecoder

    /**
     * Returns `true` when a transient codec state error (`IllegalStateException` during flush/route
     * churn) or dead `AudioTrack` (`ERROR_DEAD_OBJECT`) should be recovered internally by reopening
     * the decoder/track at the target position rather than surfacing a raw internal error banner.
     */
    fun shouldAttemptInternalRecovery(
        isGenerationCurrent: Boolean,
        isStoppingOrReleased: Boolean,
        isPaused: Boolean,
        isTransientRecoverableError: Boolean,
        priorRecoveryAttempts: Int,
    ): Boolean = isGenerationCurrent &&
        !isStoppingOrReleased &&
        !isPaused &&
        isTransientRecoverableError &&
        priorRecoveryAttempts < MAX_AUTO_RECOVERY_ATTEMPTS

    /**
     * Enforces honest `isPlaying` state: if the active decode worker thread terminates while the
     * engine is still on the same [expectedGeneration] and no recovery reload is queued, the
     * engine MUST NOT remain in [PlaybackStatus.PLAYING].
     */
    fun resolveStatusOnWorkerExit(
        currentStatus: PlaybackStatus,
        isGenerationCurrent: Boolean,
        isRecoveryScheduled: Boolean,
    ): PlaybackStatus {
        if (!isGenerationCurrent || isRecoveryScheduled) return currentStatus
        return if (currentStatus == PlaybackStatus.PLAYING) {
            PlaybackStatus.PAUSED
        } else {
            currentStatus
        }
    }
}
