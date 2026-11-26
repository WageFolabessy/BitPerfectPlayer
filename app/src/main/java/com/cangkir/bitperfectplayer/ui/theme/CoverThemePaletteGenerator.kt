package com.cangkir.bitperfectplayer.ui.theme

import kotlin.math.min

/**
 * Chromatic intensity preset for Dynamic Theme rendering.
 *
 * Both presets strictly enforce WCAG 2.1 contrast thresholds (`>= 4.5:1` interactive accents,
 * `>= 7.0:1` primary text, dark obsidian background luminance `< 0.05`).
 */
enum class DynamicThemeIntensity(val displayName: String) {
    SUBTLE("Subtle"),
    VIBRANT("Vibrant"),
    ;

    companion object {
        fun fromStoredName(raw: String?): DynamicThemeIntensity =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: SUBTLE
    }
}

/**
 * Immutable pure-Kotlin multi-color token set for dynamic artwork theming (`BitPerfectPlayerTheme`).
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
    val highlightPeakArgb: Int = primaryArgb,
    val glowPrimaryArgb: Int = primaryContainerArgb,
    val glowSecondaryArgb: Int = secondaryContainerArgb,
    val spectrumLowArgb: Int = secondaryArgb,
    val spectrumMidArgb: Int = primaryArgb,
    val spectrumHighArgb: Int = highlightPeakArgb,
    val intensity: DynamicThemeIntensity = DynamicThemeIntensity.SUBTLE,
) {
    fun toSpectrumTriad(): IntArray = intArrayOf(spectrumLowArgb, spectrumMidArgb, spectrumHighArgb)
}

typealias BitPerfectDynamicTokens = DynamicThemeTokens

/**
 * Pure-Kotlin, zero-dependency OKLCH dynamic theme token generator (zero `android.*` / `androidx.*`
 * imports).
 *
 * Replaces the legacy 1D HSL single-hue histogram with [ArtworkPaletteExtractor] (deterministic
 * center-weighted OKLab K-Means++) and [OklchColorMath] chroma-bisection gamut mapping so
 * primary/secondary accents reflect genuine multi-color artwork harmony while strictly guaranteeing
 * WCAG 2.1 contrast invariants across both [DynamicThemeIntensity.SUBTLE] and
 * [DynamicThemeIntensity.VIBRANT].
 */
object CoverThemePaletteGenerator {
    private const val MIN_ACCENT_CONTRAST_RATIO = 4.5
    private const val TARGET_TEXT_CONTRAST_RATIO = 7.5
    private const val TARGET_VARIANT_TEXT_CONTRAST_RATIO = 4.8

    private data class IntensityProfile(
        val canvasChromaScale: Float,
        val canvasChromaMin: Float,
        val canvasChromaMax: Float,
        val primaryChromaScale: Float,
        val primaryChromaMin: Float,
        val primaryChromaMax: Float,
        val secondaryChromaScale: Float,
        val secondaryChromaMin: Float,
        val secondaryChromaMax: Float,
        val containerChromaScale: Float,
        val containerChromaMin: Float,
        val containerChromaMax: Float,
        val maxPrimaryRelativeLum: Double,
        val glowPrimaryL: Float,
        val glowSecondaryL: Float,
    )

    private val SUBTLE_PROFILE = IntensityProfile(
        canvasChromaScale = 0.14f,
        canvasChromaMin = 0.009f,
        canvasChromaMax = 0.022f,
        primaryChromaScale = 0.80f,
        primaryChromaMin = 0.085f,
        primaryChromaMax = 0.145f,
        secondaryChromaScale = 0.72f,
        secondaryChromaMin = 0.070f,
        secondaryChromaMax = 0.125f,
        containerChromaScale = 0.34f,
        containerChromaMin = 0.024f,
        containerChromaMax = 0.052f,
        maxPrimaryRelativeLum = 0.58,
        glowPrimaryL = 0.44f,
        glowSecondaryL = 0.40f,
    )

