package com.cangkir.bitperfectplayer.audio

import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sign

/**
 * Pure-Kotlin eligibility policy for Safe-Mode-only live turntable audio scratch DSP
 * (zero `android.*` / `androidx.*` imports).
 *
 * **Mandatory Bit-Perfect Invariant:** Live turntable varispeed/reverse DSP is strictly prohibited
 * in [AudioOutputMode.BIT_PERFECT_OPT_IN] mode (where PCM bits must remain unmodified) and only
 * activates in [AudioOutputMode.SAFE_VOLUME_MODE] during active playback.
 */
object TurntableScratchPolicy {
    fun isLiveTurntableEligible(safetyMode: AudioOutputMode, isPlaying: Boolean, hasActiveTrack: Boolean): Boolean =
        safetyMode == AudioOutputMode.SAFE_VOLUME_MODE && isPlaying && hasActiveTrack

    /**
     * Anchors the live scratch cursor to the exact hardware playback head currently reaching the
     * output device (accounting for frames already queued inside `AudioTrack` ahead of the head),
     * clamped to the valid retained ring-buffer range `[oldestAvailableFrame, maxReadableFrame]`.
     */
    fun computeScratchEntryCursorFrame(
        currentStreamHeadFrame: Long,
        totalFramesWrittenToTrack: Long,
        hwPlaybackHeadFrames: Long,
        oldestAvailableFrame: Long,
        writeHeadFrame: Long,
    ): Double {
        val queuedAhead = (totalFramesWrittenToTrack - hwPlaybackHeadFrames).coerceAtLeast(0L)
        val maxReadableFrame = (writeHeadFrame - 1L).coerceAtLeast(oldestAvailableFrame)
        return (currentStreamHeadFrame - queuedAhead)
            .coerceIn(oldestAvailableFrame, maxReadableFrame)
            .toDouble()
    }

    /**
     * Computes the virtual stream-frame bounds `[minVirtualFrame, maxVirtualFrame]` relative to
     * [streamOriginMs] so reverse scratching can rewind smoothly to track `00:00` even after
     * passing `oldestAvailableFrame`.
     */
    fun computeVirtualFrameBounds(streamOriginMs: Long, sampleRateHz: Int, durationMs: Long): Pair<Double, Double> {
        val safeRate = sampleRateHz.coerceAtLeast(1).toDouble()
        val minVirtualFrame = -(streamOriginMs.toDouble() * safeRate / 1000.0)
        val maxVirtualFrame = if (durationMs > 0L) {
            ((durationMs - streamOriginMs).toDouble() * safeRate / 1000.0)
                .coerceAtLeast(minVirtualFrame)
        } else {
            Double.MAX_VALUE
        }
        return minVirtualFrame to maxVirtualFrame
    }

    /**
     * Converts a relative virtual scratch cursor frame into a clamped millisecond track position.
     */
    fun computeClampedScratchPositionMs(streamOriginMs: Long, scratchCursorFrame: Double, sampleRateHz: Int, durationMs: Long): Long {
        val safeRate = sampleRateHz.coerceAtLeast(1).toDouble()
        val rawMs = (streamOriginMs + (scratchCursorFrame * 1000.0) / safeRate).roundToLong()
        return if (durationMs > 0L) rawMs.coerceIn(0L, durationMs) else rawMs.coerceAtLeast(0L)
    }

    /**
     * Returns `true` when post-scratch release can resume immediately via the Fast Seamless `1.0x`
     * Ring Drain Path (zero `MediaCodec.flush()`), or `false` when a Deep Rewind `seekToMs` is required.
     */
    fun canResumeViaSeamlessRingDrain(
        bufferedFrameCount: Int,
        cursorFrameLong: Long,
        oldestAvailableFrame: Long,
        writeHeadFrame: Long,
        fallbackSeekMs: Long = -1L,
    ): Boolean = bufferedFrameCount > 0 &&
        fallbackSeekMs < 0L &&
        cursorFrameLong in oldestAvailableFrame..writeHeadFrame
}

/**
 * Zero-allocation PCM byte <-> normalized float (`-1.0f..1.0f`) conversion helpers supporting all
 * four engine encodings (`PCM_FLOAT`, `PCM_16BIT`, `PCM_24BIT_PACKED`, `PCM_32BIT`) without
 * rebuilding `AudioTrack`.
 */
