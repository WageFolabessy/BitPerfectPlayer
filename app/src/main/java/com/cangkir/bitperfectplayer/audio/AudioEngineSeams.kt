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
import com.cangkir.bitperfectplayer.diagnostics.LocalDiagnosticLogger
import com.cangkir.bitperfectplayer.lyrics.FileChannelSeekableByteSource
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Shared atomic worker/lifecycle flags owned by [AudioOutputEngine] and observed across
 * [AudioOutputSinkFactory], [GaplessPreloadController], and [TurntableScratchRunner] without
 * inventing extra locks.
 */
internal class EngineRuntimeFlags {
    val loadGeneration = AtomicLong(0L)
    val isPausedFlag = AtomicBoolean(true)
    val stopWorkerFlag = AtomicBoolean(false)
    val releasedFlag = AtomicBoolean(false)
    val pendingSeekMs = AtomicLong(PendingSeekCoalescer.NO_PENDING_SEEK)
}

/**
 * Thin seam wrapping an active PCM output track (production: Android [AudioTrack] via
 * [AndroidAudioOutputSink]; JVM coordination tests: deterministic in-memory fake sink).
 */
internal interface AudioOutputSink {
    val actualSampleRateHz: Int
    val actualEncodingLabel: String
    val isPinnedToPreferredUsbDevice: Boolean

    fun isHealthy(): Boolean
    fun isPlaying(): Boolean
    fun play(): Boolean
    fun pause()
    fun flush()
    fun unblockForShutdown()
    fun stopAndRelease()
    fun setVolume(volume: Float)
    fun readPlaybackHeadFrames(): Long
    fun readUnderrunCount(): Int
    fun configureScratchStartThreshold(desiredHeadroomFrames: Int): Int
    fun restoreDefaultStartThreshold()
    fun drainCleanly(totalFramesWritten: Long, sampleRateHz: Int, expectedGeneration: Long): Long
    fun writeAlignedPcm(
        encoding: PcmEncodingType,
        bytes: ByteArray,
        alignedByteCount: Int,
        directBuffer: ByteBuffer,
        floatBuffer: FloatArray,
        linearGain: Float,
        expectedGeneration: Long,
    ): Int

    fun writeNormalizedFloats(
        encoding: PcmEncodingType,
        srcFloats: FloatArray,
        frameCount: Int,
        channels: Int,
        scratchBytes: ByteArray,
        directBuffer: ByteBuffer,
        expectedGeneration: Long,
    ): Int
}

/**
 * Thin factory/runtime seam around [AudioOutputSink] creation and worker thread scheduling.
 */
internal interface AudioOutputSinkFactory {
    fun tryCreateSinkLocked(pcmFormat: DecodedPcmFormat): Result<AudioOutputSink>
    fun isGenerationActive(expectedGeneration: Long): Boolean
    fun applyPlaybackThreadPriority()
    fun sleepUninterrupted(durationMs: Long): Boolean
}

/**
 * Strictly read-only, lifecycle-gated spectrum tap contract implemented by [AudioTrackWriter] in
 * production and [NoOpAudioSpectrumTap] in non-visualizer tests.
 */
internal interface AudioSpectrumTap {
    fun setSpectrumTapActive(active: Boolean)
    fun releaseSpectrumTap()
    fun deactivateAndReleaseSpectrumTap()
    fun resetSpectrumTap(sampleRateHz: Int, trackFrameCursor: Long)
    fun resetSpectrumTapIfPresent(sampleRateHz: Int, trackFrameCursor: Long)
    fun tapPcmBytesReadOnly(
        sampleRateHz: Int,
        frameStart: Long,
        encoding: PcmEncodingType,
        srcBytes: ByteArray,
        byteCount: Int,
        channels: Int,
        trackFrameAfterWrite: Long,
    )

    fun tapInterleavedFloatsReadOnly(
        sampleRateHz: Int,
        frameStart: Long,
        srcFloats: FloatArray,
        frameCount: Int,
        channels: Int,
        trackFrameAfterWrite: Long,
    )

