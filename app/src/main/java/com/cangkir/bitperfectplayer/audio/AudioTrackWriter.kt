package com.cangkir.bitperfectplayer.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal data class ConfiguredAudioTrack(val track: AudioTrack, val actualSampleRateHz: Int, val actualEncodingLabel: String)

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
) {
    // Strictly read-only, lifecycle-gated PCM tap (~192 KB) for the radial FFT spectrum visualizer.
    // Allocated lazily ONLY when active; released immediately when disabled, backgrounded, or stopped.
    private val spectrumTapActive = AtomicBoolean(false)

    @Volatile
    private var spectrumPcmTap: SpectrumPcmTap? = null

    fun isWorkerActive(): Boolean = !stopWorkerFlag.get() && !releasedFlag.get()

    fun setSpectrumTapActive(active: Boolean) {
        val changed = spectrumTapActive.getAndSet(active) != active
        if (!active && changed) {
            spectrumPcmTap = null
        }
    }

    fun releaseSpectrumTap() {
        spectrumPcmTap = null
    }

    fun deactivateAndReleaseSpectrumTap() {
        spectrumTapActive.set(false)
        spectrumPcmTap = null
    }

    fun resetSpectrumTap(sampleRateHz: Int, trackFrameCursor: Long) {
        if (spectrumTapActive.get()) {
            val tap = spectrumPcmTap ?: SpectrumPcmTap().also { spectrumPcmTap = it }
            tap.reset(sampleRateHz, trackFrameCursor)
        } else if (spectrumPcmTap != null) {
            spectrumPcmTap = null
        }
    }

    fun resetSpectrumTapIfPresent(sampleRateHz: Int, trackFrameCursor: Long) {
        spectrumPcmTap?.reset(sampleRateHz, trackFrameCursor)
    }

    fun tapPcmBytesReadOnly(
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

    fun tapInterleavedFloatsReadOnly(
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

    fun sampleCurrentSpectrumWindow(track: AudioTrack?, isPlayingState: Boolean, outMonoSamples: FloatArray): Int {
        val isEnginePlaying = isPlayingState && !isPausedFlag.get() && !releasedFlag.get()
        if (!spectrumTapActive.get() || !isEnginePlaying) {
            return 0
        }
        val tap = spectrumPcmTap ?: return 0
        val activeTrack = track ?: return 0
        val isTrackPlaying = runCatching {
            activeTrack.state == AudioTrack.STATE_INITIALIZED && activeTrack.playState == AudioTrack.PLAYSTATE_PLAYING
        }.getOrDefault(false)
        if (!isTrackPlaying) return 0
        val headFrame = readUnsignedPlaybackHeadFrames(activeTrack)
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
        val alignedBufSize = PcmStreamRules.computeAudioTrackBufferSizeBytes(
            reportedMinBufferSize = reportedMinBuf,
            sampleRateHz = pcmFormat.sampleRateHz,
            bytesPerFrame = bytesPerFrame,
        )

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
        val actualSampleRateHz = track.sampleRate
        val actualEncodingLabel = androidEncodingName(actualEncodingInt)

        Log.d(
            TAG,
            "AudioTrack configured: decoder=${pcmFormat.decoderName}, decodedEncoding=${pcmFormat.encoding}, " +
                "audioTrackEncoding=$actualEncodingLabel (const=$actualEncodingInt), " +
                "sampleRate=${track.sampleRate}Hz, channels=${pcmFormat.channelCount}, " +
                "bytesPerFrame=$bytesPerFrame, bufferBytes=$alignedBufSize, " +
                "mixer=${routeAndMixerManager.activeNegotiatedMixerBehavior}",
        )
        return ConfiguredAudioTrack(
            track = track,
            actualSampleRateHz = actualSampleRateHz,
            actualEncodingLabel = actualEncodingLabel,
        )
    }

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

    fun safePlay(track: AudioTrack) {
        runCatching {
            if (track.state == AudioTrack.STATE_INITIALIZED && track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                track.play()
            }
        }
    }

    fun safePause(track: AudioTrack) {
        runCatching {
            if (track.state == AudioTrack.STATE_INITIALIZED && track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                track.pause()
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
    fun drainAudioTrackCleanly(track: AudioTrack, totalFramesWritten: Long, sampleRateHz: Int): Long {
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
        while (!drainCompleted && canContinueDrainingTrack(deadlineNano)) {
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

    private fun canContinueDrainingTrack(deadlineNano: Long): Boolean {
        val activeForDrain = isWorkerActive() && !isPausedFlag.get() && pendingSeekMs.get() < 0L
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
            )
        }
        if (applyGain) {
            applyIntegerPcmReplayGain(encoding, bytes, alignedByteCount, linearGain)
        }
        return writeDirectBufferPcmFully(track, bytes, alignedByteCount, directBuffer)
    }

    private fun writeFloatPcmFully(
        track: AudioTrack,
        bytes: ByteArray,
        alignedByteCount: Int,
        floatBuffer: FloatArray,
        applyGain: Boolean,
        linearGain: Float,
    ): Int {
        var byteOffset = 0
        var totalBytesWritten = 0
        while (byteOffset < alignedByteCount && isWorkerActive()) {
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
            val floatsWritten = writeFloatArrayChunk(track, floatBuffer, totalFloats)
            totalBytesWritten += floatsWritten * 4
            if (floatsWritten < totalFloats) return totalBytesWritten
            byteOffset += totalFloats * 4
        }
        return totalBytesWritten
    }

    private fun writeFloatArrayChunk(track: AudioTrack, floatBuffer: FloatArray, totalFloats: Int): Int {
        var floatOffset = 0
        while (floatOffset < totalFloats && isWorkerActive()) {
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
                if (isPausedFlag.get()) return floatOffset
                Thread.sleep(2L)
            } else {
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

    private fun writeDirectBufferPcmFully(track: AudioTrack, bytes: ByteArray, alignedByteCount: Int, directBuffer: ByteBuffer): Int {
        var offset = 0
        var totalBytesWritten = 0
        while (offset < alignedByteCount && isWorkerActive()) {
            val chunkLen = minOf(alignedByteCount - offset, directBuffer.capacity())
            directBuffer.clear()
            directBuffer.put(bytes, offset, chunkLen)
            directBuffer.flip()

            while (directBuffer.hasRemaining() && isWorkerActive()) {
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
    fun writeNormalizedFloatsToTrack(
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
            return writeScratchFloatChunk(track, srcFloats, totalFloats) / channels
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
        return writeDirectBufferPcmFully(track, targetBytes, encodedBytes, directBuffer) / bytesPerFrame
    }

    private fun writeScratchFloatChunk(track: AudioTrack, srcFloats: FloatArray, totalFloats: Int): Int {
        var floatOffset = 0
        while (floatOffset < totalFloats && isWorkerActive()) {
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
                if (isPausedFlag.get()) return floatOffset
                Thread.sleep(2L)
            } else {
                floatOffset += written
            }
        }
        return floatOffset
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
