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
    DEFAULT_NATIVE_RATE,
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
    val replayGainSummary: String = "ReplayGain: OFF",
    val isReplayGainModifyingSamples: Boolean = false,
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
    val isNativeSampleRateMixerActive: Boolean = false,
) {
    companion object {
        const val MAXIMUM_VERIFICATION_CLAIM_NOTICE: String =
            "Verified up to Android Audio HAL / USB Output boundary; " +
                "internal USB-C DAC cable firmware processing cannot be verified " +
                "without external hardware instrumentation."

        fun idle(): AudioDiagnosticSnapshot = AudioDiagnosticMapper.map(AudioDiagnosticInput())
    }
}

/**
 * Pure-Kotlin mapper producing an honest, 6-stage [AudioDiagnosticSnapshot] (zero `android.*` imports).
 *
 * Uses exact `Hz` sample-rate units via [AudioFormatFormatter], avoids redundant `FLAC (FLAC)` labels,
 * never equates lossless playback or native-sample-rate default mixing with verified bit-perfect output,
 * reports Safe-Mode-only ReplayGain honestly in Stage 3, and always marks Stage 6 (internal USB-C
 * DAC firmware & analog conversion) as [VerificationLevel.UNVERIFIABLE].
 */
object AudioDiagnosticMapper {

    const val FALLBACK_UNSUPPORTED_LABEL =
        "Clean Native Path — Bit-Perfect Not Supported by OS/Device"
    const val SAFE_MODE_LABEL =
        "Safe Mode (Volume Active) — Clean Native AudioTrack Path"
    const val SAFE_MODE_NATIVE_RATE_LABEL =
        "Safe Mode (Volume Active) — Native Sample-Rate USB Mixer (Android 14+ HAL)"
    const val BIT_PERFECT_FALLBACK_NATIVE_RATE_LABEL =
        "Native Sample-Rate USB Mixer — Bit-Perfect Unavailable (Volume Active)"
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

        val isNativeRateMixerNegotiated =
            input.outputConnectionType.isUsb &&
                input.apiLevel >= 34 &&
                input.negotiatedMixerBehavior == NegotiatedMixerBehavior.DEFAULT_NATIVE_RATE

        val isDownConverted =
            input.isTrackLoaded &&
                input.sourceBitDepth != null &&
                input.decodedEffectiveBitDepth > 0 &&
                input.decodedEffectiveBitDepth < input.sourceBitDepth

        val headline = resolveHeadline(input, isBitPerfectNegotiated, isNativeRateMixerNegotiated, isDownConverted)
        val sourceLabel = AudioFormatFormatter.formatContainerAndCodec(
            container = input.sourceContainer,
            codec = input.sourceCodec,
        )
        val sourceSummary = buildSourceSummary(input, sourceLabel)
        val decodedSummary = buildDecodedSummary(input, isDownConverted)
        val deviceSummary = "${input.outputDeviceName} (${input.outputConnectionType.displayName})"
        val engineSummary = if (isBitPerfectNegotiated || isNativeRateMixerNegotiated) {
            "Native AudioTrack · API 34+ ${input.negotiatedMixerSummary}"
        } else {
            "Clean Native AudioTrack · ${input.negotiatedMixerSummary}"
        }

        val volumeModeLabel = resolveVolumeModeLabel(input, isBitPerfectNegotiated, isNativeRateMixerNegotiated)
        val effectiveReplayGainSummary = resolveEffectiveReplayGainSummary(input)
        val replayGainActiveInSafeMode =
            input.safetyMode == AudioOutputMode.SAFE_VOLUME_MODE && input.isReplayGainModifyingSamples
        val dspEqVolumeSummary =
            "EQ: OFF · DSP: OFF · Crossfade: OFF · $effectiveReplayGainSummary · $volumeModeLabel"

