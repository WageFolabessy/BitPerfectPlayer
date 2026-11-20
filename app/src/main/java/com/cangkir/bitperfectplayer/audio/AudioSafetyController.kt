package com.cangkir.bitperfectplayer.audio

/**
 * Active audio safety & volume policy mode.
 * Always defaults to [SAFE_VOLUME_MODE] to protect high-sensitivity IEM users (e.g., 107 dB/mW).
 */
enum class AudioOutputMode {
    SAFE_VOLUME_MODE,
    BIT_PERFECT_OPT_IN,
}

enum class SafetyEventType {
    NONE,
    REQUIRES_SAFETY_WARNING,
    AUTO_PAUSE,
    MODE_CHANGED_TO_SAFE,
    MODE_CHANGED_TO_BIT_PERFECT,
}

enum class AutoPauseReason {
    BECOMING_NOISY,
    USB_DISCONNECTED,
    USB_CONNECTED_ROUTE_CHANGE,
    OUTPUT_ROUTE_CHANGED,
}

/**
 * Side-effect command emitted by [AudioSafetyController] whenever a policy transition occurs.
 */
data class SafetyCommand(
    val type: SafetyEventType,
    val autoPauseReason: AutoPauseReason? = null,
    val requiresClearPreferredMixerAttributes: Boolean = false,
    val warningMessage: String? = null,
)

/**
 * Immutable snapshot of current hearing safety & volume control state.
 */
data class AudioSafetyState(
    val mode: AudioOutputMode = AudioOutputMode.SAFE_VOLUME_MODE,
    val isPendingSafetyWarning: Boolean = false,
    val isHardwareVolumeVerified: Boolean = false,
    val isUsbAudioConnected: Boolean = false,
    val activeDeviceName: String = "Device Output",
    val lastAutoPauseReason: AutoPauseReason? = null,
) {
    val isFixedVolumeRisk: Boolean
        get() = mode == AudioOutputMode.BIT_PERFECT_OPT_IN && !isHardwareVolumeVerified
}

/**
 * Pure-Kotlin hearing safety & audio mode state machine (zero `android.*` imports).
 *
 * Mandatory safety invariants:
 * 1. Always initializes in [AudioOutputMode.SAFE_VOLUME_MODE].
 * 2. Requesting [AudioOutputMode.BIT_PERFECT_OPT_IN] emits [SafetyEventType.REQUIRES_SAFETY_WARNING]
 *    and stays in Safe Mode whenever `isHardwareVolumeVerified == false`.
 * 3. Connecting any new USB audio device never auto-enables Bit-Perfect Mode; it resets to or
 *    remains in Safe Mode and emits [SafetyEventType.AUTO_PAUSE].
 * 4. Becoming noisy, USB disconnect, or output route changes always emit [SafetyEventType.AUTO_PAUSE]
 *    and clear preferred bit-perfect mixer attributes when leaving Bit-Perfect Mode.
 */
class AudioSafetyController {

    private var currentState: AudioSafetyState = AudioSafetyState()

    @Synchronized
    fun state(): AudioSafetyState = currentState

    @Synchronized
    fun requestBitPerfectMode(): SafetyCommand {
        if (currentState.mode == AudioOutputMode.BIT_PERFECT_OPT_IN) {
            return SafetyCommand(type = SafetyEventType.NONE)
        }
        if (!currentState.isHardwareVolumeVerified) {
            currentState = currentState.copy(isPendingSafetyWarning = true)
            return SafetyCommand(
                type = SafetyEventType.REQUIRES_SAFETY_WARNING,
                warningMessage = FIXED_VOLUME_WARNING_MESSAGE,
            )
        }
        currentState = currentState.copy(
            mode = AudioOutputMode.BIT_PERFECT_OPT_IN,
            isPendingSafetyWarning = false,
        )
        return SafetyCommand(type = SafetyEventType.MODE_CHANGED_TO_BIT_PERFECT)
    }

    @Synchronized
    fun confirmBitPerfectWarning(): SafetyCommand {
        if (!currentState.isPendingSafetyWarning) {
            // Do not allow silent activation without passing through requestBitPerfectMode().
            return requestBitPerfectMode()
        }
        currentState = currentState.copy(
            mode = AudioOutputMode.BIT_PERFECT_OPT_IN,
            isPendingSafetyWarning = false,
        )
        return SafetyCommand(type = SafetyEventType.MODE_CHANGED_TO_BIT_PERFECT)
    }

    @Synchronized
    fun dismissSafetyWarning(): SafetyCommand {
        currentState = currentState.copy(
            mode = AudioOutputMode.SAFE_VOLUME_MODE,
            isPendingSafetyWarning = false,
        )
        return SafetyCommand(type = SafetyEventType.NONE)
    }

