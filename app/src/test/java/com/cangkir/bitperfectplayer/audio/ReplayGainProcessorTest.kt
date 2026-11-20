package com.cangkir.bitperfectplayer.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

class ReplayGainProcessorTest {

    private val sampleTags = ReplayGainMetadata(
        trackGainDb = -4.2f,
        trackPeak = 0.98f,
        albumGainDb = -6.0f,
        albumPeak = 0.99f,
    )

    @Test
    fun offMode_alwaysProducesUnityGainWithZeroModification() {
        val res = ReplayGainProcessor.resolve(
            config = ReplayGainConfig(mode = ReplayGainMode.OFF, preampDb = 3.0f, preventClipping = true),
            metadata = sampleTags,
            safetyMode = AudioOutputMode.SAFE_VOLUME_MODE,
        )
        assertEquals(1.0f, res.linearGain, 1e-6f)
        assertNull(res.appliedGainDb)
        assertFalse(res.isModifyingSamples)
        assertEquals(ReplayGainDiagnosticState.OFF, res.diagnosticState)
        assertEquals("ReplayGain: OFF", res.diagnosticLabel)
    }

    @Test
    fun bitPerfectMode_unconditionallyBypassesReplayGainAcrossTrackAndAlbumModes() {
        for (mode in listOf(ReplayGainMode.TRACK, ReplayGainMode.ALBUM)) {
            val res = ReplayGainProcessor.resolve(
                config = ReplayGainConfig(mode = mode, preampDb = 4.0f, preventClipping = false),
                metadata = sampleTags,
                safetyMode = AudioOutputMode.BIT_PERFECT_OPT_IN,
            )
            assertEquals(1.0f, res.linearGain, 1e-6f)
            assertNull(res.appliedGainDb)
            assertFalse(res.peakClamped)
            assertFalse(res.isModifyingSamples)
            assertEquals(ReplayGainDiagnosticState.BYPASSED_BIT_PERFECT, res.diagnosticState)
            assertEquals("ReplayGain: Bypassed (Bit-Perfect Active)", res.diagnosticLabel)
        }
    }

    @Test
    fun safeModeTrackAndAlbumGain_computesExactDbLinearMathAndPreampClamp() {
        val trackRes = ReplayGainProcessor.resolve(
            config = ReplayGainConfig(mode = ReplayGainMode.TRACK, preampDb = 0.0f, preventClipping = true),
            metadata = sampleTags,
            safetyMode = AudioOutputMode.SAFE_VOLUME_MODE,
        )
        val expectedLinear = 10.0.pow(-4.2 / 20.0).toFloat()
        assertEquals(expectedLinear, trackRes.linearGain, 1e-5f)
        assertFalse(trackRes.peakClamped)
        assertTrue(trackRes.isModifyingSamples)
        assertEquals("ReplayGain: Track (-4.2 dB, Safe Mode)", trackRes.diagnosticLabel)

        // Preamp out-of-range (+12 dB) is clamped to +6.0 dB: album -6.0 + 6.0 = 0.0 dB -> linear 1.0
        val albumRes = ReplayGainProcessor.resolve(
            config = ReplayGainConfig(mode = ReplayGainMode.ALBUM, preampDb = 12.0f, preventClipping = true),
            metadata = sampleTags,
            safetyMode = AudioOutputMode.SAFE_VOLUME_MODE,
        )
        assertEquals(1.0f, albumRes.linearGain, 1e-5f)
        assertFalse(albumRes.peakClamped)
        assertEquals("ReplayGain: Album (0.0 dB, Safe Mode)", albumRes.diagnosticLabel)
    }

    @Test
    fun preventClipping_clampsLinearGainToInversePeakWhenGainTimesPeakExceedsOne() {
        val quietTags = ReplayGainMetadata(trackGainDb = +6.0f, trackPeak = 0.8f)
        // +6.0 dB -> linear ~1.995; 1.995 * 0.8 = 1.596 > 1.0 -> clamped to 1 / 0.8 = 1.25 (+1.9 dB)
        val protected = ReplayGainProcessor.resolve(
            config = ReplayGainConfig(mode = ReplayGainMode.TRACK, preampDb = 0.0f, preventClipping = true),
            metadata = quietTags,
            safetyMode = AudioOutputMode.SAFE_VOLUME_MODE,
        )
        assertTrue(protected.peakClamped)
        assertEquals(1.25f, protected.linearGain, 1e-5f)
        assertTrue(protected.diagnosticLabel.contains("Peak Clamped"))

        // With preventClipping = false, full linear gain is preserved
        val unprotected = ReplayGainProcessor.resolve(
            config = ReplayGainConfig(mode = ReplayGainMode.TRACK, preampDb = 0.0f, preventClipping = false),
            metadata = quietTags,
            safetyMode = AudioOutputMode.SAFE_VOLUME_MODE,
        )
        assertFalse(unprotected.peakClamped)
        assertEquals(10.0.pow(6.0 / 20.0).toFloat(), unprotected.linearGain, 1e-5f)
    }

