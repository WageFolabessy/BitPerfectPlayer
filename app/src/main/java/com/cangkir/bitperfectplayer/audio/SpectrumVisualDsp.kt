package com.cangkir.bitperfectplayer.audio

import com.cangkir.bitperfectplayer.data.SpectrumColorStyle
import com.cangkir.bitperfectplayer.ui.theme.ArtworkPaletteExtractor
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure-Kotlin, zero-dependency local cover art color extractor for [SpectrumColorStyle.ALBUM_ADAPTIVE].
 *
 * Delegates directly to the unified perceptual OKLCH K-Means++ engine ([ArtworkPaletteExtractor])
 * so Dynamic Theme and the Spectrum Visualizer share a single deterministic multi-color pipeline.
 */
object AlbumPaletteExtractor {

    /** Fallback Monochrome Studio silver-ivory triad returned when artwork is missing or purely grayscale. */
    val FALLBACK_STUDIO_PALETTE: IntArray = intArrayOf(
        0xFFB8B8BE.toInt(),
        0xFFD8D8DC.toInt(),
        0xFFF2F2F0.toInt(),
    )

    /**
     * Extracts 3 harmonious opaque ARGB accent colors `[bassColor, midColor, trebleColor]` from
     * local artwork ARGB [pixels] (`width * height`). Zero external libraries, zero `android.*`.
     */
    fun extractAccentPalette(pixels: IntArray, width: Int, height: Int): IntArray {
        val palette = ArtworkPaletteExtractor.extractPalette(pixels, width, height)
        return if (palette.isMonochrome) {
            FALLBACK_STUDIO_PALETTE.copyOf()
        } else {
            palette.toSpectrumTriad()
        }
    }

    internal fun circularHueDistanceDeg(h1: Float, h2: Float): Float {
        val diff = abs(h1 - h2) % 360f
        return if (diff > 180f) 360f - diff else diff
    }

    internal fun rgbToHsl(r: Int, g: Int, b: Int, outHsl: FloatArray) {
        val rf = r / 255f
        val gf = g / 255f
        val bf = b / 255f
        val cMax = max(rf, max(gf, bf))
        val cMin = min(rf, min(gf, bf))
        val delta = cMax - cMin
        val l = (cMax + cMin) * 0.5f

        val h: Float
        val s: Float
        if (delta <= 1e-6f) {
            h = 0f
            s = 0f
        } else {
            s = delta / (1f - abs(2f * l - 1f)).coerceAtLeast(1e-6f)
            var rawH = when (cMax) {
                rf -> ((gf - bf) / delta) % 6f
                gf -> ((bf - rf) / delta) + 2f
                else -> ((rf - gf) / delta) + 4f
            } * 60f
            if (rawH < 0f) rawH += 360f
            h = rawH
        }
        outHsl[0] = h
        outHsl[1] = s.coerceIn(0f, 1f)
        outHsl[2] = l.coerceIn(0f, 1f)
    }
}

/**
 * Pure-Kotlin ARGB color mapper for the 3 switchable spectrum color palettes:
 * [SpectrumColorStyle.STUDIO], [SpectrumColorStyle.PRISM], and [SpectrumColorStyle.ALBUM_ADAPTIVE].
 * Zero heap allocations inside [resolveBarColorArgb] and [resolveAccentColorArgb].
 */
object SpectrumColorMapper {
    const val RESTING_SLATE_ARGB: Int = 0x663D3D47 // #3D3D47 @ 40% alpha
    private const val STUDIO_ACCENT_RGB: Int = 0xFFD4D4D8.toInt()
    private const val STUDIO_TIP_ARGB: Int = 0xFAF2F2F0.toInt()
    private const val BRIGHT_WHITE_TINT: Int = 0xFAF6F6F4.toInt()
    private const val ACTIVE_BAR_ALPHA: Int = 0xEA // ~92% alpha on crisp OLED #0E0E10

    // OLED-tuned 5-stop Prism gradient: coral-red bass -> amber-gold -> emerald-aqua -> sky-cyan -> studio violet.
    private val PRISM_STOPS: IntArray = intArrayOf(
        0xFFE8635A.toInt(), // 0.00: warm coral-red/orange (30-140 Hz bass)
        0xFFF0A84A.toInt(), // 0.25: amber-gold
        0xFF3CCFA0.toInt(), // 0.50: emerald-aqua (mids)
        0xFF4FB8F0.toInt(), // 0.75: electric sky-cyan
        0xFF9F7AEA.toInt(), // 1.00: studio violet (highs)
    )

