package com.cangkir.bitperfectplayer.audio

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Lightweight, lock-free-friendly, strictly read-only PCM side-tap ring buffer (`48,000` mono
 * floats ≈ `192 KB`) aligning UI spectrum reads with `AudioTrack.playbackHeadPosition`.
 *
 * Architectural guarantees:
 * - **Pure Kotlin** (zero `android.*` / `androidx.*` imports) for deterministic JVM unit tests.
 * - **Strictly Read-Only:** NEVER modifies source PCM bytes or floats sent to `AudioTrack`, making
 *   it 100% safe in both `SAFE_VOLUME_MODE` and `BIT_PERFECT_OPT_IN` mode.
 * - **Hi-Res Decimation:** Uses boxcar-averaged decimation strides (`1`/`2`/`4`) so 88.2/96/176.4/192 kHz
 *   streams map cleanly to ~44.1–48 kHz without aliasing or extra allocations.
 * - **Head-Synced Windowing:** Tracks the `AudioTrack` frame index corresponding to the latest
 *   written decimated sample so the UI reads the exact 1024-sample window currently reaching the ear.
 */
class SpectrumPcmTap {
    private val ring = FloatArray(CAPACITY_SAMPLES)

    private val generation = AtomicInteger(0)
    private val writeSampleCount = AtomicLong(0L)
    private val lastTrackFrameWritten = AtomicLong(0L)

    @Volatile
    private var sourceSampleRateHz: Int = 0

    @Volatile
    private var decimationStride: Int = 1

    @Volatile
    private var effectiveSampleRateHz: Int = 0

    // Audio-worker-thread-only boxcar decimation accumulators.
    private var accumSum: Float = 0f
    private var accumCount: Int = 0

    val currentEffectiveSampleRateHz: Int
        get() = effectiveSampleRateHz

    val currentDecimationStride: Int
        get() = decimationStride

    /**
     * Resets ring-buffer state and sample-rate decimation metadata when starting a track, seeking,
     * flushing `AudioTrack`, or handling a mid-stream format change.
     */
    fun reset(sampleRateHz: Int, trackFrameCursor: Long) {
        generation.incrementAndGet()
        sourceSampleRateHz = sampleRateHz.coerceAtLeast(0)
        val stride = SpectrumAnalyzerDsp.computeDecimationStride(sampleRateHz)
        decimationStride = stride
        effectiveSampleRateHz = SpectrumAnalyzerDsp.computeEffectiveSampleRateHz(sampleRateHz)
        accumSum = 0f
        accumCount = 0
        lastTrackFrameWritten.set(trackFrameCursor.coerceAtLeast(0L))
        writeSampleCount.set(0L)
        generation.incrementAndGet()
    }

