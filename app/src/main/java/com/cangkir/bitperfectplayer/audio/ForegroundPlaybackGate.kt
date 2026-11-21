package com.cangkir.bitperfectplayer.audio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

enum class ForegroundHandshakeResult {
    READY_IMMEDIATELY,
    PROMOTED,
    FAILED_START_NOT_ALLOWED,
    TIMED_OUT,
    CANCELLED,
}

/**
 * Pure-Kotlin coordinator that guards background audio startup for Android 17 (`API 37`)
 * Background Audio Hardening.
 *
 * On Android 17+, requesting audio focus or starting [android.media.AudioTrack] playback from the
 * background before the `mediaPlayback` Foreground Service is promoted with While-In-Use (WIU)
 * capability causes the OS to reject focus (`AUDIOFOCUS_REQUEST_FAILED`) or silently mute output.
 *
 * When [isAppInForeground] or [isServiceInForeground] is `true`, [needsForegroundServiceHandshake]
 * returns `false` so normal in-app playback starts synchronously with 0 ms added latency.
 */
class ForegroundPlaybackGate(initialAppInForeground: Boolean = false, initialServiceInForeground: Boolean = false) {
    private val lock = Any()

    @Volatile
    var isAppInForeground: Boolean = initialAppInForeground
        private set

    @Volatile
    var isServiceInForeground: Boolean = initialServiceInForeground
        private set

    @Volatile
    var lastFailureReason: String? = null
        private set

    private var activeToken: Long = 0L
    private var pendingWaiter: PendingWaiter? = null

    private class PendingWaiter(val token: Long, val deferred: CompletableDeferred<ForegroundHandshakeResult>)

    fun needsForegroundServiceHandshake(): Boolean = synchronized(lock) {
        !isAppInForeground && !isServiceInForeground
    }

    fun setAppInForeground(inForeground: Boolean) {
        val waiterToComplete = synchronized(lock) {
            isAppInForeground = inForeground
            if (inForeground) {
                lastFailureReason = null
                pendingWaiter
            } else {
                null
            }
        }
        waiterToComplete?.deferred?.complete(ForegroundHandshakeResult.PROMOTED)
    }

    fun markServicePromoted() {
        val waiterToComplete = synchronized(lock) {
            isServiceInForeground = true
            lastFailureReason = null
            pendingWaiter
        }
        waiterToComplete?.deferred?.complete(ForegroundHandshakeResult.PROMOTED)
    }

    fun markServiceStartFailed(reason: String? = null) {
        val waiterToComplete = synchronized(lock) {
            isServiceInForeground = false
            lastFailureReason = reason?.takeIf { it.isNotBlank() }
                ?: DEFAULT_START_FAILURE_REASON
            pendingWaiter
        }
        waiterToComplete?.deferred?.complete(ForegroundHandshakeResult.FAILED_START_NOT_ALLOWED)
    }

    fun markServiceStopped() {
        synchronized(lock) {
            isServiceInForeground = false
        }
    }

    /**
     * Reserves a new handshake token and cancels any older in-flight wait with
     * [ForegroundHandshakeResult.CANCELLED].
     */
    fun beginHandshake(): Long {
        val previousWaiter: PendingWaiter?
        val newToken: Long
        synchronized(lock) {
            previousWaiter = pendingWaiter
            newToken = ++activeToken
            pendingWaiter = if (isAppInForeground || isServiceInForeground) {
                null
            } else {
                PendingWaiter(newToken, CompletableDeferred())
            }
        }
        previousWaiter?.deferred?.complete(ForegroundHandshakeResult.CANCELLED)
        return newToken
    }

    /**
     * Cancels any active foreground promotion wait (invoked on `pause()`, `stop()`, or non-autoplay
     * track loads so a delayed service promotion never triggers unwanted playback).
     */
    fun cancelPendingHandshake() {
        val waiterToCancel = synchronized(lock) {
            ++activeToken
            val existing = pendingWaiter
            pendingWaiter = null
            existing
        }
        waiterToCancel?.deferred?.complete(ForegroundHandshakeResult.CANCELLED)
    }

    /**
     * Awaits Foreground Service promotion up to [timeoutMs].
     *
     * If [isAppInForeground] or [isServiceInForeground] is already `true`, returns
     * [ForegroundHandshakeResult.READY_IMMEDIATELY] without suspending.
     */
    suspend fun awaitServicePromotion(timeoutMs: Long = DEFAULT_HANDSHAKE_TIMEOUT_MS, token: Long? = null): ForegroundHandshakeResult {
        var immediateResult: ForegroundHandshakeResult? = null
        var waiter: PendingWaiter? = null
        var previousWaiterToCancel: PendingWaiter? = null

        synchronized(lock) {
            if (token != null) {
                if (token != activeToken) {
                    immediateResult = ForegroundHandshakeResult.CANCELLED
                } else {
                    val existing = pendingWaiter
                    if (existing != null) {
                        waiter = existing
                    } else {
                        immediateResult = if (isAppInForeground || isServiceInForeground) {
                            ForegroundHandshakeResult.READY_IMMEDIATELY
                        } else {
                            ForegroundHandshakeResult.CANCELLED
                        }
                    }
                }
            } else if (isAppInForeground || isServiceInForeground) {
                immediateResult = ForegroundHandshakeResult.READY_IMMEDIATELY
            } else {
                previousWaiterToCancel = pendingWaiter
                val newToken = ++activeToken
                val created = PendingWaiter(newToken, CompletableDeferred())
                pendingWaiter = created
                waiter = created
            }
        }

        previousWaiterToCancel?.deferred?.complete(ForegroundHandshakeResult.CANCELLED)
        val resolvedImmediate = immediateResult
        if (resolvedImmediate != null) {
            return resolvedImmediate
        }
        val activeWaiter = checkNotNull(waiter)

        return try {
            withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) {
                activeWaiter.deferred.await()
            } ?: ForegroundHandshakeResult.TIMED_OUT
        } catch (_: CancellationException) {
            ForegroundHandshakeResult.CANCELLED
        } finally {
            synchronized(lock) {
                if (pendingWaiter?.token == activeWaiter.token) {
                    pendingWaiter = null
                }
            }
        }
    }

    companion object {
        const val DEFAULT_HANDSHAKE_TIMEOUT_MS = 1_000L
        const val DEFAULT_START_FAILURE_REASON =
            "Foreground service start not allowed from background"
        const val BACKGROUND_START_BLOCKED_NOTICE =
            "Background playback blocked by Android background restrictions. Open BitPerfectPlayer to resume."
    }
}
