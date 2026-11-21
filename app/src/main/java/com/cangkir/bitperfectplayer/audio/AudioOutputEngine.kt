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
    private val pendingSeekMs = AtomicLong(-1L)

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

    fun loadTrack(request: EngineTrackRequest) {
        if (releasedFlag.get()) return
        val generation = loadGeneration.incrementAndGet()
        scratchRunner.abortScratch()
        preloadController.clearPreloadedNextAsync()
        stopWorkerFlag.set(true)
        isPausedFlag.set(true)
        currentRequest = request
        _playbackPositionMs.value = request.startPositionMs.coerceAtLeast(0L)
        _state.update {
            it.copy(status = PlaybackStatus.PREPARING, activeTrackUri = request.uri, errorMessage = null, lastAutoPauseNotice = null)
        }
        enqueueControlTask {
            if (!isLoadStale(generation)) {
                engineLock.withLock { loadTrackLocked(request, generation, preserveAutoPauseNotice = false) }
            }
        }
    }

    fun play() {
        if (releasedFlag.get()) return
        enqueueControlTask {
            if (releasedFlag.get()) return@enqueueControlTask
            engineLock.withLock {
                if (activeDecoder == null || activeAudioTrack == null || _state.value.status == PlaybackStatus.COMPLETED) {
                    val req = currentRequest ?: return@withLock
                    val resumePos = if (_state.value.status == PlaybackStatus.COMPLETED) 0L else _playbackPositionMs.value
                    val generation = loadGeneration.incrementAndGet()
                    loadTrackLocked(req.copy(startPositionMs = resumePos, autoPlay = true), generation, preserveAutoPauseNotice = false)
                    return@withLock
                }
                val track = activeAudioTrack ?: return@withLock
                if (playbackThread?.isAlive != true) {
                    routeAndMixerManager.resumeAfterTransientFocusLoss = false
                    if (safetyController.state().mode == AudioOutputMode.SAFE_VOLUME_MODE) {
                        runCatching { track.setVolume(1.0f) }
                    }
                    if (startPlaybackWorkerLocked(_playbackPositionMs.value, loadGeneration.get())) {
                        _state.update { it.copy(status = PlaybackStatus.PLAYING, errorMessage = null, lastAutoPauseNotice = null) }
                    }
                    return@withLock
                }
                if (!routeAndMixerManager.requestAudioFocusLocked()) {
                    isPausedFlag.set(true)
                    trackWriter.safePause(track)
                    _state.update { it.copy(status = PlaybackStatus.PAUSED, errorMessage = "Audio focus unavailable") }
                    return@withLock
                }
                routeAndMixerManager.resumeAfterTransientFocusLoss = false
                stopWorkerFlag.set(false)
                isPausedFlag.set(false)
                if (safetyController.state().mode == AudioOutputMode.SAFE_VOLUME_MODE) {
                    runCatching { track.setVolume(1.0f) }
                }
                trackWriter.safePlay(track)
                _state.update { it.copy(status = PlaybackStatus.PLAYING, errorMessage = null, lastAutoPauseNotice = null) }
            }
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
        if (playbackThread?.isAlive == true) {
            pendingSeekMs.set(clamped)
        } else {
            enqueueControlTask {
                if (releasedFlag.get()) return@enqueueControlTask
                engineLock.withLock {
                    val decoder = activeDecoder ?: return@withLock
                    val seekStartMs = SystemClock.elapsedRealtime()
                    val actual = runCatching { decoder.seekToMs(clamped) }.getOrDefault(clamped)
                    activeAudioTrack?.let { trackWriter.safeFlush(it) }
                    scratchRunner.resetRingBufferIfPresent(0L)
                    trackWriter.resetSpectrumTapIfPresent(decoder.format.sampleRateHz, 0L)
                    _playbackPositionMs.value = actual
                    val seekMs = (SystemClock.elapsedRealtime() - seekStartMs).coerceAtLeast(0L)
                    diagnosticLogger?.logPerf("SEEK_LATENCY") {
                        linkedMapOf(
                            "seekMs" to seekMs,
                            "targetMs" to clamped,
                            "actualMs" to actual,
                            "state" to "paused",
                        )
                    }
                }
            }
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
        preloadController.clearPreloadedNextLocked()
        stopWorkerAndReleaseTrackLocked(clearMixer = true)
        scratchRunner.releaseRingBuffer()
        trackWriter.releaseSpectrumTap()
        routeAndMixerManager.abandonAudioFocusLocked()
    }

    private fun isLoadStale(generation: Long): Boolean = releasedFlag.get() || loadGeneration.get() != generation

    private fun loadTrackLocked(request: EngineTrackRequest, generation: Long, preserveAutoPauseNotice: Boolean) {
        if (isLoadStale(generation)) return
        val loadStartMs = SystemClock.elapsedRealtime()
        preloadController.clearPreloadedNextLocked()
        stopWorkerAndReleaseTrackLocked(clearMixer = true)
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
        val decoder = openDecoderForTrackLocked(request, generation) ?: return
        if (isLoadStale(generation)) {
            runCatching { decoder.close() }
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
        activateLoadedTrackLocked(request, decoder, audioTrack, generation, preserveAutoPauseNotice, loadStartMs)
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
        loadStartMs: Long,
    ) {
        activeDecoder = decoder
        activeAudioTrack = audioTrack
        _playbackPositionMs.value = decoder.currentPositionMs
        val startedPlaying = if (request.autoPlay) {
            if (safetyController.state().mode == AudioOutputMode.SAFE_VOLUME_MODE) {
                runCatching { audioTrack.setVolume(1.0f) }
            }
            startPlaybackWorkerLocked(decoder.currentPositionMs, generation)
        } else {
            isPausedFlag.set(true)
            routeAndMixerManager.resumeAfterTransientFocusLoss = false
            routeAndMixerManager.abandonAudioFocusLocked()
            false
        }
        _state.update { current ->
            current.copy(
                status = if (startedPlaying) PlaybackStatus.PLAYING else PlaybackStatus.PAUSED,
                activeTrackUri = request.uri,
                durationMs = decoder.format.durationMs,
                errorMessage = if (request.autoPlay && !startedPlaying) "Audio focus denied" else null,
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

    private fun handleSafetyCommand(command: SafetyCommand) {
        recomputeReplayGain()
        when (command.type) {
            SafetyEventType.AUTO_PAUSE -> {
                diagnosticLogger?.logAudio("AUTO_PAUSE") {
                    linkedMapOf(
                        "reason" to (command.autoPauseReason?.name ?: "UNKNOWN"),
                        "clearMixer" to command.requiresClearPreferredMixerAttributes,
                        "mode" to safetyController.state().mode.name,
                    )
                }
                scratchRunner.abortScratch()
                routeAndMixerManager.resumeAfterTransientFocusLoss = false
                pauseInternal(clearFocus = true, notice = AudioSafetyController.formatAutoPauseNotice(command.autoPauseReason))
                enqueueSafetyReconfigure(command.requiresClearPreferredMixerAttributes, releaseRing = false, autoResume = false, true)
            }
            SafetyEventType.MODE_CHANGED_TO_SAFE -> {
                diagnosticLogger?.logAudio("AUDIO_MODE_CHANGED") {
                    linkedMapOf(
                        "mode" to AudioOutputMode.SAFE_VOLUME_MODE.name,
                        "clearMixer" to command.requiresClearPreferredMixerAttributes,
                    )
                }
                enqueueSafetyReconfigure(command.requiresClearPreferredMixerAttributes, false, _state.value.isPlaying, false)
            }
            SafetyEventType.MODE_CHANGED_TO_BIT_PERFECT -> {
                diagnosticLogger?.logAudio("AUDIO_MODE_CHANGED") {
                    linkedMapOf(
                        "mode" to AudioOutputMode.BIT_PERFECT_OPT_IN.name,
                        "clearMixer" to false,
                    )
                }
                scratchRunner.abortScratch()
                enqueueSafetyReconfigure(false, releaseRing = true, autoResume = _state.value.isPlaying, preserveAutoPauseNotice = false)
            }
            SafetyEventType.REQUIRES_SAFETY_WARNING, SafetyEventType.NONE -> {
                _state.update { it.copy(safetyState = safetyController.state()) }
                publishDiagnostics()
            }
        }
    }

    private fun enqueueSafetyReconfigure(
        clearPreferredMixer: Boolean,
        releaseRing: Boolean,
        autoResume: Boolean,
        preserveAutoPauseNotice: Boolean,
    ) {
        enqueueControlTask {
            if (releasedFlag.get()) return@enqueueControlTask
            engineLock.withLock {
                if (clearPreferredMixer) routeAndMixerManager.clearPreferredMixerAttributesOnApi34(null)
                if (releaseRing) scratchRunner.releaseRingBuffer()
                reconfigureTrackIfLoadedLocked(autoResume, preserveAutoPauseNotice)
            }
        }
    }

    private fun reconfigureTrackIfLoadedLocked(autoResume: Boolean, preserveAutoPauseNotice: Boolean) {
        preloadController.clearPreloadedNextLocked()
        val req = currentRequest
        if (req == null || activeDecoder == null) {
            _state.update { it.copy(safetyState = safetyController.state()) }
            publishDiagnostics()
            return
        }
        val generation = loadGeneration.incrementAndGet()
        loadTrackLocked(req.copy(startPositionMs = _playbackPositionMs.value, autoPlay = autoResume), generation, preserveAutoPauseNotice)
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

    private fun startPlaybackWorkerLocked(initialSeekMs: Long, expectedGeneration: Long): Boolean {
        val decoder = activeDecoder ?: return false
        val track = activeAudioTrack ?: return false
        if (!routeAndMixerManager.requestAudioFocusLocked()) {
            isPausedFlag.set(true)
            trackWriter.safePause(track)
            _state.update { it.copy(status = PlaybackStatus.PAUSED, errorMessage = "Audio focus denied") }
            return false
        }
        stopWorkerFlag.set(false)
        isPausedFlag.set(false)
        pendingSeekMs.set(-1L)
        trackWriter.safePlay(track)

        val worker = Thread(
            {
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
                runPlaybackLoop(decoder, track, initialSeekMs, expectedGeneration)
            },
            "BitPerfectAudioThread",
        )
        playbackThread = worker
        worker.start()
        return true
    }

    @Suppress("TooGenericExceptionCaught") // Top-level audio worker thread must surface OEM HAL errors safely instead of crashing
    private fun runPlaybackLoop(
        initialDecoder: PcmAudioDecoder,
        initialTrack: AudioTrack,
        initialSeekMs: Long,
        expectedGeneration: Long,
    ) {
        val state = scratchRunner.createInitialLoopState(expectedGeneration, initialDecoder, initialTrack, initialSeekMs)
        try {
            while (trackWriter.isWorkerActive()) {
                if (!scratchRunner.executeLoopStep(state)) break
            }
        } catch (e: AudioDecodeException) {
            publishWorkerErrorIfRunning(e.message ?: "Playback decoding error")
        } catch (e: Exception) {
            publishWorkerErrorIfRunning("Audio playback error: ${e.message}")
        } finally {
            scratchRunner.abortScratch()
            runCatching { scratchRunner.accumulateActivePlayTime(state, forceFlush = true) }
        }
    }

    private fun publishWorkerErrorIfRunning(message: String) {
        if (!stopWorkerFlag.get()) {
            diagnosticLogger?.logError("PLAYBACK_WORKER_ERROR") {
                linkedMapOf("msg" to message, "mode" to safetyController.state().mode.name)
            }
            _state.update { it.copy(status = PlaybackStatus.ERROR, errorMessage = message) }
        }
    }

    private fun handleEndOfStreamInLoop(state: PlaybackLoopState): Boolean {
        scratchRunner.accumulateActivePlayTime(state, forceFlush = true)
        logUnderrunDeltaIfAny(state.currentTrack, trigger = "eos")
        val preloaded = preloadController.pollSeamlessPreloadedTrack(state.currentFormat, state.expectedGeneration)
        if (preloaded != null) return applySeamlessGaplessTransitionInLoop(state, preloaded)
        if (scratchRunner.drainTrackAtEndOfStream(state)) return true
        if (trackWriter.isWorkerActive()) {
            _playbackPositionMs.value = state.durationMs.takeIf { it > 0L } ?: _playbackPositionMs.value
            _state.update { it.copy(status = PlaybackStatus.COMPLETED) }
            mainHandler.post { onTrackCompleted?.invoke() }
        }
        return false
    }

    private fun applySeamlessGaplessTransitionInLoop(state: PlaybackLoopState, preloaded: PreloadedTrack): Boolean {
        val transitioned = engineLock.withLock {
            if (!trackWriter.isWorkerActive() || loadGeneration.get() != state.expectedGeneration) return@withLock false
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
            if (!trackWriter.isWorkerActive()) return@withLock null
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
            trackWriter.safePause(track)
            trackWriter.safeFlush(track)
        }
        val thread = playbackThread
        playbackThread = null
        if (thread != null && thread != Thread.currentThread()) {
            thread.interrupt()
            runCatching { thread.join(600L) }
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
        val snapshot = routeAndMixerManager.buildDiagnosticSnapshot(
            safety,
            activeDecoder?.format,
            audioTrackActualSampleRateHz,
            audioTrackActualEncodingLabel,
            recomputeReplayGain(),
        )
        _state.update { it.copy(safetyState = safety, diagnostics = snapshot) }
    }

    companion object {
        private const val TAG = "BitPerfectAudio"
    }
}
