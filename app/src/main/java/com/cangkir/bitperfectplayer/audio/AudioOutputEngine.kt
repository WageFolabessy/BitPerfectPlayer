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
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

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
 * Observable real-time playback engine state, including hardware-clocked [currentPositionMs],
 * hearing safety state, and 6-stage [diagnostics].
 */
data class EnginePlaybackState(
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val activeTrackUri: Uri? = null,
    val currentPositionMs: Long = 0L,
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
 * - Runtime-adaptive Android 14+ (`API 34+`) `MIXER_BEHAVIOR_BIT_PERFECT` negotiation on USB audio outputs.
 * - Mandatory `clearPreferredMixerAttributes` cleanup on Safe Mode, stop/release, USB disconnect,
 *   and sample-rate/track transitions.
 * - API 31+ (`Build.VERSION_CODES.S`) PCM encoding guards with lossless float32 fallback on API 26–30.
 * - Zero app-side EQ, DSP, normalization, or crossfade.
 * - Hardware-clocked [EnginePlaybackState.currentPositionMs] via `AudioTrack.playbackHeadPosition`.
 * - Hearing-safety auto-pause on `ACTION_AUDIO_BECOMING_NOISY`, USB connect/disconnect, and route change.
 * - Full [AudioFocusRequest] integration for calls and system notifications.
 */
class AudioOutputEngine(
    context: Context,
    private val safetyController: AudioSafetyController = AudioSafetyController(),
    private val onTrackCompleted: (() -> Unit)? = null,
) {
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

    private val engineLock = ReentrantLock()
    private val isPausedFlag = AtomicBoolean(true)
    private val stopWorkerFlag = AtomicBoolean(false)
    private val releasedFlag = AtomicBoolean(false)
    private val pendingSeekMs = AtomicLong(-1L)

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
                runCatching { activeAudioTrack?.setVolume(1.0f) }
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

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            val addedSinks = addedDevices.filter { it.isSink }
            if (addedSinks.isEmpty()) return
            val usbAdded = addedSinks.firstOrNull { isUsbOutputDevice(it) }
            val chosen = usbAdded ?: detectPreferredOutputDevice() ?: addedSinks.first()
            activeOutputDevice = chosen
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
        activeOutputDevice = detectPreferredOutputDevice()
        registerAudioMonitors()
        publishDiagnostics()
    }

    fun loadTrack(request: EngineTrackRequest) {
        if (releasedFlag.get()) return
        engineLock.withLock {
            stopWorkerAndReleaseTrackLocked(clearMixer = true)
            currentRequest = request
            _state.update {
                it.copy(
                    status = PlaybackStatus.PREPARING,
                    activeTrackUri = request.uri,
                    currentPositionMs = request.startPositionMs.coerceAtLeast(0L),
                    errorMessage = null,
                    lastAutoPauseNotice = null,
                )
            }

            val decoder = try {
                MediaCodecAudioDecoder.open(
                    context = appContext,
                    uri = request.uri,
                    fileName = request.fileName,
                    mimeHint = request.mimeType,
                )
            } catch (e: AudioDecodeException) {
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

            if (request.startPositionMs > 0L) {
                runCatching { decoder.seekToMs(request.startPositionMs) }
            }

            val audioTrack = try {
                createConfiguredAudioTrackLocked(decoder.format)
            } catch (e: Exception) {
                runCatching { decoder.close() }
                activeDecoder = null
                clearPreferredMixerAttributesOnApi34(null)
                _state.update {
                    it.copy(
                        status = PlaybackStatus.ERROR,
                        errorMessage = "AudioTrack initialization failed: ${e.message}",
                    )
                }
                publishDiagnostics()
                return
            }

            activeDecoder = decoder
            activeAudioTrack = audioTrack
            _state.update {
                it.copy(
                    status = if (request.autoPlay) PlaybackStatus.PLAYING else PlaybackStatus.PAUSED,
                    activeTrackUri = request.uri,
                    currentPositionMs = decoder.currentPositionMs,
                    durationMs = decoder.format.durationMs,
                    errorMessage = null,
                )
            }
            publishDiagnostics()

            if (request.autoPlay) {
                startPlaybackWorkerLocked(initialSeekMs = decoder.currentPositionMs)
            } else {
                isPausedFlag.set(true)
            }
        }
    }

    fun play() {
        if (releasedFlag.get()) return
        engineLock.withLock {
            val decoder = activeDecoder ?: return
            val track = activeAudioTrack ?: run {
                val req = currentRequest ?: return
                loadTrack(req.copy(startPositionMs = _state.value.currentPositionMs, autoPlay = true))
                return
            }
            if (_state.value.status == PlaybackStatus.COMPLETED) {
                decoder.seekToMs(0L)
                _state.update { it.copy(currentPositionMs = 0L) }
            }
            if (!requestAudioFocusLocked()) {
                _state.update {
                    it.copy(
                        status = PlaybackStatus.PAUSED,
                        errorMessage = "Audio focus unavailable",
                    )
                }
                return
            }
            resumeAfterTransientFocusLoss = false
            isPausedFlag.set(false)
            runCatching { track.play() }
            _state.update {
                it.copy(
                    status = PlaybackStatus.PLAYING,
                    errorMessage = null,
                    lastAutoPauseNotice = null,
                )
            }
            if (playbackThread?.isAlive != true) {
                startPlaybackWorkerLocked(initialSeekMs = _state.value.currentPositionMs)
            }
        }
    }

    fun pause() {
        resumeAfterTransientFocusLoss = false
        pauseInternal(clearFocus = true, notice = null)
    }

    fun seekTo(positionMs: Long) {
        if (releasedFlag.get()) return
        val clamped = positionMs.coerceIn(0L, _state.value.durationMs.coerceAtLeast(0L))
        _state.update { it.copy(currentPositionMs = clamped) }
        if (playbackThread?.isAlive == true) {
            pendingSeekMs.set(clamped)
        } else {
            engineLock.withLock {
                val decoder = activeDecoder ?: return
                val actual = runCatching { decoder.seekToMs(clamped) }.getOrDefault(clamped)
                runCatching { activeAudioTrack?.flush() }
                _state.update { it.copy(currentPositionMs = actual) }
            }
        }
    }

    fun stop() {
        engineLock.withLock {
            resumeAfterTransientFocusLoss = false
            stopWorkerAndReleaseTrackLocked(clearMixer = true)
            abandonAudioFocusLocked()
            _state.update {
                it.copy(
                    status = PlaybackStatus.IDLE,
                    currentPositionMs = 0L,
                    errorMessage = null,
                )
            }
            publishDiagnostics()
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
        unregisterAudioMonitors()
        engineLock.withLock {
            stopWorkerAndReleaseTrackLocked(clearMixer = true)
            abandonAudioFocusLocked()
        }
    }

    private fun handleSafetyCommand(command: SafetyCommand) {
        when (command.type) {
            SafetyEventType.AUTO_PAUSE -> {
                if (command.requiresClearPreferredMixerAttributes) {
                    clearPreferredMixerAttributesOnApi34(null)
                }
                resumeAfterTransientFocusLoss = false
                val notice = when (command.autoPauseReason) {
                    AutoPauseReason.BECOMING_NOISY -> "Auto-paused: audio output disconnected or becoming noisy."
                    AutoPauseReason.USB_DISCONNECTED -> "Auto-paused: USB audio device disconnected (Safe Mode restored)."
                    AutoPauseReason.USB_CONNECTED_ROUTE_CHANGE -> "Auto-paused: USB audio device connected in Safe Mode."
                    AutoPauseReason.OUTPUT_ROUTE_CHANGED -> "Auto-paused: audio output route changed (Safe Mode active)."
                    null -> "Auto-paused for hearing safety."
                }
                pauseInternal(clearFocus = true, notice = notice)
                reconfigureTrackIfLoaded(autoResume = false)
            }
            SafetyEventType.MODE_CHANGED_TO_SAFE -> {
                if (command.requiresClearPreferredMixerAttributes) {
                    clearPreferredMixerAttributesOnApi34(null)
                }
                reconfigureTrackIfLoaded(autoResume = _state.value.isPlaying)
            }
            SafetyEventType.MODE_CHANGED_TO_BIT_PERFECT -> {
                // Re-open AudioTrack cleanly with API 34+ MIXER_BEHAVIOR_BIT_PERFECT negotiation.
                reconfigureTrackIfLoaded(autoResume = _state.value.isPlaying)
            }
            SafetyEventType.REQUIRES_SAFETY_WARNING, SafetyEventType.NONE -> {
                _state.update { it.copy(safetyState = safetyController.state()) }
                publishDiagnostics()
            }
        }
    }

    private fun reconfigureTrackIfLoaded(autoResume: Boolean) {
        val req = currentRequest
        if (req == null || activeDecoder == null) {
            _state.update { it.copy(safetyState = safetyController.state()) }
            publishDiagnostics()
            return
        }
        val resumePos = _state.value.currentPositionMs
        loadTrack(req.copy(startPositionMs = resumePos, autoPlay = autoResume))
    }

    private fun pauseInternal(clearFocus: Boolean, notice: String?) {
        engineLock.withLock {
            isPausedFlag.set(true)
            runCatching { activeAudioTrack?.pause() }
            if (clearFocus) {
                abandonAudioFocusLocked()
            }
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

        val minBuf = AudioTrack.getMinBufferSize(pcmFormat.sampleRateHz, channelMask, androidEncoding)
            .coerceAtLeast(pcmFormat.bytesPerFrame * 1024)
        val rawBufSize = maxOf(minBuf * 2, pcmFormat.bytesPerFrame * (pcmFormat.sampleRateHz / 20))
        val alignedBufSize = ((rawBufSize + pcmFormat.bytesPerFrame - 1) / pcmFormat.bytesPerFrame) * pcmFormat.bytesPerFrame

        val audioFormat = AudioFormat.Builder()
            .setSampleRate(pcmFormat.sampleRateHz)
            .setChannelMask(channelMask)
            .setEncoding(androidEncoding)
            .build()

        // PERFORMANCE_MODE_NONE avoids forcing fast-mixer 48 kHz resampling on USB HAL paths.
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

        audioTrackActualSampleRateHz = track.sampleRate
        audioTrackActualEncodingLabel = pcmFormat.encoding.displayLabel
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
                return MixerNegotiationResult(
                    behavior = NegotiatedMixerBehavior.DEFAULT_MIXER_FALLBACK,
                    summary = "Default System Mixer (No exact bit-perfect match for ${pcmFormat.sampleRateHz} Hz / ${pcmFormat.encoding.displayLabel})",
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
                    summary = "MIXER_BEHAVIOR_BIT_PERFECT (${verified.format.sampleRate} Hz, ${pcmFormat.encoding.displayLabel})",
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

    private fun startPlaybackWorkerLocked(initialSeekMs: Long) {
        val decoder = activeDecoder ?: return
        val track = activeAudioTrack ?: return
        if (!requestAudioFocusLocked()) {
            _state.update { it.copy(status = PlaybackStatus.PAUSED, errorMessage = "Audio focus denied") }
            return
        }

        stopWorkerFlag.set(false)
        isPausedFlag.set(false)
        pendingSeekMs.set(-1L)
        runCatching { track.play() }

        val worker = Thread(
            {
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
                runPlaybackLoop(decoder, track, initialSeekMs)
            },
            "BitPerfectAudioThread",
        )
        playbackThread = worker
        worker.start()
    }

    private fun runPlaybackLoop(
        decoder: PcmAudioDecoder,
        track: AudioTrack,
        initialSeekMs: Long,
    ) {
        val sampleRate = decoder.format.sampleRateHz.coerceAtLeast(1)
        val durationMs = decoder.format.durationMs.coerceAtLeast(0L)
        val bufferSize = maxOf(8_192, decoder.format.bytesPerFrame * 1024)
        val pcmBuffer = ByteArray(bufferSize)
        val byteBuffer = ByteBuffer.wrap(pcmBuffer)

        var seekBaseMs = initialSeekMs.coerceAtLeast(0L)
        var headBaseFrames = readUnsignedPlaybackHeadFrames(track)
        var lastUiUpdateNano = 0L

        try {
            while (!stopWorkerFlag.get() && !releasedFlag.get()) {
                val requestedSeek = pendingSeekMs.getAndSet(-1L)
                if (requestedSeek >= 0L) {
                    val wasPlaying = !isPausedFlag.get()
                    runCatching { track.pause() }
                    runCatching { track.flush() }
                    val actualSeekMs = decoder.seekToMs(requestedSeek)
                    seekBaseMs = actualSeekMs
                    headBaseFrames = readUnsignedPlaybackHeadFrames(track)
                    _state.update { it.copy(currentPositionMs = actualSeekMs) }
                    if (wasPlaying) {
                        runCatching { track.play() }
                    }
                    continue
                }

                if (isPausedFlag.get()) {
                    try {
                        Thread.sleep(25L)
                    } catch (_: InterruptedException) {
                        break
                    }
                    continue
                }

                val bytesRead = decoder.readPcm(pcmBuffer)
                if (bytesRead < 0) {
                    // Wait briefly for remaining queued frames to finish playing out
                    val finalPos = durationMs.takeIf { it > 0L } ?: _state.value.currentPositionMs
                    _state.update {
                        it.copy(
                            status = PlaybackStatus.COMPLETED,
                            currentPositionMs = finalPos,
                        )
                    }
                    mainHandler.post { onTrackCompleted?.invoke() }
                    break
                }

                if (bytesRead == 0) {
                    try {
                        Thread.sleep(5L)
                    } catch (_: InterruptedException) {
                        break
                    }
                    continue
                }

                byteBuffer.position(0)
                byteBuffer.limit(bytesRead)
                val written = track.write(byteBuffer, bytesRead, AudioTrack.WRITE_BLOCKING)
                if (written < 0) {
                    throw AudioDecodeException.IoError("AudioTrack.write failed with code $written")
                }

                // Publish hardware-clocked playback position (~20 Hz) for smooth seek bar & synced lyrics.
                val nowNano = System.nanoTime()
                if (nowNano - lastUiUpdateNano >= 50_000_000L) {
                    lastUiUpdateNano = nowNano
                    val headFrames = readUnsignedPlaybackHeadFrames(track)
                    val deltaFrames = (headFrames - headBaseFrames).coerceAtLeast(0L)
                    val computedPos = seekBaseMs + (deltaFrames * 1000L) / sampleRate.toLong()
                    val clampedPos = if (durationMs > 0L) computedPos.coerceIn(0L, durationMs) else computedPos
                    _state.update { it.copy(currentPositionMs = clampedPos) }
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
        }
    }

    private fun stopWorkerAndReleaseTrackLocked(clearMixer: Boolean) {
        stopWorkerFlag.set(true)
        isPausedFlag.set(true)

        val track = activeAudioTrack
        activeAudioTrack = null
        if (track != null) {
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.stop() }
        }

        val thread = playbackThread
        playbackThread = null
        if (thread != null && thread != Thread.currentThread()) {
            thread.interrupt()
            runCatching { thread.join(600L) }
        }

        if (track != null) {
            runCatching { track.release() }
        }

        val decoder = activeDecoder
        activeDecoder = null
        if (decoder != null) {
            runCatching { decoder.close() }
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

    private fun readUnsignedPlaybackHeadFrames(track: AudioTrack): Long =
        runCatching { track.playbackHeadPosition.toLong() and 0xFFFFFFFFL }.getOrDefault(0L)
}