    fun sampleCurrentSpectrumWindow(sink: AudioOutputSink?, isPlayingState: Boolean, outMonoSamples: FloatArray): Int
}

internal object NoOpAudioSpectrumTap : AudioSpectrumTap {
    override fun setSpectrumTapActive(active: Boolean) = Unit
    override fun releaseSpectrumTap() = Unit
    override fun deactivateAndReleaseSpectrumTap() = Unit
    override fun resetSpectrumTap(sampleRateHz: Int, trackFrameCursor: Long) = Unit
    override fun resetSpectrumTapIfPresent(sampleRateHz: Int, trackFrameCursor: Long) = Unit
    override fun tapPcmBytesReadOnly(
        sampleRateHz: Int,
        frameStart: Long,
        encoding: PcmEncodingType,
        srcBytes: ByteArray,
        byteCount: Int,
        channels: Int,
        trackFrameAfterWrite: Long,
    ) = Unit

    override fun tapInterleavedFloatsReadOnly(
        sampleRateHz: Int,
        frameStart: Long,
        srcFloats: FloatArray,
        frameCount: Int,
        channels: Int,
        trackFrameAfterWrite: Long,
    ) = Unit

    override fun sampleCurrentSpectrumWindow(sink: AudioOutputSink?, isPlayingState: Boolean, outMonoSamples: FloatArray): Int = 0
}

/**
 * Thin seam wrapping PCM decoder opening and header-only ReplayGain tag reading so
 * [TrackLoadCoordinator] and [GaplessPreloadController] can run with deterministic fakes in JVM tests.
 */
internal interface PcmDecoderFactory {
    @Throws(AudioDecodeException::class)
    fun open(request: EngineTrackRequest, isCancelled: () -> Boolean): PcmAudioDecoder

    fun readReplayGainMetadata(uri: Uri, fileName: String, mimeType: String?): ReplayGainMetadata?
}

/**
 * Callbacks exposed by [AudioOutputEngine] to its [AudioRouteControl] implementation.
 */
internal data class AudioRouteCallbacks(
    val onSafetyCommand: (SafetyCommand) -> Unit,
    val onDiagnosticsChanged: () -> Unit,
    val onFocusLossPause: () -> Unit,
    val onFocusGainResume: () -> Unit,
    val onDuckSafeModeVolume: (Float) -> Unit,
    val isCurrentlyPlaying: () -> Boolean,
    val isReleased: () -> Boolean,
    val currentEngineStatusName: () -> String,
    val isPlaybackActiveOrStarting: () -> Boolean,
    val onPausedSafeModeRouteRefreshed: (coalesceDiagnostics: Boolean) -> Unit,
)

/**
 * Narrow internal contract implemented by [AudioRouteAndMixerManager] for routing, audio focus,
 * API 34+ preferred USB mixer cleanup, route flapping evaluation, and 6-stage diagnostic snapshots.
 */
internal interface AudioRouteControl {
    val activeNegotiatedMixerBehavior: NegotiatedMixerBehavior
    val hasAudioFocus: Boolean
    var resumeAfterTransientFocusLoss: Boolean
    val hasActivePreferredMixer: Boolean

    fun initializeRouteMonitoring()
    fun unregisterAudioMonitors()
    fun requestAudioFocusLocked(forceRequest: Boolean = false): Boolean
    fun abandonAudioFocusLocked(): Long
    fun clearPreferredMixerAttributes(async: Boolean)
    fun evaluatePlayResume(nowElapsedMs: Long, firstPlayRequestElapsedMs: Long): PlayResumeRouteGuardResult
    fun isUsbRouteActivelyFlapping(nowElapsedMs: Long): Boolean
    fun buildPendingTrackDiagnosticsSnapshot(
        safety: AudioSafetyState,
        container: String,
        codec: String,
        sampleRateHz: Int,
        bitDepth: Int?,
        replayGainResolution: ReplayGainResolution,
    ): AudioDiagnosticSnapshot

