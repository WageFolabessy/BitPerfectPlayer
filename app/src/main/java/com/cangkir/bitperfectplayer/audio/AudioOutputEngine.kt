package com.cangkir.bitperfectplayer.audio

import android.content.Context
import android.net.Uri
import com.cangkir.bitperfectplayer.diagnostics.LocalDiagnosticLogger
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class PlaybackStatus { IDLE, PREPARING, PLAYING, PAUSED, COMPLETED, ERROR }

data class EngineTrackRequest(
    val uri: Uri,
    val fileName: String,
    val mimeType: String? = null,
    val startPositionMs: Long = 0L,
    val autoPlay: Boolean = true,
    val fingerprintPrefix: String = "none",
    val fileSizeBytes: Long = 0L,
    val expectedDurationMs: Long = 0L,
    val expectedContainer: String = "AUDIO",
    val expectedCodec: String = "Unknown",
    val expectedSampleRateHz: Int = 0,
    val expectedBitDepth: Int? = null,
)

/**
 * Observable low-frequency playback engine state (status, active track, duration, safety state,
 * and 6-stage diagnostics). High-frequency playback position (~20 Hz) is exposed separately via
 * [AudioOutputEngine.playbackPositionMs] so Compose screens do not recompose at 20 Hz.
 */
data class EnginePlaybackState(
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val activeTrackUri: Uri? = null,
    val durationMs: Long = 0L,
    val safetyState: AudioSafetyState = AudioSafetyState(),
    val diagnostics: AudioDiagnosticSnapshot = AudioDiagnosticSnapshot.idle(),
    val errorMessage: String? = null,
    val lastAutoPauseNotice: String? = null,
    val isPreparingPlayback: Boolean = false,
    val preservePlayingUiDuringTransition: Boolean = false,
) {
    val isPlaying: Boolean get() = status == PlaybackStatus.PLAYING
}

/**
 * Native Android playback engine coordinator preserving single-lock (`engineLock`) +
 * `loadGeneration` concurrency discipline while delegating focused subsystems to
 * [AudioRouteControl], [AudioOutputSinkFactory], [TrackLoadCoordinator],
 * [GaplessPreloadController], [TurntableScratchRunner], [DeferredPlayScheduler], and
 * [EnginePlaybackMetrics].
 */
