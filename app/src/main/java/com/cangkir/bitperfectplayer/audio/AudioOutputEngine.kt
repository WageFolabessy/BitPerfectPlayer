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

    private enum class FormatChangeOutcome {
        NONE,
        REBUILT_CONTINUE,
        STOPPED_BREAK,
    }

    /**
     * Mutable per-worker state container extracted from [runPlaybackLoop] so every hot audio helper
     * method stays compact (well below ART's 16,384 DEX instruction JIT/AOT compiler ceiling) with
     * zero per-buffer GC allocations or Kotlin Ref-boxing overhead.
     */
    private class PlaybackLoopState(
        val expectedGeneration: Long,
        var currentDecoder: PcmAudioDecoder,
        var currentTrack: AudioTrack,
        initialSeekMs: Long,
        initialHeadFrames: Long,
        initialRingBuffer: TurntablePcmRingBuffer?,
    ) {
        var observedFormatVersion: Int = currentDecoder.formatVersion
        var currentFormat: DecodedPcmFormat = currentDecoder.format
        var sampleRate: Int = currentFormat.sampleRateHz.coerceAtLeast(1)
        var durationMs: Long = currentFormat.durationMs.coerceAtLeast(0L)
        var bytesPerFrame: Int = currentFormat.bytesPerFrame.coerceAtLeast(1)
        var channels: Int = currentFormat.channelCount.coerceIn(1, 8)

        var bufferSize: Int = PcmStreamRules.computeAlignedBufferSize(16_384, bytesPerFrame)
        var pcmBuffer: ByteArray = ByteArray(bufferSize)
        var directBuffer: ByteBuffer = ByteBuffer.allocateDirect(bufferSize).order(ByteOrder.LITTLE_ENDIAN)
        var floatBuffer: FloatArray = FloatArray(bufferSize / 4)
        var remainder: ByteArray = ByteArray(bytesPerFrame)
        var remainderLen: Int = 0

        var activeRingBuffer: TurntablePcmRingBuffer? = initialRingBuffer
        var resampler: TurntableResampler? = initialRingBuffer?.let { TurntableResampler(sampleRate, channels) }
        var scratchChunkFrames: Int = (sampleRate / 66).coerceIn(256, 4096)
        var teeFloats: FloatArray = if (initialRingBuffer != null) {
            FloatArray(maxOf(bufferSize / currentFormat.encoding.bytesPerSample.coerceAtLeast(1), 4096))
        } else {
            FloatArray(0)
        }
        var scratchOutFloats: FloatArray =
            if (initialRingBuffer != null) FloatArray(scratchChunkFrames * channels) else FloatArray(0)
        var scratchOutBytes: ByteArray =
            if (initialRingBuffer != null) ByteArray(scratchChunkFrames * bytesPerFrame) else ByteArray(0)

        var seekBaseMs: Long = initialSeekMs.coerceAtLeast(0L)
        var streamOriginMs: Long = seekBaseMs
        var headBaseFrames: Long = initialHeadFrames
        var totalFramesWrittenToTrack: Long = initialHeadFrames
        var streamFramesWrittenToTrack: Long = 0L
        var ringDrainCursorFrame: Long = -1L
        var exitFadeFramesRemaining: Int = 0
        var decoderEosDuringScratch: Boolean = false
        var countHeadFrames: Long = initialHeadFrames
        var pendingPlayCountMs: Long = 0L
        var wasPausedInLoop: Boolean = false
        var lastUiUpdateNano: Long = 0L
        var scratchCursorFrame: Double = 0.0

        fun reconfigureBuffersForFormat(
            newFormat: DecodedPcmFormat,
            ringBuffer: TurntablePcmRingBuffer?,
        ) {
            currentFormat = newFormat
            sampleRate = newFormat.sampleRateHz.coerceAtLeast(1)
            durationMs = newFormat.durationMs.coerceAtLeast(0L)
            bytesPerFrame = newFormat.bytesPerFrame.coerceAtLeast(1)
            channels = newFormat.channelCount.coerceIn(1, 8)
            bufferSize = PcmStreamRules.computeAlignedBufferSize(16_384, bytesPerFrame)
            pcmBuffer = ByteArray(bufferSize)
            directBuffer = ByteBuffer.allocateDirect(bufferSize).order(ByteOrder.LITTLE_ENDIAN)
            floatBuffer = FloatArray(bufferSize / 4)
            remainder = ByteArray(bytesPerFrame)
            remainderLen = 0
            activeRingBuffer = ringBuffer
            resampler = ringBuffer?.let { TurntableResampler(sampleRate, channels) }
            scratchChunkFrames = (sampleRate / 66).coerceIn(256, 4096)
            teeFloats = if (ringBuffer != null) {
                FloatArray(maxOf(bufferSize / newFormat.encoding.bytesPerSample.coerceAtLeast(1), 4096))
            } else {
                FloatArray(0)
            }
            scratchOutFloats = if (ringBuffer != null) FloatArray(scratchChunkFrames * channels) else FloatArray(0)
            scratchOutBytes = if (ringBuffer != null) ByteArray(scratchChunkFrames * bytesPerFrame) else ByteArray(0)
        }
    }

    private fun ensureSafeModeRingBuffer(channelCount: Int): TurntablePcmRingBuffer? {
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

    private fun ensureActiveSpectrumTap(rateHz: Int, trackFrameCursor: Long): SpectrumPcmTap? {
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

    private fun accumulateActivePlayTime(
        state: PlaybackLoopState,
        forceFlush: Boolean,
        overrideHeadFrames: Long? = null,
    ) {
        val headFrames = overrideHeadFrames ?: readUnsignedPlaybackHeadFrames(state.currentTrack)
        val stepFrames = (headFrames - state.countHeadFrames).coerceAtLeast(0L)
        if (stepFrames > 0L) {
            val stepMs = (stepFrames * 1000L) / state.sampleRate.toLong()
            if (stepMs > 0L) {
                val consumedFrames = (stepMs * state.sampleRate.toLong()) / 1000L
                state.countHeadFrames += consumedFrames
                state.pendingPlayCountMs += stepMs
            }
        }
        if ((forceFlush && state.pendingPlayCountMs > 0L) || state.pendingPlayCountMs >= 500L) {
            val toEmit = state.pendingPlayCountMs
            state.pendingPlayCountMs = 0L
            onActivePlaybackElapsedMs?.invoke(toEmit)
        }
    }

    private fun runPlaybackLoop(
        initialDecoder: PcmAudioDecoder,
        initialTrack: AudioTrack,
        initialSeekMs: Long,
        expectedGeneration: Long,
    ) {
        val initialChannels = initialDecoder.format.channelCount.coerceIn(1, 8)
        val initialRing = ensureSafeModeRingBuffer(initialChannels)?.also { it.reset(0L) }
        val state = PlaybackLoopState(
            expectedGeneration = expectedGeneration,
            currentDecoder = initialDecoder,
            currentTrack = initialTrack,
            initialSeekMs = initialSeekMs,
            initialHeadFrames = readUnsignedPlaybackHeadFrames(initialTrack),
            initialRingBuffer = initialRing,
        )

        if (spectrumTapActive.get()) {
            val tap = spectrumPcmTap ?: SpectrumPcmTap().also { spectrumPcmTap = it }
            tap.reset(state.sampleRate, state.totalFramesWrittenToTrack)
        }

        try {
            while (!stopWorkerFlag.get() && !releasedFlag.get()) {
                val requestedSeek = pendingSeekMs.getAndSet(-1L)
                if (requestedSeek >= 0L) {
                    handlePendingSeekInLoop(state, requestedSeek)
                    continue
                }

                if (isPausedFlag.get()) {
                    if (!state.wasPausedInLoop) {
                        accumulateActivePlayTime(state, forceFlush = true)
                        state.wasPausedInLoop = true
                    }
                    try {
                        Thread.sleep(25L)
                    } catch (_: InterruptedException) {
                        break
                    }
                    continue
                }
                if (state.wasPausedInLoop) {
                    state.countHeadFrames = maxOf(
                        readUnsignedPlaybackHeadFrames(state.currentTrack),
                        state.headBaseFrames,
                    )
                    state.wasPausedInLoop = false
                }

                if (turntableScratchRequested.get()) {
                    processTurntableScratchStep(state)
                    continue
                }

                if (state.ringDrainCursorFrame >= 0L && processRingDrainStepInLoop(state)) {
                    continue
                }

                val bytesRead = if (state.decoderEosDuringScratch) {
                    -1
                } else {
                    state.currentDecoder.readPcm(state.pcmBuffer)
                }
                if (bytesRead < 0) {
                    if (handleEndOfStreamInLoop(state)) continue else break
                }

                when (handleMidStreamFormatChangeInLoop(state)) {
                    FormatChangeOutcome.REBUILT_CONTINUE -> continue
                    FormatChangeOutcome.STOPPED_BREAK -> break
                    FormatChangeOutcome.NONE -> Unit
                }

                if (bytesRead == 0) {
                    try {
                        Thread.sleep(5L)
                    } catch (_: InterruptedException) {
                        break
                    }
                    continue
                }

                processNormalPcmChunkInLoop(state, bytesRead)
                publishPlaybackPositionAndPreload(state)
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
            runCatching { accumulateActivePlayTime(state, forceFlush = true) }
        }
    }

    private fun handlePendingSeekInLoop(
        state: PlaybackLoopState,
        requestedSeekMs: Long,
    ) {
        accumulateActivePlayTime(state, forceFlush = true)
        val wasPlaying = !isPausedFlag.get()
        safePauseTrack(state.currentTrack)
        safeFlushTrack(state.currentTrack)
        state.remainderLen = 0
        val actualSeekMs = state.currentDecoder.seekToMs(requestedSeekMs)
        state.seekBaseMs = actualSeekMs
        state.streamOriginMs = actualSeekMs
        state.activeRingBuffer?.reset(0L)
        state.streamFramesWrittenToTrack = 0L
        state.ringDrainCursorFrame = -1L
        state.exitFadeFramesRemaining = 0
        state.decoderEosDuringScratch = false
        state.headBaseFrames = readUnsignedPlaybackHeadFrames(state.currentTrack)
        state.totalFramesWrittenToTrack = state.headBaseFrames
        state.countHeadFrames = state.headBaseFrames
        spectrumPcmTap?.reset(state.sampleRate, state.totalFramesWrittenToTrack)
        state.lastUiUpdateNano = 0L
        _playbackPositionMs.value = actualSeekMs
        if (wasPlaying) {
            safePlayTrack(state.currentTrack)
        }
        val nextReq = queuedNextRequest
        if (GaplessTransitionPolicy.shouldStartPreload(
                gaplessEnabled = gaplessEnabled,
                hasNextTrack = nextReq != null,
                isAlreadyPreloadingOrReady = isNextPreloadedOrLoading(nextReq?.uri),
                currentPositionMs = actualSeekMs,
                durationMs = state.durationMs,
            )
        ) {
            triggerPreloadAsync(nextReq, state.expectedGeneration)
        }
    }

    /**
     * Executes Safe-Mode-only live turntable scratch rendering (~65 ms fill headroom with 2 ms wait
     * sleep to eliminate 44,100 Hz AudioTrack underruns while preserving crisp tactile response and
     * zero `AudioTrack` rebuilds).
     */
    private fun processTurntableScratchStep(state: PlaybackLoopState) {
        val ringBuf = state.activeRingBuffer
        val scratchResampler = state.resampler
        if (safetyController.state().mode != AudioOutputMode.SAFE_VOLUME_MODE ||
            ringBuf == null ||
            scratchResampler == null
        ) {
            abortTurntableScratch()
            return
        }

        // 1. Flush and freeze PlayCountTracker so scratching never inflates listening time.
        accumulateActivePlayTime(state, forceFlush = true)

        // 2. Anchor scratch cursor to the exact hardware playback head currently reaching the speaker.
        val hwHeadOnEntry = readUnsignedPlaybackHeadFrames(state.currentTrack)
        val queuedAhead = (state.totalFramesWrittenToTrack - hwHeadOnEntry).coerceAtLeast(0L)
        val currentStreamHead = if (state.ringDrainCursorFrame >= 0L) {
            state.ringDrainCursorFrame
        } else {
            state.streamFramesWrittenToTrack
        }
        val oldestF = ringBuf.oldestAvailableFrame
        val maxReadableF = (ringBuf.writeHeadFrame - 1L).coerceAtLeast(oldestF)
        state.scratchCursorFrame = (currentStreamHead - queuedAhead)
            .coerceIn(oldestF, maxReadableF)
            .toDouble()

        // 3. Flush ~160 ms normal lookahead once so live turntable audio reacts promptly.
        safePauseTrack(state.currentTrack)
        safeFlushTrack(state.currentTrack)
        state.headBaseFrames = readUnsignedPlaybackHeadFrames(state.currentTrack)
        state.totalFramesWrittenToTrack = state.headBaseFrames
        state.countHeadFrames = state.headBaseFrames
        spectrumPcmTap?.reset(state.sampleRate, state.totalFramesWrittenToTrack)
        safePlayTrack(state.currentTrack)

        val initialRate = Float.fromBits(turntableTargetRateBits.get())
        scratchResampler.resetForScratchEntry(initialRate)
        val minVirtualFrame = -(state.streamOriginMs.toDouble() * state.sampleRate.toDouble() / 1000.0)
        val maxVirtualFrame = if (state.durationMs > 0L) {
            ((state.durationMs - state.streamOriginMs).toDouble() * state.sampleRate.toDouble() / 1000.0)
                .coerceAtLeast(minVirtualFrame)
        } else {
            Double.MAX_VALUE
        }
        // ~65 ms headroom (sampleRate / 15) + 2 ms sleep prevents 44,100 Hz AudioTrack underruns.
        val maxAheadFrames = (state.sampleRate / 15).toLong().coerceAtLeast(512L)

        while (turntableScratchRequested.get() &&
            !stopWorkerFlag.get() &&
            !releasedFlag.get() &&
            !isPausedFlag.get() &&
            pendingSeekMs.get() < 0L &&
            safetyController.state().mode == AudioOutputMode.SAFE_VOLUME_MODE
        ) {
            val hwHead = readUnsignedPlaybackHeadFrames(state.currentTrack)
            val ahead = (state.totalFramesWrittenToTrack - hwHead).coerceAtLeast(0L)
            if (ahead > maxAheadFrames) {
                try {
                    Thread.sleep(2L)
                } catch (_: InterruptedException) {
                    break
                }
                continue
            }

            val targetRate = Float.fromBits(turntableTargetRateBits.get())
            if ((targetRate > 0f || scratchResampler.currentRate > 0f) && !state.decoderEosDuringScratch) {
                decodeAheadForForwardScratch(state, ringBuf, scratchResampler, targetRate)
            }

            val effectiveMaxFrame = if (state.decoderEosDuringScratch && ringBuf.writeHeadFrame > 0L) {
                minOf(maxVirtualFrame, (ringBuf.writeHeadFrame - 1L).toDouble())
            } else {
                maxVirtualFrame
            }

            val step = scratchResampler.renderChunk(
                ringBuffer = ringBuf,
                targetRate = targetRate,
                startCursorFrame = state.scratchCursorFrame,
                minVirtualFrame = minVirtualFrame,
                maxVirtualFrame = effectiveMaxFrame,
                outFrames = state.scratchChunkFrames,
                destFloats = state.scratchOutFloats,
                replayGainLinear = activeReplayGainResolution.linearGain,
            )
            state.scratchCursorFrame = step.nextCursorFrame

            writeScratchFloatsAndTapSpectrum(state, step.framesRendered)

            val liveTrackMs = (state.streamOriginMs + (state.scratchCursorFrame * 1000.0) / state.sampleRate.toDouble())
                .roundToLong()
                .let { if (state.durationMs > 0L) it.coerceIn(0L, state.durationMs) else it.coerceAtLeast(0L) }
            _playbackPositionMs.value = liveTrackMs
        }

        exitTurntableScratchCleanly(state, ringBuf, scratchResampler, minVirtualFrame, maxVirtualFrame)
    }

    private fun decodeAheadForForwardScratch(
        state: PlaybackLoopState,
        ringBuf: TurntablePcmRingBuffer,
        scratchResampler: TurntableResampler,
        targetRate: Float,
    ) {
        val peakForwardRate = maxOf(targetRate, scratchResampler.currentRate, 0f).toDouble()
        val neededEndFrame = ceil(
            state.scratchCursorFrame + peakForwardRate * state.scratchChunkFrames.toDouble() + 4.0,
        ).toLong()
        var reads = 0
        while (ringBuf.writeHeadFrame <= neededEndFrame &&
            reads < 12 &&
            !state.decoderEosDuringScratch &&
            !stopWorkerFlag.get() &&
            !releasedFlag.get() &&
            turntableScratchRequested.get()
        ) {
            reads++
            val br = state.currentDecoder.readPcm(state.pcmBuffer)
            if (state.currentDecoder.formatVersion != state.observedFormatVersion) {
                abortTurntableScratch()
                break
            }
            if (br < 0) {
                state.decoderEosDuringScratch = true
                break
            }
            if (br == 0) break

            val comb: ByteArray
            val totLen: Int
            if (state.remainderLen > 0) {
                totLen = state.remainderLen + br
                comb = ByteArray(totLen)
                System.arraycopy(state.remainder, 0, comb, 0, state.remainderLen)
                System.arraycopy(state.pcmBuffer, 0, comb, state.remainderLen, br)
                state.remainderLen = 0
            } else {
                comb = state.pcmBuffer
                totLen = br
            }
            val alLen = PcmStreamRules.alignByteCountToFrame(totLen, state.bytesPerFrame)
            val trLen = totLen - alLen
            if (trLen > 0) {
                System.arraycopy(comb, alLen, state.remainder, 0, trLen)
                state.remainderLen = trLen
            }
            if (alLen > 0) {
                val neededFloats = alLen / state.currentFormat.encoding.bytesPerSample
                if (state.teeFloats.size < neededFloats) {
                    state.teeFloats = FloatArray(neededFloats)
                }
                val decodedFloats = TurntablePcmCodec.decodePcmBytesToFloats(
                    encoding = state.currentFormat.encoding,
                    srcBytes = comb,
                    byteOffset = 0,
                    byteCount = alLen,
                    destFloats = state.teeFloats,
                )
                val decodedFrames = decodedFloats / state.channels
                if (decodedFrames > 0) {
                    ringBuf.appendInterleavedFloats(state.teeFloats, decodedFrames)
                }
            }
        }
    }

    private fun exitTurntableScratchCleanly(
        state: PlaybackLoopState,
        ringBuf: TurntablePcmRingBuffer,
        scratchResampler: TurntableResampler,
        minVirtualFrame: Double,
        maxVirtualFrame: Double,
    ) {
        val cleanExitToPlay = !stopWorkerFlag.get() &&
            !releasedFlag.get() &&
            !isPausedFlag.get() &&
            pendingSeekMs.get() < 0L &&
            safetyController.state().mode == AudioOutputMode.SAFE_VOLUME_MODE

        if (!cleanExitToPlay) {
            turntableFallbackSeekMs.set(-1L)
            val finalMs = (state.streamOriginMs + (state.scratchCursorFrame * 1000.0) / state.sampleRate.toDouble())
                .roundToLong()
                .let { if (state.durationMs > 0L) it.coerceIn(0L, state.durationMs) else it.coerceAtLeast(0L) }
            _playbackPositionMs.value = finalMs
            state.countHeadFrames = maxOf(readUnsignedPlaybackHeadFrames(state.currentTrack), state.headBaseFrames)
            if (isPausedFlag.get() && !stopWorkerFlag.get() && !releasedFlag.get() && pendingSeekMs.get() < 0L) {
                val cursorLong = state.scratchCursorFrame.roundToLong()
                if (ringBuf.bufferedFrameCount > 0 &&
                    cursorLong >= ringBuf.oldestAvailableFrame &&
                    cursorLong <= ringBuf.writeHeadFrame
                ) {
                    state.ringDrainCursorFrame = cursorLong
                    state.seekBaseMs = finalMs
                    state.headBaseFrames = state.totalFramesWrittenToTrack
                    state.countHeadFrames = maxOf(readUnsignedPlaybackHeadFrames(state.currentTrack), state.headBaseFrames)
                } else {
                    pendingSeekMs.set(finalMs)
                }
            }
            return
        }

        val fadeOutFrames = scratchResampler.exitFadeDurationFrames().coerceAtMost(state.scratchChunkFrames)
        if (scratchResampler.fadeGain > 0.01f && fadeOutFrames > 0) {
            val tailStep = scratchResampler.renderChunk(
                ringBuffer = ringBuf,
                targetRate = scratchResampler.currentRate,
                startCursorFrame = state.scratchCursorFrame,
                minVirtualFrame = minVirtualFrame,
                maxVirtualFrame = maxVirtualFrame,
                outFrames = fadeOutFrames,
                destFloats = state.scratchOutFloats,
                replayGainLinear = activeReplayGainResolution.linearGain,
                forceFadeOut = true,
            )
            state.scratchCursorFrame = tailStep.nextCursorFrame
            writeScratchFloatsAndTapSpectrum(state, tailStep.framesRendered)
        }

        val fallbackMs = turntableFallbackSeekMs.getAndSet(-1L)
        val targetMs = if (fallbackMs >= 0L) {
            fallbackMs.let { if (state.durationMs > 0L) it.coerceIn(0L, state.durationMs) else it }
        } else {
            (state.streamOriginMs + (state.scratchCursorFrame * 1000.0) / state.sampleRate.toDouble())
                .roundToLong()
                .let { if (state.durationMs > 0L) it.coerceIn(0L, state.durationMs) else it.coerceAtLeast(0L) }
        }

        val cursorFrameLong = state.scratchCursorFrame.roundToLong()
        val cursorInsideRing = ringBuf.bufferedFrameCount > 0 &&
            cursorFrameLong >= ringBuf.oldestAvailableFrame &&
            cursorFrameLong <= ringBuf.writeHeadFrame &&
            fallbackMs < 0L

        if (cursorInsideRing) {
            // Fast Seamless Path: zero MediaCodec.flush()! Immediately feed up to 2 chunks of 1.0x
            // ring drain so AudioTrack never starves or triggers restartIfDisabled on transition.
            state.ringDrainCursorFrame = cursorFrameLong
            state.exitFadeFramesRemaining = scratchResampler.exitFadeDurationFrames()
            state.seekBaseMs = targetMs
            state.headBaseFrames = state.totalFramesWrittenToTrack
            state.countHeadFrames = maxOf(readUnsignedPlaybackHeadFrames(state.currentTrack), state.headBaseFrames)
            state.lastUiUpdateNano = 0L
            _playbackPositionMs.value = targetMs

            var preFed = 0
            while (preFed < 2 &&
                state.ringDrainCursorFrame >= 0L &&
                !stopWorkerFlag.get() &&
                !releasedFlag.get() &&
                !isPausedFlag.get() &&
                !turntableScratchRequested.get()
            ) {
                if (!processRingDrainStepInLoop(state)) break
                preFed++
            }
        } else {
            // Deep Rewind Path: rewound past oldestAvailableFrame -> single clean seekToMs.
            safePauseTrack(state.currentTrack)
            safeFlushTrack(state.currentTrack)
            state.remainderLen = 0
            ringBuf.reset(0L)
            state.ringDrainCursorFrame = -1L
            state.exitFadeFramesRemaining = 0
            state.decoderEosDuringScratch = false
            val actualSeekMs = state.currentDecoder.seekToMs(targetMs)
            state.streamOriginMs = actualSeekMs
            state.seekBaseMs = actualSeekMs
            state.streamFramesWrittenToTrack = 0L
            state.headBaseFrames = readUnsignedPlaybackHeadFrames(state.currentTrack)
            state.totalFramesWrittenToTrack = state.headBaseFrames
            state.countHeadFrames = state.headBaseFrames
            spectrumPcmTap?.reset(state.sampleRate, state.totalFramesWrittenToTrack)
            state.lastUiUpdateNano = 0L
            _playbackPositionMs.value = actualSeekMs
            safePlayTrack(state.currentTrack)
        }
    }

    private fun writeScratchFloatsAndTapSpectrum(
        state: PlaybackLoopState,
        frameCount: Int,
    ): Int {
        val framesWritten = writeNormalizedFloatsToTrack(
            track = state.currentTrack,
            encoding = state.currentFormat.encoding,
            srcFloats = state.scratchOutFloats,
            frameCount = frameCount,
            channels = state.channels,
            bytesPerFrame = state.bytesPerFrame,
            scratchBytes = state.scratchOutBytes,
            directBuffer = state.directBuffer,
        )
        if (framesWritten > 0) {
            val frameStart = state.totalFramesWrittenToTrack
            state.totalFramesWrittenToTrack += framesWritten.toLong()
            if (spectrumTapActive.get()) {
                ensureActiveSpectrumTap(state.sampleRate, frameStart)?.appendInterleavedFloatsReadOnly(
                    srcFloats = state.scratchOutFloats,
                    frameCount = framesWritten,
                    channels = state.channels,
                    trackFrameAfterWrite = state.totalFramesWrittenToTrack,
                )
            }
        }
        return framesWritten
    }

    /**
     * Drains one chunk of buffered `1.0x` post-scratch PCM frames from the ring buffer.
     * Returns `true` if a chunk was written (caller should continue loop), or `false` once fully drained.
     */
    private fun processRingDrainStepInLoop(state: PlaybackLoopState): Boolean {
        val ringBuf = state.activeRingBuffer ?: run {
            state.ringDrainCursorFrame = -1L
            return false
        }
        if (state.ringDrainCursorFrame < ringBuf.writeHeadFrame) {
            val copiedFrames = ringBuf.copyFramesAt1x(
                startFrame = state.ringDrainCursorFrame,
                maxFrames = state.scratchChunkFrames,
                destFloats = state.scratchOutFloats,
            )
            if (copiedFrames > 0) {
                val floatCount = copiedFrames * state.channels
                val effectiveGain =
                    if (safetyController.state().mode == AudioOutputMode.BIT_PERFECT_OPT_IN) {
                        1.0f
                    } else {
                        activeReplayGainResolution.linearGain
                    }
                if (ReplayGainProcessor.needsSampleScaling(effectiveGain)) {
                    ReplayGainProcessor.applyGainToFloatSamples(state.scratchOutFloats, floatCount, effectiveGain)
                }
                val resampler = state.resampler
                if (state.exitFadeFramesRemaining > 0 && resampler != null) {
                    state.exitFadeFramesRemaining = resampler.applyExitFadeIn(
                        destFloats = state.scratchOutFloats,
                        frameCount = copiedFrames,
                        remainingFadeFrames = state.exitFadeFramesRemaining,
                    )
                }
                val framesWritten = writeScratchFloatsAndTapSpectrum(state, copiedFrames)
                if (framesWritten > 0) {
                    state.ringDrainCursorFrame += framesWritten.toLong()
                    state.streamFramesWrittenToTrack = state.ringDrainCursorFrame
                }
                accumulateActivePlayTime(state, forceFlush = false)
                publishPlaybackPositionAndPreload(state)
                return true
            }
        }
        state.streamFramesWrittenToTrack = ringBuf.writeHeadFrame
        state.ringDrainCursorFrame = -1L
        return false
    }

    /**
     * Handles decoder End-Of-Stream: either chains gaplessly (`SEAMLESS_REUSE`) onto the pre-loaded
     * next decoder or drains `AudioTrack` cleanly and signals track completion.
     * Returns `true` to continue the playback loop, or `false` to exit.
     */
    private fun handleEndOfStreamInLoop(state: PlaybackLoopState): Boolean {
        accumulateActivePlayTime(state, forceFlush = true)
        val preloaded = takePreloadedTrackIfReady(state.expectedGeneration)
        val hasNext = queuedNextRequest != null
        val transitionAction = GaplessTransitionPolicy.decide(
            gaplessEnabled = gaplessEnabled,
            hasNextTrack = hasNext,
            currentFormat = state.currentFormat.toStreamSignature(),
            nextFormat = preloaded?.decoder?.format?.toStreamSignature(),
        )

        if (transitionAction == GaplessTransitionAction.SEAMLESS_REUSE && preloaded != null) {
            val transitioned = engineLock.withLock {
                if (stopWorkerFlag.get() || releasedFlag.get() || loadGeneration.get() != state.expectedGeneration) {
                    false
                } else {
                    val oldDecoder = state.currentDecoder
                    state.currentDecoder = preloaded.decoder
                    activeDecoder = preloaded.decoder
                    currentRequest = preloaded.request
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
                return false
            }

            // Keep the exact same AudioTrack running continuously (0 ms gap, zero flush/rebuild).
            state.observedFormatVersion = state.currentDecoder.formatVersion
            state.currentFormat = state.currentDecoder.format
            state.sampleRate = state.currentFormat.sampleRateHz.coerceAtLeast(1)
            state.durationMs = state.currentFormat.durationMs.coerceAtLeast(0L)
            state.bytesPerFrame = state.currentFormat.bytesPerFrame.coerceAtLeast(1)
            state.channels = state.currentFormat.channelCount.coerceIn(1, 8)
            state.activeRingBuffer = ensureSafeModeRingBuffer(state.channels)?.also { it.reset(0L) }
            state.resampler = state.activeRingBuffer?.let { TurntableResampler(state.sampleRate, state.channels) }
            state.remainderLen = 0
            state.seekBaseMs = 0L
            state.streamOriginMs = 0L
            state.streamFramesWrittenToTrack = 0L
            state.ringDrainCursorFrame = -1L
            state.exitFadeFramesRemaining = 0
            state.decoderEosDuringScratch = false
            // Track B begins playing at the exact hardware frame count already queued for Track A.
            state.headBaseFrames = state.totalFramesWrittenToTrack
            state.countHeadFrames = maxOf(readUnsignedPlaybackHeadFrames(state.currentTrack), state.headBaseFrames)
            state.lastUiUpdateNano = 0L
            _playbackPositionMs.value = 0L
            _state.update {
                it.copy(
                    status = PlaybackStatus.PLAYING,
                    activeTrackUri = preloaded.request.uri,
                    durationMs = state.currentFormat.durationMs,
                    errorMessage = null,
                )
            }
            publishDiagnostics()
            Log.d(
                TAG,
                "Gapless SEAMLESS_REUSE -> ${preloaded.request.fileName} " +
                    "(${state.currentFormat.sampleRateHz}Hz, ${state.currentFormat.encoding}, ${state.currentFormat.channelCount}ch)",
            )
            mainHandler.post { onGaplessTransition?.invoke(preloaded.request) }
            return true
        }

        // Format mismatch, gapless disabled, preload unavailable, or end of queue:
        // close any unused preloaded decoder and cleanly drain AudioTrack's tail buffer.
        preloaded?.let { runCatching { it.decoder.close() } }
        val drainedHeadFrames = drainAudioTrackCleanly(
            track = state.currentTrack,
            totalFramesWritten = state.totalFramesWrittenToTrack,
            sampleRateHz = state.sampleRate,
        )
        accumulateActivePlayTime(state, forceFlush = true, overrideHeadFrames = drainedHeadFrames)
        if (!stopWorkerFlag.get() && !releasedFlag.get() && pendingSeekMs.get() >= 0L) {
            return true
        }
        if (!stopWorkerFlag.get() && !releasedFlag.get()) {
            val finalPos = state.durationMs.takeIf { it > 0L } ?: _playbackPositionMs.value
            _playbackPositionMs.value = finalPos
            _state.update {
                it.copy(status = PlaybackStatus.COMPLETED)
            }
            mainHandler.post { onTrackCompleted?.invoke() }
        }
        return false
    }

    /**
     * Reconfigures `AudioTrack` and loop buffers dynamically if `MediaCodec` emitted
     * `INFO_OUTPUT_FORMAT_CHANGED` mid-stream.
     */
    private fun handleMidStreamFormatChangeInLoop(state: PlaybackLoopState): FormatChangeOutcome {
        val latestVersion = state.currentDecoder.formatVersion
        if (latestVersion == state.observedFormatVersion) return FormatChangeOutcome.NONE

        state.observedFormatVersion = latestVersion
        val newFormat = state.currentDecoder.format
        if (newFormat.encoding == state.currentFormat.encoding &&
            newFormat.sampleRateHz == state.currentFormat.sampleRateHz &&
            newFormat.channelCount == state.currentFormat.channelCount
        ) {
            return FormatChangeOutcome.NONE
        }

        accumulateActivePlayTime(state, forceFlush = true)
        val rebuiltTrack = engineLock.withLock {
            if (stopWorkerFlag.get() || releasedFlag.get()) {
                null
            } else {
                safeStopAndReleaseTrack(state.currentTrack)
                createConfiguredAudioTrackLocked(newFormat).also { created ->
                    activeAudioTrack = created
                    if (!isPausedFlag.get()) {
                        safePlayTrack(created)
                    }
                    publishDiagnostics()
                }
            }
        } ?: return FormatChangeOutcome.STOPPED_BREAK

        state.currentTrack = rebuiltTrack
        val newChannels = newFormat.channelCount.coerceIn(1, 8)
        val ringBuf = ensureSafeModeRingBuffer(newChannels)?.also { it.reset(0L) }
        state.reconfigureBuffersForFormat(newFormat, ringBuf)
        state.seekBaseMs = _playbackPositionMs.value
        state.streamOriginMs = state.seekBaseMs
        state.streamFramesWrittenToTrack = 0L
        state.ringDrainCursorFrame = -1L
        state.exitFadeFramesRemaining = 0
        state.decoderEosDuringScratch = false
        state.headBaseFrames = readUnsignedPlaybackHeadFrames(state.currentTrack)
        state.totalFramesWrittenToTrack = state.headBaseFrames
        state.countHeadFrames = state.headBaseFrames
        spectrumPcmTap?.reset(state.sampleRate, state.totalFramesWrittenToTrack)
        return FormatChangeOutcome.REBUILT_CONTINUE
    }

    /**
     * Aligns incoming decoder bytes to whole PCM frames, tees Safe-Mode pre-ReplayGain floats into
     * the turntable ring buffer, writes aligned PCM to `AudioTrack` (strictly unmodified in
     * Bit-Perfect Opt-In mode), and taps read-only spectrum samples post-write when active.
     */
    private fun processNormalPcmChunkInLoop(
        state: PlaybackLoopState,
        bytesRead: Int,
    ) {
        val combinedBytes: ByteArray
        val totalLen: Int
        if (state.remainderLen > 0) {
            totalLen = state.remainderLen + bytesRead
            combinedBytes = ByteArray(totalLen)
            System.arraycopy(state.remainder, 0, combinedBytes, 0, state.remainderLen)
            System.arraycopy(state.pcmBuffer, 0, combinedBytes, state.remainderLen, bytesRead)
            state.remainderLen = 0
        } else {
            combinedBytes = state.pcmBuffer
            totalLen = bytesRead
        }

        val alignedLen = PcmStreamRules.alignByteCountToFrame(totalLen, state.bytesPerFrame)
        val trailingLen = totalLen - alignedLen
        if (trailingLen > 0) {
            System.arraycopy(combinedBytes, alignedLen, state.remainder, 0, trailingLen)
            state.remainderLen = trailingLen
        }
        if (alignedLen <= 0) return

        // Safe-Mode-only pre-ReplayGain ring-buffer tee (zero allocs; NEVER runs in Bit-Perfect mode).
        val activeRing = state.activeRingBuffer
        if (safetyController.state().mode == AudioOutputMode.SAFE_VOLUME_MODE && activeRing != null) {
            val neededFloats = alignedLen / state.currentFormat.encoding.bytesPerSample
            if (state.teeFloats.size < neededFloats) {
                state.teeFloats = FloatArray(neededFloats)
            }
            val decodedFloats = TurntablePcmCodec.decodePcmBytesToFloats(
                encoding = state.currentFormat.encoding,
                srcBytes = combinedBytes,
                byteOffset = 0,
                byteCount = alignedLen,
                destFloats = state.teeFloats,
            )
            val decodedFrames = decodedFloats / state.channels
            if (decodedFrames > 0) {
                activeRing.appendInterleavedFloats(state.teeFloats, decodedFrames)
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
            track = state.currentTrack,
            encoding = state.currentFormat.encoding,
            bytes = combinedBytes,
            alignedByteCount = alignedLen,
            directBuffer = state.directBuffer,
            floatBuffer = state.floatBuffer,
            linearGain = effectiveLinearGain,
        )
        if (bytesWritten > 0) {
            val frameStart = state.totalFramesWrittenToTrack
            val writtenFrames = (bytesWritten / state.bytesPerFrame).toLong()
            state.totalFramesWrittenToTrack += writtenFrames
            state.streamFramesWrittenToTrack += writtenFrames
            // Strictly read-only, lifecycle-gated spectrum side-tap AFTER AudioTrack write
            // (100% Bit-Perfect safe; zero overhead when off-screen, backgrounded, or disabled).
            if (spectrumTapActive.get()) {
                ensureActiveSpectrumTap(state.sampleRate, frameStart)?.appendPcmBytesReadOnly(
                    encoding = state.currentFormat.encoding,
                    srcBytes = combinedBytes,
                    byteOffset = 0,
                    byteCount = bytesWritten,
                    channels = state.channels,
                    trackFrameAfterWrite = state.totalFramesWrittenToTrack,
                )
            }
        }
        accumulateActivePlayTime(state, forceFlush = false)
    }

    /**
     * Publishes hardware-clocked playback position (~20 Hz) to [_playbackPositionMs] and triggers
     * background gapless pre-loading when entering the final ~8 seconds.
     */
    private fun publishPlaybackPositionAndPreload(state: PlaybackLoopState) {
        val nowNano = System.nanoTime()
        if (nowNano - state.lastUiUpdateNano < 50_000_000L) return

        state.lastUiUpdateNano = nowNano
        val headFrames = readUnsignedPlaybackHeadFrames(state.currentTrack)
        val deltaFrames = (headFrames - state.headBaseFrames).coerceAtLeast(0L)
        val computedPos = state.seekBaseMs + (deltaFrames * 1000L) / state.sampleRate.toLong()
        val clampedPos = if (state.durationMs > 0L) computedPos.coerceIn(0L, state.durationMs) else computedPos
        _playbackPositionMs.value = clampedPos

        val nextReq = queuedNextRequest
        if (GaplessTransitionPolicy.shouldStartPreload(
                gaplessEnabled = gaplessEnabled,
                hasNextTrack = nextReq != null,
                isAlreadyPreloadingOrReady = isNextPreloadedOrLoading(nextReq?.uri),
                currentPositionMs = clampedPos,
                durationMs = state.durationMs,
            )
        ) {
            triggerPreloadAsync(nextReq, state.expectedGeneration)
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
