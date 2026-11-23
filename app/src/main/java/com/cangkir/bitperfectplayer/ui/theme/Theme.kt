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
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color

private const val THEME_TRANSITION_DURATION_MS = 300

val LocalActiveDynamicTokens = compositionLocalOf<DynamicThemeTokens?> { null }

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

/**
 * Enforces the Monochrome Studio dark Material 3 theme across all screens, smoothly adapting to
 * contrast-clamped [dynamicTokens] when the opt-in Dynamic Cover Theme layer is active.
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
    val effectiveTokens = dynamicTokens ?: CoverThemePaletteGenerator.MONOCHROME_STUDIO_TOKENS
    val colorScheme = rememberAnimatedColorScheme(effectiveTokens)

    CompositionLocalProvider(LocalActiveDynamicTokens provides dynamicTokens) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content,
        )
    }
}
