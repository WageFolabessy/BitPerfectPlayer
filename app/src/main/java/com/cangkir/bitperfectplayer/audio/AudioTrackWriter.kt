package com.cangkir.bitperfectplayer.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.cangkir.bitperfectplayer.diagnostics.LocalDiagnosticLogger
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal data class ConfiguredAudioTrack(
    val track: AudioTrack,
    val actualSampleRateHz: Int,
    val actualEncodingLabel: String,
    val isPinnedToPreferredUsbDevice: Boolean = false,
)

/**
 * Internal collaborator encapsulating [AudioTrack] creation/configuration
 * (`PERFORMANCE_MODE_NONE`, frame-aligned buffer sizing, API 31+ PCM encoding guards), idempotent
 * lifecycle calls (`safePlay`, `safePause`, `safeFlush`, `safeStopAndRelease`), natural EOS buffer
 * draining, unsigned hardware playback-head reads, and zero-allocation blocking PCM writes.
 */
internal class AudioTrackWriter(
    private val playbackAudioAttributes: AudioAttributes,
    private val stopWorkerFlag: AtomicBoolean,
    private val releasedFlag: AtomicBoolean,
    private val isPausedFlag: AtomicBoolean,
    private val pendingSeekMs: AtomicLong,
    private val loadGeneration: AtomicLong,
    private val diagnosticLogger: LocalDiagnosticLogger? = null,
    private val safetyModeProvider: () -> AudioOutputMode = { AudioOutputMode.SAFE_VOLUME_MODE },
    private val isUsbConnectedProvider: () -> Boolean = { false },
) : AudioSpectrumTap {
    // Strictly read-only, lifecycle-gated PCM tap (~192 KB) for the radial FFT spectrum visualizer.
    // Allocated lazily ONLY when active; released immediately when disabled, backgrounded, or stopped.
    private val spectrumTapActive = AtomicBoolean(false)

    @Volatile
    private var spectrumPcmTap: SpectrumPcmTap? = null

    fun asSinkFactory(routeAndMixerManager: AudioRouteAndMixerManager): AudioOutputSinkFactory = object : AudioOutputSinkFactory {
        override fun tryCreateSinkLocked(pcmFormat: DecodedPcmFormat): Result<AudioOutputSink> =
            tryCreateConfiguredAudioTrackLocked(pcmFormat, routeAndMixerManager).map { configured ->
                AndroidAudioOutputSink(
                    track = configured.track,
                    actualSampleRateHz = configured.actualSampleRateHz,
                    actualEncodingLabel = configured.actualEncodingLabel,
                    isPinnedToPreferredUsbDevice = configured.isPinnedToPreferredUsbDevice,
                    writer = this@AudioTrackWriter,
                )
            }

        override fun isGenerationActive(expectedGeneration: Long): Boolean = this@AudioTrackWriter.isGenerationActive(expectedGeneration)

        override fun applyPlaybackThreadPriority() = this@AudioTrackWriter.applyPlaybackThreadPriority()

        override fun sleepUninterrupted(durationMs: Long): Boolean = this@AudioTrackWriter.sleepUninterrupted(durationMs)
    }

    fun isWorkerActive(): Boolean = !stopWorkerFlag.get() && !releasedFlag.get()

    fun isGenerationActive(expectedGeneration: Long): Boolean =
        !stopWorkerFlag.get() && !releasedFlag.get() && loadGeneration.get() == expectedGeneration

    fun isTrackHealthy(track: AudioTrack?): Boolean = runCatching {
        track != null && track.state == AudioTrack.STATE_INITIALIZED
    }.getOrDefault(false)

    /**
     * Elevates the PCM decode/write worker thread to `THREAD_PRIORITY_URGENT_AUDIO` (with safe
     * fallback to `THREAD_PRIORITY_AUDIO` if denied by platform/test runtime). Must NOT be
     * followed by `Thread.setPriority(...)`, which can lower the Linux nice priority.
     */
    fun applyPlaybackThreadPriority() {
        val urgentApplied = runCatching {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        }.isSuccess
        if (!urgentApplied) {
            runCatching {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            }
        }
    }

    override fun setSpectrumTapActive(active: Boolean) {
        val changed = spectrumTapActive.getAndSet(active) != active
        if (!active && changed) {
            spectrumPcmTap = null
        }
    }

    override fun releaseSpectrumTap() {
        spectrumPcmTap = null
    }

    override fun deactivateAndReleaseSpectrumTap() {
        spectrumTapActive.set(false)
        spectrumPcmTap = null
    }

    override fun resetSpectrumTap(sampleRateHz: Int, trackFrameCursor: Long) {
        if (spectrumTapActive.get()) {
            val tap = spectrumPcmTap ?: SpectrumPcmTap().also { spectrumPcmTap = it }
            tap.reset(sampleRateHz, trackFrameCursor)
        } else if (spectrumPcmTap != null) {
            spectrumPcmTap = null
        }
    }

    override fun resetSpectrumTapIfPresent(sampleRateHz: Int, trackFrameCursor: Long) {
        spectrumPcmTap?.reset(sampleRateHz, trackFrameCursor)
    }

    override fun tapPcmBytesReadOnly(
        sampleRateHz: Int,
        frameStart: Long,
        encoding: PcmEncodingType,
        srcBytes: ByteArray,
        byteCount: Int,
        channels: Int,
        trackFrameAfterWrite: Long,
    ) {
        if (!spectrumTapActive.get()) return
        ensureActiveSpectrumTap(sampleRateHz, frameStart)?.appendPcmBytesReadOnly(
            encoding = encoding,
            srcBytes = srcBytes,
            byteOffset = 0,
            byteCount = byteCount,
            channels = channels,
            trackFrameAfterWrite = trackFrameAfterWrite,
        )
    }

    override fun tapInterleavedFloatsReadOnly(
        sampleRateHz: Int,
        frameStart: Long,
        srcFloats: FloatArray,
        frameCount: Int,
        channels: Int,
        trackFrameAfterWrite: Long,
    ) {
        if (!spectrumTapActive.get()) return
        ensureActiveSpectrumTap(sampleRateHz, frameStart)?.appendInterleavedFloatsReadOnly(
            srcFloats = srcFloats,
            frameCount = frameCount,
            channels = channels,
            trackFrameAfterWrite = trackFrameAfterWrite,
        )
    }

    override fun sampleCurrentSpectrumWindow(sink: AudioOutputSink?, isPlayingState: Boolean, outMonoSamples: FloatArray): Int {
        val isEnginePlaying = isPlayingState && !isPausedFlag.get() && !releasedFlag.get()
        if (!spectrumTapActive.get() || !isEnginePlaying) {
            return 0
        }
        val tap = spectrumPcmTap ?: return 0
        val activeSink = sink ?: return 0
        if (!activeSink.isPlaying()) return 0
        val headFrame = activeSink.readPlaybackHeadFrames()
        return tap.sampleWindowAtTrackFrame(headFrame, outMonoSamples)
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

    fun createConfiguredAudioTrackLocked(
        pcmFormat: DecodedPcmFormat,
        routeAndMixerManager: AudioRouteAndMixerManager,
    ): ConfiguredAudioTrack {
        val androidEncoding = toAndroidAudioEncoding(pcmFormat.encoding)
        val channelMask = toChannelOutMask(pcmFormat.channelCount)
        val usbDevice = routeAndMixerManager.prepareOutputDeviceAndNegotiateMixerLocked(
            pcmFormat = pcmFormat,
            androidEncoding = androidEncoding,
            channelMask = channelMask,
            encodingNameResolver = ::androidEncodingName,
        )

        val bytesPerFrame = pcmFormat.bytesPerFrame.coerceAtLeast(1)
        val reportedMinBuf = AudioTrack.getMinBufferSize(pcmFormat.sampleRateHz, channelMask, androidEncoding)
        val isSafeInternalSpeaker = safetyModeProvider() == AudioOutputMode.SAFE_VOLUME_MODE &&
            usbDevice == null &&
            routeAndMixerManager.mapConnectionType(routeAndMixerManager.activeOutputDevice) ==
            OutputConnectionType.INTERNAL_SPEAKER
        val alignedBufSize = PcmStreamRules.computeAudioTrackBufferSizeBytes(
            reportedMinBufferSize = reportedMinBuf,
            sampleRateHz = pcmFormat.sampleRateHz,
            bytesPerFrame = bytesPerFrame,
            useSafeSpeakerHeadroom = isSafeInternalSpeaker,
        )

        val audioFormat = AudioFormat.Builder()
            .setSampleRate(pcmFormat.sampleRateHz)
            .setChannelMask(channelMask)
            .setEncoding(androidEncoding)
            .build()

        // PERFORMANCE_MODE_NONE avoids forcing fast-mixer 48,000 Hz resampling on USB HAL paths.
        var trackBuilt = false
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(playbackAudioAttributes)
                .setAudioFormat(audioFormat)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(alignedBufSize)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_NONE)
                .build()
                .also { trackBuilt = true }
        } finally {
            if (!trackBuilt) {
                routeAndMixerManager.clearPreferredMixerAttributesOnApi34(usbDevice)
            }
        }

        val bindPreferredUsb = usbDevice != null && routeAndMixerManager.shouldBindPreferredUsbDevice(hasUsbSink = true)
        if (bindPreferredUsb) {
            runCatching { track.preferredDevice = usbDevice }
        }

        val actualEncodingInt = track.audioFormat
        val actualSampleRateHz = track.sampleRate
        val actualEncodingLabel = androidEncodingName(actualEncodingInt)

        Log.d(
            TAG,
            "AudioTrack configured: decoder=${pcmFormat.decoderName}, decodedEncoding=${pcmFormat.encoding}, " +
                "audioTrackEncoding=$actualEncodingLabel (const=$actualEncodingInt), " +
                "sampleRate=${track.sampleRate}Hz, channels=${pcmFormat.channelCount}, " +
                "bytesPerFrame=$bytesPerFrame, bufferBytes=$alignedBufSize, " +
                "pinnedUsb=$bindPreferredUsb, mixer=${routeAndMixerManager.activeNegotiatedMixerBehavior}",
        )
        diagnosticLogger?.logAudio("AUDIOTRACK_CONFIG") {
            linkedMapOf(
                "sr" to actualSampleRateHz,
                "ch" to pcmFormat.channelCount,
                "enc" to actualEncodingLabel,
                "decodedEnc" to pcmFormat.encoding.name,
                "bufBytes" to alignedBufSize,
                "bufFrames" to track.bufferSizeInFrames,
                "usb" to (usbDevice != null),
                "pinnedUsb" to bindPreferredUsb,
                "mixer" to routeAndMixerManager.activeNegotiatedMixerBehavior.name,
            )
        }
        return ConfiguredAudioTrack(
            track = track,
            actualSampleRateHz = actualSampleRateHz,
            actualEncodingLabel = actualEncodingLabel,
            isPinnedToPreferredUsbDevice = bindPreferredUsb,
        )
    }

    fun readUnderrunCount(track: AudioTrack?): Int = runCatching {
        if (track != null && track.state == AudioTrack.STATE_INITIALIZED) track.underrunCount else 0
    }.getOrDefault(0)

    fun tryCreateConfiguredAudioTrackLocked(
        pcmFormat: DecodedPcmFormat,
        routeAndMixerManager: AudioRouteAndMixerManager,
    ): Result<ConfiguredAudioTrack> = try {
        Result.success(createConfiguredAudioTrackLocked(pcmFormat, routeAndMixerManager))
    } catch (e: UnsupportedOperationException) {
        Result.failure(e)
    } catch (e: IllegalArgumentException) {
        Result.failure(e)
    } catch (e: IllegalStateException) {
        Result.failure(e)
    } catch (e: SecurityException) {
        Result.failure(e)
    }

    fun safePlay(track: AudioTrack): Boolean {
        val startMs = SystemClock.elapsedRealtime()
        val ok = runCatching {
            if (track.state != AudioTrack.STATE_INITIALIZED) return@runCatching false
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                track.play()
            }
            track.playState == AudioTrack.PLAYSTATE_PLAYING
        }.getOrDefault(false)
        val elapsedMs = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(0L)
        if (AudioStartPathPolicy.shouldRecordSlowAudioStage(elapsedMs)) {
            val sr = runCatching { track.sampleRate }.getOrDefault(0)
            diagnosticLogger?.logPerf("SLOW_AUDIO_STAGE") {
                linkedMapOf(
                    "stage" to "audioTrackPlay",
                    "elapsedMs" to elapsedMs,
                    "ok" to ok,
                    "sr" to sr,
                    "mode" to safetyModeProvider().name,
                    "usb" to isUsbConnectedProvider(),
                )
            }
        }
        return ok
    }

    fun safePause(track: AudioTrack) {
        val startMs = SystemClock.elapsedRealtime()
        runCatching {
            if (track.state == AudioTrack.STATE_INITIALIZED && track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                track.pause()
            }
        }
        val elapsedMs = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(0L)
        if (AudioStartPathPolicy.shouldRecordSlowAudioStage(elapsedMs)) {
            val sr = runCatching { track.sampleRate }.getOrDefault(0)
            diagnosticLogger?.logPerf("SLOW_AUDIO_STAGE") {
                linkedMapOf(
                    "stage" to "audioTrackPause",
                    "elapsedMs" to elapsedMs,
                    "sr" to sr,
                    "mode" to safetyModeProvider().name,
                    "usb" to isUsbConnectedProvider(),
                )
            }
        }
    }

    fun safeFlush(track: AudioTrack) {
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
     * Immediately unblocks any audio worker thread waiting inside `AudioTrack.write(..., WRITE_BLOCKING)`
     * before joining the worker thread and releasing the track.
     */
    fun safeUnblockForShutdown(track: AudioTrack) {
        runCatching {
            if (track.state == AudioTrack.STATE_INITIALIZED) {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                }
                track.flush()
                if (track.playState != AudioTrack.PLAYSTATE_STOPPED) {
                    track.stop()
                }
            }
        }
    }

    /**
     * Lowers `AudioTrack.startThresholdInFrames` on API 31+ right before live turntable scratch so
     * AudioFlinger begins rendering audibly within `~65 ms` after `flush()`, and returns the
     * actual active start threshold in frames (falling back to `bufferSizeInFrames` on API 26–30).
     */
    fun configureScratchStartThreshold(track: AudioTrack, desiredHeadroomFrames: Int): Int = runCatching {
        if (track.state != AudioTrack.STATE_INITIALIZED) return@runCatching 0
        val capacity = track.bufferCapacityInFrames.coerceAtLeast(1)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val target = desiredHeadroomFrames.coerceIn(256.coerceAtMost(capacity), capacity)
            track.startThresholdInFrames = target
            track.startThresholdInFrames
        } else {
            track.bufferSizeInFrames
        }
    }.getOrDefault(0)

    /**
     * Restores the default full-buffer `startThresholdInFrames` on API 31+ when exiting live
     * turntable scratch back to normal streaming playback.
     */
    fun restoreDefaultStartThreshold(track: AudioTrack) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        runCatching {
            if (track.state == AudioTrack.STATE_INITIALIZED) {
                val capacity = track.bufferCapacityInFrames.coerceAtLeast(1)
                track.startThresholdInFrames = track.bufferSizeInFrames.coerceIn(1, capacity)
            }
        }
    }

    /**
     * Cleanly idempotent AudioTrack teardown: avoids duplicate `stop()` / `pause()` calls when the
     * track is already paused, flushed, or stopped.
     */
    fun safeStopAndRelease(track: AudioTrack) {
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

    fun readUnsignedPlaybackHeadFrames(track: AudioTrack): Long =
        runCatching { track.playbackHeadPosition.toLong() and 0xFFFFFFFFL }.getOrDefault(0L)

    fun sleepUninterrupted(durationMs: Long): Boolean = try {
        Thread.sleep(durationMs)
        true
    } catch (_: InterruptedException) {
        false
    }

    /**
     * Cleanly drains any remaining PCM frames buffered inside [track] upon natural EOS so the last
     * ~100–300 ms of audio is never truncated by an abrupt `pause() + flush()`.
     * Returns the highest observed playback head frame count (preserving tail play-count accounting
     * even if the OEM HAL resets `playbackHeadPosition` to `0` upon entering `PLAYSTATE_STOPPED`).
     */
    fun drainAudioTrackCleanly(
        track: AudioTrack,
        totalFramesWritten: Long,
        sampleRateHz: Int,
        expectedGeneration: Long = loadGeneration.get(),
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
        var drainCompleted = false
        while (!drainCompleted && canContinueDrainingTrack(deadlineNano, expectedGeneration)) {
            val head = readUnsignedPlaybackHeadFrames(track)
            if (head < headBefore) {
                // HAL finished draining and reset playbackHeadPosition to 0 upon PLAYSTATE_STOPPED.
                maxReachedHead = maxOf(maxReachedHead, totalFramesWritten)
                drainCompleted = true
            } else {
                if (head > maxReachedHead) {
                    maxReachedHead = head
                }
                if (head == lastHead) {
                    stagnantTicks++
                } else {
                    lastHead = head
                    stagnantTicks = 0
                }
                // ~120 ms stationary head (8 ticks) or reaching totalFramesWritten -> HAL drain complete
                drainCompleted = head >= totalFramesWritten || stagnantTicks >= 8 || !sleepUninterrupted(15L)
            }
        }
        return maxReachedHead
    }

    private fun canContinueDrainingTrack(deadlineNano: Long, expectedGeneration: Long): Boolean {
        val activeForDrain = isGenerationActive(expectedGeneration) && !isPausedFlag.get() && pendingSeekMs.get() < 0L
        return activeForDrain && System.nanoTime() < deadlineNano
    }

    /**
     * Writes every byte of an aligned PCM chunk to [AudioTrack], looping cleanly on partial writes,
     * applying optional Safe-Mode-only ReplayGain with zero per-buffer GC allocations when
     * [linearGain] != `1.0f`. Returns total bytes written.
     */
    fun writeAlignedPcmFully(
        track: AudioTrack,
        encoding: PcmEncodingType,
        bytes: ByteArray,
        alignedByteCount: Int,
        directBuffer: ByteBuffer,
        floatBuffer: FloatArray,
        linearGain: Float,
        expectedGeneration: Long = loadGeneration.get(),
    ): Int {
        val applyGain = ReplayGainProcessor.needsSampleScaling(linearGain)
        if (encoding == PcmEncodingType.PCM_FLOAT) {
            return writeFloatPcmFully(
                track = track,
                bytes = bytes,
                alignedByteCount = alignedByteCount,
                floatBuffer = floatBuffer,
                applyGain = applyGain,
                linearGain = linearGain,
                expectedGeneration = expectedGeneration,
            )
        }
        if (applyGain) {
            applyIntegerPcmReplayGain(encoding, bytes, alignedByteCount, linearGain)
        }
        return writeDirectBufferPcmFully(track, bytes, alignedByteCount, directBuffer, expectedGeneration)
    }

    private fun writeFloatPcmFully(
        track: AudioTrack,
        bytes: ByteArray,
        alignedByteCount: Int,
        floatBuffer: FloatArray,
        applyGain: Boolean,
        linearGain: Float,
        expectedGeneration: Long,
    ): Int {
        var byteOffset = 0
        var totalBytesWritten = 0
        while (byteOffset < alignedByteCount && canContinueWrite(expectedGeneration)) {
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
            val floatsWritten = writeFloatArrayChunk(track, floatBuffer, totalFloats, expectedGeneration)
            totalBytesWritten += floatsWritten * 4
            if (floatsWritten < totalFloats) return totalBytesWritten
            byteOffset += totalFloats * 4
        }
        return totalBytesWritten
    }

    private fun writeFloatArrayChunk(track: AudioTrack, floatBuffer: FloatArray, totalFloats: Int, expectedGeneration: Long): Int {
        var floatOffset = 0
        var zeroWriteStartMs = -1L
        var zeroWrites = 0
        while (floatOffset < totalFloats && canContinueWrite(expectedGeneration)) {
            val writeStartMs = SystemClock.elapsedRealtime()
            val written = track.write(
                floatBuffer,
                floatOffset,
                totalFloats - floatOffset,
                AudioTrack.WRITE_BLOCKING,
            )
            val writeMs = (SystemClock.elapsedRealtime() - writeStartMs).coerceAtLeast(0L)
            if (written < 0) {
                throw classifyWriteFailure("AudioTrack float write failed with code $written", written)
            }
            if (written == 0) {
                zeroWrites++
                if (isPausedFlag.get() || !canContinueWrite(expectedGeneration)) return floatOffset
                zeroWriteStartMs = checkZeroWriteStall(track, zeroWriteStartMs, zeroWrites)
                if (!sleepUninterrupted(2L)) return floatOffset
            } else {
                logWriteStallIfNeeded(track, writeMs, written * 4, zeroWrites)
                zeroWriteStartMs = -1L
                zeroWrites = 0
                floatOffset += written
            }
        }
        return floatOffset
    }

    private fun applyIntegerPcmReplayGain(encoding: PcmEncodingType, bytes: ByteArray, alignedByteCount: Int, linearGain: Float) {
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

    private fun writeDirectBufferPcmFully(
        track: AudioTrack,
        bytes: ByteArray,
        alignedByteCount: Int,
        directBuffer: ByteBuffer,
        expectedGeneration: Long,
    ): Int {
        var offset = 0
        var totalBytesWritten = 0
        while (offset < alignedByteCount && canContinueWrite(expectedGeneration)) {
            val chunkLen = minOf(alignedByteCount - offset, directBuffer.capacity())
            directBuffer.clear()
            directBuffer.put(bytes, offset, chunkLen)
            directBuffer.flip()

            var zeroWriteStartMs = -1L
            var zeroWrites = 0
            while (directBuffer.hasRemaining() && canContinueWrite(expectedGeneration)) {
                val writeStartMs = SystemClock.elapsedRealtime()
                val written = track.write(
                    directBuffer,
                    directBuffer.remaining(),
                    AudioTrack.WRITE_BLOCKING,
                )
                val writeMs = (SystemClock.elapsedRealtime() - writeStartMs).coerceAtLeast(0L)
                if (written < 0) {
                    throw classifyWriteFailure("AudioTrack ByteBuffer write failed with code $written", written)
                }
                if (written == 0) {
                    zeroWrites++
                    if (isPausedFlag.get() || !canContinueWrite(expectedGeneration)) return totalBytesWritten
                    zeroWriteStartMs = checkZeroWriteStall(track, zeroWriteStartMs, zeroWrites)
                    if (!sleepUninterrupted(2L)) return totalBytesWritten
                } else {
                    logWriteStallIfNeeded(track, writeMs, written, zeroWrites)
                    zeroWriteStartMs = -1L
                    zeroWrites = 0
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
    fun writeNormalizedFloatsToTrack(
        track: AudioTrack,
        encoding: PcmEncodingType,
        srcFloats: FloatArray,
        frameCount: Int,
        channels: Int,
        bytesPerFrame: Int,
        scratchBytes: ByteArray,
        directBuffer: ByteBuffer,
        expectedGeneration: Long = loadGeneration.get(),
    ): Int {
        if (frameCount <= 0 || channels <= 0) return 0
        val totalFloats = frameCount * channels

        if (encoding == PcmEncodingType.PCM_FLOAT || track.audioFormat == AudioFormat.ENCODING_PCM_FLOAT) {
            return writeScratchFloatChunk(track, srcFloats, totalFloats, expectedGeneration) / channels
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
        return writeDirectBufferPcmFully(track, targetBytes, encodedBytes, directBuffer, expectedGeneration) / bytesPerFrame
    }

    private fun writeScratchFloatChunk(track: AudioTrack, srcFloats: FloatArray, totalFloats: Int, expectedGeneration: Long): Int {
        var floatOffset = 0
        var zeroWriteStartMs = -1L
        var zeroWrites = 0
        while (floatOffset < totalFloats && canContinueWrite(expectedGeneration)) {
            val writeStartMs = SystemClock.elapsedRealtime()
            val written = track.write(
                srcFloats,
                floatOffset,
                totalFloats - floatOffset,
                AudioTrack.WRITE_BLOCKING,
            )
            val writeMs = (SystemClock.elapsedRealtime() - writeStartMs).coerceAtLeast(0L)
            if (written < 0) {
                throw classifyWriteFailure("AudioTrack float scratch write failed with code $written", written)
            }
            if (written == 0) {
                zeroWrites++
                if (isPausedFlag.get() || !canContinueWrite(expectedGeneration)) return floatOffset
                zeroWriteStartMs = checkZeroWriteStall(track, zeroWriteStartMs, zeroWrites)
                if (!sleepUninterrupted(2L)) return floatOffset
            } else {
                logWriteStallIfNeeded(track, writeMs, written * 4, zeroWrites)
                zeroWriteStartMs = -1L
                zeroWrites = 0
                floatOffset += written
            }
        }
        return floatOffset
    }

    private fun logWriteStallIfNeeded(track: AudioTrack, writeMs: Long, bytes: Int, zeroWrites: Int) {
        val slowWrite = AudioStartPathPolicy.isSlowBlockingWrite(writeMs)
        if (!slowWrite && zeroWrites <= 0) return
        val sr = runCatching { track.sampleRate }.getOrDefault(0)
        val modeName = safetyModeProvider().name
        val usb = isUsbConnectedProvider()
        diagnosticLogger?.logPerf("AUDIOTRACK_WRITE_STALL") {
            linkedMapOf(
                "writeMs" to writeMs,
                "bytes" to bytes,
                "zeroWrites" to zeroWrites,
                "sr" to sr,
                "mode" to modeName,
                "usb" to usb,
            )
        }
        if (slowWrite) {
            diagnosticLogger?.logPerf("SLOW_AUDIO_STAGE") {
                linkedMapOf(
                    "stage" to "writeBlocking",
                    "elapsedMs" to writeMs,
                    "bytes" to bytes,
                    "zeroWrites" to zeroWrites,
                    "sr" to sr,
                    "mode" to modeName,
                    "usb" to usb,
                )
            }
        }
    }

    private fun canContinueWrite(expectedGeneration: Long): Boolean = isGenerationActive(expectedGeneration) && pendingSeekMs.get() < 0L

    private fun classifyWriteFailure(message: String, errorCode: Int): AudioDecodeException = when (errorCode) {
        AudioTrack.ERROR_DEAD_OBJECT,
        AudioTrack.ERROR_INVALID_OPERATION,
        -> AudioDecodeException.DeadAudioTrack(message)
        else -> AudioDecodeException.IoError(message)
    }

    private fun checkZeroWriteStall(track: AudioTrack, zeroWriteStartMs: Long, zeroWrites: Int): Long {
        val nowMs = SystemClock.elapsedRealtime()
        val startMs = if (zeroWriteStartMs < 0L) nowMs else zeroWriteStartMs
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            throw AudioDecodeException.DeadAudioTrack("AudioTrack uninitialized during write")
        }
        val stalledMs = (nowMs - startMs).coerceAtLeast(0L)
        if (stalledMs >= PlaybackSeekAndRecoveryPolicy.MAX_ZERO_WRITE_STALL_MS) {
            logWriteStallIfNeeded(track, stalledMs, 0, zeroWrites)
            throw AudioDecodeException.DeadAudioTrack(
                "AudioTrack stalled writing 0 bytes for $stalledMs ms",
            )
        }
        return startMs
    }

    fun toAndroidAudioEncoding(encoding: PcmEncodingType): Int {
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

    fun toChannelOutMask(channelCount: Int): Int = when (channelCount) {
        1 -> AudioFormat.CHANNEL_OUT_MONO
        2 -> AudioFormat.CHANNEL_OUT_STEREO
        4 -> AudioFormat.CHANNEL_OUT_QUAD
        6 -> AudioFormat.CHANNEL_OUT_5POINT1
        8 -> AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
        else -> AudioFormat.CHANNEL_OUT_STEREO
    }

    fun androidEncodingName(encoding: Int): String {
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

    companion object {
        private const val TAG = "BitPerfectAudio"
    }
}
