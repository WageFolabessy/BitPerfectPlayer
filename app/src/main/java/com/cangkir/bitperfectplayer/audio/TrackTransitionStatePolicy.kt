package com.cangkir.bitperfectplayer.audio

/**
 * Pure-Kotlin state-resolution policy eliminating stale previous-track durations and audio format
 * specs during rapid track transitions (`Next`, `Previous`, Queue selection) before `MediaCodec`
 * finishes priming (zero `android.*` imports).
 */
object TrackTransitionStatePolicy {

    fun isEngineSyncedToActiveTrack(activeTrack: PlaybackTrack?, engineTrackUri: String?, engineStatus: PlaybackStatus): Boolean {
        if (activeTrack == null || engineStatus == PlaybackStatus.PREPARING) return false
        return engineTrackUri == null || engineTrackUri == activeTrack.uriString
    }

    fun shouldPreservePlayingUiOnTrackSwitch(wasPlaying: Boolean, autoPlay: Boolean): Boolean = wasPlaying && autoPlay

    fun isEffectivelyPlayingForUi(status: PlaybackStatus, preservePlayingUiDuringTransition: Boolean): Boolean =
        status == PlaybackStatus.PLAYING ||
            (preservePlayingUiDuringTransition && status == PlaybackStatus.PREPARING)

    fun resolveDisplayDurationMs(
        activeTrack: PlaybackTrack?,
        engineTrackUri: String?,
        engineStatus: PlaybackStatus,
        engineDurationMs: Long,
    ): Long {
        if (activeTrack == null) return 0L
        if (isEngineSyncedToActiveTrack(activeTrack, engineTrackUri, engineStatus)) {
            return engineDurationMs.takeIf { it > 0L } ?: activeTrack.durationMs.coerceAtLeast(0L)
        }
        return activeTrack.durationMs.coerceAtLeast(0L)
    }

    /**
     * Prevents a lingering elapsed timestamp (e.g. `00:01`) from the outgoing track from displaying
     * under a newly selected track's title before the audio engine finishes transitioning.
     */
    fun resolveDisplayPositionMs(activeTrack: PlaybackTrack?, engineTrackUri: String?, livePositionMs: Long): Long {
        if (activeTrack == null) return 0L
        if (engineTrackUri != null && engineTrackUri != activeTrack.uriString) return 0L
        return livePositionMs.coerceAtLeast(0L)
    }

    fun resolveDisplayFormatSpec(
        activeTrack: PlaybackTrack?,
        engineTrackUri: String?,
        engineStatus: PlaybackStatus,
        diagnosticsSourceFormatSummary: String,
    ): String {
        if (activeTrack == null) return ""
        if (!isEngineSyncedToActiveTrack(activeTrack, engineTrackUri, engineStatus)) {
            return activeTrack.formatBadge
        }
        return diagnosticsSourceFormatSummary
            .takeIf { it != "No active track loaded" && it.isNotBlank() }
            ?.replace(" • ", " · ")
            ?: activeTrack.formatBadge
    }

    fun buildPendingTrackDiagnostics(
        safetyState: AudioSafetyState,
        apiLevel: Int = 34,
        outputDeviceName: String = safetyState.activeDeviceName,
        outputConnectionType: OutputConnectionType = OutputConnectionType.INTERNAL_SPEAKER,
        negotiatedMixerBehavior: NegotiatedMixerBehavior = NegotiatedMixerBehavior.NOT_APPLICABLE_SAFE_MODE,
        negotiatedMixerSummary: String = "Default System Mixer (Safe Mode)",
        replayGainSummary: String = "ReplayGain: OFF",
        container: String = "AUDIO",
        codec: String = "Unknown",
        sampleRateHz: Int = 0,
        bitDepth: Int? = null,
    ): AudioDiagnosticSnapshot = AudioDiagnosticMapper.map(
        AudioDiagnosticInput(
            isTrackLoaded = true,
            sourceContainer = container,
            sourceCodec = codec,
            sourceSampleRateHz = sampleRateHz,
            sourceBitDepth = bitDepth,
            sourceHeaderVerified = false,
            decodedPcmEncodingLabel = "Preparing",
            decodedEffectiveBitDepth = 0,
            decodedSampleRateHz = 0,
            decodedChannelCount = 0,
            decoderName = "Preparing",
            audioTrackSampleRateHz = 0,
            audioTrackEncodingLabel = "None",
            safetyMode = safetyState.mode,
            isHardwareVolumeVerified = safetyState.isHardwareVolumeVerified,
            apiLevel = apiLevel,
            outputDeviceName = outputDeviceName,
            outputConnectionType = outputConnectionType,
            negotiatedMixerBehavior = negotiatedMixerBehavior,
            negotiatedMixerSummary = negotiatedMixerSummary,
            replayGainSummary = replayGainSummary,
            isReplayGainModifyingSamples = false,
        ),
    )
}
