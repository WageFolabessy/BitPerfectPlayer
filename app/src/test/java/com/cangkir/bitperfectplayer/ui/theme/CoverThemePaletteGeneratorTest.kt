package com.cangkir.bitperfectplayer.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverThemePaletteGeneratorTest {

    private fun assertTokensPassWcagAndDarkStudioBounds(label: String, tokens: DynamicThemeTokens) {
        val primaryOnPrimary = CoverThemePaletteGenerator.contrastRatio(tokens.primaryArgb, tokens.onPrimaryArgb)
        val primaryOnSurface = CoverThemePaletteGenerator.contrastRatio(tokens.primaryArgb, tokens.surfaceArgb)
        val primaryOnVariant = CoverThemePaletteGenerator.contrastRatio(tokens.primaryArgb, tokens.surfaceVariantArgb)
        val secondaryOnVariant = CoverThemePaletteGenerator.contrastRatio(tokens.secondaryArgb, tokens.surfaceVariantArgb)
        val textOnBackground = CoverThemePaletteGenerator.contrastRatio(tokens.onBackgroundArgb, tokens.backgroundArgb)
        val textOnSurface = CoverThemePaletteGenerator.contrastRatio(tokens.onSurfaceArgb, tokens.surfaceArgb)
        val containerText = CoverThemePaletteGenerator.contrastRatio(tokens.onPrimaryContainerArgb, tokens.primaryContainerArgb)
        val bgLum = CoverThemePaletteGenerator.relativeLuminance(tokens.backgroundArgb)

        assertTrue("$label: primary/onPrimary contrast $primaryOnPrimary < 4.5", primaryOnPrimary >= 4.5)
        assertTrue("$label: primary/surface contrast $primaryOnSurface < 4.5", primaryOnSurface >= 4.5)
        assertTrue("$label: primary/surfaceVariant contrast $primaryOnVariant < 4.5", primaryOnVariant >= 4.5)
        assertTrue("$label: secondary/surfaceVariant contrast $secondaryOnVariant < 4.5", secondaryOnVariant >= 4.5)
        assertTrue("$label: onBackground/background contrast $textOnBackground < 7.0", textOnBackground >= 7.0)
        assertTrue("$label: onSurface/surface contrast $textOnSurface < 7.0", textOnSurface >= 7.0)
        assertTrue("$label: onPrimaryContainer/primaryContainer contrast $containerText < 4.5", containerText >= 4.5)
        assertTrue("$label: background luminance $bgLum too bright for dark studio theme", bgLum < 0.05)
    }

    @Test
    fun extractThemeTokens_emptyInvalidTransparentAndGrayscaleInputs_returnNullAndFallbackToMonochrome() {
        assertNull(CoverThemePaletteGenerator.extractThemeTokens(IntArray(0), 0, 0))
        assertNull(CoverThemePaletteGenerator.extractThemeTokens(IntArray(16), -1, 4))
        assertNull(CoverThemePaletteGenerator.extractThemeTokens(IntArray(16), 4, 0))

        val transparentPixels = IntArray(32 * 32) { 0x00FF0000 }
        assertNull(CoverThemePaletteGenerator.extractThemeTokens(transparentPixels, 32, 32))

        val pureBlack = IntArray(32 * 32) { 0xFF000000.toInt() }
        assertNull(CoverThemePaletteGenerator.extractThemeTokens(pureBlack, 32, 32))
        assertEquals(
            CoverThemePaletteGenerator.MONOCHROME_STUDIO_TOKENS,
            CoverThemePaletteGenerator.resolveThemeTokensOrDefault(pureBlack, 32, 32),
        )

        val pureWhite = IntArray(32 * 32) { 0xFFFFFFFF.toInt() }
        assertNull(CoverThemePaletteGenerator.extractThemeTokens(pureWhite, 32, 32))
        assertEquals(
            CoverThemePaletteGenerator.MONOCHROME_STUDIO_TOKENS,
            CoverThemePaletteGenerator.resolveThemeTokensOrDefault(pureWhite, 32, 32),
        )

        val blackAndWhiteCheckerboard = IntArray(32 * 32) { idx ->
            when (idx % 4) {
                0 -> 0xFF050505.toInt()
                1 -> 0xFF555555.toInt()
                2 -> 0xFFA8A8A8.toInt()
                else -> 0xFFF5F5F5.toInt()
            }
        }
        assertNull(CoverThemePaletteGenerator.extractThemeTokens(blackAndWhiteCheckerboard, 32, 32))

        val nearNeutralGray = IntArray(32 * 32) { 0xFF7A7A7E.toInt() }
        assertNull(CoverThemePaletteGenerator.extractThemeTokens(nearNeutralGray, 32, 32))
        assertEquals(
            CoverThemePaletteGenerator.MONOCHROME_STUDIO_TOKENS,
            CoverThemePaletteGenerator.resolveThemeTokensOrDefault(nearNeutralGray, 32, 32),
        )
    }

    @Test
    fun monochromeStudioTokens_satisfiesAllWcagContrastAndDarkCanvasRequirements() {
        assertTokensPassWcagAndDarkStudioBounds(
            label = "MONOCHROME_STUDIO_TOKENS",
            tokens = CoverThemePaletteGenerator.MONOCHROME_STUDIO_TOKENS,
        )
    }

    @Test
    fun extractThemeTokens_extremeChromaticCovers_allPassWcagContrastGuarantees() {
        val extremeCovers = linkedMapOf(
            "Neon Yellow" to IntArray(32 * 32) { 0xFFFFFF00.toInt() },
            "Neon Lime" to IntArray(32 * 32) { 0xFF39FF14.toInt() },
            "Bright Cyan" to IntArray(32 * 32) { 0xFF00FFFF.toInt() },
            "Electric Magenta" to IntArray(32 * 32) { 0xFFFF00FF.toInt() },
            "Deep Crimson Red" to IntArray(32 * 32) { 0xFF7A0A0A.toInt() },
            "Deep Navy Near-Black" to IntArray(32 * 32) { 0xFF081028.toInt() },
            "Deep Emerald Green" to IntArray(32 * 32) { 0xFF0C5C36.toInt() },
            "Pure White With Red Strip" to IntArray(32 * 32) { idx ->
                if (idx % 10 == 0) 0xFFE63946.toInt() else 0xFFFFFFFF.toInt()
            },
        )

        extremeCovers.forEach { (name, pixels) ->
            val tokens = CoverThemePaletteGenerator.extractThemeTokens(pixels, 32, 32)
            assertNotNull("$name should extract a chromatic theme token set", tokens)
            assertTokensPassWcagAndDarkStudioBounds(name, tokens!!)
            // Extreme neon covers must also stay restrained (non-glaring luminance <= 0.65).
            val primaryLum = CoverThemePaletteGenerator.relativeLuminance(tokens.primaryArgb)
            assertTrue("$name primary relative luminance $primaryLum should stay restrained <= 0.65", primaryLum <= 0.65)
        }
    }

    @Test
    fun resolveThemeTokensOrDefault_pureWhiteAndNearBlackCovers_passWcagDarkThemeGuarantees() {
        val pureWhite = CoverThemePaletteGenerator.resolveThemeTokensOrDefault(
            pixels = IntArray(32 * 32) { 0xFFFFFFFF.toInt() },
            width = 32,
            height = 32,
        )
        assertTokensPassWcagAndDarkStudioBounds("Pure White Fallback", pureWhite)

        val nearBlack = CoverThemePaletteGenerator.resolveThemeTokensOrDefault(
            pixels = IntArray(32 * 32) { 0xFF040405.toInt() },
            width = 32,
            height = 32,
        )
        assertTokensPassWcagAndDarkStudioBounds("Near Black Fallback", nearBlack)
    }

    @Test
    fun generateTokensFromHue_full360DegreeSweep_passesAllWcagThresholdsAtHighAndLowSaturation() {
        for (hueDeg in 0 until 360 step 5) {
            for (sat in listOf(0.25f, 0.60f, 1.0f)) {
                val tokens = CoverThemePaletteGenerator.generateTokensFromHue(hueDeg.toFloat(), sat)
                assertTokensPassWcagAndDarkStudioBounds("hue=$hueDeg sat=$sat", tokens)
            }
        }
    }
}
