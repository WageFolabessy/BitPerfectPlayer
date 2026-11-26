package com.cangkir.bitperfectplayer.ui.theme

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/**
 * Pure-Kotlin alpha profile for ambient atmospheric glow orbs, scaled conservatively so matte
 * obsidian surfaces and technical audio metadata remain razor-sharp on OLED displays.
 */
internal object AmbientAtmosphericGlow {
    fun primaryAlpha(intensity: DynamicThemeIntensity): Float = when (intensity) {
        DynamicThemeIntensity.SUBTLE -> 0.18f
        DynamicThemeIntensity.VIBRANT -> 0.30f
    }

    fun secondaryAlpha(intensity: DynamicThemeIntensity): Float = when (intensity) {
        DynamicThemeIntensity.SUBTLE -> 0.13f
        DynamicThemeIntensity.VIBRANT -> 0.22f
    }

    fun artistHeroPrimaryAlpha(intensity: DynamicThemeIntensity): Float = when (intensity) {
        DynamicThemeIntensity.SUBTLE -> 0.20f
        DynamicThemeIntensity.VIBRANT -> 0.34f
    }

    fun artistHeroSecondaryAlpha(intensity: DynamicThemeIntensity): Float = when (intensity) {
        DynamicThemeIntensity.SUBTLE -> 0.14f
        DynamicThemeIntensity.VIBRANT -> 0.24f
    }
}

/**
 * GPU-friendly, zero-blur, zero-per-frame-allocation multi-stop radial atmospheric glow rendered
 * strictly behind the Now Playing centerpiece (`Vinyl Disc` or `Square Cover`).
 *
 * Uses [Modifier.drawWithCache] so [Brush.radialGradient] shaders rebuild only when size or
 * animated [LocalBitPerfectExtendedColors] change (zero draw-loop allocations at 120 Hz), and fades
 * smoothly to [Color.Transparent] before reaching track metadata and transport controls.
 */
@Composable
fun AmbientNowPlayingGlow(modifier: Modifier = Modifier) {
    val extended = LocalBitPerfectExtendedColors.current
    if (!extended.isDynamicActive) return

    val glowPrimary = extended.glowPrimary
    val glowSecondary = extended.glowSecondary
    val primaryAlpha = AmbientAtmosphericGlow.primaryAlpha(extended.intensity)
    val secondaryAlpha = AmbientAtmosphericGlow.secondaryAlpha(extended.intensity)

    Spacer(
        modifier = modifier
            .semantics { contentDescription = "Ambient artwork glow" }
            .drawWithCache {
                val minDim = size.minDimension.coerceAtLeast(1f)
                val cx = size.width * 0.5f
                val cy = size.height * 0.5f
                val primaryCenter = Offset(cx - minDim * 0.11f, cy - minDim * 0.09f)
                val secondaryCenter = Offset(cx + minDim * 0.13f, cy + minDim * 0.08f)
                val primaryRadius = minDim * 0.58f
                val secondaryRadius = minDim * 0.52f

                val primaryBrush = Brush.radialGradient(
                    0.0f to glowPrimary.copy(alpha = primaryAlpha),
                    0.48f to glowPrimary.copy(alpha = primaryAlpha * 0.44f),
                    1.0f to Color.Transparent,
                    center = primaryCenter,
                    radius = primaryRadius,
                )
                val secondaryBrush = Brush.radialGradient(
                    0.0f to glowSecondary.copy(alpha = secondaryAlpha),
                    0.50f to glowSecondary.copy(alpha = secondaryAlpha * 0.40f),
                    1.0f to Color.Transparent,
                    center = secondaryCenter,
                    radius = secondaryRadius,
                )
                onDrawBehind {
                    drawCircle(brush = primaryBrush, radius = primaryRadius, center = primaryCenter)
                    drawCircle(brush = secondaryBrush, radius = secondaryRadius, center = secondaryCenter)
                }
            },
    )
}

/**
 * GPU-friendly multi-stop atmospheric wash rendered behind the Artist Hero Banner on
 * `ArtistDetailView`, consuming the isolated contextual Artist Dynamic Theme colors via
 * [LocalBitPerfectExtendedColors].
 */
@Composable
fun AmbientArtistHeroWash(modifier: Modifier = Modifier) {
    val extended = LocalBitPerfectExtendedColors.current
    if (!extended.isDynamicActive) return

    val glowPrimary = extended.glowPrimary
    val glowSecondary = extended.glowSecondary
    val primaryAlpha = AmbientAtmosphericGlow.artistHeroPrimaryAlpha(extended.intensity)
    val secondaryAlpha = AmbientAtmosphericGlow.artistHeroSecondaryAlpha(extended.intensity)

    Spacer(
        modifier = modifier
            .semantics { contentDescription = "Ambient artist hero glow" }
            .drawWithCache {
                val width = size.width.coerceAtLeast(1f)
                val height = size.height.coerceAtLeast(1f)
                val primaryCenter = Offset(width * 0.34f, height * 0.36f)
                val secondaryCenter = Offset(width * 0.72f, height * 0.52f)
                val primaryRadius = maxOf(width, height) * 0.62f
                val secondaryRadius = maxOf(width, height) * 0.54f

                val primaryBrush = Brush.radialGradient(
                    0.0f to glowPrimary.copy(alpha = primaryAlpha),
                    0.52f to glowPrimary.copy(alpha = primaryAlpha * 0.38f),
                    1.0f to Color.Transparent,
                    center = primaryCenter,
                    radius = primaryRadius,
                )
                val secondaryBrush = Brush.radialGradient(
                    0.0f to glowSecondary.copy(alpha = secondaryAlpha),
                    0.52f to glowSecondary.copy(alpha = secondaryAlpha * 0.35f),
                    1.0f to Color.Transparent,
                    center = secondaryCenter,
                    radius = secondaryRadius,
                )
                onDrawBehind {
                    drawRect(brush = primaryBrush)
                    drawRect(brush = secondaryBrush)
                }
            },
    )
}
