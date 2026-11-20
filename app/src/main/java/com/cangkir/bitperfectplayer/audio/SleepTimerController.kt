package com.cangkir.bitperfectplayer.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Available local Sleep Timer presets.
 */
enum class SleepTimerPreset(
    val label: String,
    val durationMs: Long?,
) {
    OFF("Off", null),
    MIN_15("15 min", 15L * 60_000L),
    MIN_30("30 min", 30L * 60_000L),
    MIN_45("45 min", 45L * 60_000L),
    MIN_60("60 min", 60L * 60_000L),
    END_OF_TRACK("End of current track", null),
}

/**
 * Observable Sleep Timer UI state collected exclusively by narrow leaf composables in
 * NowPlayingScreen and SettingsScreen (never in LibraryScreen, PlaylistsScreen, or BottomNavBar).
 */
data class SleepTimerState(
    val preset: SleepTimerPreset = SleepTimerPreset.OFF,
    val remainingMs: Long = 0L,
    val isFadingOut: Boolean = false,
) {
    val isActive: Boolean
        get() = preset != SleepTimerPreset.OFF

    val isEndOfTrackArmed: Boolean
        get() = preset == SleepTimerPreset.END_OF_TRACK

    val compactBadgeLabel: String
        get() = when {
            preset == SleepTimerPreset.OFF -> ""
            preset == SleepTimerPreset.END_OF_TRACK -> "End"
            else -> AudioFormatFormatter.formatDurationMs(remainingMs.coerceAtLeast(0L))
        }
}

/**
 * Pure-Kotlin Sleep Timer controller (zero `android.*` imports).
 *
 * Audio & Bit-Perfect Safety Guarantees:
 * - In [AudioOutputMode.SAFE_VOLUME_MODE], executes a smooth 3-second linear volume fade-out via
 *   [setSafeModeVolume] (`1.0f -> 0.0f`), pauses playback, and immediately restores volume to `1.0f`.
 * - In [AudioOutputMode.BIT_PERFECT_OPT_IN], pauses cleanly at expiration with ZERO calls to
 *   [setSafeModeVolume] so digital PCM/AudioTrack gain is never attenuated.
 * - Supports `End of current track` without launching any background tick coroutine.
 */
