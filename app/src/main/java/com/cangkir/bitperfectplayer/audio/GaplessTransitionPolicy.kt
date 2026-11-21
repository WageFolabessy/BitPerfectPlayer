package com.cangkir.bitperfectplayer.audio

/**
 * Outcome decided when the active decoder reaches end-of-stream (EOS).
 */
enum class GaplessTransitionAction {
    /**
     * Keep the exact same [android.media.AudioTrack] running continuously and switch immediately
     * to the pre-opened next decoder (0 ms silence gap, zero AudioTrack teardown).
     */
    SEAMLESS_REUSE,

    /**
     * Let the active AudioTrack drain all buffered frames cleanly (never truncating the tail),
     * then rebuild AudioTrack / load the next track (used when sample rate, channel count, or PCM
     * encoding differs, when gapless is disabled, or when preload is not yet ready).
     */
    DRAIN_AND_REBUILD,

    /**
     * No upcoming track in the queue: let the active AudioTrack drain cleanly, then stop.
     */
    STOP_END_OF_QUEUE,
}

/**
 * Immutable PCM stream signature used to determine whether two adjacent tracks can share the
 * exact same running AudioTrack instance without reconfiguration.
 */
data class PcmStreamSignature(val sampleRateHz: Int, val channelCount: Int, val encoding: PcmEncodingType)

fun DecodedPcmFormat.toStreamSignature(): PcmStreamSignature = PcmStreamSignature(
    sampleRateHz = sampleRateHz,
    channelCount = channelCount,
    encoding = encoding,
)

/**
 * Pure-Kotlin transition and preload policy for true gapless playback (zero `android.*` imports).
 */
object GaplessTransitionPolicy {
    /**
     * Pre-open and prime the next decoder once the current track enters its final 8 seconds
     * (or immediately for short tracks <= 8 seconds).
     */
    const val PRELOAD_LEAD_TIME_MS: Long = 8_000L

    /**
     * Upper safety bound when waiting for AudioTrack's internal buffer to finish draining on EOS.
     */
    const val MAX_DRAIN_WAIT_MS: Long = 1_500L

    /**
     * Minimum wait budget when draining AudioTrack on EOS so short trailing buffers finish cleanly.
     */
    const val MIN_DRAIN_WAIT_MS: Long = 250L

    fun decide(
        gaplessEnabled: Boolean,
        hasNextTrack: Boolean,
        currentFormat: PcmStreamSignature,
        nextFormat: PcmStreamSignature?,
    ): GaplessTransitionAction {
        if (!hasNextTrack) {
            return GaplessTransitionAction.STOP_END_OF_QUEUE
        }
        if (gaplessEnabled && nextFormat != null && currentFormat == nextFormat) {
            return GaplessTransitionAction.SEAMLESS_REUSE
        }
        return GaplessTransitionAction.DRAIN_AND_REBUILD
    }

    fun shouldStartPreload(
        gaplessEnabled: Boolean,
        hasNextTrack: Boolean,
        isAlreadyPreloadingOrReady: Boolean,
        currentPositionMs: Long,
        durationMs: Long,
    ): Boolean {
        if (!gaplessEnabled || !hasNextTrack || isAlreadyPreloadingOrReady) {
            return false
        }
        if (durationMs <= 0L) {
            return false
        }
        val remainingMs = (durationMs - currentPositionMs.coerceAtLeast(0L)).coerceAtLeast(0L)
        return remainingMs <= PRELOAD_LEAD_TIME_MS
    }

    /**
     * Computes a bounded drain timeout (in ms) based on how many PCM frames remain queued inside
     * AudioTrack ahead of its hardware playback head.
     */
    fun computeDrainTimeoutMs(remainingBufferedFrames: Long, sampleRateHz: Int): Long {
        if (sampleRateHz <= 0 || remainingBufferedFrames <= 0L) {
            return MIN_DRAIN_WAIT_MS
        }
        val estimatedAudioMs = (remainingBufferedFrames * 1000L) / sampleRateHz.toLong()
        return (estimatedAudioMs + 250L).coerceIn(MIN_DRAIN_WAIT_MS, MAX_DRAIN_WAIT_MS)
    }

    /**
     * Determines what `PlaybackController` must do immediately after `AudioOutputEngine` reports
     * a seamless same-format gapless transition, ensuring an End-of-Track sleep timer race always
     * pauses playback and clears any preloaded successor cleanly.
     */
    fun resolvePostTransitionDirective(
        hasNextTrack: Boolean,
        nextTrackMatchesTransitioned: Boolean,
        pauseAfterTransition: Boolean,
    ): PostGaplessTransitionDirective = when {
        !hasNextTrack -> PostGaplessTransitionDirective.STOP_END_OF_QUEUE
        !nextTrackMatchesTransitioned -> PostGaplessTransitionDirective.RESYNC_VIA_RELOAD
        pauseAfterTransition -> PostGaplessTransitionDirective.PAUSE_AND_PERSIST
        else -> PostGaplessTransitionDirective.CONTINUE_AND_SYNC_NEXT
    }
}

/**
 * Post-transition control directive executed by `PlaybackController` after a seamless gapless
 * decoder handoff.
 */
enum class PostGaplessTransitionDirective {
    CONTINUE_AND_SYNC_NEXT,
    PAUSE_AND_PERSIST,
    RESYNC_VIA_RELOAD,
    STOP_END_OF_QUEUE,
}
