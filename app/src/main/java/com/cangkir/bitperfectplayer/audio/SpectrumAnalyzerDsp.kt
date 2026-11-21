package com.cangkir.bitperfectplayer.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure-Kotlin, zero-allocation 1024-point radix-2 FFT and logarithmic spectrum analyzer.
 *
 * Strictly read-only analytical DSP (zero `android.*` / `androidx.*` imports, zero microphone
 * permissions, zero external dependencies) designed for accurate frequency mapping across standard
 * `44,100`/`48,000 Hz` and decimated Hi-Res `88,200`/`96,000`/`176,400`/`192,000 Hz` streams.
 */
object SpectrumAnalyzerDsp {
    const val FFT_SIZE = 1024
    const val HALF_FFT_SIZE = FFT_SIZE / 2
    const val BAND_COUNT = 32
    const val RADIAL_BAR_COUNT = 64

    const val MIN_FREQ_HZ = 30.0
    const val MAX_FREQ_HZ = 16_000.0
    const val DB_FLOOR = -62.0
    const val DB_CEIL = -4.0
    const val MAX_PERCEPTUAL_TILT_DB = 9.0

    const val TAU_ATTACK_SEC = 0.025f
    const val TAU_DECAY_SEC = 0.140f
    const val SETTLE_EPSILON = 0.002f

    // Hann window coherent gain is 0.5 -> single-sided DFT magnitude of a 0 dBFS sine is FFT_SIZE * 0.25.
    internal const val SINGLE_SIDED_NORM = FFT_SIZE * 0.25

    internal val hannWindow: FloatArray = FloatArray(FFT_SIZE) { n ->
        (0.5 * (1.0 - cos((2.0 * PI * n) / (FFT_SIZE - 1)))).toFloat()
    }

    internal val hannWindowSum: Double = hannWindow.sumOf { it.toDouble() }

    internal val bitReverseTable: IntArray = IntArray(FFT_SIZE) { i ->
        var x = i
        var rev = 0
        var bit = 0
        while (bit < 10) { // log2(1024) = 10
            rev = (rev shl 1) or (x and 1)
            x = x ushr 1
            bit++
        }
        rev
    }

    // Precomputed twiddle factors for stages 2..1024 (half-size = 512).
    internal val cosTwiddle: FloatArray = FloatArray(HALF_FFT_SIZE) { k ->
        cos(-2.0 * PI * k / FFT_SIZE).toFloat()
    }
    internal val sinTwiddle: FloatArray = FloatArray(HALF_FFT_SIZE) { k ->
        sin(-2.0 * PI * k / FFT_SIZE).toFloat()
    }

    // Gentle high-frequency perceptual tilt (0 dB at band 0 -> +9 dB at band 31) so vocals and
    // cymbals stay lively alongside bass without pinning flat at 1.0f.
    internal val bandTiltDb: DoubleArray = DoubleArray(BAND_COUNT) { b ->
        (b.toDouble() / (BAND_COUNT - 1).toDouble()) * MAX_PERCEPTUAL_TILT_DB
    }

    // Precomputed logarithmic band frequency boundaries [30 Hz .. 16,000 Hz].
    internal val bandEdgeFreqHz: DoubleArray = DoubleArray(BAND_COUNT + 1) { i ->
        MIN_FREQ_HZ * (MAX_FREQ_HZ / MIN_FREQ_HZ).pow(i.toDouble() / BAND_COUNT.toDouble())
    }

    /**
     * Computes the integer decimation stride that normalizes Hi-Res sample rates down to ~44.1–48 kHz
     * so a 1024-point FFT maintains crisp ~43–47 Hz bass resolution even on 96/192 kHz FLAC/WAV.
     */
    fun computeDecimationStride(sampleRateHz: Int): Int {
        if (sampleRateHz <= 0) return 1
        return when {
            sampleRateHz <= 50_000 -> 1
            sampleRateHz <= 100_000 -> 2
            sampleRateHz <= 200_000 -> 4
            else -> (sampleRateHz / 48_000).coerceAtLeast(1)
        }
    }