        val stages = listOf(
            buildStage1Source(input, sourceLabel, sourceSummary),
            buildStage2Decoded(input, decodedSummary),
            buildStage3AppToFramework(
                input = input,
                replayGainActiveInSafeMode = replayGainActiveInSafeMode,
                effectiveReplayGainSummary = effectiveReplayGainSummary,
                dspEqVolumeSummary = dspEqVolumeSummary,
            ),
            buildStage4OsMixer(input, isBitPerfectNegotiated, isNativeRateMixerNegotiated, headline),
            buildStage5UsbPath(input, deviceSummary),
            buildStage6DacAnalog(),
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
            stages = stages,
            maximumClaimNotice = AudioDiagnosticSnapshot.MAXIMUM_VERIFICATION_CLAIM_NOTICE,
            isNativeSampleRateMixerActive = isNativeRateMixerNegotiated,
        )
    }

    private fun resolveHeadline(
        input: AudioDiagnosticInput,
        isBitPerfectNegotiated: Boolean,
        isNativeRateMixerNegotiated: Boolean,
        isDownConverted: Boolean,
    ): String = when {
        input.safetyMode == AudioOutputMode.SAFE_VOLUME_MODE && isNativeRateMixerNegotiated ->
            SAFE_MODE_NATIVE_RATE_LABEL
        input.safetyMode == AudioOutputMode.SAFE_VOLUME_MODE -> SAFE_MODE_LABEL
        !input.outputConnectionType.isUsb -> NON_USB_FALLBACK_LABEL
        input.apiLevel < 34 -> API_BELOW_34_FALLBACK_LABEL
        isBitPerfectNegotiated && isDownConverted -> BIT_PERFECT_DOWNCONVERTED_LABEL
        isBitPerfectNegotiated -> BIT_PERFECT_ACTIVE_LABEL
        isNativeRateMixerNegotiated -> BIT_PERFECT_FALLBACK_NATIVE_RATE_LABEL
        else -> FALLBACK_UNSUPPORTED_LABEL
    }

    private fun buildSourceSummary(input: AudioDiagnosticInput, sourceLabel: String): String {
        if (!input.isTrackLoaded) return "No active track loaded"
        val parts = mutableListOf(sourceLabel)
        if (input.sourceBitDepth != null && input.sourceBitDepth > 0) {
            parts.add("${input.sourceBitDepth}-bit")
        } else {
            parts.add("Lossy / Variable")
        }
        parts.add(AudioFormatFormatter.formatSampleRateHz(input.sourceSampleRateHz))
        return parts.joinToString(" · ")
    }

    private fun buildDecodedSummary(input: AudioDiagnosticInput, isDownConverted: Boolean): String {
        if (!input.isTrackLoaded || input.decodedSampleRateHz <= 0) {
            return "Idle (no active PCM stream)"
        }
        val chLabel = formatChannels(input.decodedChannelCount)
        val downNote = if (isDownConverted) {
            " [OS decoder down-converted from ${input.sourceBitDepth}-bit]"
        } else {
            ""
        }
        val formattedRate = AudioFormatFormatter.formatSampleRateHz(input.decodedSampleRateHz)
        return "${input.decodedPcmEncodingLabel} · $formattedRate · $chLabel · ${input.decoderName}$downNote"
    }

    private fun resolveVolumeModeLabel(
        input: AudioDiagnosticInput,
        isBitPerfectNegotiated: Boolean,
        isNativeRateMixerNegotiated: Boolean,
    ): String = when {
        input.safetyMode == AudioOutputMode.SAFE_VOLUME_MODE ->
            "Safe Mode (System Volume Active)"
        isBitPerfectNegotiated && input.isHardwareVolumeVerified ->
            "Bit-Perfect Active (Hardware Volume Verified)"
        isBitPerfectNegotiated ->
            "Bit-Perfect Active (Potentially Fixed 100% / 0 dBFS — Unverified HW Volume)"
        isNativeRateMixerNegotiated ->
            "Bit-Perfect Unavailable — Native Sample-Rate USB Mixer Active (System Volume Active)"
        else ->
            "Bit-Perfect Requested — Fallback Mixer Active (System Volume Active)"
    }

    private fun resolveEffectiveReplayGainSummary(input: AudioDiagnosticInput): String = if (
        input.safetyMode == AudioOutputMode.BIT_PERFECT_OPT_IN &&
        input.replayGainSummary != "ReplayGain: OFF"
    ) {
        "ReplayGain: Bypassed (Bit-Perfect Active)"
    } else {
        input.replayGainSummary.ifBlank { "ReplayGain: OFF" }
    }

    private fun buildStage1Source(input: AudioDiagnosticInput, sourceLabel: String, sourceSummary: String): DiagnosticStageEntry =
        DiagnosticStageEntry(
            stage = AudioPathStage.SOURCE_FILE,
            summary = sourceSummary,
            detail = if (input.isTrackLoaded) {
                val formattedRate = AudioFormatFormatter.formatSampleRateHz(input.sourceSampleRateHz)
                val depthText = input.sourceBitDepth?.let { "$it-bit" } ?: "N/A"
                "Format=$sourceLabel, Source Rate=$formattedRate, Bit Depth=$depthText"
            } else {
                "Select a local audio file to inspect source stream properties."
            },
            verificationLevel = if (input.isTrackLoaded && input.sourceHeaderVerified) {
                VerificationLevel.VERIFIED
            } else {
                VerificationLevel.INDICATED
            },
        )

    private fun buildStage2Decoded(input: AudioDiagnosticInput, decodedSummary: String): DiagnosticStageEntry {
        val activeStream = input.isTrackLoaded && input.decodedSampleRateHz > 0
        return DiagnosticStageEntry(
            stage = AudioPathStage.DECODED_PCM,
            summary = decodedSummary,
            detail = if (activeStream) {
                val formattedRate = AudioFormatFormatter.formatSampleRateHz(input.decodedSampleRateHz)
                "Decoder=${input.decoderName}, " +
                    "Actual Output Encoding=${input.decodedPcmEncodingLabel}, " +
                    "Effective Precision=${input.decodedEffectiveBitDepth}-bit, " +
                    "Rate=$formattedRate, Channels=${input.decodedChannelCount}"
            } else {
                "PCM decoder inactive."
            },
            verificationLevel = if (activeStream) VerificationLevel.VERIFIED else VerificationLevel.INDICATED,
        )
    }

    private fun buildStage3AppToFramework(
        input: AudioDiagnosticInput,
        replayGainActiveInSafeMode: Boolean,
        effectiveReplayGainSummary: String,
        dspEqVolumeSummary: String,
    ): DiagnosticStageEntry {
        val stage3ProcessingTag = if (replayGainActiveInSafeMode) {
            effectiveReplayGainSummary
        } else {
            "Zero App DSP · $effectiveReplayGainSummary"
        }
        val hasAudioTrackRate = input.isTrackLoaded && input.audioTrackSampleRateHz > 0
        val formattedRate = if (hasAudioTrackRate) {
            AudioFormatFormatter.formatSampleRateHz(input.audioTrackSampleRateHz)
        } else {
            ""
        }
        return DiagnosticStageEntry(
            stage = AudioPathStage.APP_TO_FRAMEWORK,
            summary = if (hasAudioTrackRate) {
                "AudioTrack ${input.audioTrackEncodingLabel} @ $formattedRate · $stage3ProcessingTag"
            } else {
                "Clean Native AudioTrack Engine · $stage3ProcessingTag"
            },
            detail = if (hasAudioTrackRate) {
                "AudioTrack Encoding=${input.audioTrackEncodingLabel}, Rate=$formattedRate · $dspEqVolumeSummary"
            } else {
                dspEqVolumeSummary
            },
            verificationLevel = VerificationLevel.VERIFIED,
        )
    }

    private fun buildStage4OsMixer(
        input: AudioDiagnosticInput,
        isBitPerfectNegotiated: Boolean,
        isNativeRateMixerNegotiated: Boolean,
        headline: String,
    ): DiagnosticStageEntry {
        val stage4Level = when {
            isBitPerfectNegotiated -> VerificationLevel.VERIFIED
            isNativeRateMixerNegotiated -> VerificationLevel.VERIFIED
            input.apiLevel >= 34 && input.negotiatedMixerBehavior == NegotiatedMixerBehavior.DEFAULT_MIXER_FALLBACK ->
                VerificationLevel.VERIFIED
            else -> VerificationLevel.INDICATED
        }
        return DiagnosticStageEntry(
            stage = AudioPathStage.OS_POLICY_MIXER_RESAMPLER,
            summary = headline,
            detail = "API ${input.apiLevel} · Mixer=${input.negotiatedMixerSummary}",
            verificationLevel = stage4Level,
        )
    }

    private fun buildStage5UsbPath(input: AudioDiagnosticInput, deviceSummary: String): DiagnosticStageEntry = DiagnosticStageEntry(
        stage = AudioPathStage.USB_OUTPUT_DAC_PATH,
        summary = deviceSummary,
        detail = if (input.outputConnectionType.isUsb) {
            "USB audio route reported by Android AudioDeviceInfo; hardware volume verified=${input.isHardwareVolumeVerified}."
        } else {
            "Non-USB route reported by Android AudioDeviceInfo."
        },
        verificationLevel = VerificationLevel.INDICATED,
    )

    private fun buildStage6DacAnalog(): DiagnosticStageEntry = DiagnosticStageEntry(
        stage = AudioPathStage.DAC_ANALOG_CONVERSION,
        summary = "Internal USB-C DAC Firmware & Analog Stage — Unverifiable in Software",
        detail = AudioDiagnosticSnapshot.MAXIMUM_VERIFICATION_CLAIM_NOTICE,
        verificationLevel = VerificationLevel.UNVERIFIABLE,
    )

    private fun formatChannels(count: Int): String = when (count) {
        1 -> "Mono (1ch)"
        2 -> "Stereo (2ch)"
        in 3..Int.MAX_VALUE -> "${count}ch"
        else -> "Unknown ch"
    }
}
