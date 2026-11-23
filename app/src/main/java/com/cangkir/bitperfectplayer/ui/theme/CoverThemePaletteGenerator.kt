package com.cangkir.bitperfectplayer.ui.theme

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Immutable pure-Kotlin color token set for dynamic album-cover theming (`BitPerfectPlayerTheme`).
 *
 * Semantic diagnostic tokens (`EmeraldSafe`, `AmberIndicated`, `CoralWarning`) live outside this
 * structure so verification states never desaturate or drift with cover art.
 */
data class DynamicThemeTokens(
    val primaryArgb: Int,
    val onPrimaryArgb: Int,
    val primaryContainerArgb: Int,
    val onPrimaryContainerArgb: Int,
    val secondaryArgb: Int,
    val onSecondaryArgb: Int,
    val secondaryContainerArgb: Int,
    val onSecondaryContainerArgb: Int,
    val backgroundArgb: Int,
    val onBackgroundArgb: Int,
    val surfaceArgb: Int,
    val onSurfaceArgb: Int,
    val surfaceVariantArgb: Int,
    val onSurfaceVariantArgb: Int,
    val surfaceContainerHighestArgb: Int,
    val outlineArgb: Int,
)

/**
 * Pure-Kotlin, zero-dependency quantized cover color extractor and WCAG-verified dark theme
 * generator (zero `android.*` or `androidx.*` imports).
 *
 * Samples a downsampled pixel array into 36 hue buckets weighted by saturation and tonal
 * prominence, rejects monochrome/grayscale/empty covers (returning `null` so UI falls back to
 * [MONOCHROME_STUDIO_TOKENS]), and mathematically enforces WCAG 2.1 contrast thresholds across
 * every surface, text, and button token.
 */
object CoverThemePaletteGenerator {
    private const val HUE_BUCKETS = 36
    private const val MAX_SAMPLES = 1_024
    private const val MIN_ALPHA = 128
    private const val MIN_RGB_DELTA = 14
    private const val MIN_HSV_SAT = 0.08f
    private const val MIN_HSL_SAT = 0.14f
    private const val MIN_HSL_LUM = 0.035f
    private const val MAX_HSL_LUM = 0.965f
    private const val MIN_CHROMATIC_RATIO = 0.03f
    private const val MAX_PRIMARY_RELATIVE_LUMINANCE = 0.56
    private const val MIN_ACCENT_CONTRAST_RATIO = 4.5
    private const val TARGET_ACCENT_CONTRAST_RATIO = 5.0
    private const val TARGET_TEXT_CONTRAST_RATIO = 7.5
    private const val TARGET_VARIANT_TEXT_CONTRAST_RATIO = 4.8

    val MONOCHROME_STUDIO_TOKENS: DynamicThemeTokens = DynamicThemeTokens(
        primaryArgb = 0xFFF2F2F0.toInt(),
        onPrimaryArgb = 0xFF111113.toInt(),
        primaryContainerArgb = 0xFF2A2A30.toInt(),
        onPrimaryContainerArgb = 0xFFF2F2F0.toInt(),
        secondaryArgb = 0xFFB8B8BE.toInt(),
        onSecondaryArgb = 0xFF111113.toInt(),
        secondaryContainerArgb = 0xFF26262C.toInt(),
        onSecondaryContainerArgb = 0xFFE6E6E8.toInt(),
        backgroundArgb = 0xFF0E0E10.toInt(),
        onBackgroundArgb = 0xFFF2F2F0.toInt(),
        surfaceArgb = 0xFF161619.toInt(),
        onSurfaceArgb = 0xFFF2F2F0.toInt(),
        surfaceVariantArgb = 0xFF1E1E22.toInt(),
        onSurfaceVariantArgb = 0xFFA2A2AA.toInt(),
        surfaceContainerHighestArgb = 0xFF26262C.toInt(),
        outlineArgb = 0xFF34343C.toInt(),
    )

    private class QuantizedHueStats(
        val weight: FloatArray,
        val sinSum: FloatArray,
        val cosSum: FloatArray,
        val satSum: FloatArray,
        val chromaticCount: Int,
        val opaqueCount: Int,
    )

