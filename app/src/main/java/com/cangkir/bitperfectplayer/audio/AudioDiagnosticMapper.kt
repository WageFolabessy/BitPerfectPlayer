package com.cangkir.bitperfectplayer.audio

/**
 * Confidence tier for every reported diagnostic field (SPEC Section 3.5).
 */
enum class VerificationLevel {
    VERIFIED,
    INDICATED,
    UNVERIFIABLE,
}

/**
 * The 6 separated audio reproduction stages (SPEC Section 3.1).
 */
enum class AudioPathStage(val stageNumber: Int, val title: String) {
    SOURCE_FILE(1, "Source File"),
    DECODED_PCM(2, "Decoded PCM Stream"),
    APP_TO_FRAMEWORK(3, "App to Android Audio Framework"),
    OS_POLICY_MIXER_RESAMPLER(4, "OS Audio Policy / Mixer / HAL"),
    USB_OUTPUT_DAC_PATH(5, "USB Audio / Output Device Path"),
    DAC_ANALOG_CONVERSION(6, "USB-C DAC Firmware & Analog Stage"),
}

enum class NegotiatedMixerBehavior {
    NOT_APPLICABLE_SAFE_MODE,
    UNSUPPORTED_API_BELOW_34,
    UNSUPPORTED_NON_USB_OUTPUT,
    DEFAULT_MIXER_FALLBACK,
    BIT_PERFECT,
}

enum class OutputConnectionType(val displayName: String) {
    USB_DEVICE("USB Audio Device"),
    USB_HEADSET("USB-C Audio Headset"),
    WIRED_HEADSET("Wired 3.5mm Headset"),
    BLUETOOTH("Bluetooth Audio"),
    INTERNAL_SPEAKER("Internal Speaker"),
    OTHER("Other Output Device"),
    ;

    val isUsb: Boolean
        get() = this == USB_DEVICE || this == USB_HEADSET
}

data class DiagnosticStageEntry(
    val stage: AudioPathStage,
    val summary: String,
    val detail: String,
    val verificationLevel: VerificationLevel,
)

/**
 * Raw runtime telemetry fed into [AudioDiagnosticMapper] from decoders, AudioTrack, AudioManager,
 * and [AudioSafetyController].
 */
data class AudioDiagnosticInput(
    val isTrackLoaded: Boolean = false,
    val sourceContainer: String = "None",
    val sourceCodec: String = "None",
    val sourceSampleRateHz: Int = 0,
    val sourceBitDepth: Int? = null,
    val sourceHeaderVerified: Boolean = false,
    val decodedPcmEncodingLabel: String = "None",
    val decodedEffectiveBitDepth: Int = 0,
    val decodedSampleRateHz: Int = 0,
    val decodedChannelCount: Int = 0,
    val decoderName: String = "Idle",
    val audioTrackSampleRateHz: Int = 0,
    val audioTrackEncodingLabel: String = "None",
    val safetyMode: AudioOutputMode = AudioOutputMode.SAFE_VOLUME_MODE,
    val isHardwareVolumeVerified: Boolean = false,
    val apiLevel: Int = 34,
    val outputDeviceName: String = "Internal Speaker",
    val outputConnectionType: OutputConnectionType = OutputConnectionType.INTERNAL_SPEAKER,
    val negotiatedMixerBehavior: NegotiatedMixerBehavior = NegotiatedMixerBehavior.NOT_APPLICABLE_SAFE_MODE,
    val negotiatedMixerSummary: String = "Default System Mixer (Safe Mode)",
)

/**
 * Complete real-time diagnostic snapshot rendered by the in-app Audio Diagnostics Panel.
 */
data class AudioDiagnosticSnapshot(
    val pathStatusHeadline: String,
    val isBitPerfectMixerActive: Boolean,
    val isBitDepthDownConvertedByDecoder: Boolean,
    val sourceFormatSummary: String,
    val decodedPcmSummary: String,
    val activeOutputDeviceSummary: String,
    val activeEngineAndMixerSummary: String,
    val dspEqVolumeStatusSummary: String,
    val stages: List<DiagnosticStageEntry>,
    val maximumClaimNotice: String = MAXIMUM_VERIFICATION_CLAIM_NOTICE,
) {
    companion object {
        const val MAXIMUM_VERIFICATION_CLAIM_NOTICE: String =
            "Verified up to Android Audio HAL / USB Output boundary; internal USB-C DAC cable firmware processing cannot be verified without external hardware instrumentation."

        fun idle(): AudioDiagnosticSnapshot = AudioDiagnosticMapper.map(AudioDiagnosticInput())
    }
}