    fun computeEffectiveSampleRateHz(sampleRateHz: Int): Int {
        if (sampleRateHz <= 0) return 0
        val stride = computeDecimationStride(sampleRateHz)
        return (sampleRateHz / stride).coerceAtLeast(1)
    }

    /**
     * Frame-rate-independent fast-attack / smooth-decay exponential smoothing (`dtSeconds` aware
     * across 60 Hz & 120 Hz displays). Updates [currentBands] in place and returns the maximum
     * post-smoothing band energy so UI animation loops can stop cleanly when settled (`< 0.002f`).
     */
    fun smoothBandsInPlace(currentBands: FloatArray, targetBands: FloatArray, dtSeconds: Float): Float {
        val count = minOf(currentBands.size, targetBands.size)
        if (count <= 0) return 0f
        val dt = if (dtSeconds.isFinite()) dtSeconds.coerceIn(0f, 0.1f) else 0f
        val attackAlpha = if (dt > 0f) (1.0 - exp((-dt / TAU_ATTACK_SEC).toDouble())).toFloat() else 0f
        val decayAlpha = if (dt > 0f) (1.0 - exp((-dt / TAU_DECAY_SEC).toDouble())).toFloat() else 0f

        var maxVal = 0f
        for (i in 0 until count) {
            val rawTarget = targetBands[i]
            val target = if (rawTarget.isFinite()) rawTarget.coerceIn(0f, 1f) else 0f
            val prev = if (currentBands[i].isFinite()) currentBands[i].coerceIn(0f, 1f) else 0f
            val alpha = if (target > prev) attackAlpha else decayAlpha
            var next = prev + (target - prev) * alpha
            if (target == 0f && next < 0.0005f) {
                next = 0f
            } else {
                next = next.coerceIn(0f, 1f)
            }
            currentBands[i] = next
            if (next > maxVal) maxVal = next
        }
        return maxVal
    }

    /**
     * Mirrors 32 logarithmic bands cleanly left/right into 64 radial bars around 360° so both
     * 6 o'clock (low bass) and 12 o'clock (high treble) join symmetrically with zero harsh
     * 30 Hz-next-to-16 kHz seam discontinuity.
     *
     * Index `0` starts at 12 o'clock and proceeds clockwise:
     * - `0..31`: treble (`band 31`) down through mids (3 o'clock) to sub-bass (`band 0` at 6 o'clock)
     * - `32..63`: sub-bass (`band 0` at 6 o'clock) up through mids (9 o'clock) to treble (`band 31` at 12 o'clock)
     */
    fun mapBandsToSymmetricRadialBars(bands32: FloatArray, outBars64: FloatArray) {
        if (bands32.size < BAND_COUNT || outBars64.size < RADIAL_BAR_COUNT) return
        for (i in 0 until BAND_COUNT) {
            val v = bands32[i].let { if (it.isFinite()) it.coerceIn(0f, 1f) else 0f }
            outBars64[BAND_COUNT - 1 - i] = v
            outBars64[BAND_COUNT + i] = v
        }
    }

    /**
     * Performs an in-place 1024-point iterative Cooley-Tukey radix-2 FFT on bit-reversed [real]
     * and [imag] arrays using precomputed twiddle tables (zero heap allocations).
     */
    internal fun fftInPlace(real: FloatArray, imag: FloatArray) {
        var halfSize = 1
        while (halfSize < FFT_SIZE) {
            val step = halfSize shl 1
            val twiddleStep = FFT_SIZE / step
            var groupStart = 0
            while (groupStart < FFT_SIZE) {
                var twiddleIndex = 0
                for (k in 0 until halfSize) {
                    val i = groupStart + k
                    val j = i + halfSize
                    val wr = cosTwiddle[twiddleIndex]
                    val wi = sinTwiddle[twiddleIndex]
                    twiddleIndex += twiddleStep

                    val tr = wr * real[j] - wi * imag[j]
                    val ti = wr * imag[j] + wi * real[j]

                    real[j] = real[i] - tr
                    imag[j] = imag[i] - ti
                    real[i] = real[i] + tr
                    imag[i] = imag[i] + ti
                }
                groupStart += step
            }
            halfSize = step
        }
    }
}