    private val VIBRANT_PROFILE = IntensityProfile(
        canvasChromaScale = 0.24f,
        canvasChromaMin = 0.015f,
        canvasChromaMax = 0.036f,
        primaryChromaScale = 1.05f,
        primaryChromaMin = 0.125f,
        primaryChromaMax = 0.205f,
        secondaryChromaScale = 0.96f,
        secondaryChromaMin = 0.110f,
        secondaryChromaMax = 0.185f,
        containerChromaScale = 0.52f,
        containerChromaMin = 0.038f,
        containerChromaMax = 0.078f,
        maxPrimaryRelativeLum = 0.62,
        glowPrimaryL = 0.52f,
        glowSecondaryL = 0.48f,
    )

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
        highlightPeakArgb = 0xFFF2F2F0.toInt(),
        glowPrimaryArgb = 0xFF2A2A30.toInt(),
        glowSecondaryArgb = 0xFF26262C.toInt(),
        spectrumLowArgb = 0xFFB8B8BE.toInt(),
        spectrumMidArgb = 0xFFD8D8DC.toInt(),
        spectrumHighArgb = 0xFFF2F2F0.toInt(),
        intensity = DynamicThemeIntensity.SUBTLE,
    )

    /**
     * Extracts contrast-verified multi-color [DynamicThemeTokens] from ARGB [pixels]
     * (`width * height`), or returns `null` when the artwork is missing, transparent, or monochrome.
     */
    fun extractThemeTokens(
        pixels: IntArray,
        width: Int,
        height: Int,
        intensity: DynamicThemeIntensity = DynamicThemeIntensity.SUBTLE,
    ): DynamicThemeTokens? {
        val palette = ArtworkPaletteExtractor.extractPalette(pixels, width, height)
        return generateTokensFromPalette(palette, intensity)
    }

    /**
     * Convenience helper returning [extractThemeTokens] when chromatic, or [MONOCHROME_STUDIO_TOKENS].
     */
    fun resolveThemeTokensOrDefault(
        pixels: IntArray,
        width: Int,
        height: Int,
        intensity: DynamicThemeIntensity = DynamicThemeIntensity.SUBTLE,
    ): DynamicThemeTokens = extractThemeTokens(pixels, width, height, intensity) ?: MONOCHROME_STUDIO_TOKENS

    /**
     * Maps a cached perceptual [ArtworkColorPalette] into contrast-verified [BitPerfectDynamicTokens]
     * for [intensity] (`SUBTLE` vs `VIBRANT`), or returns `null` when [palette] is monochrome.
     */
    fun generateTokensFromPalette(
        palette: ArtworkColorPalette,
        intensity: DynamicThemeIntensity = DynamicThemeIntensity.SUBTLE,
    ): BitPerfectDynamicTokens? {
        if (palette.isMonochrome) return null
        return buildTokensInOklch(
            primary = palette.primaryOklch,
            secondary = palette.secondaryOklch,
            canvasHueDeg = palette.canvasOklch.hDeg,
            highlightPeakArgb = palette.highlightPeakArgb,
            spectrumLowArgb = palette.spectrumLowArgb,
            spectrumMidArgb = palette.spectrumMidArgb,
            spectrumHighArgb = palette.spectrumHighArgb,
            intensity = intensity,
        )
    }

    /**
     * Generates a contrast-verified OKLCH dark theme token set anchored at [hueDeg] and
     * [sourceSaturation] (`0..1`) for either [DynamicThemeIntensity.SUBTLE] or
     * [DynamicThemeIntensity.VIBRANT].
     */
    fun generateTokensFromHue(
        hueDeg: Float,
        sourceSaturation: Float,
        intensity: DynamicThemeIntensity = DynamicThemeIntensity.SUBTLE,
    ): DynamicThemeTokens {
        val hue = OklchColorMath.normalizeHueDeg(hueDeg)
        val sat = if (sourceSaturation.isFinite()) sourceSaturation.coerceIn(0f, 1f) else 0f
        val baseChroma = (0.04f + sat * 0.16f).coerceIn(0.04f, 0.20f)
        val primary = Oklch(l = 0.72f, c = baseChroma, hDeg = hue)
        val secondaryHue = OklchColorMath.normalizeHueDeg(hue + 40f)
        val secondary = Oklch(l = 0.72f, c = (baseChroma * 0.85f).coerceIn(0.04f, 0.18f), hDeg = secondaryHue)
        val highlight = OklchColorMath.oklchToSrgbArgb(
            l = 0.85f,
            c = min(0.14f, OklchColorMath.maxInGamutChroma(0.85f, secondaryHue) * 0.90f),
            hDeg = secondaryHue,
        )
        val specLow = OklchColorMath.oklchToSrgbArgb(0.69f, min(0.16f, OklchColorMath.maxInGamutChroma(0.69f, hue) * 0.85f), hue)
        val specMid = OklchColorMath.oklchToSrgbArgb(
            0.72f,
            min(0.16f, OklchColorMath.maxInGamutChroma(0.72f, secondaryHue) * 0.85f),
            secondaryHue,
        )
        val thirdHue = OklchColorMath.normalizeHueDeg(hue + 158f)
        val specHigh = OklchColorMath.oklchToSrgbArgb(
            0.75f,
            min(0.16f, OklchColorMath.maxInGamutChroma(0.75f, thirdHue) * 0.85f),
            thirdHue,
        )
        return buildTokensInOklch(
            primary = primary,
            secondary = secondary,
            canvasHueDeg = hue,
            highlightPeakArgb = highlight,
            spectrumLowArgb = specLow,
            spectrumMidArgb = specMid,
            spectrumHighArgb = specHigh,
            intensity = intensity,
        )
    }

    /** Computes exact WCAG 2.1 relative luminance (`0.0..1.0`) for an opaque or semi-opaque ARGB color. */
    fun relativeLuminance(argb: Int): Double = OklchColorMath.relativeLuminance(argb)

    /** Computes exact WCAG 2.1 contrast ratio (`1.0..21.0`) between [argb1] and [argb2]. */
    fun contrastRatio(argb1: Int, argb2: Int): Double = OklchColorMath.contrastRatio(argb1, argb2)

    private data class DarkSurfaceRamp(
        val backgroundArgb: Int,
        val onBackgroundArgb: Int,
        val surfaceArgb: Int,
        val onSurfaceArgb: Int,
        val surfaceVariantArgb: Int,
        val onSurfaceVariantArgb: Int,
        val surfaceContainerHighestArgb: Int,
        val outlineArgb: Int,
    )

    private fun buildDarkSurfaceRamp(canvasHue: Float, bgChroma: Float, profile: IntensityProfile): DarkSurfaceRamp {
        val backgroundArgb = OklchColorMath.oklchToSrgbArgb(l = 0.155f, c = bgChroma, hDeg = canvasHue)
        val surfaceArgb = OklchColorMath.oklchToSrgbArgb(l = 0.195f, c = bgChroma * 1.05f, hDeg = canvasHue)
        val surfaceVariantArgb = OklchColorMath.oklchToSrgbArgb(
            l = 0.232f,
            c = (bgChroma * 1.15f).coerceAtMost(profile.canvasChromaMax * 1.15f),
            hDeg = canvasHue,
        )
        val surfaceContainerHighestArgb = OklchColorMath.oklchToSrgbArgb(
            l = 0.272f,
            c = (bgChroma * 1.20f).coerceAtMost(profile.canvasChromaMax * 1.25f),
            hDeg = canvasHue,
        )
        val outlineArgb = OklchColorMath.oklchToSrgbArgb(
            l = 0.345f,
            c = (bgChroma * 1.15f).coerceAtMost(profile.canvasChromaMax * 1.20f),
            hDeg = canvasHue,
        )
        val onBackgroundArgb = OklchColorMath.solveAccessibleLightText(
            hueDeg = canvasHue,
            chroma = 0.008f,
            initialLightness = 0.955f,
            againstArgb = backgroundArgb,
            minRatio = TARGET_TEXT_CONTRAST_RATIO,
        )
        val onSurfaceArgb = OklchColorMath.solveAccessibleLightText(
            hueDeg = canvasHue,
            chroma = 0.008f,
            initialLightness = 0.955f,
            againstArgb = surfaceArgb,
            minRatio = TARGET_TEXT_CONTRAST_RATIO,
        )
        val onSurfaceVariantArgb = OklchColorMath.solveAccessibleLightText(
            hueDeg = canvasHue,
            chroma = 0.012f,
            initialLightness = 0.72f,
            againstArgb = surfaceVariantArgb,
            minRatio = TARGET_VARIANT_TEXT_CONTRAST_RATIO,
        )
        return DarkSurfaceRamp(
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

    private fun buildTokensInOklch(
        primary: Oklch,
        secondary: Oklch,
        canvasHueDeg: Float,
        highlightPeakArgb: Int,
        spectrumLowArgb: Int,
        spectrumMidArgb: Int,
        spectrumHighArgb: Int,
        intensity: DynamicThemeIntensity,
    ): DynamicThemeTokens {
        val profile = when (intensity) {
            DynamicThemeIntensity.SUBTLE -> SUBTLE_PROFILE
            DynamicThemeIntensity.VIBRANT -> VIBRANT_PROFILE
        }
        val canvasHue = OklchColorMath.normalizeHueDeg(canvasHueDeg)
        val primaryHue = OklchColorMath.normalizeHueDeg(primary.hDeg)
        val secondaryHue = OklchColorMath.normalizeHueDeg(secondary.hDeg)

        val bgChroma = (primary.c * profile.canvasChromaScale).coerceIn(profile.canvasChromaMin, profile.canvasChromaMax)
        val surfaces = buildDarkSurfaceRamp(canvasHue, bgChroma, profile)

        val onPrimaryArgb = OklchColorMath.oklchToSrgbArgb(l = 0.145f, c = bgChroma * 0.75f, hDeg = primaryHue)
        val onSecondaryArgb = OklchColorMath.oklchToSrgbArgb(l = 0.145f, c = bgChroma * 0.75f, hDeg = secondaryHue)

        val primaryDesiredChroma = (primary.c * profile.primaryChromaScale).coerceIn(
            profile.primaryChromaMin,
            profile.primaryChromaMax,
        )
        val primaryArgb = OklchColorMath.solveAccessibleDarkThemeAccent(
            hueDeg = primaryHue,
            desiredChroma = primaryDesiredChroma,
            initialLightness = 0.75f,
            onAccentArgb = onPrimaryArgb,
            darkReferenceArgb = surfaces.surfaceVariantArgb,
            minContrastRatio = MIN_ACCENT_CONTRAST_RATIO,
            maxRelativeLuminance = profile.maxPrimaryRelativeLum,
        )

        val secondaryDesiredChroma = (secondary.c * profile.secondaryChromaScale).coerceIn(
            profile.secondaryChromaMin,
            profile.secondaryChromaMax,
        )
        val secondaryArgb = OklchColorMath.solveAccessibleDarkThemeAccent(
            hueDeg = secondaryHue,
            desiredChroma = secondaryDesiredChroma,
            initialLightness = 0.74f,
            onAccentArgb = onSecondaryArgb,
            darkReferenceArgb = surfaces.surfaceVariantArgb,
            minContrastRatio = MIN_ACCENT_CONTRAST_RATIO,
            maxRelativeLuminance = profile.maxPrimaryRelativeLum,
        )

        val primaryContainerChroma = (primary.c * profile.containerChromaScale).coerceIn(
            profile.containerChromaMin,
            profile.containerChromaMax,
        )
        val primaryContainerArgb = OklchColorMath.oklchToSrgbArgb(l = 0.285f, c = primaryContainerChroma, hDeg = primaryHue)
        val onPrimaryContainerArgb = OklchColorMath.solveAccessibleLightText(
            hueDeg = primaryHue,
            chroma = 0.016f,
            initialLightness = 0.94f,
            againstArgb = primaryContainerArgb,
            minRatio = 7.0,
        )

        val secondaryContainerChroma = (secondary.c * profile.containerChromaScale).coerceIn(
            profile.containerChromaMin,
            profile.containerChromaMax,
        )
        val secondaryContainerArgb = OklchColorMath.oklchToSrgbArgb(l = 0.275f, c = secondaryContainerChroma, hDeg = secondaryHue)
        val onSecondaryContainerArgb = OklchColorMath.solveAccessibleLightText(
            hueDeg = secondaryHue,
            chroma = 0.016f,
            initialLightness = 0.93f,
            againstArgb = secondaryContainerArgb,
            minRatio = 7.0,
        )

        val glowPrimaryArgb = OklchColorMath.oklchToSrgbArgb(
            l = profile.glowPrimaryL,
            c = min(primaryDesiredChroma, OklchColorMath.maxInGamutChroma(profile.glowPrimaryL, primaryHue) * 0.92f),
            hDeg = primaryHue,
        )
        val glowSecondaryArgb = OklchColorMath.oklchToSrgbArgb(
            l = profile.glowSecondaryL,
            c = min(secondaryDesiredChroma, OklchColorMath.maxInGamutChroma(profile.glowSecondaryL, secondaryHue) * 0.92f),
            hDeg = secondaryHue,
        )

        return DynamicThemeTokens(
            primaryArgb = primaryArgb,
            onPrimaryArgb = onPrimaryArgb,
            primaryContainerArgb = primaryContainerArgb,
            onPrimaryContainerArgb = onPrimaryContainerArgb,
            secondaryArgb = secondaryArgb,
            onSecondaryArgb = onSecondaryArgb,
            secondaryContainerArgb = secondaryContainerArgb,
            onSecondaryContainerArgb = onSecondaryContainerArgb,
            backgroundArgb = surfaces.backgroundArgb,
            onBackgroundArgb = surfaces.onBackgroundArgb,
            surfaceArgb = surfaces.surfaceArgb,
            onSurfaceArgb = surfaces.onSurfaceArgb,
            surfaceVariantArgb = surfaces.surfaceVariantArgb,
            onSurfaceVariantArgb = surfaces.onSurfaceVariantArgb,
            surfaceContainerHighestArgb = surfaces.surfaceContainerHighestArgb,
            outlineArgb = surfaces.outlineArgb,
            highlightPeakArgb = highlightPeakArgb,
            glowPrimaryArgb = glowPrimaryArgb,
            glowSecondaryArgb = glowSecondaryArgb,
            spectrumLowArgb = spectrumLowArgb,
            spectrumMidArgb = spectrumMidArgb,
            spectrumHighArgb = spectrumHighArgb,
            intensity = intensity,
        )
    }
}
