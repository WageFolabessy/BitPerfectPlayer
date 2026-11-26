package com.cangkir.bitperfectplayer.ui.theme

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Perceptual OKLab color coordinates (`L in 0..1`, `a`, `b` opponent axes).
 * Pure Kotlin (zero `android.*` / `androidx.*` imports).
 */
data class OkLab(val l: Float, val a: Float, val b: Float)

/**
 * Polar OKLCH perceptual color coordinates (`l in 0..1`, `c >= 0`, `hDeg in 0..<360`).
 * Pure Kotlin (zero `android.*` / `androidx.*` imports).
 */
data class Oklch(val l: Float, val c: Float, val hDeg: Float)

/**
 * Pure-Kotlin, dependency-free perceptual color conversion and WCAG 2.1 contrast engine
 * implementing exact `sRGB <-> Linear RGB <-> OKLab <-> OKLCH` math with hue-preserving
 * sRGB gamut clipping via chroma bisection (zero `android.*` / `androidx.*` imports).
 */
object OklchColorMath {
    private const val GAMUT_BISECTION_STEPS = 14
    private const val LIGHTNESS_SOLVE_STEPS = 16
    private const val DEG_TO_RAD = (PI / 180.0)
    private const val RAD_TO_DEG = (180.0 / PI)

    fun normalizeHueDeg(hueDeg: Float): Float {
        if (!hueDeg.isFinite()) return 0f
        val wrapped = ((hueDeg % 360f) + 360f) % 360f
        return if (wrapped >= 360f) 0f else wrapped
    }

    fun circularHueDistanceDeg(h1: Float, h2: Float): Float {
        val diff = abs(normalizeHueDeg(h1) - normalizeHueDeg(h2))
        return if (diff > 180f) 360f - diff else diff
    }

