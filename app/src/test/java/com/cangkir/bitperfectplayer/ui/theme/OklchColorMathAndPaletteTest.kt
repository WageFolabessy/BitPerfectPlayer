package com.cangkir.bitperfectplayer.ui.theme

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OklchColorMathAndPaletteTest {

    private fun solidSplitBitmap(width: Int, height: Int, leftArgb: Int, rightArgb: Int, splitFraction: Float = 0.5f): IntArray {
        val splitX = (width * splitFraction).toInt()
        return IntArray(width * height) { idx ->
            val x = idx % width
            if (x < splitX) leftArgb else rightArgb
        }
    }

    private fun assertStrictWcagAndStudioBounds(label: String, tokens: BitPerfectDynamicTokens) {
        val primaryOnPrimary = OklchColorMath.contrastRatio(tokens.primaryArgb, tokens.onPrimaryArgb)
        val primaryOnSurface = OklchColorMath.contrastRatio(tokens.primaryArgb, tokens.surfaceArgb)
        val primaryOnVariant = OklchColorMath.contrastRatio(tokens.primaryArgb, tokens.surfaceVariantArgb)
        val secondaryOnSecondary = OklchColorMath.contrastRatio(tokens.secondaryArgb, tokens.onSecondaryArgb)
        val secondaryOnVariant = OklchColorMath.contrastRatio(tokens.secondaryArgb, tokens.surfaceVariantArgb)
        val onBgContrast = OklchColorMath.contrastRatio(tokens.onBackgroundArgb, tokens.backgroundArgb)
        val onSurfaceContrast = OklchColorMath.contrastRatio(tokens.onSurfaceArgb, tokens.surfaceArgb)
        val onPrimaryContainer = OklchColorMath.contrastRatio(tokens.onPrimaryContainerArgb, tokens.primaryContainerArgb)
        val onSecondaryContainer = OklchColorMath.contrastRatio(tokens.onSecondaryContainerArgb, tokens.secondaryContainerArgb)
        val bgLum = OklchColorMath.relativeLuminance(tokens.backgroundArgb)

        assertTrue("$label: primary/onPrimary $primaryOnPrimary < 4.5", primaryOnPrimary >= 4.5)
        assertTrue("$label: primary/surface $primaryOnSurface < 4.5", primaryOnSurface >= 4.5)
        assertTrue("$label: primary/surfaceVariant $primaryOnVariant < 4.5", primaryOnVariant >= 4.5)
        assertTrue("$label: secondary/onSecondary $secondaryOnSecondary < 4.5", secondaryOnSecondary >= 4.5)
        assertTrue("$label: secondary/surfaceVariant $secondaryOnVariant < 4.5", secondaryOnVariant >= 4.5)
        assertTrue("$label: onBackground/background $onBgContrast < 7.0", onBgContrast >= 7.0)
        assertTrue("$label: onSurface/surface $onSurfaceContrast < 7.0", onSurfaceContrast >= 7.0)
        assertTrue("$label: onPrimaryContainer $onPrimaryContainer < 4.5", onPrimaryContainer >= 4.5)
        assertTrue("$label: onSecondaryContainer $onSecondaryContainer < 4.5", onSecondaryContainer >= 4.5)
        assertTrue("$label: background luminance $bgLum >= 0.05", bgLum < 0.05)
    }

    @Test
    fun oklchRoundTrip_srgbToOklchAndBack_matchesWithinOneLsbAcrossDiverseColors() {
        val samples = intArrayOf(
            0xFF000000.toInt(),
            0xFFFFFFFF.toInt(),
            0xFF808080.toInt(),
            0xFFE63946.toInt(),
            0xFF1D4ED8.toInt(),
            0xFFF59E0B.toInt(),
            0xFF10B981.toInt(),
            0xFF7C3AED.toInt(),
            0xFF06B6D4.toInt(),
            0xFF181A24.toInt(),
            0xFFF2D492.toInt(),
            0xFF4C1D95.toInt(),
        )

        for (argb in samples) {
            val oklch = OklchColorMath.argbToOklch(argb)
            val roundTripped = OklchColorMath.oklchToSrgbArgb(oklch)

            val dr = abs(((argb ushr 16) and 0xFF) - ((roundTripped ushr 16) and 0xFF))
            val dg = abs(((argb ushr 8) and 0xFF) - ((roundTripped ushr 8) and 0xFF))
            val db = abs((argb and 0xFF) - (roundTripped and 0xFF))
            assertTrue(
                "Round-trip RGB drift too high for 0x${argb.toUInt().toString(16)}: dr=$dr dg=$dg db=$db",
                dr <= 1 && dg <= 1 && db <= 1,
            )
        }
    }

    @Test
    fun oklchGamutClipping_preservesHueAndPreventsChalkyBluesOrMuddyOliveYellows() {
        // Out-of-gamut chroma across all hues must clamp chroma without twisting perceptual hue.
        for (hueDeg in 0 until 360 step 15) {
            val mapped = OklchColorMath.oklchToSrgbArgb(l = 0.70f, c = 0.35f, hDeg = hueDeg.toFloat())
            val actual = OklchColorMath.argbToOklch(mapped)
            val hueDrift = OklchColorMath.circularHueDistanceDeg(hueDeg.toFloat(), actual.hDeg)
            assertTrue("Hue $hueDeg drifted by $hueDrift deg during gamut clipping", hueDrift <= 2.5f)
        }

        val darkSurface = 0xFF1A1B24.toInt()
        val darkInk = 0xFF0E0F14.toInt()

        // Deep cobalt blue (h = 264) & deep violet (h = 294): legible >= 4.5:1 without chalky pastel washout.
        for (blueVioletHue in listOf(264f, 294f)) {
            val accent = OklchColorMath.solveAccessibleDarkThemeAccent(
                hueDeg = blueVioletHue,
                desiredChroma = 0.16f,
                initialLightness = 0.72f,
                onAccentArgb = darkInk,
                darkReferenceArgb = darkSurface,
            )
            val accentOklch = OklchColorMath.argbToOklch(accent)
            assertTrue("Blue/violet accent washed out to chalky pastel: L=${accentOklch.l}", accentOklch.l <= 0.84f)
            assertTrue("Blue/violet accent lost chroma: C=${accentOklch.c}", accentOklch.c >= 0.075f)
            assertTrue(
                "Blue/violet hue drifted: target=$blueVioletHue actual=${accentOklch.hDeg}",
                OklchColorMath.circularHueDistanceDeg(blueVioletHue, accentOklch.hDeg) <= 3.0f,
            )
            assertTrue(OklchColorMath.contrastRatio(accent, darkSurface) >= 4.5)
            assertTrue(OklchColorMath.contrastRatio(accent, darkInk) >= 4.5)
        }

        // Warm gold / yellow (h = 90): luminous warm gold, NEVER muddy olive (R > G > B and L >= 0.72).
        val goldAccent = OklchColorMath.solveAccessibleDarkThemeAccent(
            hueDeg = 90f,
            desiredChroma = 0.15f,
            initialLightness = 0.76f,
            onAccentArgb = darkInk,
            darkReferenceArgb = darkSurface,
        )
        val goldOklch = OklchColorMath.argbToOklch(goldAccent)
        val r = (goldAccent ushr 16) and 0xFF
        val g = (goldAccent ushr 8) and 0xFF
        val b = goldAccent and 0xFF
        assertTrue("Gold accent too dark / muddy olive: L=${goldOklch.l}", goldOklch.l >= 0.72f)
        assertTrue("Gold accent should stay warm (R > G > B), got rgb($r,$g,$b)", r > g && g > b)
        assertTrue(OklchColorMath.circularHueDistanceDeg(90f, goldOklch.hDeg) <= 3.0f)
        assertTrue(OklchColorMath.contrastRatio(goldAccent, darkSurface) >= 4.5)
    }

    @Test
    fun artworkPaletteExtractor_extractsGenuinelyDistinctPrimaryAndSecondaryAccentsFromMultiColorArt() {
        val pairs = listOf(
            Triple("Cobalt Blue + Warm Gold", 0xFF1E4FD6.toInt(), 0xFFE5A93B.toInt()),
            Triple("Crimson Red + Electric Cyan", 0xFFD9363E.toInt(), 0xFF1BB8D4.toInt()),
            Triple("Warm Orange + Deep Teal", 0xFFE86F2D.toInt(), 0xFF138A8A.toInt()),
        )

        for ((label, leftColor, rightColor) in pairs) {
            val pixels = solidSplitBitmap(
                width = 48,
                height = 48,
                leftArgb = leftColor,
                rightArgb = rightColor,
                splitFraction = 0.62f,
            )
            val palette = ArtworkPaletteExtractor.extractPalette(pixels, 48, 48)
            assertFalse("$label must not be classified as monochrome", palette.isMonochrome)

            val primaryActual = OklchColorMath.argbToOklch(palette.primaryAccentArgb)
            val secondaryActual = OklchColorMath.argbToOklch(palette.secondaryAccentArgb)
            val hueSeparation = OklchColorMath.circularHueDistanceDeg(primaryActual.hDeg, secondaryActual.hDeg)
            assertTrue(
                "$label expected genuinely distinct primary & secondary hues (>= 45 deg), got $hueSeparation deg",
                hueSeparation >= 45f,
            )

            // Verify left dominant field wins primary and right field supplies secondary.
            val leftOklch = OklchColorMath.argbToOklch(leftColor)
            val rightOklch = OklchColorMath.argbToOklch(rightColor)
            assertTrue(
                "$label primary hue ${primaryActual.hDeg} should match dominant field ${leftOklch.hDeg}",
                OklchColorMath.circularHueDistanceDeg(primaryActual.hDeg, leftOklch.hDeg) <= 20f,
            )
            assertTrue(
                "$label secondary hue ${secondaryActual.hDeg} should match secondary field ${rightOklch.hDeg}",
                OklchColorMath.circularHueDistanceDeg(secondaryActual.hDeg, rightOklch.hDeg) <= 20f,
            )

            // Verify SUBTLE & VIBRANT tokens preserve genuine multi-color primary vs secondary separation.
            for (intensity in DynamicThemeIntensity.entries) {
                val tokens = CoverThemePaletteGenerator.generateTokensFromPalette(palette, intensity)
                assertNotNull(tokens)
                assertStrictWcagAndStudioBounds("$label/${intensity.name}", tokens!!)
                val tokenPrimaryHue = OklchColorMath.argbToOklch(tokens.primaryArgb).hDeg
                val tokenSecondaryHue = OklchColorMath.argbToOklch(tokens.secondaryArgb).hDeg
                assertTrue(
                    "$label/${intensity.name} tokens collapsed primary & secondary hues",
                    OklchColorMath.circularHueDistanceDeg(tokenPrimaryHue, tokenSecondaryHue) >= 45f,
                )
            }
        }
    }

    @Test
    fun artworkPaletteExtractor_tinyNeonCornerBadgeDoesNotHijackDominantField() {
        val width = 48
        val height = 48
        val dominantBlue = 0xFF28558F.toInt() // calm dominant ocean blue covering ~98.4%
        val neonCornerMagenta = 0xFFFF00FF.toInt() // tiny 6x6 corner sticker (~1.5% of pixels)
        val pixels = IntArray(width * height) { idx ->
            val x = idx % width
            val y = idx / width
            if (x < 6 && y < 6) neonCornerMagenta else dominantBlue
        }

        val palette = ArtworkPaletteExtractor.extractPalette(pixels, width, height)
        assertFalse(palette.isMonochrome)

        val expectedBlueHue = OklchColorMath.argbToOklch(dominantBlue).hDeg
        val neonMagentaHue = OklchColorMath.argbToOklch(neonCornerMagenta).hDeg
        val actualPrimaryHue = OklchColorMath.argbToOklch(palette.primaryAccentArgb).hDeg

        assertTrue(
            "Dominant blue hue ($expectedBlueHue) should remain primary, got $actualPrimaryHue",
            OklchColorMath.circularHueDistanceDeg(actualPrimaryHue, expectedBlueHue) <= 15f,
        )
        assertTrue(
            "Tiny neon corner magenta ($neonMagentaHue) must NOT hijack primary ($actualPrimaryHue)",
            OklchColorMath.circularHueDistanceDeg(actualPrimaryHue, neonMagentaHue) >= 45f,
        )
    }

    @Test
    fun artworkPaletteExtractor_detectsMonochromeAndNearNeutralCoversCleanly() {
        val grayChecker = IntArray(48 * 48) { idx ->
            when (idx % 4) {
                0 -> 0xFF0A0A0C.toInt()
                1 -> 0xFF4E4E52.toInt()
                2 -> 0xFFA2A2A8.toInt()
                else -> 0xFFF4F4F6.toInt()
            }
        }
        val monoPalette = ArtworkPaletteExtractor.extractPalette(grayChecker, 48, 48)
        assertTrue(monoPalette.isMonochrome)
        assertEquals(ArtworkPaletteExtractor.MONOCHROME_STUDIO_PALETTE, monoPalette)

        val transparent = IntArray(48 * 48) { 0x00FF2288 }
        assertTrue(ArtworkPaletteExtractor.extractPalette(transparent, 48, 48).isMonochrome)
    }

    @Test
    fun dynamicThemeIntensity_full360SweepPassesWcagInBothSubtleAndVibrantAndVibrantIsRicher() {
        for (hueDeg in 0 until 360 step 10) {
            val subtle = CoverThemePaletteGenerator.generateTokensFromHue(
                hueDeg = hueDeg.toFloat(),
                sourceSaturation = 0.85f,
                intensity = DynamicThemeIntensity.SUBTLE,
            )
            val vibrant = CoverThemePaletteGenerator.generateTokensFromHue(
                hueDeg = hueDeg.toFloat(),
                sourceSaturation = 0.85f,
                intensity = DynamicThemeIntensity.VIBRANT,
            )

            assertEquals(DynamicThemeIntensity.SUBTLE, subtle.intensity)
            assertEquals(DynamicThemeIntensity.VIBRANT, vibrant.intensity)
            assertStrictWcagAndStudioBounds("SUBTLE hue=$hueDeg", subtle)
            assertStrictWcagAndStudioBounds("VIBRANT hue=$hueDeg", vibrant)

            val subtlePrimaryC = OklchColorMath.argbToOklch(subtle.primaryArgb).c
            val vibrantPrimaryC = OklchColorMath.argbToOklch(vibrant.primaryArgb).c
            assertTrue(
                "VIBRANT chroma ($vibrantPrimaryC) should be >= SUBTLE chroma ($subtlePrimaryC) at hue=$hueDeg",
                vibrantPrimaryC >= subtlePrimaryC - 0.005f,
            )
        }
    }

    @Test
    fun artworkPaletteExtractor_isDeterministicAndFastOn48x48MultiColorImage() {
        val pixels = IntArray(48 * 48) { idx ->
            val x = idx % 48
            val y = idx / 48
            when {
                x < 16 -> 0xFF1D4ED8.toInt()
                x < 32 && y < 24 -> 0xFFE86F2D.toInt()
                x < 32 -> 0xFF129A74.toInt()
                else -> 0xFF9333EA.toInt()
            }
        }

        // Warm up JIT cleanly before timing.
        val baseline = ArtworkPaletteExtractor.extractPalette(pixels, 48, 48)
        repeat(20) {
            assertEquals(baseline, ArtworkPaletteExtractor.extractPalette(pixels, 48, 48))
        }

        val iterations = 50
        val startNs = System.nanoTime()
        repeat(iterations) {
            val current = ArtworkPaletteExtractor.extractPalette(pixels, 48, 48)
            assertEquals("OKLab K-Means++ must be 100% deterministic across runs", baseline, current)
        }
        val avgMs = (System.nanoTime() - startNs) / 1_000_000.0 / iterations
        assertTrue("Expected < 5.0 ms average per 48x48 image (target < 2 ms), got ${"%.3f".format(avgMs)} ms", avgMs < 5.0)
    }
}