    @Synchronized
    fun switchToSafeMode(): SafetyCommand {
        val wasBitPerfect = currentState.mode == AudioOutputMode.BIT_PERFECT_OPT_IN
        currentState = currentState.copy(
            mode = AudioOutputMode.SAFE_VOLUME_MODE,
            isPendingSafetyWarning = false,
        )
        return SafetyCommand(
            type = SafetyEventType.MODE_CHANGED_TO_SAFE,
            requiresClearPreferredMixerAttributes = wasBitPerfect || currentState.isUsbAudioConnected,
        )
    }

    /**
     * Called when any audio output device (especially USB-C DAC/earphone) is connected.
     * Never auto-enables Bit-Perfect Mode: always resets to or remains in [AudioOutputMode.SAFE_VOLUME_MODE]
     * and triggers immediate [SafetyEventType.AUTO_PAUSE].
     */
    @Synchronized
    fun onAudioDeviceConnected(
        deviceName: String,
        isUsbAudio: Boolean,
        isHardwareVolumeVerified: Boolean = false,
    ): SafetyCommand {
        val wasBitPerfect = currentState.mode == AudioOutputMode.BIT_PERFECT_OPT_IN
        val reason = if (isUsbAudio) {
            AutoPauseReason.USB_CONNECTED_ROUTE_CHANGE
        } else {
            AutoPauseReason.OUTPUT_ROUTE_CHANGED
        }
        currentState = currentState.copy(
            mode = AudioOutputMode.SAFE_VOLUME_MODE,
            isPendingSafetyWarning = false,
            isHardwareVolumeVerified = isHardwareVolumeVerified,
            isUsbAudioConnected = isUsbAudio,
            activeDeviceName = deviceName.ifBlank { "Audio Device" },
            lastAutoPauseReason = reason,
        )
        return SafetyCommand(
            type = SafetyEventType.AUTO_PAUSE,
            autoPauseReason = reason,
            requiresClearPreferredMixerAttributes = wasBitPerfect || isUsbAudio,
        )
    }

    @Synchronized
    fun onUsbDisconnected(fallbackDeviceName: String = "Internal Speaker"): SafetyCommand {
        currentState = currentState.copy(
            mode = AudioOutputMode.SAFE_VOLUME_MODE,
            isPendingSafetyWarning = false,
            isHardwareVolumeVerified = false,
            isUsbAudioConnected = false,
            activeDeviceName = fallbackDeviceName.ifBlank { "Internal Speaker" },
            lastAutoPauseReason = AutoPauseReason.USB_DISCONNECTED,
        )
        return SafetyCommand(
            type = SafetyEventType.AUTO_PAUSE,
            autoPauseReason = AutoPauseReason.USB_DISCONNECTED,
            requiresClearPreferredMixerAttributes = true,
        )
    }

    @Synchronized
    fun onBecomingNoisy(): SafetyCommand {
        val wasBitPerfect = currentState.mode == AudioOutputMode.BIT_PERFECT_OPT_IN
        currentState = currentState.copy(
            mode = AudioOutputMode.SAFE_VOLUME_MODE,
            isPendingSafetyWarning = false,
            lastAutoPauseReason = AutoPauseReason.BECOMING_NOISY,
        )
        return SafetyCommand(
            type = SafetyEventType.AUTO_PAUSE,
            autoPauseReason = AutoPauseReason.BECOMING_NOISY,
            requiresClearPreferredMixerAttributes = wasBitPerfect,
        )
    }

    @Synchronized
    fun onOutputRouteChanged(
        deviceName: String,
        isUsbAudio: Boolean,
        isHardwareVolumeVerified: Boolean = false,
    ): SafetyCommand {
        val wasBitPerfect = currentState.mode == AudioOutputMode.BIT_PERFECT_OPT_IN
        currentState = currentState.copy(
            mode = AudioOutputMode.SAFE_VOLUME_MODE,
            isPendingSafetyWarning = false,
            isHardwareVolumeVerified = isHardwareVolumeVerified,
            isUsbAudioConnected = isUsbAudio,
            activeDeviceName = deviceName.ifBlank { "Device Output" },
            lastAutoPauseReason = AutoPauseReason.OUTPUT_ROUTE_CHANGED,
        )
        return SafetyCommand(
            type = SafetyEventType.AUTO_PAUSE,
            autoPauseReason = AutoPauseReason.OUTPUT_ROUTE_CHANGED,
            requiresClearPreferredMixerAttributes = wasBitPerfect || !isUsbAudio,
        )
    }

    @Synchronized
    fun updateHardwareVolumeVerification(verified: Boolean) {
        currentState = currentState.copy(isHardwareVolumeVerified = verified)
    }

    companion object {
        const val FIXED_VOLUME_WARNING_MESSAGE: String =
            "Hardware volume control is not verified for this output device. " +
                "Bit-Perfect Mode may bypass system volume and play at full 100% (0 dBFS) output, " +
                "which can cause hearing damage on high-sensitivity earphones/IEMs. " +
                "Remove earphones before starting playback or stay in Safe Mode."
    }
}