class SleepTimerController(
    private val scope: CoroutineScope,
    private val isBitPerfectOptIn: () -> Boolean,
    private val setSafeModeVolume: (Float) -> Unit,
    private val pausePlayback: () -> Unit,
    private val onEndOfTrackArmedChanged: ((Boolean) -> Unit)? = null,
    private val onTimerCompleted: ((SleepTimerPreset) -> Unit)? = null,
) {
    private val _state = MutableStateFlow(SleepTimerState())
    val state: StateFlow<SleepTimerState> = _state.asStateFlow()

    private var timerJob: Job? = null

    fun setPreset(preset: SleepTimerPreset) {
        val wasEndOfTrack = _state.value.isEndOfTrackArmed
        cancelInternal(restoreVolumeIfFading = true)

        when (preset) {
            SleepTimerPreset.OFF -> {
                _state.value = SleepTimerState()
                if (wasEndOfTrack) onEndOfTrackArmedChanged?.invoke(false)
            }
            SleepTimerPreset.END_OF_TRACK -> {
                _state.value = SleepTimerState(preset = SleepTimerPreset.END_OF_TRACK)
                if (!wasEndOfTrack) onEndOfTrackArmedChanged?.invoke(true)
            }
            else -> {
                if (wasEndOfTrack) onEndOfTrackArmedChanged?.invoke(false)
                val duration = preset.durationMs ?: return
                startCountdown(preset = preset, durationMs = duration)
            }
        }
    }

    /**
     * Starts a timed sleep countdown with an explicit [durationMs] (used internally and by JVM tests).
     */
    internal fun startCustomCountdown(preset: SleepTimerPreset, durationMs: Long) {
        val wasEndOfTrack = _state.value.isEndOfTrackArmed
        cancelInternal(restoreVolumeIfFading = true)
        if (wasEndOfTrack) onEndOfTrackArmedChanged?.invoke(false)
        if (durationMs <= 0L) {
            _state.value = SleepTimerState()
            return
        }
        startCountdown(preset = preset, durationMs = durationMs)
    }

    fun cancel() {
        setPreset(SleepTimerPreset.OFF)
    }

    /**
     * Called by `PlaybackController` when the active track reaches natural EOS.
     * If [SleepTimerPreset.END_OF_TRACK] is armed, consumes the trigger, pauses playback cleanly,
     * resets the timer to [SleepTimerPreset.OFF], and returns `true` so the next track does not start.
     */
    fun consumeEndOfTrackTrigger(): Boolean {
        if (_state.value.preset != SleepTimerPreset.END_OF_TRACK) return false
        _state.value = SleepTimerState()
        onEndOfTrackArmedChanged?.invoke(false)
        pausePlayback()
        onTimerCompleted?.invoke(SleepTimerPreset.END_OF_TRACK)
        return true
    }

    fun release() {
        cancelInternal(restoreVolumeIfFading = true)
        _state.value = SleepTimerState()
    }

    private fun startCountdown(preset: SleepTimerPreset, durationMs: Long) {
        _state.value = SleepTimerState(
            preset = preset,
            remainingMs = durationMs,
            isFadingOut = false,
        )
        timerJob = scope.launch {
            var remaining = durationMs

            // Coarse 1-second ticks until entering the final 3-second fade window.
            while (remaining > FADE_DURATION_MS) {
                val step = minOf(TICK_INTERVAL_MS, remaining - FADE_DURATION_MS)
                delay(step)
                remaining -= step
                _state.value = SleepTimerState(
                    preset = preset,
                    remainingMs = remaining,
                    isFadingOut = false,
                )
            }

            // Final 3-second window: smooth linear fade in Safe Mode, or zero volume scaling in Bit-Perfect Mode.
            val fadeTotalMs = remaining.coerceIn(0L, FADE_DURATION_MS)
            if (fadeTotalMs > 0L) {
                if (isBitPerfectOptIn()) {
                    // Bit-Perfect Mode: count down cleanly with ZERO calls to setSafeModeVolume.
                    while (remaining > 0L) {
                        val step = minOf(TICK_INTERVAL_MS, remaining)
                        delay(step)
                        remaining -= step
                        _state.value = SleepTimerState(
                            preset = preset,
                            remainingMs = remaining,
                            isFadingOut = false,
                        )
                    }
                } else {
                    var appliedSafeFade = false
                    val steps = (fadeTotalMs / FADE_STEP_MS).toInt().coerceAtLeast(1)
                    val stepDelay = fadeTotalMs / steps
                    for (stepIndex in 1..steps) {
                        delay(stepDelay)
                        if (isBitPerfectOptIn()) {
                            // User switched to Bit-Perfect mid-fade: immediately restore unity gain and stop fading.
                            if (appliedSafeFade) {
                                setSafeModeVolume(1.0f)
                                appliedSafeFade = false
                            }
                            val leftover = (steps - stepIndex) * stepDelay
                            if (leftover > 0L) delay(leftover)
                            break
                        }
                        val nextVol = ((steps - stepIndex).toFloat() / steps.toFloat()).coerceIn(0.0f, 1.0f)
                        setSafeModeVolume(nextVol)
                        appliedSafeFade = true
                        remaining = (fadeTotalMs - stepIndex * stepDelay).coerceAtLeast(0L)
                        _state.value = SleepTimerState(
                            preset = preset,
                            remainingMs = remaining,
                            isFadingOut = true,
                        )
                    }
                    pausePlayback()
                    if (appliedSafeFade) {
                        setSafeModeVolume(1.0f)
                    }
                    _state.value = SleepTimerState()
                    onTimerCompleted?.invoke(preset)
                    return@launch
                }
            }

            pausePlayback()
            _state.value = SleepTimerState()
            onTimerCompleted?.invoke(preset)
        }
    }

    private fun cancelInternal(restoreVolumeIfFading: Boolean) {
        val wasFading = _state.value.isFadingOut
        timerJob?.cancel()
        timerJob = null
        if (restoreVolumeIfFading && wasFading && !isBitPerfectOptIn()) {
            setSafeModeVolume(1.0f)
        }
    }

    companion object {
        const val FADE_DURATION_MS: Long = 3_000L
        const val FADE_STEP_MS: Long = 100L
        const val TICK_INTERVAL_MS: Long = 1_000L
    }
}
