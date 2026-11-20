package com.cangkir.bitperfectplayer.audio

/**
 * Pure-Kotlin flush payload emitted by [PlayCountTracker] when a track qualifies for a play count
 * increment and/or accumulated real listening time should be persisted to `track_stats`.
 */
data class PlayCountFlushEvent(
    val trackUri: String,
    val incrementPlayCount: Boolean,
    val playTimeDeltaMs: Long,
)

/**
 * Pure-Kotlin anti-skip play count & real listening-time engine (zero `android.*` imports).
 *
 * Anti-skip guarantees:
 * - Accumulates ONLY genuine active PCM playback elapsed time (never paused time or seek jumps).
 * - Strictly NEVER increments play count for `< 5,000 ms` of actual playback.
 * - Threshold per track session:
 *   - `durationMs >= 10_000L`: `(durationMs / 2L).coerceIn(10_000L, 30_000L)` (30s or 50%, min 10s).
 *   - `durationMs in 1L..9_999L`: `maxOf(5_000L, (durationMs * 80L) / 100L)` or natural EOS after `>= 5s`.
 *   - `durationMs <= 0L`: `30_000L`.
 * - Counts at most ONCE per session (pause/resume never double-counts).
 * - Resets session cleanly on track switch, replay after completion, or `RepeatMode.ONE` loop.
 */
class PlayCountTracker {

    private var activeTrackUri: String? = null
    private var durationMs: Long = 0L
    private var sessionAccumulatedMs: Long = 0L
    private var unflushedPlayTimeMs: Long = 0L
    private var hasQualifiedInSession: Boolean = false

    @Synchronized
    fun currentSessionAccumulatedMs(): Long = sessionAccumulatedMs

    @Synchronized
    fun hasQualifiedCurrentSession(): Boolean = hasQualifiedInSession

    /**
     * Begins a fresh listening session for [trackUri]. Returns any unflushed listening time from
     * the previous track session so it can be persisted before switching tracks.
     */
    @Synchronized
    fun startSession(trackUri: String, durationMs: Long): PlayCountFlushEvent? {
        val previousFlush = drainUnflushedLocked(incrementPlayCount = false)
        activeTrackUri = trackUri
        this.durationMs = durationMs.coerceAtLeast(0L)
        sessionAccumulatedMs = 0L
        unflushedPlayTimeMs = 0L
        hasQualifiedInSession = false
        return previousFlush
    }

    /**
     * Updates the confirmed track duration once the audio decoder opens the stream, without
     * resetting already-accumulated playback time.
     */
    @Synchronized
    fun updateDuration(durationMs: Long) {
        if (durationMs > 0L) {
            this.durationMs = durationMs
        }
    }

    /**
     * Records genuine active PCM playback time [deltaMs] while playing. Ignores non-positive
     * values and caps single ticks to [MAX_SINGLE_TICK_MS] so pause/resume gaps or seek jumps can
     * never spoof elapsed listening time.
     */
    @Synchronized
    fun onActivePlaybackElapsed(deltaMs: Long): PlayCountFlushEvent? {
        if (activeTrackUri == null || deltaMs <= 0L) return null
        val boundedDelta = deltaMs.coerceAtMost(MAX_SINGLE_TICK_MS)
        sessionAccumulatedMs += boundedDelta
        unflushedPlayTimeMs += boundedDelta

        val threshold = qualificationThresholdMs(durationMs)
        if (!hasQualifiedInSession &&
            sessionAccumulatedMs >= MIN_QUALIFYING_PLAY_MS &&
            sessionAccumulatedMs >= threshold
        ) {
            hasQualifiedInSession = true
            return drainUnflushedLocked(incrementPlayCount = true)
        }
        return null
    }

    /**
     * Explicit seek notification: seeking never adds playback time and never qualifies a track.
     */
    @Synchronized
    fun onSeek() {
        // Intentionally no-op on sessionAccumulatedMs: seeking must never count as listening time.
    }

    /**
     * Handles natural end-of-stream playback completion. For short tracks (`1..9,999 ms`), natural
     * EOS after at least `5,000 ms` of real playback qualifies once. Always flushes remaining
     * accumulated listening time.
     */
    @Synchronized
    fun onNaturalCompletion(): PlayCountFlushEvent? {
        if (activeTrackUri == null) return null
        var newlyQualified = false
        if (!hasQualifiedInSession && sessionAccumulatedMs >= MIN_QUALIFYING_PLAY_MS) {
            val qualifiesByThreshold = sessionAccumulatedMs >= qualificationThresholdMs(durationMs)
            val qualifiesByShortTrackEos = durationMs in 1L..9_999L
            if (qualifiesByThreshold || qualifiesByShortTrackEos) {
                hasQualifiedInSession = true
                newlyQualified = true
            }
        }
        return drainUnflushedLocked(incrementPlayCount = newlyQualified)
    }

    /**
     * Flushes any pending real listening time on pause, stop, or app lifecycle stop WITHOUT
     * resetting the session's qualification state (pause/resume continues the same session).
     */
    @Synchronized
    fun flushPendingPlayTime(): PlayCountFlushEvent? =
        drainUnflushedLocked(incrementPlayCount = false)

    /**
     * Resets the current track's session so replaying from start after completion or looping under
     * `RepeatMode.ONE` can qualify for a new play count.
     */
    @Synchronized
    fun resetSessionForReplay(): PlayCountFlushEvent? {
        val flush = drainUnflushedLocked(incrementPlayCount = false)
        sessionAccumulatedMs = 0L
        unflushedPlayTimeMs = 0L
        hasQualifiedInSession = false
        return flush
    }

    /**
     * Clears the active session completely (e.g., when playback queue becomes empty or stopped).
     */
    @Synchronized
    fun clearSession(): PlayCountFlushEvent? {
        val flush = drainUnflushedLocked(incrementPlayCount = false)
        activeTrackUri = null
        durationMs = 0L
        sessionAccumulatedMs = 0L
        unflushedPlayTimeMs = 0L
        hasQualifiedInSession = false
        return flush
    }

    private fun drainUnflushedLocked(incrementPlayCount: Boolean): PlayCountFlushEvent? {
        val uri = activeTrackUri ?: return null
        val pendingTime = unflushedPlayTimeMs
        unflushedPlayTimeMs = 0L
        if (!incrementPlayCount && pendingTime <= 0L) return null
        return PlayCountFlushEvent(
            trackUri = uri,
            incrementPlayCount = incrementPlayCount,
            playTimeDeltaMs = pendingTime,
        )
    }

    companion object {
        const val MIN_QUALIFYING_PLAY_MS: Long = 5_000L
        const val MIN_LONG_TRACK_THRESHOLD_MS: Long = 10_000L
        const val MAX_QUALIFYING_THRESHOLD_MS: Long = 30_000L
        const val UNKNOWN_DURATION_THRESHOLD_MS: Long = 30_000L
        const val MAX_SINGLE_TICK_MS: Long = 2_000L

        /**
         * Computes the anti-skip active-playback qualification threshold for [durationMs].
         */
        fun qualificationThresholdMs(durationMs: Long): Long = when {
            durationMs >= 10_000L -> (durationMs / 2L).coerceIn(
                MIN_LONG_TRACK_THRESHOLD_MS,
                MAX_QUALIFYING_THRESHOLD_MS,
            )
            durationMs in 1L..9_999L -> maxOf(
                MIN_QUALIFYING_PLAY_MS,
                (durationMs * 80L) / 100L,
            )
            else -> UNKNOWN_DURATION_THRESHOLD_MS
        }
    }
}
