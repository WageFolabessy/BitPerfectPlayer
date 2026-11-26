package com.cangkir.bitperfectplayer.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color

private const val THEME_TRANSITION_DURATION_MS = 300

/**
 * Extended multi-color dynamic theme tokens exposed alongside Material 3 [ColorScheme] for
 * luminous highlights, GPU-friendly ambient atmospheric glow orbs, and album-adaptive accents.
 *
 * Semantic safety/diagnostic colors (`EmeraldSafe`, `AmberIndicated`, `CoralWarning`) live outside
 * this structure and remain strictly invariant across all artwork palettes.
 */
@Immutable
data class BitPerfectExtendedColors(
    val highlightPeak: Color,
    val glowPrimary: Color,
    val glowSecondary: Color,
    val spectrumLow: Color,
    val spectrumMid: Color,
    val spectrumHigh: Color,
    val isDynamicActive: Boolean,
    val intensity: DynamicThemeIntensity,
)

private val DEFAULT_MONOCHROME_EXTENDED_COLORS = BitPerfectExtendedColors(
    highlightPeak = Color(CoverThemePaletteGenerator.MONOCHROME_STUDIO_TOKENS.highlightPeakArgb),
    glowPrimary = Color(CoverThemePaletteGenerator.MONOCHROME_STUDIO_TOKENS.glowPrimaryArgb),
    glowSecondary = Color(CoverThemePaletteGenerator.MONOCHROME_STUDIO_TOKENS.glowSecondaryArgb),
    spectrumLow = Color(CoverThemePaletteGenerator.MONOCHROME_STUDIO_TOKENS.spectrumLowArgb),
    spectrumMid = Color(CoverThemePaletteGenerator.MONOCHROME_STUDIO_TOKENS.spectrumMidArgb),
    spectrumHigh = Color(CoverThemePaletteGenerator.MONOCHROME_STUDIO_TOKENS.spectrumHighArgb),
    isDynamicActive = false,
    intensity = DynamicThemeIntensity.SUBTLE,
)

val LocalActiveDynamicTokens = compositionLocalOf<DynamicThemeTokens?> { null }
val LocalBitPerfectExtendedColors = compositionLocalOf { DEFAULT_MONOCHROME_EXTENDED_COLORS }

/**
 * High-contrast Monochrome Studio / Dynamic Cover toggle switch colors with unambiguous
 * active (bright accent track + obsidian thumb) vs inactive (dark slate track + muted thumb) states.
 */
@Composable
fun studioSwitchColors(): SwitchColors = SwitchDefaults.colors(
    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
    checkedTrackColor = MaterialTheme.colorScheme.primary,
    checkedBorderColor = MaterialTheme.colorScheme.primary,
    uncheckedThumbColor = TextMuted,
    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    uncheckedBorderColor = MaterialTheme.colorScheme.outline,
)

@Composable
private fun rememberAnimatedThemeColor(targetArgb: Int, label: String): State<Color> = animateColorAsState(
    targetValue = Color(targetArgb),
    animationSpec = tween(durationMillis = THEME_TRANSITION_DURATION_MS),
    label = label,
)