    /**
     * Extracts contrast-verified [DynamicThemeTokens] from ARGB [pixels] (`width * height`), or
     * returns `null` when the image is missing, empty, transparent, or purely grayscale/monochrome.
     */
    fun extractThemeTokens(pixels: IntArray, width: Int, height: Int): DynamicThemeTokens? {
        if (width <= 0 || height <= 0 || pixels.isEmpty()) return null
        val totalPixels = minOf(pixels.size, width * height)
        if (totalPixels <= 0) return null

        val stats = accumulateQuantizedHues(pixels, totalPixels)
        if (stats.opaqueCount == 0) return null
        val chromaticRatio = stats.chromaticCount.toFloat() / stats.opaqueCount.toFloat()
        if (chromaticRatio < MIN_CHROMATIC_RATIO) return null

        var bestBucket = -1
        var bestWeight = 0f
        for (b in 0 until HUE_BUCKETS) {
            val w = stats.weight[b]
            if (w > bestWeight) {
                bestWeight = w
                bestBucket = b
            }
        }
        if (bestBucket < 0 || bestWeight <= 1e-4f) return null

        // Blend the winning bucket with its immediate +/- 10 deg neighbors to stabilize boundaries.
        var combinedWeight = 0f
        var combinedSin = 0f
        var combinedCos = 0f
        var combinedSat = 0f
        for (offset in -1..1) {
            val bucket = (bestBucket + offset + HUE_BUCKETS) % HUE_BUCKETS
            val w = stats.weight[bucket]
            if (w > 0f) {
                combinedWeight += w
                combinedSin += stats.sinSum[bucket]
                combinedCos += stats.cosSum[bucket]
                combinedSat += stats.satSum[bucket]
            }
        }
        if (combinedWeight <= 1e-4f) return null

        var dominantHueDeg = atan2(combinedSin, combinedCos) * (180f / PI.toFloat())
        if (dominantHueDeg < 0f) dominantHueDeg += 360f
        val dominantSat = (combinedSat / combinedWeight).coerceIn(0f, 1f)
        return generateTokensFromHue(hueDeg = dominantHueDeg, sourceSaturation = dominantSat)
    }

    /**
     * Convenience helper returning [extractThemeTokens] when chromatic, or [MONOCHROME_STUDIO_TOKENS].
     */
    fun resolveThemeTokensOrDefault(pixels: IntArray, width: Int, height: Int): DynamicThemeTokens =
        extractThemeTokens(pixels, width, height) ?: MONOCHROME_STUDIO_TOKENS

