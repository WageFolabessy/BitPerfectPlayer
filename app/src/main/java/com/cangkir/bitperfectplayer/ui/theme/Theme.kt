package com.cangkir.bitperfectplayer.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val BitPerfectDarkColorScheme = darkColorScheme(
    primary = IcePrimary,
    onPrimary = OnIcePrimary,
    primaryContainer = IcePrimaryContainer,
    onPrimaryContainer = OnIcePrimaryContainer,
    secondary = SlateSecondary,
    onSecondary = OnSlateSecondary,
    secondaryContainer = SlateSecondaryContainer,
    onSecondaryContainer = OnSlateSecondaryContainer,
    tertiary = EmeraldSafe,
    onTertiary = OnIcePrimary,
    tertiaryContainer = EmeraldSafeContainer,
    onTertiaryContainer = OnEmeraldSafeContainer,
    error = CoralWarning,
    onError = OnIcePrimary,
    errorContainer = CoralWarningContainer,
    onErrorContainer = OnCoralWarningContainer,
    background = ObsidianBackground,
    onBackground = TextPrimary,
    surface = ObsidianSurface,
    onSurface = TextPrimary,
    surfaceVariant = ObsidianSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    surfaceContainerHighest = ObsidianSurfaceHighest,
    outline = ObsidianOutline,
)

/**
 * Enforces a consistent minimalist utilitarian dark Material 3 theme across all screens.
 */
@Composable
fun BitPerfectPlayerTheme(darkTheme: Boolean = true, dynamicColor: Boolean = false, content: @Composable () -> Unit) {
    // Locked intentionally to BitPerfectDarkColorScheme so contrast and safety indicators stay consistent.
    val colorScheme = if (darkTheme || !dynamicColor) {
        BitPerfectDarkColorScheme
    } else {
        BitPerfectDarkColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
