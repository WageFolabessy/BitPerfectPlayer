package com.cangkir.bitperfectplayer.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.media.AudioTrack
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.cangkir.bitperfectplayer.lyrics.FileChannelSeekableByteSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.ceil
import kotlin.math.roundToLong

enum class PlaybackStatus {
    IDLE,
    PREPARING,
    PLAYING,
    PAUSED,
    COMPLETED,
    ERROR,
}

data class EngineTrackRequest(
    val uri: Uri,
    val fileName: String,
    val mimeType: String? = null,
    val startPositionMs: Long = 0L,
    val autoPlay: Boolean = true,
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
    val isPlaying: Boolean
        get() = status == PlaybackStatus.PLAYING
}

/**
 * Native Android [AudioTrack] playback engine with:
 * - Confirmed `MediaCodec.outputFormat` negotiation before `AudioTrack` creation (and dynamic
 *   reconfiguration if `INFO_OUTPUT_FORMAT_CHANGED` occurs mid-stream).
 * - True gapless playback (background next-track decoder pre-loading + seamless same-format
 *   [AudioTrack] reuse with zero silence gap) and clean `AudioTrack` buffer draining on format
 *   changes or end-of-queue so tail audio is never truncated.
 * - Safe-Mode-only zero-allocation ReplayGain gain scaling with mandatory automatic bypass
 *   (`gain = 1.0f`, zero sample modification) in [AudioOutputMode.BIT_PERFECT_OPT_IN] mode.
 * - Frame-aligned direct little-endian `ByteBuffer` writes for integer PCM and `FloatArray` writes
 *   for `ENCODING_PCM_FLOAT` with full partial-write loops.
 * - Runtime-adaptive Android 14+ (`API 34+`) `MIXER_BEHAVIOR_BIT_PERFECT` negotiation on USB audio outputs
 *   with mandatory `clearPreferredMixerAttributes` cleanup.
 * - Hearing-safety auto-pause on `ACTION_AUDIO_BECOMING_NOISY`, USB connect/disconnect, and route change.
 */
