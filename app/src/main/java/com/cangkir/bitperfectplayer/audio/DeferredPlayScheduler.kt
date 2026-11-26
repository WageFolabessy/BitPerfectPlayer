package com.cangkir.bitperfectplayer.audio

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Internal coordinator managing bounded USB route-flapping Play deferral, coalesced diagnostic
 * refreshes during paused Safe-Mode route churn, and lock-free [PlayResumeAbortGuard] tokens.
 */
internal class DeferredPlayScheduler(
    private val callbackDispatcher: EngineCallbackDispatcher,
    private val clock: EngineClock,
    private val releasedFlag: AtomicBoolean,
    private val routeControl: AudioRouteControl,
    private val metrics: EnginePlaybackMetrics,
    private val onPublishDiagnostics: () -> Unit,
) {
    private val abortGuard = PlayResumeAbortGuard()
    private val deferredLock = Any()
    private var deferredPlayRunnable: Runnable? = null
    private var deferredPlayAttemptSeq: Long = 0L
    private var deferredPlayFirstRequestMs: Long = 0L

    private val coalescedDiagnosticsRunnable = Runnable {
        if (!releasedFlag.get()) {
            onPublishDiagnostics()
        }
    }

    fun beginResumeAttempt(): Long = abortGuard.beginResumeAttempt()

    fun cancelInFlightResumes() = abortGuard.cancelInFlightResumes()

    fun currentResumeAttemptSeq(): Long = abortGuard.currentResumeAttemptSeq()

    fun isResumeAttemptValid(attemptSeq: Long): Boolean = abortGuard.isResumeAttemptValid(attemptSeq)

    fun hasDeferredPlayPending(): Boolean = synchronized(deferredLock) {
        deferredPlayRunnable != null
    }

    fun evaluateAndDispatchPlay(nowMs: Long, onProceedNow: () -> Unit, onDeferredWaiting: () -> Unit, onAborted: () -> Unit) {
        val attemptSeq = abortGuard.beginResumeAttempt()
        val firstRequestMs = activeDeferredFirstRequestMs(nowMs)
        dispatchPlayRouteDecision(
            attemptSeq = attemptSeq,
            firstRequestMs = firstRequestMs,
            nowMs = nowMs,
            isInitialRequest = true,
            onProceedNow = onProceedNow,
            onDeferredWaiting = onDeferredWaiting,
            onAborted = onAborted,
        )
    }

    fun cancelDeferredPlay() {
        val toCancel = synchronized(deferredLock) {
            val prev = deferredPlayRunnable
            deferredPlayRunnable = null
            deferredPlayAttemptSeq = 0L
            deferredPlayFirstRequestMs = 0L
            prev
        }
        if (toCancel != null) {
            callbackDispatcher.removeCallbacks(toCancel)
        }
    }

    fun scheduleCoalescedDiagnostics(delayMs: Long = UsbRouteStabilityTracker.DEFAULT_DIAGNOSTICS_COALESCE_MS) {
        callbackDispatcher.removeCallbacks(coalescedDiagnosticsRunnable)
        callbackDispatcher.postDelayed(coalescedDiagnosticsRunnable, delayMs)
    }

    fun cancelCoalescedDiagnostics() {
        callbackDispatcher.removeCallbacks(coalescedDiagnosticsRunnable)
    }

    private fun activeDeferredFirstRequestMs(fallbackNowMs: Long): Long = synchronized(deferredLock) {
        if (deferredPlayRunnable != null && deferredPlayFirstRequestMs > 0L) {
            deferredPlayFirstRequestMs
        } else {
            fallbackNowMs
        }
    }

    private fun dispatchPlayRouteDecision(
        attemptSeq: Long,
        firstRequestMs: Long,
        nowMs: Long,
        isInitialRequest: Boolean,
        onProceedNow: () -> Unit,
        onDeferredWaiting: () -> Unit,
        onAborted: () -> Unit,
    ) {
        val routeGuard = routeControl.evaluatePlayResume(
            nowElapsedMs = nowMs,
            firstPlayRequestElapsedMs = firstRequestMs,
        )
        when (routeGuard.decision) {
            PlayResumeRouteDecision.PROCEED_NOW -> {
                if (isInitialRequest) cancelDeferredPlay()
                onProceedNow()
            }
            PlayResumeRouteDecision.DEFER_FOR_ROUTE_SETTLE -> {
                if (isInitialRequest) onDeferredWaiting()
                metrics.logPlayDeferredForRouteFlap(routeGuard.deferDelayMs)
                scheduleDeferredPlay(
                    attemptSeq = attemptSeq,
                    firstRequestMs = firstRequestMs,
                    delayMs = routeGuard.deferDelayMs,
                    onProceedNow = onProceedNow,
                    onAborted = onAborted,
                )
            }
            PlayResumeRouteDecision.ABORT_ROUTE_UNSTABLE -> {
                if (isInitialRequest) cancelDeferredPlay()
                abortGuard.cancelInFlightResumes()
                metrics.logPlayAbortedForRouteFlap((nowMs - firstRequestMs).coerceAtLeast(0L))
                onAborted()
            }
        }
    }

    private fun scheduleDeferredPlay(
        attemptSeq: Long,
        firstRequestMs: Long,
        delayMs: Long,
        onProceedNow: () -> Unit,
        onAborted: () -> Unit,
    ) {
        val holder = arrayOfNulls<Runnable>(1)
        val runnable = Runnable {
            val self = holder[0] ?: return@Runnable
            executeDeferredPlayIfStillValid(self, attemptSeq, firstRequestMs, onProceedNow, onAborted)
        }
        holder[0] = runnable
        synchronized(deferredLock) {
            deferredPlayRunnable?.let { callbackDispatcher.removeCallbacks(it) }
            deferredPlayRunnable = runnable
            deferredPlayAttemptSeq = attemptSeq
            deferredPlayFirstRequestMs = firstRequestMs
        }
        callbackDispatcher.postDelayed(runnable, delayMs.coerceAtLeast(1L))
    }

    private fun executeDeferredPlayIfStillValid(
        expectedRunnable: Runnable,
        attemptSeq: Long,
        firstRequestMs: Long,
        onProceedNow: () -> Unit,
        onAborted: () -> Unit,
    ) {
        val ownsToken = synchronized(deferredLock) {
            val matches = deferredPlayRunnable === expectedRunnable && deferredPlayAttemptSeq == attemptSeq
            if (matches) {
                deferredPlayRunnable = null
                deferredPlayAttemptSeq = 0L
                deferredPlayFirstRequestMs = 0L
            }
            matches
        }
        if (!ownsToken || releasedFlag.get()) return
        if (!abortGuard.isResumeAttemptValid(attemptSeq)) {
            onAborted()
            return
        }
        dispatchPlayRouteDecision(
            attemptSeq = attemptSeq,
            firstRequestMs = firstRequestMs,
            nowMs = clock.elapsedRealtimeMs(),
            isInitialRequest = false,
            onProceedNow = onProceedNow,
            onDeferredWaiting = {},
            onAborted = onAborted,
        )
    }
}
