package com.cangkir.bitperfectplayer.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioTrack
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.cangkir.bitperfectplayer.BuildConfig
import com.cangkir.bitperfectplayer.diagnostics.DiagnosticLogFormatter
import com.cangkir.bitperfectplayer.diagnostics.LocalDiagnosticLogger
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
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
 * Native Android [AudioTrack] playback engine coordinator delegating focused subsystems to
 * [AudioRouteAndMixerManager], [AudioTrackWriter], [GaplessPreloadController], and [TurntableScratchRunner].
 */
class AudioOutputEngine(
    context: Context,
    private val safetyController: AudioSafetyController = AudioSafetyController(),
    private val onTrackCompleted: (() -> Unit)? = null,
    private val onGaplessTransition: ((EngineTrackRequest) -> Unit)? = null,
    private val onActivePlaybackElapsedMs: ((Long) -> Unit)? = null,
    private val diagnosticLogger: LocalDiagnosticLogger? = null,
) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val playbackAudioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private val _state = MutableStateFlow(EnginePlaybackState(safetyState = safetyController.state()))
    val state: StateFlow<EnginePlaybackState> = _state.asStateFlow()
    private val _playbackPositionMs = MutableStateFlow(0L)
    val playbackPositionMs: StateFlow<Long> = _playbackPositionMs.asStateFlow()
    private val _openingTrackUri = MutableStateFlow<String?>(null)
    val openingTrackUri: StateFlow<String?> = _openingTrackUri.asStateFlow()

    private data class EngineControlLoadRequest(
        val request: EngineTrackRequest,
        val generation: Long,
        val preserveAutoPauseNotice: Boolean,
        val forceAudioFocus: Boolean,
        val clearMixerBeforeCreate: Boolean,
        val resumeAttemptSeq: Long = 0L,
    )

    private val engineLock = ReentrantLock()
    private val controlExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "BitPerfectEngineControl").apply { isDaemon = true }
    }
    private val commandCoalescer = PlaybackCommandCoalescer<EngineControlLoadRequest>()
    private val playResumeAbortGuard = PlayResumeAbortGuard()
    private val deferredPlayLock = Any()
    private var deferredPlayRunnable: Runnable? = null
    private var deferredPlayAttemptSeq: Long = 0L
    private var deferredPlayFirstRequestMs: Long = 0L
    private val coalescedDiagnosticsRunnable = Runnable {
        if (!releasedFlag.get()) {
            publishDiagnostics()
        }
    }
    private val stagedPreloadedTrack = AtomicReference<PreloadedTrack?>(null)
    private val loadGeneration = AtomicLong(0L)
    private val isPausedFlag = AtomicBoolean(true)
    private val stopWorkerFlag = AtomicBoolean(false)
    private val releasedFlag = AtomicBoolean(false)
    private val pendingRouteReconfigureOnPlay = AtomicBoolean(false)
    private val pendingSeekMs = AtomicLong(PendingSeekCoalescer.NO_PENDING_SEEK)
    private val seekCoalescer = PendingSeekCoalescer(pendingSeekMs)
    private val lastPlayRequestElapsedMs = AtomicLong(0L)

    @Volatile private var consecutiveAutoRecoveries: Int = 0

    @Volatile private var replayGainConfig: ReplayGainConfig = ReplayGainConfig()

    @Volatile private var activeTrackReplayGainMetadata: ReplayGainMetadata? = null

    @Volatile
    private var activeReplayGainResolution: ReplayGainResolution =
        ReplayGainProcessor.resolve(ReplayGainConfig(), null, AudioOutputMode.SAFE_VOLUME_MODE)

    @Volatile private var currentRequest: EngineTrackRequest? = null

    @Volatile private var activeDecoder: PcmAudioDecoder? = null

    @Volatile private var activeAudioTrack: AudioTrack? = null

    @Volatile private var playbackThread: Thread? = null

    @Volatile private var audioTrackActualSampleRateHz: Int = 0

    @Volatile private var audioTrackActualEncodingLabel: String = "None"

    @Volatile private var lastReportedUnderrunCount: Int = 0

    @Volatile private var activeTrackUsesBitPerfectOrPreferredRoute: Boolean = false

    @Volatile private var activeTrackPinnedToPreferredUsbDevice: Boolean = false

    @Volatile private var lastWorkerStartFocusMs: Long = 0L

    @Volatile private var lastWorkerStartTrackPlayMs: Long = 0L

    private val routeAndMixerManager = AudioRouteAndMixerManager(
        appContext, audioManager, mainHandler, playbackAudioAttributes, safetyController,
        onSafetyCommand = ::handleSafetyCommand,
        onDiagnosticsChanged = ::publishDiagnostics,
        onFocusLossPause = {
            cancelDeferredPlay()
            playResumeAbortGuard.cancelInFlightResumes()
            commandCoalescer.cancelPendingStartOnExternalPause()
            applyImmediatePauseState(notice = null)
            enqueueControlTask {
                if (!releasedFlag.get()) {
                    engineLock.withLock {
                        activeAudioTrack?.let { track ->
                            logUnderrunDeltaIfAny(track, trigger = "focus_loss_pause")
                            trackWriter.safePause(track)
                        }
                    }
                }
            }
        },
        onFocusGainResume = ::play,
        onDuckSafeModeVolume = { vol -> runCatching { activeAudioTrack?.setVolume(vol) } },
        isCurrentlyPlaying = { _state.value.isPlaying },
        isReleased = { releasedFlag.get() },
        diagnosticLogger = diagnosticLogger,
        currentEngineStatusName = { _state.value.status.name },
        isPlaybackActiveOrStarting = ::isPlaybackActiveOrStarting,
        onPausedSafeModeRouteRefreshed = ::handlePausedSafeModeRouteRefresh,
    )
    private val trackWriter = AudioTrackWriter(
        playbackAudioAttributes,
        stopWorkerFlag,
        releasedFlag,
        isPausedFlag,
        pendingSeekMs,
        loadGeneration,
        diagnosticLogger = diagnosticLogger,
        safetyModeProvider = { safetyController.state().mode },
        isUsbConnectedProvider = { safetyController.state().isUsbAudioConnected },
    )
    private val preloadController = GaplessPreloadController(
        appContext = appContext,
        releasedFlag = releasedFlag,
        loadGeneration = loadGeneration,
        shouldReadReplayGain = { AudioStartPathPolicy.shouldScanReplayGainMetadata(replayGainConfig.mode) },
    )
    private val scratchRunner = TurntableScratchRunner(
        safetyController, trackWriter, preloadController, stopWorkerFlag, releasedFlag, isPausedFlag, pendingSeekMs,
        playbackPositionMs = _playbackPositionMs,
        activeReplayGainLinear = { activeReplayGainResolution.linearGain },
        onActivePlaybackElapsedMs = onActivePlaybackElapsedMs,
        onEndOfStream = ::handleEndOfStreamInLoop,
        onMidStreamFormatChanged = ::handleMidStreamFormatChangeInLoop,
        diagnosticLogger = diagnosticLogger,
    )

    init {
        routeAndMixerManager.initializeRouteMonitoring()
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
            val targetGen = loadGeneration.get()
            enqueueControlTask {
                if (releasedFlag.get() || loadGeneration.get() != targetGen) return@enqueueControlTask
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

    private fun shouldApplyLazyReplayGainLocked(targetReq: EngineTrackRequest, targetGen: Long): Boolean {
        val sameGeneration = !releasedFlag.get() && loadGeneration.get() == targetGen
        return sameGeneration && currentRequest?.uri == targetReq.uri && activeTrackReplayGainMetadata == null
    }

    fun setNextTrack(request: EngineTrackRequest?) {
        val currentState = _state.value
        preloadController.setNextTrack(request, currentState.isPlaying, _playbackPositionMs.value, currentState.durationMs)
    }

    fun preparePendingTrackPosition(startPositionMs: Long) {
        // Invalidate any outgoing worker generation BEFORE resetting elapsed position so the
        // previous track's decode loop can never write a stale 00:01 over 00:00.
        loadGeneration.incrementAndGet()
        stopWorkerFlag.set(true)
        isPausedFlag.set(true)
        scratchRunner.abortScratch()
        seekCoalescer.clear()
        _playbackPositionMs.value = startPositionMs.coerceAtLeast(0L)
    }

    fun buildPendingDiagnosticsForTrack(track: PlaybackTrack): AudioDiagnosticSnapshot =
        routeAndMixerManager.buildPendingTrackDiagnosticsSnapshot(
            safety = safetyController.state(),
            container = track.container,
            codec = track.codec,
            sampleRateHz = track.sampleRateHz,
            bitDepth = track.bitDepth,
            replayGainResolution = recomputeReplayGain(),
        )

    fun loadTrack(request: EngineTrackRequest) {
        if (releasedFlag.get()) return
        cancelDeferredPlay()
        val generation = loadGeneration.incrementAndGet()
        val resumeAttemptSeq = if (request.autoPlay) {
            playResumeAbortGuard.beginResumeAttempt()
        } else {
            playResumeAbortGuard.cancelInFlightResumes()
            0L
        }
        consecutiveAutoRecoveries = 0
        pendingRouteReconfigureOnPlay.set(false)
        scratchRunner.abortScratch()
        seekCoalescer.clear()
        val matchedPreloaded = if (request.startPositionMs == 0L) {
            preloadController.takePreloadedTrackMatchingUri(request.uri)
        } else {
            null
        }
        stagePreloadedTrack(matchedPreloaded)
        if (matchedPreloaded == null) {
            preloadController.clearPreloadedUnlessMatchesAsync(request.uri, request.startPositionMs)
        }
        stopWorkerFlag.set(true)
        isPausedFlag.set(true)
        currentRequest = request
        activeTrackReplayGainMetadata = null
        _playbackPositionMs.value = request.startPositionMs.coerceAtLeast(0L)
        _openingTrackUri.value = request.uri.toString()
        val pendingDiag = routeAndMixerManager.buildPendingTrackDiagnosticsSnapshot(
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
        if (releasedFlag.get()) return
        val nowMs = SystemClock.elapsedRealtime()
        lastPlayRequestElapsedMs.set(nowMs)
        logEngineLifecycle(event = "PLAY_REQ", source = "ENGINE_PLAY")
        consecutiveAutoRecoveries = 0
        val current = _state.value
        val alreadyStreaming = current.status == PlaybackStatus.PLAYING &&
            playbackThread?.isAlive == true &&
            !isPausedFlag.get() &&
            !pendingRouteReconfigureOnPlay.get() &&
            !commandCoalescer.hasPendingLoad() &&
            !hasDeferredPlayPending()
        if (alreadyStreaming) {
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
        val attemptSeq = playResumeAbortGuard.beginResumeAttempt()
        val firstRequestMs = activeDeferredFirstRequestMs(nowMs)
        val routeGuard = routeAndMixerManager.evaluatePlayResume(
            nowElapsedMs = nowMs,
            firstPlayRequestElapsedMs = firstRequestMs,
        )
        when (routeGuard.decision) {
            PlayResumeRouteDecision.PROCEED_NOW -> {
                cancelDeferredPlay()
                // Immediately dismiss any Auto-paused banner or transient error on the very first Play tap.
                _state.update { it.copy(errorMessage = null, lastAutoPauseNotice = null, isPreparingPlayback = true) }
                if (commandCoalescer.submitPlay()) {
                    scheduleControlDrain()
                }
            }
            PlayResumeRouteDecision.DEFER_FOR_ROUTE_SETTLE -> {
                _state.update { it.copy(errorMessage = null, lastAutoPauseNotice = null, isPreparingPlayback = true) }
                diagnosticLogger?.logAudio("PLAY_DEFERRED_ROUTE_FLAP") {
                    linkedMapOf("delayMs" to routeGuard.deferDelayMs)
                }
                scheduleDeferredPlay(
                    attemptSeq = attemptSeq,
                    firstRequestMs = firstRequestMs,
                    delayMs = routeGuard.deferDelayMs,
                )
            }
            PlayResumeRouteDecision.ABORT_ROUTE_UNSTABLE -> {
                cancelDeferredPlay()
                playResumeAbortGuard.cancelInFlightResumes()
                diagnosticLogger?.logAudio("PLAY_ABORTED_ROUTE_FLAP") {
                    linkedMapOf("waitedMs" to (nowMs - firstRequestMs).coerceAtLeast(0L))
                }
                clearPreparingFlagsAfterAbortedPlay()
            }
        }
    }

    private fun playLocked() {
        val attemptSeq = playResumeAbortGuard.currentResumeAttemptSeq()
        val playLockedEntryMs = SystemClock.elapsedRealtime()
        val requestStartMs = lastPlayRequestElapsedMs.getAndSet(0L).takeIf { it > 0L } ?: playLockedEntryMs
        val queueWaitMs = (playLockedEntryMs - requestStartMs).coerceAtLeast(0L)
        val track = activeAudioTrack
        if (!isResumeAuthorizedForPlayback(attemptSeq, checkFlapping = true)) {
            abortSupersededPlayLocked(
                track = track,
                pauseTrack = false,
                abandonFocus = false,
                trackPlayMs = 0L,
                stage = "play_entry",
            )
            return
        }
        val shouldReload = RouteChangeReconfigurePolicy.shouldReloadOnPlay(
            hasPendingRouteReconfigure = pendingRouteReconfigureOnPlay.get(),
            hasActiveDecoder = activeDecoder != null,
            hasActiveAudioTrack = track != null,
            isCompleted = _state.value.status == PlaybackStatus.COMPLETED,
            isError = _state.value.status == PlaybackStatus.ERROR,
            isAudioTrackHealthy = trackWriter.isTrackHealthy(track),
        )
        if (shouldReload) {
            reloadCurrentTrackForPlayLocked(
                requestStartMs = requestStartMs,
                queueWaitMs = queueWaitMs,
                attemptSeq = attemptSeq,
            )
            return
        }
        val activeTrack = track ?: run {
            _state.update { it.copy(isPreparingPlayback = false, preservePlayingUiDuringTransition = false) }
            return
        }
        if (playbackThread?.isAlive != true) {
            resumeByRestartingWorkerLocked(activeTrack, requestStartMs, queueWaitMs, attemptSeq)
            return
        }
        if (!isPausedFlag.get() && _state.value.status == PlaybackStatus.PLAYING) {
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
        resumeInPlaceTrackLocked(activeTrack, requestStartMs, queueWaitMs, attemptSeq)
    }

    private fun resumeInPlaceTrackLocked(activeTrack: AudioTrack, requestStartMs: Long, queueWaitMs: Long, attemptSeq: Long) {
        val startMs = SystemClock.elapsedRealtime()
        val focusStartMs = SystemClock.elapsedRealtime()
        if (!routeAndMixerManager.requestAudioFocusLocked(forceRequest = true)) {
            isPausedFlag.set(true)
            trackWriter.safePause(activeTrack)
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
        val focusMs = (SystemClock.elapsedRealtime() - focusStartMs).coerceAtLeast(0L)
        if (!isResumeAuthorizedForPlayback(attemptSeq, checkFlapping = true)) {
            abortSupersededPlayLocked(
                track = activeTrack,
                pauseTrack = false,
                abandonFocus = true,
                trackPlayMs = 0L,
                stage = "in_place_post_focus",
            )
            return
        }
        if (safetyController.state().mode == AudioOutputMode.SAFE_VOLUME_MODE) {
            runCatching { activeTrack.setVolume(1.0f) }
        }
        val trackPlayStartMs = SystemClock.elapsedRealtime()
        val playOk = trackWriter.safePlay(activeTrack)
        val trackPlayMs = (SystemClock.elapsedRealtime() - trackPlayStartMs).coerceAtLeast(0L)
        if (!isResumeAuthorizedForPlayback(attemptSeq, checkFlapping = false)) {
            abortSupersededPlayLocked(
                track = activeTrack,
                pauseTrack = playOk,
                abandonFocus = true,
                trackPlayMs = trackPlayMs,
                stage = "in_place_post_safePlay",
            )
            return
        }
        if (!playOk) {
            reloadCurrentTrackForPlayLocked(
                requestStartMs = requestStartMs,
                queueWaitMs = queueWaitMs,
                attemptSeq = attemptSeq,
            )
            return
        }
        routeAndMixerManager.resumeAfterTransientFocusLoss = false
        stopWorkerFlag.set(false)
        isPausedFlag.set(false)
        logSlowAudioTrackBuildAndStart(startMs, activeDecoder?.format?.sampleRateHz ?: 0)
        _state.update {
            it.copy(
                status = PlaybackStatus.PLAYING,
                errorMessage = null,
                lastAutoPauseNotice = null,
                isPreparingPlayback = false,
                preservePlayingUiDuringTransition = false,
            )
        }
        logPlayResumeLatency(
            path = "IN_PLACE_RESUME",
            queueWaitMs = queueWaitMs,
            focusMs = focusMs,
            trackPlayMs = trackPlayMs,
            totalMs = (SystemClock.elapsedRealtime() - requestStartMs).coerceAtLeast(0L),
        )
    }

    private fun resumeByRestartingWorkerLocked(activeTrack: AudioTrack, requestStartMs: Long, queueWaitMs: Long, attemptSeq: Long) {
        if (!isResumeAuthorizedForPlayback(attemptSeq, checkFlapping = true)) {
            abortSupersededPlayLocked(
                track = activeTrack,
                pauseTrack = false,
                abandonFocus = false,
                trackPlayMs = 0L,
                stage = "worker_restart_entry",
            )
            return
        }
        routeAndMixerManager.resumeAfterTransientFocusLoss = false
        if (safetyController.state().mode == AudioOutputMode.SAFE_VOLUME_MODE) {
            runCatching { activeTrack.setVolume(1.0f) }
        }
        val startMs = SystemClock.elapsedRealtime()
        when (
            startPlaybackWorkerLocked(
                initialSeekMs = _playbackPositionMs.value,
                expectedGeneration = loadGeneration.get(),
                forceAudioFocus = true,
                clearPendingSeek = false,
                resumeAttemptSeq = attemptSeq,
            )
        ) {
            WorkerStartResult.STARTED -> {
                logSlowAudioTrackBuildAndStart(startMs, activeDecoder?.format?.sampleRateHz ?: 0)
                _state.update {
                    it.copy(
                        status = PlaybackStatus.PLAYING,
                        errorMessage = null,
                        lastAutoPauseNotice = null,
                        isPreparingPlayback = false,
                        preservePlayingUiDuringTransition = false,
                    )
                }
                logPlayResumeLatency(
                    path = "WORKER_RESTART",
                    queueWaitMs = queueWaitMs,
                    focusMs = lastWorkerStartFocusMs,
                    trackPlayMs = lastWorkerStartTrackPlayMs,
                    totalMs = (SystemClock.elapsedRealtime() - requestStartMs).coerceAtLeast(0L),
                )
            }
            WorkerStartResult.AUDIO_TRACK_FAILED ->
                reloadCurrentTrackForPlayLocked(
                    requestStartMs = requestStartMs,
                    queueWaitMs = queueWaitMs,
                    attemptSeq = attemptSeq,
                )
            WorkerStartResult.AUDIO_FOCUS_DENIED -> {
                _state.update { it.copy(isPreparingPlayback = false, preservePlayingUiDuringTransition = false) }
            }
            WorkerStartResult.ABORTED_BY_PAUSE -> {
                isPausedFlag.set(true)
                clearPreparingFlagsAfterAbortedPlay()
                publishDiagnostics()
            }
        }
    }

    private fun reloadCurrentTrackForPlayLocked(
        requestStartMs: Long = SystemClock.elapsedRealtime(),
        queueWaitMs: Long = 0L,
        attemptSeq: Long = playResumeAbortGuard.currentResumeAttemptSeq(),
    ) {
        if (!isResumeAuthorizedForPlayback(attemptSeq, checkFlapping = true)) {
            abortSupersededPlayLocked(
                track = activeAudioTrack,
                pauseTrack = false,
                abandonFocus = false,
                trackPlayMs = 0L,
                stage = "reload_entry",
            )
            return
        }
        pendingRouteReconfigureOnPlay.set(false)
        val req = currentRequest ?: return
        val resumePos = if (_state.value.status == PlaybackStatus.COMPLETED) {
            0L
        } else {
            seekCoalescer.pollPendingSeek() ?: _playbackPositionMs.value.coerceAtLeast(0L)
        }
        val generation = loadGeneration.incrementAndGet()
        loadTrackLocked(
            request = req.copy(startPositionMs = resumePos, autoPlay = true),
            generation = generation,
            preserveAutoPauseNotice = false,
            forceAudioFocus = true,
            resumeAttemptSeq = attemptSeq,
        )
        if (_state.value.isPlaying) {
            logPlayResumeLatency(
                path = "RELOAD",
                queueWaitMs = queueWaitMs,
                focusMs = lastWorkerStartFocusMs,
                trackPlayMs = lastWorkerStartTrackPlayMs,
                totalMs = (SystemClock.elapsedRealtime() - requestStartMs).coerceAtLeast(0L),
            )
        }
    }

    private fun logPlayResumeLatency(path: String, queueWaitMs: Long, focusMs: Long, trackPlayMs: Long, totalMs: Long) {
        val safety = safetyController.state()
        val sr = activeDecoder?.format?.sampleRateHz ?: audioTrackActualSampleRateHz
        val enc = activeDecoder?.format?.encoding?.name ?: audioTrackActualEncodingLabel
        diagnosticLogger?.logPerf("PLAY_RESUME_LATENCY") {
            linkedMapOf(
                "path" to path,
                "queueWaitMs" to queueWaitMs,
                "focusMs" to focusMs,
                "trackPlayMs" to trackPlayMs,
                "totalMs" to totalMs,
                "sr" to sr,
                "enc" to enc,
                "mode" to safety.mode.name,
                "usb" to safety.isUsbAudioConnected,
                "pinnedUsb" to activeTrackPinnedToPreferredUsbDevice,
            )
        }
    }

    /**
     * Adjusts [AudioTrack] output volume strictly for Safe Mode features (e.g., 3-second Sleep
     * Timer fade-out). Never invokes software `AudioTrack.setVolume(...)` in
     * [AudioOutputMode.BIT_PERFECT_OPT_IN].
     */
    fun setSafeModeOutputVolume(volume: Float) {
        if (safetyController.state().mode != AudioOutputMode.SAFE_VOLUME_MODE) return
        runCatching { activeAudioTrack?.setVolume(volume.coerceIn(0.0f, 1.0f)) }
    }

    fun pause() {
        cancelDeferredPlay()
        playResumeAbortGuard.cancelInFlightResumes()
        logEngineLifecycle(event = "PAUSE_REQ", source = "ENGINE_PAUSE")
        routeAndMixerManager.resumeAfterTransientFocusLoss = false
        applyImmediatePauseState(notice = null)
        if (commandCoalescer.submitPause()) {
            scheduleControlDrain()
        }
    }

    fun notifyBackgroundStartBlocked(notice: String) {
        cancelDeferredPlay()
        playResumeAbortGuard.cancelInFlightResumes()
        routeAndMixerManager.resumeAfterTransientFocusLoss = false
        commandCoalescer.cancelPendingStartOnExternalPause()
        applyImmediatePauseState(notice = notice)
        enqueueControlTask {
            if (!releasedFlag.get()) {
                engineLock.withLock {
                    activeAudioTrack?.let { track ->
                        logUnderrunDeltaIfAny(track, trigger = "bg_blocked_pause")
                        trackWriter.safePause(track)
                    }
                    routeAndMixerManager.abandonAudioFocusLocked()
                }
            }
        }
    }

    fun logSessionCleared(source: String = "MINI_PLAYER_SWIPE") {
        logEngineLifecycle(event = "SESSION_CLEARED", source = source)
    }

    private fun logEngineLifecycle(event: String, source: String) {
        val logger = diagnosticLogger ?: return
        if (!logger.isEnabled) return
        val statusName = _state.value.status.name
        val posMs = _playbackPositionMs.value
        val trackHealthy = trackWriter.isTrackHealthy(activeAudioTrack)
        val workerAlive = playbackThread?.isAlive == true
        val pendingReconfig = pendingRouteReconfigureOnPlay.get()
        val hasFocus = routeAndMixerManager.hasAudioFocus
        logger.logAudio("ENGINE_LIFECYCLE") {
            linkedMapOf(
                "event" to event,
                "source" to source,
                "status" to statusName,
                "posMs" to posMs,
                "trackHealthy" to trackHealthy,
                "workerAlive" to workerAlive,
                "pendingReconfig" to pendingReconfig,
                "hasFocus" to hasFocus,
            )
        }
    }

    fun beginTurntableScratch(): Boolean =
        scratchRunner.beginScratch(isPlaying = _state.value.isPlaying, hasActiveTrack = activeAudioTrack != null)

    fun updateTurntableScratchRate(playbackRate: Float) = scratchRunner.updateScratchRate(playbackRate)

    fun endTurntableScratch(fallbackTargetMs: Long? = null) = scratchRunner.endScratch(fallbackTargetMs)

    fun setSpectrumTapActive(active: Boolean) = trackWriter.setSpectrumTapActive(active)

    fun sampleCurrentSpectrumWindow(outMonoSamples: FloatArray): Int =
        trackWriter.sampleCurrentSpectrumWindow(activeAudioTrack, _state.value.isPlaying, outMonoSamples)

    fun seekTo(positionMs: Long) {
        if (releasedFlag.get()) return
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
            if (releasedFlag.get()) return@enqueueControlTask
            engineLock.withLock { seekWhilePausedLocked() }
        }
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
        val seekStartMs = SystemClock.elapsedRealtime()
        val actual = runCatching { decoder.seekToMs(target) }.getOrElse { err ->
            // Mark for clean reopen on next play() rather than surfacing a raw flush/state error.
            pendingRouteReconfigureOnPlay.set(true)
            diagnosticLogger?.logError("PAUSED_SEEK_DEFER_REBUILD") {
                linkedMapOf("errType" to err.javaClass.simpleName, "targetMs" to target)
            }
            target
        }
        activeAudioTrack?.let { trackWriter.safeFlush(it) }
        scratchRunner.resetRingBufferIfPresent(0L)
        trackWriter.resetSpectrumTapIfPresent(decoder.format.sampleRateHz, 0L)
        _playbackPositionMs.value = actual
        val seekMs = (SystemClock.elapsedRealtime() - seekStartMs).coerceAtLeast(0L)
        diagnosticLogger?.logPerf("SEEK_LATENCY") {
            linkedMapOf(
                "seekMs" to seekMs,
                "targetMs" to target,
                "actualMs" to actual,
                "state" to "paused",
            )
        }
    }

    fun stop() {
        if (releasedFlag.get()) return
        cancelDeferredPlay()
        playResumeAbortGuard.cancelInFlightResumes()
        logEngineLifecycle(event = "STOP_REQ", source = "ENGINE_STOP")
        scratchRunner.abortScratch()
        if (_state.value.status == PlaybackStatus.IDLE && activeAudioTrack == null && activeDecoder == null) {
            scratchRunner.releaseRingBuffer()
            trackWriter.releaseSpectrumTap()
            clearStagedPreloadedTrack(asyncClose = true)
            preloadController.clearPreloadedNextAsync()
            return
        }
        loadGeneration.incrementAndGet()
        clearStagedPreloadedTrack(asyncClose = true)
        preloadController.clearQueuedAndPreloadedAsync()
        routeAndMixerManager.resumeAfterTransientFocusLoss = false
        _openingTrackUri.value = null
        stopWorkerFlag.set(true)
        isPausedFlag.set(true)
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
        if (!releasedFlag.compareAndSet(false, true)) return
        cancelDeferredPlay()
        cancelCoalescedDiagnosticsPublish()
        playResumeAbortGuard.cancelInFlightResumes()
        scratchRunner.abortScratch()
        trackWriter.deactivateAndReleaseSpectrumTap()
        loadGeneration.incrementAndGet()
        commandCoalescer.clearAll()
        _openingTrackUri.value = null
        stopWorkerFlag.set(true)
        isPausedFlag.set(true)
        routeAndMixerManager.unregisterAudioMonitors()
        clearStagedPreloadedTrack(asyncClose = false)
        preloadController.shutdown()
        runCatching {
            controlExecutor.execute {
                engineLock.withLock { teardownFullEngineResourcesLocked(asyncMixerClear = false) }
            }
        }
        controlExecutor.shutdown()
    }

    private fun teardownFullEngineResourcesLocked(asyncMixerClear: Boolean = true) {
        pendingRouteReconfigureOnPlay.set(false)
        clearStagedPreloadedTrack(asyncClose = false)
        preloadController.clearPreloadedNextLocked()
        stopWorkerAndReleaseTrackLocked(clearMixer = true, asyncMixerClear = asyncMixerClear)
        scratchRunner.releaseRingBuffer()
        trackWriter.releaseSpectrumTap()
        routeAndMixerManager.abandonAudioFocusLocked()
    }

    private fun isLoadStale(generation: Long): Boolean = releasedFlag.get() || loadGeneration.get() != generation

    private enum class WorkerStartResult {
        STARTED,
        AUDIO_FOCUS_DENIED,
        AUDIO_TRACK_FAILED,
        ABORTED_BY_PAUSE,
    }

    private data class TrackLoadTimings(
        val loadStartMs: Long,
        val audioTrackBuildStartMs: Long,
        val stopOldMs: Long,
        val decoderOpenMs: Long,
        val rgScanMs: Long,
        val trackCreateMs: Long,
        val wasPreloaded: Boolean,
    )

    private fun loadTrackLocked(
        request: EngineTrackRequest,
        generation: Long,
        preserveAutoPauseNotice: Boolean,
        forceAudioFocus: Boolean = false,
        clearMixerBeforeCreate: Boolean = false,
        resumeAttemptSeq: Long = 0L,
    ) {
        if (isLoadStale(generation)) {
            _openingTrackUri.compareAndSet(request.uri.toString(), null)
            return
        }
        _openingTrackUri.value = request.uri.toString()
        pendingRouteReconfigureOnPlay.set(false)
        val loadStartMs = SystemClock.elapsedRealtime()
        stopWorkerAndReleaseTrackLocked(clearMixer = clearMixerBeforeCreate, asyncMixerClear = true)
        val stopOldMs = (SystemClock.elapsedRealtime() - loadStartMs).coerceAtLeast(0L)
        currentRequest = request
        activeTrackReplayGainMetadata = null
        recomputeReplayGain()
        diagnosticLogger?.logAudio("TRACK_OPEN") {
            linkedMapOf(
                "fp" to request.fingerprintPrefix,
                "ext" to DiagnosticLogFormatter.extractSafeFileExtension(request.fileName, request.mimeType),
                "sizeBytes" to request.fileSizeBytes,
                "startPosMs" to request.startPositionMs,
                "autoPlay" to request.autoPlay,
                "mode" to safetyController.state().mode.name,
            )
        }

        val decoder: PcmAudioDecoder?
        var wasPreloaded = false
        var decoderOpenMs = 0L
        var rgScanMs = 0L
        try {
            val preloaded = if (request.startPositionMs == 0L && !isLoadStale(generation)) {
                takeStagedPreloadedTrackMatchingUri(request.uri)
                    ?: preloadController.takePreloadedTrackMatchingUri(request.uri)
            } else {
                clearStagedPreloadedTrack(asyncClose = true)
                null
            }
            val shouldReadRg = AudioStartPathPolicy.shouldScanReplayGainMetadata(replayGainConfig.mode)
            if (preloaded != null) {
                wasPreloaded = true
                preloadController.consumeQueuedNextRequestIfMatches(request.uri)
                activeTrackReplayGainMetadata = preloaded.replayGainMetadata ?: if (shouldReadRg) {
                    val rgStartMs = SystemClock.elapsedRealtime()
                    preloadController.readReplayGainMetadata(request.uri, request.fileName, request.mimeType).also {
                        rgScanMs = (SystemClock.elapsedRealtime() - rgStartMs).coerceAtLeast(0L)
                    }
                } else {
                    null
                }
                decoder = preloaded.decoder
            } else {
                preloadController.clearPreloadedNextLocked()
                // Open and prime decoder BEFORE creating AudioTrack so codec.outputFormat is confirmed.
                val decodeOpenStartMs = SystemClock.elapsedRealtime()
                val opened = openDecoderForTrackLocked(request, generation)
                decoderOpenMs = (SystemClock.elapsedRealtime() - decodeOpenStartMs).coerceAtLeast(0L)
                if (opened == null) {
                    routeAndMixerManager.clearPreferredMixerAttributesAsyncOnApi34(null)
                    clearPreparingFlagsIfUnbound(request.uri)
                    return
                }
                activeTrackReplayGainMetadata = if (shouldReadRg) {
                    val rgStartMs = SystemClock.elapsedRealtime()
                    preloadController.readReplayGainMetadata(request.uri, request.fileName, request.mimeType).also {
                        rgScanMs = (SystemClock.elapsedRealtime() - rgStartMs).coerceAtLeast(0L)
                    }
                } else {
                    null
                }
                decoder = opened
            }
        } finally {
            _openingTrackUri.compareAndSet(request.uri.toString(), null)
        }

        if (isLoadStale(generation)) {
            runCatching { decoder.close() }
            routeAndMixerManager.clearPreferredMixerAttributesAsyncOnApi34(null)
            clearPreparingFlagsIfUnbound(request.uri)
            return
        }

        recomputeReplayGain()
        if (request.startPositionMs > 0L) runCatching { decoder.seekToMs(request.startPositionMs) }

        val canAutoPlay = request.autoPlay &&
            !commandCoalescer.hasPendingPauseOrStop() &&
            (resumeAttemptSeq <= 0L || playResumeAbortGuard.isResumeAttemptValid(resumeAttemptSeq))
        val effectiveRequest = if (canAutoPlay == request.autoPlay) request else request.copy(autoPlay = canAutoPlay)
        val audioTrackStartMs = SystemClock.elapsedRealtime()
        val audioTrack = createAudioTrackForDecoderLocked(decoder, generation) ?: run {
            clearPreparingFlagsIfUnbound(request.uri)
            return
        }
        val trackCreateMs = (SystemClock.elapsedRealtime() - audioTrackStartMs).coerceAtLeast(0L)
        if (isLoadStale(generation)) {
            trackWriter.safeStopAndRelease(audioTrack)
            runCatching { decoder.close() }
            routeAndMixerManager.clearPreferredMixerAttributesAsyncOnApi34(null)
            clearPreparingFlagsIfUnbound(request.uri)
            return
        }
        activateLoadedTrackLocked(
            request = effectiveRequest,
            decoder = decoder,
            audioTrack = audioTrack,
            generation = generation,
            preserveAutoPauseNotice = preserveAutoPauseNotice,
            forceAudioFocus = forceAudioFocus,
            timings = TrackLoadTimings(
                loadStartMs = loadStartMs,
                audioTrackBuildStartMs = audioTrackStartMs,
                stopOldMs = stopOldMs,
                decoderOpenMs = decoderOpenMs,
                rgScanMs = rgScanMs,
                trackCreateMs = trackCreateMs,
                wasPreloaded = wasPreloaded,
            ),
            resumeAttemptSeq = resumeAttemptSeq,
        )
    }

    private fun clearPreparingFlagsIfUnbound(requestUri: Uri) {
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

    private fun stagePreloadedTrack(track: PreloadedTrack?) {
        stagedPreloadedTrack.getAndSet(track)?.let { previous ->
            preloadController.closeDecoderOffMainThread(previous.decoder)
        }
    }

    private fun takeStagedPreloadedTrackMatchingUri(uri: Uri): PreloadedTrack? {
        val current = stagedPreloadedTrack.getAndSet(null) ?: return null
        if (current.request.uri == uri) {
            return current
        }
        preloadController.closeDecoderOffMainThread(current.decoder)
        return null
    }

    private fun clearStagedPreloadedTrack(asyncClose: Boolean) {
        stagedPreloadedTrack.getAndSet(null)?.let { previous ->
            if (asyncClose) {
                preloadController.closeDecoderOffMainThread(previous.decoder)
            } else {
                runCatching { previous.decoder.close() }
            }
        }
    }

    private fun openDecoderForTrackLocked(request: EngineTrackRequest, generation: Long): PcmAudioDecoder? = try {
        MediaCodecAudioDecoder.open(
            context = appContext,
            uri = request.uri,
            fileName = request.fileName,
            mimeHint = request.mimeType,
            diagnosticLogger = diagnosticLogger,
            isCancelled = { isLoadStale(generation) },
        )
    } catch (_: AudioDecodeException.Cancelled) {
        null
    } catch (e: AudioDecodeException) {
        if (loadGeneration.get() == generation) {
            activeDecoder = null
            _state.update {
                it.copy(
                    status = PlaybackStatus.ERROR,
                    errorMessage = e.message ?: "Unable to decode audio file",
                    isPreparingPlayback = false,
                    preservePlayingUiDuringTransition = false,
                )
            }
            publishDiagnostics()
        }
        null
    }

    private fun createAudioTrackForDecoderLocked(decoder: PcmAudioDecoder, generation: Long): AudioTrack? =
        trackWriter.tryCreateConfiguredAudioTrackLocked(decoder.format, routeAndMixerManager)
            .onSuccess { configured ->
                audioTrackActualSampleRateHz = configured.actualSampleRateHz
                audioTrackActualEncodingLabel = configured.actualEncodingLabel
                activeTrackUsesBitPerfectOrPreferredRoute = isBitPerfectOrPreferredRoute(configured)
                activeTrackPinnedToPreferredUsbDevice = configured.isPinnedToPreferredUsbDevice
                lastReportedUnderrunCount = 0
            }
            .onFailure { error ->
                diagnosticLogger?.logError("AUDIOTRACK_INIT_ERROR") {
                    linkedMapOf(
                        "errorType" to error.javaClass.simpleName,
                        "msg" to (error.message ?: "unknown"),
                        "sr" to decoder.format.sampleRateHz,
                        "ch" to decoder.format.channelCount,
                        "enc" to decoder.format.encoding.name,
                        "mode" to safetyController.state().mode.name,
                    )
                }
                runCatching { decoder.close() }
                activeDecoder = null
                routeAndMixerManager.clearPreferredMixerAttributesAsyncOnApi34(null)
                if (loadGeneration.get() == generation) {
                    _state.update {
                        it.copy(
                            status = PlaybackStatus.ERROR,
                            errorMessage = "AudioTrack initialization failed: ${error.message}",
                            isPreparingPlayback = false,
                            preservePlayingUiDuringTransition = false,
                        )
                    }
                    publishDiagnostics()
                }
            }
            .getOrNull()?.track

    private fun activateLoadedTrackLocked(
        request: EngineTrackRequest,
        decoder: PcmAudioDecoder,
        audioTrack: AudioTrack,
        generation: Long,
        preserveAutoPauseNotice: Boolean,
        forceAudioFocus: Boolean,
        timings: TrackLoadTimings,
        resumeAttemptSeq: Long = 0L,
    ) {
        activeDecoder = decoder
        activeAudioTrack = audioTrack
        lastWorkerStartFocusMs = 0L
        lastWorkerStartTrackPlayMs = 0L
        val initialPosMs = if (request.startPositionMs == 0L) 0L else decoder.currentPositionMs.coerceAtLeast(0L)
        _playbackPositionMs.value = initialPosMs
        val startResult = startLoadedTrackIfAutoPlayLocked(
            request = request,
            decoder = decoder,
            audioTrack = audioTrack,
            initialPosMs = initialPosMs,
            generation = generation,
            forceAudioFocus = forceAudioFocus,
            resumeAttemptSeq = resumeAttemptSeq,
        )
        val startedPlaying = startResult == WorkerStartResult.STARTED
        if (startedPlaying) {
            logSlowAudioTrackBuildAndStart(timings.audioTrackBuildStartMs, decoder.format.sampleRateHz)
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
                durationMs = decoder.format.durationMs.takeIf { it > 0L } ?: request.expectedDurationMs.coerceAtLeast(0L),
                errorMessage = errMsg,
                lastAutoPauseNotice = if (preserveNotice) current.lastAutoPauseNotice else null,
                isPreparingPlayback = false,
                preservePlayingUiDuringTransition = false,
            )
        }
        publishDiagnostics()
        val loadMs = (SystemClock.elapsedRealtime() - timings.loadStartMs).coerceAtLeast(0L)
        diagnosticLogger?.logPerf("TRACK_LOAD_LATENCY") {
            linkedMapOf(
                "stopOldMs" to timings.stopOldMs,
                "decoderOpenMs" to timings.decoderOpenMs,
                "rgScanMs" to timings.rgScanMs,
                "trackCreateMs" to timings.trackCreateMs,
                "focusMs" to lastWorkerStartFocusMs,
                "trackPlayMs" to lastWorkerStartTrackPlayMs,
                "loadMs" to loadMs,
                "preloaded" to timings.wasPreloaded,
                "autoPlay" to request.autoPlay,
                "startedPlaying" to startedPlaying,
                "sr" to decoder.format.sampleRateHz,
                "enc" to decoder.format.encoding.name,
                "ch" to decoder.format.channelCount,
                "mode" to safetyController.state().mode.name,
            )
        }
    }

    private fun startLoadedTrackIfAutoPlayLocked(
        request: EngineTrackRequest,
        decoder: PcmAudioDecoder,
        audioTrack: AudioTrack,
        initialPosMs: Long,
        generation: Long,
        forceAudioFocus: Boolean,
        resumeAttemptSeq: Long = 0L,
    ): WorkerStartResult {
        if (!request.autoPlay) {
            isPausedFlag.set(true)
            routeAndMixerManager.resumeAfterTransientFocusLoss = false
            routeAndMixerManager.abandonAudioFocusLocked()
            return WorkerStartResult.AUDIO_FOCUS_DENIED
        }
        if (safetyController.state().mode == AudioOutputMode.SAFE_VOLUME_MODE) {
            runCatching { audioTrack.setVolume(1.0f) }
        }
        val firstAttempt = startPlaybackWorkerLocked(
            initialSeekMs = initialPosMs,
            expectedGeneration = generation,
            forceAudioFocus = forceAudioFocus,
            clearPendingSeek = true,
            resumeAttemptSeq = resumeAttemptSeq,
        )
        if (firstAttempt != WorkerStartResult.AUDIO_TRACK_FAILED || isLoadStale(generation)) {
            return firstAttempt
        }
        // One-shot AudioTrack rebuild with fresh USB mixer negotiation if OEM HAL rejected play()
        // right after recycling a prior high-rate stream (e.g. 96 kHz -> 96 kHz on USB Safe Mode).
        diagnosticLogger?.logAudio("AUDIOTRACK_START_RETRY") {
            linkedMapOf("sr" to decoder.format.sampleRateHz, "mode" to safetyController.state().mode.name)
        }
        trackWriter.safeStopAndRelease(audioTrack)
        activeAudioTrack = null
        routeAndMixerManager.clearPreferredMixerAttributesAsyncOnApi34(null)
        val rebuilt = trackWriter.tryCreateConfiguredAudioTrackLocked(decoder.format, routeAndMixerManager)
            .onSuccess { configured ->
                audioTrackActualSampleRateHz = configured.actualSampleRateHz
                audioTrackActualEncodingLabel = configured.actualEncodingLabel
                activeTrackUsesBitPerfectOrPreferredRoute = isBitPerfectOrPreferredRoute(configured)
                activeTrackPinnedToPreferredUsbDevice = configured.isPinnedToPreferredUsbDevice
                lastReportedUnderrunCount = 0
            }
            .getOrNull()?.track ?: return WorkerStartResult.AUDIO_TRACK_FAILED
        activeAudioTrack = rebuilt
        if (safetyController.state().mode == AudioOutputMode.SAFE_VOLUME_MODE) {
            runCatching { rebuilt.setVolume(1.0f) }
        }
        return startPlaybackWorkerLocked(
            initialSeekMs = initialPosMs,
            expectedGeneration = generation,
            forceAudioFocus = true,
            clearPendingSeek = true,
            resumeAttemptSeq = resumeAttemptSeq,
        )
    }

    private fun handleSafetyCommand(command: SafetyCommand) {
        val safetyStartMs = SystemClock.elapsedRealtime()
        recomputeReplayGain()
        val statusBeforeCommand = _state.value.status
        val isActivelyPlayingOrPreparing =
            statusBeforeCommand == PlaybackStatus.PLAYING || statusBeforeCommand == PlaybackStatus.PREPARING
        val hasLoadedTrack = currentRequest != null &&
            (
                activeDecoder != null ||
                    statusBeforeCommand == PlaybackStatus.PREPARING ||
                    pendingRouteReconfigureOnPlay.get()
                )
        val canPauseInPlace = RouteChangeReconfigurePolicy.canKeepSafeModeTrackOnRouteChange(
            wasBitPerfectMode = command.wasBitPerfectMode,
            hasActivePreferredMixerOrPinnedRoute = activeTrackUsesBitPerfectOrPreferredRoute ||
                routeAndMixerManager.hasActivePreferredMixer,
            isPreparing = statusBeforeCommand == PlaybackStatus.PREPARING,
            hasActiveDecoder = activeDecoder != null,
            hasHealthyAudioTrack = trackWriter.isTrackHealthy(activeAudioTrack),
        )
        val reconfigureAction = RouteChangeReconfigurePolicy.decideAction(
            commandType = command.type,
            isActivelyPlayingOrPreparing = isActivelyPlayingOrPreparing,
            hasLoadedTrack = hasLoadedTrack,
            canPauseInPlaceOnSafeRoute = canPauseInPlace,
        )
        when (command.type) {
            SafetyEventType.AUTO_PAUSE -> {
                cancelDeferredPlay()
                cancelCoalescedDiagnosticsPublish()
                playResumeAbortGuard.cancelInFlightResumes()
                diagnosticLogger?.logAudio("AUTO_PAUSE") {
                    linkedMapOf(
                        "reason" to (command.autoPauseReason?.name ?: "UNKNOWN"),
                        "clearMixer" to command.requiresClearPreferredMixerAttributes,
                        "mode" to safetyController.state().mode.name,
                        "action" to reconfigureAction.name,
                    )
                }
                scratchRunner.abortScratch()
                routeAndMixerManager.resumeAfterTransientFocusLoss = false
                commandCoalescer.cancelPendingStartOnExternalPause()
                if (reconfigureAction == SafetyReconfigureAction.TEARDOWN_AND_REBIND_ON_PLAY) {
                    pendingRouteReconfigureOnPlay.set(true)
                    stopWorkerFlag.set(true)
                }
                applyImmediatePauseState(notice = AudioSafetyController.formatAutoPauseNotice(command.autoPauseReason))
                val uiUpdateMs = (SystemClock.elapsedRealtime() - safetyStartMs).coerceAtLeast(0L)
                enqueueSafetyReconfigure(
                    action = reconfigureAction,
                    clearPreferredMixer = command.requiresClearPreferredMixerAttributes,
                    releaseRing = false,
                    autoResume = false,
                    preserveAutoPauseNotice = true,
                    autoPauseReason = command.autoPauseReason,
                    safetyStartMs = safetyStartMs,
                    uiUpdateMs = uiUpdateMs,
                )
            }
            SafetyEventType.MODE_CHANGED_TO_SAFE -> {
                val shouldResume = statusBeforeCommand == PlaybackStatus.PLAYING
                val modeResumeSeq = if (shouldResume) playResumeAbortGuard.beginResumeAttempt() else 0L
                diagnosticLogger?.logAudio("AUDIO_MODE_CHANGED") {
                    linkedMapOf(
                        "mode" to AudioOutputMode.SAFE_VOLUME_MODE.name,
                        "clearMixer" to command.requiresClearPreferredMixerAttributes,
                    )
                }
                enqueueSafetyReconfigure(
                    action = reconfigureAction,
                    clearPreferredMixer = command.requiresClearPreferredMixerAttributes,
                    releaseRing = false,
                    autoResume = shouldResume,
                    preserveAutoPauseNotice = false,
                    resumeAttemptSeq = modeResumeSeq,
                )
            }
            SafetyEventType.MODE_CHANGED_TO_BIT_PERFECT -> {
                val shouldResume = statusBeforeCommand == PlaybackStatus.PLAYING
                val modeResumeSeq = if (shouldResume) playResumeAbortGuard.beginResumeAttempt() else 0L
                diagnosticLogger?.logAudio("AUDIO_MODE_CHANGED") {
                    linkedMapOf(
                        "mode" to AudioOutputMode.BIT_PERFECT_OPT_IN.name,
                        "clearMixer" to false,
                    )
                }
                scratchRunner.abortScratch()
                enqueueSafetyReconfigure(
                    action = reconfigureAction,
                    clearPreferredMixer = false,
                    releaseRing = true,
                    autoResume = shouldResume,
                    preserveAutoPauseNotice = false,
                    resumeAttemptSeq = modeResumeSeq,
                )
            }
            SafetyEventType.REQUIRES_SAFETY_WARNING, SafetyEventType.NONE -> {
                _state.update { it.copy(safetyState = safetyController.state()) }
                publishDiagnostics()
            }
        }
    }

    private fun enqueueSafetyReconfigure(
        action: SafetyReconfigureAction,
        clearPreferredMixer: Boolean,
        releaseRing: Boolean,
        autoResume: Boolean,
        preserveAutoPauseNotice: Boolean,
        autoPauseReason: AutoPauseReason? = null,
        safetyStartMs: Long = 0L,
        uiUpdateMs: Long = 0L,
        resumeAttemptSeq: Long = 0L,
    ) {
        enqueueControlTask {
            if (releasedFlag.get()) return@enqueueControlTask
            engineLock.withLock {
                var pauseIpcMs = 0L
                var abandonFocusMs = 0L
                var clearMixerMs = 0L
                if (releaseRing) scratchRunner.releaseRingBuffer()
                when (action) {
                    SafetyReconfigureAction.RECONFIGURE_NOW -> {
                        if (clearPreferredMixer) {
                            val clearStart = SystemClock.elapsedRealtime()
                            routeAndMixerManager.clearPreferredMixerAttributesAsyncOnApi34(null)
                            clearMixerMs = (SystemClock.elapsedRealtime() - clearStart).coerceAtLeast(0L)
                        }
                        reconfigureTrackIfLoadedLocked(autoResume, preserveAutoPauseNotice, resumeAttemptSeq)
                    }
                    SafetyReconfigureAction.PAUSE_IN_PLACE_KEEP_TRACK -> {
                        val metrics = pauseInPlaceKeepTrackLocked(clearPreferredMixer)
                        pauseIpcMs = metrics.first
                        abandonFocusMs = metrics.second
                        clearMixerMs = metrics.third
                    }
                    SafetyReconfigureAction.TEARDOWN_AND_REBIND_ON_PLAY -> {
                        val metrics = teardownRouteInvalidatedSessionLocked(clearPreferredMixer)
                        pauseIpcMs = metrics.first
                        abandonFocusMs = metrics.second
                        clearMixerMs = metrics.third
                    }
                    SafetyReconfigureAction.DEFER_RECONFIGURE_UNTIL_PLAY -> {
                        if (clearPreferredMixer) {
                            val clearStart = SystemClock.elapsedRealtime()
                            routeAndMixerManager.clearPreferredMixerAttributesAsyncOnApi34(null)
                            clearMixerMs = (SystemClock.elapsedRealtime() - clearStart).coerceAtLeast(0L)
                        }
                        pauseIpcMs = deferRouteReconfigureWhilePausedLocked()
                    }
                    SafetyReconfigureAction.DIAGNOSTICS_ONLY -> {
                        if (clearPreferredMixer) {
                            val clearStart = SystemClock.elapsedRealtime()
                            routeAndMixerManager.clearPreferredMixerAttributesAsyncOnApi34(null)
                            clearMixerMs = (SystemClock.elapsedRealtime() - clearStart).coerceAtLeast(0L)
                        }
                        preloadController.clearPreloadedNextLocked()
                        _state.update { it.copy(safetyState = safetyController.state()) }
                        publishDiagnostics()
                    }
                }
                if (autoPauseReason != null && safetyStartMs > 0L) {
                    val totalMs = (SystemClock.elapsedRealtime() - safetyStartMs).coerceAtLeast(0L)
                    val sr = activeDecoder?.format?.sampleRateHz ?: audioTrackActualSampleRateHz
                    diagnosticLogger?.logPerf("AUTO_PAUSE_LATENCY") {
                        linkedMapOf(
                            "reason" to autoPauseReason.name,
                            "action" to action.name,
                            "uiUpdateMs" to uiUpdateMs,
                            "pauseIpcMs" to pauseIpcMs,
                            "abandonFocusMs" to abandonFocusMs,
                            "clearMixerMs" to clearMixerMs,
                            "totalMs" to totalMs,
                            "sr" to sr,
                            "mode" to safetyController.state().mode.name,
                        )
                    }
                    diagnosticLogger?.requestFlush()
                }
            }
        }
    }

    /**
     * Pauses an unpinned Safe-Mode session without destroying `AudioTrack` or `MediaCodec`,
     * avoiding HyperOS's multi-second USB HAL teardown stalls on `192 kHz` unplug/replug while
     * leaving [AudioOutputEngine.playLocked] + `DeadAudioTrack` recovery ready if HAL invalidates
     * the underlying track. Returns `(pauseIpcMs, abandonFocusMs, clearMixerMs)`.
     */
    private fun pauseInPlaceKeepTrackLocked(clearPreferredMixer: Boolean): Triple<Long, Long, Long> {
        var clearMixerMs = 0L
        if (clearPreferredMixer && routeAndMixerManager.hasActivePreferredMixer) {
            val clearStart = SystemClock.elapsedRealtime()
            routeAndMixerManager.clearPreferredMixerAttributesAsyncOnApi34(null)
            clearMixerMs = (SystemClock.elapsedRealtime() - clearStart).coerceAtLeast(0L)
        }
        preloadController.clearPreloadedNextLocked()
        isPausedFlag.set(true)
        val pauseStart = SystemClock.elapsedRealtime()
        activeAudioTrack?.let { track ->
            logUnderrunDeltaIfAny(track, trigger = "auto_pause_in_place")
            trackWriter.safePause(track)
        }
        val pauseIpcMs = (SystemClock.elapsedRealtime() - pauseStart).coerceAtLeast(0L)
        val focusStart = SystemClock.elapsedRealtime()
        routeAndMixerManager.abandonAudioFocusLocked()
        val abandonFocusMs = (SystemClock.elapsedRealtime() - focusStart).coerceAtLeast(0L)
        scratchRunner.markRouteChangedWhilePaused()
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
        return Triple(pauseIpcMs, abandonFocusMs, clearMixerMs)
    }

    /**
     * Tears down the invalidated worker thread, dead `AudioTrack`, and decoder after a USB plug/unplug
     * or `BECOMING_NOISY` auto-pause while preserving [currentRequest] and exact [_playbackPositionMs]
     * so the very first Play tap cleanly rebinds on the active output route in `< 500 ms`.
     * Returns `(pauseIpcMs, abandonFocusMs, clearMixerMs)`.
     */
    private fun teardownRouteInvalidatedSessionLocked(clearPreferredMixer: Boolean): Triple<Long, Long, Long> {
        preloadController.clearPreloadedNextLocked()
        val teardownStart = SystemClock.elapsedRealtime()
        stopWorkerAndReleaseTrackLocked(clearMixer = clearPreferredMixer, asyncMixerClear = true)
        val pauseIpcMs = (SystemClock.elapsedRealtime() - teardownStart).coerceAtLeast(0L)
        val focusStart = SystemClock.elapsedRealtime()
        routeAndMixerManager.abandonAudioFocusLocked()
        val abandonFocusMs = (SystemClock.elapsedRealtime() - focusStart).coerceAtLeast(0L)
        if (currentRequest != null) {
            pendingRouteReconfigureOnPlay.set(true)
        }
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
        return Triple(pauseIpcMs, abandonFocusMs, 0L)
    }

    private fun deferRouteReconfigureWhilePausedLocked(): Long {
        preloadController.clearPreloadedNextLocked()
        // Guard against a narrow race where an earlier queued play() started right before this task ran.
        isPausedFlag.set(true)
        val pauseStart = SystemClock.elapsedRealtime()
        activeAudioTrack?.let { trackWriter.safePause(it) }
        val pauseIpcMs = (SystemClock.elapsedRealtime() - pauseStart).coerceAtLeast(0L)
        if (currentRequest != null) {
            pendingRouteReconfigureOnPlay.set(true)
        }
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
        return pauseIpcMs
    }

    private fun reconfigureTrackIfLoadedLocked(autoResume: Boolean, preserveAutoPauseNotice: Boolean, resumeAttemptSeq: Long = 0L) {
        preloadController.clearPreloadedNextLocked()
        val req = currentRequest
        if (req == null || (activeDecoder == null && !pendingRouteReconfigureOnPlay.get())) {
            _state.update { it.copy(safetyState = safetyController.state()) }
            publishDiagnostics()
            return
        }
        val canResume = autoResume && (resumeAttemptSeq <= 0L || playResumeAbortGuard.isResumeAttemptValid(resumeAttemptSeq))
        val generation = loadGeneration.incrementAndGet()
        loadTrackLocked(
            request = req.copy(startPositionMs = _playbackPositionMs.value, autoPlay = canResume),
            generation = generation,
            preserveAutoPauseNotice = preserveAutoPauseNotice || (autoResume && !canResume),
            forceAudioFocus = canResume,
            resumeAttemptSeq = if (canResume) resumeAttemptSeq else 0L,
        )
    }

    /**
     * Immediately transitions the observable playback state and worker `isPausedFlag` to paused
     * on the calling thread with zero Binder IPC (`AudioTrack.pause()` is offloaded to
     * [controlExecutor] so Main-thread UI updates and route broadcasts never block on AudioFlinger).
     */
    private fun applyImmediatePauseState(notice: String?) {
        scratchRunner.abortScratch()
        val wasPaused = isPausedFlag.getAndSet(true)
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
        if (releasedFlag.get()) return
        enqueueControlTask {
            try {
                while (!releasedFlag.get()) {
                    val intent = commandCoalescer.pollNextIntent() ?: break
                    try {
                        engineLock.withLock {
                            if (!releasedFlag.get()) executeCoalescedIntentLocked(intent)
                        }
                    } finally {
                        commandCoalescer.markExecutionFinished()
                    }
                }
            } finally {
                if (commandCoalescer.onDrainExited() && !releasedFlag.get()) {
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
                    maxOf(payload.resumeAttemptSeq, playResumeAbortGuard.currentResumeAttemptSeq())
                } else {
                    0L
                }
                if (!isLoadStale(payload.generation)) {
                    loadTrackLocked(
                        request = effectiveRequest,
                        generation = payload.generation,
                        preserveAutoPauseNotice = payload.preserveAutoPauseNotice,
                        forceAudioFocus = payload.forceAudioFocus,
                        clearMixerBeforeCreate = payload.clearMixerBeforeCreate,
                        resumeAttemptSeq = effectiveResumeSeq,
                    )
                }
            }
            CoalescedControlIntent.Play -> playLocked()
            CoalescedControlIntent.Pause -> {
                routeAndMixerManager.resumeAfterTransientFocusLoss = false
                isPausedFlag.set(true)
                activeAudioTrack?.let { track ->
                    logUnderrunDeltaIfAny(track, trigger = "pause")
                    trackWriter.safePause(track)
                }
                routeAndMixerManager.abandonAudioFocusLocked()
                applyImmediatePauseState(notice = null)
                diagnosticLogger?.requestFlush()
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
                diagnosticLogger?.requestFlush()
            }
        }
    }

    private fun logSlowAudioTrackBuildAndStart(startMs: Long, sampleRateHz: Int) {
        val elapsedMs = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(0L)
        if (AudioStartPathPolicy.shouldLogSlowAudioStage(BuildConfig.DEBUG, elapsedMs)) {
            Log.w(
                TAG,
                "Slow audio stage [audioTrackBuildAndStart]: ${elapsedMs}ms " +
                    "(sr=${sampleRateHz}Hz, mode=${safetyController.state().mode})",
            )
        }
        if (AudioStartPathPolicy.shouldRecordSlowAudioStage(elapsedMs)) {
            val safety = safetyController.state()
            diagnosticLogger?.logPerf("SLOW_AUDIO_STAGE") {
                linkedMapOf(
                    "stage" to "audioTrackBuildAndStart",
                    "elapsedMs" to elapsedMs,
                    "sr" to sampleRateHz,
                    "mode" to safety.mode.name,
                    "usb" to safety.isUsbAudioConnected,
                )
            }
        }
    }

    private fun enqueueControlTask(block: () -> Unit) {
        if (!releasedFlag.get()) runCatching { controlExecutor.execute(block) }
    }

    private fun createConfiguredAudioTrackLocked(pcmFormat: DecodedPcmFormat): AudioTrack {
        val configured = trackWriter.createConfiguredAudioTrackLocked(pcmFormat, routeAndMixerManager)
        audioTrackActualSampleRateHz = configured.actualSampleRateHz
        audioTrackActualEncodingLabel = configured.actualEncodingLabel
        activeTrackUsesBitPerfectOrPreferredRoute = isBitPerfectOrPreferredRoute(configured)
        activeTrackPinnedToPreferredUsbDevice = configured.isPinnedToPreferredUsbDevice
        lastReportedUnderrunCount = 0
        return configured.track
    }

    private fun isBitPerfectOrPreferredRoute(configured: ConfiguredAudioTrack): Boolean = configured.isPinnedToPreferredUsbDevice ||
        routeAndMixerManager.activeNegotiatedMixerBehavior == NegotiatedMixerBehavior.BIT_PERFECT ||
        routeAndMixerManager.activeNegotiatedMixerBehavior == NegotiatedMixerBehavior.DEFAULT_NATIVE_RATE

    private fun startPlaybackWorkerLocked(
        initialSeekMs: Long,
        expectedGeneration: Long,
        forceAudioFocus: Boolean = false,
        clearPendingSeek: Boolean = true,
        resumeAttemptSeq: Long = playResumeAbortGuard.currentResumeAttemptSeq(),
    ): WorkerStartResult {
        lastWorkerStartFocusMs = 0L
        lastWorkerStartTrackPlayMs = 0L
        val decoder = activeDecoder ?: return WorkerStartResult.AUDIO_TRACK_FAILED
        val track = activeAudioTrack?.takeIf { trackWriter.isTrackHealthy(it) }
            ?: return WorkerStartResult.AUDIO_TRACK_FAILED
        val startOutcome = requestFocusAndStartTrackLocked(
            track = track,
            forceAudioFocus = forceAudioFocus,
            resumeAttemptSeq = resumeAttemptSeq,
        )
        if (startOutcome != WorkerStartResult.STARTED) {
            return startOutcome
        }
        stopWorkerFlag.set(false)
        isPausedFlag.set(false)
        if (clearPendingSeek) seekCoalescer.clear()

        val worker = Thread(
            {
                trackWriter.applyPlaybackThreadPriority()
                runPlaybackLoop(decoder, track, initialSeekMs, expectedGeneration)
            },
            "BitPerfectAudioThread",
        )
        playbackThread = worker
        worker.start()
        return WorkerStartResult.STARTED
    }

    private fun requestFocusAndStartTrackLocked(track: AudioTrack, forceAudioFocus: Boolean, resumeAttemptSeq: Long): WorkerStartResult {
        if (!isResumeAuthorizedForPlayback(resumeAttemptSeq, checkFlapping = true)) {
            abortSupersededPlayLocked(track, pauseTrack = false, abandonFocus = false, trackPlayMs = 0L, stage = "worker_entry")
            return WorkerStartResult.ABORTED_BY_PAUSE
        }
        val focusStartMs = SystemClock.elapsedRealtime()
        val focusGranted = routeAndMixerManager.requestAudioFocusLocked(forceRequest = forceAudioFocus)
        lastWorkerStartFocusMs = (SystemClock.elapsedRealtime() - focusStartMs).coerceAtLeast(0L)
        if (!focusGranted) {
            isPausedFlag.set(true)
            trackWriter.safePause(track)
            _state.update { it.copy(status = PlaybackStatus.PAUSED, errorMessage = "Audio focus denied") }
            return WorkerStartResult.AUDIO_FOCUS_DENIED
        }
        if (!isResumeAuthorizedForPlayback(resumeAttemptSeq, checkFlapping = true)) {
            abortSupersededPlayLocked(track, pauseTrack = false, abandonFocus = true, trackPlayMs = 0L, stage = "worker_post_focus")
            return WorkerStartResult.ABORTED_BY_PAUSE
        }
        val playStartMs = SystemClock.elapsedRealtime()
        val playOk = trackWriter.safePlay(track)
        lastWorkerStartTrackPlayMs = (SystemClock.elapsedRealtime() - playStartMs).coerceAtLeast(0L)
        if (!isResumeAuthorizedForPlayback(resumeAttemptSeq, checkFlapping = false)) {
            abortSupersededPlayLocked(
                track = track,
                pauseTrack = playOk,
                abandonFocus = true,
                trackPlayMs = lastWorkerStartTrackPlayMs,
                stage = "worker_post_safePlay",
            )
            return WorkerStartResult.ABORTED_BY_PAUSE
        }
        return if (playOk) {
            WorkerStartResult.STARTED
        } else {
            isPausedFlag.set(true)
            WorkerStartResult.AUDIO_TRACK_FAILED
        }
    }

    @Suppress("TooGenericExceptionCaught") // Top-level audio worker thread must surface OEM HAL errors safely instead of crashing
    private fun runPlaybackLoop(
        initialDecoder: PcmAudioDecoder,
        initialTrack: AudioTrack,
        initialSeekMs: Long,
        expectedGeneration: Long,
    ) {
        val state = scratchRunner.createInitialLoopState(expectedGeneration, initialDecoder, initialTrack, initialSeekMs)
        var recoveryScheduled = false
        try {
            while (trackWriter.isGenerationActive(expectedGeneration)) {
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
            isGenerationCurrent = loadGeneration.get() == expectedGeneration,
            isStoppingOrReleased = stopWorkerFlag.get() || releasedFlag.get(),
            isPaused = isPausedFlag.get(),
            isTransientRecoverableError = true,
            priorRecoveryAttempts = consecutiveAutoRecoveries,
        )
        if (canRecover) {
            consecutiveAutoRecoveries++
            val recoverySeq = playResumeAbortGuard.currentResumeAttemptSeq()
            val resumePos = seekCoalescer.pollPendingSeek() ?: _playbackPositionMs.value.coerceAtLeast(0L)
            diagnosticLogger?.logAudio("PLAYBACK_AUTO_RECOVER") {
                linkedMapOf(
                    "attempt" to consecutiveAutoRecoveries,
                    "deadTrack" to isDeadTrack,
                    "errType" to error.javaClass.simpleName,
                    "posMs" to resumePos,
                    "mode" to safetyController.state().mode.name,
                )
            }
            enqueueControlTask {
                if (shouldAbortPendingAutoRecovery(expectedGeneration, recoverySeq)) {
                    return@enqueueControlTask
                }
                engineLock.withLock {
                    if (shouldAbortPendingAutoRecovery(expectedGeneration, recoverySeq)) {
                        return@withLock
                    }
                    val req = currentRequest ?: return@withLock
                    val nextGen = loadGeneration.incrementAndGet()
                    loadTrackLocked(
                        request = req.copy(startPositionMs = resumePos, autoPlay = true),
                        generation = nextGen,
                        preserveAutoPauseNotice = false,
                        forceAudioFocus = true,
                        clearMixerBeforeCreate = true,
                        resumeAttemptSeq = recoverySeq,
                    )
                }
            }
            return true
        }
        if (isDeadTrack && trackWriter.isGenerationActive(expectedGeneration)) {
            pendingRouteReconfigureOnPlay.set(true)
            isPausedFlag.set(true)
            _state.update { it.copy(status = PlaybackStatus.PAUSED, errorMessage = null) }
            publishDiagnostics()
            return false
        }
        publishWorkerErrorIfRunning(expectedGeneration, error.message ?: "Playback decoding error")
        return false
    }

    private fun shouldAbortPendingAutoRecovery(expectedGeneration: Long, recoverySeq: Long): Boolean {
        val aborted = releasedFlag.get() || isPausedFlag.get()
        return aborted ||
            loadGeneration.get() != expectedGeneration ||
            !playResumeAbortGuard.isResumeAttemptValid(recoverySeq)
    }

    private fun reconcileStatusOnWorkerExit(expectedGeneration: Long, recoveryScheduled: Boolean) {
        val isGenCurrent = loadGeneration.get() == expectedGeneration
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
        if (trackWriter.isGenerationActive(expectedGeneration)) {
            diagnosticLogger?.logError("PLAYBACK_WORKER_ERROR") {
                linkedMapOf(
                    "msg" to DiagnosticLogFormatter.redactFilenameAndPaths(message, currentRequest?.fileName),
                    "mode" to safetyController.state().mode.name,
                )
            }
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
        if (!trackWriter.isGenerationActive(state.expectedGeneration)) return false
        scratchRunner.accumulateActivePlayTime(state, forceFlush = true)
        logUnderrunDeltaIfAny(state.currentTrack, trigger = "eos")
        val preloaded = preloadController.pollSeamlessPreloadedTrack(state.currentFormat, state.expectedGeneration)
        if (preloaded != null) return applySeamlessGaplessTransitionInLoop(state, preloaded)
        if (scratchRunner.drainTrackAtEndOfStream(state)) return true
        if (trackWriter.isGenerationActive(state.expectedGeneration)) {
            _playbackPositionMs.value = state.durationMs.takeIf { it > 0L } ?: _playbackPositionMs.value
            _state.update { it.copy(status = PlaybackStatus.COMPLETED) }
            mainHandler.post { onTrackCompleted?.invoke() }
        }
        return false
    }

    private fun applySeamlessGaplessTransitionInLoop(state: PlaybackLoopState, preloaded: PreloadedTrack): Boolean {
        val transitioned = engineLock.withLock {
            if (!trackWriter.isGenerationActive(state.expectedGeneration)) return@withLock false
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

        // Keep the exact same AudioTrack running continuously (0 ms gap, zero flush/rebuild).
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
        Log.d(TAG, "Gapless SEAMLESS_REUSE -> ${preloaded.request.fileName} (${state.currentFormat.sampleRateHz}Hz)")
        diagnosticLogger?.logAudio("GAPLESS_SWAP") {
            linkedMapOf(
                "fp" to preloaded.request.fingerprintPrefix,
                "ext" to DiagnosticLogFormatter.extractSafeFileExtension(
                    preloaded.request.fileName,
                    preloaded.request.mimeType,
                ),
                "sr" to state.currentFormat.sampleRateHz,
                "enc" to state.currentFormat.encoding.name,
                "ch" to state.currentFormat.channelCount,
                "mode" to safetyController.state().mode.name,
            )
        }
        mainHandler.post { onGaplessTransition?.invoke(preloaded.request) }
        return true
    }

    private fun handleMidStreamFormatChangeInLoop(state: PlaybackLoopState, newFormat: DecodedPcmFormat): Boolean {
        scratchRunner.accumulateActivePlayTime(state, forceFlush = true)
        logUnderrunDeltaIfAny(state.currentTrack, trigger = "format_change")
        diagnosticLogger?.logAudio("MID_STREAM_FORMAT_CHANGE") {
            linkedMapOf(
                "sr" to newFormat.sampleRateHz,
                "enc" to newFormat.encoding.name,
                "ch" to newFormat.channelCount,
            )
        }
        val rebuiltTrack = engineLock.withLock {
            if (!trackWriter.isGenerationActive(state.expectedGeneration)) return@withLock null
            trackWriter.safeStopAndRelease(state.currentTrack)
            createConfiguredAudioTrackLocked(newFormat).also { created ->
                activeAudioTrack = created
                if (!isPausedFlag.get()) trackWriter.safePlay(created)
                publishDiagnostics()
            }
        } ?: return false
        scratchRunner.completeMidStreamTrackRebuild(state, rebuiltTrack, newFormat)
        return true
    }

    private fun stopWorkerAndReleaseTrackLocked(clearMixer: Boolean, asyncMixerClear: Boolean = true) {
        scratchRunner.abortScratch()
        stopWorkerFlag.set(true)
        isPausedFlag.set(true)
        val track = activeAudioTrack
        activeAudioTrack = null
        if (track != null) {
            logUnderrunDeltaIfAny(track, trigger = "release")
            trackWriter.safeUnblockForShutdown(track)
        }
        val thread = playbackThread
        playbackThread = null
        if (thread != null && thread != Thread.currentThread()) {
            joinWorkerCleanly(thread)
        }
        if (track != null) trackWriter.safeStopAndRelease(track)
        activeDecoder?.let { runCatching { it.close() } }
        activeDecoder = null
        scratchRunner.releaseRingBufferIfBitPerfect()
        if (clearMixer) {
            if (asyncMixerClear) {
                routeAndMixerManager.clearPreferredMixerAttributesAsyncOnApi34(null)
            } else {
                routeAndMixerManager.clearPreferredMixerAttributesOnApi34(null)
            }
        }
        audioTrackActualSampleRateHz = 0
        audioTrackActualEncodingLabel = "None"
        activeTrackUsesBitPerfectOrPreferredRoute = false
        activeTrackPinnedToPreferredUsbDevice = false
        lastReportedUnderrunCount = 0
    }

    private fun joinWorkerCleanly(thread: Thread) {
        val deadlineMs = SystemClock.elapsedRealtime() + WORKER_JOIN_TIMEOUT_MS
        while (thread.isAlive) {
            thread.interrupt()
            val remainingMs = deadlineMs - SystemClock.elapsedRealtime()
            if (remainingMs <= 0L) break
            runCatching { thread.join(minOf(remainingMs, WORKER_JOIN_SLICE_MS)) }
        }
    }

    private fun logUnderrunDeltaIfAny(track: AudioTrack?, trigger: String) {
        val logger = diagnosticLogger ?: return
        if (!logger.isEnabled || track == null) return
        val current = trackWriter.readUnderrunCount(track)
        val delta = current - lastReportedUnderrunCount
        if (delta > 0) {
            lastReportedUnderrunCount = current
            logger.logPerf("TRACK_UNDERRUNS") {
                linkedMapOf(
                    "delta" to delta,
                    "total" to current,
                    "trigger" to trigger,
                    "sr" to audioTrackActualSampleRateHz,
                    "enc" to audioTrackActualEncodingLabel,
                    "mode" to safetyController.state().mode.name,
                )
            }
        }
    }

    private fun recomputeReplayGain(): ReplayGainResolution =
        ReplayGainProcessor.resolve(replayGainConfig, activeTrackReplayGainMetadata, safetyController.state().mode)
            .also { activeReplayGainResolution = it }

    private fun publishDiagnostics() {
        val safety = safetyController.state()
        val format = activeDecoder?.format
        val pendingReq = currentRequest
        val snapshot = if (format == null && _state.value.status == PlaybackStatus.PREPARING && pendingReq != null) {
            routeAndMixerManager.buildPendingTrackDiagnosticsSnapshot(
                safety = safety,
                container = pendingReq.expectedContainer,
                codec = pendingReq.expectedCodec,
                sampleRateHz = pendingReq.expectedSampleRateHz,
                bitDepth = pendingReq.expectedBitDepth,
                replayGainResolution = recomputeReplayGain(),
            )
        } else {
            routeAndMixerManager.buildDiagnosticSnapshot(
                safety,
                format,
                audioTrackActualSampleRateHz,
                audioTrackActualEncodingLabel,
                recomputeReplayGain(),
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
            !isPausedFlag.get() ||
            commandCoalescer.isStartOrLoadInFlight() ||
            hasDeferredPlayPending()
    }

    private fun handlePausedSafeModeRouteRefresh(coalesceDiagnostics: Boolean) {
        preloadController.clearPreloadedNextAsync()
        if (activeAudioTrack != null) {
            scratchRunner.markRouteChangedWhilePaused()
        }
        val needsReconfigure = currentRequest != null &&
            (
                activeTrackUsesBitPerfectOrPreferredRoute ||
                    routeAndMixerManager.hasActivePreferredMixer ||
                    !trackWriter.isTrackHealthy(activeAudioTrack)
                )
        if (needsReconfigure) {
            pendingRouteReconfigureOnPlay.set(true)
        }
        if (coalesceDiagnostics) {
            mainHandler.removeCallbacks(coalescedDiagnosticsRunnable)
            mainHandler.postDelayed(
                coalescedDiagnosticsRunnable,
                UsbRouteStabilityTracker.DEFAULT_DIAGNOSTICS_COALESCE_MS,
            )
        } else {
            cancelCoalescedDiagnosticsPublish()
            publishDiagnostics()
        }
    }

    private fun cancelCoalescedDiagnosticsPublish() {
        mainHandler.removeCallbacks(coalescedDiagnosticsRunnable)
    }

    private fun activeDeferredFirstRequestMs(fallbackNowMs: Long): Long = synchronized(deferredPlayLock) {
        if (deferredPlayRunnable != null && deferredPlayFirstRequestMs > 0L) {
            deferredPlayFirstRequestMs
        } else {
            fallbackNowMs
        }
    }

    private fun hasDeferredPlayPending(): Boolean = synchronized(deferredPlayLock) {
        deferredPlayRunnable != null
    }

    private fun scheduleDeferredPlay(attemptSeq: Long, firstRequestMs: Long, delayMs: Long) {
        val holder = arrayOfNulls<Runnable>(1)
        val runnable = Runnable {
            val self = holder[0] ?: return@Runnable
            executeDeferredPlayIfStillValid(self, attemptSeq, firstRequestMs)
        }
        holder[0] = runnable
        synchronized(deferredPlayLock) {
            deferredPlayRunnable?.let { mainHandler.removeCallbacks(it) }
            deferredPlayRunnable = runnable
            deferredPlayAttemptSeq = attemptSeq
            deferredPlayFirstRequestMs = firstRequestMs
        }
        mainHandler.postDelayed(runnable, delayMs.coerceAtLeast(1L))
    }

    private fun cancelDeferredPlay() {
        val toCancel = synchronized(deferredPlayLock) {
            val prev = deferredPlayRunnable
            deferredPlayRunnable = null
            deferredPlayAttemptSeq = 0L
            deferredPlayFirstRequestMs = 0L
            prev
        }
        if (toCancel != null) {
            mainHandler.removeCallbacks(toCancel)
        }
    }

    private fun executeDeferredPlayIfStillValid(expectedRunnable: Runnable, attemptSeq: Long, firstRequestMs: Long) {
        val ownsToken = synchronized(deferredPlayLock) {
            val matches = deferredPlayRunnable === expectedRunnable && deferredPlayAttemptSeq == attemptSeq
            if (matches) {
                deferredPlayRunnable = null
                deferredPlayAttemptSeq = 0L
                deferredPlayFirstRequestMs = 0L
            }
            matches
        }
        if (!ownsToken || releasedFlag.get()) return
        if (!playResumeAbortGuard.isResumeAttemptValid(attemptSeq)) {
            clearPreparingFlagsAfterAbortedPlay()
            return
        }
        val nowMs = SystemClock.elapsedRealtime()
        val routeGuard = routeAndMixerManager.evaluatePlayResume(
            nowElapsedMs = nowMs,
            firstPlayRequestElapsedMs = firstRequestMs,
        )
        when (routeGuard.decision) {
            PlayResumeRouteDecision.PROCEED_NOW -> {
                if (commandCoalescer.submitPlay()) {
                    scheduleControlDrain()
                }
            }
            PlayResumeRouteDecision.DEFER_FOR_ROUTE_SETTLE -> {
                diagnosticLogger?.logAudio("PLAY_DEFERRED_ROUTE_FLAP") {
                    linkedMapOf("delayMs" to routeGuard.deferDelayMs)
                }
                scheduleDeferredPlay(
                    attemptSeq = attemptSeq,
                    firstRequestMs = firstRequestMs,
                    delayMs = routeGuard.deferDelayMs,
                )
            }
            PlayResumeRouteDecision.ABORT_ROUTE_UNSTABLE -> {
                playResumeAbortGuard.cancelInFlightResumes()
                diagnosticLogger?.logAudio("PLAY_ABORTED_ROUTE_FLAP") {
                    linkedMapOf("waitedMs" to (nowMs - firstRequestMs).coerceAtLeast(0L))
                }
                clearPreparingFlagsAfterAbortedPlay()
            }
        }
    }

    private fun isResumeAuthorizedForPlayback(attemptSeq: Long, checkFlapping: Boolean): Boolean {
        if (releasedFlag.get() || commandCoalescer.hasPendingPauseOrStop()) return false
        if (attemptSeq <= 0L || !playResumeAbortGuard.isResumeAttemptValid(attemptSeq)) return false
        if (checkFlapping && routeAndMixerManager.isUsbRouteActivelyFlapping(SystemClock.elapsedRealtime())) {
            return false
        }
        return true
    }

    private fun abortSupersededPlayLocked(
        track: AudioTrack?,
        pauseTrack: Boolean,
        abandonFocus: Boolean,
        trackPlayMs: Long,
        stage: String,
    ) {
        isPausedFlag.set(true)
        if (pauseTrack && track != null) {
            trackWriter.safePause(track)
        }
        if (abandonFocus) {
            routeAndMixerManager.abandonAudioFocusLocked()
        }
        diagnosticLogger?.logAudio("PLAY_RESUME_ABORTED") {
            linkedMapOf(
                "stage" to stage,
                "pauseTrack" to pauseTrack,
                "trackPlayMs" to trackPlayMs,
                "status" to _state.value.status.name,
                "mode" to safetyController.state().mode.name,
            )
        }
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
        private const val TAG = "BitPerfectAudio"
        private const val WORKER_JOIN_TIMEOUT_MS = 2_500L
        private const val WORKER_JOIN_SLICE_MS = 500L
    }
}
