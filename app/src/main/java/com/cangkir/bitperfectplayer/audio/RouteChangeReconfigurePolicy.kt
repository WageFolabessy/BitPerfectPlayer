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
     * Immediately reconfigure the loaded track (active playback auto-paused on route change, or
     * explicit Safe Mode / Bit-Perfect Opt-In user switch requiring immediate mixer negotiation).
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
            isActivelyPlayingOrPreparing -> SafetyReconfigureAction.RECONFIGURE_NOW
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
    ): Boolean = hasPendingRouteReconfigure ||
        !hasActiveDecoder ||
        !hasActiveAudioTrack ||
        isCompleted
}