    /**
     * Resolves the opaque accent ARGB color at [normalizedBandPos] (`0.0f` bass .. `1.0f` treble)
     * for [style], interpolating smoothly across multi-stop palettes with zero heap allocations.
     */
    fun resolveAccentColorArgb(style: SpectrumColorStyle, normalizedBandPos: Float, albumPalette: IntArray): Int {
        val pos = if (normalizedBandPos.isFinite()) normalizedBandPos.coerceIn(0f, 1f) else 0f
        return when (style) {
            SpectrumColorStyle.STUDIO -> STUDIO_ACCENT_RGB
            SpectrumColorStyle.PRISM -> sampleMultiStopRgb(PRISM_STOPS, pos)
            SpectrumColorStyle.ALBUM_ADAPTIVE -> {
                val palette = if (albumPalette.size >= 3) albumPalette else AlbumPaletteExtractor.FALLBACK_STUDIO_PALETTE
                sampleMultiStopRgb(palette, pos)
            }
        }
    }

    /**
     * Blends resting matte slate (`#3D3D47`) into the active style's color as [energy] rises, with
     * a crisp luminous peak lift above `0.72f`.
     */
    fun resolveBarColorArgb(style: SpectrumColorStyle, normalizedBandPos: Float, energy: Float, albumPalette: IntArray): Int {
        val e = if (energy.isFinite()) energy.coerceIn(0f, 1f) else 0f
        val accentOpaque = resolveAccentColorArgb(style, normalizedBandPos, albumPalette)
        val activeBarArgb = (ACTIVE_BAR_ALPHA shl 24) or (accentOpaque and 0x00FFFFFF)

        return if (e <= 0.72f) {
            lerpArgb(RESTING_SLATE_ARGB, activeBarArgb, e / 0.72f)
        } else {
            val peakTarget = if (style == SpectrumColorStyle.STUDIO) {
                STUDIO_TIP_ARGB
            } else {
                lerpArgb(activeBarArgb, BRIGHT_WHITE_TINT, 0.42f)
            }
            lerpArgb(activeBarArgb, peakTarget, (e - 0.72f) / 0.28f)
        }
    }

    /** Resolves a bright peak tip tint matching the bar color for transients (`energy > 0.68f`). */
    fun resolveTipColorArgb(style: SpectrumColorStyle, normalizedBandPos: Float, energy: Float, albumPalette: IntArray): Int {
        val e = if (energy.isFinite()) energy.coerceIn(0f, 1f) else 0f
        val tipFraction = ((e - 0.68f) / 0.32f).coerceIn(0f, 1f)
        val alpha = (tipFraction * 0.78f * 255f).roundToInt().coerceIn(0, 255)
        val baseRgb = if (style == SpectrumColorStyle.STUDIO) {
            STUDIO_TIP_ARGB and 0x00FFFFFF
        } else {
            val accent = resolveAccentColorArgb(style, normalizedBandPos, albumPalette)
            lerpArgb(accent, BRIGHT_WHITE_TINT, 0.55f) and 0x00FFFFFF
        }
        return (alpha shl 24) or baseRgb
    }

    /** Brightened opaque spark color derived cleanly from the launching band's color. */
    fun resolveSparkColorArgb(style: SpectrumColorStyle, normalizedBandPos: Float, albumPalette: IntArray): Int {
        val accent = resolveAccentColorArgb(style, normalizedBandPos, albumPalette)
        return (0xFF shl 24) or (lerpArgb(accent, BRIGHT_WHITE_TINT, 0.28f) and 0x00FFFFFF)
    }

    private fun sampleMultiStopRgb(stops: IntArray, pos: Float): Int {
        val lastIdx = stops.size - 1
        if (lastIdx <= 0) return stops.firstOrNull() ?: STUDIO_ACCENT_RGB
        val scaled = pos * lastIdx
        val idx0 = scaled.toInt().coerceIn(0, lastIdx)
        val idx1 = (idx0 + 1).coerceAtMost(lastIdx)
        val frac = (scaled - idx0).coerceIn(0f, 1f)
        return lerpArgb(stops[idx0], stops[idx1], frac)
    }

