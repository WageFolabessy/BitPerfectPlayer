package com.cangkir.bitperfectplayer.audio

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * User-configurable ReplayGain mode.
 * Defaults to [OFF] for pristine neutral playback out-of-the-box and is strictly bypassed in
 * [AudioOutputMode.BIT_PERFECT_OPT_IN] mode.
 */
enum class ReplayGainMode(val displayName: String) {
    OFF("Off"),
    TRACK("Track"),
    ALBUM("Album"),
    ;

    companion object {
        fun fromStoredName(raw: String?): ReplayGainMode = entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: OFF
    }
}

/**
 * ReplayGain tag values parsed locally from audio file metadata headers.
 */
data class ReplayGainMetadata(
    val trackGainDb: Float? = null,
    val trackPeak: Float? = null,
    val albumGainDb: Float? = null,
    val albumPeak: Float? = null,
) {
    val hasAnyGain: Boolean
        get() = trackGainDb != null || albumGainDb != null
}

/**
 * Persisted ReplayGain user configuration.
 */
data class ReplayGainConfig(
    val mode: ReplayGainMode = ReplayGainMode.OFF,
    val preampDb: Float = 0.0f,
    val preventClipping: Boolean = true,
) {
    val clampedPreampDb: Float
        get() = preampDb.coerceIn(MIN_PREAMP_DB, MAX_PREAMP_DB)

    companion object {
        const val MIN_PREAMP_DB: Float = -6.0f
        const val MAX_PREAMP_DB: Float = 6.0f
    }
}

enum class ReplayGainDiagnosticState {
    OFF,
    ACTIVE_TRACK,
    ACTIVE_ALBUM,
    NO_TAGS,
    BYPASSED_BIT_PERFECT,
}

/**
 * Resolved per-track ReplayGain linear scalar and honest diagnostic summary.
 */
data class ReplayGainResolution(
    val linearGain: Float,
    val appliedGainDb: Float?,
    val peakClamped: Boolean,
    val diagnosticState: ReplayGainDiagnosticState,
    val diagnosticLabel: String,
) {
    val isModifyingSamples: Boolean
        get() = diagnosticState == ReplayGainDiagnosticState.ACTIVE_TRACK ||
            diagnosticState == ReplayGainDiagnosticState.ACTIVE_ALBUM
}

/**
 * Pure-Kotlin ReplayGain gain calculator and zero-allocation in-place PCM gain processor
 * (zero `android.*` imports).
 *
 * **Mandatory Bit-Perfect Invariant:** Whenever `safetyMode == AudioOutputMode.BIT_PERFECT_OPT_IN`,
 * ReplayGain is unconditionally bypassed (`linearGain = 1.0f`, zero sample modification).
 */
object ReplayGainProcessor {
    const val BYPASSED_BIT_PERFECT_OPT_IN_LABEL = "ReplayGain: Bypassed (Bit-Perfect Opt-In)"
    private const val UNITY_EPSILON = 1e-5f
    private const val INT24_MIN = -8_388_608
    private const val INT24_MAX = 8_388_607

    fun resolve(config: ReplayGainConfig, metadata: ReplayGainMetadata?, safetyMode: AudioOutputMode): ReplayGainResolution {
        if (config.mode == ReplayGainMode.OFF) {
            return ReplayGainResolution(
                linearGain = 1.0f,
                appliedGainDb = null,
                peakClamped = false,
                diagnosticState = ReplayGainDiagnosticState.OFF,
                diagnosticLabel = "ReplayGain: OFF",
            )
        }

        // MANDATORY BIT-PERFECT INVARIANT: never modify PCM samples when Bit-Perfect Opt-In is active.
        if (safetyMode == AudioOutputMode.BIT_PERFECT_OPT_IN) {
            return ReplayGainResolution(
                linearGain = 1.0f,
                appliedGainDb = null,
                peakClamped = false,
                diagnosticState = ReplayGainDiagnosticState.BYPASSED_BIT_PERFECT,
                diagnosticLabel = BYPASSED_BIT_PERFECT_OPT_IN_LABEL,
            )
        }

        val (selectedGainDb, selectedPeak, usingAlbumTag) = when (config.mode) {
            ReplayGainMode.TRACK -> {
                val trackGain = metadata?.trackGainDb
                if (trackGain != null) {
                    Triple(trackGain, metadata.trackPeak, false)
                } else {
                    Triple(null, null, false)
                }
            }
            ReplayGainMode.ALBUM -> {
                val albumGain = metadata?.albumGainDb
                if (albumGain != null) {
                    Triple(albumGain, metadata.albumPeak, true)
                } else if (metadata?.trackGainDb != null) {
                    // Standard ReplayGain Album fallback: use Track gain when album tag is absent.
                    Triple(metadata.trackGainDb, metadata.trackPeak, false)
                } else {
                    Triple(null, null, true)
                }
            }
            ReplayGainMode.OFF -> Triple(null, null, false)
        }

        if (selectedGainDb == null || !selectedGainDb.isFinite()) {
            val modeName = config.mode.displayName
            return ReplayGainResolution(
                linearGain = 1.0f,
                appliedGainDb = null,
                peakClamped = false,
                diagnosticState = ReplayGainDiagnosticState.NO_TAGS,
                diagnosticLabel = "ReplayGain: $modeName (No Tags, Safe Mode)",
            )
        }

        val totalDb = selectedGainDb + config.clampedPreampDb
        var linearGain = 10.0.pow(totalDb.toDouble() / 20.0).toFloat()
        if (!linearGain.isFinite() || linearGain <= 0f) {
            linearGain = 1.0f
        }

        var peakClamped = false
        val validPeak = selectedPeak?.takeIf { it.isFinite() && it > 0f }
        if (config.preventClipping && validPeak != null) {
            val predictedPeak = linearGain * validPeak
            if (predictedPeak > 1.0f) {
                linearGain = (1.0 / validPeak.toDouble()).toFloat()
                peakClamped = true
            }
        }

        val effectiveDb = if (peakClamped) {
            (20.0 * log10(linearGain.toDouble())).toFloat()
        } else {
            totalDb
        }

        val tagKindLabel = if (usingAlbumTag) "Album" else "Track"
        val diagState = if (usingAlbumTag) {
            ReplayGainDiagnosticState.ACTIVE_ALBUM
        } else {
            ReplayGainDiagnosticState.ACTIVE_TRACK
        }
        val clampSuffix = if (peakClamped) ", Peak Clamped" else ""
        val label = "ReplayGain: $tagKindLabel (${formatSignedDb(effectiveDb)}$clampSuffix, Safe Mode)"

        return ReplayGainResolution(
            linearGain = linearGain,
            appliedGainDb = effectiveDb,
            peakClamped = peakClamped,
            diagnosticState = diagState,
            diagnosticLabel = label,
        )
    }

