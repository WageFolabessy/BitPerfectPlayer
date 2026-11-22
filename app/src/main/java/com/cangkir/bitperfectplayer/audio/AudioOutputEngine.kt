package com.cangkir.bitperfectplayer.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioTrack
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.cangkir.bitperfectplayer.diagnostics.DiagnosticLogFormatter
import com.cangkir.bitperfectplayer.diagnostics.LocalDiagnosticLogger
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
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

    private val engineLock = ReentrantLock()
    private val controlExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "BitPerfectEngineControl").apply { isDaemon = true }
    }
    private val loadGeneration = AtomicLong(0L)
    private val isPausedFlag = AtomicBoolean(true)
    private val stopWorkerFlag = AtomicBoolean(false)
    private val releasedFlag = AtomicBoolean(false)
    private val pendingRouteReconfigureOnPlay = AtomicBoolean(false)
    private val pendingSeekMs = AtomicLong(PendingSeekCoalescer.NO_PENDING_SEEK)
    private val seekCoalescer = PendingSeekCoalescer(pendingSeekMs)

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

    private val routeAndMixerManager = AudioRouteAndMixerManager(
        appContext, audioManager, mainHandler, playbackAudioAttributes, safetyController,
        onSafetyCommand = ::handleSafetyCommand,
        onDiagnosticsChanged = ::publishDiagnostics,
        onFocusLossPause = { pauseInternal(clearFocus = false, notice = null) },
        onFocusGainResume = ::play,
        onDuckSafeModeVolume = { vol -> runCatching { activeAudioTrack?.setVolume(vol) } },
        isCurrentlyPlaying = { _state.value.isPlaying },
        isReleased = { releasedFlag.get() },
        diagnosticLogger = diagnosticLogger,
    )
    private val trackWriter = AudioTrackWriter(
        playbackAudioAttributes,
        stopWorkerFlag,
        releasedFlag,
        isPausedFlag,
        pendingSeekMs,
        loadGeneration,
        diagnosticLogger = diagnosticLogger,
    )
    private val preloadController = GaplessPreloadController(appContext, releasedFlag, loadGeneration)
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
        val generation = loadGeneration.incrementAndGet()
        consecutiveAutoRecoveries = 0
        pendingRouteReconfigureOnPlay.set(false)
        scratchRunner.abortScratch()
        seekCoalescer.clear()
        preloadController.clearPreloadedNextAsync()
        stopWorkerFlag.set(true)
        isPausedFlag.set(true)
        currentRequest = request
        activeTrackReplayGainMetadata = null
        _playbackPositionMs.value = request.startPositionMs.coerceAtLeast(0L)
        val pendingDiag = routeAndMixerManager.buildPendingTrackDiagnosticsSnapshot(
            safety = safetyController.state(),
            container = request.expectedContainer,
            codec = request.expectedCodec,
            sampleRateHz = request.expectedSampleRateHz,
            bitDepth = request.expectedBitDepth,
            replayGainResolution = recomputeReplayGain(),
        )
        _state.update {
            it.copy(
                status = PlaybackStatus.PREPARING,
                activeTrackUri = request.uri,
                durationMs = request.expectedDurationMs.coerceAtLeast(0L),
                diagnostics = pendingDiag,
                errorMessage = null,
                lastAutoPauseNotice = null,
            )
        }
        enqueueControlTask {
            if (!isLoadStale(generation)) {
                engineLock.withLock {
                    loadTrackLocked(
                        request = request,
                        generation = generation,
                        preserveAutoPauseNotice = false,
                        forceAudioFocus = false,
                    )
                }
            }
        }
    }

    fun play() {
        if (releasedFlag.get()) return
        consecutiveAutoRecoveries = 0
        // Immediately dismiss any Auto-paused banner or transient error on the very first Play tap.
        _state.update { it.copy(errorMessage = null, lastAutoPauseNotice = null) }
        enqueueControlTask {
            if (releasedFlag.get()) return@enqueueControlTask
            engineLock.withLock { playLocked() }
        }
    }

    private fun playLocked() {
        val track = activeAudioTrack
        val shouldReload = RouteChangeReconfigurePolicy.shouldReloadOnPlay(
            hasPendingRouteReconfigure = pendingRouteReconfigureOnPlay.get(),
            hasActiveDecoder = activeDecoder != null,
            hasActiveAudioTrack = track != null,
            isCompleted = _state.value.status == PlaybackStatus.COMPLETED,
            isError = _state.value.status == PlaybackStatus.ERROR,
            isAudioTrackHealthy = trackWriter.isTrackHealthy(track),
        )
        if (shouldReload) {
            reloadCurrentTrackForPlayLocked()
            return
        }
        val activeTrack = track ?: return
        if (playbackThread?.isAlive != true) {
            routeAndMixerManager.resumeAfterTransientFocusLoss = false
            if (safetyController.state().mode == AudioOutputMode.SAFE_VOLUME_MODE) {
                runCatching { activeTrack.setVolume(1.0f) }
            }
            when (
                startPlaybackWorkerLocked(
                    initialSeekMs = _playbackPositionMs.value,
                    expectedGeneration = loadGeneration.get(),
                    forceAudioFocus = true,
                    clearPendingSeek = false,
                )
            ) {
                WorkerStartResult.STARTED -> {
                    _state.update { it.copy(status = PlaybackStatus.PLAYING, errorMessage = null, lastAutoPauseNotice = null) }
                }
                WorkerStartResult.AUDIO_TRACK_FAILED -> reloadCurrentTrackForPlayLocked()
                WorkerStartResult.AUDIO_FOCUS_DENIED -> Unit
            }
            return
        }
        if (!routeAndMixerManager.requestAudioFocusLocked(forceRequest = true)) {
            isPausedFlag.set(true)
            trackWriter.safePause(activeTrack)
            _state.update { it.copy(status = PlaybackStatus.PAUSED, errorMessage = "Audio focus unavailable") }
            return
        }
        routeAndMixerManager.resumeAfterTransientFocusLoss = false
        stopWorkerFlag.set(false)
        isPausedFlag.set(false)
        if (safetyController.state().mode == AudioOutputMode.SAFE_VOLUME_MODE) {
            runCatching { activeTrack.setVolume(1.0f) }
        }
        if (!trackWriter.safePlay(activeTrack)) {
            reloadCurrentTrackForPlayLocked()
            return
        }
        _state.update { it.copy(status = PlaybackStatus.PLAYING, errorMessage = null, lastAutoPauseNotice = null) }
    }

    private fun reloadCurrentTrackForPlayLocked() {
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
        )
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
        routeAndMixerManager.resumeAfterTransientFocusLoss = false
        pauseInternal(clearFocus = true, notice = null)
    }

    fun notifyBackgroundStartBlocked(notice: String) {
        routeAndMixerManager.resumeAfterTransientFocusLoss = false
        pauseInternal(clearFocus = true, notice = notice)
        _state.update { current ->
            current.copy(
                status = PlaybackStatus.PAUSED,
                errorMessage = null,
                lastAutoPauseNotice = notice,
            )
        }
        enqueueControlTask {
            if (releasedFlag.get()) return@enqueueControlTask
            _state.update { current ->
                current.copy(
                    status = PlaybackStatus.PAUSED,
                    errorMessage = null,
                    lastAutoPauseNotice = notice,
                )
            }
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
        scratchRunner.abortScratch()
        if (_state.value.status == PlaybackStatus.IDLE && activeAudioTrack == null && activeDecoder == null) {
            scratchRunner.releaseRingBuffer()
            trackWriter.releaseSpectrumTap()
            preloadController.clearPreloadedNextAsync()
            return
        }
        loadGeneration.incrementAndGet()
        preloadController.clearQueuedAndPreloadedAsync()
        routeAndMixerManager.resumeAfterTransientFocusLoss = false
        stopWorkerFlag.set(true)
        isPausedFlag.set(true)
        enqueueControlTask {
            engineLock.withLock {
                teardownFullEngineResourcesLocked()
                activeTrackReplayGainMetadata = null
                recomputeReplayGain()
                _playbackPositionMs.value = 0L
                _state.update { it.copy(status = PlaybackStatus.IDLE, errorMessage = null) }
                publishDiagnostics()
                diagnosticLogger?.requestFlush()
            }
        }
    }

    fun requestBitPerfectMode(): SafetyCommand = safetyController.requestBitPerfectMode().also(::handleSafetyCommand)

    fun confirmBitPerfectWarning(): SafetyCommand = safetyController.confirmBitPerfectWarning().also(::handleSafetyCommand)

    fun dismissSafetyWarning(): SafetyCommand = safetyController.dismissSafetyWarning().also(::handleSafetyCommand)

    fun switchToSafeMode(): SafetyCommand = safetyController.switchToSafeMode().also(::handleSafetyCommand)

    fun clearAutoPauseNotice() = _state.update { it.copy(lastAutoPauseNotice = null) }

    fun release() {
        if (!releasedFlag.compareAndSet(false, true)) return
        scratchRunner.abortScratch()
        trackWriter.deactivateAndReleaseSpectrumTap()
        loadGeneration.incrementAndGet()
        stopWorkerFlag.set(true)
        isPausedFlag.set(true)
        routeAndMixerManager.unregisterAudioMonitors()
        preloadController.shutdown()
        runCatching {
            controlExecutor.execute {
                engineLock.withLock { teardownFullEngineResourcesLocked() }
            }
        }
        controlExecutor.shutdown()
    }

    private fun teardownFullEngineResourcesLocked() {
        pendingRouteReconfigureOnPlay.set(false)
        preloadController.clearPreloadedNextLocked()
        stopWorkerAndReleaseTrackLocked(clearMixer = true)
        scratchRunner.releaseRingBuffer()
        trackWriter.releaseSpectrumTap()
        routeAndMixerManager.abandonAudioFocusLocked()
    }

    private fun isLoadStale(generation: Long): Boolean = releasedFlag.get() || loadGeneration.get() != generation

    private enum class WorkerStartResult {
        STARTED,
        AUDIO_FOCUS_DENIED,
        AUDIO_TRACK_FAILED,
    }

    private fun loadTrackLocked(
        request: EngineTrackRequest,
        generation: Long,
        preserveAutoPauseNotice: Boolean,
        forceAudioFocus: Boolean = false,
        clearMixerBeforeCreate: Boolean = false,
    ) {
        if (isLoadStale(generation)) return
        pendingRouteReconfigureOnPlay.set(false)
        val loadStartMs = SystemClock.elapsedRealtime()
        preloadController.clearPreloadedNextLocked()
        stopWorkerAndReleaseTrackLocked(clearMixer = clearMixerBeforeCreate)
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

        // Open and prime decoder BEFORE creating AudioTrack so codec.outputFormat is confirmed.
        val decoder = openDecoderForTrackLocked(request, generation)
        if (decoder == null) {
            routeAndMixerManager.clearPreferredMixerAttributesOnApi34(null)
            return
        }
        if (isLoadStale(generation)) {
            runCatching { decoder.close() }
            routeAndMixerManager.clearPreferredMixerAttributesOnApi34(null)
            return
        }

        // OOM-safe header-only ReplayGain tag scan (< 1 ms on control thread, zero Main Thread I/O).
        activeTrackReplayGainMetadata = preloadController.readReplayGainMetadata(request.uri, request.fileName, request.mimeType)
        recomputeReplayGain()
        if (request.startPositionMs > 0L) runCatching { decoder.seekToMs(request.startPositionMs) }

        val audioTrack = createAudioTrackForDecoderLocked(decoder, generation) ?: return
        if (isLoadStale(generation)) {
            trackWriter.safeStopAndRelease(audioTrack)
            runCatching { decoder.close() }
            routeAndMixerManager.clearPreferredMixerAttributesOnApi34(null)
            return
        }
        activateLoadedTrackLocked(
            request = request,
            decoder = decoder,
            audioTrack = audioTrack,
            generation = generation,
            preserveAutoPauseNotice = preserveAutoPauseNotice,
            forceAudioFocus = forceAudioFocus,
            loadStartMs = loadStartMs,
        )
    }

    private fun openDecoderForTrackLocked(request: EngineTrackRequest, generation: Long): PcmAudioDecoder? = try {
        MediaCodecAudioDecoder.open(
            context = appContext,
            uri = request.uri,
            fileName = request.fileName,
            mimeHint = request.mimeType,
            diagnosticLogger = diagnosticLogger,
        )
    } catch (e: AudioDecodeException) {
        if (loadGeneration.get() == generation) {
            activeDecoder = null
            _state.update { it.copy(status = PlaybackStatus.ERROR, errorMessage = e.message ?: "Unable to decode audio file") }
            publishDiagnostics()
        }
        null
    }

    private fun createAudioTrackForDecoderLocked(decoder: PcmAudioDecoder, generation: Long): AudioTrack? =
        trackWriter.tryCreateConfiguredAudioTrackLocked(decoder.format, routeAndMixerManager)
            .onSuccess { configured ->
                audioTrackActualSampleRateHz = configured.actualSampleRateHz
                audioTrackActualEncodingLabel = configured.actualEncodingLabel
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
                routeAndMixerManager.clearPreferredMixerAttributesOnApi34(null)
                if (loadGeneration.get() == generation) {
                    _state.update {
                        it.copy(status = PlaybackStatus.ERROR, errorMessage = "AudioTrack initialization failed: ${error.message}")
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
        loadStartMs: Long,
    ) {
        activeDecoder = decoder
        activeAudioTrack = audioTrack
        val initialPosMs = if (request.startPositionMs == 0L) 0L else decoder.currentPositionMs.coerceAtLeast(0L)
        _playbackPositionMs.value = initialPosMs
        val startResult = startLoadedTrackIfAutoPlayLocked(
            request = request,
            decoder = decoder,
            audioTrack = audioTrack,
            initialPosMs = initialPosMs,
            generation = generation,
            forceAudioFocus = forceAudioFocus,
        )
        val startedPlaying = startResult == WorkerStartResult.STARTED
        if (startResult == WorkerStartResult.AUDIO_TRACK_FAILED) {
            pendingRouteReconfigureOnPlay.set(true)
        }
        val errMsg = when {
            !request.autoPlay || startedPlaying -> null
            startResult == WorkerStartResult.AUDIO_FOCUS_DENIED -> "Audio focus denied"
            else -> null
        }
        _state.update { current ->
            current.copy(
                status = if (startedPlaying) PlaybackStatus.PLAYING else PlaybackStatus.PAUSED,
                activeTrackUri = request.uri,
                durationMs = decoder.format.durationMs.takeIf { it > 0L } ?: request.expectedDurationMs.coerceAtLeast(0L),
                errorMessage = errMsg,
                lastAutoPauseNotice = if (preserveAutoPauseNotice) current.lastAutoPauseNotice else null,
            )
        }
        publishDiagnostics()
        val loadMs = (SystemClock.elapsedRealtime() - loadStartMs).coerceAtLeast(0L)
        diagnosticLogger?.logPerf("TRACK_LOAD_LATENCY") {
            linkedMapOf(
                "loadMs" to loadMs,
                "autoPlay" to request.autoPlay,
                "startedPlaying" to startedPlaying,
                "sr" to decoder.format.sampleRateHz,
                "enc" to decoder.format.encoding.name,
                "ch" to decoder.format.channelCount,
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
        routeAndMixerManager.clearPreferredMixerAttributesOnApi34(null)
        val rebuilt = trackWriter.tryCreateConfiguredAudioTrackLocked(decoder.format, routeAndMixerManager)
            .onSuccess { configured ->
                audioTrackActualSampleRateHz = configured.actualSampleRateHz
                audioTrackActualEncodingLabel = configured.actualEncodingLabel
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
        )
    }

    private fun handleSafetyCommand(command: SafetyCommand) {
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
        val reconfigureAction = RouteChangeReconfigurePolicy.decideAction(
            commandType = command.type,
            isActivelyPlayingOrPreparing = isActivelyPlayingOrPreparing,
            hasLoadedTrack = hasLoadedTrack,
        )
        when (command.type) {
            SafetyEventType.AUTO_PAUSE -> {
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
                if (reconfigureAction == SafetyReconfigureAction.TEARDOWN_AND_REBIND_ON_PLAY) {
                    pendingRouteReconfigureOnPlay.set(true)
                    stopWorkerFlag.set(true)
                }
                pauseInternal(clearFocus = true, notice = AudioSafetyController.formatAutoPauseNotice(command.autoPauseReason))
                enqueueSafetyReconfigure(
                    action = reconfigureAction,
                    clearPreferredMixer = command.requiresClearPreferredMixerAttributes,
                    releaseRing = false,
                    autoResume = false,
                    preserveAutoPauseNotice = true,
                )
            }
            SafetyEventType.MODE_CHANGED_TO_SAFE -> {
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
                    autoResume = statusBeforeCommand == PlaybackStatus.PLAYING,
                    preserveAutoPauseNotice = false,
                )
            }
            SafetyEventType.MODE_CHANGED_TO_BIT_PERFECT -> {
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
                    autoResume = statusBeforeCommand == PlaybackStatus.PLAYING,
                    preserveAutoPauseNotice = false,
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
    ) {
        enqueueControlTask {
            if (releasedFlag.get()) return@enqueueControlTask
            engineLock.withLock {
                if (releaseRing) scratchRunner.releaseRingBuffer()
                when (action) {
                    SafetyReconfigureAction.RECONFIGURE_NOW -> {
                        if (clearPreferredMixer) routeAndMixerManager.clearPreferredMixerAttributesOnApi34(null)
                        reconfigureTrackIfLoadedLocked(autoResume, preserveAutoPauseNotice)
                    }
                    SafetyReconfigureAction.TEARDOWN_AND_REBIND_ON_PLAY ->
                        teardownRouteInvalidatedSessionLocked(clearPreferredMixer)
                    SafetyReconfigureAction.DEFER_RECONFIGURE_UNTIL_PLAY -> {
                        if (clearPreferredMixer) routeAndMixerManager.clearPreferredMixerAttributesOnApi34(null)
                        deferRouteReconfigureWhilePausedLocked()
                    }
                    SafetyReconfigureAction.DIAGNOSTICS_ONLY -> {
                        if (clearPreferredMixer) routeAndMixerManager.clearPreferredMixerAttributesOnApi34(null)
                        preloadController.clearPreloadedNextLocked()
                        _state.update { it.copy(safetyState = safetyController.state()) }
                        publishDiagnostics()
                    }
                }
            }
        }
    }

    /**
     * Tears down the invalidated worker thread, dead `AudioTrack`, and decoder after a USB plug/unplug
     * or `BECOMING_NOISY` auto-pause while preserving [currentRequest] and exact [_playbackPositionMs]
     * so the very first Play tap cleanly rebinds on the active output route in `< 500 ms`.
     */
    private fun teardownRouteInvalidatedSessionLocked(clearPreferredMixer: Boolean) {
        preloadController.clearPreloadedNextLocked()
        stopWorkerAndReleaseTrackLocked(clearMixer = clearPreferredMixer)
        routeAndMixerManager.abandonAudioFocusLocked()
        if (currentRequest != null) {
            pendingRouteReconfigureOnPlay.set(true)
        }
        _state.update { current ->
            val safeStatus = if (current.status == PlaybackStatus.PLAYING || current.status == PlaybackStatus.PREPARING) {
                PlaybackStatus.PAUSED
            } else {
                current.status
            }
            current.copy(status = safeStatus, safetyState = safetyController.state())
        }
        publishDiagnostics()
    }

    private fun deferRouteReconfigureWhilePausedLocked() {
        preloadController.clearPreloadedNextLocked()
        // Guard against a narrow race where an earlier queued play() started right before this task ran.
        isPausedFlag.set(true)
        activeAudioTrack?.let { trackWriter.safePause(it) }
        if (currentRequest != null) {
            pendingRouteReconfigureOnPlay.set(true)
        }
        _state.update { current ->
            val safeStatus = if (current.status == PlaybackStatus.PLAYING || current.status == PlaybackStatus.PREPARING) {
                PlaybackStatus.PAUSED
            } else {
                current.status
            }
            current.copy(status = safeStatus, safetyState = safetyController.state())
        }
        publishDiagnostics()
    }

    private fun reconfigureTrackIfLoadedLocked(autoResume: Boolean, preserveAutoPauseNotice: Boolean) {
        preloadController.clearPreloadedNextLocked()
        val req = currentRequest
        if (req == null || (activeDecoder == null && !pendingRouteReconfigureOnPlay.get())) {
            _state.update { it.copy(safetyState = safetyController.state()) }
            publishDiagnostics()
            return
        }
        val generation = loadGeneration.incrementAndGet()
        loadTrackLocked(
            request = req.copy(startPositionMs = _playbackPositionMs.value, autoPlay = autoResume),
            generation = generation,
            preserveAutoPauseNotice = preserveAutoPauseNotice,
            forceAudioFocus = autoResume,
        )
    }

    private fun pauseInternal(clearFocus: Boolean, notice: String?) {
        scratchRunner.abortScratch()
        val wasPaused = isPausedFlag.getAndSet(true)
        activeAudioTrack?.let { track ->
            logUnderrunDeltaIfAny(track, trigger = "pause")
            trackWriter.safePause(track)
        }
        val currentStatus = _state.value.status
        val needsStateUpdate = !wasPaused ||
            currentStatus == PlaybackStatus.PLAYING ||
            currentStatus == PlaybackStatus.PREPARING ||
            notice != null

        if (needsStateUpdate) {
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
                )
            }
            publishDiagnostics()
        }
        diagnosticLogger?.requestFlush()
        if (clearFocus && routeAndMixerManager.hasAudioFocus) {
            enqueueControlTask {
                if (!releasedFlag.get()) engineLock.withLock { routeAndMixerManager.abandonAudioFocusLocked() }
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
        lastReportedUnderrunCount = 0
        return configured.track
    }

    private fun startPlaybackWorkerLocked(
        initialSeekMs: Long,
        expectedGeneration: Long,
        forceAudioFocus: Boolean = false,
        clearPendingSeek: Boolean = true,
    ): WorkerStartResult {
        val decoder = activeDecoder ?: return WorkerStartResult.AUDIO_TRACK_FAILED
        val track = activeAudioTrack ?: return WorkerStartResult.AUDIO_TRACK_FAILED
        if (!trackWriter.isTrackHealthy(track)) return WorkerStartResult.AUDIO_TRACK_FAILED
        if (!routeAndMixerManager.requestAudioFocusLocked(forceRequest = forceAudioFocus)) {
            isPausedFlag.set(true)
            trackWriter.safePause(track)
            _state.update { it.copy(status = PlaybackStatus.PAUSED, errorMessage = "Audio focus denied") }
            return WorkerStartResult.AUDIO_FOCUS_DENIED
        }
        if (!trackWriter.safePlay(track)) {
            isPausedFlag.set(true)
            return WorkerStartResult.AUDIO_TRACK_FAILED
        }
        stopWorkerFlag.set(false)
        isPausedFlag.set(false)
        if (clearPendingSeek) seekCoalescer.clear()

        val worker = Thread(
            {
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
                runPlaybackLoop(decoder, track, initialSeekMs, expectedGeneration)
            },
            "BitPerfectAudioThread",
        )
        playbackThread = worker
        worker.start()
        return WorkerStartResult.STARTED
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
                if (releasedFlag.get() || loadGeneration.get() != expectedGeneration || isPausedFlag.get()) {
                    return@enqueueControlTask
                }
                engineLock.withLock {
                    if (releasedFlag.get() || loadGeneration.get() != expectedGeneration || isPausedFlag.get()) {
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

    private fun reconcileStatusOnWorkerExit(expectedGeneration: Long, recoveryScheduled: Boolean) {
        val isGenCurrent = loadGeneration.get() == expectedGeneration
        _state.update { current ->
            val nextStatus = PlaybackSeekAndRecoveryPolicy.resolveStatusOnWorkerExit(
                currentStatus = current.status,
                isGenerationCurrent = isGenCurrent,
                isRecoveryScheduled = recoveryScheduled,
            )
            if (nextStatus != current.status) current.copy(status = nextStatus) else current
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
            _state.update { it.copy(status = PlaybackStatus.ERROR, errorMessage = message) }
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

    private fun stopWorkerAndReleaseTrackLocked(clearMixer: Boolean) {
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
        if (clearMixer) routeAndMixerManager.clearPreferredMixerAttributesOnApi34(null)
        audioTrackActualSampleRateHz = 0
        audioTrackActualEncodingLabel = "None"
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
        _state.update { it.copy(safetyState = safety, diagnostics = snapshot) }
    }

    companion object {
        private const val TAG = "BitPerfectAudio"
        private const val WORKER_JOIN_TIMEOUT_MS = 2_500L
        private const val WORKER_JOIN_SLICE_MS = 500L
    }
}