@Composable
private fun rememberAnimatedColorScheme(tokens: DynamicThemeTokens): ColorScheme {
    val primary by rememberAnimatedThemeColor(tokens.primaryArgb, "primary")
    val onPrimary by rememberAnimatedThemeColor(tokens.onPrimaryArgb, "onPrimary")
    val primaryContainer by rememberAnimatedThemeColor(tokens.primaryContainerArgb, "primaryContainer")
    val onPrimaryContainer by rememberAnimatedThemeColor(tokens.onPrimaryContainerArgb, "onPrimaryContainer")
    val secondary by rememberAnimatedThemeColor(tokens.secondaryArgb, "secondary")
    val onSecondary by rememberAnimatedThemeColor(tokens.onSecondaryArgb, "onSecondary")
    val secondaryContainer by rememberAnimatedThemeColor(tokens.secondaryContainerArgb, "secondaryContainer")
    val onSecondaryContainer by rememberAnimatedThemeColor(tokens.onSecondaryContainerArgb, "onSecondaryContainer")
    val background by rememberAnimatedThemeColor(tokens.backgroundArgb, "background")
    val onBackground by rememberAnimatedThemeColor(tokens.onBackgroundArgb, "onBackground")
    val surface by rememberAnimatedThemeColor(tokens.surfaceArgb, "surface")
    val onSurface by rememberAnimatedThemeColor(tokens.onSurfaceArgb, "onSurface")
    val surfaceVariant by rememberAnimatedThemeColor(tokens.surfaceVariantArgb, "surfaceVariant")
    val onSurfaceVariant by rememberAnimatedThemeColor(tokens.onSurfaceVariantArgb, "onSurfaceVariant")
    val surfaceContainerHighest by rememberAnimatedThemeColor(tokens.surfaceContainerHighestArgb, "surfaceContainerHighest")
    val outline by rememberAnimatedThemeColor(tokens.outlineArgb, "outline")

    return darkColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        secondary = secondary,
        onSecondary = onSecondary,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        tertiary = EmeraldSafe,
        onTertiary = OnStudioIvoryPrimary,
        tertiaryContainer = EmeraldSafeContainer,
        onTertiaryContainer = OnEmeraldSafeContainer,
        error = CoralWarning,
        onError = OnStudioIvoryPrimary,
        errorContainer = CoralWarningContainer,
        onErrorContainer = OnCoralWarningContainer,
        background = background,
        onBackground = onBackground,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = onSurfaceVariant,
        surfaceContainerHighest = surfaceContainerHighest,
        outline = outline,
    )
}

@Composable
private fun rememberAnimatedExtendedColors(tokens: DynamicThemeTokens, isDynamicActive: Boolean): BitPerfectExtendedColors {
    val highlightPeak by rememberAnimatedThemeColor(tokens.highlightPeakArgb, "highlightPeak")
    val glowPrimary by rememberAnimatedThemeColor(tokens.glowPrimaryArgb, "glowPrimary")
    val glowSecondary by rememberAnimatedThemeColor(tokens.glowSecondaryArgb, "glowSecondary")
    val spectrumLow by rememberAnimatedThemeColor(tokens.spectrumLowArgb, "spectrumLow")
    val spectrumMid by rememberAnimatedThemeColor(tokens.spectrumMidArgb, "spectrumMid")
    val spectrumHigh by rememberAnimatedThemeColor(tokens.spectrumHighArgb, "spectrumHigh")
    return BitPerfectExtendedColors(
        highlightPeak = highlightPeak,
        glowPrimary = glowPrimary,
        glowSecondary = glowSecondary,
        spectrumLow = spectrumLow,
        spectrumMid = spectrumMid,
        spectrumHigh = spectrumHigh,
        isDynamicActive = isDynamicActive,
        intensity = tokens.intensity,
    )
}

/**
 * Enforces the Monochrome Studio dark Material 3 theme across all screens, smoothly animating
 * multi-color OKLCH [dynamicTokens] (Material 3 slots + [LocalBitPerfectExtendedColors] glow and
 * highlight accents) over `300 ms` when the opt-in Dynamic Theme layer is active.
 */
@Composable
fun BitPerfectPlayerTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    dynamicTokens: DynamicThemeTokens? = null,
    content: @Composable () -> Unit,
) {
    // System Wallpaper Monet dynamicColor is intentionally ignored so contrast and safety indicators hold.
    @Suppress("UNUSED_VARIABLE")
    val unusedModeFlags = darkTheme || !dynamicColor
    val isDynamicActive = dynamicTokens != null
    val effectiveTokens = dynamicTokens ?: CoverThemePaletteGenerator.MONOCHROME_STUDIO_TOKENS
    val colorScheme = rememberAnimatedColorScheme(effectiveTokens)
    val extendedColors = rememberAnimatedExtendedColors(effectiveTokens, isDynamicActive = isDynamicActive)

    CompositionLocalProvider(
        LocalActiveDynamicTokens provides dynamicTokens,
        LocalBitPerfectExtendedColors provides extendedColors,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content,
        )
    }
}
