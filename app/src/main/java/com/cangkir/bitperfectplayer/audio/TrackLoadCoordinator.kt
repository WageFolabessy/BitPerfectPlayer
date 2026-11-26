package com.cangkir.bitperfectplayer.audio

import android.net.Uri
import java.util.concurrent.atomic.AtomicReference

internal enum class WorkerStartResult {
    STARTED,
    AUDIO_FOCUS_DENIED,
    AUDIO_TRACK_FAILED,
    ABORTED_BY_PAUSE,
}

internal data class TrackLoadRequestParams(
    val request: EngineTrackRequest,
    val generation: Long,
    val preserveAutoPauseNotice: Boolean,
    val forceAudioFocus: Boolean = false,
    val clearMixerBeforeCreate: Boolean = false,
    val resumeAttemptSeq: Long = 0L,
)

/**
 * Narrow engine-owned session callbacks invoked by [TrackLoadCoordinator] strictly while
 * [AudioOutputEngine]'s single `engineLock` is held.
 */
internal interface TrackLoadHost {
    fun isLoadStale(generation: Long): Boolean
    fun beginTrackLoadLocked(request: EngineTrackRequest, clearMixerBeforeCreate: Boolean): Long
    fun finishOpeningTrackUri(uriString: String)
    fun shouldScanReplayGain(): Boolean
    fun applyReplayGainMetadataLocked(metadata: ReplayGainMetadata?)
    fun canAutoPlayLoad(request: EngineTrackRequest, resumeAttemptSeq: Long): Boolean
    fun bindSinkMetadataLocked(sink: AudioOutputSink)
    fun commitLoadedSessionLocked(decoder: PcmAudioDecoder, sink: AudioOutputSink, initialPosMs: Long)
    fun replaceActiveSinkLocked(sink: AudioOutputSink?)
    fun startPlaybackWorkerLocked(
        initialSeekMs: Long,
        expectedGeneration: Long,
        forceAudioFocus: Boolean,
        clearPendingSeek: Boolean,
        resumeAttemptSeq: Long,
    ): WorkerStartResult

    fun onTrackLoadErrorLocked(generation: Long, message: String)
    fun clearPreparingFlagsIfUnbound(requestUri: Uri)
    fun publishLoadedTrackStateLocked(
        request: EngineTrackRequest,
        format: DecodedPcmFormat,
        startResult: WorkerStartResult,
        preserveAutoPauseNotice: Boolean,
    )
}

/**
 * Internal collaborator decomposing track opening, preloaded-decoder staging, ReplayGain scanning,
 * [AudioOutputSink] creation, and 1-shot HAL start retry into focused steps (`< 60` lines each)
 * while preserving [AudioOutputEngine]'s single-lock `engineLock` + `loadGeneration` discipline.
 */
