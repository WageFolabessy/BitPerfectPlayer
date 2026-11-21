package com.cangkir.bitperfectplayer.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioDiagnosticMapperTest {

    @Test
    fun bitPerfectActiveOnApi34Usb_reportsVerifiedHalExactHzAndUnverifiableDacStage() {
        val snapshot = AudioDiagnosticMapper.map(
            AudioDiagnosticInput(
                isTrackLoaded = true,
                sourceContainer = "WAV",
                sourceCodec = "PCM",
                sourceSampleRateHz = 96_000,
                sourceBitDepth = 24,
                sourceHeaderVerified = true,
                decodedPcmEncodingLabel = "PCM_24BIT_PACKED",
                decodedEffectiveBitDepth = 24,
                decodedSampleRateHz = 96_000,
                decodedChannelCount = 2,
                decoderName = "WavPcmDecoder",
                audioTrackSampleRateHz = 96_000,
                audioTrackEncodingLabel = "PCM_24BIT_PACKED",
                safetyMode = AudioOutputMode.BIT_PERFECT_OPT_IN,
                isHardwareVolumeVerified = false,
                apiLevel = 34,
                outputDeviceName = "KZ EDX Pro 2 USB-C",
                outputConnectionType = OutputConnectionType.USB_HEADSET,
                negotiatedMixerBehavior = NegotiatedMixerBehavior.BIT_PERFECT,
                negotiatedMixerSummary = "MIXER_BEHAVIOR_BIT_PERFECT (96,000 Hz, PCM_24BIT_PACKED)",
            ),
        )

        assertTrue(snapshot.isBitPerfectMixerActive)
        assertFalse(snapshot.isBitDepthDownConvertedByDecoder)
        assertEquals(AudioDiagnosticMapper.BIT_PERFECT_ACTIVE_LABEL, snapshot.pathStatusHeadline)
        assertEquals(6, snapshot.stages.size)

        // Exact Hz formatting (never kHz)
        assertTrue(snapshot.sourceFormatSummary.contains("96,000 Hz"))
        assertFalse(snapshot.sourceFormatSummary.contains("kHz"))
        assertTrue(snapshot.decodedPcmSummary.contains("96,000 Hz"))
        assertFalse(snapshot.decodedPcmSummary.contains("kHz"))
        assertTrue(snapshot.stages[2].summary.contains("96,000 Hz"))
        assertTrue(snapshot.stages[2].summary.contains("PCM_24BIT_PACKED"))

        // Stage 1..3 Verified, Stage 4 Verified (HAL negotiated), Stage 5 Indicated, Stage 6 ALWAYS Unverifiable
        assertEquals(VerificationLevel.VERIFIED, snapshot.stages[0].verificationLevel)
        assertEquals(VerificationLevel.VERIFIED, snapshot.stages[1].verificationLevel)
        assertEquals(VerificationLevel.VERIFIED, snapshot.stages[2].verificationLevel)
        assertEquals(VerificationLevel.VERIFIED, snapshot.stages[3].verificationLevel)
        assertEquals(VerificationLevel.INDICATED, snapshot.stages[4].verificationLevel)
        assertEquals(VerificationLevel.UNVERIFIABLE, snapshot.stages[5].verificationLevel)
        assertEquals(AudioPathStage.DAC_ANALOG_CONVERSION, snapshot.stages[5].stage)
        assertEquals(AudioDiagnosticSnapshot.MAXIMUM_VERIFICATION_CLAIM_NOTICE, snapshot.maximumClaimNotice)
        assertTrue(snapshot.dspEqVolumeStatusSummary.contains("EQ: OFF"))
        assertTrue(snapshot.dspEqVolumeStatusSummary.contains("DSP: OFF"))
        assertTrue(snapshot.dspEqVolumeStatusSummary.contains("Fixed 100%"))
    }

    @Test
    fun cleanNativeFallback_deduplicatesFlacLabelAndNeverClaimsBitPerfect() {
        val snapshot = AudioDiagnosticMapper.map(
            AudioDiagnosticInput(
                isTrackLoaded = true,
                sourceContainer = "FLAC",
                sourceCodec = "FLAC",
                sourceSampleRateHz = 44_100,
                sourceBitDepth = 16,
                sourceHeaderVerified = true,
                decodedPcmEncodingLabel = "PCM_16BIT",
                decodedEffectiveBitDepth = 16,
                decodedSampleRateHz = 44_100,
                decodedChannelCount = 2,
                decoderName = "MediaCodecAudioDecoder",
                audioTrackSampleRateHz = 44_100,
                audioTrackEncodingLabel = "PCM_16BIT",
                safetyMode = AudioOutputMode.BIT_PERFECT_OPT_IN,
                apiLevel = 34,
                outputDeviceName = "USB-C DAC",
                outputConnectionType = OutputConnectionType.USB_DEVICE,
                negotiatedMixerBehavior = NegotiatedMixerBehavior.DEFAULT_MIXER_FALLBACK,
                negotiatedMixerSummary = "Default System Mixer (Bit-Perfect Unavailable)",
            ),
        )

        assertFalse(snapshot.isBitPerfectMixerActive)
        assertEquals(AudioDiagnosticMapper.FALLBACK_UNSUPPORTED_LABEL, snapshot.pathStatusHeadline)
        assertEquals("FLAC · 16-bit · 44,100 Hz", snapshot.sourceFormatSummary)
        assertFalse(snapshot.sourceFormatSummary.contains("FLAC (FLAC)"))
        assertTrue(snapshot.dspEqVolumeStatusSummary.contains("Fallback Mixer Active"))
        assertEquals(VerificationLevel.UNVERIFIABLE, snapshot.stages.last().verificationLevel)
    }

    @Test
    fun safeMode_reportsSafeVolumeActive_andFlagsDecoderDownConversionHonestly() {
        val snapshot = AudioDiagnosticMapper.map(
            AudioDiagnosticInput(
                isTrackLoaded = true,
                sourceContainer = "FLAC",
                sourceCodec = "FLAC",
                sourceSampleRateHz = 48_000,
                sourceBitDepth = 24,
                sourceHeaderVerified = true,
                decodedPcmEncodingLabel = "PCM_16BIT",
                decodedEffectiveBitDepth = 16,
                decodedSampleRateHz = 48_000,
                decodedChannelCount = 2,
                decoderName = "MediaCodec (c2.android.flac.decoder)",
                audioTrackSampleRateHz = 48_000,
                audioTrackEncodingLabel = "PCM_16BIT",
                safetyMode = AudioOutputMode.SAFE_VOLUME_MODE,
                apiLevel = 34,
                outputDeviceName = "KZ EDX Pro 2 USB-C",
                outputConnectionType = OutputConnectionType.USB_HEADSET,
                negotiatedMixerBehavior = NegotiatedMixerBehavior.NOT_APPLICABLE_SAFE_MODE,
            ),
        )

        assertFalse(snapshot.isBitPerfectMixerActive)
        assertTrue(snapshot.isBitDepthDownConvertedByDecoder)
        assertEquals(AudioDiagnosticMapper.SAFE_MODE_LABEL, snapshot.pathStatusHeadline)
        assertTrue(snapshot.decodedPcmSummary.contains("48,000 Hz"))
        assertTrue(snapshot.decodedPcmSummary.contains("down-converted from 24-bit"))
        assertTrue(snapshot.dspEqVolumeStatusSummary.contains("Safe Mode"))
        assertEquals(VerificationLevel.UNVERIFIABLE, snapshot.stages[5].verificationLevel)
    }

    @Test
    fun nonUsbAndPreApi34_emitHonestFallbackLabels() {
        val nonUsb = AudioDiagnosticMapper.map(
            AudioDiagnosticInput(
                isTrackLoaded = true,
                safetyMode = AudioOutputMode.BIT_PERFECT_OPT_IN,
                apiLevel = 34,
                outputConnectionType = OutputConnectionType.INTERNAL_SPEAKER,
                negotiatedMixerBehavior = NegotiatedMixerBehavior.BIT_PERFECT,
            ),
        )
        assertFalse(nonUsb.isBitPerfectMixerActive)
        assertEquals(AudioDiagnosticMapper.NON_USB_FALLBACK_LABEL, nonUsb.pathStatusHeadline)

        val api30 = AudioDiagnosticMapper.map(
            AudioDiagnosticInput(
                isTrackLoaded = true,
                safetyMode = AudioOutputMode.BIT_PERFECT_OPT_IN,
                apiLevel = 30,
                outputConnectionType = OutputConnectionType.USB_HEADSET,
                negotiatedMixerBehavior = NegotiatedMixerBehavior.UNSUPPORTED_API_BELOW_34,
            ),
        )
        assertFalse(api30.isBitPerfectMixerActive)
        assertEquals(AudioDiagnosticMapper.API_BELOW_34_FALLBACK_LABEL, api30.pathStatusHeadline)
    }

    @Test
    fun stage3Diagnostics_reportsReplayGainOffSafeModeActiveAndBitPerfectBypassHonestly() {
        // 1. ReplayGain OFF
        val offSnapshot = AudioDiagnosticMapper.map(
            AudioDiagnosticInput(
                isTrackLoaded = true,
                audioTrackSampleRateHz = 96_000,
                audioTrackEncodingLabel = "PCM_FLOAT (32-bit IEEE)",
                safetyMode = AudioOutputMode.SAFE_VOLUME_MODE,
                replayGainSummary = "ReplayGain: OFF",
                isReplayGainModifyingSamples = false,
            ),
        )
        assertTrue(offSnapshot.stages[2].summary.contains("ReplayGain: OFF"))
        assertTrue(offSnapshot.stages[2].summary.contains("Zero App DSP"))

        // 2. ReplayGain Active in Safe Mode (does NOT falsely claim Zero App DSP)
        val safeActive = AudioDiagnosticMapper.map(
            AudioDiagnosticInput(
                isTrackLoaded = true,
                audioTrackSampleRateHz = 96_000,
                audioTrackEncodingLabel = "PCM_FLOAT (32-bit IEEE)",
                safetyMode = AudioOutputMode.SAFE_VOLUME_MODE,
                replayGainSummary = "ReplayGain: Track (-4.2 dB, Safe Mode)",
                isReplayGainModifyingSamples = true,
            ),
        )
        assertTrue(safeActive.stages[2].summary.contains("ReplayGain: Track (-4.2 dB, Safe Mode)"))
        assertFalse(safeActive.stages[2].summary.contains("Zero App DSP"))
        assertTrue(safeActive.dspEqVolumeStatusSummary.contains("ReplayGain: Track (-4.2 dB, Safe Mode)"))

        // 3. Bit-Perfect Opt-In strictly forces ReplayGain Bypassed label and Zero App DSP
        val bitPerfectBypass = AudioDiagnosticMapper.map(
            AudioDiagnosticInput(
                isTrackLoaded = true,
                audioTrackSampleRateHz = 96_000,
                audioTrackEncodingLabel = "PCM_24BIT_PACKED",
                safetyMode = AudioOutputMode.BIT_PERFECT_OPT_IN,
                outputConnectionType = OutputConnectionType.USB_HEADSET,
                apiLevel = 34,
                negotiatedMixerBehavior = NegotiatedMixerBehavior.BIT_PERFECT,
                replayGainSummary = "ReplayGain: Bypassed (Bit-Perfect Active)",
                isReplayGainModifyingSamples = false,
            ),
        )
        assertTrue(bitPerfectBypass.stages[2].summary.contains("Zero App DSP"))
        assertTrue(bitPerfectBypass.stages[2].summary.contains("ReplayGain: Bypassed (Bit-Perfect Active)"))
        assertTrue(bitPerfectBypass.dspEqVolumeStatusSummary.contains("ReplayGain: Bypassed (Bit-Perfect Active)"))
    }

    @Test
    fun defaultNativeRateUsbMixer_isReportedHonestlyInBothSafeModeAndBitPerfectFallback_neverClaimingBitPerfect() {
        // 1. Bit-Perfect requested, HAL reported 0 bit-perfect profiles -> negotiated MIXER_BEHAVIOR_DEFAULT @ 96 kHz
        val bpFallbackNativeRate = AudioDiagnosticMapper.map(
            AudioDiagnosticInput(
                isTrackLoaded = true,
                sourceContainer = "FLAC",
                sourceCodec = "FLAC",
                sourceSampleRateHz = 96_000,
                sourceBitDepth = 24,
                sourceHeaderVerified = true,
                decodedPcmEncodingLabel = "PCM 24-bit Packed",
                decodedEffectiveBitDepth = 24,
                decodedSampleRateHz = 96_000,
                decodedChannelCount = 2,
                decoderName = "MediaExtractor Direct PCM (PCM 24-bit Packed)",
                audioTrackSampleRateHz = 96_000,
                audioTrackEncodingLabel = "PCM_24BIT_PACKED",
                safetyMode = AudioOutputMode.BIT_PERFECT_OPT_IN,
                apiLevel = 36,
                outputDeviceName = "KZ EDX Pro 2 USB-C",
                outputConnectionType = OutputConnectionType.USB_HEADSET,
                negotiatedMixerBehavior = NegotiatedMixerBehavior.DEFAULT_NATIVE_RATE,
                negotiatedMixerSummary =
                "MIXER_BEHAVIOR_DEFAULT (96,000 Hz, PCM_FLOAT — Native Rate, Bit-Perfect Unavailable, Volume Active)",
            ),
        )
        assertFalse(bpFallbackNativeRate.isBitPerfectMixerActive)
        assertTrue(bpFallbackNativeRate.isNativeSampleRateMixerActive)
        assertEquals(AudioDiagnosticMapper.BIT_PERFECT_FALLBACK_NATIVE_RATE_LABEL, bpFallbackNativeRate.pathStatusHeadline)
        assertEquals(VerificationLevel.VERIFIED, bpFallbackNativeRate.stages[3].verificationLevel)
        assertTrue(bpFallbackNativeRate.dspEqVolumeStatusSummary.contains("System Volume Active"))
        assertEquals(VerificationLevel.UNVERIFIABLE, bpFallbackNativeRate.stages[5].verificationLevel)

        // 2. Safe Mode on API 34+ USB with MIXER_BEHAVIOR_DEFAULT @ 96 kHz
        val safeModeNativeRate = AudioDiagnosticMapper.map(
            AudioDiagnosticInput(
                isTrackLoaded = true,
                sourceContainer = "FLAC",
                sourceCodec = "FLAC",
                sourceSampleRateHz = 96_000,
                sourceBitDepth = 24,
                sourceHeaderVerified = true,
                decodedPcmEncodingLabel = "PCM 24-bit Packed",
                decodedEffectiveBitDepth = 24,
                decodedSampleRateHz = 96_000,
                decodedChannelCount = 2,
                decoderName = "MediaExtractor Direct PCM (PCM 24-bit Packed)",
                audioTrackSampleRateHz = 96_000,
                audioTrackEncodingLabel = "PCM_24BIT_PACKED",
                safetyMode = AudioOutputMode.SAFE_VOLUME_MODE,
                apiLevel = 36,
                outputDeviceName = "KZ EDX Pro 2 USB-C",
                outputConnectionType = OutputConnectionType.USB_HEADSET,
                negotiatedMixerBehavior = NegotiatedMixerBehavior.DEFAULT_NATIVE_RATE,
                negotiatedMixerSummary = "MIXER_BEHAVIOR_DEFAULT (96,000 Hz, PCM_FLOAT — Native Rate, Safe Mode Volume Active)",
            ),
        )
        assertFalse(safeModeNativeRate.isBitPerfectMixerActive)
        assertTrue(safeModeNativeRate.isNativeSampleRateMixerActive)
        assertEquals(AudioDiagnosticMapper.SAFE_MODE_NATIVE_RATE_LABEL, safeModeNativeRate.pathStatusHeadline)
        assertEquals(VerificationLevel.VERIFIED, safeModeNativeRate.stages[3].verificationLevel)
        assertTrue(safeModeNativeRate.dspEqVolumeStatusSummary.contains("Safe Mode (System Volume Active)"))
    }
}