    fun buildDiagnosticSnapshot(
        safety: AudioSafetyState,
        activeFormat: DecodedPcmFormat?,
        audioTrackSampleRateHz: Int,
        audioTrackEncodingLabel: String,
        replayGainResolution: ReplayGainResolution,
    ): AudioDiagnosticSnapshot
}

internal fun interface EngineClock {
    fun elapsedRealtimeMs(): Long
}

internal interface EngineCallbackDispatcher {
    fun post(runnable: Runnable)
    fun postDelayed(runnable: Runnable, delayMs: Long)
    fun removeCallbacks(runnable: Runnable)
}

internal class HandlerCallbackDispatcher(private val handler: Handler) : EngineCallbackDispatcher {
    override fun post(runnable: Runnable) {
        handler.post(runnable)
    }

    override fun postDelayed(runnable: Runnable, delayMs: Long) {
        handler.postDelayed(runnable, delayMs)
    }

    override fun removeCallbacks(runnable: Runnable) {
        handler.removeCallbacks(runnable)
    }
}

internal data class AudioSinkBackend(val sinkFactory: AudioOutputSinkFactory, val spectrumTap: AudioSpectrumTap)

internal data class AudioEngineDependencies(
    val routeControlFactory: (AudioRouteCallbacks) -> AudioRouteControl,
    val sinkBackendFactory: (EngineRuntimeFlags, AudioRouteControl) -> AudioSinkBackend,
    val decoderFactory: PcmDecoderFactory,
    val callbackDispatcher: EngineCallbackDispatcher,
    val clock: EngineClock = EngineClock { SystemClock.elapsedRealtime() },
    val controlExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "BitPerfectEngineControl").apply { isDaemon = true }
    },
    val preloadExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "BitPerfectGaplessPreload").apply { isDaemon = true }
    },
    val isDebugBuild: Boolean = BuildConfig.DEBUG,
    val slowStageDebugLogger: ((String) -> Unit)? = { msg -> Log.w(TAG, msg) },
) {
    companion object {
        private const val TAG = "BitPerfectAudio"

        fun createAndroid(
            context: Context,
            safetyController: AudioSafetyController,
            diagnosticLogger: LocalDiagnosticLogger?,
        ): AudioEngineDependencies {
            val appContext = context.applicationContext
            val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val mainHandler = Handler(Looper.getMainLooper())
            val playbackAudioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            return AudioEngineDependencies(
                routeControlFactory = { callbacks ->
                    AudioRouteAndMixerManager(
                        appContext = appContext,
                        audioManager = audioManager,
                        mainHandler = mainHandler,
                        playbackAudioAttributes = playbackAudioAttributes,
                        safetyController = safetyController,
                        onSafetyCommand = callbacks.onSafetyCommand,
                        onDiagnosticsChanged = callbacks.onDiagnosticsChanged,
                        onFocusLossPause = callbacks.onFocusLossPause,
                        onFocusGainResume = callbacks.onFocusGainResume,
                        onDuckSafeModeVolume = callbacks.onDuckSafeModeVolume,
                        isCurrentlyPlaying = callbacks.isCurrentlyPlaying,
                        isReleased = callbacks.isReleased,
                        diagnosticLogger = diagnosticLogger,
                        currentEngineStatusName = callbacks.currentEngineStatusName,
                        isPlaybackActiveOrStarting = callbacks.isPlaybackActiveOrStarting,
                        onPausedSafeModeRouteRefreshed = callbacks.onPausedSafeModeRouteRefreshed,
                    )
                },
                sinkBackendFactory = { flags, routeControl ->
                    val routeManager = routeControl as AudioRouteAndMixerManager
                    val writer = AudioTrackWriter(
                        playbackAudioAttributes = playbackAudioAttributes,
                        stopWorkerFlag = flags.stopWorkerFlag,
                        releasedFlag = flags.releasedFlag,
                        isPausedFlag = flags.isPausedFlag,
                        pendingSeekMs = flags.pendingSeekMs,
                        loadGeneration = flags.loadGeneration,
                        diagnosticLogger = diagnosticLogger,
                        safetyModeProvider = { safetyController.state().mode },
                        isUsbConnectedProvider = { safetyController.state().isUsbAudioConnected },
                    )
                    AudioSinkBackend(
                        sinkFactory = writer.asSinkFactory(routeManager),
                        spectrumTap = writer,
                    )
                },
                decoderFactory = AndroidPcmDecoderFactory(appContext, diagnosticLogger),
                callbackDispatcher = HandlerCallbackDispatcher(mainHandler),
            )
        }
    }
}

