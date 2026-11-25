package com.cangkir.bitperfectplayer.audio

/**
 * Direction of a genuine physical or HAL USB audio route transition tracked for flap detection.
 */
enum class UsbTransitionKind {
    ATTACH,
    DETACH,
}

/**
 * Policy disposition for an incoming output-route or noisy transition event.
 */
enum class RouteAutoPauseDisposition {
    /** Playing, starting playback, Opt-In Bit-Perfect mode, or safety dialog pending: enforce full auto-pause. */
    FULL_SAFETY_AUTO_PAUSE,

    /** Already paused/idle in Safe Mode without rapid flapping: refresh route/diagnostics immediately, no alert spam. */
    SILENT_ROUTE_REFRESH_IMMEDIATE,

    /** Already paused/idle in Safe Mode mid-flapping burst: refresh metadata/mixer state & coalesce UI diagnostics. */
    SILENT_ROUTE_REFRESH_COALESCED,
}

/**
 * Action chosen for a user or focus-gain `play()` request relative to recent USB flapping activity.
 */
enum class PlayResumeRouteDecision {
    PROCEED_NOW,
    DEFER_FOR_ROUTE_SETTLE,
    ABORT_ROUTE_UNSTABLE,
}

data class PlayResumeRouteGuardResult(val decision: PlayResumeRouteDecision, val deferDelayMs: Long = 0L)

/**
 * Pure-Kotlin hearing-safety vs. paused-state route flapping policy (zero `android.*` imports).
 *
 * Ensures rapid physical USB-C attach/detach bounce bursts while already `PAUSED` or `IDLE` in
 * [AudioOutputMode.SAFE_VOLUME_MODE] update active route metadata and clear stale preferred mixer
 * attributes cleanly without firing redundant `AUTO_PAUSE` alerts or diagnostic spam, while never
 * relaxing hearing-safety auto-pause during active playback, startup, pending safety warnings, or
 * [AudioOutputMode.BIT_PERFECT_OPT_IN].
 */
object UsbRouteStabilityPolicy {

    fun decideRouteAutoPauseDisposition(
        isActivelyPlayingOrStarting: Boolean,
        safetyMode: AudioOutputMode,
        isPendingSafetyWarning: Boolean,
        isRapidFlappingBurst: Boolean,
    ): RouteAutoPauseDisposition {
        if (isActivelyPlayingOrStarting ||
            safetyMode != AudioOutputMode.SAFE_VOLUME_MODE ||
            isPendingSafetyWarning
        ) {
            return RouteAutoPauseDisposition.FULL_SAFETY_AUTO_PAUSE
        }
        return if (isRapidFlappingBurst) {
            RouteAutoPauseDisposition.SILENT_ROUTE_REFRESH_COALESCED
        } else {
            RouteAutoPauseDisposition.SILENT_ROUTE_REFRESH_IMMEDIATE
        }
    }
}

/**
 * Sliding-window detector for rapid alternating USB attach/detach bounce storms (`loose USB-C port`)
 * governing paused Safe-Mode UI coalescing and bounded pre-`AudioTrack.play()` route settling.
 *
 * A single clean plug or single clean unplug (including delayed same-direction callbacks) never
 * enters flapping state (`PROCEED_NOW`).
 */
