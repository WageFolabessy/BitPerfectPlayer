package com.cangkir.bitperfectplayer.audio

/**
 * Outcome tier chosen by [UsbMixerProfileSelector] for Android 14+ (`API 34+`) USB
 * `AudioManager.setPreferredMixerAttributes(...)` negotiation.
 */
enum class UsbMixerSelectionKind {
    BIT_PERFECT,
    DEFAULT_NATIVE_RATE,
    NONE,
}

/**
 * Pure-Kotlin projection of an Android 14+ `AudioMixerAttributes` profile reported by
 * `AudioManager.getSupportedMixerAttributes(usbDevice)` (zero `android.*` imports).
 */
data class UsbMixerCandidate(
    val index: Int,
    val isBitPerfect: Boolean,
    val sampleRateHz: Int,
    val androidEncoding: Int,
    val channelCount: Int,
    val channelMask: Int = 0,
)

/**
 * Ordered negotiation plan for API 34+ USB mixer selection.
 *
 * When [primaryKind] is [UsbMixerSelectionKind.BIT_PERFECT], [fallbackDefaultCandidateIndex]
 * optionally carries the best native-rate `MIXER_BEHAVIOR_DEFAULT` profile in case the HAL
 * rejects applying the bit-perfect attribute at runtime.
 */
data class UsbMixerNegotiationPlan(
    val primaryCandidateIndex: Int?,
    val primaryKind: UsbMixerSelectionKind,
    val fallbackDefaultCandidateIndex: Int? = null,
)

/**
 * Pure-Kotlin Android 14+ USB mixer profile selector (zero `android.*` imports).
 *
 * Priority & Hearing-Safety Rules:
 * 1. In [AudioOutputMode.BIT_PERFECT_OPT_IN]:
 *    - Prefer `MIXER_BEHAVIOR_BIT_PERFECT` with exact sample rate, encoding, and channel match.
 *    - If 0 matching bit-perfect profiles exist (or applying bit-perfect fails), fall back to
 *      `MIXER_BEHAVIOR_DEFAULT` matching the track's native sample rate and channel count,
 *      preferring the highest compatible PCM precision (`exact > PCM_FLOAT > PCM_32BIT > PCM_24BIT_PACKED > PCM_16BIT`).
 * 2. In [AudioOutputMode.SAFE_VOLUME_MODE]:
 *    - **NEVER** select `MIXER_BEHAVIOR_BIT_PERFECT` (preserving system volume protection for
 *      high-sensitivity IEMs).
 *    - Allow selecting `MIXER_BEHAVIOR_DEFAULT` strictly for native sample-rate matching on USB
 *      so 44.1 / 48 / 96 / 192 kHz tracks avoid unnecessary 48 kHz OS resampling while keeping
 *      system volume control 100% active.
 */
object UsbMixerProfileSelector {

    fun buildNegotiationPlan(
        candidates: List<UsbMixerCandidate>,
        safetyMode: AudioOutputMode,
        targetSampleRateHz: Int,
        targetAndroidEncoding: Int,
        targetChannelCount: Int,
        targetChannelMask: Int = 0,
    ): UsbMixerNegotiationPlan {
        if (candidates.isEmpty() || targetSampleRateHz <= 0 || targetChannelCount <= 0) {
            return UsbMixerNegotiationPlan(null, UsbMixerSelectionKind.NONE, null)
        }

        val rateAndChannelMatches = candidates.filter { candidate ->
            candidate.sampleRateHz == targetSampleRateHz &&
                isChannelCompatible(candidate, targetChannelCount, targetChannelMask)
        }
        if (rateAndChannelMatches.isEmpty()) {
            return UsbMixerNegotiationPlan(null, UsbMixerSelectionKind.NONE, null)
        }

        val bestDefault = rateAndChannelMatches
            .filter { !it.isBitPerfect }
            .maxByOrNull { encodingPreferenceRank(it.androidEncoding, targetAndroidEncoding) }

        if (safetyMode == AudioOutputMode.SAFE_VOLUME_MODE) {
            return if (bestDefault != null) {
                UsbMixerNegotiationPlan(
                    primaryCandidateIndex = bestDefault.index,
                    primaryKind = UsbMixerSelectionKind.DEFAULT_NATIVE_RATE,
                    fallbackDefaultCandidateIndex = null,
                )
            } else {
                UsbMixerNegotiationPlan(null, UsbMixerSelectionKind.NONE, null)
            }
        }

        val exactBitPerfect = rateAndChannelMatches.firstOrNull { candidate ->
            candidate.isBitPerfect && candidate.androidEncoding == targetAndroidEncoding
        }
        return when {
            exactBitPerfect != null -> UsbMixerNegotiationPlan(
                primaryCandidateIndex = exactBitPerfect.index,
                primaryKind = UsbMixerSelectionKind.BIT_PERFECT,
                fallbackDefaultCandidateIndex = bestDefault?.index,
            )
            bestDefault != null -> UsbMixerNegotiationPlan(
                primaryCandidateIndex = bestDefault.index,
                primaryKind = UsbMixerSelectionKind.DEFAULT_NATIVE_RATE,
                fallbackDefaultCandidateIndex = null,
            )
            else -> UsbMixerNegotiationPlan(null, UsbMixerSelectionKind.NONE, null)
        }
    }

    internal fun encodingPreferenceRank(candidateEncoding: Int, targetEncoding: Int): Int {
        if (candidateEncoding == targetEncoding) return 50
        return when (candidateEncoding) {
            PcmStreamRules.ANDROID_ENCODING_PCM_FLOAT -> 40
            PcmStreamRules.ANDROID_ENCODING_PCM_32BIT -> 30
            PcmStreamRules.ANDROID_ENCODING_PCM_24BIT_PACKED -> 20
            PcmStreamRules.ANDROID_ENCODING_PCM_16BIT -> 10
            else -> 0
        }
    }

    private fun isChannelCompatible(candidate: UsbMixerCandidate, targetChannelCount: Int, targetChannelMask: Int): Boolean {
        val maskMatches = targetChannelMask != 0 && candidate.channelMask != 0 && candidate.channelMask == targetChannelMask
        val countMatches = candidate.channelCount == targetChannelCount
        return maskMatches || countMatches
    }
}