    internal fun lerpArgb(c0: Int, c1: Int, fraction: Float): Int {
        val t = fraction.coerceIn(0f, 1f)
        val a0 = (c0 ushr 24) and 0xFF
        val r0 = (c0 ushr 16) and 0xFF
        val g0 = (c0 ushr 8) and 0xFF
        val b0 = c0 and 0xFF

        val a1 = (c1 ushr 24) and 0xFF
        val r1 = (c1 ushr 16) and 0xFF
        val g1 = (c1 ushr 8) and 0xFF
        val b1 = c1 and 0xFF

        val a = (a0 + (a1 - a0) * t).roundToInt().coerceIn(0, 255)
        val r = (r0 + (r1 - r0) * t).roundToInt().coerceIn(0, 255)
        val g = (g0 + (g1 - g0) * t).roundToInt().coerceIn(0, 255)
        val b = (b0 + (b1 - b0) * t).roundToInt().coerceIn(0, 255)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }
}

/**
 * Fixed-capacity, zero-allocation particle pool (`MAX_SPARKS = 64`) launching crisp kinetic sparks
 * from transient musical spikes across both radial (Vinyl Disc) and linear (Square Cover) layouts.
 *
 * All particle arrays are pre-allocated primitives; `emitFromBands(...)` and `step(...)` allocate
 * **0 bytes** per 120 Hz frame.
 */
class KineticSparkPool {
    val active = BooleanArray(MAX_SPARKS)
    val angleRad = FloatArray(MAX_SPARKS)
    val normalizedX = FloatArray(MAX_SPARKS)
    val launchEnergy = FloatArray(MAX_SPARKS)
    val distanceDp = FloatArray(MAX_SPARKS)
    val tangentOffsetDp = FloatArray(MAX_SPARKS)
    val radialVelDp = FloatArray(MAX_SPARKS)
    val tangentVelDp = FloatArray(MAX_SPARKS)
    val ageSec = FloatArray(MAX_SPARKS)
    val lifetimeSec = FloatArray(MAX_SPARKS)
    val sizeDp = FloatArray(MAX_SPARKS)
    val colorArgb = IntArray(MAX_SPARKS)

    private val prevBands = FloatArray(SpectrumAnalyzerDsp.BAND_COUNT)
    private val bandCooldownSec = FloatArray(SpectrumAnalyzerDsp.BAND_COUNT)
    private var nextSlotCursor = 0
    private var rngState: Int = 0x5F3759DF

    var activeCount: Int = 0
        private set

    /**
     * Detects transient spikes across the 32 logarithmic bands (`delta > 0.12f && energy > 0.28f`)
     * respecting per-band `85 ms` cooldowns and spawns 1–2 sparks matching each band's angle/color.
     */
    fun emitFromBands(bands32: FloatArray, dtSeconds: Float, style: SpectrumColorStyle, albumPalette: IntArray) {
        val dt = if (dtSeconds.isFinite()) dtSeconds.coerceIn(0f, 0.1f) else 0f
        val count = minOf(bands32.size, SpectrumAnalyzerDsp.BAND_COUNT)
        val denom = (SpectrumAnalyzerDsp.BAND_COUNT - 1).coerceAtLeast(1).toFloat()

        for (i in 0 until count) {
            if (bandCooldownSec[i] > 0f) {
                bandCooldownSec[i] = (bandCooldownSec[i] - dt).coerceAtLeast(0f)
            }
            val raw = bands32[i]
            val energy = if (raw.isFinite()) raw.coerceIn(0f, 1f) else 0f
            val delta = energy - prevBands[i]
            prevBands[i] = energy

            if (delta > TRANSIENT_DELTA_THRESHOLD &&
                energy > TRANSIENT_ENERGY_THRESHOLD &&
                bandCooldownSec[i] <= 0f
            ) {
                bandCooldownSec[i] = BAND_COOLDOWN_SEC
                val normPos = i.toFloat() / denom
                val sparkColor = SpectrumColorMapper.resolveSparkColorArgb(style, normPos, albumPalette)
                val intensity = ((delta + energy) * 0.55f).coerceIn(0f, 1f)
                val spawnCount = if (delta > 0.24f || energy > 0.72f) 2 else 1

                val rightBarIndex = SpectrumAnalyzerDsp.BAND_COUNT - 1 - i
                val leftBarIndex = SpectrumAnalyzerDsp.BAND_COUNT + i
                val startLeft = (nextRngInt() and 1) == 0

                for (s in 0 until spawnCount) {
                    val useLeft = if (spawnCount == 2) (s == 1) else startLeft
                    val barIndex = if (useLeft) leftBarIndex else rightBarIndex
                    val radialAngle = (-PI / 2.0 + (2.0 * PI * barIndex) / SpectrumAnalyzerDsp.RADIAL_BAR_COUNT).toFloat()
                    spawnSpark(
                        radialAngle = radialAngle,
                        normX = normPos,
                        energy = energy,
                        intensity = intensity,
                        argb = sparkColor,
                    )
                }
            }
        }
    }

