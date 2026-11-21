package com.cangkir.bitperfectplayer.audio

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectrumAnalyzerDspTest {

    private fun generateSine(sampleRateHz: Int, freqHz: Double, sampleCount: Int, amplitude: Float = 0.5f): FloatArray =
        FloatArray(sampleCount) { n ->
            (amplitude * sin(2.0 * PI * freqHz * n / sampleRateHz)).toFloat()
        }

    private fun indexOfMax(values: FloatArray): Int {
        var bestIdx = 0
        var bestVal = Float.NEGATIVE_INFINITY
        for (i in values.indices) {
            if (values[i] > bestVal) {
                bestVal = values[i]
                bestIdx = i
            }
        }
        return bestIdx
    }

    @Test
    fun computeDecimationStride_normalizesStandardAndHiResRates() {
        assertEquals(1, SpectrumAnalyzerDsp.computeDecimationStride(44_100))
        assertEquals(1, SpectrumAnalyzerDsp.computeDecimationStride(48_000))
        assertEquals(2, SpectrumAnalyzerDsp.computeDecimationStride(88_200))
        assertEquals(2, SpectrumAnalyzerDsp.computeDecimationStride(96_000))
        assertEquals(4, SpectrumAnalyzerDsp.computeDecimationStride(176_400))
        assertEquals(4, SpectrumAnalyzerDsp.computeDecimationStride(192_000))
        assertEquals(8, SpectrumAnalyzerDsp.computeDecimationStride(384_000))
    }

    @Test
    fun computeSpectrumBands_pureSineTonesPeakInAccurateBassMidTrebleBandsAt44100Hz() {
        val analyzer = SpectrumAnalyzer()
        val bands = FloatArray(SpectrumAnalyzerDsp.BAND_COUNT)

        // 100 Hz -> low bass region (bands 4..8)
        analyzer.computeSpectrumBands(generateSine(44_100, 100.0, 1024), 44_100, bands)
        val bassPeak = indexOfMax(bands)
        assertTrue("Expected 100 Hz peak in bass bands 4..8, got $bassPeak", bassPeak in 4..8)
        assertTrue("Expected strong bass peak energy, got ${bands[bassPeak]}", bands[bassPeak] > 0.65f)
        assertTrue("Expected treble quiet during 100 Hz sine", bands[28] < 0.05f)

        // 1,000 Hz -> mid region (bands 16..20)
        analyzer.computeSpectrumBands(generateSine(44_100, 1_000.0, 1024), 44_100, bands)
        val midPeak = indexOfMax(bands)
        assertTrue("Expected 1,000 Hz peak in mid bands 16..20, got $midPeak", midPeak in 16..20)
        assertTrue("Expected strong mid peak energy, got ${bands[midPeak]}", bands[midPeak] > 0.70f)
        assertTrue("Expected sub-bass quiet during 1,000 Hz sine", bands[2] < 0.05f)

        // 8,000 Hz -> treble region (bands 26..30)
        analyzer.computeSpectrumBands(generateSine(44_100, 8_000.0, 1024), 44_100, bands)
        val treblePeak = indexOfMax(bands)
        assertTrue("Expected 8,000 Hz peak in treble bands 26..30, got $treblePeak", treblePeak in 26..30)
        assertTrue("Expected strong treble peak energy, got ${bands[treblePeak]}", bands[treblePeak] > 0.70f)
        assertTrue("Expected bass quiet during 8,000 Hz sine", bands[4] < 0.05f)
    }

    @Test
    fun computeSpectrumBands_hiRes192kHzDecimatedViaSpectrumPcmTapMapsFrequenciesAccurately() {
        val tap = SpectrumPcmTap()
        val analyzer = SpectrumAnalyzer()
        val window = FloatArray(SpectrumAnalyzerDsp.FFT_SIZE)
        val bands = FloatArray(SpectrumAnalyzerDsp.BAND_COUNT)

        // Test 100 Hz, 1,000 Hz, and 8,000 Hz stereo PCM at 192,000 Hz through SpectrumPcmTap (stride=4 -> 48,000 Hz).
        val testCases = listOf(
            Triple(100.0, 4..8, "100 Hz bass"),
            Triple(1_000.0, 16..20, "1,000 Hz mid"),
            Triple(8_000.0, 26..30, "8,000 Hz treble"),
        )
        val frameCount = SpectrumAnalyzerDsp.FFT_SIZE * 4 + 256 // > 1024 decimated samples

        for ((freqHz, expectedRange, label) in testCases) {
            tap.reset(sampleRateHz = 192_000, trackFrameCursor = 0L)
            assertEquals(4, tap.currentDecimationStride)
            assertEquals(48_000, tap.currentEffectiveSampleRateHz)

            val stereo = FloatArray(frameCount * 2)
            for (f in 0 until frameCount) {
                val v = (0.45 * sin(2.0 * PI * freqHz * f / 192_000.0)).toFloat()
                stereo[f * 2] = v
                stereo[f * 2 + 1] = v
            }
            val originalCopy = stereo.copyOf()
            tap.appendInterleavedFloatsReadOnly(
                srcFloats = stereo,
                frameCount = frameCount,
                channels = 2,
                trackFrameAfterWrite = frameCount.toLong(),
            )
            // Verify strict read-only invariant: source PCM array was NEVER modified.
            assertTrue("Source PCM floats must remain untouched", stereo.contentEquals(originalCopy))

            val effRate = tap.sampleWindowAtTrackFrame(
                playbackHeadFrame = frameCount.toLong(),
                outMonoSamples = window,
            )
            assertEquals(48_000, effRate)
            analyzer.computeSpectrumBands(window, effRate, bands)
            val peak = indexOfMax(bands)
            assertTrue("Expected $label at 192 kHz in $expectedRange, got $peak", peak in expectedRange)
            assertTrue("Expected strong peak for $label at 192 kHz, got ${bands[peak]}", bands[peak] > 0.60f)
        }
    }

    @Test
    fun computeSpectrumBands_silenceProducesExactZerosWithoutNaNOrInfinity() {
        val analyzer = SpectrumAnalyzer()
        val silence = FloatArray(SpectrumAnalyzerDsp.FFT_SIZE) { 0f }
        val bands = FloatArray(SpectrumAnalyzerDsp.BAND_COUNT) { 0.5f }

        analyzer.computeSpectrumBands(silence, 48_000, bands)

        for (i in bands.indices) {
            assertTrue("Band $i must be finite", bands[i].isFinite())
            assertEquals(0f, bands[i], 0.0f)
        }
    }

    @Test
    fun smoothBandsInPlace_enforcesFastAttackSmoothDecayAndFullDecayToZeroOnPause() {
        val current = FloatArray(SpectrumAnalyzerDsp.BAND_COUNT) { 0f }
        val target = FloatArray(SpectrumAnalyzerDsp.BAND_COUNT) { 1.0f }

        // Fast attack: after 25 ms (1 tauAttack), should rise to ~63.2% of target.
        val afterAttack1 = SpectrumAnalyzerDsp.smoothBandsInPlace(current, target, 0.025f)
        assertTrue("Expected ~0.63 after 1 attack tau, got $afterAttack1", afterAttack1 in 0.58f..0.68f)

        val afterAttack2 = SpectrumAnalyzerDsp.smoothBandsInPlace(current, target, 0.025f)
        assertTrue("Attack must rise monotonically", afterAttack2 > afterAttack1)

        // Smooth decay on pause (target = 0): slower than attack, monotonic, decays all the way to 0.0f.
        val zeroTarget = FloatArray(SpectrumAnalyzerDsp.BAND_COUNT) { 0f }
        var prev = afterAttack2
        repeat(6) {
            val next = SpectrumAnalyzerDsp.smoothBandsInPlace(current, zeroTarget, 0.016f)
            assertTrue("Decay must decrease monotonically", next < prev)
            prev = next
        }
        // After ~1.5s of 16ms frames, all bands settle cleanly to exact 0.0f.
        var finalMax = prev
        repeat(100) {
            finalMax = SpectrumAnalyzerDsp.smoothBandsInPlace(current, zeroTarget, 0.016f)
        }
        assertEquals(0f, finalMax, 0.0f)
        assertTrue(current.all { it == 0f })
    }

    @Test
    fun mapBandsToSymmetricRadialBars_mirrorsSeamlesslyAtBoth12And6OClock() {
        val bands32 = FloatArray(SpectrumAnalyzerDsp.BAND_COUNT) { i -> (i + 1) / 32f }
        val bars64 = FloatArray(SpectrumAnalyzerDsp.RADIAL_BAR_COUNT)

        SpectrumAnalyzerDsp.mapBandsToSymmetricRadialBars(bands32, bars64)

        // Exact left/right mirror symmetry around the vertical axis.
        for (i in 0 until SpectrumAnalyzerDsp.BAND_COUNT) {
            assertEquals(bars64[i], bars64[63 - i], 0f)
        }
        // 6 o'clock seam (indices 31 and 32) both carry sub-bass band 0.
        assertEquals(bands32[0], bars64[31], 0f)
        assertEquals(bands32[0], bars64[32], 0f)
        // 12 o'clock seam (indices 0 and 63) both carry treble band 31.
        assertEquals(bands32[31], bars64[0], 0f)
        assertEquals(bands32[31], bars64[63], 0f)
    }

    @Test
    fun spectrumPcmTap_alignsWindowToPlaybackHeadEliminatingLookaheadDesync() {
        val tap = SpectrumPcmTap()
        tap.reset(sampleRateHz = 48_000, trackFrameCursor = 0L)

        // Write 10,000 mono 16-bit PCM frames:
        // - Frames 0..4,999 carry 100 Hz bass sine
        // - Frames 5,000..9,999 carry 8,000 Hz treble sine (simulating AudioTrack lookahead buffer)
        val totalFrames = 10_000
        val pcm16 = ByteArray(totalFrames * 2)
        for (f in 0 until totalFrames) {
            val freq = if (f < 5_000) 100.0 else 8_000.0
            val sample = (0.6 * sin(2.0 * PI * freq * f / 48_000.0) * 32_767.0).toInt().coerceIn(-32_768, 32_767)
            pcm16[f * 2] = (sample and 0xFF).toByte()
            pcm16[f * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }
        val untouchedSnapshot = pcm16.copyOf()
        tap.appendPcmBytesReadOnly(
            encoding = PcmEncodingType.PCM_16BIT,
            srcBytes = pcm16,
            byteOffset = 0,
            byteCount = pcm16.size,
            channels = 1,
            trackFrameAfterWrite = totalFrames.toLong(),
        )
        assertTrue("Bit-Perfect read-only guarantee: PCM bytes must never be mutated", pcm16.contentEquals(untouchedSnapshot))

        val analyzer = SpectrumAnalyzer()
        val window = FloatArray(SpectrumAnalyzerDsp.FFT_SIZE)
        val bands = FloatArray(SpectrumAnalyzerDsp.BAND_COUNT)

        // When AudioTrack playbackHeadPosition is at frame 3,500 (still in the 100 Hz segment,
        // even though the audio thread already wrote up to frame 10,000), FFT must see 100 Hz bass!
        val rateAtBass = tap.sampleWindowAtTrackFrame(playbackHeadFrame = 3_500L, outMonoSamples = window)
        assertEquals(48_000, rateAtBass)
        analyzer.computeSpectrumBands(window, rateAtBass, bands)
        val bassPeak = indexOfMax(bands)
        assertTrue("Head-synced window at frame 3500 must show 100 Hz bass, got band $bassPeak", bassPeak in 4..8)

        // When playbackHeadPosition advances to frame 9,500, FFT must see 8,000 Hz treble!
        val rateAtTreble = tap.sampleWindowAtTrackFrame(playbackHeadFrame = 9_500L, outMonoSamples = window)
        assertEquals(48_000, rateAtTreble)
        analyzer.computeSpectrumBands(window, rateAtTreble, bands)
        val treblePeak = indexOfMax(bands)
        assertTrue("Head-synced window at frame 9500 must show 8,000 Hz treble, got band $treblePeak", treblePeak in 26..30)
    }
}