    fun srgbChannelToLinear(channel: Double): Double {
        val c = channel.coerceIn(0.0, 1.0)
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    fun linearToSrgbChannel(linear: Double): Double {
        val c = linear.coerceIn(0.0, 1.0)
        return if (c <= 0.0031308) 12.92 * c else 1.055 * c.pow(1.0 / 2.4) - 0.055
    }

    fun argbToOkLab(argb: Int): OkLab {
        val r = srgbChannelToLinear(((argb ushr 16) and 0xFF) / 255.0)
        val g = srgbChannelToLinear(((argb ushr 8) and 0xFF) / 255.0)
        val b = srgbChannelToLinear((argb and 0xFF) / 255.0)
        return linearRgbToOkLab(r, g, b)
    }

    fun linearRgbToOkLab(r: Double, g: Double, b: Double): OkLab {
        val lmsL = 0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b
        val lmsM = 0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b
        val lmsS = 0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b

        val lRoot = cbrt(lmsL.coerceAtLeast(0.0))
        val mRoot = cbrt(lmsM.coerceAtLeast(0.0))
        val sRoot = cbrt(lmsS.coerceAtLeast(0.0))

        val okL = 0.2104542553 * lRoot + 0.7936177850 * mRoot - 0.0040720468 * sRoot
        val okA = 1.9779984951 * lRoot - 2.4285922050 * mRoot + 0.4505937099 * sRoot
        val okB = 0.0259040371 * lRoot + 0.7827717662 * mRoot - 0.8086757660 * sRoot
        return OkLab(okL.toFloat(), okA.toFloat(), okB.toFloat())
    }

    fun okLabToOklch(lab: OkLab): Oklch {
        val chroma = hypot(lab.a.toDouble(), lab.b.toDouble()).toFloat()
        val hue = if (chroma <= 1e-6f) {
            0f
        } else {
            normalizeHueDeg((atan2(lab.b.toDouble(), lab.a.toDouble()) * RAD_TO_DEG).toFloat())
        }
        return Oklch(l = lab.l.coerceIn(0f, 1f), c = chroma.coerceAtLeast(0f), hDeg = hue)
    }

    fun oklchToOkLab(oklch: Oklch): OkLab {
        val rad = normalizeHueDeg(oklch.hDeg).toDouble() * DEG_TO_RAD
        val c = oklch.c.coerceAtLeast(0f).toDouble()
        return OkLab(
            l = oklch.l.coerceIn(0f, 1f),
            a = (c * cos(rad)).toFloat(),
            b = (c * sin(rad)).toFloat(),
        )
    }

    fun argbToOklch(argb: Int): Oklch = okLabToOklch(argbToOkLab(argb))

    /**
     * Converts [Oklch] to an opaque sRGB ARGB integer using hue-preserving chroma bisection
     * whenever the requested `(L, C, H)` exceeds the sRGB gamut boundary.
     */
    fun oklchToSrgbArgb(l: Float, c: Float, hDeg: Float): Int {
        val clampedL = if (l.isFinite()) l.coerceIn(0f, 1f) else 0f
        if (clampedL <= 0.0001f) return 0xFF000000.toInt()
        if (clampedL >= 0.9999f) return 0xFFFFFFFF.toInt()

        val clampedC = if (c.isFinite()) c.coerceAtLeast(0f) else 0f
        val hue = normalizeHueDeg(hDeg)
        val rad = hue.toDouble() * DEG_TO_RAD
        val cosH = cos(rad)
        val sinH = sin(rad)

        val lDouble = clampedL.toDouble()
        val safeChroma = if (clampedC <= 1e-6f || isOkLabInSrgbGamut(lDouble, clampedC * cosH, clampedC * sinH)) {
            clampedC.toDouble()
        } else {
            bisectInGamutChroma(lDouble, clampedC.toDouble(), cosH, sinH)
        }
        return okLabUncheckedToSrgbArgb(lDouble, safeChroma * cosH, safeChroma * sinH)
    }

    fun oklchToSrgbArgb(oklch: Oklch): Int = oklchToSrgbArgb(oklch.l, oklch.c, oklch.hDeg)

    /**
     * Finds the maximum in-gamut sRGB chroma at fixed perceptual lightness [l] and hue [hDeg].
     */
    fun maxInGamutChroma(l: Float, hDeg: Float): Float {
        val clampedL = l.coerceIn(0f, 1f)
        if (clampedL <= 0.001f || clampedL >= 0.999f) return 0f
        val rad = normalizeHueDeg(hDeg).toDouble() * DEG_TO_RAD
        return bisectInGamutChroma(clampedL.toDouble(), 0.40, cos(rad), sin(rad)).toFloat()
    }

    /** Computes exact WCAG 2.1 relative luminance (`0.0..1.0`) for an opaque or semi-opaque ARGB color. */
    fun relativeLuminance(argb: Int): Double {
        val rLin = srgbChannelToLinear(((argb ushr 16) and 0xFF) / 255.0)
        val gLin = srgbChannelToLinear(((argb ushr 8) and 0xFF) / 255.0)
        val bLin = srgbChannelToLinear((argb and 0xFF) / 255.0)
        return 0.2126 * rLin + 0.7152 * gLin + 0.0722 * bLin
    }

    /** Computes exact WCAG 2.1 contrast ratio (`1.0..21.0`) between [argb1] and [argb2]. */
    fun contrastRatio(argb1: Int, argb2: Int): Double {
        val lum1 = relativeLuminance(argb1)
        val lum2 = relativeLuminance(argb2)
        val lighter = max(lum1, lum2)
        val darker = min(lum1, lum2)
        return (lighter + 0.05) / (darker + 0.05)
    }

    /**
     * Solves an accessible, hue-faithful accent in OKLCH that satisfies strict WCAG contrast
     * against both dark surfaces and dark foreground ink without washing out deep blues/violets
     * into chalky pastels or turning golds/yellows muddy olive.
     */
    fun solveAccessibleDarkThemeAccent(
        hueDeg: Float,
        desiredChroma: Float,
        initialLightness: Float,
        onAccentArgb: Int,
        darkReferenceArgb: Int,
        minContrastRatio: Double = 4.5,
        maxRelativeLuminance: Double = 0.60,
    ): Int {
        val hue = normalizeHueDeg(hueDeg)
        val darkRefLum = max(relativeLuminance(darkReferenceArgb), relativeLuminance(onAccentArgb))
        val minRequiredLum = (minContrastRatio * (darkRefLum + 0.05) - 0.05).coerceIn(0.0, 0.90)
        val ceilingLum = max(maxRelativeLuminance, minRequiredLum + 0.04).coerceAtMost(0.92)

        // Pick a hue-aware lightness that naturally honors yellow/gold warmth and blue/violet depth.
        val targetStartL = hueAwareInitialLightness(hue, initialLightness)
        var solvedL = targetStartL
        var candidate = renderGamutAwareAccent(solvedL, desiredChroma, hue)
        val candidateLum = relativeLuminance(candidate)

        if (candidateLum < minRequiredLum) {
            solvedL = binarySearchLightnessForMinLum(
                lowL = solvedL,
                highL = 0.94f,
                desiredChroma = desiredChroma,
                hueDeg = hue,
                minLum = minRequiredLum + 0.015,
            )
            candidate = renderGamutAwareAccent(solvedL, desiredChroma, hue)
        } else if (candidateLum > ceilingLum) {
            // Preserve crisp warm gold/yellow saturation rather than crushing into muddy olive.
            val lowBoundL = if (hue in 65f..118f) 0.72f else 0.56f
            solvedL = binarySearchLightnessForMaxLum(
                lowL = lowBoundL,
                highL = solvedL,
                desiredChroma = desiredChroma,
                hueDeg = hue,
                maxLum = ceilingLum,
            )
            candidate = renderGamutAwareAccent(solvedL, desiredChroma, hue)
        }

        // Final contrast safety guard against both onAccent and darkReference.
        var guardL = solvedL
        var guardC = desiredChroma
        while (
            guardL < 0.96f &&
            (contrastRatio(candidate, darkReferenceArgb) < minContrastRatio || contrastRatio(candidate, onAccentArgb) < minContrastRatio)
        ) {
            guardL = (guardL + 0.02f).coerceAtMost(0.96f)
            if (guardL > 0.88f) {
                guardC = (guardC * 0.85f).coerceAtLeast(0.02f)
            }
            candidate = renderGamutAwareAccent(guardL, guardC, hue)
        }
        return candidate
    }

    /**
     * Lifts light foreground text in OKLCH until it reaches at least [minRatio] against [againstArgb].
     */
    fun solveAccessibleLightText(hueDeg: Float, chroma: Float, initialLightness: Float, againstArgb: Int, minRatio: Double): Int {
        val hue = normalizeHueDeg(hueDeg)
        var lightness = initialLightness.coerceIn(0.55f, 0.98f)
        var candidate = oklchToSrgbArgb(lightness, chroma, hue)
        while (lightness < 0.985f && contrastRatio(candidate, againstArgb) < minRatio) {
            lightness = (lightness + 0.015f).coerceAtMost(0.985f)
            candidate = oklchToSrgbArgb(lightness, chroma, hue)
        }
        return candidate
    }

    private fun hueAwareInitialLightness(hueDeg: Float, baseL: Float): Float = when {
        // Yellow/Gold range: keep high perceptual L so golds stay luminous and warm, never muddy olive.
        hueDeg in 70f..115f -> max(baseL, 0.80f).coerceAtMost(0.84f)
        // Deep Blue/Indigo/Violet range: moderate-high L retains deep chromatic saturation without chalky pastel.
        hueDeg in 240f..315f -> baseL.coerceIn(0.71f, 0.78f)
        else -> baseL.coerceIn(0.70f, 0.81f)
    }

    private fun renderGamutAwareAccent(lightness: Float, desiredChroma: Float, hueDeg: Float): Int {
        val maxC = maxInGamutChroma(lightness, hueDeg)
        // Stay up to 96% of in-gamut chroma so colors retain purity without edge-clipping artifacts.
        val effectiveC = min(desiredChroma, maxC * 0.96f).coerceAtLeast(0f)
        return oklchToSrgbArgb(lightness, effectiveC, hueDeg)
    }

    private fun binarySearchLightnessForMinLum(lowL: Float, highL: Float, desiredChroma: Float, hueDeg: Float, minLum: Double): Float {
        var lo = lowL.coerceIn(0.1f, 0.96f)
        var hi = highL.coerceIn(lo, 0.96f)
        repeat(LIGHTNESS_SOLVE_STEPS) {
            val mid = (lo + hi) * 0.5f
            val lum = relativeLuminance(renderGamutAwareAccent(mid, desiredChroma, hueDeg))
            if (lum < minLum) {
                lo = mid
            } else {
                hi = mid
            }
        }
        return hi
    }

    private fun binarySearchLightnessForMaxLum(lowL: Float, highL: Float, desiredChroma: Float, hueDeg: Float, maxLum: Double): Float {
        var lo = lowL.coerceIn(0.40f, highL)
        var hi = highL.coerceIn(lo, 0.96f)
        repeat(LIGHTNESS_SOLVE_STEPS) {
            val mid = (lo + hi) * 0.5f
            val lum = relativeLuminance(renderGamutAwareAccent(mid, desiredChroma, hueDeg))
            if (lum > maxLum) {
                hi = mid
            } else {
                lo = mid
            }
        }
        return lo
    }

    private fun bisectInGamutChroma(l: Double, maxC: Double, cosH: Double, sinH: Double): Double {
        var lo = 0.0
        var hi = maxC
        repeat(GAMUT_BISECTION_STEPS) {
            val mid = (lo + hi) * 0.5
            if (isOkLabInSrgbGamut(l, mid * cosH, mid * sinH)) {
                lo = mid
            } else {
                hi = mid
            }
        }
        return lo
    }

    private fun isOkLabInSrgbGamut(l: Double, a: Double, b: Double): Boolean {
        val lRoot = l + 0.3963377774 * a + 0.2158037573 * b
        val mRoot = l - 0.1055613458 * a - 0.0638541728 * b
        val sRoot = l - 0.0894841775 * a - 1.2914855480 * b

        val lmsL = lRoot * lRoot * lRoot
        val lmsM = mRoot * mRoot * mRoot
        val lmsS = sRoot * sRoot * sRoot

        val r = +4.0767416621 * lmsL - 3.3077115913 * lmsM + 0.2309699292 * lmsS
        val g = -1.2684380046 * lmsL + 2.6097574011 * lmsM - 0.3413193965 * lmsS
        val bLin = -0.0041960863 * lmsL - 0.7034186147 * lmsM + 1.7076147010 * lmsS

        val eps = 1e-4
        return r in -eps..(1.0 + eps) && g in -eps..(1.0 + eps) && bLin in -eps..(1.0 + eps)
    }

    private fun okLabUncheckedToSrgbArgb(l: Double, a: Double, b: Double): Int {
        val lRoot = l + 0.3963377774 * a + 0.2158037573 * b
        val mRoot = l - 0.1055613458 * a - 0.0638541728 * b
        val sRoot = l - 0.0894841775 * a - 1.2914855480 * b

        val lmsL = lRoot * lRoot * lRoot
        val lmsM = mRoot * mRoot * mRoot
        val lmsS = sRoot * sRoot * sRoot

        val rLin = +4.0767416621 * lmsL - 3.3077115913 * lmsM + 0.2309699292 * lmsS
        val gLin = -1.2684380046 * lmsL + 2.6097574011 * lmsM - 0.3413193965 * lmsS
        val bLin = -0.0041960863 * lmsL - 0.7034186147 * lmsM + 1.7076147010 * lmsS

        val r = (linearToSrgbChannel(rLin) * 255.0).roundToInt().coerceIn(0, 255)
        val g = (linearToSrgbChannel(gLin) * 255.0).roundToInt().coerceIn(0, 255)
        val bl = (linearToSrgbChannel(bLin) * 255.0).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }
}