    /**
     * Advances all active sparks with smooth exponential drag (`exp(-4.0 * dt)`), expires completed
     * particles, and returns the exact remaining active count so paused UI loops terminate at `0`.
     */
    fun step(dtSeconds: Float): Int {
        val dt = if (dtSeconds.isFinite()) dtSeconds.coerceIn(0f, 0.1f) else 0f
        val drag = exp(-DRAG_RATE * dt.toDouble()).toFloat()
        var live = 0

        for (slot in 0 until MAX_SPARKS) {
            if (!active[slot]) continue
            val nextAge = ageSec[slot] + dt
            if (!nextAge.isFinite() || nextAge >= lifetimeSec[slot]) {
                active[slot] = false
                continue
            }
            ageSec[slot] = nextAge
            distanceDp[slot] += radialVelDp[slot] * dt
            tangentOffsetDp[slot] += tangentVelDp[slot] * dt
            radialVelDp[slot] *= drag
            tangentVelDp[slot] *= drag
            live++
        }
        activeCount = live
        return live
    }

    /** Clears all active sparks and transient cooldown state cleanly on track switch or disable. */
    fun clear() {
        active.fill(false)
        prevBands.fill(0f)
        bandCooldownSec.fill(0f)
        distanceDp.fill(0f)
        tangentOffsetDp.fill(0f)
        radialVelDp.fill(0f)
        tangentVelDp.fill(0f)
        ageSec.fill(0f)
        activeCount = 0
    }

    private fun spawnSpark(radialAngle: Float, normX: Float, energy: Float, intensity: Float, argb: Int) {
        var slot = -1
        for (attempt in 0 until MAX_SPARKS) {
            val candidate = (nextSlotCursor + attempt) % MAX_SPARKS
            if (!active[candidate]) {
                slot = candidate
                nextSlotCursor = (candidate + 1) % MAX_SPARKS
                break
            }
        }
        if (slot < 0) return // Strictly capped at MAX_SPARKS = 64

        active[slot] = true
        angleRad[slot] = radialAngle
        normalizedX[slot] = normX
        launchEnergy[slot] = energy
        distanceDp[slot] = 0f
        tangentOffsetDp[slot] = 0f
        radialVelDp[slot] = (MIN_RADIAL_VEL_DP + intensity * 70f + nextRngUnit() * 15f)
            .coerceIn(MIN_RADIAL_VEL_DP, MAX_RADIAL_VEL_DP)
        tangentVelDp[slot] = (nextRngUnit() * 2f - 1f) * MAX_TANGENT_VEL_DP
        ageSec[slot] = 0f
        lifetimeSec[slot] = MIN_LIFETIME_SEC + nextRngUnit() * (MAX_LIFETIME_SEC - MIN_LIFETIME_SEC)
        sizeDp[slot] = (MIN_SIZE_DP + intensity * 0.8f + nextRngUnit() * 0.4f).coerceIn(MIN_SIZE_DP, MAX_SIZE_DP)
        colorArgb[slot] = argb
        activeCount++
    }

    private fun nextRngInt(): Int {
        var x = rngState
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        rngState = x
        return x
    }

    private fun nextRngUnit(): Float = (nextRngInt() ushr 8).toFloat() / 16_777_216f

    companion object {
        const val MAX_SPARKS = 64
        const val TRANSIENT_DELTA_THRESHOLD = 0.12f
        const val TRANSIENT_ENERGY_THRESHOLD = 0.28f
        const val BAND_COOLDOWN_SEC = 0.085f
        const val MIN_RADIAL_VEL_DP = 60f
        const val MAX_RADIAL_VEL_DP = 145f
        const val MAX_TANGENT_VEL_DP = 18f
        const val MIN_SIZE_DP = 1.6f
        const val MAX_SIZE_DP = 2.8f
        const val MIN_LIFETIME_SEC = 0.30f
        const val MAX_LIFETIME_SEC = 0.55f
        const val DRAG_RATE = 4.0
    }
}
