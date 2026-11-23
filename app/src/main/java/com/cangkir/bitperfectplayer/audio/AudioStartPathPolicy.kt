package com.cangkir.bitperfectplayer.audio

/**
 * Decision on whether track startup should touch Android's USB mixer attribute APIs synchronously.
 */
enum class MixerNegotiationStrategy {
    SKIP_IN_SAFE_MODE,
    SKIP_DURING_NOISY_COOLDOWN,
    APPLY_BIT_PERFECT_USB,
}

/**
 * Coalesced action for the primary Play/Pause button in UI controllers.
 */
enum class TogglePlayPauseAction {
    PAUSE,
    PLAY,
}

/**
 * Pure-Kotlin rules for zero-blocking playback start, slow-IPC diagnostics, and UI toggle intent.
 */
object AudioStartPathPolicy {
    const val API_34_UPSIDE_DOWN_CAKE = 34
    const val SLOW_AUDIO_STAGE_THRESHOLD_MS = 150L
    const val PREPARING_INDICATOR_DELAY_MS = 250L

    fun decideMixerNegotiationStrategy(
        sdkInt: Int,
        mode: AudioOutputMode,
        isNoisyCooldownActive: Boolean,
        hasUsbSink: Boolean,
    ): MixerNegotiationStrategy {
        if (mode != AudioOutputMode.BIT_PERFECT_OPT_IN) {
            return MixerNegotiationStrategy.SKIP_IN_SAFE_MODE
        }
        if (isNoisyCooldownActive) {
            return MixerNegotiationStrategy.SKIP_DURING_NOISY_COOLDOWN
        }
        if (sdkInt < API_34_UPSIDE_DOWN_CAKE || !hasUsbSink) {
            return MixerNegotiationStrategy.SKIP_IN_SAFE_MODE
        }
        return MixerNegotiationStrategy.APPLY_BIT_PERFECT_USB
    }

    fun shouldLogSlowAudioStage(isDebugBuild: Boolean, elapsedMs: Long): Boolean = isDebugBuild && elapsedMs > SLOW_AUDIO_STAGE_THRESHOLD_MS

    fun resolveTogglePlayPauseAction(
        isPlaying: Boolean,
        isPreparingPlayback: Boolean,
        isPreservingPlayingUi: Boolean = false,
    ): TogglePlayPauseAction {
        if (isPlaying || isPreservingPlayingUi) {
            return TogglePlayPauseAction.PAUSE
        }
        if (isPreparingPlayback) {
            // Re-tapping while startup is already in flight must keep start intent instead of flip-flopping to Pause.
            return TogglePlayPauseAction.PLAY
        }
        return TogglePlayPauseAction.PLAY
    }
}