class AudioOutputEngine internal constructor(
    private val safetyController: AudioSafetyController = AudioSafetyController(),
    private val onTrackCompleted: (() -> Unit)? = null,
    private val onGaplessTransition: ((EngineTrackRequest) -> Unit)? = null,
    private val onActivePlaybackElapsedMs: ((Long) -> Unit)? = null,
    private val diagnosticLogger: LocalDiagnosticLogger? = null,
    deps: AudioEngineDependencies,
) {
    constructor(
        context: Context,
        safetyController: AudioSafetyController = AudioSafetyController(),
        onTrackCompleted: (() -> Unit)? = null,
        onGaplessTransition: ((EngineTrackRequest) -> Unit)? = null,
        onActivePlaybackElapsedMs: ((Long) -> Unit)? = null,
        diagnosticLogger: LocalDiagnosticLogger? = null,
    ) : this(
        safetyController = safetyController,
        onTrackCompleted = onTrackCompleted,
        onGaplessTransition = onGaplessTransition,
        onActivePlaybackElapsedMs = onActivePlaybackElapsedMs,
        diagnosticLogger = diagnosticLogger,
        deps = AudioEngineDependencies.createAndroid(context, safetyController, diagnosticLogger),
    )

    private data class EngineControlLoadRequest(
        val request: EngineTrackRequest,
        val generation: Long,
        val preserveAutoPauseNotice: Boolean,
        val forceAudioFocus: Boolean,
        val clearMixerBeforeCreate: Boolean,
        val resumeAttemptSeq: Long = 0L,
    )

    private val _state = MutableStateFlow(EnginePlaybackState(safetyState = safetyController.state()))
    val state: StateFlow<EnginePlaybackState> = _state.asStateFlow()
    private val _playbackPositionMs = MutableStateFlow(0L)
    val playbackPositionMs: StateFlow<Long> = _playbackPositionMs.asStateFlow()
    private val _openingTrackUri = MutableStateFlow<String?>(null)
    val openingTrackUri: StateFlow<String?> = _openingTrackUri.asStateFlow()

    private val engineLock = ReentrantLock()
    private val controlExecutor: ExecutorService = deps.controlExecutor
    private val callbackDispatcher: EngineCallbackDispatcher = deps.callbackDispatcher
    private val clock: EngineClock = deps.clock
    private val commandCoalescer = PlaybackCommandCoalescer<EngineControlLoadRequest>()
    private val runtimeFlags = EngineRuntimeFlags()
    private val pendingRouteReconfigureOnPlay = AtomicBoolean(false)
    private val seekCoalescer = PendingSeekCoalescer(runtimeFlags.pendingSeekMs)
    private val lastPlayRequestElapsedMs = AtomicLong(0L)

    @Volatile private var consecutiveAutoRecoveries: Int = 0

    @Volatile private var replayGainConfig: ReplayGainConfig = ReplayGainConfig()

    @Volatile private var activeTrackReplayGainMetadata: ReplayGainMetadata? = null

    @Volatile
    private var activeReplayGainResolution: ReplayGainResolution =
        ReplayGainProcessor.resolve(ReplayGainConfig(), null, AudioOutputMode.SAFE_VOLUME_MODE)

    @Volatile private var currentRequest: EngineTrackRequest? = null

    @Volatile private var activeDecoder: PcmAudioDecoder? = null

    @Volatile private var activeSink: AudioOutputSink? = null

    @Volatile private var playbackThread: Thread? = null

    @Volatile private var audioTrackActualSampleRateHz: Int = 0

    @Volatile private var audioTrackActualEncodingLabel: String = "None"

    @Volatile private var activeTrackUsesBitPerfectOrPreferredRoute: Boolean = false

    @Volatile private var activeTrackPinnedToPreferredUsbDevice: Boolean = false

    private val metrics = EnginePlaybackMetrics(
        safetyController = safetyController,
        diagnosticLogger = diagnosticLogger,
        clock = clock,
        isDebugBuild = deps.isDebugBuild,
        slowStageDebugLogger = deps.slowStageDebugLogger,
    )

    private val routeControl: AudioRouteControl = deps.routeControlFactory(
        AudioRouteCallbacks(
            onSafetyCommand = ::handleSafetyCommand,
            onDiagnosticsChanged = ::publishDiagnostics,
            onFocusLossPause = ::handleFocusLossPause,
            onFocusGainResume = ::play,
            onDuckSafeModeVolume = { vol -> activeSink?.setVolume(vol) },
            isCurrentlyPlaying = { _state.value.isPlaying },
            isReleased = { runtimeFlags.releasedFlag.get() },
            currentEngineStatusName = { _state.value.status.name },
            isPlaybackActiveOrStarting = ::isPlaybackActiveOrStarting,
            onPausedSafeModeRouteRefreshed = ::handlePausedSafeModeRouteRefresh,
        ),
    )

    private val sinkBackend: AudioSinkBackend = deps.sinkBackendFactory(runtimeFlags, routeControl)
    private val sinkFactory: AudioOutputSinkFactory = sinkBackend.sinkFactory
    private val spectrumTap: AudioSpectrumTap = sinkBackend.spectrumTap

    private val deferredPlayScheduler = DeferredPlayScheduler(
        callbackDispatcher = callbackDispatcher,
        clock = clock,
        releasedFlag = runtimeFlags.releasedFlag,
        routeControl = routeControl,
        metrics = metrics,
        onPublishDiagnostics = ::publishDiagnostics,
    )

    private val preloadController = GaplessPreloadController(
        decoderFactory = deps.decoderFactory,
        releasedFlag = runtimeFlags.releasedFlag,
        loadGeneration = runtimeFlags.loadGeneration,
        shouldReadReplayGain = { AudioStartPathPolicy.shouldScanReplayGainMetadata(replayGainConfig.mode) },
        preloadExecutor = deps.preloadExecutor,
    )

    private val scratchRunner = TurntableScratchRunner(
        safetyController = safetyController,
        sinkFactory = sinkFactory,
        spectrumTap = spectrumTap,
        preloadController = preloadController,
        runtimeFlags = runtimeFlags,
        playbackPositionMs = _playbackPositionMs,
        activeReplayGainLinear = { activeReplayGainResolution.linearGain },
        onActivePlaybackElapsedMs = onActivePlaybackElapsedMs,
        onEndOfStream = ::handleEndOfStreamInLoop,
        onMidStreamFormatChanged = ::handleMidStreamFormatChangeInLoop,
        diagnosticLogger = diagnosticLogger,
        clock = clock,
    )

    private val trackLoadCoordinator = TrackLoadCoordinator(
        safetyController = safetyController,
        routeControl = routeControl,
        sinkFactory = sinkFactory,
        decoderFactory = deps.decoderFactory,
        preloadController = preloadController,
        metrics = metrics,
        clock = clock,
        host = createTrackLoadHost(),
    )

    private val safetyReconfigureHandler = AudioSafetyReconfigureHandler(
        routeControl = routeControl,
        preloadController = preloadController,
        scratchRunner = scratchRunner,
        deferredPlayScheduler = deferredPlayScheduler,
        runtimeFlags = runtimeFlags,
        metrics = metrics,
        clock = clock,
        host = createSafetyReconfigureHost(),
    )

    init {
        routeControl.initializeRouteMonitoring()
        publishDiagnostics()
    }

    fun setGaplessEnabled(enabled: Boolean) = preloadController.setGaplessEnabled(enabled)

    fun setReplayGainConfig(config: ReplayGainConfig) {
        replayGainConfig = config
        recomputeReplayGain()
        publishDiagnostics()
        val targetReq = currentRequest
        if (AudioStartPathPolicy.shouldScanReplayGainMetadata(config.mode) &&
            activeTrackReplayGainMetadata == null &&
            targetReq != null
        ) {
            val targetGen = runtimeFlags.loadGeneration.get()
            enqueueControlTask {
                if (runtimeFlags.releasedFlag.get() || runtimeFlags.loadGeneration.get() != targetGen) return@enqueueControlTask
                val loadedMeta = preloadController.readReplayGainMetadata(
                    uri = targetReq.uri,
                    fileName = targetReq.fileName,
                    mimeType = targetReq.mimeType,
                )
                engineLock.withLock {
                    if (shouldApplyLazyReplayGainLocked(targetReq, targetGen)) {
                        activeTrackReplayGainMetadata = loadedMeta
                        recomputeReplayGain()
                        publishDiagnostics()
                    }
                }
            }
        }
    }

    fun setNextTrack(request: EngineTrackRequest?) {
        val currentState = _state.value
        preloadController.setNextTrack(request, currentState.isPlaying, _playbackPositionMs.value, currentState.durationMs)
    }

    fun preparePendingTrackPosition(startPositionMs: Long) {
        // Invalidate any outgoing worker generation BEFORE resetting elapsed position so the
        // previous track's decode loop can never write a stale 00:01 over 00:00.
        runtimeFlags.loadGeneration.incrementAndGet()
        runtimeFlags.stopWorkerFlag.set(true)
        runtimeFlags.isPausedFlag.set(true)
        scratchRunner.abortScratch()
        seekCoalescer.clear()
        _playbackPositionMs.value = startPositionMs.coerceAtLeast(0L)
    }

    fun buildPendingDiagnosticsForTrack(track: PlaybackTrack): AudioDiagnosticSnapshot = routeControl.buildPendingTrackDiagnosticsSnapshot(
        safety = safetyController.state(),
        container = track.container,
        codec = track.codec,
        sampleRateHz = track.sampleRateHz,
        bitDepth = track.bitDepth,
        replayGainResolution = recomputeReplayGain(),
    )

    fun loadTrack(request: EngineTrackRequest) {
        if (runtimeFlags.releasedFlag.get()) return
        deferredPlayScheduler.cancelDeferredPlay()
        val generation = runtimeFlags.loadGeneration.incrementAndGet()
        val resumeAttemptSeq = if (request.autoPlay) {
            deferredPlayScheduler.beginResumeAttempt()
        } else {
            deferredPlayScheduler.cancelInFlightResumes()
            0L
        }
        consecutiveAutoRecoveries = 0
        pendingRouteReconfigureOnPlay.set(false)
        scratchRunner.abortScratch()
        seekCoalescer.clear()
        stageMatchingPreloadedTrack(request)
        runtimeFlags.stopWorkerFlag.set(true)
        runtimeFlags.isPausedFlag.set(true)
        currentRequest = request
        activeTrackReplayGainMetadata = null
        _playbackPositionMs.value = request.startPositionMs.coerceAtLeast(0L)
        _openingTrackUri.value = request.uri.toString()
        publishPreparingTrackState(request)
        val payload = EngineControlLoadRequest(
            request = request,
            generation = generation,
            preserveAutoPauseNotice = false,
            forceAudioFocus = false,
            clearMixerBeforeCreate = false,
            resumeAttemptSeq = resumeAttemptSeq,
        )
        if (commandCoalescer.submitLoadTrack(payload, startAfterLoad = request.autoPlay)) {
            scheduleControlDrain()
        }
    }

    fun play() {
        if (runtimeFlags.releasedFlag.get()) return
        val nowMs = clock.elapsedRealtimeMs()
        lastPlayRequestElapsedMs.set(nowMs)
        logEngineLifecycle(event = "PLAY_REQ", source = "ENGINE_PLAY")
        consecutiveAutoRecoveries = 0
        if (isAlreadyActivelyStreaming()) {
            _state.update {
                it.copy(
                    errorMessage = null,
                    lastAutoPauseNotice = null,
                    isPreparingPlayback = false,
                    preservePlayingUiDuringTransition = false,
                )
            }
            return
        }
        deferredPlayScheduler.evaluateAndDispatchPlay(
            nowMs = nowMs,
            onProceedNow = {
                // Immediately dismiss any Auto-paused banner or transient error on the very first Play tap.
                _state.update { it.copy(errorMessage = null, lastAutoPauseNotice = null, isPreparingPlayback = true) }
                if (commandCoalescer.submitPlay()) {
                    scheduleControlDrain()
                }
            },
            onDeferredWaiting = {
                _state.update { it.copy(errorMessage = null, lastAutoPauseNotice = null, isPreparingPlayback = true) }
            },
            onAborted = ::clearPreparingFlagsAfterAbortedPlay,
        )
    }

    /**
     * Adjusts output volume strictly for Safe Mode features (e.g., 3-second Sleep Timer fade-out).
     * Never invokes software volume scaling in [AudioOutputMode.BIT_PERFECT_OPT_IN].
     */
    fun setSafeModeOutputVolume(volume: Float) {
        if (safetyController.state().mode != AudioOutputMode.SAFE_VOLUME_MODE) return
        activeSink?.setVolume(volume.coerceIn(0.0f, 1.0f))
    }

    fun pause() {
        deferredPlayScheduler.cancelDeferredPlay()
        deferredPlayScheduler.cancelInFlightResumes()
        logEngineLifecycle(event = "PAUSE_REQ", source = "ENGINE_PAUSE")
        routeControl.resumeAfterTransientFocusLoss = false
        applyImmediatePauseState(notice = null)
        if (commandCoalescer.submitPause()) {
            scheduleControlDrain()
        }
    }

    fun notifyBackgroundStartBlocked(notice: String) {
        deferredPlayScheduler.cancelDeferredPlay()
        deferredPlayScheduler.cancelInFlightResumes()
        routeControl.resumeAfterTransientFocusLoss = false
        commandCoalescer.cancelPendingStartOnExternalPause()
        applyImmediatePauseState(notice = notice)
        enqueueControlTask {
            if (!runtimeFlags.releasedFlag.get()) {
                engineLock.withLock {
                    activeSink?.let { sink ->
                        logUnderrunDeltaIfAny(sink, trigger = "bg_blocked_pause")
                        sink.pause()
                    }
                    routeControl.abandonAudioFocusLocked()
                }
            }
        }
    }

    fun logSessionCleared(source: String = "MINI_PLAYER_SWIPE") {
        logEngineLifecycle(event = "SESSION_CLEARED", source = source)
    }

    fun beginTurntableScratch(): Boolean =
        scratchRunner.beginScratch(isPlaying = _state.value.isPlaying, hasActiveTrack = activeSink != null)

    fun updateTurntableScratchRate(playbackRate: Float) = scratchRunner.updateScratchRate(playbackRate)

    fun endTurntableScratch(fallbackTargetMs: Long? = null) = scratchRunner.endScratch(fallbackTargetMs)

    fun setSpectrumTapActive(active: Boolean) = spectrumTap.setSpectrumTapActive(active)

    fun sampleCurrentSpectrumWindow(outMonoSamples: FloatArray): Int =
        spectrumTap.sampleCurrentSpectrumWindow(activeSink, _state.value.isPlaying, outMonoSamples)

    fun seekTo(positionMs: Long) {
        if (runtimeFlags.releasedFlag.get()) return
        scratchRunner.abortScratch()
        preloadController.clearPreloadedNextAsync()
        val clamped = positionMs.coerceIn(0L, _state.value.durationMs.coerceAtLeast(0L))
        _playbackPositionMs.value = clamped
        // Always record atomically first so rapid seeks coalesce and a live worker thread handles
        // MediaCodec.flush() exclusively on its own single-owner decode thread.
        seekCoalescer.requestSeek(clamped)
        if (PlaybackSeekAndRecoveryPolicy.shouldDeferSeekToWorkerThread(playbackThread?.isAlive == true)) {
            return
        }
        enqueueControlTask {
            if (runtimeFlags.releasedFlag.get()) return@enqueueControlTask
            engineLock.withLock { seekWhilePausedLocked() }
        }
    }

    fun stop() {
        if (runtimeFlags.releasedFlag.get()) return
        deferredPlayScheduler.cancelDeferredPlay()
        deferredPlayScheduler.cancelInFlightResumes()
        logEngineLifecycle(event = "STOP_REQ", source = "ENGINE_STOP")
        scratchRunner.abortScratch()
        if (_state.value.status == PlaybackStatus.IDLE && activeSink == null && activeDecoder == null) {
            scratchRunner.releaseRingBuffer()
            spectrumTap.releaseSpectrumTap()
            trackLoadCoordinator.clearStagedPreloadedTrack(asyncClose = true)
            preloadController.clearPreloadedNextAsync()
            return
        }
        runtimeFlags.loadGeneration.incrementAndGet()
        trackLoadCoordinator.clearStagedPreloadedTrack(asyncClose = true)
        preloadController.clearQueuedAndPreloadedAsync()
        routeControl.resumeAfterTransientFocusLoss = false
        _openingTrackUri.value = null
        runtimeFlags.stopWorkerFlag.set(true)
        runtimeFlags.isPausedFlag.set(true)
        _state.update { it.copy(isPreparingPlayback = false, preservePlayingUiDuringTransition = false) }
        if (commandCoalescer.submitStop()) {
            scheduleControlDrain()
        }
    }

    fun requestBitPerfectMode(): SafetyCommand = safetyController.requestBitPerfectMode().also(::handleSafetyCommand)

    fun confirmBitPerfectWarning(): SafetyCommand = safetyController.confirmBitPerfectWarning().also(::handleSafetyCommand)

    fun dismissSafetyWarning(): SafetyCommand = safetyController.dismissSafetyWarning().also(::handleSafetyCommand)

    fun switchToSafeMode(): SafetyCommand = safetyController.switchToSafeMode().also(::handleSafetyCommand)

    fun clearAutoPauseNotice() = _state.update { it.copy(lastAutoPauseNotice = null) }

    fun release() {
        if (!runtimeFlags.releasedFlag.compareAndSet(false, true)) return
        deferredPlayScheduler.cancelDeferredPlay()
        deferredPlayScheduler.cancelCoalescedDiagnostics()
        deferredPlayScheduler.cancelInFlightResumes()
        scratchRunner.abortScratch()
        spectrumTap.deactivateAndReleaseSpectrumTap()
        runtimeFlags.loadGeneration.incrementAndGet()
        commandCoalescer.clearAll()
        _openingTrackUri.value = null
        runtimeFlags.stopWorkerFlag.set(true)
        runtimeFlags.isPausedFlag.set(true)
        routeControl.unregisterAudioMonitors()
        trackLoadCoordinator.clearStagedPreloadedTrack(asyncClose = false)
        preloadController.shutdown()
        runCatching {
            controlExecutor.execute {
                engineLock.withLock { teardownFullEngineResourcesLocked(asyncMixerClear = false) }
            }
        }
        controlExecutor.shutdown()
    }

    private fun createTrackLoadHost(): TrackLoadHost = object : TrackLoadHost {
        override fun isLoadStale(generation: Long): Boolean = this@AudioOutputEngine.isLoadStale(generation)

        override fun beginTrackLoadLocked(request: EngineTrackRequest, clearMixerBeforeCreate: Boolean): Long {
            _openingTrackUri.value = request.uri.toString()
            pendingRouteReconfigureOnPlay.set(false)
            val stopStartMs = clock.elapsedRealtimeMs()
            stopWorkerAndReleaseTrackLocked(clearMixer = clearMixerBeforeCreate, asyncMixerClear = true)
            val stopOldMs = metrics.elapsedSince(stopStartMs)
            currentRequest = request
            activeTrackReplayGainMetadata = null
            recomputeReplayGain()
            return stopOldMs
        }

        override fun finishOpeningTrackUri(uriString: String) {
            _openingTrackUri.compareAndSet(uriString, null)
        }

        override fun shouldScanReplayGain(): Boolean = AudioStartPathPolicy.shouldScanReplayGainMetadata(replayGainConfig.mode)

        override fun applyReplayGainMetadataLocked(metadata: ReplayGainMetadata?) {
            activeTrackReplayGainMetadata = metadata
            recomputeReplayGain()
        }

        override fun canAutoPlayLoad(request: EngineTrackRequest, resumeAttemptSeq: Long): Boolean = request.autoPlay &&
            !commandCoalescer.hasPendingPauseOrStop() &&
            (resumeAttemptSeq <= 0L || deferredPlayScheduler.isResumeAttemptValid(resumeAttemptSeq))

        override fun bindSinkMetadataLocked(sink: AudioOutputSink) {
            audioTrackActualSampleRateHz = sink.actualSampleRateHz
            audioTrackActualEncodingLabel = sink.actualEncodingLabel
            activeTrackUsesBitPerfectOrPreferredRoute = trackLoadCoordinator.isBitPerfectOrPreferredRoute(sink)
            activeTrackPinnedToPreferredUsbDevice = sink.isPinnedToPreferredUsbDevice
            metrics.resetUnderrunBaseline()
        }

        override fun commitLoadedSessionLocked(decoder: PcmAudioDecoder, sink: AudioOutputSink, initialPosMs: Long) {
            activeDecoder = decoder
            activeSink = sink
            _playbackPositionMs.value = initialPosMs
        }

        override fun replaceActiveSinkLocked(sink: AudioOutputSink?) {
            activeSink = sink
        }

        override fun startPlaybackWorkerLocked(
            initialSeekMs: Long,
            expectedGeneration: Long,
            forceAudioFocus: Boolean,
            clearPendingSeek: Boolean,
            resumeAttemptSeq: Long,
        ): WorkerStartResult = this@AudioOutputEngine.startPlaybackWorkerLocked(
            initialSeekMs = initialSeekMs,
            expectedGeneration = expectedGeneration,
            forceAudioFocus = forceAudioFocus,
            clearPendingSeek = clearPendingSeek,
            resumeAttemptSeq = resumeAttemptSeq,
        )

        override fun onTrackLoadErrorLocked(generation: Long, message: String) {
            activeDecoder = null
            if (runtimeFlags.loadGeneration.get() == generation) {
                _state.update {
                    it.copy(
                        status = PlaybackStatus.ERROR,
                        errorMessage = message,
                        isPreparingPlayback = false,
                        preservePlayingUiDuringTransition = false,
                    )
                }
                publishDiagnostics()
            }
        }

        override fun clearPreparingFlagsIfUnbound(requestUri: Uri) {
            if (!commandCoalescer.hasPendingLoad()) {
                _state.update { current ->
                    if (current.activeTrackUri == requestUri && current.status == PlaybackStatus.PREPARING) {
                        current.copy(isPreparingPlayback = false, preservePlayingUiDuringTransition = false)
                    } else {
                        current
                    }
                }
            }
        }

        override fun publishLoadedTrackStateLocked(
            request: EngineTrackRequest,
            format: DecodedPcmFormat,
            startResult: WorkerStartResult,
            preserveAutoPauseNotice: Boolean,
        ) {
            val startedPlaying = startResult == WorkerStartResult.STARTED
            if (!startedPlaying) {
                runtimeFlags.isPausedFlag.set(true)
            }
            if (startResult == WorkerStartResult.AUDIO_TRACK_FAILED) {
                pendingRouteReconfigureOnPlay.set(true)
            }
            val errMsg = when {
                !request.autoPlay || startedPlaying -> null
                startResult == WorkerStartResult.AUDIO_FOCUS_DENIED -> "Audio focus denied"
                else -> null
            }
            val preserveNotice = preserveAutoPauseNotice || startResult == WorkerStartResult.ABORTED_BY_PAUSE
            _state.update { current ->
                current.copy(
                    status = if (startedPlaying) PlaybackStatus.PLAYING else PlaybackStatus.PAUSED,
                    activeTrackUri = request.uri,
                    durationMs = format.durationMs.takeIf { it > 0L } ?: request.expectedDurationMs.coerceAtLeast(0L),
                    errorMessage = errMsg,
                    lastAutoPauseNotice = if (preserveNotice) current.lastAutoPauseNotice else null,
                    isPreparingPlayback = false,
                    preservePlayingUiDuringTransition = false,
                )
            }
            publishDiagnostics()
        }
    }

    private fun stageMatchingPreloadedTrack(request: EngineTrackRequest) {
        val matchedPreloaded = if (request.startPositionMs == 0L) {
            preloadController.takePreloadedTrackMatchingUri(request.uri)
        } else {
            null
        }
        trackLoadCoordinator.stagePreloadedTrack(matchedPreloaded)
        if (matchedPreloaded == null) {
            preloadController.clearPreloadedUnlessMatchesAsync(request.uri, request.startPositionMs)
        }
    }

    private fun publishPreparingTrackState(request: EngineTrackRequest) {
        val pendingDiag = routeControl.buildPendingTrackDiagnosticsSnapshot(
            safety = safetyController.state(),
            container = request.expectedContainer,
            codec = request.expectedCodec,
            sampleRateHz = request.expectedSampleRateHz,
            bitDepth = request.expectedBitDepth,
            replayGainResolution = recomputeReplayGain(),
        )
        val preservePlayingUi = TrackTransitionStatePolicy.shouldPreservePlayingUiOnTrackSwitch(
            wasPlaying = _state.value.let { current ->
                current.status == PlaybackStatus.PLAYING ||
                    (current.status == PlaybackStatus.PREPARING && current.preservePlayingUiDuringTransition)
            },
            autoPlay = request.autoPlay,
        )
        _state.update {
            it.copy(
                status = PlaybackStatus.PREPARING,
                activeTrackUri = request.uri,
                durationMs = request.expectedDurationMs.coerceAtLeast(0L),
                diagnostics = pendingDiag,
                errorMessage = null,
                lastAutoPauseNotice = null,
                isPreparingPlayback = request.autoPlay,
                preservePlayingUiDuringTransition = preservePlayingUi,
            )
        }
    }

    private fun isAlreadyActivelyStreaming(): Boolean {
        val current = _state.value
        return current.status == PlaybackStatus.PLAYING &&
            playbackThread?.isAlive == true &&
            !runtimeFlags.isPausedFlag.get() &&
            !pendingRouteReconfigureOnPlay.get() &&
            !commandCoalescer.hasPendingLoad() &&
            !deferredPlayScheduler.hasDeferredPlayPending()
    }

    private fun handleFocusLossPause() {
        deferredPlayScheduler.cancelDeferredPlay()
        deferredPlayScheduler.cancelInFlightResumes()
        commandCoalescer.cancelPendingStartOnExternalPause()
        applyImmediatePauseState(notice = null)
        enqueueControlTask {
            if (!runtimeFlags.releasedFlag.get()) {
                engineLock.withLock {
                    activeSink?.let { sink ->
                        logUnderrunDeltaIfAny(sink, trigger = "focus_loss_pause")
                        sink.pause()
                    }
                }
            }
        }
    }

    private fun shouldApplyLazyReplayGainLocked(targetReq: EngineTrackRequest, targetGen: Long): Boolean {
        val sameGeneration = !runtimeFlags.releasedFlag.get() && runtimeFlags.loadGeneration.get() == targetGen
        return sameGeneration && currentRequest?.uri == targetReq.uri && activeTrackReplayGainMetadata == null
    }

    private fun playLocked() {
        val attemptSeq = deferredPlayScheduler.currentResumeAttemptSeq()
        val playLockedEntryMs = clock.elapsedRealtimeMs()
        val requestStartMs = lastPlayRequestElapsedMs.getAndSet(0L).takeIf { it > 0L } ?: playLockedEntryMs
        val queueWaitMs = EngineMetricMath.elapsedDurationMs(requestStartMs, playLockedEntryMs)
        val sink = activeSink
        if (!isResumeAuthorizedForPlayback(attemptSeq, checkFlapping = true)) {
            abortSupersededPlayLocked(sink, pauseSink = false, abandonFocus = false, trackPlayMs = 0L, stage = "play_entry")
            return
        }
        val shouldReload = RouteChangeReconfigurePolicy.shouldReloadOnPlay(
            hasPendingRouteReconfigure = pendingRouteReconfigureOnPlay.get(),
            hasActiveDecoder = activeDecoder != null,
            hasActiveAudioTrack = sink != null,
            isCompleted = _state.value.status == PlaybackStatus.COMPLETED,
            isError = _state.value.status == PlaybackStatus.ERROR,
            isAudioTrackHealthy = sink?.isHealthy() == true,
        )
        if (shouldReload) {
            reloadCurrentTrackForPlayLocked(requestStartMs, queueWaitMs, attemptSeq)
            return
        }
        val activeOutput = sink ?: run {
            _state.update { it.copy(isPreparingPlayback = false, preservePlayingUiDuringTransition = false) }
            return
        }
        if (playbackThread?.isAlive != true) {
            resumeByRestartingWorkerLocked(activeOutput, requestStartMs, queueWaitMs, attemptSeq)
            return
        }
        if (!runtimeFlags.isPausedFlag.get() && _state.value.status == PlaybackStatus.PLAYING) {
            _state.update {
                it.copy(
                    errorMessage = null,
                    lastAutoPauseNotice = null,
                    isPreparingPlayback = false,
                    preservePlayingUiDuringTransition = false,
                )
            }
            return
        }
        resumeInPlaceSinkLocked(activeOutput, requestStartMs, queueWaitMs, attemptSeq)
    }

    private fun resumeInPlaceSinkLocked(activeOutput: AudioOutputSink, requestStartMs: Long, queueWaitMs: Long, attemptSeq: Long) {
        val startMs = clock.elapsedRealtimeMs()
        val focusStartMs = clock.elapsedRealtimeMs()
        if (!routeControl.requestAudioFocusLocked(forceRequest = true)) {
            runtimeFlags.isPausedFlag.set(true)
            activeOutput.pause()
            _state.update {
                it.copy(
                    status = PlaybackStatus.PAUSED,
                    errorMessage = "Audio focus unavailable",
                    isPreparingPlayback = false,
                    preservePlayingUiDuringTransition = false,
                )
            }
            return
        }
        val focusMs = metrics.elapsedSince(focusStartMs)
        if (!isResumeAuthorizedForPlayback(attemptSeq, checkFlapping = true)) {
            abortSupersededPlayLocked(activeOutput, pauseSink = false, abandonFocus = true, trackPlayMs = 0L, stage = "in_place_post_focus")
            return
        }
        if (safetyController.state().mode == AudioOutputMode.SAFE_VOLUME_MODE) {
            activeOutput.setVolume(1.0f)
        }
        val playStartMs = clock.elapsedRealtimeMs()
        val playOk = activeOutput.play()
        val trackPlayMs = metrics.elapsedSince(playStartMs)
        if (!isResumeAuthorizedForPlayback(attemptSeq, checkFlapping = false)) {
            abortSupersededPlayLocked(
                sink = activeOutput,
                pauseSink = playOk,
                abandonFocus = true,
                trackPlayMs = trackPlayMs,
                stage = "in_place_post_safePlay",
            )
            return
        }
        if (!playOk) {
            reloadCurrentTrackForPlayLocked(requestStartMs, queueWaitMs, attemptSeq)
            return
        }
        routeControl.resumeAfterTransientFocusLoss = false
        runtimeFlags.stopWorkerFlag.set(false)
        runtimeFlags.isPausedFlag.set(false)
        metrics.logSlowAudioTrackBuildAndStart(startMs, activeDecoder?.format?.sampleRateHz ?: 0)
        _state.update {
            it.copy(
                status = PlaybackStatus.PLAYING,
                errorMessage = null,
                lastAutoPauseNotice = null,
                isPreparingPlayback = false,
                preservePlayingUiDuringTransition = false,
            )
        }
        reportPlayResumeLatency("IN_PLACE_RESUME", queueWaitMs, focusMs, trackPlayMs, requestStartMs)
    }

    private fun resumeByRestartingWorkerLocked(activeOutput: AudioOutputSink, requestStartMs: Long, queueWaitMs: Long, attemptSeq: Long) {
        if (!isResumeAuthorizedForPlayback(attemptSeq, checkFlapping = true)) {
            abortSupersededPlayLocked(
                sink = activeOutput,
                pauseSink = false,
                abandonFocus = false,
                trackPlayMs = 0L,
                stage = "worker_restart_entry",
            )
            return
        }
        routeControl.resumeAfterTransientFocusLoss = false
        if (safetyController.state().mode == AudioOutputMode.SAFE_VOLUME_MODE) {
            activeOutput.setVolume(1.0f)
        }
        val startMs = clock.elapsedRealtimeMs()
        when (
            startPlaybackWorkerLocked(
                initialSeekMs = _playbackPositionMs.value,
                expectedGeneration = runtimeFlags.loadGeneration.get(),
                forceAudioFocus = true,
                clearPendingSeek = false,
                resumeAttemptSeq = attemptSeq,
            )
        ) {
            WorkerStartResult.STARTED -> {
                metrics.logSlowAudioTrackBuildAndStart(startMs, activeDecoder?.format?.sampleRateHz ?: 0)
                _state.update {
                    it.copy(
                        status = PlaybackStatus.PLAYING,
                        errorMessage = null,
                        lastAutoPauseNotice = null,
                        isPreparingPlayback = false,
                        preservePlayingUiDuringTransition = false,
                    )
                }
                reportPlayResumeLatency(
                    path = "WORKER_RESTART",
                    queueWaitMs = queueWaitMs,
                    focusMs = metrics.lastWorkerStartFocusMs,
                    trackPlayMs = metrics.lastWorkerStartTrackPlayMs,
                    requestStartMs = requestStartMs,
                )
            }
            WorkerStartResult.AUDIO_TRACK_FAILED -> reloadCurrentTrackForPlayLocked(requestStartMs, queueWaitMs, attemptSeq)
            WorkerStartResult.AUDIO_FOCUS_DENIED ->
                _state.update { it.copy(isPreparingPlayback = false, preservePlayingUiDuringTransition = false) }
            WorkerStartResult.ABORTED_BY_PAUSE -> {
                runtimeFlags.isPausedFlag.set(true)
                clearPreparingFlagsAfterAbortedPlay()
                publishDiagnostics()
            }
        }
    }

    private fun reloadCurrentTrackForPlayLocked(
        requestStartMs: Long = clock.elapsedRealtimeMs(),
        queueWaitMs: Long = 0L,
        attemptSeq: Long = deferredPlayScheduler.currentResumeAttemptSeq(),
    ) {
        if (!isResumeAuthorizedForPlayback(attemptSeq, checkFlapping = true)) {
            abortSupersededPlayLocked(activeSink, pauseSink = false, abandonFocus = false, trackPlayMs = 0L, stage = "reload_entry")
            return
        }
        pendingRouteReconfigureOnPlay.set(false)
        val req = currentRequest ?: return
        val resumePos = if (_state.value.status == PlaybackStatus.COMPLETED) {
            0L
        } else {
            seekCoalescer.pollPendingSeek() ?: _playbackPositionMs.value.coerceAtLeast(0L)
        }
        val generation = runtimeFlags.loadGeneration.incrementAndGet()
        trackLoadCoordinator.loadTrackLocked(
            TrackLoadRequestParams(
                request = req.copy(startPositionMs = resumePos, autoPlay = true),
                generation = generation,
                preserveAutoPauseNotice = false,
                forceAudioFocus = true,
                resumeAttemptSeq = attemptSeq,
            ),
        )
        if (_state.value.isPlaying) {
            reportPlayResumeLatency(
                path = "RELOAD",
                queueWaitMs = queueWaitMs,
                focusMs = metrics.lastWorkerStartFocusMs,
                trackPlayMs = metrics.lastWorkerStartTrackPlayMs,
                requestStartMs = requestStartMs,
            )
        }
    }

    private fun reportPlayResumeLatency(path: String, queueWaitMs: Long, focusMs: Long, trackPlayMs: Long, requestStartMs: Long) {
        metrics.logPlayResumeLatency(
            PlayResumeLatencyReport(
                path = path,
                queueWaitMs = queueWaitMs,
                focusMs = focusMs,
                trackPlayMs = trackPlayMs,
                requestStartMs = requestStartMs,
                sampleRateHz = activeDecoder?.format?.sampleRateHz ?: audioTrackActualSampleRateHz,
                encodingLabel = activeDecoder?.format?.encoding?.name ?: audioTrackActualEncodingLabel,
                pinnedUsb = activeTrackPinnedToPreferredUsbDevice,
            ),
        )
    }

    private fun seekWhilePausedLocked() {
        // Re-check INSIDE engineLock: if a decode worker started in the meantime, IT owns MediaCodec.
        if (PlaybackSeekAndRecoveryPolicy.shouldDeferSeekToWorkerThread(playbackThread?.isAlive == true)) return
        val target = seekCoalescer.pollPendingSeek() ?: return
        if (
            PlaybackSeekAndRecoveryPolicy.shouldRecordPositionOnlyWhilePaused(
                hasPendingRouteReconfigure = pendingRouteReconfigureOnPlay.get(),
                hasActiveDecoder = activeDecoder != null,
            )
        ) {
            _playbackPositionMs.value = target
            return
        }
        val decoder = activeDecoder ?: return
        val seekStartMs = clock.elapsedRealtimeMs()
        val actual = runCatching { decoder.seekToMs(target) }.getOrElse { err ->
            // Mark for clean reopen on next play() rather than surfacing a raw flush/state error.
            pendingRouteReconfigureOnPlay.set(true)
            metrics.logPausedSeekDeferredRebuild(err, target)
            target
        }
        activeSink?.flush()
        scratchRunner.resetRingBufferIfPresent(0L)
        spectrumTap.resetSpectrumTapIfPresent(decoder.format.sampleRateHz, 0L)
        _playbackPositionMs.value = actual
        metrics.logPausedSeekLatency(seekStartMs, target, actual)
    }

    private fun teardownFullEngineResourcesLocked(asyncMixerClear: Boolean = true) {
        pendingRouteReconfigureOnPlay.set(false)
        trackLoadCoordinator.clearStagedPreloadedTrack(asyncClose = false)
        preloadController.clearPreloadedNextLocked()
        stopWorkerAndReleaseTrackLocked(clearMixer = true, asyncMixerClear = asyncMixerClear)
        scratchRunner.releaseRingBuffer()
        spectrumTap.releaseSpectrumTap()
        routeControl.abandonAudioFocusLocked()
    }

    private fun isLoadStale(generation: Long): Boolean = runtimeFlags.releasedFlag.get() || runtimeFlags.loadGeneration.get() != generation

    private fun handleSafetyCommand(command: SafetyCommand) = safetyReconfigureHandler.handleSafetyCommand(command)

    private fun createSafetyReconfigureHost(): SafetyReconfigureHost = object : SafetyReconfigureHost {
        override fun currentStatus(): PlaybackStatus = _state.value.status
        override fun currentTrackRequest(): EngineTrackRequest? = currentRequest
        override fun currentActiveDecoder(): PcmAudioDecoder? = activeDecoder
        override fun currentActiveSink(): AudioOutputSink? = activeSink
        override fun hasPendingRouteReconfigure(): Boolean = pendingRouteReconfigureOnPlay.get()
        override fun setPendingRouteReconfigure(pending: Boolean) = pendingRouteReconfigureOnPlay.set(pending)
        override fun hasBitPerfectOrPreferredRoute(): Boolean = activeTrackUsesBitPerfectOrPreferredRoute
        override fun actualSampleRateHz(): Int = audioTrackActualSampleRateHz
        override fun recomputeReplayGain() {
            this@AudioOutputEngine.recomputeReplayGain()
        }

        override fun cancelPendingStartOnExternalPause() = commandCoalescer.cancelPendingStartOnExternalPause()
        override fun applyImmediatePauseState(notice: String?) = this@AudioOutputEngine.applyImmediatePauseState(notice)
        override fun publishSafePausedState() = this@AudioOutputEngine.publishSafePausedState()
        override fun publishSafetyAndDiagnostics() {
            _state.update { it.copy(safetyState = safetyController.state()) }
            publishDiagnostics()
        }

        override fun enqueueLockedControlTask(block: () -> Unit) {
            enqueueControlTask {
                if (!runtimeFlags.releasedFlag.get()) {
                    engineLock.withLock(block)
                }
            }
        }

        override fun logUnderrunDeltaIfAny(sink: AudioOutputSink?, trigger: String) =
            this@AudioOutputEngine.logUnderrunDeltaIfAny(sink, trigger)

        override fun stopWorkerAndReleaseTrackLocked(clearMixer: Boolean, asyncMixerClear: Boolean) =
            this@AudioOutputEngine.stopWorkerAndReleaseTrackLocked(clearMixer, asyncMixerClear)

        override fun loadReconfiguredTrackLocked(autoResume: Boolean, preserveAutoPauseNotice: Boolean, resumeAttemptSeq: Long) {
            val req = currentRequest ?: return
            val generation = runtimeFlags.loadGeneration.incrementAndGet()
            trackLoadCoordinator.loadTrackLocked(
                TrackLoadRequestParams(
                    request = req.copy(startPositionMs = _playbackPositionMs.value, autoPlay = autoResume),
                    generation = generation,
                    preserveAutoPauseNotice = preserveAutoPauseNotice,
                    forceAudioFocus = autoResume,
                    resumeAttemptSeq = resumeAttemptSeq,
                ),
            )
        }
    }

    private fun publishSafePausedState() {
        _state.update { current ->
            val safeStatus = if (current.status == PlaybackStatus.PLAYING || current.status == PlaybackStatus.PREPARING) {
                PlaybackStatus.PAUSED
            } else {
                current.status
            }
            current.copy(
                status = safeStatus,
                safetyState = safetyController.state(),
                isPreparingPlayback = false,
                preservePlayingUiDuringTransition = false,
            )
        }
        publishDiagnostics()
    }

    private fun applyImmediatePauseState(notice: String?) {
        scratchRunner.abortScratch()
        val wasPaused = runtimeFlags.isPausedFlag.getAndSet(true)
        val currentStatus = _state.value.status
        val needsStateUpdate = !wasPaused ||
            currentStatus == PlaybackStatus.PLAYING ||
            currentStatus == PlaybackStatus.PREPARING ||
            notice != null

        if (needsStateUpdate || _state.value.isPreparingPlayback || _state.value.preservePlayingUiDuringTransition) {
            _state.update { current ->
                val nextStatus = if (current.status == PlaybackStatus.PLAYING || current.status == PlaybackStatus.PREPARING) {
                    PlaybackStatus.PAUSED
                } else {
                    current.status
                }
                current.copy(
                    status = nextStatus,
                    safetyState = safetyController.state(),
                    lastAutoPauseNotice = notice ?: current.lastAutoPauseNotice,
                    isPreparingPlayback = false,
                    preservePlayingUiDuringTransition = false,
                )
            }
            publishDiagnostics()
        }
    }

    private fun scheduleControlDrain() {
        if (runtimeFlags.releasedFlag.get()) return
        enqueueControlTask {
            try {
                while (!runtimeFlags.releasedFlag.get()) {
                    val intent = commandCoalescer.pollNextIntent() ?: break
                    try {
                        engineLock.withLock {
                            if (!runtimeFlags.releasedFlag.get()) executeCoalescedIntentLocked(intent)
                        }
                    } finally {
                        commandCoalescer.markExecutionFinished()
                    }
                }
            } finally {
                if (commandCoalescer.onDrainExited() && !runtimeFlags.releasedFlag.get()) {
                    scheduleControlDrain()
                }
            }
        }
    }

    private fun executeCoalescedIntentLocked(intent: CoalescedControlIntent<EngineControlLoadRequest>) {
        when (intent) {
            is CoalescedControlIntent.LoadTrack -> {
                val payload = intent.request
                val effectiveRequest = if (intent.startAfterLoad == payload.request.autoPlay) {
                    payload.request
                } else {
                    payload.request.copy(autoPlay = intent.startAfterLoad)
                }
                val effectiveResumeSeq = if (intent.startAfterLoad) {
                    maxOf(payload.resumeAttemptSeq, deferredPlayScheduler.currentResumeAttemptSeq())
                } else {
                    0L
                }
                if (!isLoadStale(payload.generation)) {
                    trackLoadCoordinator.loadTrackLocked(
                        TrackLoadRequestParams(
                            request = effectiveRequest,
                            generation = payload.generation,
                            preserveAutoPauseNotice = payload.preserveAutoPauseNotice,
                            forceAudioFocus = payload.forceAudioFocus,
                            clearMixerBeforeCreate = payload.clearMixerBeforeCreate,
                            resumeAttemptSeq = effectiveResumeSeq,
                        ),
                    )
                }
            }
            CoalescedControlIntent.Play -> playLocked()
            CoalescedControlIntent.Pause -> {
                routeControl.resumeAfterTransientFocusLoss = false
                runtimeFlags.isPausedFlag.set(true)
                activeSink?.let { sink ->
                    logUnderrunDeltaIfAny(sink, trigger = "pause")
                    sink.pause()
                }
                routeControl.abandonAudioFocusLocked()
                applyImmediatePauseState(notice = null)
                metrics.requestFlush()
            }
            CoalescedControlIntent.Stop -> {
                teardownFullEngineResourcesLocked(asyncMixerClear = true)
                activeTrackReplayGainMetadata = null
                recomputeReplayGain()
                _playbackPositionMs.value = 0L
                _state.update {
                    it.copy(
                        status = PlaybackStatus.IDLE,
                        errorMessage = null,
                        isPreparingPlayback = false,
                        preservePlayingUiDuringTransition = false,
                    )
                }
                publishDiagnostics()
                metrics.requestFlush()
            }
        }
    }

    private fun enqueueControlTask(block: () -> Unit) {
        if (!runtimeFlags.releasedFlag.get()) runCatching { controlExecutor.execute(block) }
    }

    private fun startPlaybackWorkerLocked(
        initialSeekMs: Long,
        expectedGeneration: Long,
        forceAudioFocus: Boolean = false,
        clearPendingSeek: Boolean = true,
        resumeAttemptSeq: Long = deferredPlayScheduler.currentResumeAttemptSeq(),
    ): WorkerStartResult {
        metrics.resetWorkerStartTimings()
        val decoder = activeDecoder ?: return WorkerStartResult.AUDIO_TRACK_FAILED
        val sink = activeSink?.takeIf { it.isHealthy() } ?: return WorkerStartResult.AUDIO_TRACK_FAILED
        val startOutcome = requestFocusAndStartSinkLocked(sink, forceAudioFocus, resumeAttemptSeq)
        if (startOutcome != WorkerStartResult.STARTED) {
            return startOutcome
        }
        runtimeFlags.stopWorkerFlag.set(false)
        runtimeFlags.isPausedFlag.set(false)
        if (clearPendingSeek) seekCoalescer.clear()

        val worker = Thread(
            {
                sinkFactory.applyPlaybackThreadPriority()
                runPlaybackLoop(decoder, sink, initialSeekMs, expectedGeneration)
            },
            "BitPerfectAudioThread",
        )
        playbackThread = worker
        worker.start()
        return WorkerStartResult.STARTED
    }

    private fun requestFocusAndStartSinkLocked(
        sink: AudioOutputSink,
        forceAudioFocus: Boolean,
        resumeAttemptSeq: Long,
    ): WorkerStartResult {
        if (!isResumeAuthorizedForPlayback(resumeAttemptSeq, checkFlapping = true)) {
            abortSupersededPlayLocked(sink, pauseSink = false, abandonFocus = false, trackPlayMs = 0L, stage = "worker_entry")
            return WorkerStartResult.ABORTED_BY_PAUSE
        }
        val focusStartMs = clock.elapsedRealtimeMs()
        val focusGranted = routeControl.requestAudioFocusLocked(forceRequest = forceAudioFocus)
        metrics.lastWorkerStartFocusMs = metrics.elapsedSince(focusStartMs)
        if (!focusGranted) {
            runtimeFlags.isPausedFlag.set(true)
            sink.pause()
            _state.update { it.copy(status = PlaybackStatus.PAUSED, errorMessage = "Audio focus denied") }
            return WorkerStartResult.AUDIO_FOCUS_DENIED
        }
        if (!isResumeAuthorizedForPlayback(resumeAttemptSeq, checkFlapping = true)) {
            abortSupersededPlayLocked(sink, pauseSink = false, abandonFocus = true, trackPlayMs = 0L, stage = "worker_post_focus")
            return WorkerStartResult.ABORTED_BY_PAUSE
        }
        val playStartMs = clock.elapsedRealtimeMs()
        val playOk = sink.play()
        metrics.lastWorkerStartTrackPlayMs = metrics.elapsedSince(playStartMs)
        if (!isResumeAuthorizedForPlayback(resumeAttemptSeq, checkFlapping = false)) {
            abortSupersededPlayLocked(
                sink = sink,
                pauseSink = playOk,
                abandonFocus = true,
                trackPlayMs = metrics.lastWorkerStartTrackPlayMs,
                stage = "worker_post_safePlay",
            )
            return WorkerStartResult.ABORTED_BY_PAUSE
        }
        return if (playOk) {
            WorkerStartResult.STARTED
        } else {
            runtimeFlags.isPausedFlag.set(true)
            WorkerStartResult.AUDIO_TRACK_FAILED
        }
    }

    @Suppress("TooGenericExceptionCaught") // Top-level audio worker thread must surface OEM HAL errors safely instead of crashing
    private fun runPlaybackLoop(
        initialDecoder: PcmAudioDecoder,
        initialSink: AudioOutputSink,
        initialSeekMs: Long,
        expectedGeneration: Long,
    ) {
        val state = scratchRunner.createInitialLoopState(expectedGeneration, initialDecoder, initialSink, initialSeekMs)
        var recoveryScheduled = false
        try {
            while (sinkFactory.isGenerationActive(expectedGeneration)) {
                if (!scratchRunner.executeLoopStep(state)) break
            }
        } catch (e: AudioDecodeException.TransientCodecState) {
            recoveryScheduled = handleRecoverableWorkerFailure(expectedGeneration, isDeadTrack = false, error = e)
        } catch (e: AudioDecodeException.DeadAudioTrack) {
            recoveryScheduled = handleRecoverableWorkerFailure(expectedGeneration, isDeadTrack = true, error = e)
        } catch (e: IllegalStateException) {
            recoveryScheduled = handleRecoverableWorkerFailure(expectedGeneration, isDeadTrack = false, error = e)
        } catch (e: AudioDecodeException) {
            publishWorkerErrorIfRunning(expectedGeneration, e.message ?: "Playback decoding error")
        } catch (e: Exception) {
            publishWorkerErrorIfRunning(expectedGeneration, "Audio playback error: ${e.message}")
        } finally {
            scratchRunner.abortScratch()
            runCatching { scratchRunner.accumulateActivePlayTime(state, forceFlush = true) }
            reconcileStatusOnWorkerExit(expectedGeneration, recoveryScheduled)
        }
    }

    private fun handleRecoverableWorkerFailure(expectedGeneration: Long, isDeadTrack: Boolean, error: Throwable): Boolean {
        val canRecover = PlaybackSeekAndRecoveryPolicy.shouldAttemptInternalRecovery(
            isGenerationCurrent = runtimeFlags.loadGeneration.get() == expectedGeneration,
            isStoppingOrReleased = runtimeFlags.stopWorkerFlag.get() || runtimeFlags.releasedFlag.get(),
            isPaused = runtimeFlags.isPausedFlag.get(),
            isTransientRecoverableError = true,
            priorRecoveryAttempts = consecutiveAutoRecoveries,
        )
        if (canRecover) {
            consecutiveAutoRecoveries++
            val recoverySeq = deferredPlayScheduler.currentResumeAttemptSeq()
            val resumePos = seekCoalescer.pollPendingSeek() ?: _playbackPositionMs.value.coerceAtLeast(0L)
            metrics.logPlaybackAutoRecover(consecutiveAutoRecoveries, isDeadTrack, error, resumePos)
            enqueueControlTask {
                if (shouldAbortPendingAutoRecovery(expectedGeneration, recoverySeq)) return@enqueueControlTask
                engineLock.withLock {
                    if (shouldAbortPendingAutoRecovery(expectedGeneration, recoverySeq)) return@withLock
                    val req = currentRequest ?: return@withLock
                    val nextGen = runtimeFlags.loadGeneration.incrementAndGet()
                    trackLoadCoordinator.loadTrackLocked(
                        TrackLoadRequestParams(
                            request = req.copy(startPositionMs = resumePos, autoPlay = true),
                            generation = nextGen,
                            preserveAutoPauseNotice = false,
                            forceAudioFocus = true,
                            clearMixerBeforeCreate = true,
                            resumeAttemptSeq = recoverySeq,
                        ),
                    )
                }
            }
            return true
        }
        if (isDeadTrack && sinkFactory.isGenerationActive(expectedGeneration)) {
            pendingRouteReconfigureOnPlay.set(true)
            runtimeFlags.isPausedFlag.set(true)
            _state.update { it.copy(status = PlaybackStatus.PAUSED, errorMessage = null) }
            publishDiagnostics()
            return false
        }
        publishWorkerErrorIfRunning(expectedGeneration, error.message ?: "Playback decoding error")
        return false
    }

    private fun shouldAbortPendingAutoRecovery(expectedGeneration: Long, recoverySeq: Long): Boolean {
        val aborted = runtimeFlags.releasedFlag.get() || runtimeFlags.isPausedFlag.get()
        return aborted ||
            runtimeFlags.loadGeneration.get() != expectedGeneration ||
            !deferredPlayScheduler.isResumeAttemptValid(recoverySeq)
    }

    private fun reconcileStatusOnWorkerExit(expectedGeneration: Long, recoveryScheduled: Boolean) {
        val isGenCurrent = runtimeFlags.loadGeneration.get() == expectedGeneration
        _state.update { current ->
            val nextStatus = PlaybackSeekAndRecoveryPolicy.resolveStatusOnWorkerExit(
                currentStatus = current.status,
                isGenerationCurrent = isGenCurrent,
                isRecoveryScheduled = recoveryScheduled,
            )
            if (nextStatus != current.status) {
                current.copy(
                    status = nextStatus,
                    isPreparingPlayback = false,
                    preservePlayingUiDuringTransition = false,
                )
            } else {
                current
            }
        }
    }

    private fun publishWorkerErrorIfRunning(expectedGeneration: Long, message: String) {
        if (sinkFactory.isGenerationActive(expectedGeneration)) {
            metrics.logWorkerError(message, currentRequest?.fileName)
            _state.update {
                it.copy(
                    status = PlaybackStatus.ERROR,
                    errorMessage = message,
                    isPreparingPlayback = false,
                    preservePlayingUiDuringTransition = false,
                )
            }
        }
    }

    private fun handleEndOfStreamInLoop(state: PlaybackLoopState): Boolean {
        if (!sinkFactory.isGenerationActive(state.expectedGeneration)) return false
        scratchRunner.accumulateActivePlayTime(state, forceFlush = true)
        logUnderrunDeltaIfAny(state.currentSink, trigger = "eos")
        val preloaded = preloadController.pollSeamlessPreloadedTrack(state.currentFormat, state.expectedGeneration)
        if (preloaded != null) return applySeamlessGaplessTransitionInLoop(state, preloaded)
        if (scratchRunner.drainTrackAtEndOfStream(state)) return true
        if (sinkFactory.isGenerationActive(state.expectedGeneration)) {
            _playbackPositionMs.value = state.durationMs.takeIf { it > 0L } ?: _playbackPositionMs.value
            _state.update { it.copy(status = PlaybackStatus.COMPLETED) }
            callbackDispatcher.post { onTrackCompleted?.invoke() }
        }
        return false
    }

    private fun applySeamlessGaplessTransitionInLoop(state: PlaybackLoopState, preloaded: PreloadedTrack): Boolean {
        val transitioned = engineLock.withLock {
            if (!sinkFactory.isGenerationActive(state.expectedGeneration)) return@withLock false
            val oldDecoder = state.currentDecoder
            activeDecoder = preloaded.decoder
            currentRequest = preloaded.request
            preloadController.consumeQueuedNextRequestIfMatches(preloaded.request.uri)
            activeTrackReplayGainMetadata = preloaded.replayGainMetadata
            recomputeReplayGain()
            runCatching { oldDecoder.close() }
            true
        }
        if (!transitioned) {
            runCatching { preloaded.decoder.close() }
            return false
        }

        // Keep the exact same AudioOutputSink running continuously (0 ms gap, zero flush/rebuild).
        scratchRunner.completeSeamlessGaplessTransition(state, preloaded.decoder)
        _state.update {
            it.copy(
                status = PlaybackStatus.PLAYING,
                activeTrackUri = preloaded.request.uri,
                durationMs = state.durationMs,
                errorMessage = null,
            )
        }
        publishDiagnostics()
        metrics.logGaplessSwap(preloaded.request, state.currentFormat)
        callbackDispatcher.post { onGaplessTransition?.invoke(preloaded.request) }
        return true
    }

    private fun handleMidStreamFormatChangeInLoop(state: PlaybackLoopState, newFormat: DecodedPcmFormat): Boolean {
        scratchRunner.accumulateActivePlayTime(state, forceFlush = true)
        logUnderrunDeltaIfAny(state.currentSink, trigger = "format_change")
        metrics.logMidStreamFormatChange(newFormat)
        val rebuiltSink = engineLock.withLock {
            if (!sinkFactory.isGenerationActive(state.expectedGeneration)) return@withLock null
            state.currentSink.stopAndRelease()
            trackLoadCoordinator.createSinkForMidStreamRebuildLocked(newFormat)?.also { created ->
                activeSink = created
                if (!runtimeFlags.isPausedFlag.get()) created.play()
                publishDiagnostics()
            }
        } ?: return false
        scratchRunner.completeMidStreamSinkRebuild(state, rebuiltSink, newFormat)
        return true
    }

    private fun stopWorkerAndReleaseTrackLocked(clearMixer: Boolean, asyncMixerClear: Boolean = true) {
        scratchRunner.abortScratch()
        runtimeFlags.stopWorkerFlag.set(true)
        runtimeFlags.isPausedFlag.set(true)
        val sink = activeSink
        activeSink = null
        if (sink != null) {
            logUnderrunDeltaIfAny(sink, trigger = "release")
            sink.unblockForShutdown()
        }
        val thread = playbackThread
        playbackThread = null
        if (thread != null && thread != Thread.currentThread()) {
            joinWorkerCleanly(thread)
        }
        sink?.stopAndRelease()
        activeDecoder?.let { runCatching { it.close() } }
        activeDecoder = null
        scratchRunner.releaseRingBufferIfBitPerfect()
        if (clearMixer) {
            routeControl.clearPreferredMixerAttributes(async = asyncMixerClear)
        }
        audioTrackActualSampleRateHz = 0
        audioTrackActualEncodingLabel = "None"
        activeTrackUsesBitPerfectOrPreferredRoute = false
        activeTrackPinnedToPreferredUsbDevice = false
        metrics.resetUnderrunBaseline()
    }

    private fun joinWorkerCleanly(thread: Thread) {
        val deadlineMs = clock.elapsedRealtimeMs() + WORKER_JOIN_TIMEOUT_MS
        while (thread.isAlive) {
            thread.interrupt()
            val remainingMs = deadlineMs - clock.elapsedRealtimeMs()
            if (remainingMs <= 0L) break
            runCatching { thread.join(minOf(remainingMs, WORKER_JOIN_SLICE_MS)) }
        }
    }

    private fun logUnderrunDeltaIfAny(sink: AudioOutputSink?, trigger: String) {
        metrics.logUnderrunDeltaIfAny(sink, trigger, audioTrackActualSampleRateHz, audioTrackActualEncodingLabel)
    }

    private fun logEngineLifecycle(event: String, source: String) {
        metrics.logEngineLifecycle(
            event = event,
            source = source,
            snapshot = EngineLifecycleSnapshot(
                statusName = _state.value.status.name,
                posMs = _playbackPositionMs.value,
                trackHealthy = activeSink?.isHealthy() == true,
                workerAlive = playbackThread?.isAlive == true,
                pendingReconfig = pendingRouteReconfigureOnPlay.get(),
                hasFocus = routeControl.hasAudioFocus,
            ),
        )
    }

    private fun recomputeReplayGain(): ReplayGainResolution =
        ReplayGainProcessor.resolve(replayGainConfig, activeTrackReplayGainMetadata, safetyController.state().mode)
            .also { activeReplayGainResolution = it }

    private fun publishDiagnostics() {
        val safety = safetyController.state()
        val format = activeDecoder?.format
        val pendingReq = currentRequest
        val snapshot = if (format == null && _state.value.status == PlaybackStatus.PREPARING && pendingReq != null) {
            routeControl.buildPendingTrackDiagnosticsSnapshot(
                safety = safety,
                container = pendingReq.expectedContainer,
                codec = pendingReq.expectedCodec,
                sampleRateHz = pendingReq.expectedSampleRateHz,
                bitDepth = pendingReq.expectedBitDepth,
                replayGainResolution = recomputeReplayGain(),
            )
        } else {
            routeControl.buildDiagnosticSnapshot(
                safety = safety,
                activeFormat = format,
                audioTrackSampleRateHz = audioTrackActualSampleRateHz,
                audioTrackEncodingLabel = audioTrackActualEncodingLabel,
                replayGainResolution = recomputeReplayGain(),
            )
        }
        _state.update { current ->
            if (current.safetyState == safety && current.diagnostics == snapshot) {
                current
            } else {
                current.copy(safetyState = safety, diagnostics = snapshot)
            }
        }
    }

    private fun isPlaybackActiveOrStarting(): Boolean {
        val current = _state.value
        return current.status == PlaybackStatus.PLAYING ||
            current.status == PlaybackStatus.PREPARING ||
            current.isPreparingPlayback ||
            !runtimeFlags.isPausedFlag.get() ||
            commandCoalescer.isStartOrLoadInFlight() ||
            deferredPlayScheduler.hasDeferredPlayPending()
    }

    private fun handlePausedSafeModeRouteRefresh(coalesceDiagnostics: Boolean) =
        safetyReconfigureHandler.handlePausedSafeModeRouteRefresh(coalesceDiagnostics)

    private fun isResumeAuthorizedForPlayback(attemptSeq: Long, checkFlapping: Boolean): Boolean {
        if (runtimeFlags.releasedFlag.get() || commandCoalescer.hasPendingPauseOrStop()) return false
        if (attemptSeq <= 0L || !deferredPlayScheduler.isResumeAttemptValid(attemptSeq)) return false
        if (checkFlapping && routeControl.isUsbRouteActivelyFlapping(clock.elapsedRealtimeMs())) {
            return false
        }
        return true
    }

    private fun abortSupersededPlayLocked(
        sink: AudioOutputSink?,
        pauseSink: Boolean,
        abandonFocus: Boolean,
        trackPlayMs: Long,
        stage: String,
    ) {
        runtimeFlags.isPausedFlag.set(true)
        if (pauseSink && sink != null) {
            sink.pause()
        }
        if (abandonFocus) {
            routeControl.abandonAudioFocusLocked()
        }
        metrics.logPlayResumeAborted(stage, pauseSink, trackPlayMs, _state.value.status.name)
        clearPreparingFlagsAfterAbortedPlay()
        publishDiagnostics()
    }

    private fun clearPreparingFlagsAfterAbortedPlay() {
        _state.update { current ->
            val nextStatus = if (current.status == PlaybackStatus.PREPARING) {
                PlaybackStatus.PAUSED
            } else {
                current.status
            }
            if (nextStatus == current.status &&
                !current.isPreparingPlayback &&
                !current.preservePlayingUiDuringTransition
            ) {
                current
            } else {
                current.copy(
                    status = nextStatus,
                    isPreparingPlayback = false,
                    preservePlayingUiDuringTransition = false,
                )
            }
        }
    }

    companion object {
        private const val WORKER_JOIN_TIMEOUT_MS = 2_500L
        private const val WORKER_JOIN_SLICE_MS = 500L
    }
}