/**
 * Reusable pre-allocated FFT spectrum processor. Instantiate once per analyzer thread/composable
 * to run zero-allocation `computeSpectrumBands(...)` calls at 30–60 Hz without GC pressure.
 */
class SpectrumAnalyzer {
    private val real = FloatArray(SpectrumAnalyzerDsp.FFT_SIZE)
    private val imag = FloatArray(SpectrumAnalyzerDsp.FFT_SIZE)
    private val magnitudes = FloatArray(SpectrumAnalyzerDsp.HALF_FFT_SIZE)

    private var cachedSampleRateHz: Int = -1
    private val bandStartBin = IntArray(SpectrumAnalyzerDsp.BAND_COUNT)
    private val bandEndBin = IntArray(SpectrumAnalyzerDsp.BAND_COUNT)
    private val bandCenterExactBin = FloatArray(SpectrumAnalyzerDsp.BAND_COUNT)

    /**
     * Computes 32 normalized logarithmic frequency bands (`0.0f..1.0f`) across `30 Hz .. 16,000 Hz`
     * from [monoSamples] (first `1024` samples) sampled at [effectiveSampleRateHz].
     * Performs **zero heap allocations**.
     */
    fun computeSpectrumBands(monoSamples: FloatArray, effectiveSampleRateHz: Int, outBands: FloatArray) {
        val outCount = minOf(outBands.size, SpectrumAnalyzerDsp.BAND_COUNT)
        if (outCount <= 0) return

        if (effectiveSampleRateHz <= 0 || monoSamples.size < SpectrumAnalyzerDsp.FFT_SIZE) {
            for (i in 0 until outCount) outBands[i] = 0f
            return
        }

        if (effectiveSampleRateHz != cachedSampleRateHz) {
            rebuildBinPlan(effectiveSampleRateHz)
        }

        // Estimate DC using Hann-weighted mean so true DC bias is removed cleanly while
        // non-integer-cycle mid/high tones do not leak phantom DC energy into bin 1 (sub-bass).
        val window = SpectrumAnalyzerDsp.hannWindow
        var weightedSum = 0.0
        for (n in 0 until SpectrumAnalyzerDsp.FFT_SIZE) {
            val s = monoSamples[n]
            if (s.isFinite()) weightedSum += s.toDouble() * window[n].toDouble()
        }
        val dc = (weightedSum / SpectrumAnalyzerDsp.hannWindowSum).toFloat()

        // Bit-reverse + apply precomputed Hann window in a single zero-allocation pass.
        val bitRev = SpectrumAnalyzerDsp.bitReverseTable
        var energySum = 0f
        for (n in 0 until SpectrumAnalyzerDsp.FFT_SIZE) {
            val raw = monoSamples[n]
            val clean = if (raw.isFinite()) (raw - dc) else 0f
            val windowed = clean * window[n]
            energySum += windowed * windowed
            val dst = bitRev[n]
            real[dst] = windowed
            imag[dst] = 0f
        }

        if (energySum <= 1e-16f) {
            for (i in 0 until outCount) outBands[i] = 0f
            return
        }

        SpectrumAnalyzerDsp.fftInPlace(real, imag)

        // Single-sided normalized magnitudes for bins 1..511.
        val invNorm = (1.0 / SpectrumAnalyzerDsp.SINGLE_SIDED_NORM).toFloat()
        magnitudes[0] = 0f
        for (bin in 1 until SpectrumAnalyzerDsp.HALF_FFT_SIZE) {
            val re = real[bin]
            val im = imag[bin]
            magnitudes[bin] = sqrt(re * re + im * im) * invNorm
        }

        val dbSpan = SpectrumAnalyzerDsp.DB_CEIL - SpectrumAnalyzerDsp.DB_FLOOR
        val tilts = SpectrumAnalyzerDsp.bandTiltDb

        for (b in 0 until outCount) {
            val start = bandStartBin[b]
            val end = bandEndBin[b]
            val peakMag: Float = if (end > start) {
                var best = 0f
                for (bin in start..end) {
                    val m = magnitudes[bin]
                    if (m > best) best = m
                }
                best
            } else {
                // Sub-bin logarithmic bass region: linearly interpolate adjacent positive FFT bins.
                val exact = bandCenterExactBin[b]
                val b0 = floor(exact).toInt().coerceIn(1, SpectrumAnalyzerDsp.HALF_FFT_SIZE - 1)
                val b1 = (b0 + 1).coerceAtMost(SpectrumAnalyzerDsp.HALF_FFT_SIZE - 1)
                val frac = (exact - b0.toFloat()).coerceIn(0f, 1f)
                magnitudes[b0] + frac * (magnitudes[b1] - magnitudes[b0])
            }

            if (peakMag <= 1e-9f || !peakMag.isFinite()) {
                outBands[b] = 0f
            } else {
                val db = 20.0 * log10(peakMag.toDouble()) + tilts[b]
                val norm = ((db - SpectrumAnalyzerDsp.DB_FLOOR) / dbSpan).toFloat()
                outBands[b] = if (norm.isFinite()) norm.coerceIn(0f, 1f) else 0f
            }
        }
    }