class UsbRouteStabilityTracker(
    private val flapWindowMs: Long = DEFAULT_FLAP_WINDOW_MS,
    private val flapSettleMs: Long = DEFAULT_FLAP_SETTLE_MS,
    private val maxDeferBudgetMs: Long = DEFAULT_MAX_DEFER_BUDGET_MS,
) {
    private data class UsbTransitionEvent(val timestampMs: Long, val kind: UsbTransitionKind)

    private val transitions = ArrayDeque<UsbTransitionEvent>()

    @Synchronized
    fun recordUsbTransition(kind: UsbTransitionKind, nowElapsedMs: Long): Boolean {
        pruneLocked(nowElapsedMs)
        transitions.addLast(UsbTransitionEvent(timestampMs = nowElapsedMs, kind = kind))
        while (transitions.size > MAX_TRACKED_TRANSITIONS) {
            transitions.removeFirst()
        }
        return isActivelyFlappingLocked(nowElapsedMs)
    }

    @Synchronized
    fun isActivelyFlapping(nowElapsedMs: Long): Boolean {
        pruneLocked(nowElapsedMs)
        return isActivelyFlappingLocked(nowElapsedMs)
    }

    @Synchronized
    fun evaluatePlayResume(nowElapsedMs: Long, firstPlayRequestElapsedMs: Long = nowElapsedMs): PlayResumeRouteGuardResult {
        pruneLocked(nowElapsedMs)
        if (!isActivelyFlappingLocked(nowElapsedMs)) {
            return PlayResumeRouteGuardResult(decision = PlayResumeRouteDecision.PROCEED_NOW, deferDelayMs = 0L)
        }
        val lastTimestampMs = transitions.last().timestampMs
        val remainingSettleMs = (flapSettleMs - (nowElapsedMs - lastTimestampMs)).coerceIn(1L, flapSettleMs)
        val alreadyWaitedMs = (nowElapsedMs - firstPlayRequestElapsedMs).coerceAtLeast(0L)
        return if (alreadyWaitedMs + remainingSettleMs > maxDeferBudgetMs) {
            PlayResumeRouteGuardResult(decision = PlayResumeRouteDecision.ABORT_ROUTE_UNSTABLE, deferDelayMs = 0L)
        } else {
            PlayResumeRouteGuardResult(
                decision = PlayResumeRouteDecision.DEFER_FOR_ROUTE_SETTLE,
                deferDelayMs = remainingSettleMs,
            )
        }
    }

    @Synchronized
    fun reset() {
        transitions.clear()
    }

    private fun pruneLocked(nowElapsedMs: Long) {
        val last = transitions.lastOrNull()
        if (last != null && nowElapsedMs < last.timestampMs) {
            transitions.clear()
            return
        }
        val cutoffMs = nowElapsedMs - flapWindowMs
        while (transitions.isNotEmpty() && transitions.first().timestampMs < cutoffMs) {
            transitions.removeFirst()
        }
    }

    private fun isActivelyFlappingLocked(nowElapsedMs: Long): Boolean {
        if (transitions.size < 2) return false
        val lastTimestampMs = transitions.last().timestampMs
        val sinceLastMs = nowElapsedMs - lastTimestampMs
        if (sinceLastMs !in 0L until flapSettleMs) return false
        var sawAttach = false
        var sawDetach = false
        for (event in transitions) {
            when (event.kind) {
                UsbTransitionKind.ATTACH -> sawAttach = true
                UsbTransitionKind.DETACH -> sawDetach = true
            }
            if (sawAttach && sawDetach) return true
        }
        return false
    }

    companion object {
        const val DEFAULT_FLAP_WINDOW_MS: Long = 1_500L
        const val DEFAULT_FLAP_SETTLE_MS: Long = 450L
        const val DEFAULT_MAX_DEFER_BUDGET_MS: Long = 900L
        const val DEFAULT_DIAGNOSTICS_COALESCE_MS: Long = 120L
        private const val MAX_TRACKED_TRANSITIONS: Int = 16
    }
}

/**
 * Lock-free-style monotonic generation guard enabling pause / safety `AUTO_PAUSE` / stop callers
 * to supersede any slow in-flight `AudioTrack.play()` or track reload without blocking behind
 * `controlExecutor` or `engineLock`.
 */
class PlayResumeAbortGuard {
    private var sequence: Long = 0L
    private var lastResumeRequestSeq: Long = 0L
    private var lastCancelOrPauseSeq: Long = 0L

    @Synchronized
    fun beginResumeAttempt(): Long {
        sequence++
        lastResumeRequestSeq = sequence
        return sequence
    }

    @Synchronized
    fun currentResumeAttemptSeq(): Long = lastResumeRequestSeq

    @Synchronized
    fun cancelInFlightResumes(): Long {
        sequence++
        lastCancelOrPauseSeq = sequence
        return sequence
    }

    @Synchronized
    fun isResumeAttemptValid(attemptSeq: Long): Boolean = attemptSeq > 0L && attemptSeq > lastCancelOrPauseSeq

    @Synchronized
    fun hasUncancelledResumeRequest(): Boolean = lastResumeRequestSeq > lastCancelOrPauseSeq
}