internal class TrackLoadCoordinator(
    private val safetyController: AudioSafetyController,
    private val routeControl: AudioRouteControl,
    private val sinkFactory: AudioOutputSinkFactory,
    private val decoderFactory: PcmDecoderFactory,
    private val preloadController: GaplessPreloadController,
    private val metrics: EnginePlaybackMetrics,
    private val clock: EngineClock,
    private val host: TrackLoadHost,
) {
    private val stagedPreloadedTrack = AtomicReference<PreloadedTrack?>(null)

    private data class ResolvedTrackDecoder(
        val decoder: PcmAudioDecoder,
        val replayGainMetadata: ReplayGainMetadata?,
        val wasPreloaded: Boolean,
        val decoderOpenMs: Long,
        val rgScanMs: Long,
    )

    fun stagePreloadedTrack(track: PreloadedTrack?) {
        stagedPreloadedTrack.getAndSet(track)?.let { previous ->
            preloadController.closeDecoderOffMainThread(previous.decoder)
        }
    }

    fun takeStagedPreloadedTrackMatchingUri(uri: Uri): PreloadedTrack? {
        val current = stagedPreloadedTrack.getAndSet(null) ?: return null
        if (current.request.uri == uri) {
            return current
        }
        preloadController.closeDecoderOffMainThread(current.decoder)
        return null
    }

    fun clearStagedPreloadedTrack(asyncClose: Boolean) {
        stagedPreloadedTrack.getAndSet(null)?.let { previous ->
            if (asyncClose) {
                preloadController.closeDecoderOffMainThread(previous.decoder)
            } else {
                runCatching { previous.decoder.close() }
            }
        }
    }

    fun isBitPerfectOrPreferredRoute(sink: AudioOutputSink): Boolean = sink.isPinnedToPreferredUsbDevice ||
        routeControl.activeNegotiatedMixerBehavior == NegotiatedMixerBehavior.BIT_PERFECT ||
        routeControl.activeNegotiatedMixerBehavior == NegotiatedMixerBehavior.DEFAULT_NATIVE_RATE

    fun createSinkForMidStreamRebuildLocked(newFormat: DecodedPcmFormat): AudioOutputSink? = sinkFactory.tryCreateSinkLocked(newFormat)
        .onSuccess { created -> host.bindSinkMetadataLocked(created) }
        .getOrNull()

    fun loadTrackLocked(params: TrackLoadRequestParams) {
        val request = params.request
        val generation = params.generation
        if (host.isLoadStale(generation)) {
            host.finishOpeningTrackUri(request.uri.toString())
            return
        }
        val loadStartMs = clock.elapsedRealtimeMs()
        val stopOldMs = host.beginTrackLoadLocked(request, params.clearMixerBeforeCreate)
        metrics.logTrackOpen(request)

        val resolved = resolveDecoderAndReplayGainLocked(request, generation) ?: return
        if (host.isLoadStale(generation)) {
            cleanupStaleLoadLocked(decoder = resolved.decoder, sink = null, requestUri = request.uri)
            return
        }

        host.applyReplayGainMetadataLocked(resolved.replayGainMetadata)
        if (request.startPositionMs > 0L) {
            runCatching { resolved.decoder.seekToMs(request.startPositionMs) }
        }

        val effectiveAutoPlay = host.canAutoPlayLoad(request, params.resumeAttemptSeq)
        val effectiveRequest = if (effectiveAutoPlay == request.autoPlay) request else request.copy(autoPlay = effectiveAutoPlay)
        val sinkBuildStartMs = clock.elapsedRealtimeMs()
        val sink = createSinkForDecoderLocked(resolved.decoder, generation) ?: run {
            host.clearPreparingFlagsIfUnbound(request.uri)
            return
        }
        val trackCreateMs = metrics.elapsedSince(sinkBuildStartMs)
        if (host.isLoadStale(generation)) {
            cleanupStaleLoadLocked(decoder = resolved.decoder, sink = sink, requestUri = request.uri)
            return
        }
        activateLoadedTrackLocked(
            params = params.copy(request = effectiveRequest),
            decoder = resolved.decoder,
            sink = sink,
            timings = TrackLoadTimings(
                loadStartMs = loadStartMs,
                audioTrackBuildStartMs = sinkBuildStartMs,
                stopOldMs = stopOldMs,
                decoderOpenMs = resolved.decoderOpenMs,
                rgScanMs = resolved.rgScanMs,
                trackCreateMs = trackCreateMs,
                wasPreloaded = resolved.wasPreloaded,
            ),
        )
    }

    private fun resolveDecoderAndReplayGainLocked(request: EngineTrackRequest, generation: Long): ResolvedTrackDecoder? = try {
        val preloaded = takeMatchingPreloadedTrack(request, generation)
        val shouldReadRg = host.shouldScanReplayGain()
        if (preloaded != null) {
            resolveFromPreloadedTrack(request, preloaded, shouldReadRg)
        } else {
            resolveByOpeningFreshDecoderLocked(request, generation, shouldReadRg)
        }
    } finally {
        host.finishOpeningTrackUri(request.uri.toString())
    }

    private fun takeMatchingPreloadedTrack(request: EngineTrackRequest, generation: Long): PreloadedTrack? {
        if (request.startPositionMs != 0L || host.isLoadStale(generation)) {
            clearStagedPreloadedTrack(asyncClose = true)
            return null
        }
        return takeStagedPreloadedTrackMatchingUri(request.uri)
            ?: preloadController.takePreloadedTrackMatchingUri(request.uri)
    }

    private fun resolveFromPreloadedTrack(
        request: EngineTrackRequest,
        preloaded: PreloadedTrack,
        shouldReadRg: Boolean,
    ): ResolvedTrackDecoder {
        preloadController.consumeQueuedNextRequestIfMatches(request.uri)
        var rgScanMs = 0L
        val rgMeta = preloaded.replayGainMetadata ?: if (shouldReadRg) {
            val rgStartMs = clock.elapsedRealtimeMs()
            preloadController.readReplayGainMetadata(request.uri, request.fileName, request.mimeType).also {
                rgScanMs = metrics.elapsedSince(rgStartMs)
            }
        } else {
            null
        }
        return ResolvedTrackDecoder(
            decoder = preloaded.decoder,
            replayGainMetadata = rgMeta,
            wasPreloaded = true,
            decoderOpenMs = 0L,
            rgScanMs = rgScanMs,
        )
    }

    private fun resolveByOpeningFreshDecoderLocked(
        request: EngineTrackRequest,
        generation: Long,
        shouldReadRg: Boolean,
    ): ResolvedTrackDecoder? {
        preloadController.clearPreloadedNextLocked()
        // Open and prime decoder BEFORE creating sink so codec.outputFormat is confirmed.
        val openStartMs = clock.elapsedRealtimeMs()
        val opened = openDecoderForTrackLocked(request, generation)
        val decoderOpenMs = metrics.elapsedSince(openStartMs)
        if (opened == null) {
            routeControl.clearPreferredMixerAttributes(async = true)
            host.clearPreparingFlagsIfUnbound(request.uri)
            return null
        }
        var rgScanMs = 0L
        val rgMeta = if (shouldReadRg) {
            val rgStartMs = clock.elapsedRealtimeMs()
            preloadController.readReplayGainMetadata(request.uri, request.fileName, request.mimeType).also {
                rgScanMs = metrics.elapsedSince(rgStartMs)
            }
        } else {
            null
        }
        return ResolvedTrackDecoder(
            decoder = opened,
            replayGainMetadata = rgMeta,
            wasPreloaded = false,
            decoderOpenMs = decoderOpenMs,
            rgScanMs = rgScanMs,
        )
    }

    private fun openDecoderForTrackLocked(request: EngineTrackRequest, generation: Long): PcmAudioDecoder? = try {
        decoderFactory.open(request = request, isCancelled = { host.isLoadStale(generation) })
    } catch (_: AudioDecodeException.Cancelled) {
        null
    } catch (e: AudioDecodeException) {
        host.onTrackLoadErrorLocked(generation, e.message ?: "Unable to decode audio file")
        null
    }

    private fun createSinkForDecoderLocked(decoder: PcmAudioDecoder, generation: Long): AudioOutputSink? =
        sinkFactory.tryCreateSinkLocked(decoder.format)
            .onSuccess { created -> host.bindSinkMetadataLocked(created) }
            .onFailure { error ->
                metrics.logAudioTrackInitError(error, decoder.format)
                runCatching { decoder.close() }
                routeControl.clearPreferredMixerAttributes(async = true)
                host.onTrackLoadErrorLocked(generation, "AudioTrack initialization failed: ${error.message}")
            }
            .getOrNull()

    private fun activateLoadedTrackLocked(
        params: TrackLoadRequestParams,
        decoder: PcmAudioDecoder,
        sink: AudioOutputSink,
        timings: TrackLoadTimings,
    ) {
        val initialPosMs = if (params.request.startPositionMs == 0L) 0L else decoder.currentPositionMs.coerceAtLeast(0L)
        host.commitLoadedSessionLocked(decoder, sink, initialPosMs)
        metrics.resetWorkerStartTimings()

        val startResult = startLoadedTrackIfAutoPlayLocked(params, decoder, sink, initialPosMs)
        val startedPlaying = startResult == WorkerStartResult.STARTED
        if (startedPlaying) {
            metrics.logSlowAudioTrackBuildAndStart(timings.audioTrackBuildStartMs, decoder.format.sampleRateHz)
        }
        host.publishLoadedTrackStateLocked(
            request = params.request,
            format = decoder.format,
            startResult = startResult,
            preserveAutoPauseNotice = params.preserveAutoPauseNotice,
        )
        metrics.logTrackLoadLatency(params.request, decoder.format, timings, startedPlaying)
    }

    private fun startLoadedTrackIfAutoPlayLocked(
        params: TrackLoadRequestParams,
        decoder: PcmAudioDecoder,
        sink: AudioOutputSink,
        initialPosMs: Long,
    ): WorkerStartResult {
        if (!params.request.autoPlay) {
            routeControl.resumeAfterTransientFocusLoss = false
            routeControl.abandonAudioFocusLocked()
            return WorkerStartResult.AUDIO_FOCUS_DENIED
        }
        if (safetyController.state().mode == AudioOutputMode.SAFE_VOLUME_MODE) {
            sink.setVolume(1.0f)
        }
        val firstAttempt = host.startPlaybackWorkerLocked(
            initialSeekMs = initialPosMs,
            expectedGeneration = params.generation,
            forceAudioFocus = params.forceAudioFocus,
            clearPendingSeek = true,
            resumeAttemptSeq = params.resumeAttemptSeq,
        )
        if (firstAttempt != WorkerStartResult.AUDIO_TRACK_FAILED || host.isLoadStale(params.generation)) {
            return firstAttempt
        }
        return retryStartWithRebuiltSinkLocked(params, decoder, sink, initialPosMs)
    }

    private fun retryStartWithRebuiltSinkLocked(
        params: TrackLoadRequestParams,
        decoder: PcmAudioDecoder,
        failedSink: AudioOutputSink,
        initialPosMs: Long,
    ): WorkerStartResult {
        // One-shot sink rebuild with fresh USB mixer negotiation if OEM HAL rejected play()
        // right after recycling a prior high-rate stream (e.g. 96 kHz -> 96 kHz on USB Safe Mode).
        metrics.logAudioTrackStartRetry(decoder.format.sampleRateHz)
        failedSink.stopAndRelease()
        host.replaceActiveSinkLocked(null)
        routeControl.clearPreferredMixerAttributes(async = true)
        val rebuilt = sinkFactory.tryCreateSinkLocked(decoder.format)
            .onSuccess { configured -> host.bindSinkMetadataLocked(configured) }
            .getOrNull() ?: return WorkerStartResult.AUDIO_TRACK_FAILED
        host.replaceActiveSinkLocked(rebuilt)
        if (safetyController.state().mode == AudioOutputMode.SAFE_VOLUME_MODE) {
            rebuilt.setVolume(1.0f)
        }
        return host.startPlaybackWorkerLocked(
            initialSeekMs = initialPosMs,
            expectedGeneration = params.generation,
            forceAudioFocus = true,
            clearPendingSeek = true,
            resumeAttemptSeq = params.resumeAttemptSeq,
        )
    }

    private fun cleanupStaleLoadLocked(decoder: PcmAudioDecoder, sink: AudioOutputSink?, requestUri: Uri) {
        sink?.stopAndRelease()
        runCatching { decoder.close() }
        routeControl.clearPreferredMixerAttributes(async = true)
        host.clearPreparingFlagsIfUnbound(requestUri)
    }
}
