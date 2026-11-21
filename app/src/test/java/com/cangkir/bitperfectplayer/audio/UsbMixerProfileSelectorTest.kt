package com.cangkir.bitperfectplayer.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsbMixerProfileSelectorTest {

    @Test
    fun bitPerfectOptIn_prefersExactBitPerfectMatchAndProvidesDefaultFallback() {
        val candidates = listOf(
            UsbMixerCandidate(
                0,
                isBitPerfect = false,
                sampleRateHz = 48_000,
                androidEncoding = PcmStreamRules.ANDROID_ENCODING_PCM_16BIT,
                channelCount = 2,
            ),
            UsbMixerCandidate(
                1,
                isBitPerfect = false,
                sampleRateHz = 96_000,
                androidEncoding = PcmStreamRules.ANDROID_ENCODING_PCM_FLOAT,
                channelCount = 2,
            ),
            UsbMixerCandidate(
                2,
                isBitPerfect = true,
                sampleRateHz = 96_000,
                androidEncoding = PcmStreamRules.ANDROID_ENCODING_PCM_24BIT_PACKED,
                channelCount = 2,
            ),
        )

        val plan = UsbMixerProfileSelector.buildNegotiationPlan(
            candidates = candidates,
            safetyMode = AudioOutputMode.BIT_PERFECT_OPT_IN,
            targetSampleRateHz = 96_000,
            targetAndroidEncoding = PcmStreamRules.ANDROID_ENCODING_PCM_24BIT_PACKED,
            targetChannelCount = 2,
        )

        assertEquals(UsbMixerSelectionKind.BIT_PERFECT, plan.primaryKind)
        assertEquals(2, plan.primaryCandidateIndex)
        assertEquals(1, plan.fallbackDefaultCandidateIndex)
    }

    @Test
    fun bitPerfectOptIn_fallsBackToDefaultNativeRateWhenZeroBitPerfectProfilesExist() {
        val candidates = listOf(
            UsbMixerCandidate(
                0,
                isBitPerfect = false,
                sampleRateHz = 48_000,
                androidEncoding = PcmStreamRules.ANDROID_ENCODING_PCM_FLOAT,
                channelCount = 2,
            ),
            UsbMixerCandidate(
                1,
                isBitPerfect = false,
                sampleRateHz = 96_000,
                androidEncoding = PcmStreamRules.ANDROID_ENCODING_PCM_16BIT,
                channelCount = 2,
            ),
            UsbMixerCandidate(
                2,
                isBitPerfect = false,
                sampleRateHz = 96_000,
                androidEncoding = PcmStreamRules.ANDROID_ENCODING_PCM_32BIT,
                channelCount = 2,
            ),
            UsbMixerCandidate(
                3,
                isBitPerfect = false,
                sampleRateHz = 96_000,
                androidEncoding = PcmStreamRules.ANDROID_ENCODING_PCM_FLOAT,
                channelCount = 2,
            ),
        )

        val plan = UsbMixerProfileSelector.buildNegotiationPlan(
            candidates = candidates,
            safetyMode = AudioOutputMode.BIT_PERFECT_OPT_IN,
            targetSampleRateHz = 96_000,
            targetAndroidEncoding = PcmStreamRules.ANDROID_ENCODING_PCM_24BIT_PACKED,
            targetChannelCount = 2,
        )

        // Exact encoding absent -> prefers PCM_FLOAT > PCM_32BIT > PCM_24BIT_PACKED > PCM_16BIT at native 96 kHz
        assertEquals(UsbMixerSelectionKind.DEFAULT_NATIVE_RATE, plan.primaryKind)
        assertEquals(3, plan.primaryCandidateIndex)
        assertNull(plan.fallbackDefaultCandidateIndex)
    }

    @Test
    fun bitPerfectOptIn_prefersExactEncodingInDefaultNativeRateFallback() {
        val candidates = listOf(
            UsbMixerCandidate(
                0,
                isBitPerfect = false,
                sampleRateHz = 96_000,
                androidEncoding = PcmStreamRules.ANDROID_ENCODING_PCM_FLOAT,
                channelCount = 2,
            ),
            UsbMixerCandidate(
                1,
                isBitPerfect = false,
                sampleRateHz = 96_000,
                androidEncoding = PcmStreamRules.ANDROID_ENCODING_PCM_24BIT_PACKED,
                channelCount = 2,
            ),
        )

        val plan = UsbMixerProfileSelector.buildNegotiationPlan(
            candidates = candidates,
            safetyMode = AudioOutputMode.BIT_PERFECT_OPT_IN,
            targetSampleRateHz = 96_000,
            targetAndroidEncoding = PcmStreamRules.ANDROID_ENCODING_PCM_24BIT_PACKED,
            targetChannelCount = 2,
        )

        assertEquals(UsbMixerSelectionKind.DEFAULT_NATIVE_RATE, plan.primaryKind)
        assertEquals(1, plan.primaryCandidateIndex)
    }

    @Test
    fun safeMode_strictlyNeverSelectsBitPerfectEvenWhenExactBitPerfectProfileExists() {
        val candidates = listOf(
            UsbMixerCandidate(
                0,
                isBitPerfect = true,
                sampleRateHz = 96_000,
                androidEncoding = PcmStreamRules.ANDROID_ENCODING_PCM_24BIT_PACKED,
                channelCount = 2,
            ),
            UsbMixerCandidate(
                1,
                isBitPerfect = false,
                sampleRateHz = 96_000,
                androidEncoding = PcmStreamRules.ANDROID_ENCODING_PCM_FLOAT,
                channelCount = 2,
            ),
        )

        val plan = UsbMixerProfileSelector.buildNegotiationPlan(
            candidates = candidates,
            safetyMode = AudioOutputMode.SAFE_VOLUME_MODE,
            targetSampleRateHz = 96_000,
            targetAndroidEncoding = PcmStreamRules.ANDROID_ENCODING_PCM_24BIT_PACKED,
            targetChannelCount = 2,
        )

        assertEquals(UsbMixerSelectionKind.DEFAULT_NATIVE_RATE, plan.primaryKind)
        assertEquals(1, plan.primaryCandidateIndex)
        assertNull(plan.fallbackDefaultCandidateIndex)

        // When ONLY bit-perfect profiles exist, Safe Mode returns NONE rather than risking fixed-volume output
        val onlyBitPerfectPlan = UsbMixerProfileSelector.buildNegotiationPlan(
            candidates = listOf(candidates[0]),
            safetyMode = AudioOutputMode.SAFE_VOLUME_MODE,
            targetSampleRateHz = 96_000,
            targetAndroidEncoding = PcmStreamRules.ANDROID_ENCODING_PCM_24BIT_PACKED,
            targetChannelCount = 2,
        )
        assertEquals(UsbMixerSelectionKind.NONE, onlyBitPerfectPlan.primaryKind)
        assertNull(onlyBitPerfectPlan.primaryCandidateIndex)
    }

    @Test
    fun returnsNoneWhenSampleRateOrChannelCountDoesNotMatch() {
        val candidates = listOf(
            UsbMixerCandidate(
                0,
                isBitPerfect = false,
                sampleRateHz = 48_000,
                androidEncoding = PcmStreamRules.ANDROID_ENCODING_PCM_FLOAT,
                channelCount = 2,
            ),
            UsbMixerCandidate(
                1,
                isBitPerfect = false,
                sampleRateHz = 96_000,
                androidEncoding = PcmStreamRules.ANDROID_ENCODING_PCM_FLOAT,
                channelCount = 1,
            ),
        )

        val plan = UsbMixerProfileSelector.buildNegotiationPlan(
            candidates = candidates,
            safetyMode = AudioOutputMode.BIT_PERFECT_OPT_IN,
            targetSampleRateHz = 96_000,
            targetAndroidEncoding = PcmStreamRules.ANDROID_ENCODING_PCM_FLOAT,
            targetChannelCount = 2,
        )

        assertEquals(UsbMixerSelectionKind.NONE, plan.primaryKind)
        assertNull(plan.primaryCandidateIndex)
        assertNull(plan.fallbackDefaultCandidateIndex)
    }
}