    /**
     * Strictly read-only tap of aligned little-endian PCM bytes already written to `AudioTrack`.
     * NEVER mutates [srcBytes].
     */
    fun appendPcmBytesReadOnly(
        encoding: PcmEncodingType,
        srcBytes: ByteArray,
        byteOffset: Int,
        byteCount: Int,
        channels: Int,
        trackFrameAfterWrite: Long,
    ) {
        if (byteCount <= 0 || channels <= 0 || effectiveSampleRateHz <= 0) return
        val bytesPerSample = encoding.bytesPerSample
        val bytesPerFrame = bytesPerSample * channels
        if (bytesPerFrame <= 0) return
        val frameCount = byteCount / bytesPerFrame
        if (frameCount <= 0) return

        val invChannels = 1.0f / channels.toFloat()
        val stride = decimationStride
        val invStride = 1.0f / stride.toFloat()
        var writeIdx = writeSampleCount.get()
        var pos = byteOffset

        for (f in 0 until frameCount) {
            var monoSum = 0f
            when (encoding) {
                PcmEncodingType.PCM_16BIT -> {
                    for (ch in 0 until channels) {
                        val b0 = srcBytes[pos].toInt() and 0xFF
                        val b1 = srcBytes[pos + 1].toInt()
                        val s16 = (b1 shl 8) or b0
                        monoSum += s16 * INV_PCM16
                        pos += 2
                    }
                }
                PcmEncodingType.PCM_24BIT_PACKED -> {
                    for (ch in 0 until channels) {
                        val b0 = srcBytes[pos].toInt() and 0xFF
                        val b1 = srcBytes[pos + 1].toInt() and 0xFF
                        val b2 = srcBytes[pos + 2].toInt()
                        val s24 = (b2 shl 16) or (b1 shl 8) or b0
                        monoSum += s24 * INV_PCM24
                        pos += 3
                    }
                }
                PcmEncodingType.PCM_32BIT -> {
                    for (ch in 0 until channels) {
                        val b0 = srcBytes[pos].toInt() and 0xFF
                        val b1 = srcBytes[pos + 1].toInt() and 0xFF
                        val b2 = srcBytes[pos + 2].toInt() and 0xFF
                        val b3 = srcBytes[pos + 3].toInt()
                        val s32 = (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
                        monoSum += s32.toFloat() * INV_PCM32
                        pos += 4
                    }
                }
                PcmEncodingType.PCM_FLOAT -> {
                    for (ch in 0 until channels) {
                        val b0 = srcBytes[pos].toInt() and 0xFF
                        val b1 = srcBytes[pos + 1].toInt() and 0xFF
                        val b2 = srcBytes[pos + 2].toInt() and 0xFF
                        val b3 = srcBytes[pos + 3].toInt() and 0xFF
                        val bits = (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
                        val v = Float.fromBits(bits)
                        if (v.isFinite()) monoSum += v.coerceIn(-1.0f, 1.0f)
                        pos += 4
                    }
                }
            }

            val mono = monoSum * invChannels
            if (stride == 1) {
                ring[(writeIdx % CAPACITY_SAMPLES).toInt()] = mono
                writeIdx++
            } else {
                accumSum += mono
                accumCount++
                if (accumCount >= stride) {
                    ring[(writeIdx % CAPACITY_SAMPLES).toInt()] = accumSum * invStride
                    writeIdx++
                    accumSum = 0f
                    accumCount = 0
                }
            }
        }

        // Publish track frame and writeCount AFTER writing samples to ring (happens-before visibility).
        lastTrackFrameWritten.set(trackFrameAfterWrite)
        writeSampleCount.set(writeIdx)
    }

    /**
     * Strictly read-only tap of normalized interleaved float PCM frames (`-1.0f..1.0f`), used by
     * Safe-Mode live turntable scratch rendering and seamless `1.0x` ring-buffer exit draining.
     * NEVER mutates [srcFloats].
     */
    fun appendInterleavedFloatsReadOnly(
        srcFloats: FloatArray,
        frameCount: Int,
        channels: Int,
        trackFrameAfterWrite: Long,
    ) {
        if (frameCount <= 0 || channels <= 0 || effectiveSampleRateHz <= 0) return
        val maxFrames = minOf(frameCount, srcFloats.size / channels)
        if (maxFrames <= 0) return

        val invChannels = 1.0f / channels.toFloat()
        val stride = decimationStride
        val invStride = 1.0f / stride.toFloat()
        var writeIdx = writeSampleCount.get()
        var idx = 0

        for (f in 0 until maxFrames) {
            var monoSum = 0f
            for (ch in 0 until channels) {
                val v = srcFloats[idx++]
                if (v.isFinite()) monoSum += v.coerceIn(-1.0f, 1.0f)
            }
            val mono = monoSum * invChannels
            if (stride == 1) {
                ring[(writeIdx % CAPACITY_SAMPLES).toInt()] = mono
                writeIdx++
            } else {
                accumSum += mono
                accumCount++
                if (accumCount >= stride) {
                    ring[(writeIdx % CAPACITY_SAMPLES).toInt()] = accumSum * invStride
                    writeIdx++
                    accumSum = 0f
                    accumCount = 0
                }
            }
        }

        lastTrackFrameWritten.set(trackFrameAfterWrite)
        writeSampleCount.set(writeIdx)
    }

    /**
     * Reads a [outMonoSamples.size]-sample window aligned to [playbackHeadFrame] (the current
     * audible `AudioTrack.playbackHeadPosition`) into [outMonoSamples].
     *
     * Returns the effective decimated sample rate in `Hz`, or `0` if inactive / no samples ready /
     * reset raced mid-read. Zero heap allocations.
     */
    fun sampleWindowAtTrackFrame(
        playbackHeadFrame: Long,
        outMonoSamples: FloatArray,
    ): Int {
        val windowSize = outMonoSamples.size
        if (windowSize <= 0) return 0

        val genBefore = generation.get()
        if ((genBefore and 1) != 0) {
            // Reset currently in progress on worker thread.
            zeroOut(outMonoSamples)
            return 0
        }

        val effRate = effectiveSampleRateHz
        val stride = decimationStride.coerceAtLeast(1)
        val writeCount = writeSampleCount.get()
        val endTrackFrame = lastTrackFrameWritten.get()

        if (effRate <= 0 || writeCount <= 0L) {
            zeroOut(outMonoSamples)
            return 0
        }

        // Convert AudioTrack queued lookahead frames into decimated ring samples (capped safely at 0.5s).
        val maxReasonableAheadSamples = minOf(CAPACITY_SAMPLES - windowSize - 1, effRate / 2).coerceAtLeast(0).toLong()
        val aheadTrackFrames = if (playbackHeadFrame > 0L) {
            (endTrackFrame - playbackHeadFrame).coerceAtLeast(0L)
        } else {
            0L
        }
        val aheadDecimated = (aheadTrackFrames / stride.toLong()).coerceIn(0L, maxReasonableAheadSamples)

        val oldestAvailable = (writeCount - CAPACITY_SAMPLES).coerceAtLeast(0L)
        val headSampleExclusive = (writeCount - aheadDecimated).coerceIn(oldestAvailable, writeCount)
        val availableUpToHead = (headSampleExclusive - oldestAvailable).toInt().coerceAtLeast(0)

        if (availableUpToHead <= 0) {
            zeroOut(outMonoSamples)
            return 0
        }

        if (availableUpToHead < windowSize) {
            val pad = windowSize - availableUpToHead
            for (i in 0 until pad) outMonoSamples[i] = 0f
            var src = oldestAvailable
            for (i in pad until windowSize) {
                outMonoSamples[i] = ring[(src % CAPACITY_SAMPLES).toInt()]
                src++
            }
        } else {
            var src = headSampleExclusive - windowSize.toLong()
            for (i in 0 until windowSize) {
                outMonoSamples[i] = ring[(src % CAPACITY_SAMPLES).toInt()]
                src++
            }
        }

        // Verify generation and ring-overwrite safety after copying (lock-free seqlock check).
        val genAfter = generation.get()
        val latestWrite = writeSampleCount.get()
        if (genAfter != genBefore || (latestWrite - CAPACITY_SAMPLES) > (headSampleExclusive - minOf(availableUpToHead, windowSize))) {
            zeroOut(outMonoSamples)
            return 0
        }

        return effRate
    }

    private fun zeroOut(dest: FloatArray) {
        for (i in dest.indices) dest[i] = 0f
    }

    companion object {
        const val CAPACITY_SAMPLES = 48_000 // ~192 KB float buffer (~1.0s at 48 kHz decimated)
        private const val INV_PCM16 = 1.0f / 32_768.0f
        private const val INV_PCM24 = 1.0f / 8_388_608.0f
        private const val INV_PCM32 = 1.0f / 2_147_483_648.0f
    }
}
