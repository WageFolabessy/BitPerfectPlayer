package com.cangkir.bitperfectplayer.audio

import android.media.AudioTrack
import android.os.SystemClock
import com.cangkir.bitperfectplayer.diagnostics.LocalDiagnosticLogger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import kotlin.math.roundToLong
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Mutable per-worker state container shared across `BitPerfectAudioThread` and
 * [TurntableScratchRunner] so every hot audio method stays compact (well below ART's 16,384 DEX
 * instruction JIT/AOT compiler ceiling) with zero per-buffer GC allocations or Ref-boxing overhead.
 */
internal class PlaybackLoopState(
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
    var alignedChunkBytes: ByteArray = pcmBuffer
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
    var pendingFlushResetGuard: Boolean = false
    var countHeadFrames: Long = initialHeadFrames
    var pendingPlayCountMs: Long = 0L
    var wasPausedInLoop: Boolean = false
    var lastUiUpdateNano: Long = 0L
    var scratchCursorFrame: Double = 0.0

    /**
     * If `AudioTrack.flush()` completed asynchronously after `play()` (dropping `hwHead` below
     * the stale pre-flush `headBaseFrames`), rebases all track-head counters cleanly to `hwHead`.
     * Never fires during gapless transitions (`pendingFlushResetGuard == false`).
     */
    fun reconcileAsyncFlushReset(hwHead: Long) {
        if (!pendingFlushResetGuard) return
        if (hwHead < headBaseFrames) {
            val safeHead = hwHead.coerceAtLeast(0L)
            headBaseFrames = safeHead
            totalFramesWrittenToTrack = safeHead + streamFramesWrittenToTrack
            countHeadFrames = safeHead
            pendingFlushResetGuard = false
        } else if (hwHead > headBaseFrames) {
            // HAL advanced monotonically past headBaseFrames without resetting to 0.
            pendingFlushResetGuard = false
        }
    }

    /**
     * Combines any sub-frame [remainder] with [bytesRead] bytes in [pcmBuffer], stores any new
     * trailing sub-frame bytes back into [remainder], sets [alignedChunkBytes], and returns the
     * frame-aligned byte count ready to tee/write. Zero wrapper allocations.
     */
    fun prepareAlignedPcmChunk(bytesRead: Int): Int {
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
        alignedChunkBytes = combinedBytes
        return alignedLen
    }

    fun accumulateActivePlayTime(headFrames: Long, forceFlush: Boolean, onActivePlaybackElapsedMs: ((Long) -> Unit)?) {
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

    fun resetStreamStateAfterSeek(actualSeekMs: Long, headFrames: Long) {
        remainderLen = 0
        seekBaseMs = actualSeekMs
        streamOriginMs = actualSeekMs
        activeRingBuffer?.reset(0L)
        streamFramesWrittenToTrack = 0L
        ringDrainCursorFrame = -1L
        exitFadeFramesRemaining = 0
        decoderEosDuringScratch = false
        pendingFlushResetGuard = headFrames > 0L
        headBaseFrames = headFrames
        totalFramesWrittenToTrack = headFrames
        countHeadFrames = headFrames
        lastUiUpdateNano = 0L
    }

    fun applyRouteHeadRebase(observedHeadFrames: Long) {
        val rebase = RouteChangeReconfigurePolicy.computeRouteHeadRebase(
            seekBaseMs = seekBaseMs,
            headBaseFrames = headBaseFrames,
            countHeadFrames = countHeadFrames,
            totalFramesWrittenToTrack = totalFramesWrittenToTrack,
            observedHeadFrames = observedHeadFrames,
            sampleRateHz = sampleRate,
        )
        seekBaseMs = rebase.seekBaseMs
        streamOriginMs = rebase.seekBaseMs
        headBaseFrames = rebase.headBaseFrames
        totalFramesWrittenToTrack = rebase.totalFramesWrittenToTrack
        streamFramesWrittenToTrack = rebase.streamFramesWrittenToTrack
        countHeadFrames = rebase.countHeadFrames
        pendingFlushResetGuard = rebase.armAsyncFlushResetGuard
        activeRingBuffer?.reset(0L)
        lastUiUpdateNano = 0L
    }

    fun transitionToSeamlessNextDecoder(nextDecoder: PcmAudioDecoder, nextRingBuffer: TurntablePcmRingBuffer?, currentHeadFrames: Long) {
        currentDecoder = nextDecoder
        observedFormatVersion = nextDecoder.formatVersion
        currentFormat = nextDecoder.format
        sampleRate = currentFormat.sampleRateHz.coerceAtLeast(1)
        durationMs = currentFormat.durationMs.coerceAtLeast(0L)
        bytesPerFrame = currentFormat.bytesPerFrame.coerceAtLeast(1)
        channels = currentFormat.channelCount.coerceIn(1, 8)
        activeRingBuffer = nextRingBuffer
        resampler = nextRingBuffer?.let { TurntableResampler(sampleRate, channels) }
        remainderLen = 0
        seekBaseMs = 0L
        streamOriginMs = 0L
        streamFramesWrittenToTrack = 0L
        ringDrainCursorFrame = -1L
        exitFadeFramesRemaining = 0
        decoderEosDuringScratch = false
        pendingFlushResetGuard = false
        // Track B begins playing at the exact hardware frame count already queued for Track A.
        headBaseFrames = totalFramesWrittenToTrack
        countHeadFrames = maxOf(currentHeadFrames, headBaseFrames)
        lastUiUpdateNano = 0L
    }

    fun pollMidStreamFormatChange(): DecodedPcmFormat? {
        val latestVersion = currentDecoder.formatVersion
        if (latestVersion == observedFormatVersion) return null
        observedFormatVersion = latestVersion
        val newFormat = currentDecoder.format
        val unchanged = newFormat.encoding == currentFormat.encoding &&
            newFormat.sampleRateHz == currentFormat.sampleRateHz &&
            newFormat.channelCount == currentFormat.channelCount
        return if (unchanged) null else newFormat
    }

    fun applyMidStreamRebuiltTrack(
        rebuiltTrack: AudioTrack,
        newFormat: DecodedPcmFormat,
        ringBuffer: TurntablePcmRingBuffer?,
        currentPositionMs: Long,
        headFrames: Long,
    ) {
        currentTrack = rebuiltTrack
        reconfigureBuffersForFormat(newFormat, ringBuffer)
        seekBaseMs = currentPositionMs
        streamOriginMs = seekBaseMs
        streamFramesWrittenToTrack = 0L
        ringDrainCursorFrame = -1L
        exitFadeFramesRemaining = 0
        decoderEosDuringScratch = false
        pendingFlushResetGuard = false
        headBaseFrames = headFrames
        totalFramesWrittenToTrack = headFrames
        countHeadFrames = headFrames
    }

    fun ensureSafeModeScratchBuffers(ringBuffer: TurntablePcmRingBuffer) {
        if (activeRingBuffer !== ringBuffer) {
            activeRingBuffer = ringBuffer
        }
        val existingResampler = resampler
        if (existingResampler == null || existingResampler.sampleRate != sampleRate || existingResampler.channels != channels) {
            resampler = TurntableResampler(sampleRate, channels)
        }
        val minTeeFloats = maxOf(bufferSize / currentFormat.encoding.bytesPerSample.coerceAtLeast(1), 4096)
        if (teeFloats.size < minTeeFloats) {
            teeFloats = FloatArray(minTeeFloats)
        }
        val minScratchFloats = scratchChunkFrames * channels
        if (scratchOutFloats.size < minScratchFloats) {
            scratchOutFloats = FloatArray(minScratchFloats)
        }
        val minScratchBytes = scratchChunkFrames * bytesPerFrame
        if (scratchOutBytes.size < minScratchBytes) {
            scratchOutBytes = ByteArray(minScratchBytes)
        }
    }

    fun reconfigureBuffersForFormat(newFormat: DecodedPcmFormat, ringBuffer: TurntablePcmRingBuffer?) {
        currentFormat = newFormat
        sampleRate = newFormat.sampleRateHz.coerceAtLeast(1)
        durationMs = newFormat.durationMs.coerceAtLeast(0L)
        bytesPerFrame = newFormat.bytesPerFrame.coerceAtLeast(1)
        channels = newFormat.channelCount.coerceIn(1, 8)
        bufferSize = PcmStreamRules.computeAlignedBufferSize(16_384, bytesPerFrame)
        pcmBuffer = ByteArray(bufferSize)
        alignedChunkBytes = pcmBuffer
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

/**
 * Internal collaborator owning the Safe-Mode-only lazy 8 MB [TurntablePcmRingBuffer] lifecycle,
 * pre-ReplayGain PCM ring-buffer teeing, normal PCM chunk writing, lock-free UI -> audio thread
 * scratch rate signaling, low-latency `~65 ms` (`sampleRate / 15`) fill-throttled varispeed/reverse
 * resampler execution, and click-free exit transition (Fast Seamless `1.0x` ring drain vs Deep
 * Rewind `seekToMs`).
 *
 * **Mandatory Bit-Perfect Invariant:** In [AudioOutputMode.BIT_PERFECT_OPT_IN], [turntableRingBuffer]
 * is never allocated (and immediately freed on mode switch/stop/release), [beginScratch] returns
 * `false`, and [teeNormalPcmIntoRingBufferIfSafeMode] performs zero work.
 */
internal class TurntableScratchRunner(
    private val safetyController: AudioSafetyController,
    private val trackWriter: AudioTrackWriter,
    private val preloadController: GaplessPreloadController,
    private val stopWorkerFlag: AtomicBoolean,
    private val releasedFlag: AtomicBoolean,
    private val isPausedFlag: AtomicBoolean,
    private val pendingSeekMs: AtomicLong,
    private val playbackPositionMs: MutableStateFlow<Long>,
    private val activeReplayGainLinear: () -> Float,
    private val onActivePlaybackElapsedMs: ((Long) -> Unit)?,
    private val onEndOfStream: (PlaybackLoopState) -> Boolean,
    private val onMidStreamFormatChanged: (PlaybackLoopState, DecodedPcmFormat) -> Boolean,
    private val diagnosticLogger: LocalDiagnosticLogger? = null,
) {
    private val turntableScratchRequested = AtomicBoolean(false)
    private val turntableTargetRateBits = AtomicInteger(0)
    private val turntableFallbackSeekMs = AtomicLong(-1L)
    private val pendingRouteHeadRebase = AtomicBoolean(false)

    @Volatile
    private var turntableRingBuffer: TurntablePcmRingBuffer? = null

    fun markRouteChangedWhilePaused() {
        pendingRouteHeadRebase.set(true)
    }

    fun isScratchRequested(): Boolean = turntableScratchRequested.get()

    fun beginScratch(isPlaying: Boolean, hasActiveTrack: Boolean): Boolean {
        val eligible = TurntableScratchPolicy.isLiveTurntableEligible(
            safetyMode = safetyController.state().mode,
            isPlaying = isPlaying && !isPausedFlag.get(),
            hasActiveTrack = hasActiveTrack && !stopWorkerFlag.get() && !releasedFlag.get(),
        )
        if (!eligible) return false
        turntableTargetRateBits.set(0f.toRawBits())
        turntableFallbackSeekMs.set(-1L)
        turntableScratchRequested.set(true)
        return true
    }

    fun updateScratchRate(playbackRate: Float) {
        if (!turntableScratchRequested.get()) return
        val clamped = TurntableSpeedController.clampPlaybackRate(playbackRate)
        turntableTargetRateBits.set(clamped.toRawBits())
    }

    fun endScratch(fallbackTargetMs: Long? = null) {
        if (!turntableScratchRequested.get()) return
        turntableFallbackSeekMs.set(fallbackTargetMs?.coerceAtLeast(0L) ?: -1L)
        turntableScratchRequested.set(false)
    }

    fun abortScratch() {
        turntableScratchRequested.set(false)
        turntableTargetRateBits.set(0f.toRawBits())
        turntableFallbackSeekMs.set(-1L)
    }

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

    fun resetRingBufferIfPresent(anchorFrame: Long = 0L) {
        turntableRingBuffer?.reset(anchorFrame)
    }

    fun releaseRingBuffer() {
        turntableRingBuffer = null
    }

    fun releaseRingBufferIfBitPerfect() {
        if (safetyController.state().mode == AudioOutputMode.BIT_PERFECT_OPT_IN) {
            turntableRingBuffer = null
        }
    }

    fun createInitialLoopState(
        expectedGeneration: Long,
        initialDecoder: PcmAudioDecoder,
        initialTrack: AudioTrack,
        initialSeekMs: Long,
    ): PlaybackLoopState {
        pendingRouteHeadRebase.set(false)
        val initialChannels = initialDecoder.format.channelCount.coerceIn(1, 8)
        val initialRing = ensureSafeModeRingBuffer(initialChannels)?.also { it.reset(0L) }
        return PlaybackLoopState(
            expectedGeneration = expectedGeneration,
            currentDecoder = initialDecoder,
            currentTrack = initialTrack,
            initialSeekMs = initialSeekMs,
            initialHeadFrames = trackWriter.readUnsignedPlaybackHeadFrames(initialTrack),
            initialRingBuffer = initialRing,
        ).also { state ->
            trackWriter.resetSpectrumTap(state.sampleRate, state.totalFramesWrittenToTrack)
        }
    }

    fun executeLoopStep(state: PlaybackLoopState): Boolean {
        if (!trackWriter.isGenerationActive(state.expectedGeneration)) return false
        val requestedSeek = pendingSeekMs.getAndSet(-1L)
        if (requestedSeek >= 0L) {
            handlePendingSeekInLoop(state, requestedSeek)
            return true
        }
        if (isPausedFlag.get()) {
            return handlePausedStepInLoop(state)
        }
        resumeFromPausedStepIfNeeded(state)

        return when {
            isScratchRequested() -> {
                processTurntableScratchStep(state)
                true
            }
            state.ringDrainCursorFrame >= 0L && processRingDrainStepInLoop(state) -> true
            else -> executeDecodeAndWriteStep(state)
        }
    }

    private fun executeDecodeAndWriteStep(state: PlaybackLoopState): Boolean {
        val bytesRead = if (state.decoderEosDuringScratch) -1 else state.currentDecoder.readPcm(state.pcmBuffer)
        if (!trackWriter.isGenerationActive(state.expectedGeneration)) return false
        if (bytesRead < 0) return onEndOfStream(state)
        val changedFormat = state.pollMidStreamFormatChange()
        if (changedFormat != null) return onMidStreamFormatChanged(state, changedFormat)
        return if (bytesRead == 0) {
            trackWriter.sleepUninterrupted(5L)
        } else {
            processNormalPcmChunkInLoop(state, bytesRead)
            true
        }
    }

    fun completeSeamlessGaplessTransition(state: PlaybackLoopState, nextDecoder: PcmAudioDecoder) {
        val nextChannels = nextDecoder.format.channelCount.coerceIn(1, 8)
        val nextRing = ensureSafeModeRingBuffer(nextChannels)?.also { it.reset(0L) }
        val currentHead = trackWriter.readUnsignedPlaybackHeadFrames(state.currentTrack)
        state.transitionToSeamlessNextDecoder(nextDecoder, nextRing, currentHead)
        if (trackWriter.isGenerationActive(state.expectedGeneration)) {
            playbackPositionMs.value = 0L
        }
    }

    fun completeMidStreamTrackRebuild(state: PlaybackLoopState, rebuiltTrack: AudioTrack, newFormat: DecodedPcmFormat) {
        val newChannels = newFormat.channelCount.coerceIn(1, 8)
        val ringBuf = ensureSafeModeRingBuffer(newChannels)?.also { it.reset(0L) }
        val headFrames = trackWriter.readUnsignedPlaybackHeadFrames(rebuiltTrack)
        state.applyMidStreamRebuiltTrack(rebuiltTrack, newFormat, ringBuf, playbackPositionMs.value, headFrames)
        trackWriter.resetSpectrumTapIfPresent(state.sampleRate, state.totalFramesWrittenToTrack)
    }

    fun drainTrackAtEndOfStream(state: PlaybackLoopState): Boolean {
        val drainedHeadFrames = trackWriter.drainAudioTrackCleanly(
            track = state.currentTrack,
            totalFramesWritten = state.totalFramesWrittenToTrack,
            sampleRateHz = state.sampleRate,
            expectedGeneration = state.expectedGeneration,
        )
        accumulateActivePlayTime(state, forceFlush = true, overrideHeadFrames = drainedHeadFrames)
        return trackWriter.isGenerationActive(state.expectedGeneration) && pendingSeekMs.get() >= 0L
    }

    fun accumulateActivePlayTime(state: PlaybackLoopState, forceFlush: Boolean, overrideHeadFrames: Long? = null) {
        val headFrames = overrideHeadFrames ?: trackWriter.readUnsignedPlaybackHeadFrames(state.currentTrack)
        state.reconcileAsyncFlushReset(headFrames)
        state.accumulateActivePlayTime(headFrames, forceFlush, onActivePlaybackElapsedMs)
    }

    fun publishPlaybackPositionAndPreload(state: PlaybackLoopState) {
        if (!trackWriter.isGenerationActive(state.expectedGeneration)) return
        val nowNano = System.nanoTime()
        if (nowNano - state.lastUiUpdateNano < 50_000_000L) return

        state.lastUiUpdateNano = nowNano
        val headFrames = trackWriter.readUnsignedPlaybackHeadFrames(state.currentTrack)
        state.reconcileAsyncFlushReset(headFrames)
        val deltaFrames = (headFrames - state.headBaseFrames).coerceAtLeast(0L)
        val computedPos = state.seekBaseMs + (deltaFrames * 1000L) / state.sampleRate.toLong()
        val clampedPos = if (state.durationMs > 0L) computedPos.coerceIn(0L, state.durationMs) else computedPos
        if (!trackWriter.isGenerationActive(state.expectedGeneration)) return
        playbackPositionMs.value = clampedPos

        preloadController.maybeTriggerPreloadForPosition(
            currentPositionMs = clampedPos,
            durationMs = state.durationMs,
            expectedGeneration = state.expectedGeneration,
        )
    }

    fun handlePausedStepInLoop(state: PlaybackLoopState): Boolean {
        if (!state.wasPausedInLoop) {
            accumulateActivePlayTime(state, forceFlush = true)
            state.wasPausedInLoop = true
        }
        return trackWriter.sleepUninterrupted(25L)
    }

    fun resumeFromPausedStepIfNeeded(state: PlaybackLoopState) {
        val routeRebaseNeeded = pendingRouteHeadRebase.getAndSet(false)
        if (state.wasPausedInLoop || routeRebaseNeeded) {
            val headFrames = trackWriter.readUnsignedPlaybackHeadFrames(state.currentTrack)
            if (routeRebaseNeeded) {
                val headBaseBefore = state.headBaseFrames
                state.applyRouteHeadRebase(headFrames)
                trackWriter.resetSpectrumTapIfPresent(state.sampleRate, state.totalFramesWrittenToTrack)
                diagnosticLogger?.logAudio("ROUTE_HEAD_REBASE") {
                    linkedMapOf(
                        "observedHead" to headFrames,
                        "headBaseBefore" to headBaseBefore,
                        "seekBaseMs" to state.seekBaseMs,
                        "rebasedHead" to state.headBaseFrames,
                        "armFlushGuard" to state.pendingFlushResetGuard,
                    )
                }
            } else {
                state.reconcileAsyncFlushReset(headFrames)
                state.countHeadFrames = maxOf(headFrames, state.headBaseFrames)
            }
            state.wasPausedInLoop = false
        }
    }

    fun handlePendingSeekInLoop(state: PlaybackLoopState, requestedSeekMs: Long) {
        if (!trackWriter.isGenerationActive(state.expectedGeneration)) return
        pendingRouteHeadRebase.set(false)
        val seekStartMs = SystemClock.elapsedRealtime()
        accumulateActivePlayTime(state, forceFlush = true)
        val wasPlaying = !isPausedFlag.get()
        trackWriter.safePause(state.currentTrack)
        trackWriter.safeFlush(state.currentTrack)
        val actualSeekMs = state.currentDecoder.seekToMs(requestedSeekMs)
        val headFrames = trackWriter.readUnsignedPlaybackHeadFrames(state.currentTrack)
        state.resetStreamStateAfterSeek(actualSeekMs, headFrames)
        trackWriter.resetSpectrumTapIfPresent(state.sampleRate, state.totalFramesWrittenToTrack)
        if (!trackWriter.isGenerationActive(state.expectedGeneration)) return
        playbackPositionMs.value = actualSeekMs
        if (wasPlaying && !isPausedFlag.get()) {
            if (!trackWriter.safePlay(state.currentTrack)) {
                throw AudioDecodeException.DeadAudioTrack("AudioTrack failed to restart after seek")
            }
        }
        preloadController.maybeTriggerPreloadForPosition(
            currentPositionMs = actualSeekMs,
            durationMs = state.durationMs,
            expectedGeneration = state.expectedGeneration,
        )
        diagnosticLogger?.logPerf("SEEK_LATENCY") {
            linkedMapOf(
                "seekMs" to (SystemClock.elapsedRealtime() - seekStartMs).coerceAtLeast(0L),
                "targetPosMs" to requestedSeekMs,
                "actualPosMs" to actualSeekMs,
                "wasPlaying" to wasPlaying,
            )
        }
    }

    fun processNormalPcmChunkInLoop(state: PlaybackLoopState, bytesRead: Int) {
        val alignedLen = state.prepareAlignedPcmChunk(bytesRead)
        if (alignedLen > 0) {
            val combinedBytes = state.alignedChunkBytes
            // Safe-Mode-only pre-ReplayGain ring-buffer tee (zero allocations; NEVER runs in Bit-Perfect mode).
            teeNormalPcmIntoRingBufferIfSafeMode(state, combinedBytes, alignedLen)

            // Mandatory Bit-Perfect Invariant: strictly bypass ReplayGain (gain = 1.0f) in BIT_PERFECT_OPT_IN.
            val effectiveLinearGain =
                if (safetyController.state().mode == AudioOutputMode.BIT_PERFECT_OPT_IN) {
                    1.0f
                } else {
                    activeReplayGainLinear()
                }
            val bytesWritten = trackWriter.writeAlignedPcmFully(
                track = state.currentTrack,
                encoding = state.currentFormat.encoding,
                bytes = combinedBytes,
                alignedByteCount = alignedLen,
                directBuffer = state.directBuffer,
                floatBuffer = state.floatBuffer,
                linearGain = effectiveLinearGain,
                expectedGeneration = state.expectedGeneration,
            )
            if (bytesWritten > 0) {
                val frameStart = state.totalFramesWrittenToTrack
                val writtenFrames = (bytesWritten / state.bytesPerFrame).toLong()
                state.totalFramesWrittenToTrack += writtenFrames
                state.streamFramesWrittenToTrack += writtenFrames
                // Strictly read-only, lifecycle-gated spectrum side-tap AFTER AudioTrack write.
                trackWriter.tapPcmBytesReadOnly(
                    sampleRateHz = state.sampleRate,
                    frameStart = frameStart,
                    encoding = state.currentFormat.encoding,
                    srcBytes = combinedBytes,
                    byteCount = bytesWritten,
                    channels = state.channels,
                    trackFrameAfterWrite = state.totalFramesWrittenToTrack,
                )
            }
            accumulateActivePlayTime(state, forceFlush = false)
        }
        publishPlaybackPositionAndPreload(state)
    }

    /**
     * Safe-Mode-only pre-ReplayGain ring-buffer tee (zero allocations; strictly no-op in
     * [AudioOutputMode.BIT_PERFECT_OPT_IN]).
     */
    fun teeNormalPcmIntoRingBufferIfSafeMode(state: PlaybackLoopState, alignedBytes: ByteArray, alignedLen: Int) {
        if (!ensureSafeModeLoopScratchReady(state)) return
        val activeRing = state.activeRingBuffer ?: return

        val neededFloats = alignedLen / state.currentFormat.encoding.bytesPerSample
        if (state.teeFloats.size < neededFloats) {
            state.teeFloats = FloatArray(neededFloats)
        }
        val decodedFloats = TurntablePcmCodec.decodePcmBytesToFloats(
            encoding = state.currentFormat.encoding,
            srcBytes = alignedBytes,
            byteOffset = 0,
            byteCount = alignedLen,
            destFloats = state.teeFloats,
        )
        val decodedFrames = decodedFloats / state.channels
        if (decodedFrames > 0) {
            activeRing.appendInterleavedFloats(state.teeFloats, decodedFrames)
        }
    }

    /**
     * Executes Safe-Mode-only live turntable scratch rendering using a start-threshold-aware
     * fill-throttle and [TurntableFlushHeadTracker] so post-`flush()` async `playbackHeadPosition`
     * resets or `startThresholdInFrames` floors can never deadlock into silence.
     */
    fun processTurntableScratchStep(state: PlaybackLoopState) {
        if (!ensureSafeModeLoopScratchReady(state)) {
            abortScratch()
            return
        }
        val ringBuf = state.activeRingBuffer
        val scratchResampler = state.resampler
        if (ringBuf == null || scratchResampler == null) {
            abortScratch()
            return
        }

        val scratchEntryElapsedMs = SystemClock.elapsedRealtime()
        // 1. Flush and freeze PlayCountTracker so scratching never inflates listening time.
        accumulateActivePlayTime(state, forceFlush = true)

        // 2. Anchor scratch cursor to the exact hardware playback head currently reaching the speaker.
        val hwHeadOnEntry = trackWriter.readUnsignedPlaybackHeadFrames(state.currentTrack)
        state.reconcileAsyncFlushReset(hwHeadOnEntry)
        val currentStreamHead = if (state.ringDrainCursorFrame >= 0L) {
            state.ringDrainCursorFrame
        } else {
            state.streamFramesWrittenToTrack
        }
        state.scratchCursorFrame = TurntableScratchPolicy.computeScratchEntryCursorFrame(
            currentStreamHeadFrame = currentStreamHead,
            totalFramesWrittenToTrack = state.totalFramesWrittenToTrack,
            hwPlaybackHeadFrames = hwHeadOnEntry,
            oldestAvailableFrame = ringBuf.oldestAvailableFrame,
            writeHeadFrame = ringBuf.writeHeadFrame,
        )

        // 3. Flush normal lookahead once, configure low-latency start threshold on API 31+, and
        //    anchor a flush-safe head tracker AFTER safePlay() so async resets never deadlock.
        trackWriter.safePause(state.currentTrack)
        trackWriter.safeFlush(state.currentTrack)
        val desiredHeadroomFrames = (state.sampleRate / 15).coerceAtLeast(512)
        val activeStartThreshold = trackWriter.configureScratchStartThreshold(state.currentTrack, desiredHeadroomFrames)
        val maxAheadFrames = TurntableScratchPolicy.computeEffectiveScratchMaxAheadFrames(
            sampleRateHz = state.sampleRate,
            trackStartThresholdFrames = activeStartThreshold,
            chunkFrames = state.scratchChunkFrames,
        )
        diagnosticLogger?.logAudio("TURNTABLE_STATE") {
            linkedMapOf(
                "action" to "enter",
                "sr" to state.sampleRate,
                "startThreshold" to activeStartThreshold,
                "maxAhead" to maxAheadFrames,
                "ringFrames" to (ringBuf.writeHeadFrame - ringBuf.oldestAvailableFrame),
            )
        }
        trackWriter.safePlay(state.currentTrack)
        val headTracker = TurntableFlushHeadTracker(
            initialObservedHead = trackWriter.readUnsignedPlaybackHeadFrames(state.currentTrack),
            maxAheadFrames = maxAheadFrames,
        )
        state.pendingFlushResetGuard = false
        state.headBaseFrames = headTracker.headOrigin
        state.totalFramesWrittenToTrack = headTracker.currentTrackWrittenFrame()
        state.countHeadFrames = headTracker.headOrigin
        trackWriter.resetSpectrumTapIfPresent(state.sampleRate, state.totalFramesWrittenToTrack)

        val initialRate = Float.fromBits(turntableTargetRateBits.get())
        scratchResampler.resetForScratchEntry(initialRate)
        val (minVirtualFrame, maxVirtualFrame) = TurntableScratchPolicy.computeVirtualFrameBounds(
            streamOriginMs = state.streamOriginMs,
            sampleRateHz = state.sampleRate,
            durationMs = state.durationMs,
        )

        while (canContinueTurntableScratch(state.expectedGeneration)) {
            val prevOrigin = headTracker.headOrigin
            val hwHead = trackWriter.readUnsignedPlaybackHeadFrames(state.currentTrack)
            val ahead = headTracker.computeAheadFrames(hwHead)
            if (headTracker.headOrigin != prevOrigin) {
                state.headBaseFrames = headTracker.headOrigin
                state.totalFramesWrittenToTrack = headTracker.currentTrackWrittenFrame()
                state.countHeadFrames = headTracker.headOrigin
                trackWriter.resetSpectrumTapIfPresent(state.sampleRate, state.totalFramesWrittenToTrack)
            }
            if (ahead > maxAheadFrames) {
                if (!trackWriter.sleepUninterrupted(2L)) break
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
                replayGainLinear = activeReplayGainLinear(),
            )
            state.scratchCursorFrame = step.nextCursorFrame

            val writtenFrames = writeScratchFloatsAndTapSpectrum(state, step.framesRendered)
            headTracker.onFramesWritten(writtenFrames)
            state.totalFramesWrittenToTrack = headTracker.currentTrackWrittenFrame()
            if (trackWriter.isGenerationActive(state.expectedGeneration)) {
                playbackPositionMs.value = computeClampedScratchPositionMs(state)
            }
        }

        exitTurntableScratchCleanly(state, ringBuf, scratchResampler, minVirtualFrame, maxVirtualFrame)
        diagnosticLogger?.logAudio("TURNTABLE_STATE") {
            linkedMapOf(
                "action" to "exit",
                "durationMs" to (SystemClock.elapsedRealtime() - scratchEntryElapsedMs).coerceAtLeast(0L),
                "ringDrain" to (state.ringDrainCursorFrame >= 0L),
            )
        }
    }

    private fun ensureSafeModeLoopScratchReady(state: PlaybackLoopState): Boolean {
        if (safetyController.state().mode != AudioOutputMode.SAFE_VOLUME_MODE) return false
        val ring = ensureSafeModeRingBuffer(state.channels) ?: return false
        state.ensureSafeModeScratchBuffers(ring)
        return true
    }

    /**
     * Drains one chunk of buffered `1.0x` post-scratch PCM frames from the ring buffer.
     * Returns `true` if a chunk was written (caller should continue loop), or `false` once fully drained.
     */
    fun processRingDrainStepInLoop(state: PlaybackLoopState): Boolean {
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
                        activeReplayGainLinear()
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
                accumulateActivePlayTime(state, false)
                publishPlaybackPositionAndPreload(state)
                return true
            }
        }
        state.streamFramesWrittenToTrack = ringBuf.writeHeadFrame
        state.ringDrainCursorFrame = -1L
        return false
    }

    private fun isSafeModeActivePlayback(expectedGeneration: Long): Boolean = trackWriter.isGenerationActive(expectedGeneration) &&
        !isPausedFlag.get() &&
        safetyController.state().mode == AudioOutputMode.SAFE_VOLUME_MODE

    private fun canContinueTurntableScratch(expectedGeneration: Long): Boolean =
        turntableScratchRequested.get() && pendingSeekMs.get() < 0L && isSafeModeActivePlayback(expectedGeneration)

    private fun canDecodeAheadForScratch(
        state: PlaybackLoopState,
        ringBuf: TurntablePcmRingBuffer,
        neededEndFrame: Long,
        reads: Int,
    ): Boolean {
        val needMoreFrames = ringBuf.writeHeadFrame <= neededEndFrame && reads < 16 && !state.decoderEosDuringScratch
        return needMoreFrames && trackWriter.isGenerationActive(state.expectedGeneration) && turntableScratchRequested.get()
    }

    private fun canPreFeedRingDrain(state: PlaybackLoopState, preFed: Int): Boolean {
        val readyToDrain = preFed < 2 &&
            state.ringDrainCursorFrame >= 0L &&
            trackWriter.isGenerationActive(state.expectedGeneration)
        return readyToDrain && !isPausedFlag.get() && !turntableScratchRequested.get()
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
        var emptyReads = 0
        var keepReading = true
        while (keepReading && canDecodeAheadForScratch(state, ringBuf, neededEndFrame, reads)) {
            reads++
            val br = state.currentDecoder.readPcm(state.pcmBuffer)
            when {
                state.currentDecoder.formatVersion != state.observedFormatVersion -> {
                    abortScratch()
                    keepReading = false
                }
                br < 0 -> {
                    state.decoderEosDuringScratch = true
                    keepReading = false
                }
                br == 0 -> {
                    emptyReads++
                    if (emptyReads >= 3 || !trackWriter.sleepUninterrupted(1L)) {
                        keepReading = false
                    }
                }
                else -> {
                    emptyReads = 0
                    val alLen = state.prepareAlignedPcmChunk(br)
                    if (alLen > 0) {
                        teeNormalPcmIntoRingBufferIfSafeMode(state, state.alignedChunkBytes, alLen)
                    }
                }
            }
        }
    }

    private fun computeClampedScratchPositionMs(state: PlaybackLoopState): Long = TurntableScratchPolicy.computeClampedScratchPositionMs(
        streamOriginMs = state.streamOriginMs,
        scratchCursorFrame = state.scratchCursorFrame,
        sampleRateHz = state.sampleRate,
        durationMs = state.durationMs,
    )

    private fun exitTurntableScratchCleanly(
        state: PlaybackLoopState,
        ringBuf: TurntablePcmRingBuffer,
        scratchResampler: TurntableResampler,
        minVirtualFrame: Double,
        maxVirtualFrame: Double,
    ) {
        trackWriter.restoreDefaultStartThreshold(state.currentTrack)
        val cleanExitToPlay = isSafeModeActivePlayback(state.expectedGeneration) && pendingSeekMs.get() < 0L
        if (!cleanExitToPlay) {
            handleInterruptedScratchExit(state, ringBuf)
            return
        }

        renderScratchExitFadeOut(state, ringBuf, scratchResampler, minVirtualFrame, maxVirtualFrame)

        val fallbackMs = turntableFallbackSeekMs.getAndSet(-1L)
        val targetMs = if (fallbackMs >= 0L) {
            if (state.durationMs > 0L) fallbackMs.coerceIn(0L, state.durationMs) else fallbackMs
        } else {
            computeClampedScratchPositionMs(state)
        }

        val cursorFrameLong = state.scratchCursorFrame.roundToLong()
        val canSeamlessDrain = TurntableScratchPolicy.canResumeViaSeamlessRingDrain(
            bufferedFrameCount = ringBuf.bufferedFrameCount,
            cursorFrameLong = cursorFrameLong,
            oldestAvailableFrame = ringBuf.oldestAvailableFrame,
            writeHeadFrame = ringBuf.writeHeadFrame,
            fallbackSeekMs = fallbackMs,
        )
        if (canSeamlessDrain) {
            resumeScratchViaRingDrain(state, scratchResampler, cursorFrameLong, targetMs)
        } else {
            resumeScratchViaDeepRewindSeek(state, targetMs)
        }
    }

    private fun handleInterruptedScratchExit(state: PlaybackLoopState, ringBuf: TurntablePcmRingBuffer) {
        turntableFallbackSeekMs.set(-1L)
        if (!trackWriter.isGenerationActive(state.expectedGeneration)) return
        val finalMs = computeClampedScratchPositionMs(state)
        playbackPositionMs.value = finalMs
        state.countHeadFrames = maxOf(trackWriter.readUnsignedPlaybackHeadFrames(state.currentTrack), state.headBaseFrames)

        val pausedWithoutPendingSeek = isPausedFlag.get() &&
            trackWriter.isGenerationActive(state.expectedGeneration) &&
            pendingSeekMs.get() < 0L
        if (!pausedWithoutPendingSeek) return

        val cursorLong = state.scratchCursorFrame.roundToLong()
        val canSeamlessDrain = TurntableScratchPolicy.canResumeViaSeamlessRingDrain(
            bufferedFrameCount = ringBuf.bufferedFrameCount,
            cursorFrameLong = cursorLong,
            oldestAvailableFrame = ringBuf.oldestAvailableFrame,
            writeHeadFrame = ringBuf.writeHeadFrame,
        )
        if (canSeamlessDrain) {
            state.ringDrainCursorFrame = cursorLong
            state.seekBaseMs = finalMs
            state.headBaseFrames = state.totalFramesWrittenToTrack
            state.countHeadFrames = maxOf(trackWriter.readUnsignedPlaybackHeadFrames(state.currentTrack), state.headBaseFrames)
        } else {
            pendingSeekMs.set(finalMs)
        }
    }

    private fun renderScratchExitFadeOut(
        state: PlaybackLoopState,
        ringBuf: TurntablePcmRingBuffer,
        scratchResampler: TurntableResampler,
        minVirtualFrame: Double,
        maxVirtualFrame: Double,
    ) {
        val fadeOutFrames = scratchResampler.exitFadeDurationFrames().coerceAtMost(state.scratchChunkFrames)
        if (scratchResampler.fadeGain <= 0.01f || fadeOutFrames <= 0) return

        val tailStep = scratchResampler.renderChunk(
            ringBuffer = ringBuf,
            targetRate = scratchResampler.currentRate,
            startCursorFrame = state.scratchCursorFrame,
            minVirtualFrame = minVirtualFrame,
            maxVirtualFrame = maxVirtualFrame,
            outFrames = fadeOutFrames,
            destFloats = state.scratchOutFloats,
            replayGainLinear = activeReplayGainLinear(),
            forceFadeOut = true,
        )
        state.scratchCursorFrame = tailStep.nextCursorFrame
        writeScratchFloatsAndTapSpectrum(state, tailStep.framesRendered)
    }

    private fun resumeScratchViaRingDrain(
        state: PlaybackLoopState,
        scratchResampler: TurntableResampler,
        cursorFrameLong: Long,
        targetMs: Long,
    ) {
        // Fast Seamless Path: zero MediaCodec.flush()! Immediately feed up to 2 chunks of 1.0x
        // ring drain so AudioTrack never starves or triggers restartIfDisabled on transition.
        state.ringDrainCursorFrame = cursorFrameLong
        state.exitFadeFramesRemaining = scratchResampler.exitFadeDurationFrames()
        state.seekBaseMs = targetMs
        state.headBaseFrames = state.totalFramesWrittenToTrack
        state.countHeadFrames = maxOf(trackWriter.readUnsignedPlaybackHeadFrames(state.currentTrack), state.headBaseFrames)
        state.lastUiUpdateNano = 0L
        if (trackWriter.isGenerationActive(state.expectedGeneration)) {
            playbackPositionMs.value = targetMs
        }

        var preFed = 0
        while (canPreFeedRingDrain(state, preFed)) {
            if (!processRingDrainStepInLoop(state)) break
            preFed++
        }
    }

    private fun resumeScratchViaDeepRewindSeek(state: PlaybackLoopState, targetMs: Long) {
        // Deep Rewind Path: rewound past oldestAvailableFrame -> single clean seekToMs.
        trackWriter.safePause(state.currentTrack)
        trackWriter.safeFlush(state.currentTrack)
        val actualSeekMs = state.currentDecoder.seekToMs(targetMs)
        val headFrames = trackWriter.readUnsignedPlaybackHeadFrames(state.currentTrack)
        state.resetStreamStateAfterSeek(actualSeekMs, headFrames)
        trackWriter.resetSpectrumTapIfPresent(state.sampleRate, state.totalFramesWrittenToTrack)
        if (!trackWriter.isGenerationActive(state.expectedGeneration)) return
        playbackPositionMs.value = actualSeekMs
        if (!isPausedFlag.get() && !trackWriter.safePlay(state.currentTrack)) {
            throw AudioDecodeException.DeadAudioTrack("AudioTrack failed to restart after scratch rewind seek")
        }
    }

    private fun writeScratchFloatsAndTapSpectrum(state: PlaybackLoopState, frameCount: Int): Int {
        val framesWritten = trackWriter.writeNormalizedFloatsToTrack(
            track = state.currentTrack,
            encoding = state.currentFormat.encoding,
            srcFloats = state.scratchOutFloats,
            frameCount = frameCount,
            channels = state.channels,
            bytesPerFrame = state.bytesPerFrame,
            scratchBytes = state.scratchOutBytes,
            directBuffer = state.directBuffer,
            expectedGeneration = state.expectedGeneration,
        )
        if (framesWritten > 0) {
            val frameStart = state.totalFramesWrittenToTrack
            state.totalFramesWrittenToTrack += framesWritten.toLong()
            trackWriter.tapInterleavedFloatsReadOnly(
                sampleRateHz = state.sampleRate,
                frameStart = frameStart,
                srcFloats = state.scratchOutFloats,
                frameCount = framesWritten,
                channels = state.channels,
                trackFrameAfterWrite = state.totalFramesWrittenToTrack,
            )
        }
        return framesWritten
    }
}