    @Test
    fun missingTags_andAlbumFallbackToTrackTag_behaveCleanly() {
        val noTags = ReplayGainProcessor.resolve(
            config = ReplayGainConfig(mode = ReplayGainMode.TRACK, preampDb = 3.0f),
            metadata = null,
            safetyMode = AudioOutputMode.SAFE_VOLUME_MODE,
        )
        assertEquals(1.0f, noTags.linearGain, 1e-6f)
        assertFalse(noTags.isModifyingSamples)
        assertEquals(ReplayGainDiagnosticState.NO_TAGS, noTags.diagnosticState)
        assertEquals("ReplayGain: Track (No Tags, Safe Mode)", noTags.diagnosticLabel)

        val albumFallback = ReplayGainProcessor.resolve(
            config = ReplayGainConfig(mode = ReplayGainMode.ALBUM),
            metadata = ReplayGainMetadata(trackGainDb = -3.0f, trackPeak = 0.9f),
            safetyMode = AudioOutputMode.SAFE_VOLUME_MODE,
        )
        assertEquals(ReplayGainDiagnosticState.ACTIVE_TRACK, albumFallback.diagnosticState)
        assertEquals("ReplayGain: Track (-3.0 dB, Safe Mode)", albumFallback.diagnosticLabel)
    }

    @Test
    fun inPlaceSampleScalers_scaleFloat32Pcm16AndPcm24WithSaturationClamping() {
        // Float32
        val floats = floatArrayOf(0.5f, -0.8f, 0.9f)
        ReplayGainProcessor.applyGainToFloatSamples(floats, floats.size, 0.5f)
        assertEquals(0.25f, floats[0], 1e-6f)
        assertEquals(-0.4f, floats[1], 1e-6f)
        assertEquals(0.45f, floats[2], 1e-6f)

        // Float32 clipping saturation
        val hotFloats = floatArrayOf(0.8f, -0.8f)
        ReplayGainProcessor.applyGainToFloatSamples(hotFloats, hotFloats.size, 2.0f)
        assertEquals(1.0f, hotFloats[0], 1e-6f)
        assertEquals(-1.0f, hotFloats[1], 1e-6f)

        // PCM_16BIT LE
        val pcm16 = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(20_000)
            .putShort(-10_000)
            .putShort(30_000)
            .array()
        ReplayGainProcessor.applyGainToPcm16LittleEndian(pcm16, 0, pcm16.size, 0.5f)
        val out16 = ByteBuffer.wrap(pcm16).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(10_000.toShort(), out16.short)
        assertEquals((-5_000).toShort(), out16.short)
        assertEquals(15_000.toShort(), out16.short)

        // PCM_24BIT_PACKED LE
        val pcm24 = ByteArray(6)
        val pos24 = 4_000_000
        val neg24 = -2_000_000
        pcm24[0] = (pos24 and 0xFF).toByte()
        pcm24[1] = ((pos24 shr 8) and 0xFF).toByte()
        pcm24[2] = ((pos24 shr 16) and 0xFF).toByte()
        pcm24[3] = (neg24 and 0xFF).toByte()
        pcm24[4] = ((neg24 shr 8) and 0xFF).toByte()
        pcm24[5] = ((neg24 shr 16) and 0xFF).toByte()
        ReplayGainProcessor.applyGainToPcm24PackedLittleEndian(pcm24, 0, pcm24.size, 0.5f)
        val decodedPos = (pcm24[0].toInt() and 0xFF) or ((pcm24[1].toInt() and 0xFF) shl 8) or (pcm24[2].toInt() shl 16)
        val decodedNeg = (pcm24[3].toInt() and 0xFF) or ((pcm24[4].toInt() and 0xFF) shl 8) or (pcm24[5].toInt() shl 16)
        assertEquals(2_000_000, decodedPos)
        assertEquals(-1_000_000, decodedNeg)
    }
}