class AudioOutputEngine(
    context: Context,
    private val safetyController: AudioSafetyController = AudioSafetyController(),
    private val onTrackCompleted: (() -> Unit)? = null,
    private val onGaplessTransition: ((EngineTrackRequest) -> Unit)? = null,
    private val onActivePlaybackElapsedMs: ((Long) -> Unit)? = null,
) {
    private data class PreloadedTrack(
        val request: EngineTrackRequest,
        val decoder: PcmAudioDecoder,
        val replayGainMetadata: ReplayGainMetadata?,
        val generation: Long,
    )

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val playbackAudioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private val _state = MutableStateFlow(
        EnginePlaybackState(
            safetyState = safetyController.state(),
            diagnostics = AudioDiagnosticSnapshot.idle(),
        ),
    )
    val state: StateFlow<EnginePlaybackState> = _state.asStateFlow()

    private val _playbackPositionMs = MutableStateFlow(0L)
    val playbackPositionMs: StateFlow<Long> = _playbackPositionMs.asStateFlow()

    private val engineLock = ReentrantLock()
    private val preloadLock = Any()
    private val controlExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "BitPerfectEngineControl").apply { isDaemon = true }
    }
    private val preloadExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "BitPerfectGaplessPreload").apply { isDaemon = true }
    }
    private val loadGeneration = AtomicLong(0L)
    private val isPausedFlag = AtomicBoolean(true)
    private val stopWorkerFlag = AtomicBoolean(false)
    private val releasedFlag = AtomicBoolean(false)
    private val pendingSeekMs = AtomicLong(-1L)

    // Safe-Mode-only real-time turntable scratch state (lock-free UI -> Audio thread signaling).
    // Ring buffer is NEVER allocated or written in BIT_PERFECT_OPT_IN mode.
    private val turntableScratchRequested = AtomicBoolean(false)
    private val turntableTargetRateBits = AtomicInteger(0)
    private val turntableFallbackSeekMs = AtomicLong(-1L)

    @Volatile
    private var turntableRingBuffer: TurntablePcmRingBuffer? = null

    // Strictly read-only, lifecycle-gated PCM tap (~192 KB) for the radial FFT spectrum visualizer.
    // Allocated lazily ONLY when active; released immediately when disabled, backgrounded, or stopped.
    private val spectrumTapActive = AtomicBoolean(false)

    @Volatile
    private var spectrumPcmTap: SpectrumPcmTap? = null

    @Volatile
    private var gaplessEnabled: Boolean = true

    @Volatile
    private var queuedNextRequest: EngineTrackRequest? = null

    private var preloadedTrack: PreloadedTrack? = null
    private var preloadingUri: Uri? = null

    @Volatile
    private var replayGainConfig: ReplayGainConfig = ReplayGainConfig()

    @Volatile
    private var activeTrackReplayGainMetadata: ReplayGainMetadata? = null

    @Volatile
    private var activeReplayGainResolution: ReplayGainResolution = ReplayGainProcessor.resolve(
        config = ReplayGainConfig(),
        metadata = null,
        safetyMode = AudioOutputMode.SAFE_VOLUME_MODE,
    )

    @Volatile
    private var currentRequest: EngineTrackRequest? = null

    @Volatile
    private var activeDecoder: PcmAudioDecoder? = null

    @Volatile
    private var activeAudioTrack: AudioTrack? = null

    @Volatile
    private var playbackThread: Thread? = null

    @Volatile
    private var lastConfiguredMixerDevice: AudioDeviceInfo? = null

    @Volatile
    private var activeOutputDevice: AudioDeviceInfo? = null

    @Volatile
    private var activeNegotiatedMixerBehavior: NegotiatedMixerBehavior =
        NegotiatedMixerBehavior.NOT_APPLICABLE_SAFE_MODE

    @Volatile
    private var activeNegotiatedMixerSummary: String = "Default System Mixer (Safe Mode)"

    @Volatile
    private var audioTrackActualSampleRateHz: Int = 0

    @Volatile
    private var audioTrackActualEncodingLabel: String = "None"

    @Volatile
    private var hasAudioFocus: Boolean = false

    @Volatile
    private var resumeAfterTransientFocusLoss: Boolean = false

    private var receiversRegistered: Boolean = false

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasAudioFocus = false
                resumeAfterTransientFocusLoss = false
                pauseInternal(clearFocus = false, notice = null)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                val wasPlaying = _state.value.isPlaying
                resumeAfterTransientFocusLoss = wasPlaying
                pauseInternal(clearFocus = false, notice = null)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (safetyController.state().mode == AudioOutputMode.BIT_PERFECT_OPT_IN) {
                    // Never digitally attenuate PCM samples in Bit-Perfect Mode; pause cleanly instead.
                    resumeAfterTransientFocusLoss = _state.value.isPlaying
                    pauseInternal(clearFocus = false, notice = null)
                } else {
                    runCatching { activeAudioTrack?.setVolume(0.25f) }
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasAudioFocus = true
                if (safetyController.state().mode == AudioOutputMode.SAFE_VOLUME_MODE) {
                    runCatching { activeAudioTrack?.setVolume(1.0f) }
                }
                if (resumeAfterTransientFocusLoss && !releasedFlag.get()) {
                    resumeAfterTransientFocusLoss = false
                    play()
                }
            }
        }
    }

    private val audioFocusRequest: AudioFocusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(playbackAudioAttributes)
            .setAcceptsDelayedFocusGain(true)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener(focusChangeListener, mainHandler)
            .build()

    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                val cmd = safetyController.onBecomingNoisy()
                handleSafetyCommand(cmd)
            }
        }
    }

    private val initialDeviceCallbackPending = AtomicBoolean(true)

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            val addedSinks = addedDevices.filter { it.isSink }
            if (addedSinks.isEmpty()) return
            val usbAdded = addedSinks.firstOrNull { isUsbOutputDevice(it) }
            val chosen = usbAdded ?: detectPreferredOutputDevice() ?: addedSinks.first()
            activeOutputDevice = chosen
            // AudioManager delivers currently connected devices immediately upon registration.
            // Sync initial device metadata in Safe Mode without firing a spurious cold-start auto-pause banner.
            if (initialDeviceCallbackPending.compareAndSet(true, false)) {
                safetyController.syncInitialOutputDevice(
                    deviceName = resolveDeviceName(chosen),
                    isUsbAudio = isUsbOutputDevice(chosen),
                    isHardwareVolumeVerified = false,
                )
                publishDiagnostics()
                return
            }
            val cmd = safetyController.onAudioDeviceConnected(
                deviceName = resolveDeviceName(chosen),
                isUsbAudio = isUsbOutputDevice(chosen),
                isHardwareVolumeVerified = false, // Conservative assumption: never assume UAC hardware volume
            )
            handleSafetyCommand(cmd)
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            val removedSinks = removedDevices.filter { it.isSink }
            if (removedSinks.isEmpty()) return
            val removedUsb = removedSinks.firstOrNull { isUsbOutputDevice(it) }
            val fallback = detectPreferredOutputDevice()
            activeOutputDevice = fallback

            if (removedUsb != null) {
                clearPreferredMixerAttributesOnApi34(removedUsb)
                val cmd = safetyController.onUsbDisconnected(
                    fallbackDeviceName = fallback?.let(::resolveDeviceName) ?: "Internal Speaker",
                )
                handleSafetyCommand(cmd)
            } else {
                val cmd = safetyController.onOutputRouteChanged(
                    deviceName = fallback?.let(::resolveDeviceName) ?: "Internal Speaker",
                    isUsbAudio = fallback?.let(::isUsbOutputDevice) == true,
                    isHardwareVolumeVerified = false,
                )
                handleSafetyCommand(cmd)
            }
        }
    }

    init {
        val initialDevice = detectPreferredOutputDevice()
        activeOutputDevice = initialDevice
        if (initialDevice != null) {
            safetyController.syncInitialOutputDevice(
                deviceName = resolveDeviceName(initialDevice),
                isUsbAudio = isUsbOutputDevice(initialDevice),
                isHardwareVolumeVerified = false,
            )
        }
        registerAudioMonitors()
        publishDiagnostics()
    }

    fun setGaplessEnabled(enabled: Boolean) {
        if (gaplessEnabled == enabled) return
        gaplessEnabled = enabled
        if (!enabled) {
            clearPreloadedNextAsync()
        }
    }

    fun setReplayGainConfig(config: ReplayGainConfig) {
        replayGainConfig = config
        recomputeReplayGain()
        publishDiagnostics()
    }

    /**
     * Supplies the upcoming queue track for background gapless decoder pre-loading (or `null` when
     * at end of queue). Automatically cancels/closes any stale pre-loaded decoder if the upcoming
     * track URI changed or was cleared.
     */
    fun setNextTrack(request: EngineTrackRequest?) {
        queuedNextRequest = request
        val staleDecoder: PcmAudioDecoder? = synchronized(preloadLock) {
            val existing = preloadedTrack
            if (existing != null && (request == null || existing.request.uri != request.uri)) {
                preloadedTrack = null
                preloadingUri = null
                existing.decoder
            } else {
                if (preloadingUri != null && (request == null || preloadingUri != request.uri)) {
                    preloadingUri = null
                }
                null
            }
        }
        if (staleDecoder != null) {
            closeDecoderOffMainThread(staleDecoder)
        }
        val currentState = _state.value
        if (request != null &&
            currentState.isPlaying &&
            GaplessTransitionPolicy.shouldStartPreload(
                gaplessEnabled = gaplessEnabled,
                hasNextTrack = true,
                isAlreadyPreloadingOrReady = isNextPreloadedOrLoading(request.uri),
                currentPositionMs = _playbackPositionMs.value,
                durationMs = currentState.durationMs,
            )
        ) {
            triggerPreloadAsync(request, loadGeneration.get())
        }
    }

    fun loadTrack(request: EngineTrackRequest) {
        if (releasedFlag.get()) return
        val generation = loadGeneration.incrementAndGet()
        abortTurntableScratch()
        clearPreloadedNextAsync()
        stopWorkerFlag.set(true)
        isPausedFlag.set(true)
        currentRequest = request
        _playbackPositionMs.value = request.startPositionMs.coerceAtLeast(0L)
        _state.update {
            it.copy(
                status = PlaybackStatus.PREPARING,
                activeTrackUri = request.uri,
                errorMessage = null,
                lastAutoPauseNotice = null,
            )
        }
        enqueueControlTask {
            if (releasedFlag.get() || loadGeneration.get() != generation) return@enqueueControlTask
            engineLock.withLock {
                loadTrackLocked(
                    request = request,
                    generation = generation,
                    preserveAutoPauseNotice = false,
                )
            }
        }
    }

    private fun loadTrackLocked(
        request: EngineTrackRequest,
        generation: Long,
        preserveAutoPauseNotice: Boolean,
    ) {
        if (releasedFlag.get() || loadGeneration.get() != generation) return
        clearPreloadedNextLocked()
        stopWorkerAndReleaseTrackLocked(clearMixer = true)
        currentRequest = request
        activeTrackReplayGainMetadata = null
        recomputeReplayGain()

        // Open and prime decoder BEFORE creating AudioTrack so codec.outputFormat is confirmed.
        val decoder = try {
            MediaCodecAudioDecoder.open(
                context = appContext,
                uri = request.uri,
                fileName = request.fileName,
                mimeHint = request.mimeType,
            )
        } catch (e: AudioDecodeException) {
            if (loadGeneration.get() != generation) return
            activeDecoder = null
            _state.update {
                it.copy(
                    status = PlaybackStatus.ERROR,
                    errorMessage = e.message ?: "Unable to decode audio file",
                )
            }
            publishDiagnostics()
            return
        }

        if (releasedFlag.get() || loadGeneration.get() != generation) {
            runCatching { decoder.close() }
            return
        }

        // OOM-safe header-only ReplayGain tag scan (< 1 ms on control thread, zero Main Thread I/O).
        activeTrackReplayGainMetadata = readReplayGainMetadata(
            uri = request.uri,
            fileName = request.fileName,
            mimeType = request.mimeType,
        )
        recomputeReplayGain()

        if (request.startPositionMs > 0L) {
            runCatching { decoder.seekToMs(request.startPositionMs) }
        }

        val audioTrack = try {
            createConfiguredAudioTrackLocked(decoder.format)
        } catch (e: Exception) {
            runCatching { decoder.close() }
            activeDecoder = null
            clearPreferredMixerAttributesOnApi34(null)
            if (loadGeneration.get() != generation) return
            _state.update {
                it.copy(
                    status = PlaybackStatus.ERROR,
                    errorMessage = "AudioTrack initialization failed: ${e.message}",
                )
            }
            publishDiagnostics()
            return
        }

        if (releasedFlag.get() || loadGeneration.get() != generation) {
            safeStopAndReleaseTrack(audioTrack)
            runCatching { decoder.close() }
            clearPreferredMixerAttributesOnApi34(null)
            return
        }

        activeDecoder = decoder
        activeAudioTrack = audioTrack
        _playbackPositionMs.value = decoder.currentPositionMs
        _state.update { current ->
            current.copy(
                status = if (request.autoPlay) PlaybackStatus.PLAYING else PlaybackStatus.PAUSED,
                activeTrackUri = request.uri,
                durationMs = decoder.format.durationMs,
                errorMessage = null,
                lastAutoPauseNotice = if (preserveAutoPauseNotice) current.lastAutoPauseNotice else null,
            )
        }
        publishDiagnostics()

        if (request.autoPlay) {
            startPlaybackWorkerLocked(
                initialSeekMs = decoder.currentPositionMs,
                expectedGeneration = generation,
            )
        } else {
            isPausedFlag.set(true)
            resumeAfterTransientFocusLoss = false
            abandonAudioFocusLocked()
        }
    }

    fun play() {
        if (releasedFlag.get()) return
        enqueueControlTask {
            if (releasedFlag.get()) return@enqueueControlTask
            engineLock.withLock {
                if (activeDecoder == null || activeAudioTrack == null || _state.value.status == PlaybackStatus.COMPLETED) {
                    val req = currentRequest ?: return@withLock
                    val restartFromZero = _state.value.status == PlaybackStatus.COMPLETED
                    val resumePos = if (restartFromZero) 0L else _playbackPositionMs.value
                    val generation = loadGeneration.incrementAndGet()
                    loadTrackLocked(
                        request = req.copy(startPositionMs = resumePos, autoPlay = true),
                        generation = generation,
                        preserveAutoPauseNotice = false,
                    )
                    return@withLock
                }
                val track = activeAudioTrack ?: return@withLock
                if (!requestAudioFocusLocked()) {
                    _state.update {
                        it.copy(
                            status = PlaybackStatus.PAUSED,
                            errorMessage = "Audio focus unavailable",
                        )
                    }
                    return@withLock
                }
                resumeAfterTransientFocusLoss = false
                isPausedFlag.set(false)
                if (safetyController.state().mode == AudioOutputMode.SAFE_VOLUME_MODE) {
                    runCatching { track.setVolume(1.0f) }
                }
                safePlayTrack(track)
                _state.update {
                    it.copy(
                        status = PlaybackStatus.PLAYING,
                        errorMessage = null,
                        lastAutoPauseNotice = null,
                    )
                }
                if (playbackThread?.isAlive != true) {
                    startPlaybackWorkerLocked(
                        initialSeekMs = _playbackPositionMs.value,
                        expectedGeneration = loadGeneration.get(),
                    )
                }
            }
        }
    }

    /**
     * Adjusts [AudioTrack] output volume strictly for Safe Mode features (e.g., 3-second Sleep
     * Timer fade-out). Never invokes software `AudioTrack.setVolume(...)` in
     * [AudioOutputMode.BIT_PERFECT_OPT_IN].
     */
    fun setSafeModeOutputVolume(volume: Float) {
        if (safetyController.state().mode != AudioOutputMode.SAFE_VOLUME_MODE) {
            return
        }
        val clamped = volume.coerceIn(0.0f, 1.0f)
        runCatching { activeAudioTrack?.setVolume(clamped) }
    }

    fun pause() {
        resumeAfterTransientFocusLoss = false
        pauseInternal(clearFocus = true, notice = null)
    }

    /**
     * Engages real-time turntable varispeed / hold / reverse scratch DSP strictly in
     * [AudioOutputMode.SAFE_VOLUME_MODE] during active playback. Strict no-op (`false`) in
     * [AudioOutputMode.BIT_PERFECT_OPT_IN] or whenever paused/idle.
     */
    fun beginTurntableScratch(): Boolean {
        val eligible = TurntableScratchPolicy.isLiveTurntableEligible(
            safetyMode = safetyController.state().mode,
            isPlaying = _state.value.isPlaying && !isPausedFlag.get(),
            hasActiveTrack = activeAudioTrack != null && !stopWorkerFlag.get() && !releasedFlag.get(),
        )
        if (!eligible) return false
        turntableTargetRateBits.set(0f.toRawBits())
        turntableFallbackSeekMs.set(-1L)
        turntableScratchRequested.set(true)
        return true
    }

    /**
     * Lock-free update of the live turntable playback rate (`[-4.0f..+4.0f]`) from the UI touch
     * or flick-inertia loop. Strict no-op when live turntable scratch is not active.
     */
    fun updateTurntableScratchRate(playbackRate: Float) {
        if (!turntableScratchRequested.get()) return
        val clamped = TurntableSpeedController.clampPlaybackRate(playbackRate)
        turntableTargetRateBits.set(clamped.toRawBits())
    }

    /**
     * Exits real-time turntable scratch mode cleanly, seamlessly resuming 1.0x playback from the
     * ring buffer (or seeking once if rewound past `oldestAvailableFrame`).
     */
    fun endTurntableScratch(fallbackTargetMs: Long? = null) {
        if (!turntableScratchRequested.get()) return
        turntableFallbackSeekMs.set(fallbackTargetMs?.coerceAtLeast(0L) ?: -1L)
        turntableScratchRequested.set(false)
    }

    private fun abortTurntableScratch() {
        turntableScratchRequested.set(false)
        turntableTargetRateBits.set(0f.toRawBits())
        turntableFallbackSeekMs.set(-1L)
    }

    /**
     * Activates or deactivates the strictly read-only PCM spectrum side-tap.
     * When `active == false`, immediately releases the ~192 KB ring buffer so `BitPerfectAudioThread`
     * performs zero tapping and UI performs zero FFT work.
     */
    fun setSpectrumTapActive(active: Boolean) {
        val changed = spectrumTapActive.getAndSet(active) != active
        if (!active && changed) {
            spectrumPcmTap = null
        }
    }

    /**
     * Reads a head-synced 1024-sample mono window aligned with `AudioTrack.playbackHeadPosition`
     * into [outMonoSamples]. Returns effective decimated sample rate in `Hz`, or `0` when paused,
     * inactive, or uninitialized. Safe to call off the audio thread (`Dispatchers.Default`).
     */
    fun sampleCurrentSpectrumWindow(outMonoSamples: FloatArray): Int {
        if (!spectrumTapActive.get() || !_state.value.isPlaying || isPausedFlag.get() || releasedFlag.get()) {
            return 0
        }
        val tap = spectrumPcmTap ?: return 0
        val track = activeAudioTrack ?: return 0
        val isTrackPlaying = runCatching {
            track.state == AudioTrack.STATE_INITIALIZED && track.playState == AudioTrack.PLAYSTATE_PLAYING
        }.getOrDefault(false)
        if (!isTrackPlaying) return 0
        val headFrame = readUnsignedPlaybackHeadFrames(track)
        return tap.sampleWindowAtTrackFrame(headFrame, outMonoSamples)
    }

    fun seekTo(positionMs: Long) {
        if (releasedFlag.get()) return
        abortTurntableScratch()
        clearPreloadedNextAsync()
        val clamped = positionMs.coerceIn(0L, _state.value.durationMs.coerceAtLeast(0L))
        _playbackPositionMs.value = clamped
        if (playbackThread?.isAlive == true) {
            pendingSeekMs.set(clamped)
        } else {
            enqueueControlTask {
                if (releasedFlag.get()) return@enqueueControlTask
                engineLock.withLock {
                    val decoder = activeDecoder ?: return@withLock
                    val actual = runCatching { decoder.seekToMs(clamped) }.getOrDefault(clamped)
                    activeAudioTrack?.let { safeFlushTrack(it) }
                    turntableRingBuffer?.reset(0L)
                    spectrumPcmTap?.reset(decoder.format.sampleRateHz, 0L)
                    _playbackPositionMs.value = actual
                }
            }
        }
    }

    fun stop() {
        if (releasedFlag.get()) return
        abortTurntableScratch()
        if (_state.value.status == PlaybackStatus.IDLE && activeAudioTrack == null && activeDecoder == null) {
            turntableRingBuffer = null
            spectrumPcmTap = null
            clearPreloadedNextAsync()
            return
        }
        loadGeneration.incrementAndGet()
        queuedNextRequest = null
        clearPreloadedNextAsync()
        resumeAfterTransientFocusLoss = false
        stopWorkerFlag.set(true)
        isPausedFlag.set(true)
        enqueueControlTask {
            engineLock.withLock {
                clearPreloadedNextLocked()
                stopWorkerAndReleaseTrackLocked(clearMixer = true)
                turntableRingBuffer = null
                spectrumPcmTap = null
                abandonAudioFocusLocked()
                activeTrackReplayGainMetadata = null
                recomputeReplayGain()
                _playbackPositionMs.value = 0L
                _state.update {
                    it.copy(
                        status = PlaybackStatus.IDLE,
                        errorMessage = null,
                    )
                }
                publishDiagnostics()
            }
        }
    }

    fun requestBitPerfectMode(): SafetyCommand {
        val cmd = safetyController.requestBitPerfectMode()
        handleSafetyCommand(cmd)
        return cmd
    }

    fun confirmBitPerfectWarning(): SafetyCommand {
        val cmd = safetyController.confirmBitPerfectWarning()
        handleSafetyCommand(cmd)
        return cmd
    }

    fun dismissSafetyWarning(): SafetyCommand {
        val cmd = safetyController.dismissSafetyWarning()
        handleSafetyCommand(cmd)
        return cmd
    }

    fun switchToSafeMode(): SafetyCommand {
        val cmd = safetyController.switchToSafeMode()
        handleSafetyCommand(cmd)
        return cmd
    }

    fun clearAutoPauseNotice() {
        _state.update { it.copy(lastAutoPauseNotice = null) }
    }

    fun release() {
        if (!releasedFlag.compareAndSet(false, true)) return
        abortTurntableScratch()
        spectrumTapActive.set(false)
        spectrumPcmTap = null
        loadGeneration.incrementAndGet()
        queuedNextRequest = null
        stopWorkerFlag.set(true)
        isPausedFlag.set(true)
        unregisterAudioMonitors()
        preloadExecutor.shutdownNow()
        runCatching {
            controlExecutor.execute {
                engineLock.withLock {
                    clearPreloadedNextLocked()
                    stopWorkerAndReleaseTrackLocked(clearMixer = true)
                    turntableRingBuffer = null
                    spectrumPcmTap = null
                    abandonAudioFocusLocked()
                }
            }
        }
        controlExecutor.shutdown()
    }

    private fun handleSafetyCommand(command: SafetyCommand) {
        recomputeReplayGain()
        when (command.type) {
            SafetyEventType.AUTO_PAUSE -> {
                abortTurntableScratch()
                resumeAfterTransientFocusLoss = false
                val notice = when (command.autoPauseReason) {
                    AutoPauseReason.BECOMING_NOISY -> "Auto-paused: audio output disconnected or becoming noisy."
                    AutoPauseReason.USB_DISCONNECTED -> "Auto-paused: USB audio device disconnected (Safe Mode restored)."
                    AutoPauseReason.USB_CONNECTED_ROUTE_CHANGE -> "Auto-paused: USB audio device connected in Safe Mode."
                    AutoPauseReason.OUTPUT_ROUTE_CHANGED -> "Auto-paused: audio output route changed (Safe Mode active)."
                    null -> "Auto-paused for hearing safety."
                }
                pauseInternal(clearFocus = true, notice = notice)
                enqueueControlTask {
                    if (releasedFlag.get()) return@enqueueControlTask
                    engineLock.withLock {
                        if (command.requiresClearPreferredMixerAttributes) {
                            clearPreferredMixerAttributesOnApi34(null)
                        }
                        reconfigureTrackIfLoadedLocked(
                            autoResume = false,
                            preserveAutoPauseNotice = true,
                        )
                    }
                }
            }
            SafetyEventType.MODE_CHANGED_TO_SAFE -> {
                val shouldResume = _state.value.isPlaying
                enqueueControlTask {
                    if (releasedFlag.get()) return@enqueueControlTask
                    engineLock.withLock {
                        if (command.requiresClearPreferredMixerAttributes) {
                            clearPreferredMixerAttributesOnApi34(null)
                        }
                        reconfigureTrackIfLoadedLocked(
                            autoResume = shouldResume,
                            preserveAutoPauseNotice = false,
                        )
                    }
                }
            }
            SafetyEventType.MODE_CHANGED_TO_BIT_PERFECT -> {
                abortTurntableScratch()
                val shouldResume = _state.value.isPlaying
                enqueueControlTask {
                    if (releasedFlag.get()) return@enqueueControlTask
                    engineLock.withLock {
                        // Mandatory Bit-Perfect invariant: release ring buffer memory immediately.
                        turntableRingBuffer = null
                        reconfigureTrackIfLoadedLocked(
                            autoResume = shouldResume,
                            preserveAutoPauseNotice = false,
                        )
                    }
                }
            }
            SafetyEventType.REQUIRES_SAFETY_WARNING, SafetyEventType.NONE -> {
                _state.update { it.copy(safetyState = safetyController.state()) }
                publishDiagnostics()
            }
        }
    }

    private fun reconfigureTrackIfLoadedLocked(
        autoResume: Boolean,
        preserveAutoPauseNotice: Boolean,
    ) {
        clearPreloadedNextLocked()
        val req = currentRequest
        if (req == null || activeDecoder == null) {
            _state.update { it.copy(safetyState = safetyController.state()) }
            publishDiagnostics()
            return
        }
        val generation = loadGeneration.incrementAndGet()
        val resumePos = _playbackPositionMs.value
        loadTrackLocked(
            request = req.copy(startPositionMs = resumePos, autoPlay = autoResume),
            generation = generation,
            preserveAutoPauseNotice = preserveAutoPauseNotice,
        )
    }

    private fun pauseInternal(clearFocus: Boolean, notice: String?) {
        abortTurntableScratch()
        val wasPaused = isPausedFlag.getAndSet(true)
        activeAudioTrack?.let { safePauseTrack(it) }
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
        if (clearFocus && hasAudioFocus) {
            enqueueControlTask {
                if (releasedFlag.get()) return@enqueueControlTask
                engineLock.withLock {
                    abandonAudioFocusLocked()
                }
            }
        }
    }

    private fun enqueueControlTask(block: () -> Unit) {
        if (releasedFlag.get()) return
        runCatching { controlExecutor.execute(block) }
    }

    private fun createConfiguredAudioTrackLocked(pcmFormat: DecodedPcmFormat): AudioTrack {
        // Always clear stale preferred mixer attributes before configuring a new track/sample rate.
        clearPreferredMixerAttributesOnApi34(null)

        val currentDevice = detectPreferredOutputDevice().also { activeOutputDevice = it }
        val usbDevice = currentDevice?.takeIf { isUsbOutputDevice(it) }
        val androidEncoding = toAndroidAudioEncoding(pcmFormat.encoding)
        val channelMask = toChannelOutMask(pcmFormat.channelCount)

        // Negotiate API 34+ MIXER_BEHAVIOR_BIT_PERFECT only when opted in on a USB output device.
        val negotiation = negotiateMixerAttributesLocked(
            pcmFormat = pcmFormat,
            androidEncoding = androidEncoding,
            channelMask = channelMask,
            usbDevice = usbDevice,
        )
        activeNegotiatedMixerBehavior = negotiation.behavior
        activeNegotiatedMixerSummary = negotiation.summary

        val bytesPerFrame = pcmFormat.bytesPerFrame.coerceAtLeast(1)
        val minBuf = AudioTrack.getMinBufferSize(pcmFormat.sampleRateHz, channelMask, androidEncoding)
            .coerceAtLeast(bytesPerFrame * 1024)
        val rawBufSize = maxOf(minBuf * 2, bytesPerFrame * (pcmFormat.sampleRateHz / 20))
        val alignedBufSize = PcmStreamRules.computeAlignedBufferSize(rawBufSize, bytesPerFrame)

        val audioFormat = AudioFormat.Builder()
            .setSampleRate(pcmFormat.sampleRateHz)
            .setChannelMask(channelMask)
            .setEncoding(androidEncoding)
            .build()

        // PERFORMANCE_MODE_NONE avoids forcing fast-mixer 48,000 Hz resampling on USB HAL paths.
        val track = AudioTrack.Builder()
            .setAudioAttributes(playbackAudioAttributes)
            .setAudioFormat(audioFormat)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(alignedBufSize)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_NONE)
            .build()

        if (usbDevice != null) {
            runCatching { track.preferredDevice = usbDevice }
        }

        val actualEncodingInt = track.audioFormat
        audioTrackActualSampleRateHz = track.sampleRate
        audioTrackActualEncodingLabel = androidEncodingName(actualEncodingInt)

        Log.d(
            TAG,
            "AudioTrack configured: decoder=${pcmFormat.decoderName}, decodedEncoding=${pcmFormat.encoding}, " +
                "audioTrackEncoding=${audioTrackActualEncodingLabel} (const=$actualEncodingInt), " +
                "sampleRate=${track.sampleRate}Hz, channels=${pcmFormat.channelCount}, " +
                "bytesPerFrame=$bytesPerFrame, bufferBytes=$alignedBufSize, mixer=${negotiation.behavior}",
        )
        return track
    }

    private data class MixerNegotiationResult(
        val behavior: NegotiatedMixerBehavior,
        val summary: String,
    )

    private fun negotiateMixerAttributesLocked(
        pcmFormat: DecodedPcmFormat,
        androidEncoding: Int,
        channelMask: Int,
        usbDevice: AudioDeviceInfo?,
    ): MixerNegotiationResult {
        val safetyMode = safetyController.state().mode
        if (safetyMode == AudioOutputMode.SAFE_VOLUME_MODE) {
            return MixerNegotiationResult(
                behavior = NegotiatedMixerBehavior.NOT_APPLICABLE_SAFE_MODE,
                summary = "Default System Mixer (Safe Mode — Volume Active)",
            )
        }
        if (usbDevice == null) {
            return MixerNegotiationResult(
                behavior = NegotiatedMixerBehavior.UNSUPPORTED_NON_USB_OUTPUT,
                summary = "Default System Mixer (No USB Audio Output Connected)",
            )
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return MixerNegotiationResult(
                behavior = NegotiatedMixerBehavior.UNSUPPORTED_API_BELOW_34,
                summary = "Clean Native AudioTrack (Android ${Build.VERSION.SDK_INT} < API 34)",
            )
        }
        return negotiateApi34BitPerfectMixer(pcmFormat, androidEncoding, channelMask, usbDevice)
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun negotiateApi34BitPerfectMixer(
        pcmFormat: DecodedPcmFormat,
        androidEncoding: Int,
        channelMask: Int,
        usbDevice: AudioDeviceInfo,
    ): MixerNegotiationResult {
        return try {
            val supported = audioManager.getSupportedMixerAttributes(usbDevice)
            val bitPerfectCandidates = supported.filter {
                it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT
            }
            if (bitPerfectCandidates.isEmpty()) {
                return MixerNegotiationResult(
                    behavior = NegotiatedMixerBehavior.DEFAULT_MIXER_FALLBACK,
                    summary = "Default System Mixer (USB HAL reported 0 bit-perfect profiles)",
                )
            }

            // Prefer exact sample rate + encoding + channel mask match.
            val exactMatch = bitPerfectCandidates.firstOrNull { candidate ->
                val fmt = candidate.format
                fmt.sampleRate == pcmFormat.sampleRateHz &&
                    fmt.encoding == androidEncoding &&
                    (fmt.channelMask == channelMask || fmt.channelCount == pcmFormat.channelCount)
            }
            if (exactMatch == null) {
                val reported = bitPerfectCandidates
                    .take(6)
                    .joinToString(", ") { candidate ->
                        val fmt = candidate.format
                        "${AudioFormatFormatter.formatSampleRateHz(fmt.sampleRate)}/${androidEncodingName(fmt.encoding)}"
                    }
                return MixerNegotiationResult(
                    behavior = NegotiatedMixerBehavior.DEFAULT_MIXER_FALLBACK,
                    summary = "Default System Mixer (No exact bit-perfect match for ${AudioFormatFormatter.formatSampleRateHz(pcmFormat.sampleRateHz)} / ${androidEncodingName(androidEncoding)}; HAL supports: $reported)",
                )
            }

            val applied = audioManager.setPreferredMixerAttributes(
                playbackAudioAttributes,
                usbDevice,
                exactMatch,
            )
            if (!applied) {
                return MixerNegotiationResult(
                    behavior = NegotiatedMixerBehavior.DEFAULT_MIXER_FALLBACK,
                    summary = "Default System Mixer (setPreferredMixerAttributes returned false)",
                )
            }

            val verified = audioManager.getPreferredMixerAttributes(playbackAudioAttributes, usbDevice)
            if (verified != null && verified.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT) {
                lastConfiguredMixerDevice = usbDevice
                MixerNegotiationResult(
                    behavior = NegotiatedMixerBehavior.BIT_PERFECT,
                    summary = "MIXER_BEHAVIOR_BIT_PERFECT (${AudioFormatFormatter.formatSampleRateHz(verified.format.sampleRate)}, ${androidEncodingName(verified.format.encoding)})",
                )
            } else {
                clearPreferredMixerAttributesOnApi34(usbDevice)
                MixerNegotiationResult(
                    behavior = NegotiatedMixerBehavior.DEFAULT_MIXER_FALLBACK,
                    summary = "Default System Mixer (HAL did not confirm bit-perfect preference)",
                )
            }
        } catch (e: Exception) {
            clearPreferredMixerAttributesOnApi34(usbDevice)
            MixerNegotiationResult(
                behavior = NegotiatedMixerBehavior.DEFAULT_MIXER_FALLBACK,
                summary = "Default System Mixer (API 34 negotiation error: ${e.javaClass.simpleName})",
            )
        }
    }

    /**
     * Mandatory Android 14+ cleanup: clears any previously set bit-perfect mixer preference so
     * system audio routing and other apps are never left stuck in fixed-volume bit-perfect mode.
     */
    private fun clearPreferredMixerAttributesOnApi34(explicitDevice: AudioDeviceInfo?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val targets = linkedSetOf<AudioDeviceInfo>()
        explicitDevice?.let { targets.add(it) }
        lastConfiguredMixerDevice?.let { targets.add(it) }
        activeOutputDevice?.takeIf { isUsbOutputDevice(it) }?.let { targets.add(it) }

        for (device in targets) {
            runCatching {
                audioManager.clearPreferredMixerAttributes(playbackAudioAttributes, device)
            }
        }
        lastConfiguredMixerDevice = null
    }

    private fun startPlaybackWorkerLocked(
        initialSeekMs: Long,
        expectedGeneration: Long,
    ) {
        val decoder = activeDecoder ?: return
        val track = activeAudioTrack ?: return
        if (!requestAudioFocusLocked()) {
            _state.update { it.copy(status = PlaybackStatus.PAUSED, errorMessage = "Audio focus denied") }
            return
        }

        stopWorkerFlag.set(false)
        isPausedFlag.set(false)
        pendingSeekMs.set(-1L)
        safePlayTrack(track)

        val worker = Thread(
            {
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
                runPlaybackLoop(
                    initialDecoder = decoder,
                    initialTrack = track,
                    initialSeekMs = initialSeekMs,
                    expectedGeneration = expectedGeneration,
                )
            },
            "BitPerfectAudioThread",
        )
        playbackThread = worker
        worker.start()
    }

    private fun runPlaybackLoop(
        initialDecoder: PcmAudioDecoder,
        initialTrack: AudioTrack,
        initialSeekMs: Long,
        expectedGeneration: Long,
    ) {
        var currentDecoder = initialDecoder
        var currentTrack = initialTrack
        var observedFormatVersion = currentDecoder.formatVersion
        var currentFormat = currentDecoder.format
        var sampleRate = currentFormat.sampleRateHz.coerceAtLeast(1)
        var durationMs = currentFormat.durationMs.coerceAtLeast(0L)
        var bytesPerFrame = currentFormat.bytesPerFrame.coerceAtLeast(1)
        var channels = currentFormat.channelCount.coerceIn(1, 8)

        var bufferSize = PcmStreamRules.computeAlignedBufferSize(16_384, bytesPerFrame)
        var pcmBuffer = ByteArray(bufferSize)
        var directBuffer = ByteBuffer.allocateDirect(bufferSize).order(ByteOrder.LITTLE_ENDIAN)
        var floatBuffer = FloatArray(bufferSize / 4)
        var remainder = ByteArray(bytesPerFrame)
        var remainderLen = 0

        fun ensureSafeModeRingBuffer(channelCount: Int): TurntablePcmRingBuffer? {
            if (safetyController.state().mode != AudioOutputMode.SAFE_VOLUME_MODE) {
                turntableRingBuffer = null
                return null
            }
            val existing = turntableRingBuffer
            if (existing != null && existing.channels == channelCount) {
                return existing
            }
            val created = TurntablePcmRingBuffer(channelCount = channelCount)
            turntableRingBuffer = created
            return created
        }

        var activeRingBuffer = ensureSafeModeRingBuffer(channels)?.also { it.reset(0L) }
        var resampler = if (activeRingBuffer != null) TurntableResampler(sampleRate, channels) else null
        var scratchChunkFrames = (sampleRate / 66).coerceIn(256, 4096)
        var teeFloats = if (activeRingBuffer != null) {
            FloatArray(maxOf(bufferSize / currentFormat.encoding.bytesPerSample.coerceAtLeast(1), 4096))
        } else {
            FloatArray(0)
        }
        var scratchOutFloats = if (activeRingBuffer != null) FloatArray(scratchChunkFrames * channels) else FloatArray(0)
        var scratchOutBytes = if (activeRingBuffer != null) ByteArray(scratchChunkFrames * bytesPerFrame) else ByteArray(0)

        fun ensureActiveSpectrumTap(rateHz: Int, trackFrameCursor: Long): SpectrumPcmTap? {
            if (!spectrumTapActive.get()) {
                if (spectrumPcmTap != null) spectrumPcmTap = null
                return null
            }
            val expectedEffRate = SpectrumAnalyzerDsp.computeEffectiveSampleRateHz(rateHz)
            val existing = spectrumPcmTap
            if (existing != null && existing.currentEffectiveSampleRateHz == expectedEffRate) {
                return existing
            }
            val tap = existing ?: SpectrumPcmTap().also { spectrumPcmTap = it }
            tap.reset(rateHz, trackFrameCursor)
            return tap
        }

        var seekBaseMs = initialSeekMs.coerceAtLeast(0L)
        var streamOriginMs = seekBaseMs
        var headBaseFrames = readUnsignedPlaybackHeadFrames(currentTrack)
        var totalFramesWrittenToTrack = headBaseFrames
        var streamFramesWrittenToTrack = 0L
        var ringDrainCursorFrame = -1L
        var exitFadeFramesRemaining = 0
        var decoderEosDuringScratch = false
        var countHeadFrames = headBaseFrames
        var pendingPlayCountMs = 0L
        var wasPausedInLoop = false
        var lastUiUpdateNano = 0L

        if (spectrumTapActive.get()) {
            val tap = spectrumPcmTap ?: SpectrumPcmTap().also { spectrumPcmTap = it }
            tap.reset(sampleRate, totalFramesWrittenToTrack)
        }

        fun accumulateActivePlayTime(forceFlush: Boolean, overrideHeadFrames: Long? = null) {
            val headFrames = overrideHeadFrames ?: readUnsignedPlaybackHeadFrames(currentTrack)
            val stepFrames = (headFrames - countHeadFrames).coerceAtLeast(0L)
            if (stepFrames > 0L) {
                val stepMs = (stepFrames * 1000L) / sampleRate.toLong()
                if (stepMs > 0L) {
                    val consumedFrames = (stepMs * sampleRate.toLong()) / 1000L
                    countHeadFrames += consumedFrames
                    pendingPlayCountMs += stepMs
                }
            }
            if ((forceFlush && pendingPlayCountMs > 0L) || pendingPlayCountMs >= 500L) {
                val toEmit = pendingPlayCountMs
                pendingPlayCountMs = 0L
                onActivePlaybackElapsedMs?.invoke(toEmit)
            }
        }

        try {
            while (!stopWorkerFlag.get() && !releasedFlag.get()) {
                val requestedSeek = pendingSeekMs.getAndSet(-1L)
                if (requestedSeek >= 0L) {
                    accumulateActivePlayTime(forceFlush = true)
                    val wasPlaying = !isPausedFlag.get()
                    safePauseTrack(currentTrack)
                    safeFlushTrack(currentTrack)
                    remainderLen = 0
                    val actualSeekMs = currentDecoder.seekToMs(requestedSeek)
                    seekBaseMs = actualSeekMs
                    streamOriginMs = actualSeekMs
                    activeRingBuffer?.reset(0L)
                    streamFramesWrittenToTrack = 0L
                    ringDrainCursorFrame = -1L
                    exitFadeFramesRemaining = 0
                    decoderEosDuringScratch = false
                    headBaseFrames = readUnsignedPlaybackHeadFrames(currentTrack)
                    totalFramesWrittenToTrack = headBaseFrames
                    countHeadFrames = headBaseFrames
                    spectrumPcmTap?.reset(sampleRate, totalFramesWrittenToTrack)
                    lastUiUpdateNano = 0L
                    _playbackPositionMs.value = actualSeekMs
                    if (wasPlaying) {
                        safePlayTrack(currentTrack)
                    }
                    val nextReq = queuedNextRequest
                    if (GaplessTransitionPolicy.shouldStartPreload(
                            gaplessEnabled = gaplessEnabled,
                            hasNextTrack = nextReq != null,
                            isAlreadyPreloadingOrReady = isNextPreloadedOrLoading(nextReq?.uri),
                            currentPositionMs = actualSeekMs,
                            durationMs = durationMs,
                        )
                    ) {
                        triggerPreloadAsync(nextReq, expectedGeneration)
                    }
                    continue
                }

                if (isPausedFlag.get()) {
                    if (!wasPausedInLoop) {
                        accumulateActivePlayTime(forceFlush = true)
                        wasPausedInLoop = true
                    }
                    try {
                        Thread.sleep(25L)
                    } catch (_: InterruptedException) {
                        break
                    }
                    continue
                }
                if (wasPausedInLoop) {
                    countHeadFrames = maxOf(readUnsignedPlaybackHeadFrames(currentTrack), headBaseFrames)
                    wasPausedInLoop = false
                }

                // Safe-Mode-only live turntable scratch loop (~35-40 ms latency, zero AudioTrack rebuild).
                if (turntableScratchRequested.get()) {
                    val ringBuf = activeRingBuffer
                    val scratchResampler = resampler
                    if (safetyController.state().mode != AudioOutputMode.SAFE_VOLUME_MODE ||
                        ringBuf == null ||
                        scratchResampler == null
                    ) {
                        abortTurntableScratch()
                    } else {
                        // 1. Flush and freeze PlayCountTracker so scratching never inflates listening time.
                        accumulateActivePlayTime(forceFlush = true)

                        // 2. Anchor scratch cursor to the exact hardware playback head currently reaching the speaker.
                        val hwHeadOnEntry = readUnsignedPlaybackHeadFrames(currentTrack)
                        val queuedAhead = (totalFramesWrittenToTrack - hwHeadOnEntry).coerceAtLeast(0L)
                        val currentStreamHead = if (ringDrainCursorFrame >= 0L) {
                            ringDrainCursorFrame
                        } else {
                            streamFramesWrittenToTrack
                        }
                        val oldestF = ringBuf.oldestAvailableFrame
                        val maxReadableF = (ringBuf.writeHeadFrame - 1L).coerceAtLeast(oldestF)
                        var scratchCursorFrame = (currentStreamHead - queuedAhead)
                            .coerceIn(oldestF, maxReadableF)
                            .toDouble()

                        // 3. Flush ~160 ms normal lookahead once so live turntable audio reacts in ~35-40 ms.
                        safePauseTrack(currentTrack)
                        safeFlushTrack(currentTrack)
                        headBaseFrames = readUnsignedPlaybackHeadFrames(currentTrack)
                        totalFramesWrittenToTrack = headBaseFrames
                        countHeadFrames = headBaseFrames
                        spectrumPcmTap?.reset(sampleRate, totalFramesWrittenToTrack)
                        safePlayTrack(currentTrack)

                        val initialRate = Float.fromBits(turntableTargetRateBits.get())
                        scratchResampler.resetForScratchEntry(initialRate)
                        val minVirtualFrame = -(streamOriginMs.toDouble() * sampleRate.toDouble() / 1000.0)
                        val maxVirtualFrame = if (durationMs > 0L) {
                            ((durationMs - streamOriginMs).toDouble() * sampleRate.toDouble() / 1000.0)
                                .coerceAtLeast(minVirtualFrame)
                        } else {
                            Double.MAX_VALUE
                        }
                        val maxAheadFrames = (sampleRate / 25).toLong().coerceAtLeast(256L)

                        while (turntableScratchRequested.get() &&
                            !stopWorkerFlag.get() &&
                            !releasedFlag.get() &&
                            !isPausedFlag.get() &&
                            pendingSeekMs.get() < 0L &&
                            safetyController.state().mode == AudioOutputMode.SAFE_VOLUME_MODE
                        ) {
                            val hwHead = readUnsignedPlaybackHeadFrames(currentTrack)
                            val ahead = (totalFramesWrittenToTrack - hwHead).coerceAtLeast(0L)
                            if (ahead > maxAheadFrames) {
                                try {
                                    Thread.sleep(4L)
                                } catch (_: InterruptedException) {
                                    break
                                }
                                continue
                            }

                            val targetRate = Float.fromBits(turntableTargetRateBits.get())

                            // Forward scratch: decode additional PCM into ring buffer up to +4.0x real-time.
                            if ((targetRate > 0f || scratchResampler.currentRate > 0f) && !decoderEosDuringScratch) {
                                val peakForwardRate = maxOf(targetRate, scratchResampler.currentRate, 0f).toDouble()
                                val neededEndFrame = ceil(
                                    scratchCursorFrame + peakForwardRate * scratchChunkFrames.toDouble() + 4.0,
                                ).toLong()
                                var reads = 0
                                while (ringBuf.writeHeadFrame <= neededEndFrame &&
                                    reads < 12 &&
                                    !decoderEosDuringScratch &&
                                    !stopWorkerFlag.get() &&
                                    !releasedFlag.get() &&
                                    turntableScratchRequested.get()
                                ) {
                                    reads++
                                    val br = currentDecoder.readPcm(pcmBuffer)
                                    if (currentDecoder.formatVersion != observedFormatVersion) {
                                        abortTurntableScratch()
                                        break
                                    }
                                    if (br < 0) {
                                        decoderEosDuringScratch = true
                                        break
                                    }
                                    if (br == 0) break

                                    val comb: ByteArray
                                    val totLen: Int
                                    if (remainderLen > 0) {
                                        totLen = remainderLen + br
                                        comb = ByteArray(totLen)
                                        System.arraycopy(remainder, 0, comb, 0, remainderLen)
                                        System.arraycopy(pcmBuffer, 0, comb, remainderLen, br)
                                        remainderLen = 0
                                    } else {
                                        comb = pcmBuffer
                                        totLen = br
                                    }
                                    val alLen = PcmStreamRules.alignByteCountToFrame(totLen, bytesPerFrame)
                                    val trLen = totLen - alLen
                                    if (trLen > 0) {
                                        System.arraycopy(comb, alLen, remainder, 0, trLen)
                                        remainderLen = trLen
                                    }
                                    if (alLen > 0) {
                                        val neededFloats = alLen / currentFormat.encoding.bytesPerSample
                                        if (teeFloats.size < neededFloats) {
                                            teeFloats = FloatArray(neededFloats)
                                        }
                                        val decodedFloats = TurntablePcmCodec.decodePcmBytesToFloats(
                                            encoding = currentFormat.encoding,
                                            srcBytes = comb,
                                            byteOffset = 0,
                                            byteCount = alLen,
                                            destFloats = teeFloats,
                                        )
                                        val decodedFrames = decodedFloats / channels
                                        if (decodedFrames > 0) {
                                            ringBuf.appendInterleavedFloats(teeFloats, decodedFrames)
                                        }
                                    }
                                }
                            }

                            val effectiveMaxFrame = if (decoderEosDuringScratch && ringBuf.writeHeadFrame > 0L) {
                                minOf(maxVirtualFrame, (ringBuf.writeHeadFrame - 1L).toDouble())
                            } else {
                                maxVirtualFrame
                            }

                            val rgGain = activeReplayGainResolution.linearGain
                            val step = scratchResampler.renderChunk(
                                ringBuffer = ringBuf,
                                targetRate = targetRate,
                                startCursorFrame = scratchCursorFrame,
                                minVirtualFrame = minVirtualFrame,
                                maxVirtualFrame = effectiveMaxFrame,
                                outFrames = scratchChunkFrames,
                                destFloats = scratchOutFloats,
                                replayGainLinear = rgGain,
                            )
                            scratchCursorFrame = step.nextCursorFrame

                            val framesWritten = writeNormalizedFloatsToTrack(
                                track = currentTrack,
                                encoding = currentFormat.encoding,
                                srcFloats = scratchOutFloats,
                                frameCount = step.framesRendered,
                                channels = channels,
                                bytesPerFrame = bytesPerFrame,
                                scratchBytes = scratchOutBytes,
                                directBuffer = directBuffer,
                            )
                            if (framesWritten > 0) {
                                val frameStart = totalFramesWrittenToTrack
                                totalFramesWrittenToTrack += framesWritten.toLong()
                                if (spectrumTapActive.get()) {
                                    ensureActiveSpectrumTap(sampleRate, frameStart)?.appendInterleavedFloatsReadOnly(
                                        srcFloats = scratchOutFloats,
                                        frameCount = framesWritten,
                                        channels = channels,
                                        trackFrameAfterWrite = totalFramesWrittenToTrack,
                                    )
                                }
                            }

                            val liveTrackMs = (streamOriginMs + (scratchCursorFrame * 1000.0) / sampleRate.toDouble())
                                .roundToLong()
                                .let { if (durationMs > 0L) it.coerceIn(0L, durationMs) else it.coerceAtLeast(0L) }
                            _playbackPositionMs.value = liveTrackMs
                        }

                        // Exit turntable scratch cleanly
                        val cleanExitToPlay = !stopWorkerFlag.get() &&
                            !releasedFlag.get() &&
                            !isPausedFlag.get() &&
                            pendingSeekMs.get() < 0L &&
                            safetyController.state().mode == AudioOutputMode.SAFE_VOLUME_MODE

                        if (cleanExitToPlay) {
                            val fadeOutFrames = scratchResampler.exitFadeDurationFrames().coerceAtMost(scratchChunkFrames)
                            if (scratchResampler.fadeGain > 0.01f && fadeOutFrames > 0) {
                                val tailStep = scratchResampler.renderChunk(
                                    ringBuffer = ringBuf,
                                    targetRate = scratchResampler.currentRate,
                                    startCursorFrame = scratchCursorFrame,
                                    minVirtualFrame = minVirtualFrame,
                                    maxVirtualFrame = maxVirtualFrame,
                                    outFrames = fadeOutFrames,
                                    destFloats = scratchOutFloats,
                                    replayGainLinear = activeReplayGainResolution.linearGain,
                                    forceFadeOut = true,
                                )
                                scratchCursorFrame = tailStep.nextCursorFrame
                                val tailWritten = writeNormalizedFloatsToTrack(
                                    track = currentTrack,
                                    encoding = currentFormat.encoding,
                                    srcFloats = scratchOutFloats,
                                    frameCount = tailStep.framesRendered,
                                    channels = channels,
                                    bytesPerFrame = bytesPerFrame,
                                    scratchBytes = scratchOutBytes,
                                    directBuffer = directBuffer,
                                )
                                if (tailWritten > 0) {
                                    val frameStart = totalFramesWrittenToTrack
                                    totalFramesWrittenToTrack += tailWritten.toLong()
                                    if (spectrumTapActive.get()) {
                                        ensureActiveSpectrumTap(sampleRate, frameStart)?.appendInterleavedFloatsReadOnly(
                                            srcFloats = scratchOutFloats,
                                            frameCount = tailWritten,
                                            channels = channels,
                                            trackFrameAfterWrite = totalFramesWrittenToTrack,
                                        )
                                    }
                                }
                            }

                            val fallbackMs = turntableFallbackSeekMs.getAndSet(-1L)
                            val targetMs = if (fallbackMs >= 0L) {
                                fallbackMs.let { if (durationMs > 0L) it.coerceIn(0L, durationMs) else it }
                            } else {
                                (streamOriginMs + (scratchCursorFrame * 1000.0) / sampleRate.toDouble())
                                    .roundToLong()
                                    .let { if (durationMs > 0L) it.coerceIn(0L, durationMs) else it.coerceAtLeast(0L) }
                            }

                            val cursorFrameLong = scratchCursorFrame.roundToLong()
                            val cursorInsideRing = ringBuf.bufferedFrameCount > 0 &&
                                cursorFrameLong >= ringBuf.oldestAvailableFrame &&
                                cursorFrameLong <= ringBuf.writeHeadFrame &&
                                fallbackMs < 0L

                            if (cursorInsideRing) {
                                // Fast Seamless Path: zero MediaCodec.flush()! Drain ring buffer at 1.0x back to writeHead.
                                ringDrainCursorFrame = cursorFrameLong
                                exitFadeFramesRemaining = scratchResampler.exitFadeDurationFrames()
                                seekBaseMs = targetMs
                                headBaseFrames = totalFramesWrittenToTrack
                                countHeadFrames = maxOf(readUnsignedPlaybackHeadFrames(currentTrack), headBaseFrames)
                                lastUiUpdateNano = 0L
                                _playbackPositionMs.value = targetMs
                            } else {
                                // Deep Rewind Path: rewound past oldestAvailableFrame -> single clean seekToMs.
                                safePauseTrack(currentTrack)
                                safeFlushTrack(currentTrack)
                                remainderLen = 0
                                ringBuf.reset(0L)
                                ringDrainCursorFrame = -1L
                                exitFadeFramesRemaining = 0
                                decoderEosDuringScratch = false
                                val actualSeekMs = currentDecoder.seekToMs(targetMs)
                                streamOriginMs = actualSeekMs
                                seekBaseMs = actualSeekMs
                                streamFramesWrittenToTrack = 0L
                                headBaseFrames = readUnsignedPlaybackHeadFrames(currentTrack)
                                totalFramesWrittenToTrack = headBaseFrames
                                countHeadFrames = headBaseFrames
                                spectrumPcmTap?.reset(sampleRate, totalFramesWrittenToTrack)
                                lastUiUpdateNano = 0L
                                _playbackPositionMs.value = actualSeekMs
                                safePlayTrack(currentTrack)
                            }
                        } else {
                            turntableFallbackSeekMs.set(-1L)
                            val finalMs = (streamOriginMs + (scratchCursorFrame * 1000.0) / sampleRate.toDouble())
                                .roundToLong()
                                .let { if (durationMs > 0L) it.coerceIn(0L, durationMs) else it.coerceAtLeast(0L) }
                            _playbackPositionMs.value = finalMs
                            countHeadFrames = maxOf(readUnsignedPlaybackHeadFrames(currentTrack), headBaseFrames)
                            if (isPausedFlag.get() && !stopWorkerFlag.get() && !releasedFlag.get() && pendingSeekMs.get() < 0L) {
                                val cursorLong = scratchCursorFrame.roundToLong()
                                if (ringBuf.bufferedFrameCount > 0 &&
                                    cursorLong >= ringBuf.oldestAvailableFrame &&
                                    cursorLong <= ringBuf.writeHeadFrame
                                ) {
                                    ringDrainCursorFrame = cursorLong
                                    seekBaseMs = finalMs
                                    headBaseFrames = totalFramesWrittenToTrack
                                    countHeadFrames = maxOf(readUnsignedPlaybackHeadFrames(currentTrack), headBaseFrames)
                                } else {
                                    pendingSeekMs.set(finalMs)
                                }
                            }
                        }
                        continue
                    }
                }

                // Fast seamless post-scratch drain: play remaining buffered 1.0x frames before resuming decoder reads.
                if (ringDrainCursorFrame >= 0L && activeRingBuffer != null) {
                    val ringBuf = activeRingBuffer
                    if (ringDrainCursorFrame < ringBuf.writeHeadFrame) {
                        val copiedFrames = ringBuf.copyFramesAt1x(
                            startFrame = ringDrainCursorFrame,
                            maxFrames = scratchChunkFrames,
                            destFloats = scratchOutFloats,
                        )
                        if (copiedFrames > 0) {
                            val floatCount = copiedFrames * channels
                            val effectiveGain =
                                if (safetyController.state().mode == AudioOutputMode.BIT_PERFECT_OPT_IN) {
                                    1.0f
                                } else {
                                    activeReplayGainResolution.linearGain
                                }
                            if (ReplayGainProcessor.needsSampleScaling(effectiveGain)) {
                                ReplayGainProcessor.applyGainToFloatSamples(scratchOutFloats, floatCount, effectiveGain)
                            }
                            if (exitFadeFramesRemaining > 0 && resampler != null) {
                                exitFadeFramesRemaining = resampler.applyExitFadeIn(
                                    destFloats = scratchOutFloats,
                                    frameCount = copiedFrames,
                                    remainingFadeFrames = exitFadeFramesRemaining,
                                )
                            }
                            val framesWritten = writeNormalizedFloatsToTrack(
                                track = currentTrack,
                                encoding = currentFormat.encoding,
                                srcFloats = scratchOutFloats,
                                frameCount = copiedFrames,
                                channels = channels,
                                bytesPerFrame = bytesPerFrame,
                                scratchBytes = scratchOutBytes,
                                directBuffer = directBuffer,
                            )
                            if (framesWritten > 0) {
                                val frameStart = totalFramesWrittenToTrack
                                totalFramesWrittenToTrack += framesWritten.toLong()
                                ringDrainCursorFrame += framesWritten.toLong()
                                streamFramesWrittenToTrack = ringDrainCursorFrame
                                if (spectrumTapActive.get()) {
                                    ensureActiveSpectrumTap(sampleRate, frameStart)?.appendInterleavedFloatsReadOnly(
                                        srcFloats = scratchOutFloats,
                                        frameCount = framesWritten,
                                        channels = channels,
                                        trackFrameAfterWrite = totalFramesWrittenToTrack,
                                    )
                                }
                            }
                            accumulateActivePlayTime(forceFlush = false)
                            val nowNano = System.nanoTime()
                            if (nowNano - lastUiUpdateNano >= 50_000_000L) {
                                lastUiUpdateNano = nowNano
                                val headFrames = readUnsignedPlaybackHeadFrames(currentTrack)
                                val deltaFrames = (headFrames - headBaseFrames).coerceAtLeast(0L)
                                val computedPos = seekBaseMs + (deltaFrames * 1000L) / sampleRate.toLong()
                                val clampedPos = if (durationMs > 0L) computedPos.coerceIn(0L, durationMs) else computedPos
                                _playbackPositionMs.value = clampedPos

                                val nextReq = queuedNextRequest
                                if (GaplessTransitionPolicy.shouldStartPreload(
                                        gaplessEnabled = gaplessEnabled,
                                        hasNextTrack = nextReq != null,
                                        isAlreadyPreloadingOrReady = isNextPreloadedOrLoading(nextReq?.uri),
                                        currentPositionMs = clampedPos,
                                        durationMs = durationMs,
                                    )
                                ) {
                                    triggerPreloadAsync(nextReq, expectedGeneration)
                                }
                            }
                            continue
                        }
                    }
                    streamFramesWrittenToTrack = ringBuf.writeHeadFrame
                    ringDrainCursorFrame = -1L
                }

                val bytesRead = if (decoderEosDuringScratch) -1 else currentDecoder.readPcm(pcmBuffer)
                if (bytesRead < 0) {
                    accumulateActivePlayTime(forceFlush = true)
                    val preloaded = takePreloadedTrackIfReady(expectedGeneration)
                    val hasNext = queuedNextRequest != null
                    val transitionAction = GaplessTransitionPolicy.decide(
                        gaplessEnabled = gaplessEnabled,
                        hasNextTrack = hasNext,
                        currentFormat = currentFormat.toStreamSignature(),
                        nextFormat = preloaded?.decoder?.format?.toStreamSignature(),
                    )

                    if (transitionAction == GaplessTransitionAction.SEAMLESS_REUSE && preloaded != null) {
                        val transitioned = engineLock.withLock {
                            if (stopWorkerFlag.get() || releasedFlag.get() || loadGeneration.get() != expectedGeneration) {
                                false
                            } else {
                                val oldDecoder = currentDecoder
                                currentDecoder = preloaded.decoder
                                activeDecoder = preloaded.decoder
                                currentRequest = preloaded.request
                                // Clear consumed next-track pointer until PlaybackController supplies the subsequent track.
                                if (queuedNextRequest?.uri == preloaded.request.uri) {
                                    queuedNextRequest = null
                                }
                                activeTrackReplayGainMetadata = preloaded.replayGainMetadata
                                recomputeReplayGain()
                                runCatching { oldDecoder.close() }
                                true
                            }
                        }

                        if (!transitioned) {
                            runCatching { preloaded.decoder.close() }
                            break
                        }

                        // Keep the exact same AudioTrack running continuously (0 ms gap, zero flush/rebuild).
                        observedFormatVersion = currentDecoder.formatVersion
                        currentFormat = currentDecoder.format
                        sampleRate = currentFormat.sampleRateHz.coerceAtLeast(1)
                        durationMs = currentFormat.durationMs.coerceAtLeast(0L)
                        bytesPerFrame = currentFormat.bytesPerFrame.coerceAtLeast(1)
                        channels = currentFormat.channelCount.coerceIn(1, 8)
                        activeRingBuffer = ensureSafeModeRingBuffer(channels)?.also { it.reset(0L) }
                        resampler = if (activeRingBuffer != null) TurntableResampler(sampleRate, channels) else null
                        remainderLen = 0
                        seekBaseMs = 0L
                        streamOriginMs = 0L
                        streamFramesWrittenToTrack = 0L
                        ringDrainCursorFrame = -1L
                        exitFadeFramesRemaining = 0
                        decoderEosDuringScratch = false
                        // Track B begins playing at the exact hardware frame count already queued for Track A.
                        headBaseFrames = totalFramesWrittenToTrack
                        countHeadFrames = maxOf(readUnsignedPlaybackHeadFrames(currentTrack), headBaseFrames)
                        lastUiUpdateNano = 0L
                        _playbackPositionMs.value = 0L
                        _state.update {
                            it.copy(
                                status = PlaybackStatus.PLAYING,
                                activeTrackUri = preloaded.request.uri,
                                durationMs = currentFormat.durationMs,
                                errorMessage = null,
                            )
                        }
                        publishDiagnostics()
                        Log.d(
                            TAG,
                            "Gapless SEAMLESS_REUSE -> ${preloaded.request.fileName} " +
                                "(${currentFormat.sampleRateHz}Hz, ${currentFormat.encoding}, ${currentFormat.channelCount}ch)",
                        )
                        mainHandler.post { onGaplessTransition?.invoke(preloaded.request) }
                        continue
                    }

                    // Format mismatch, gapless disabled, preload unavailable, or end of queue:
                    // close any unused preloaded decoder and cleanly drain AudioTrack's tail buffer
                    // so the final ~100-300 ms of audio is NEVER truncated.
                    preloaded?.let { runCatching { it.decoder.close() } }
                    val drainedHeadFrames = drainAudioTrackCleanly(
                        track = currentTrack,
                        totalFramesWritten = totalFramesWrittenToTrack,
                        sampleRateHz = sampleRate,
                    )
                    accumulateActivePlayTime(forceFlush = true, overrideHeadFrames = drainedHeadFrames)
                    // If user sought back into the track while the tail buffer was draining, honor the seek.
                    if (!stopWorkerFlag.get() && !releasedFlag.get() && pendingSeekMs.get() >= 0L) {
                        continue
                    }
                    if (!stopWorkerFlag.get() && !releasedFlag.get()) {
                        val finalPos = durationMs.takeIf { it > 0L } ?: _playbackPositionMs.value
                        _playbackPositionMs.value = finalPos
                        _state.update {
                            it.copy(status = PlaybackStatus.COMPLETED)
                        }
                        mainHandler.post { onTrackCompleted?.invoke() }
                    }
                    break
                }

                // Reconfigure AudioTrack dynamically if MediaCodec emitted INFO_OUTPUT_FORMAT_CHANGED mid-stream.
                val latestVersion = currentDecoder.formatVersion
                if (latestVersion != observedFormatVersion) {
                    observedFormatVersion = latestVersion
                    val newFormat = currentDecoder.format
                    if (newFormat.encoding != currentFormat.encoding ||
                        newFormat.sampleRateHz != currentFormat.sampleRateHz ||
                        newFormat.channelCount != currentFormat.channelCount
                    ) {
                        accumulateActivePlayTime(forceFlush = true)
                        val rebuiltTrack = engineLock.withLock {
                            if (stopWorkerFlag.get() || releasedFlag.get()) {
                                null
                            } else {
                                safeStopAndReleaseTrack(currentTrack)
                                createConfiguredAudioTrackLocked(newFormat).also { created ->
                                    activeAudioTrack = created
                                    if (!isPausedFlag.get()) {
                                        safePlayTrack(created)
                                    }
                                    publishDiagnostics()
                                }
                            }
                        } ?: break
                        currentTrack = rebuiltTrack
                        currentFormat = newFormat
                        sampleRate = currentFormat.sampleRateHz.coerceAtLeast(1)
                        durationMs = currentFormat.durationMs.coerceAtLeast(0L)
                        bytesPerFrame = currentFormat.bytesPerFrame.coerceAtLeast(1)
                        channels = currentFormat.channelCount.coerceIn(1, 8)
                        bufferSize = PcmStreamRules.computeAlignedBufferSize(16_384, bytesPerFrame)
                        pcmBuffer = ByteArray(bufferSize)
                        directBuffer = ByteBuffer.allocateDirect(bufferSize).order(ByteOrder.LITTLE_ENDIAN)
                        floatBuffer = FloatArray(bufferSize / 4)
                        remainder = ByteArray(bytesPerFrame)
                        remainderLen = 0
                        activeRingBuffer = ensureSafeModeRingBuffer(channels)?.also { it.reset(0L) }
                        resampler = if (activeRingBuffer != null) TurntableResampler(sampleRate, channels) else null
                        scratchChunkFrames = (sampleRate / 66).coerceIn(256, 4096)
                        teeFloats = if (activeRingBuffer != null) {
                            FloatArray(maxOf(bufferSize / currentFormat.encoding.bytesPerSample.coerceAtLeast(1), 4096))
                        } else {
                            FloatArray(0)
                        }
                        scratchOutFloats = if (activeRingBuffer != null) FloatArray(scratchChunkFrames * channels) else FloatArray(0)
                        scratchOutBytes = if (activeRingBuffer != null) ByteArray(scratchChunkFrames * bytesPerFrame) else ByteArray(0)
                        seekBaseMs = _playbackPositionMs.value
                        streamOriginMs = seekBaseMs
                        streamFramesWrittenToTrack = 0L
                        ringDrainCursorFrame = -1L
                        exitFadeFramesRemaining = 0
                        decoderEosDuringScratch = false
                        headBaseFrames = readUnsignedPlaybackHeadFrames(currentTrack)
                        totalFramesWrittenToTrack = headBaseFrames
                        countHeadFrames = headBaseFrames
                        spectrumPcmTap?.reset(sampleRate, totalFramesWrittenToTrack)
                        // Skip writing bytes from the prior buffer/format into the newly allocated pcmBuffer.
                        continue
                    }
                }

                if (bytesRead == 0) {
                    try {
                        Thread.sleep(5L)
                    } catch (_: InterruptedException) {
                        break
                    }
                    continue
                }

                // Combine any partial-frame remainder with incoming bytes and write ONLY whole PCM frames.
                val combinedBytes: ByteArray
                val totalLen: Int
                if (remainderLen > 0) {
                    totalLen = remainderLen + bytesRead
                    combinedBytes = ByteArray(totalLen)
                    System.arraycopy(remainder, 0, combinedBytes, 0, remainderLen)
                    System.arraycopy(pcmBuffer, 0, combinedBytes, remainderLen, bytesRead)
                    remainderLen = 0
                } else {
                    combinedBytes = pcmBuffer
                    totalLen = bytesRead
                }

                val alignedLen = PcmStreamRules.alignByteCountToFrame(totalLen, bytesPerFrame)
                val trailingLen = totalLen - alignedLen
                if (trailingLen > 0) {
                    System.arraycopy(combinedBytes, alignedLen, remainder, 0, trailingLen)
                    remainderLen = trailingLen
                }

                if (alignedLen > 0) {
                    // Safe-Mode-only pre-ReplayGain ring-buffer tee (zero allocs; NEVER runs in Bit-Perfect mode).
                    if (safetyController.state().mode == AudioOutputMode.SAFE_VOLUME_MODE && activeRingBuffer != null) {
                        val neededFloats = alignedLen / currentFormat.encoding.bytesPerSample
                        if (teeFloats.size < neededFloats) {
                            teeFloats = FloatArray(neededFloats)
                        }
                        val decodedFloats = TurntablePcmCodec.decodePcmBytesToFloats(
                            encoding = currentFormat.encoding,
                            srcBytes = combinedBytes,
                            byteOffset = 0,
                            byteCount = alignedLen,
                            destFloats = teeFloats,
                        )
                        val decodedFrames = decodedFloats / channels
                        if (decodedFrames > 0) {
                            activeRingBuffer.appendInterleavedFloats(teeFloats, decodedFrames)
                        }
                    }

                    // Mandatory Bit-Perfect Invariant: strictly bypass ReplayGain (gain = 1.0f) in BIT_PERFECT_OPT_IN.
                    val effectiveLinearGain =
                        if (safetyController.state().mode == AudioOutputMode.BIT_PERFECT_OPT_IN) {
                            1.0f
                        } else {
                            activeReplayGainResolution.linearGain
                        }
                    val bytesWritten = writeAlignedPcmFully(
                        track = currentTrack,
                        encoding = currentFormat.encoding,
                        bytes = combinedBytes,
                        alignedByteCount = alignedLen,
                        directBuffer = directBuffer,
                        floatBuffer = floatBuffer,
                        linearGain = effectiveLinearGain,
                    )
                    if (bytesWritten > 0) {
                        val frameStart = totalFramesWrittenToTrack
                        val writtenFrames = (bytesWritten / bytesPerFrame).toLong()
                        totalFramesWrittenToTrack += writtenFrames
                        streamFramesWrittenToTrack += writtenFrames
                        // Strictly read-only, lifecycle-gated spectrum side-tap AFTER AudioTrack write
                        // (100% Bit-Perfect safe; zero overhead when off-screen, backgrounded, or disabled).
                        if (spectrumTapActive.get()) {
                            ensureActiveSpectrumTap(sampleRate, frameStart)?.appendPcmBytesReadOnly(
                                encoding = currentFormat.encoding,
                                srcBytes = combinedBytes,
                                byteOffset = 0,
                                byteCount = bytesWritten,
                                channels = channels,
                                trackFrameAfterWrite = totalFramesWrittenToTrack,
                            )
                        }
                    }
                    accumulateActivePlayTime(forceFlush = false)
                }

                // Publish hardware-clocked playback position (~20 Hz) to dedicated flow only (zero full-UI recompositions).
                val nowNano = System.nanoTime()
                if (nowNano - lastUiUpdateNano >= 50_000_000L) {
                    lastUiUpdateNano = nowNano
                    val headFrames = readUnsignedPlaybackHeadFrames(currentTrack)
                    val deltaFrames = (headFrames - headBaseFrames).coerceAtLeast(0L)
                    val computedPos = seekBaseMs + (deltaFrames * 1000L) / sampleRate.toLong()
                    val clampedPos = if (durationMs > 0L) computedPos.coerceIn(0L, durationMs) else computedPos
                    _playbackPositionMs.value = clampedPos

                    // Trigger background gapless pre-loading when entering the final ~8 seconds.
                    val nextReq = queuedNextRequest
                    if (GaplessTransitionPolicy.shouldStartPreload(
                            gaplessEnabled = gaplessEnabled,
                            hasNextTrack = nextReq != null,
                            isAlreadyPreloadingOrReady = isNextPreloadedOrLoading(nextReq?.uri),
                            currentPositionMs = clampedPos,
                            durationMs = durationMs,
                        )
                    ) {
                        triggerPreloadAsync(nextReq, expectedGeneration)
                    }
                }
            }
        } catch (e: AudioDecodeException) {
            if (!stopWorkerFlag.get()) {
                _state.update {
                    it.copy(
                        status = PlaybackStatus.ERROR,
                        errorMessage = e.message ?: "Playback decoding error",
                    )
                }
            }
        } catch (e: Exception) {
            if (!stopWorkerFlag.get()) {
                _state.update {
                    it.copy(
                        status = PlaybackStatus.ERROR,
                        errorMessage = "Audio playback error: ${e.message}",
                    )
                }
            }
        } finally {
            abortTurntableScratch()
            runCatching { accumulateActivePlayTime(forceFlush = true) }
        }
    }

    /**
     * Cleanly drains any remaining PCM frames buffered inside [track] upon natural EOS so the last
     * ~100–300 ms of audio is never truncated by an abrupt `pause() + flush()`.
     * Returns the highest observed playback head frame count (preserving tail play-count accounting
     * even if the OEM HAL resets `playbackHeadPosition` to `0` upon entering `PLAYSTATE_STOPPED`).
     */
    private fun drainAudioTrackCleanly(
        track: AudioTrack,
        totalFramesWritten: Long,
        sampleRateHz: Int,
    ): Long {
        val headBefore = readUnsignedPlaybackHeadFrames(track)
        if (track.state != AudioTrack.STATE_INITIALIZED || track.playState != AudioTrack.PLAYSTATE_PLAYING) {
            return headBefore
        }
        val remainingFrames = (totalFramesWritten - headBefore).coerceAtLeast(0L)
        val timeoutMs = GaplessTransitionPolicy.computeDrainTimeoutMs(remainingFrames, sampleRateHz)

        // Calling stop() on a MODE_STREAM AudioTrack plays out all queued frames cleanly before halting.
        runCatching { track.stop() }

        val deadlineNano = System.nanoTime() + timeoutMs * 1_000_000L
        var lastHead = headBefore
        var maxReachedHead = headBefore
        var stagnantTicks = 0
        while (!stopWorkerFlag.get() &&
            !releasedFlag.get() &&
            !isPausedFlag.get() &&
            pendingSeekMs.get() < 0L &&
            System.nanoTime() < deadlineNano
        ) {
            val head = readUnsignedPlaybackHeadFrames(track)
            if (head < headBefore) {
                // HAL finished draining and reset playbackHeadPosition to 0 upon PLAYSTATE_STOPPED.
                maxReachedHead = maxOf(maxReachedHead, totalFramesWritten)
                break
            }
            if (head > maxReachedHead) {
                maxReachedHead = head
            }
            if (head >= totalFramesWritten) break
            if (head == lastHead) {
                stagnantTicks++
                if (stagnantTicks >= 8) break // ~120 ms stationary head -> HAL drain complete
            } else {
                lastHead = head
                stagnantTicks = 0
            }
            try {
                Thread.sleep(15L)
            } catch (_: InterruptedException) {
                break
            }
        }
        return maxReachedHead
    }

    /**
     * Writes every byte of an aligned PCM chunk to [AudioTrack], looping cleanly on partial writes,
     * applying optional Safe-Mode-only ReplayGain with zero per-buffer GC allocations when
     * [linearGain] != `1.0f`. Returns total bytes written.
     */
    private fun writeAlignedPcmFully(
        track: AudioTrack,
        encoding: PcmEncodingType,
        bytes: ByteArray,
        alignedByteCount: Int,
        directBuffer: ByteBuffer,
        floatBuffer: FloatArray,
        linearGain: Float,
    ): Int {
        val applyGain = ReplayGainProcessor.needsSampleScaling(linearGain)

        if (encoding == PcmEncodingType.PCM_FLOAT) {
            var byteOffset = 0
            var totalBytesWritten = 0
            while (byteOffset < alignedByteCount && !stopWorkerFlag.get() && !releasedFlag.get()) {
                val chunkBytes = minOf(alignedByteCount - byteOffset, floatBuffer.size * 4)
                val totalFloats = PcmStreamRules.convertLittleEndianBytesToFloats(
                    sourceBytes = bytes,
                    sourceOffset = byteOffset,
                    byteCount = chunkBytes,
                    destFloats = floatBuffer,
                )
                if (applyGain) {
                    ReplayGainProcessor.applyGainToFloatSamples(floatBuffer, totalFloats, linearGain)
                }
                var floatOffset = 0
                while (floatOffset < totalFloats && !stopWorkerFlag.get() && !releasedFlag.get()) {
                    val written = track.write(
                        floatBuffer,
                        floatOffset,
                        totalFloats - floatOffset,
                        AudioTrack.WRITE_BLOCKING,
                    )
                    if (written < 0) {
                        throw AudioDecodeException.IoError("AudioTrack float write failed with code $written")
                    }
                    if (written == 0) {
                        if (isPausedFlag.get()) return totalBytesWritten
                        Thread.sleep(2L)
                    } else {
                        floatOffset += written
                        totalBytesWritten += written * 4
                    }
                }
                byteOffset += totalFloats * 4
            }
            return totalBytesWritten
        }

        if (applyGain) {
            when (encoding) {
                PcmEncodingType.PCM_16BIT ->
                    ReplayGainProcessor.applyGainToPcm16LittleEndian(bytes, 0, alignedByteCount, linearGain)
                PcmEncodingType.PCM_24BIT_PACKED ->
                    ReplayGainProcessor.applyGainToPcm24PackedLittleEndian(bytes, 0, alignedByteCount, linearGain)
                PcmEncodingType.PCM_32BIT ->
                    ReplayGainProcessor.applyGainToPcm32LittleEndian(bytes, 0, alignedByteCount, linearGain)
                PcmEncodingType.PCM_FLOAT -> Unit
            }
        }

        var offset = 0
        var totalBytesWritten = 0
        while (offset < alignedByteCount && !stopWorkerFlag.get() && !releasedFlag.get()) {
            val chunkLen = minOf(alignedByteCount - offset, directBuffer.capacity())
            directBuffer.clear()
            directBuffer.put(bytes, offset, chunkLen)
            directBuffer.flip()

            while (directBuffer.hasRemaining() && !stopWorkerFlag.get() && !releasedFlag.get()) {
                val written = track.write(
                    directBuffer,
                    directBuffer.remaining(),
                    AudioTrack.WRITE_BLOCKING,
                )
                if (written < 0) {
                    throw AudioDecodeException.IoError("AudioTrack ByteBuffer write failed with code $written")
                }
                if (written == 0) {
                    if (isPausedFlag.get()) return totalBytesWritten
                    Thread.sleep(2L)
                } else {
                    totalBytesWritten += written
                }
            }
            offset += chunkLen
        }
        return totalBytesWritten
    }

    /**
     * Encodes and writes [frameCount] normalized interleaved float PCM frames (`-1.0f..1.0f`) to
     * [track] using its active [encoding] with zero per-chunk heap allocations. Used strictly by
     * Safe-Mode live turntable scratch rendering and seamless `1.0x` ring-buffer exit draining.
     * Returns total PCM frames written.
     */
    private fun writeNormalizedFloatsToTrack(
        track: AudioTrack,
        encoding: PcmEncodingType,
        srcFloats: FloatArray,
        frameCount: Int,
        channels: Int,
        bytesPerFrame: Int,
        scratchBytes: ByteArray,
        directBuffer: ByteBuffer,
    ): Int {
        if (frameCount <= 0 || channels <= 0) return 0
        val totalFloats = frameCount * channels

        if (encoding == PcmEncodingType.PCM_FLOAT || track.audioFormat == AudioFormat.ENCODING_PCM_FLOAT) {
            var floatOffset = 0
            while (floatOffset < totalFloats && !stopWorkerFlag.get() && !releasedFlag.get()) {
                val written = track.write(
                    srcFloats,
                    floatOffset,
                    totalFloats - floatOffset,
                    AudioTrack.WRITE_BLOCKING,
                )
                if (written < 0) {
                    throw AudioDecodeException.IoError("AudioTrack float scratch write failed with code $written")
                }
                if (written == 0) {
                    if (isPausedFlag.get()) return floatOffset / channels
                    Thread.sleep(2L)
                } else {
                    floatOffset += written
                }
            }
            return floatOffset / channels
        }

        val totalNeededBytes = frameCount * bytesPerFrame
        val targetBytes = if (scratchBytes.size >= totalNeededBytes) {
            scratchBytes
        } else {
            ByteArray(totalNeededBytes)
        }
        val encodedBytes = TurntablePcmCodec.encodeFloatsToPcmBytes(
            encoding = encoding,
            srcFloats = srcFloats,
            floatCount = totalFloats,
            destBytes = targetBytes,
        )
        var offset = 0
        var totalBytesWritten = 0
        while (offset < encodedBytes && !stopWorkerFlag.get() && !releasedFlag.get()) {
            val chunkLen = minOf(encodedBytes - offset, directBuffer.capacity())
            directBuffer.clear()
            directBuffer.put(targetBytes, offset, chunkLen)
            directBuffer.flip()

            while (directBuffer.hasRemaining() && !stopWorkerFlag.get() && !releasedFlag.get()) {
                val written = track.write(
                    directBuffer,
                    directBuffer.remaining(),
                    AudioTrack.WRITE_BLOCKING,
                )
                if (written < 0) {
                    throw AudioDecodeException.IoError("AudioTrack scratch write failed with code $written")
                }
                if (written == 0) {
                    if (isPausedFlag.get()) return totalBytesWritten / bytesPerFrame
                    Thread.sleep(2L)
                } else {
                    totalBytesWritten += written
                }
            }
            offset += chunkLen
        }
        return totalBytesWritten / bytesPerFrame
    }

    private fun isNextPreloadedOrLoading(uri: Uri?): Boolean {
        if (uri == null) return true
        return synchronized(preloadLock) {
            preloadedTrack?.request?.uri == uri || preloadingUri == uri
        }
    }

    private fun triggerPreloadAsync(
        request: EngineTrackRequest?,
        expectedGeneration: Long,
    ) {
        if (request == null || releasedFlag.get() || !gaplessEnabled) return
        val shouldLaunch = synchronized(preloadLock) {
            if (preloadedTrack?.request?.uri == request.uri || preloadingUri == request.uri) {
                false
            } else {
                preloadingUri = request.uri
                true
            }
        }
        if (!shouldLaunch) return

        runCatching {
            preloadExecutor.execute {
                if (releasedFlag.get() ||
                    !gaplessEnabled ||
                    loadGeneration.get() != expectedGeneration ||
                    queuedNextRequest?.uri != request.uri
                ) {
                    synchronized(preloadLock) {
                        if (preloadingUri == request.uri) preloadingUri = null
                    }
                    return@execute
                }

                val rgMeta = readReplayGainMetadata(
                    uri = request.uri,
                    fileName = request.fileName,
                    mimeType = request.mimeType,
                )
                val openedDecoder = try {
                    MediaCodecAudioDecoder.open(
                        context = appContext,
                        uri = request.uri,
                        fileName = request.fileName,
                        mimeHint = request.mimeType,
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Gapless preload skipped for ${request.fileName}: ${e.message}")
                    synchronized(preloadLock) {
                        if (preloadingUri == request.uri) preloadingUri = null
                    }
                    return@execute
                }

                var decoderToClose: PcmAudioDecoder? = null
                synchronized(preloadLock) {
                    val stillValid = !releasedFlag.get() &&
                        gaplessEnabled &&
                        loadGeneration.get() == expectedGeneration &&
                        queuedNextRequest?.uri == request.uri
                    if (stillValid) {
                        decoderToClose = preloadedTrack?.decoder
                        preloadedTrack = PreloadedTrack(
                            request = request,
                            decoder = openedDecoder,
                            replayGainMetadata = rgMeta,
                            generation = expectedGeneration,
                        )
                        if (preloadingUri == request.uri) preloadingUri = null
                    } else {
                        if (preloadingUri == request.uri) preloadingUri = null
                        decoderToClose = openedDecoder
                    }
                }
                decoderToClose?.let { runCatching { it.close() } }
            }
        }.onFailure {
            synchronized(preloadLock) {
                if (preloadingUri == request.uri) preloadingUri = null
            }
        }
    }

    private fun takePreloadedTrackIfReady(expectedGeneration: Long): PreloadedTrack? {
        var staleToClose: PcmAudioDecoder? = null
        val ready = synchronized(preloadLock) {
            val existing = preloadedTrack ?: return@synchronized null
            preloadedTrack = null
            preloadingUri = null
            val matches = existing.generation == expectedGeneration &&
                queuedNextRequest?.uri == existing.request.uri
            if (matches) {
                existing
            } else {
                staleToClose = existing.decoder
                null
            }
        }
        staleToClose?.let { runCatching { it.close() } }
        return ready
    }

    private fun clearPreloadedNextAsync() {
        val toClose = synchronized(preloadLock) {
            val prev = preloadedTrack?.decoder
            preloadedTrack = null
            preloadingUri = null
            prev
        }
        if (toClose != null) {
            closeDecoderOffMainThread(toClose)
        }
    }

    private fun clearPreloadedNextLocked() {
        val toClose = synchronized(preloadLock) {
            val prev = preloadedTrack?.decoder
            preloadedTrack = null
            preloadingUri = null
            prev
        }
        toClose?.let { runCatching { it.close() } }
    }

    private fun closeDecoderOffMainThread(decoder: PcmAudioDecoder) {
        if (releasedFlag.get()) {
            runCatching { decoder.close() }
            return
        }
        runCatching {
            preloadExecutor.execute { runCatching { decoder.close() } }
        }.onFailure {
            runCatching { decoder.close() }
        }
    }

    private fun recomputeReplayGain(): ReplayGainResolution {
        val resolved = ReplayGainProcessor.resolve(
            config = replayGainConfig,
            metadata = activeTrackReplayGainMetadata,
            safetyMode = safetyController.state().mode,
        )
        activeReplayGainResolution = resolved
        return resolved
    }

    private fun readReplayGainMetadata(
        uri: Uri,
        fileName: String,
        mimeType: String?,
    ): ReplayGainMetadata? = runCatching {
        val formatHint = AudioFormatClassifier.classify(fileName, mimeType)
        appContext.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            FileInputStream(pfd.fileDescriptor).use { fis ->
                val channel = fis.channel
                val size = runCatching { channel.size() }.getOrDefault(-1L)
                if (size > 0L) {
                    ReplayGainTagParser.parse(
                        source = FileChannelSeekableByteSource(channel, size),
                        formatHint = formatHint,
                    )
                } else {
                    null
                }
            }
        }
    }.getOrNull()

    private fun safePlayTrack(track: AudioTrack) {
        runCatching {
            if (track.state == AudioTrack.STATE_INITIALIZED && track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                track.play()
            }
        }
    }

    private fun safePauseTrack(track: AudioTrack) {
        runCatching {
            if (track.state == AudioTrack.STATE_INITIALIZED && track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                track.pause()
            }
        }
    }

    private fun safeFlushTrack(track: AudioTrack) {
        runCatching {
            if (track.state == AudioTrack.STATE_INITIALIZED) {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                }
                track.flush()
            }
        }
    }

    /**
     * Cleanly idempotent AudioTrack teardown: avoids duplicate `stop()` / `pause()` calls when the
     * track is already paused, flushed, or stopped.
     */
    private fun safeStopAndReleaseTrack(track: AudioTrack) {
        runCatching {
            if (track.state == AudioTrack.STATE_INITIALIZED) {
                when (track.playState) {
                    AudioTrack.PLAYSTATE_PLAYING -> {
                        track.pause()
                        track.flush()
                        track.stop()
                    }
                    AudioTrack.PLAYSTATE_PAUSED -> {
                        track.flush()
                        track.stop()
                    }
                    AudioTrack.PLAYSTATE_STOPPED -> {
                        // Already stopped; skip redundant stop() call.
                    }
                }
            }
        }
        runCatching { track.release() }
    }

    private fun stopWorkerAndReleaseTrackLocked(clearMixer: Boolean) {
        abortTurntableScratch()
        stopWorkerFlag.set(true)
        isPausedFlag.set(true)

        val track = activeAudioTrack
        activeAudioTrack = null
        if (track != null) {
            // Unblock any blocking track.write() on worker thread cleanly once.
            safePauseTrack(track)
            safeFlushTrack(track)
        }

        val thread = playbackThread
        playbackThread = null
        if (thread != null && thread != Thread.currentThread()) {
            thread.interrupt()
            runCatching { thread.join(600L) }
        }

        if (track != null) {
            safeStopAndReleaseTrack(track)
        }

        val decoder = activeDecoder
        activeDecoder = null
        if (decoder != null) {
            runCatching { decoder.close() }
        }

        if (safetyController.state().mode == AudioOutputMode.BIT_PERFECT_OPT_IN) {
            turntableRingBuffer = null
        }

        if (clearMixer) {
            clearPreferredMixerAttributesOnApi34(null)
        }
        audioTrackActualSampleRateHz = 0
        audioTrackActualEncodingLabel = "None"
    }

    private fun requestAudioFocusLocked(): Boolean {
        if (hasAudioFocus) return true
        val res = audioManager.requestAudioFocus(audioFocusRequest)
        hasAudioFocus = (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        return hasAudioFocus
    }

    private fun abandonAudioFocusLocked() {
        if (!hasAudioFocus) return
        runCatching { audioManager.abandonAudioFocusRequest(audioFocusRequest) }
        hasAudioFocus = false
    }

    private fun registerAudioMonitors() {
        if (receiversRegistered) return
        receiversRegistered = true
        runCatching {
            audioManager.registerAudioDeviceCallback(audioDeviceCallback, mainHandler)
        }
        runCatching {
            ContextCompat.registerReceiver(
                appContext,
                becomingNoisyReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
    }

    private fun unregisterAudioMonitors() {
        if (!receiversRegistered) return
        receiversRegistered = false
        runCatching { audioManager.unregisterAudioDeviceCallback(audioDeviceCallback) }
        runCatching { appContext.unregisterReceiver(becomingNoisyReceiver) }
    }

    private fun publishDiagnostics() {
        val safety = safetyController.state()
        val rgResolution = recomputeReplayGain()
        val fmt = activeDecoder?.format
        val device = activeOutputDevice ?: detectPreferredOutputDevice()
        val connType = mapConnectionType(device)
        val deviceName = device?.let(::resolveDeviceName) ?: safety.activeDeviceName

        val snapshot = AudioDiagnosticMapper.map(
            AudioDiagnosticInput(
                isTrackLoaded = fmt != null,
                sourceContainer = fmt?.sourceContainer ?: "None",
                sourceCodec = fmt?.sourceCodec ?: "None",
                sourceSampleRateHz = fmt?.sourceSampleRateHz ?: 0,
                sourceBitDepth = fmt?.sourceBitDepth,
                sourceHeaderVerified = fmt?.sourceHeaderVerified ?: false,
                decodedPcmEncodingLabel = fmt?.encoding?.displayLabel ?: "None",
                decodedEffectiveBitDepth = fmt?.effectiveBitDepth ?: 0,
                decodedSampleRateHz = fmt?.sampleRateHz ?: 0,
                decodedChannelCount = fmt?.channelCount ?: 0,
                decoderName = fmt?.decoderName ?: "Idle",
                audioTrackSampleRateHz = audioTrackActualSampleRateHz,
                audioTrackEncodingLabel = audioTrackActualEncodingLabel,
                safetyMode = safety.mode,
                isHardwareVolumeVerified = safety.isHardwareVolumeVerified,
                apiLevel = Build.VERSION.SDK_INT,
                outputDeviceName = deviceName,
                outputConnectionType = connType,
                negotiatedMixerBehavior = activeNegotiatedMixerBehavior,
                negotiatedMixerSummary = activeNegotiatedMixerSummary,
                replayGainSummary = rgResolution.diagnosticLabel,
                isReplayGainModifyingSamples = rgResolution.isModifyingSamples,
            ),
        )
        _state.update {
            it.copy(
                safetyState = safety,
                diagnostics = snapshot,
            )
        }
    }

    private fun detectPreferredOutputDevice(): AudioDeviceInfo? {
        val outputs = runCatching { audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }
            .getOrDefault(emptyArray())
        return outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
            ?: outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_DEVICE }
            ?: outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES }
            ?: outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
            ?: outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            ?: outputs.firstOrNull()
    }

    private fun isUsbOutputDevice(device: AudioDeviceInfo): Boolean =
        device.type == AudioDeviceInfo.TYPE_USB_DEVICE || device.type == AudioDeviceInfo.TYPE_USB_HEADSET

    private fun mapConnectionType(device: AudioDeviceInfo?): OutputConnectionType {
        if (device == null) return OutputConnectionType.INTERNAL_SPEAKER
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (device.type == AudioDeviceInfo.TYPE_BLE_HEADSET || device.type == AudioDeviceInfo.TYPE_BLE_SPEAKER) {
                return OutputConnectionType.BLUETOOTH
            }
        }
        return when (device.type) {
            AudioDeviceInfo.TYPE_USB_HEADSET -> OutputConnectionType.USB_HEADSET
            AudioDeviceInfo.TYPE_USB_DEVICE -> OutputConnectionType.USB_DEVICE
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES ->
                OutputConnectionType.WIRED_HEADSET
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO ->
                OutputConnectionType.BLUETOOTH
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE ->
                OutputConnectionType.INTERNAL_SPEAKER
            else -> OutputConnectionType.OTHER
        }
    }

    private fun resolveDeviceName(device: AudioDeviceInfo): String {
        val product = device.productName?.toString()?.trim().orEmpty()
        return product.ifEmpty { mapConnectionType(device).displayName }
    }

    private fun toAndroidAudioEncoding(encoding: PcmEncodingType): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            when (encoding) {
                PcmEncodingType.PCM_24BIT_PACKED -> return AudioFormat.ENCODING_PCM_24BIT_PACKED
                PcmEncodingType.PCM_32BIT -> return AudioFormat.ENCODING_PCM_32BIT
                PcmEncodingType.PCM_FLOAT -> return AudioFormat.ENCODING_PCM_FLOAT
                PcmEncodingType.PCM_16BIT -> return AudioFormat.ENCODING_PCM_16BIT
            }
        }
        return when (encoding) {
            PcmEncodingType.PCM_16BIT -> AudioFormat.ENCODING_PCM_16BIT
            PcmEncodingType.PCM_24BIT_PACKED, PcmEncodingType.PCM_32BIT, PcmEncodingType.PCM_FLOAT ->
                AudioFormat.ENCODING_PCM_FLOAT
        }
    }

    private fun toChannelOutMask(channelCount: Int): Int = when (channelCount) {
        1 -> AudioFormat.CHANNEL_OUT_MONO
        2 -> AudioFormat.CHANNEL_OUT_STEREO
        4 -> AudioFormat.CHANNEL_OUT_QUAD
        6 -> AudioFormat.CHANNEL_OUT_5POINT1
        8 -> AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
        else -> AudioFormat.CHANNEL_OUT_STEREO
    }

    private fun androidEncodingName(encoding: Int): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (encoding == AudioFormat.ENCODING_PCM_24BIT_PACKED) return "PCM_24BIT_PACKED"
            if (encoding == AudioFormat.ENCODING_PCM_32BIT) return "PCM_32BIT"
        }
        return when (encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> "PCM_16BIT"
            AudioFormat.ENCODING_PCM_FLOAT -> "PCM_FLOAT (32-bit IEEE)"
            else -> "ENC_$encoding"
        }
    }

    private fun readUnsignedPlaybackHeadFrames(track: AudioTrack): Long =
        runCatching { track.playbackHeadPosition.toLong() and 0xFFFFFFFFL }.getOrDefault(0L)

    companion object {
        private const val TAG = "BitPerfectAudio"
    }
}