    /**
     * Generates a studio-grade dark theme palette anchored at [hueDeg] with strict WCAG 2.1
     * luminance clamping (`>= 4.5:1` interactive accents, `>= 7.0:1` primary text on dark canvas).
     */
    fun generateTokensFromHue(hueDeg: Float, sourceSaturation: Float): DynamicThemeTokens {
        val hue = ((hueDeg % 360f) + 360f) % 360f
        val sat = if (sourceSaturation.isFinite()) sourceSaturation.coerceIn(0f, 1f) else 0f

        val bgSat = (sat * 0.28f).coerceIn(0.10f, 0.22f)
        val backgroundArgb = hslToOpaqueArgb(hue, bgSat, 0.060f)
        val surfaceArgb = hslToOpaqueArgb(hue, bgSat, 0.090f)
        val surfaceVariantArgb = hslToOpaqueArgb(hue, (bgSat * 1.15f).coerceAtMost(0.26f), 0.125f)
        val surfaceContainerHighestArgb = hslToOpaqueArgb(hue, (bgSat * 1.15f).coerceAtMost(0.28f), 0.160f)
        val outlineArgb = hslToOpaqueArgb(hue, (bgSat * 1.10f).coerceAtMost(0.26f), 0.235f)

        val onBackgroundArgb = ensureLightTextContrast(
            hueDeg = hue,
            saturation = 0.08f,
            initialLum = 0.945f,
            againstArgb = backgroundArgb,
            minRatio = TARGET_TEXT_CONTRAST_RATIO,
        )
        val onSurfaceArgb = ensureLightTextContrast(
            hueDeg = hue,
            saturation = 0.08f,
            initialLum = 0.945f,
            againstArgb = surfaceArgb,
            minRatio = TARGET_TEXT_CONTRAST_RATIO,
        )
        val onSurfaceVariantArgb = ensureLightTextContrast(
            hueDeg = hue,
            saturation = 0.10f,
            initialLum = 0.68f,
            againstArgb = surfaceVariantArgb,
            minRatio = TARGET_VARIANT_TEXT_CONTRAST_RATIO,
        )

        val onPrimaryArgb = hslToOpaqueArgb(hue, (bgSat * 0.8f).coerceAtMost(0.18f), 0.065f)
        val primarySat = (sat * 0.62f).coerceIn(0.32f, 0.62f)
        val primaryArgb = solveStudioAccent(
            hueDeg = hue,
            baseSaturation = primarySat,
            initialLum = 0.68f,
            onAccentArgb = onPrimaryArgb,
            surfaceArgb = surfaceArgb,
            surfaceVariantArgb = surfaceVariantArgb,
            maxRelativeLum = MAX_PRIMARY_RELATIVE_LUMINANCE,
        )

        val primaryContainerArgb = hslToOpaqueArgb(hue, (primarySat * 0.45f).coerceIn(0.16f, 0.32f), 0.19f)
        val onPrimaryContainerArgb = ensureLightTextContrast(
            hueDeg = hue,
            saturation = (primarySat * 0.30f).coerceIn(0.08f, 0.22f),
            initialLum = 0.92f,
            againstArgb = primaryContainerArgb,
            minRatio = 7.0,
        )

        val secondarySat = (primarySat * 0.42f).coerceIn(0.14f, 0.30f)
        val secondaryArgb = solveStudioAccent(
            hueDeg = hue,
            baseSaturation = secondarySat,
            initialLum = 0.66f,
            onAccentArgb = onPrimaryArgb,
            surfaceArgb = surfaceArgb,
            surfaceVariantArgb = surfaceVariantArgb,
            maxRelativeLum = MAX_PRIMARY_RELATIVE_LUMINANCE,
        )

        return DynamicThemeTokens(
            primaryArgb = primaryArgb,
            onPrimaryArgb = onPrimaryArgb,
            primaryContainerArgb = primaryContainerArgb,
            onPrimaryContainerArgb = onPrimaryContainerArgb,
            secondaryArgb = secondaryArgb,
            onSecondaryArgb = onPrimaryArgb,
            secondaryContainerArgb = surfaceContainerHighestArgb,
            onSecondaryContainerArgb = onPrimaryContainerArgb,
            backgroundArgb = backgroundArgb,
            onBackgroundArgb = onBackgroundArgb,
            surfaceArgb = surfaceArgb,
            onSurfaceArgb = onSurfaceArgb,
            surfaceVariantArgb = surfaceVariantArgb,
            onSurfaceVariantArgb = onSurfaceVariantArgb,
            surfaceContainerHighestArgb = surfaceContainerHighestArgb,
            outlineArgb = outlineArgb,
        )
    }