    private fun rebuildBinPlan(effectiveSampleRateHz: Int) {
        cachedSampleRateHz = effectiveSampleRateHz
        val nyquist = effectiveSampleRateHz * 0.5
        val binPerHz = SpectrumAnalyzerDsp.FFT_SIZE.toDouble() / effectiveSampleRateHz.toDouble()
        val maxBin = SpectrumAnalyzerDsp.HALF_FFT_SIZE - 1
        val edges = SpectrumAnalyzerDsp.bandEdgeFreqHz

        for (b in 0 until SpectrumAnalyzerDsp.BAND_COUNT) {
            val fLow = edges[b].coerceAtMost(nyquist)
            val fHigh = edges[b + 1].coerceAtMost(nyquist)
            val fCenter = sqrt(fLow * fHigh).coerceAtMost(nyquist)

            val rawStart = floor(fLow * binPerHz + 0.5).toInt()
            val rawEnd = floor(fHigh * binPerHz + 0.5).toInt()
            bandStartBin[b] = rawStart.coerceIn(1, maxBin)
            bandEndBin[b] = rawEnd.coerceIn(1, maxBin)
            bandCenterExactBin[b] = (fCenter * binPerHz).toFloat().coerceIn(1f, maxBin.toFloat())
        }
    }

    companion object {
        /** Helper useful for verifying logarithmic center frequency mapping in unit tests. */
        fun bandCenterFreqHz(bandIndex: Int): Double {
            val clamped = bandIndex.coerceIn(0, SpectrumAnalyzerDsp.BAND_COUNT - 1)
            val ratio = SpectrumAnalyzerDsp.MAX_FREQ_HZ / SpectrumAnalyzerDsp.MIN_FREQ_HZ
            val fLow = SpectrumAnalyzerDsp.MIN_FREQ_HZ * exp(ln(ratio) * clamped / SpectrumAnalyzerDsp.BAND_COUNT)
            val fHigh = SpectrumAnalyzerDsp.MIN_FREQ_HZ * exp(ln(ratio) * (clamped + 1) / SpectrumAnalyzerDsp.BAND_COUNT)
            return sqrt(fLow * fHigh)
        }
    }
}