/**
 * Pure-Kotlin mapper producing an honest, 6-stage [AudioDiagnosticSnapshot] (zero `android.*` imports).
 *
 * Never equates FLAC/WAV playback with verified bit-perfect output and always tags Stage 6
 * (internal USB-C DAC firmware & analog conversion) as [VerificationLevel.UNVERIFIABLE].
 */
object AudioDiagnosticMapper {

    const val FALLBACK_UNSUPPORTED_LABEL =
        "Clean Native Path — Bit-Perfect Not Supported by OS/Device"
    const val SAFE_MODE_LABEL =
        "Safe Mode (Volume Active) — Clean Native AudioTrack Path"
    const val NON_USB_FALLBACK_LABEL =
        "Clean Native Path — Bit-Perfect Requires USB Audio Output"
    const val API_BELOW_34_FALLBACK_LABEL =
        "Clean Native Path — Bit-Perfect Requires Android 14+ (API 34+)"
    const val BIT_PERFECT_ACTIVE_LABEL =
        "Bit-Perfect USB Mixer Active (Android 14+ HAL)"
    const val BIT_PERFECT_DOWNCONVERTED_LABEL =
        "Bit-Perfect USB Mixer Active (OS Decoder Output Down-Converted)"

    fun map(input: AudioDiagnosticInput): AudioDiagnosticSnapshot {
        val isBitPerfectNegotiated =
            input.safetyMode == AudioOutputMode.BIT_PERFECT_OPT_IN &&
                input.outputConnectionType.isUsb &&
                input.apiLevel >= 34 &&
                input.negotiatedMixerBehavior == NegotiatedMixerBehavior.BIT_PERFECT

        val isDownConverted =
            input.isTrackLoaded &&
                input.sourceBitDepth != null &&
                input.decodedEffectiveBitDepth > 0 &&
                input.decodedEffectiveBitDepth < input.sourceBitDepth

        val headline = when {
            input.safetyMode == AudioOutputMode.SAFE_VOLUME_MODE -> SAFE_MODE_LABEL
            !input.outputConnectionType.isUsb -> NON_USB_FALLBACK_LABEL
            input.apiLevel < 34 -> API_BELOW_34_FALLBACK_LABEL
            !isBitPerfectNegotiated -> FALLBACK_UNSUPPORTED_LABEL
            isDownConverted -> BIT_PERFECT_DOWNCONVERTED_LABEL
            else -> BIT_PERFECT_ACTIVE_LABEL
        }

        val sourceSummary = if (!input.isTrackLoaded) {
            "No active track loaded"
        } else {
            val bitPart = input.sourceBitDepth?.let { "${it}-bit" } ?: "Lossy / Variable"
            val ratePart = formatSampleRate(input.sourceSampleRateHz)
            "${input.sourceContainer} (${input.sourceCodec}) • $ratePart • $bitPart"
        }

        val decodedSummary = if (!input.isTrackLoaded || input.decodedSampleRateHz <= 0) {
            "Idle (no active PCM stream)"
        } else {
            val chLabel = formatChannels(input.decodedChannelCount)
            val downNote = if (isDownConverted) {
                " [OS decoder down-converted from ${input.sourceBitDepth}-bit]"
            } else {
                ""
            }
            "${input.decodedPcmEncodingLabel} • ${formatSampleRate(input.decodedSampleRateHz)} • $chLabel (${input.decoderName})$downNote"
        }

        val deviceSummary = "${input.outputDeviceName} (${input.outputConnectionType.displayName})"

        val engineSummary = if (isBitPerfectNegotiated) {
            "Native AudioTrack + API 34+ MIXER_BEHAVIOR_BIT_PERFECT (${input.negotiatedMixerSummary})"
        } else {
            "Clean Native AudioTrack (${input.negotiatedMixerSummary})"
        }

        val volumeModeLabel = when (input.safetyMode) {
            AudioOutputMode.SAFE_VOLUME_MODE ->
                "Safe Mode (System Volume Active)"
            AudioOutputMode.BIT_PERFECT_OPT_IN ->
                if (input.isHardwareVolumeVerified) {
                    "Bit-Perfect Opt-In (Hardware Volume Verified)"
                } else {
                    "Bit-Perfect Opt-In (Potentially Fixed 100% / 0 dBFS — Unverified HW Volume)"
                }
        }
        val dspEqVolumeSummary = "EQ: OFF • DSP: OFF • Normalization: OFF • Crossfade: OFF • $volumeModeLabel"

        val stage1 = DiagnosticStageEntry(
            stage = AudioPathStage.SOURCE_FILE,
            summary = sourceSummary,
            detail = if (input.isTrackLoaded) {
                "Container=${input.sourceContainer}, Codec=${input.sourceCodec}, Source Rate=${formatSampleRate(input.sourceSampleRateHz)}, Bit Depth=${input.sourceBitDepth?.let { "$it-bit" } ?: "N/A"}"
            } else {
                "Select a local audio file to inspect source stream properties."
            },
            verificationLevel = if (input.isTrackLoaded && input.sourceHeaderVerified) {
                VerificationLevel.VERIFIED
            } else {
                VerificationLevel.INDICATED
            },
        )

        val stage2 = DiagnosticStageEntry(
            stage = AudioPathStage.DECODED_PCM,
            summary = decodedSummary,
            detail = if (input.isTrackLoaded && input.decodedSampleRateHz > 0) {
                "Decoder=${input.decoderName}, Encoding=${input.decodedPcmEncodingLabel}, Effective Precision=${input.decodedEffectiveBitDepth}-bit, Channels=${input.decodedChannelCount}"
            } else {
                "PCM decoder inactive."
            },
            verificationLevel = if (input.isTrackLoaded && input.decodedSampleRateHz > 0) {
                VerificationLevel.VERIFIED
            } else {
                VerificationLevel.INDICATED
            },
        )

        val stage3 = DiagnosticStageEntry(
            stage = AudioPathStage.APP_TO_FRAMEWORK,
            summary = if (input.isTrackLoaded && input.audioTrackSampleRateHz > 0) {
                "AudioTrack ${input.audioTrackEncodingLabel} @ ${formatSampleRate(input.audioTrackSampleRateHz)} • Zero App DSP"
            } else {
                "Clean Native AudioTrack Engine • Zero App DSP"
            },
            detail = dspEqVolumeSummary,
            verificationLevel = VerificationLevel.VERIFIED,
        )

        val stage4Level = when {
            isBitPerfectNegotiated -> VerificationLevel.VERIFIED
            input.apiLevel >= 34 && input.negotiatedMixerBehavior == NegotiatedMixerBehavior.DEFAULT_MIXER_FALLBACK ->
                VerificationLevel.VERIFIED
            else -> VerificationLevel.INDICATED
        }
        val stage4 = DiagnosticStageEntry(
            stage = AudioPathStage.OS_POLICY_MIXER_RESAMPLER,
            summary = headline,
            detail = "API ${input.apiLevel} • Mixer=${input.negotiatedMixerSummary}",
            verificationLevel = stage4Level,
        )

        val stage5 = DiagnosticStageEntry(
            stage = AudioPathStage.USB_OUTPUT_DAC_PATH,
            summary = deviceSummary,
            detail = if (input.outputConnectionType.isUsb) {
                "USB audio route reported by Android AudioDeviceInfo; hardware volume verified=${input.isHardwareVolumeVerified}."
            } else {
                "Non-USB route reported by Android AudioDeviceInfo."
            },
            verificationLevel = VerificationLevel.INDICATED,
        )

        val stage6 = DiagnosticStageEntry(
            stage = AudioPathStage.DAC_ANALOG_CONVERSION,
            summary = "Internal USB-C DAC Firmware & Analog Stage — Unverifiable in Software",
            detail = AudioDiagnosticSnapshot.MAXIMUM_VERIFICATION_CLAIM_NOTICE,
            verificationLevel = VerificationLevel.UNVERIFIABLE,
        )

        return AudioDiagnosticSnapshot(
            pathStatusHeadline = headline,
            isBitPerfectMixerActive = isBitPerfectNegotiated,
            isBitDepthDownConvertedByDecoder = isDownConverted,
            sourceFormatSummary = sourceSummary,
            decodedPcmSummary = decodedSummary,
            activeOutputDeviceSummary = deviceSummary,
            activeEngineAndMixerSummary = engineSummary,
            dspEqVolumeStatusSummary = dspEqVolumeSummary,
            stages = listOf(stage1, stage2, stage3, stage4, stage5, stage6),
            maximumClaimNotice = AudioDiagnosticSnapshot.MAXIMUM_VERIFICATION_CLAIM_NOTICE,
        )
    }

    private fun formatSampleRate(hz: Int): String = when {
        hz <= 0 -> "Unknown Hz"
        hz % 1000 == 0 -> "${hz / 1000} kHz"
        else -> String.format(java.util.Locale.US, "%.1f kHz", hz / 1000.0)
    }

    private fun formatChannels(count: Int): String = when (count) {
        1 -> "Mono (1ch)"
        2 -> "Stereo (2ch)"
        in 3..Int.MAX_VALUE -> "${count}ch"
        else -> "Unknown ch"
    }
}