internal class AndroidAudioOutputSink(
    private val track: AudioTrack,
    override val actualSampleRateHz: Int,
    override val actualEncodingLabel: String,
    override val isPinnedToPreferredUsbDevice: Boolean,
    private val writer: AudioTrackWriter,
) : AudioOutputSink {

    override fun isHealthy(): Boolean = writer.isTrackHealthy(track)

    override fun isPlaying(): Boolean = runCatching {
        track.state == AudioTrack.STATE_INITIALIZED && track.playState == AudioTrack.PLAYSTATE_PLAYING
    }.getOrDefault(false)

    override fun play(): Boolean = writer.safePlay(track)

    override fun pause() = writer.safePause(track)

    override fun flush() = writer.safeFlush(track)

    override fun unblockForShutdown() = writer.safeUnblockForShutdown(track)

    override fun stopAndRelease() = writer.safeStopAndRelease(track)

    override fun setVolume(volume: Float) {
        runCatching { track.setVolume(volume) }
    }

    override fun readPlaybackHeadFrames(): Long = writer.readUnsignedPlaybackHeadFrames(track)

    override fun readUnderrunCount(): Int = writer.readUnderrunCount(track)

    override fun configureScratchStartThreshold(desiredHeadroomFrames: Int): Int =
        writer.configureScratchStartThreshold(track, desiredHeadroomFrames)

    override fun restoreDefaultStartThreshold() = writer.restoreDefaultStartThreshold(track)

    override fun drainCleanly(totalFramesWritten: Long, sampleRateHz: Int, expectedGeneration: Long): Long =
        writer.drainAudioTrackCleanly(track, totalFramesWritten, sampleRateHz, expectedGeneration)

    override fun writeAlignedPcm(
        encoding: PcmEncodingType,
        bytes: ByteArray,
        alignedByteCount: Int,
        directBuffer: ByteBuffer,
        floatBuffer: FloatArray,
        linearGain: Float,
        expectedGeneration: Long,
    ): Int = writer.writeAlignedPcmFully(
        track = track,
        encoding = encoding,
        bytes = bytes,
        alignedByteCount = alignedByteCount,
        directBuffer = directBuffer,
        floatBuffer = floatBuffer,
        linearGain = linearGain,
        expectedGeneration = expectedGeneration,
    )

    override fun writeNormalizedFloats(
        encoding: PcmEncodingType,
        srcFloats: FloatArray,
        frameCount: Int,
        channels: Int,
        scratchBytes: ByteArray,
        directBuffer: ByteBuffer,
        expectedGeneration: Long,
    ): Int = writer.writeNormalizedFloatsToTrack(
        track = track,
        encoding = encoding,
        srcFloats = srcFloats,
        frameCount = frameCount,
        channels = channels,
        bytesPerFrame = (channels * encoding.bytesPerSample).coerceAtLeast(1),
        scratchBytes = scratchBytes,
        directBuffer = directBuffer,
        expectedGeneration = expectedGeneration,
    )
}

internal class AndroidPcmDecoderFactory(private val appContext: Context, private val diagnosticLogger: LocalDiagnosticLogger?) :
    PcmDecoderFactory {
    override fun open(request: EngineTrackRequest, isCancelled: () -> Boolean): PcmAudioDecoder = MediaCodecAudioDecoder.open(
        context = appContext,
        uri = request.uri,
        fileName = request.fileName,
        mimeHint = request.mimeType,
        diagnosticLogger = diagnosticLogger,
        isCancelled = isCancelled,
    )

    override fun readReplayGainMetadata(uri: Uri, fileName: String, mimeType: String?): ReplayGainMetadata? = runCatching {
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
}