object TurntablePcmCodec {
    private const val INT16_DIVISOR = 32_768.0f
    private const val INT16_SCALE_MAX = 32_767.0f
    private const val INT24_MIN = -8_388_608
    private const val INT24_MAX = 8_388_607
    private const val INT24_DIVISOR = 8_388_608.0f
    private const val INT24_SCALE_MAX = 8_388_607.0
    private const val INT32_DIVISOR = 2_147_483_648.0
    private const val INT32_SCALE_MAX = 2_147_483_647.0

    /**
     * Decodes little-endian PCM bytes in [encoding] into normalized interleaved floats in
     * [destFloats]. Returns the exact number of float samples written (zero heap allocations).
     */
    fun decodePcmBytesToFloats(
        encoding: PcmEncodingType,
        srcBytes: ByteArray,
        byteOffset: Int,
        byteCount: Int,
        destFloats: FloatArray,
    ): Int {
        val invalidSourceRange = byteCount <= 0 || byteOffset !in srcBytes.indices
        if (invalidSourceRange || destFloats.isEmpty()) {
            return 0
        }
        val availableBytes = minOf(byteCount, srcBytes.size - byteOffset)
        val bps = encoding.bytesPerSample
        val sampleCount = minOf(availableBytes / bps, destFloats.size)
        if (sampleCount <= 0) return 0

        var srcIdx = byteOffset
        when (encoding) {
            PcmEncodingType.PCM_16BIT -> {
                for (i in 0 until sampleCount) {
                    val lo = srcBytes[srcIdx].toInt() and 0xFF
                    val hi = srcBytes[srcIdx + 1].toInt()
                    val sample = (hi shl 8) or lo
                    destFloats[i] = (sample.toFloat() / INT16_DIVISOR).coerceIn(-1.0f, 1.0f)
                    srcIdx += 2
                }
            }
            PcmEncodingType.PCM_24BIT_PACKED -> {
                for (i in 0 until sampleCount) {
                    val b0 = srcBytes[srcIdx].toInt() and 0xFF
                    val b1 = srcBytes[srcIdx + 1].toInt() and 0xFF
                    val b2 = srcBytes[srcIdx + 2].toInt() // Signed MSB preserves 24-bit sign extension
                    val sample = (b2 shl 16) or (b1 shl 8) or b0
                    destFloats[i] = (sample.toFloat() / INT24_DIVISOR).coerceIn(-1.0f, 1.0f)
                    srcIdx += 3
                }
            }
            PcmEncodingType.PCM_32BIT -> {
                for (i in 0 until sampleCount) {
                    val b0 = srcBytes[srcIdx].toInt() and 0xFF
                    val b1 = srcBytes[srcIdx + 1].toInt() and 0xFF
                    val b2 = srcBytes[srcIdx + 2].toInt() and 0xFF
                    val b3 = srcBytes[srcIdx + 3].toInt()
                    val sample = (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
                    destFloats[i] = (sample.toDouble() / INT32_DIVISOR).toFloat().coerceIn(-1.0f, 1.0f)
                    srcIdx += 4
                }
            }
            PcmEncodingType.PCM_FLOAT -> {
                for (i in 0 until sampleCount) {
                    val b0 = srcBytes[srcIdx].toInt() and 0xFF
                    val b1 = srcBytes[srcIdx + 1].toInt() and 0xFF
                    val b2 = srcBytes[srcIdx + 2].toInt() and 0xFF
                    val b3 = srcBytes[srcIdx + 3].toInt() and 0xFF
                    val bits = (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
                    val value = Float.fromBits(bits)
                    destFloats[i] = if (value.isFinite()) value else 0.0f
                    srcIdx += 4
                }
            }
        }
        return sampleCount
    }

    /**
     * Encodes normalized interleaved floats from [srcFloats] into little-endian PCM bytes in
     * [destBytes] with strict saturation clamping (`[-1.0f, 1.0f]`) so digital wraparound never
     * produces clicks. Returns exact byte count written (zero heap allocations).
     */
    fun encodeFloatsToPcmBytes(encoding: PcmEncodingType, srcFloats: FloatArray, floatCount: Int, destBytes: ByteArray): Int {
        if (floatCount <= 0 || srcFloats.isEmpty() || destBytes.isEmpty()) return 0
        val bps = encoding.bytesPerSample
        val count = minOf(floatCount, srcFloats.size, destBytes.size / bps)
        if (count <= 0) return 0

        var dstIdx = 0
        when (encoding) {
            PcmEncodingType.PCM_16BIT -> {
                for (i in 0 until count) {
                    val raw = srcFloats[i]
                    val clamped = if (raw.isFinite()) raw.coerceIn(-1.0f, 1.0f) else 0.0f
                    val scaled = (clamped * INT16_SCALE_MAX)
                        .roundToInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    destBytes[dstIdx] = (scaled and 0xFF).toByte()
                    destBytes[dstIdx + 1] = ((scaled shr 8) and 0xFF).toByte()
                    dstIdx += 2
                }
            }
            PcmEncodingType.PCM_24BIT_PACKED -> {
                for (i in 0 until count) {
                    val raw = srcFloats[i]
                    val clamped = if (raw.isFinite()) raw.coerceIn(-1.0f, 1.0f).toDouble() else 0.0
                    val scaled = (clamped * INT24_SCALE_MAX)
                        .roundToInt()
                        .coerceIn(INT24_MIN, INT24_MAX)
                    destBytes[dstIdx] = (scaled and 0xFF).toByte()
                    destBytes[dstIdx + 1] = ((scaled shr 8) and 0xFF).toByte()
                    destBytes[dstIdx + 2] = ((scaled shr 16) and 0xFF).toByte()
                    dstIdx += 3
                }
            }
            PcmEncodingType.PCM_32BIT -> {
                for (i in 0 until count) {
                    val raw = srcFloats[i]
                    val clamped = if (raw.isFinite()) raw.coerceIn(-1.0f, 1.0f).toDouble() else 0.0
                    val scaled = (clamped * INT32_SCALE_MAX)
                        .roundToLong()
                        .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
                        .toInt()
                    destBytes[dstIdx] = (scaled and 0xFF).toByte()
                    destBytes[dstIdx + 1] = ((scaled shr 8) and 0xFF).toByte()
                    destBytes[dstIdx + 2] = ((scaled shr 16) and 0xFF).toByte()
                    destBytes[dstIdx + 3] = ((scaled shr 24) and 0xFF).toByte()
                    dstIdx += 4
                }
            }
            PcmEncodingType.PCM_FLOAT -> {
                for (i in 0 until count) {
                    val raw = srcFloats[i]
                    val clamped = if (raw.isFinite()) raw.coerceIn(-1.0f, 1.0f) else 0.0f
                    val bits = clamped.toRawBits()
                    destBytes[dstIdx] = (bits and 0xFF).toByte()
                    destBytes[dstIdx + 1] = ((bits shr 8) and 0xFF).toByte()
                    destBytes[dstIdx + 2] = ((bits shr 16) and 0xFF).toByte()
                    destBytes[dstIdx + 3] = ((bits shr 24) and 0xFF).toByte()
                    dstIdx += 4
                }
            }
        }
        return dstIdx
    }
}

/**
 * Fixed-size, OOM-safe circular float PCM buffer strictly capped at **8 MB**
 * ([MAX_RING_FLOATS] = `2,097,152` floats, rounded down to a whole multiple of [channelCount]).
 *
 * Tracks absolute monotonic stream frame indices (`oldestAvailableFrame .. writeHeadFrame - 1`)
 * for O(1) append and O(1) random-access fractional-frame linear interpolation in both forward
 * and reverse directions.
 */
class TurntablePcmRingBuffer(channelCount: Int, maxFloats: Int = MAX_RING_FLOATS) {
    val channels: Int = channelCount.coerceIn(1, 8)
    val capacityFrames: Int = (maxFloats.coerceIn(channels, MAX_RING_FLOATS)) / channels
    val capacityFloats: Int = capacityFrames * channels

    private val storage = FloatArray(capacityFloats)

    var anchorFrame: Long = 0L
        private set

    /** Exclusive monotonic upper bound of written stream frames. */
    var writeHeadFrame: Long = 0L
        private set

    /** Inclusive oldest valid stream frame currently retained in the ring buffer. */
    val oldestAvailableFrame: Long
        get() = maxOf(anchorFrame, writeHeadFrame - capacityFrames.toLong())

    val bufferedFrameCount: Int
        get() = (writeHeadFrame - oldestAvailableFrame).toInt().coerceIn(0, capacityFrames)

    /**
     * Resets read/write pointers to [newAnchorFrame] without reallocating the backing array.
     */
    fun reset(newAnchorFrame: Long = 0L) {
        val safeAnchor = newAnchorFrame.coerceAtLeast(0L)
        anchorFrame = safeAnchor
        writeHeadFrame = safeAnchor
    }

    /**
     * Appends [frameCount] interleaved float frames from [srcFloats] (starting at [srcFloatOffset])
     * in O(1) amortized time using at most two contiguous `System.arraycopy` calls across wraparound.
     */
    fun appendInterleavedFloats(srcFloats: FloatArray, frameCount: Int, srcFloatOffset: Int = 0) {
        if (frameCount <= 0 || srcFloatOffset < 0 || capacityFrames <= 0) return
        val maxAvailableFrames = ((srcFloats.size - srcFloatOffset) / channels).coerceAtLeast(0)
        val count = minOf(frameCount, maxAvailableFrames)
        if (count <= 0) return

        var srcOffset = srcFloatOffset
        var remaining = count
        if (remaining > capacityFrames) {
            // Only keep the most recent capacityFrames frames if a single burst exceeds ring size.
            val skipFrames = remaining - capacityFrames
            srcOffset += skipFrames * channels
            writeHeadFrame += skipFrames.toLong()
            remaining = capacityFrames
        }

        val startSlot = frameToSlot(writeHeadFrame)
        val firstChunkFrames = minOf(remaining, capacityFrames - startSlot)
        System.arraycopy(
            srcFloats,
            srcOffset,
            storage,
            startSlot * channels,
            firstChunkFrames * channels,
        )

        val secondChunkFrames = remaining - firstChunkFrames
        if (secondChunkFrames > 0) {
            System.arraycopy(
                srcFloats,
                srcOffset + firstChunkFrames * channels,
                storage,
                0,
                secondChunkFrames * channels,
            )
        }
        writeHeadFrame += remaining.toLong()
    }

    /**
     * Reads a single linearly interpolated frame (`s0 + frac * (s1 - s0)`) at absolute stream
     * coordinate [fractionalFrame] into [outChannels].
     *
     * Returns `true` if [fractionalFrame] lies inside `[oldestAvailableFrame, writeHeadFrame - 1]`;
     * otherwise fills [outChannels] with zeros and returns `false`.
     */
    fun readInterpolatedFrame(fractionalFrame: Double, outChannels: FloatArray): Boolean {
        if (bufferedFrameCount <= 0 || !fractionalFrame.isFinite() || outChannels.size < channels) {
            zeroOut(outChannels)
            return false
        }
        val minFrame = oldestAvailableFrame
        val maxFrame = writeHeadFrame - 1L
        if (fractionalFrame < minFrame.toDouble() || fractionalFrame > maxFrame.toDouble()) {
            zeroOut(outChannels)
            return false
        }
        readClampedInterpolatedFrame(fractionalFrame, outChannels)
        return true
    }

    /**
     * Reads a linearly interpolated frame clamped to `[oldestAvailableFrame, writeHeadFrame - 1]`,
     * used strictly during 4 ms anti-click boundary fade-outs so audio ramps smoothly to zero
     * instead of stepping abruptly at the ring edge.
     */
    fun readClampedInterpolatedFrame(fractionalFrame: Double, outChannels: FloatArray): Boolean {
        if (bufferedFrameCount <= 0 || outChannels.size < channels) {
            zeroOut(outChannels)
            return false
        }
        val minFrame = oldestAvailableFrame
        val maxFrame = writeHeadFrame - 1L
        val clamped = if (fractionalFrame.isFinite()) {
            fractionalFrame.coerceIn(minFrame.toDouble(), maxFrame.toDouble())
        } else {
            minFrame.toDouble()
        }

        val f0 = floor(clamped).toLong().coerceIn(minFrame, maxFrame)
        val f1 = (f0 + 1L).coerceAtMost(maxFrame)
        val frac = (clamped - f0.toDouble()).coerceIn(0.0, 1.0).toFloat()

        val base0 = frameToSlot(f0) * channels
        val base1 = frameToSlot(f1) * channels
        for (ch in 0 until channels) {
            val s0 = storage[base0 + ch]
            val s1 = storage[base1 + ch]
            outChannels[ch] = s0 + frac * (s1 - s0)
        }
        return true
    }

    /**
     * Copies up to [maxFrames] contiguous 1.0x frames starting at [startFrame] into [destFloats]
     * for fast seamless post-scratch drain without `MediaCodec.flush()`. Returns exact frames copied.
     */
    fun copyFramesAt1x(startFrame: Long, maxFrames: Int, destFloats: FloatArray): Int {
        if (maxFrames <= 0 || bufferedFrameCount <= 0) return 0
        if (startFrame < oldestAvailableFrame || startFrame >= writeHeadFrame) return 0
        val available = (writeHeadFrame - startFrame).coerceAtMost(maxFrames.toLong()).toInt()
        val destCapFrames = destFloats.size / channels
        val count = minOf(available, destCapFrames)
        if (count <= 0) return 0

        val startSlot = frameToSlot(startFrame)
        val firstFrames = minOf(count, capacityFrames - startSlot)
        System.arraycopy(
            storage,
            startSlot * channels,
            destFloats,
            0,
            firstFrames * channels,
        )
        val secondFrames = count - firstFrames
        if (secondFrames > 0) {
            System.arraycopy(
                storage,
                0,
                destFloats,
                firstFrames * channels,
                secondFrames * channels,
            )
        }
        return count
    }

    private fun frameToSlot(frameIndex: Long): Int {
        val rem = (frameIndex % capacityFrames.toLong()).toInt()
        return if (rem < 0) rem + capacityFrames else rem
    }

    private fun zeroOut(outChannels: FloatArray) {
        val limit = minOf(channels, outChannels.size)
        for (ch in 0 until limit) {
            outChannels[ch] = 0.0f
        }
    }

    companion object {
        const val MAX_RING_BYTES: Int = 8 * 1024 * 1024 // 8 MB hard cap
        const val MAX_RING_FLOATS: Int = MAX_RING_BYTES / 4 // 2,097,152 floats
    }
}

/**
 * Pure-Kotlin musical turntable velocity-to-rate mapper, slew-rate smoother, and compact HUD label
 * formatter (zero `android.*` / `androidx.*` imports).
 */
object TurntableSpeedController {
    const val MAX_PLAYBACK_RATE: Float = 4.0f
    const val HOLD_DEADBAND_DEG_PER_SEC: Float = 6.0f
    const val NORMAL_SPIN_DEG_PER_SEC: Float = 20.0f
    const val FAST_SCRUB_DEG_PER_SEC: Float = 90.0f
    const val MAX_SCRUB_DEG_PER_SEC: Float = 360.0f

    const val RATE_AT_20_DEG_PER_SEC: Float = 0.85f
    const val RATE_AT_90_DEG_PER_SEC: Float = 1.80f
    const val RATE_STOP_EPSILON: Float = 0.02f

    /** Click-free linear fade duration in milliseconds for entry, hold, boundary, and exit ramps. */
    const val FADE_ENVELOPE_MS: Int = 4

    /**
     * Maps finger angular velocity (`deg/s`) smoothly and symmetrically onto a live audio playback
     * rate in `[-4.0f, +4.0f]`:
     * - `|degPerSec| < 6 deg/s` -> `0.0f` (hold-to-stop deadband)
     * - `20 deg/s` -> `0.85x` (within `~0.6x–1.0x`)
     * - `90 deg/s` -> `1.80x` (within `~1.6x–2.0x`)
     * - `>= 360 deg/s` -> `4.00x` (capped cleanly)
     */
    fun mapAngularVelocityToRate(angularVelocityDegPerSec: Float): Float {
        if (!angularVelocityDegPerSec.isFinite()) return 0.0f
        val speed = abs(angularVelocityDegPerSec)
        if (speed < HOLD_DEADBAND_DEG_PER_SEC) return 0.0f

        val magnitude = when {
            speed <= NORMAL_SPIN_DEG_PER_SEC -> {
                val t = (speed - HOLD_DEADBAND_DEG_PER_SEC) /
                    (NORMAL_SPIN_DEG_PER_SEC - HOLD_DEADBAND_DEG_PER_SEC)
                RATE_AT_20_DEG_PER_SEC * smoothstep(t)
            }
            speed <= FAST_SCRUB_DEG_PER_SEC -> {
                val t = (speed - NORMAL_SPIN_DEG_PER_SEC) /
                    (FAST_SCRUB_DEG_PER_SEC - NORMAL_SPIN_DEG_PER_SEC)
                RATE_AT_20_DEG_PER_SEC + (RATE_AT_90_DEG_PER_SEC - RATE_AT_20_DEG_PER_SEC) * smoothstep(t)
            }
            speed < MAX_SCRUB_DEG_PER_SEC -> {
                val t = (speed - FAST_SCRUB_DEG_PER_SEC) /
                    (MAX_SCRUB_DEG_PER_SEC - FAST_SCRUB_DEG_PER_SEC)
                RATE_AT_90_DEG_PER_SEC + (MAX_PLAYBACK_RATE - RATE_AT_90_DEG_PER_SEC) * smoothstep(t)
            }
            else -> MAX_PLAYBACK_RATE
        }
        return (sign(angularVelocityDegPerSec) * magnitude).coerceIn(-MAX_PLAYBACK_RATE, MAX_PLAYBACK_RATE)
    }

    /**
     * Clamps a raw external playback rate safely into `[-4.0f, +4.0f]`.
     */
    fun clampPlaybackRate(rate: Float): Float = if (rate.isFinite()) rate.coerceIn(-MAX_PLAYBACK_RATE, MAX_PLAYBACK_RATE) else 0.0f

    /**
     * Formats live scratch speed concisely for the turntable HUD pill (e.g., `"2.1×"`, `"-1.4×"`, `"HOLD"`).
     */
    fun formatSpeedBadge(playbackRate: Float): String {
        val clamped = clampPlaybackRate(playbackRate)
        if (abs(clamped) < 0.05f) return "HOLD"
        return String.format(Locale.US, "%.1f×", clamped)
    }

    private fun smoothstep(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }
}

/**
 * Result emitted after rendering a single resampled turntable chunk.
 */
data class TurntableResampleStepResult(
    val nextCursorFrame: Double,
    val framesRendered: Int,
    val isBelowOldestFrame: Boolean,
    val isAtOrPastWriteHead: Boolean,
)

/**
 * Click-free, zero-allocation real-time varispeed & reverse turntable resampler with per-sample
 * exponential rate smoothing (~12 ms) and a 4 ms linear gain envelope on entry, hold, reverse
 * boundary crossing, and exit.
 */
class TurntableResampler(sampleRateHz: Int, channelCount: Int) {
    val sampleRate: Int = sampleRateHz.coerceAtLeast(8_000)
    val channels: Int = channelCount.coerceIn(1, 8)

    private val fadeFrames: Int = maxOf(1, (sampleRate * TurntableSpeedController.FADE_ENVELOPE_MS) / 1000)
    private val fadeStep: Float = 1.0f / fadeFrames.toFloat()
    private val rateSmoothAlpha: Float = (1.0 - exp(-1.0 / (sampleRate.toDouble() * 0.012))).toFloat()
    private val frameScratch = FloatArray(channels)

    var currentRate: Float = 0.0f
        private set

    var fadeGain: Float = 0.0f
        private set

    /**
     * Resets smoothing state when entering a new live turntable scratch session.
     * Starts [fadeGain] at `0.0f` so audio smoothly ramps in over 4 ms with zero pop.
     */
    fun resetForScratchEntry(initialRate: Float = 0.0f) {
        currentRate = TurntableSpeedController.clampPlaybackRate(initialRate)
        fadeGain = 0.0f
    }

    /**
     * Renders [outFrames] resampled interleaved float frames from [ringBuffer] starting at
     * [startCursorFrame], applying per-sample rate smoothing, 4 ms anti-click gain enveloping,
     * and optional Safe-Mode [replayGainLinear].
     *
     * Virtual [cursorFrame] stays within `[minVirtualFrame, maxVirtualFrame]` so the user can
     * continue rewinding smoothly all the way to track `00:00` even after passing
     * `ringBuffer.oldestAvailableFrame` (faded cleanly to silence until re-entering the buffer).
     */
    fun renderChunk(
        ringBuffer: TurntablePcmRingBuffer,
        targetRate: Float,
        startCursorFrame: Double,
        minVirtualFrame: Double,
        maxVirtualFrame: Double,
        outFrames: Int,
        destFloats: FloatArray,
        replayGainLinear: Float = 1.0f,
        forceFadeOut: Boolean = false,
    ): TurntableResampleStepResult {
        val safeOutFrames = minOf(outFrames, destFloats.size / channels).coerceAtLeast(0)
        if (safeOutFrames == 0) {
            return TurntableResampleStepResult(
                nextCursorFrame = startCursorFrame,
                framesRendered = 0,
                isBelowOldestFrame = startCursorFrame < ringBuffer.oldestAvailableFrame.toDouble(),
                isAtOrPastWriteHead = startCursorFrame >= (ringBuffer.writeHeadFrame - 1L).toDouble(),
            )
        }

        val clampedTarget = TurntableSpeedController.clampPlaybackRate(targetRate)
        val safeGain = if (replayGainLinear.isFinite() && replayGainLinear > 0f) replayGainLinear else 1.0f
        val oldestFrame = ringBuffer.oldestAvailableFrame.toDouble()
        val maxReadableFrame = (ringBuffer.writeHeadFrame - 1L).toDouble()
        val hasAudio = ringBuffer.bufferedFrameCount > 0

        var cursor = if (startCursorFrame.isFinite()) {
            startCursorFrame.coerceIn(minVirtualFrame, maxVirtualFrame)
        } else {
            oldestFrame
        }

        for (f in 0 until safeOutFrames) {
            // Smooth per-sample rate glide (prevents pitch zipper artifacts)
            currentRate += (clampedTarget - currentRate) * rateSmoothAlpha
            if (abs(clampedTarget) < 1e-4f && abs(currentRate) < TurntableSpeedController.RATE_STOP_EPSILON) {
                currentRate = 0.0f
            }

            val inBufferBounds = hasAudio && cursor >= oldestFrame && cursor <= maxReadableFrame
            val isMoving = abs(currentRate) >= TurntableSpeedController.RATE_STOP_EPSILON
            val targetEnv = if (!forceFadeOut && inBufferBounds && isMoving) 1.0f else 0.0f

            // 4 ms linear anti-click gain envelope
            if (fadeGain < targetEnv) {
                fadeGain = minOf(targetEnv, fadeGain + fadeStep)
            } else if (fadeGain > targetEnv) {
                fadeGain = maxOf(targetEnv, fadeGain - fadeStep)
            }

            val baseOut = f * channels
            if (fadeGain > 0.0f && hasAudio) {
                // Clamp edge read during the 4 ms boundary fade-out to avoid step discontinuity pops
                ringBuffer.readClampedInterpolatedFrame(cursor, frameScratch)
                val totalScale = fadeGain * safeGain
                for (ch in 0 until channels) {
                    destFloats[baseOut + ch] = (frameScratch[ch] * totalScale).coerceIn(-1.0f, 1.0f)
                }
            } else {
                for (ch in 0 until channels) {
                    destFloats[baseOut + ch] = 0.0f
                }
            }

            // Advance stream cursor (positive forward, negative reverse) clamped to track bounds
            val next = cursor + currentRate.toDouble()
            cursor = next.coerceIn(minVirtualFrame, maxVirtualFrame)
        }

        return TurntableResampleStepResult(
            nextCursorFrame = cursor,
            framesRendered = safeOutFrames,
            isBelowOldestFrame = !hasAudio || cursor < oldestFrame,
            isAtOrPastWriteHead = !hasAudio || cursor >= maxReadableFrame,
        )
    }

    /**
     * Applies a short linear fade-in over the first [fadeFrames] of [destFloats] during seamless
     * scratch-exit resume into normal 1.0x playback. Returns remaining fade frames.
     */
    fun applyExitFadeIn(destFloats: FloatArray, frameCount: Int, remainingFadeFrames: Int): Int {
        if (remainingFadeFrames <= 0 || frameCount <= 0) return 0
        val total = fadeFrames
        var remaining = remainingFadeFrames
        val limitFrames = minOf(frameCount, destFloats.size / channels)
        for (f in 0 until limitFrames) {
            if (remaining <= 0) break
            val progress = ((total - remaining + 1).toFloat() / total.toFloat()).coerceIn(0f, 1f)
            val base = f * channels
            for (ch in 0 until channels) {
                destFloats[base + ch] = (destFloats[base + ch] * progress).coerceIn(-1.0f, 1.0f)
            }
            remaining--
        }
        return remaining
    }

    fun exitFadeDurationFrames(): Int = fadeFrames
}
