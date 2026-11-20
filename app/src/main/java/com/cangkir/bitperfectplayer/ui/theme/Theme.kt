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
 * Enforces a consistent audiophile Material 3 Dark Theme across all screens (SPEC Section 4.3).
 */
@Composable
fun BitPerfectPlayerTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    // Intentionally locked to BitPerfectDarkColorScheme so contrast and safety badges stay consistent.
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