    /** Computes exact WCAG 2.1 relative luminance (`0.0..1.0`) for an opaque or semi-opaque ARGB color. */
    fun relativeLuminance(argb: Int): Double {
        val rLin = linearizeChannel(((argb ushr 16) and 0xFF) / 255.0)
        val gLin = linearizeChannel(((argb ushr 8) and 0xFF) / 255.0)
        val bLin = linearizeChannel((argb and 0xFF) / 255.0)
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

    private fun accumulateQuantizedHues(pixels: IntArray, totalPixels: Int): QuantizedHueStats {
        val stride = (totalPixels / MAX_SAMPLES).coerceAtLeast(1)
        val bucketWeight = FloatArray(HUE_BUCKETS)
        val bucketSin = FloatArray(HUE_BUCKETS)
        val bucketCos = FloatArray(HUE_BUCKETS)
        val bucketSat = FloatArray(HUE_BUCKETS)
        val hsl = FloatArray(3)
        var opaqueCount = 0
        var chromaticCount = 0

        var i = 0
        while (i < totalPixels) {
            val argb = pixels[i]
            i += stride
            if (((argb ushr 24) and 0xFF) >= MIN_ALPHA) {
                opaqueCount++
                val sampledChromatic = tryAccumulateChromaticPixel(
                    argb = argb,
                    hslScratch = hsl,
                    bucketWeight = bucketWeight,
                    bucketSin = bucketSin,
                    bucketCos = bucketCos,
                    bucketSat = bucketSat,
                )
                if (sampledChromatic) {
                    chromaticCount++
                }
            }
        }

        return QuantizedHueStats(
            weight = bucketWeight,
            sinSum = bucketSin,
            cosSum = bucketCos,
            satSum = bucketSat,
            chromaticCount = chromaticCount,
            opaqueCount = opaqueCount,
        )
    }

    private fun tryAccumulateChromaticPixel(
        argb: Int,
        hslScratch: FloatArray,
        bucketWeight: FloatArray,
        bucketSin: FloatArray,
        bucketCos: FloatArray,
        bucketSat: FloatArray,
    ): Boolean {
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        val cMaxInt = max(r, max(g, b))
        val cMinInt = min(r, min(g, b))
        val deltaInt = cMaxInt - cMinInt
        if (deltaInt < MIN_RGB_DELTA || cMaxInt == 0) return false

        val hsvSat = deltaInt.toFloat() / cMaxInt.toFloat()
        if (hsvSat < MIN_HSV_SAT) return false

        rgbToHsl(r = r, g = g, b = b, outHsl = hslScratch)
        val hue = hslScratch[0]
        val hslSat = hslScratch[1]
        val hslLum = hslScratch[2]
        if (hslSat < MIN_HSL_SAT || hslLum < MIN_HSL_LUM || hslLum > MAX_HSL_LUM) return false

        val lumProminence = (1.0f - abs(hslLum - 0.50f) * 1.15f).coerceIn(0.25f, 1.0f)
        val weight = (0.25f + hsvSat * hsvSat) * lumProminence
        val bucket = ((hue / 360f) * HUE_BUCKETS).toInt().coerceIn(0, HUE_BUCKETS - 1)
        val rad = hue * (PI.toFloat() / 180f)

        bucketWeight[bucket] += weight
        bucketSin[bucket] += sin(rad) * weight
        bucketCos[bucket] += cos(rad) * weight
        bucketSat[bucket] += max(hsvSat, hslSat) * weight
        return true
    }

    private fun solveStudioAccent(
        hueDeg: Float,
        baseSaturation: Float,
        initialLum: Float,
        onAccentArgb: Int,
        surfaceArgb: Int,
        surfaceVariantArgb: Int,
        maxRelativeLum: Double,
    ): Int {
        var lum = initialLum
        var sat = baseSaturation
        var candidate = hslToOpaqueArgb(hueDeg, sat, lum)

        // Step 1: Tame overly bright/neon hues (yellow, lime, cyan) while keeping crisp contrast.
        while (lum > 0.48f && relativeLuminance(candidate) > maxRelativeLum) {
            val nextLum = (lum - 0.02f).coerceAtLeast(0.48f)
            val nextCandidate = hslToOpaqueArgb(hueDeg, sat, nextLum)
            if (contrastRatio(nextCandidate, onAccentArgb) >= 5.5 &&
                contrastRatio(nextCandidate, surfaceVariantArgb) >= 5.5
            ) {
                lum = nextLum
                candidate = nextCandidate
            } else {
                break
            }
        }

        // Step 2: Lift inherently low-luminance hues (deep blue, violet, red) until WCAG >= 5.0:1.
        while (
            lum < 0.94f &&
            !meetsAccentContrast(
                candidateArgb = candidate,
                onAccentArgb = onAccentArgb,
                surfaceArgb = surfaceArgb,
                surfaceVariantArgb = surfaceVariantArgb,
                threshold = TARGET_ACCENT_CONTRAST_RATIO,
            )
        ) {
            lum = (lum + 0.02f).coerceAtMost(0.94f)
            if (lum > 0.84f) {
                sat = (sat * 0.92f).coerceAtLeast(0.24f)
            }
            candidate = hslToOpaqueArgb(hueDeg, sat, lum)
        }

        return if (meetsAccentContrast(candidate, onAccentArgb, surfaceArgb, surfaceVariantArgb, MIN_ACCENT_CONTRAST_RATIO)) {
            candidate
        } else {
            MONOCHROME_STUDIO_TOKENS.primaryArgb
        }
    }

    private fun meetsAccentContrast(
        candidateArgb: Int,
        onAccentArgb: Int,
        surfaceArgb: Int,
        surfaceVariantArgb: Int,
        threshold: Double,
    ): Boolean = contrastRatio(candidateArgb, onAccentArgb) >= threshold &&
        contrastRatio(candidateArgb, surfaceArgb) >= threshold &&
        contrastRatio(candidateArgb, surfaceVariantArgb) >= threshold

    private fun ensureLightTextContrast(hueDeg: Float, saturation: Float, initialLum: Float, againstArgb: Int, minRatio: Double): Int {
        var lum = initialLum
        var candidate = hslToOpaqueArgb(hueDeg, saturation, lum)
        while (lum < 0.97f && contrastRatio(candidate, againstArgb) < minRatio) {
            lum = (lum + 0.02f).coerceAtMost(0.97f)
            candidate = hslToOpaqueArgb(hueDeg, saturation, lum)
        }
        return candidate
    }

    private fun linearizeChannel(channel: Double): Double = if (channel <= 0.04045) {
        channel / 12.92
    } else {
        ((channel + 0.055) / 1.055).pow(2.4)
    }

    private fun rgbToHsl(r: Int, g: Int, b: Int, outHsl: FloatArray) {
        val rf = r / 255f
        val gf = g / 255f
        val bf = b / 255f
        val cMax = max(rf, max(gf, bf))
        val cMin = min(rf, min(gf, bf))
        val delta = cMax - cMin
        val lum = (cMax + cMin) * 0.5f

        val hue: Float
        val sat: Float
        if (delta <= 1e-6f) {
            hue = 0f
            sat = 0f
        } else {
            sat = delta / (1f - abs(2f * lum - 1f)).coerceAtLeast(1e-6f)
            var rawH = when (cMax) {
                rf -> ((gf - bf) / delta) % 6f
                gf -> ((bf - rf) / delta) + 2f
                else -> ((rf - gf) / delta) + 4f
            } * 60f
            if (rawH < 0f) rawH += 360f
            hue = rawH
        }
        outHsl[0] = hue
        outHsl[1] = sat.coerceIn(0f, 1f)
        outHsl[2] = lum.coerceIn(0f, 1f)
    }

    private fun hslToOpaqueArgb(hDeg: Float, s: Float, l: Float): Int {
        val hue = ((hDeg % 360f) + 360f) % 360f
        val sat = s.coerceIn(0f, 1f)
        val lum = l.coerceIn(0f, 1f)
        val chroma = (1f - abs(2f * lum - 1f)) * sat
        val secondary = chroma * (1f - abs(((hue / 60f) % 2f) - 1f))
        val match = lum - chroma * 0.5f

        val r1: Float
        val g1: Float
        val b1: Float
        when {
            hue < 60f -> {
                r1 = chroma
                g1 = secondary
                b1 = 0f
            }
            hue < 120f -> {
                r1 = secondary
                g1 = chroma
                b1 = 0f
            }
            hue < 180f -> {
                r1 = 0f
                g1 = chroma
                b1 = secondary
            }
            hue < 240f -> {
                r1 = 0f
                g1 = secondary
                b1 = chroma
            }
            hue < 300f -> {
                r1 = secondary
                g1 = 0f
                b1 = chroma
            }
            else -> {
                r1 = chroma
                g1 = 0f
                b1 = secondary
            }
        }
        val r = ((r1 + match) * 255f).roundToInt().coerceIn(0, 255)
        val g = ((g1 + match) * 255f).roundToInt().coerceIn(0, 255)
        val b = ((b1 + match) * 255f).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