    fun needsSampleScaling(linearGain: Float): Boolean = linearGain.isFinite() && linearGain > 0f && abs(linearGain - 1.0f) > UNITY_EPSILON

    /**
     * Zero-allocation in-place float32 PCM gain application (Safe Mode only).
     */
    fun applyGainToFloatSamples(samples: FloatArray, sampleCount: Int, linearGain: Float) {
        if (!needsSampleScaling(linearGain) || sampleCount <= 0) return
        val limit = minOf(sampleCount, samples.size)
        for (i in 0 until limit) {
            val scaled = samples[i] * linearGain
            samples[i] = scaled.coerceIn(-1.0f, 1.0f)
        }
    }

    /**
     * Zero-allocation in-place signed 16-bit little-endian PCM gain application with rounding
     * and saturation clamping (Safe Mode only).
     */
    fun applyGainToPcm16LittleEndian(bytes: ByteArray, offset: Int, byteCount: Int, linearGain: Float) {
        if (!needsSampleScaling(linearGain) || byteCount < 2) return
        val end = minOf(offset + (byteCount and 1.inv()), bytes.size - 1)
        var i = offset
        while (i < end) {
            val lo = bytes[i].toInt() and 0xFF
            val hi = bytes[i + 1].toInt()
            val sample = (hi shl 8) or lo
            val scaled = (sample.toFloat() * linearGain)
                .roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            bytes[i] = (scaled and 0xFF).toByte()
            bytes[i + 1] = ((scaled shr 8) and 0xFF).toByte()
            i += 2
        }
    }

    /**
     * Zero-allocation in-place signed 24-bit packed little-endian PCM gain application in double
     * precision with saturation clamping (Safe Mode only).
     */
    fun applyGainToPcm24PackedLittleEndian(bytes: ByteArray, offset: Int, byteCount: Int, linearGain: Float) {
        if (!needsSampleScaling(linearGain) || byteCount < 3) return
        val gainDouble = linearGain.toDouble()
        val alignedLen = (byteCount / 3) * 3
        val end = minOf(offset + alignedLen, bytes.size - 2)
        var i = offset
        while (i < end) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = bytes[i + 1].toInt() and 0xFF
            val b2 = bytes[i + 2].toInt() // sign-extended
            val sample = (b2 shl 16) or (b1 shl 8) or b0
            val scaled = (sample.toDouble() * gainDouble)
                .roundToInt()
                .coerceIn(INT24_MIN, INT24_MAX)
            bytes[i] = (scaled and 0xFF).toByte()
            bytes[i + 1] = ((scaled shr 8) and 0xFF).toByte()
            bytes[i + 2] = ((scaled shr 16) and 0xFF).toByte()
            i += 3
        }
    }

    /**
     * Zero-allocation in-place signed 32-bit little-endian PCM gain application in double
     * precision with saturation clamping (Safe Mode only).
     */
    fun applyGainToPcm32LittleEndian(bytes: ByteArray, offset: Int, byteCount: Int, linearGain: Float) {
        if (!needsSampleScaling(linearGain) || byteCount < 4) return
        val gainDouble = linearGain.toDouble()
        val alignedLen = byteCount and 3.inv()
        val end = minOf(offset + alignedLen, bytes.size - 3)
        var i = offset
        while (i < end) {
            val sample = (bytes[i].toInt() and 0xFF) or
                ((bytes[i + 1].toInt() and 0xFF) shl 8) or
                ((bytes[i + 2].toInt() and 0xFF) shl 16) or
                (bytes[i + 3].toInt() shl 24)
            val scaledLong = kotlin.math.round(sample.toDouble() * gainDouble).toLong()
                .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
            val scaled = scaledLong.toInt()
            bytes[i] = (scaled and 0xFF).toByte()
            bytes[i + 1] = ((scaled ushr 8) and 0xFF).toByte()
            bytes[i + 2] = ((scaled ushr 16) and 0xFF).toByte()
            bytes[i + 3] = ((scaled ushr 24) and 0xFF).toByte()
            i += 4
        }
    }

    /**
     * Locale-independent signed dB formatter (always uses ASCII `.`, e.g., `"-4.2 dB"`, `"+1.5 dB"`, `"0.0 dB"`).
     */
    fun formatSignedDb(db: Float): String {
        val tenths = (db * 10f).roundToInt()
        if (tenths == 0) return "0.0 dB"
        val sign = if (tenths > 0) "+" else "-"
        val absTenths = abs(tenths)
        val whole = absTenths / 10
        val frac = absTenths % 10
        return "$sign$whole.$frac dB"
    }
}
