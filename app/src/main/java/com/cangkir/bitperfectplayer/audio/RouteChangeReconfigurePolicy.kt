package com.cangkir.bitperfectplayer.audio

/**
 * Action chosen by [RouteChangeReconfigurePolicy] for a [SafetyCommand] so paused route changes
 * (such as screen recording virtual audio sinks or route flaps) avoid redundant `MediaCodec` +
 * `AudioTrack` teardown/re-creation cycles while still honoring all hearing-safety guarantees.
 */
enum class SafetyReconfigureAction {
    /** Only refresh safety & 6-stage diagnostics state (no track loaded or warning-only event). */
    DIAGNOSTICS_ONLY,

    /**
     * Playback is already paused/idle during a route/device/noisy event: clear stale USB preferred
     * mixer attributes, update diagnostics, and defer `MediaCodec` + `AudioTrack` rebuild until
     * the next `play()` invocation.
     */
    DEFER_RECONFIGURE_UNTIL_PLAY,

    /**
     * Active playback was auto-paused by a USB plug/unplug, `ACTION_AUDIO_BECOMING_NOISY`, or
     * output route change: immediately stop the worker, release the invalidated `AudioTrack` and
     * decoder, clear preferred USB mixer attributes, abandon audio focus, preserve the current
     * track & exact `positionMs`, and rebind cleanly on the very first `play()` tap without
     * constructing a throwaway `AudioTrack` on a churning USB HAL.
     */
    TEARDOWN_AND_REBIND_ON_PLAY,

    /**
     * Immediately reconfigure the loaded track (explicit Safe Mode / Bit-Perfect Opt-In user
     * switch requiring immediate mixer negotiation and live diagnostics refresh).
     */
    RECONFIGURE_NOW,
}

/**
 * Pure-Kotlin route-change and mode-switch reconfigure policy (zero `android.*` imports).
 */
object RouteChangeReconfigurePolicy {

    /**
     * Decides the minimal safe reconfigure action for [commandType] using the engine status
     * captured *before* applying pause side effects and whether a track is loaded/preparing.
     */
    fun decideAction(
        commandType: SafetyEventType,
        isActivelyPlayingOrPreparing: Boolean,
        hasLoadedTrack: Boolean,
    ): SafetyReconfigureAction = when (commandType) {
        SafetyEventType.REQUIRES_SAFETY_WARNING,
        SafetyEventType.NONE,
        -> SafetyReconfigureAction.DIAGNOSTICS_ONLY

        SafetyEventType.MODE_CHANGED_TO_SAFE,
        SafetyEventType.MODE_CHANGED_TO_BIT_PERFECT,
        -> if (hasLoadedTrack) {
            SafetyReconfigureAction.RECONFIGURE_NOW
        } else {
            SafetyReconfigureAction.DIAGNOSTICS_ONLY
        }

        SafetyEventType.AUTO_PAUSE -> when {
            !hasLoadedTrack -> SafetyReconfigureAction.DIAGNOSTICS_ONLY
            isActivelyPlayingOrPreparing -> SafetyReconfigureAction.TEARDOWN_AND_REBIND_ON_PLAY
            else -> SafetyReconfigureAction.DEFER_RECONFIGURE_UNTIL_PLAY
        }
    }

    /**
     * Returns `true` when `play()` must reload the active track and re-negotiate output routing
     * / mixer attributes rather than resuming an existing `AudioTrack` handle directly.
     */
    fun shouldReloadOnPlay(
        hasPendingRouteReconfigure: Boolean,
        hasActiveDecoder: Boolean,
        hasActiveAudioTrack: Boolean,
        isCompleted: Boolean,
        isError: Boolean = false,
        isAudioTrackHealthy: Boolean = true,
    ): Boolean = hasPendingRouteReconfigure ||
        !hasActiveDecoder ||
        !hasActiveAudioTrack ||
        isCompleted ||
        isError ||
        !isAudioTrackHealthy
}
