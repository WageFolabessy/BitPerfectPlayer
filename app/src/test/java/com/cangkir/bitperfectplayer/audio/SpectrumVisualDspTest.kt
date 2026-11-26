package com.cangkir.bitperfectplayer.audio

import com.cangkir.bitperfectplayer.data.SpectrumColorStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectrumVisualDspTest {

    private fun hueOf(argb: Int): Float {
        val hsl = FloatArray(3)
        AlbumPaletteExtractor.rgbToHsl(
            r = (argb ushr 16) and 0xFF,
            g = (argb ushr 8) and 0xFF,
            b = argb and 0xFF,
            outHsl = hsl,
        )
        return hsl[0]
    }

    private fun hslOf(argb: Int): FloatArray {
        val hsl = FloatArray(3)
        AlbumPaletteExtractor.rgbToHsl(
            r = (argb ushr 16) and 0xFF,
            g = (argb ushr 8) and 0xFF,
            b = argb and 0xFF,
            outHsl = hsl,
        )
        return hsl
    }

    @Test
    fun albumPaletteExtractor_extractsThreeDistinctVibrantHuesFromMultiColorArtwork() {
        val width = 60
        val height = 60
        val pixels = IntArray(width * height) { idx ->
            val x = idx % width
            when {
                x < 20 -> 0xFFD93829.toInt() // vivid red
                x < 40 -> 0xFF22B86B.toInt() // vivid green
                else -> 0xFF2E6CE6.toInt() // vivid blue
            }
        }

        val palette = AlbumPaletteExtractor.extractAccentPalette(pixels, width, height)
        assertEquals(3, palette.size)

        val h0 = hueOf(palette[0])
        val h1 = hueOf(palette[1])
        val h2 = hueOf(palette[2])
        assertTrue(AlbumPaletteExtractor.circularHueDistanceDeg(h0, h1) >= 36f)
        assertTrue(AlbumPaletteExtractor.circularHueDistanceDeg(h1, h2) >= 36f)
        assertTrue(AlbumPaletteExtractor.circularHueDistanceDeg(h0, h2) >= 36f)

        // All extracted colors must stay within OLED-readable vibrancy ranges.
        for (color in palette) {
            val hsl = hslOf(color)
            assertTrue("Expected S in 0.50..0.92, got ${hsl[1]}", hsl[1] in 0.50f..0.92f)
            assertTrue("Expected L in 0.48..0.76, got ${hsl[2]}", hsl[2] in 0.48f..0.76f)
        }
    }

    @Test
    fun albumPaletteExtractor_fallsBackToStudioPaletteOnGrayscaleAndEmptyInputs() {
        val empty = AlbumPaletteExtractor.extractAccentPalette(IntArray(0), 0, 0)
        assertTrue(empty.contentEquals(AlbumPaletteExtractor.FALLBACK_STUDIO_PALETTE))

        // Black, gray, and white checkerboard artwork -> strictly falls back to Monochrome Studio triad.
        val grayscale = IntArray(40 * 40) { idx ->
            when (idx % 3) {
                0 -> 0xFF0A0A0A.toInt()
                1 -> 0xFF808080.toInt()
                else -> 0xFFF5F5F5.toInt()
            }
        }
        val fromGray = AlbumPaletteExtractor.extractAccentPalette(grayscale, 40, 40)
        assertTrue(fromGray.contentEquals(AlbumPaletteExtractor.FALLBACK_STUDIO_PALETTE))
    }

    @Test
    fun albumPaletteExtractor_derivesHarmoniousSeparatedTriadOnMonochromaticCover() {
        // Red & black monochromatic album cover -> derives 3 well-separated OLED accents.
        val pixels = IntArray(40 * 40) { idx ->
            if (idx % 2 == 0) 0xFFCC2222.toInt() else 0xFF0E0E10.toInt()
        }
        val palette = AlbumPaletteExtractor.extractAccentPalette(pixels, 40, 40)
        assertEquals(3, palette.size)
        assertTrue(AlbumPaletteExtractor.circularHueDistanceDeg(hueOf(palette[0]), hueOf(palette[1])) >= 36f)
        assertTrue(AlbumPaletteExtractor.circularHueDistanceDeg(hueOf(palette[1]), hueOf(palette[2])) >= 36f)
    }

    @Test
    fun spectrumColorMapper_producesExpectedHuesForStudioPrismAndAlbumStyles() {
        val customAlbum = intArrayOf(0xFFE65C4F.toInt(), 0xFF47C98F.toInt(), 0xFF8A75E8.toInt())

        // STUDIO keeps consistent Monochrome Studio silver-ivory across bass, mid, and treble.
        val studioBass = SpectrumColorMapper.resolveBarColorArgb(SpectrumColorStyle.STUDIO, 0f, 0.65f, customAlbum)
        val studioMid = SpectrumColorMapper.resolveBarColorArgb(SpectrumColorStyle.STUDIO, 0.5f, 0.65f, customAlbum)
        val studioTreble = SpectrumColorMapper.resolveBarColorArgb(SpectrumColorStyle.STUDIO, 1f, 0.65f, customAlbum)
        assertEquals(studioBass, studioMid)
        assertEquals(studioMid, studioTreble)

        // PRISM produces distinct hues across bass (warm coral), mid (emerald-aqua), treble (studio violet).
        val prismBass = SpectrumColorMapper.resolveBarColorArgb(SpectrumColorStyle.PRISM, 0f, 0.70f, customAlbum)
        val prismMid = SpectrumColorMapper.resolveBarColorArgb(SpectrumColorStyle.PRISM, 0.5f, 0.70f, customAlbum)
        val prismTreble = SpectrumColorMapper.resolveBarColorArgb(SpectrumColorStyle.PRISM, 1f, 0.70f, customAlbum)
        assertNotEquals(prismBass, prismMid)
        assertNotEquals(prismMid, prismTreble)
        assertTrue(AlbumPaletteExtractor.circularHueDistanceDeg(hueOf(prismBass), hueOf(prismMid)) >= 60f)
        assertTrue(AlbumPaletteExtractor.circularHueDistanceDeg(hueOf(prismMid), hueOf(prismTreble)) >= 60f)

        // ALBUM_ADAPTIVE uses the extracted album palette stops.
        val albumBass = SpectrumColorMapper.resolveAccentColorArgb(SpectrumColorStyle.ALBUM_ADAPTIVE, 0f, customAlbum)
        val albumMid = SpectrumColorMapper.resolveAccentColorArgb(SpectrumColorStyle.ALBUM_ADAPTIVE, 0.5f, customAlbum)
        val albumTreble = SpectrumColorMapper.resolveAccentColorArgb(SpectrumColorStyle.ALBUM_ADAPTIVE, 1f, customAlbum)
        assertEquals(customAlbum[0], albumBass)
        assertEquals(customAlbum[1], albumMid)
        assertEquals(customAlbum[2], albumTreble)
    }

    @Test
    fun kineticSparkPool_spawnsOnTransientRespectsCooldownAndCapAndDecaysToZeroOnPause() {
        val pool = KineticSparkPool()
        val palette = AlbumPaletteExtractor.FALLBACK_STUDIO_PALETTE

        // Quiet baseline -> 0 sparks spawned.
        val quiet = FloatArray(SpectrumAnalyzerDsp.BAND_COUNT) { 0.05f }
        pool.emitFromBands(quiet, 0.016f, SpectrumColorStyle.PRISM, palette)
        assertEquals(0, pool.activeCount)

        // Sharp transient spike across 4 bass/mid bands -> spawns sparks immediately.
        val spike = FloatArray(SpectrumAnalyzerDsp.BAND_COUNT) { idx ->
            if (idx in listOf(2, 5, 14, 24)) 0.85f else 0.05f
        }
        pool.emitFromBands(spike, 0.016f, SpectrumColorStyle.PRISM, palette)
        val countAfterFirstSpike = pool.activeCount
        assertTrue("Expected sparks spawned on transient spike, got $countAfterFirstSpike", countAfterFirstSpike in 4..8)

        // Immediate repeat spike within 85 ms cooldown (even after dipping) must respect cooldown!
        pool.emitFromBands(quiet, 0.016f, SpectrumColorStyle.PRISM, palette)
        pool.emitFromBands(spike, 0.016f, SpectrumColorStyle.PRISM, palette)
        assertEquals("Cooldown must block rapid re-spawn on same bands", countAfterFirstSpike, pool.activeCount)

        // Heavy continuous transients across all 32 bands must NEVER exceed MAX_SPARKS = 64.
        val allHigh = FloatArray(SpectrumAnalyzerDsp.BAND_COUNT) { 0.95f }
        repeat(6) {
            pool.emitFromBands(quiet, 0.095f, SpectrumColorStyle.PRISM, palette)
            pool.emitFromBands(allHigh, 0.016f, SpectrumColorStyle.PRISM, palette)
            assertTrue("Active sparks must never exceed MAX_SPARKS=64", pool.activeCount <= KineticSparkPool.MAX_SPARKS)
        }
        assertTrue("Expected pool near capacity under heavy transients", pool.activeCount >= 32)

        // Advance physics during pause/silence -> all particles advance smoothly with finite positions and expire to 0.
        var remaining = pool.activeCount
        repeat(50) {
            // 50 * 16 ms = 800 ms (> max lifetime 550 ms)
            remaining = pool.step(0.016f)
            for (slot in 0 until KineticSparkPool.MAX_SPARKS) {
                if (pool.active[slot]) {
                    assertTrue(pool.distanceDp[slot].isFinite())
                    assertTrue(pool.tangentOffsetDp[slot].isFinite())
                    assertTrue(pool.radialVelDp[slot].isFinite())
                    assertTrue(pool.distanceDp[slot] >= 0f)
                }
            }
        }
        assertEquals("All sparks must fully expire to 0 after max lifetime", 0, remaining)
        assertEquals(0, pool.activeCount)
    }

    @Test
    fun albumAdaptive4StopPalette_mapsBassMidTrebleAndBlendsPeaksTowardHighlight() {
        val bassStop = 0xFFE65C4F.toInt() // warm coral bass
        val midStop = 0xFF3E8EF7.toInt() // cobalt primary mid
        val trebleStop = 0xFFE8B44A.toInt() // warm gold secondary treble
        val highlightPeak = 0xFFFFF4C8.toInt() // bright gold-ivory peak
        val palette4 = intArrayOf(bassStop, midStop, trebleStop, highlightPeak)

        // Exact 3-band frequency stop mapping (ignoring index 3 highlightPeak during base interpolation).
        assertEquals(bassStop, SpectrumColorMapper.resolveAccentColorArgb(SpectrumColorStyle.ALBUM_ADAPTIVE, 0.0f, palette4))
        assertEquals(midStop, SpectrumColorMapper.resolveAccentColorArgb(SpectrumColorStyle.ALBUM_ADAPTIVE, 0.5f, palette4))
        assertEquals(trebleStop, SpectrumColorMapper.resolveAccentColorArgb(SpectrumColorStyle.ALBUM_ADAPTIVE, 1.0f, palette4))

        // High-amplitude treble bar (> 0.72f) blends cleanly toward highlightPeak (brighter than mid-energy treble).
        val midEnergyTreble = SpectrumColorMapper.resolveBarColorArgb(SpectrumColorStyle.ALBUM_ADAPTIVE, 1.0f, 0.65f, palette4)
        val peakEnergyTreble = SpectrumColorMapper.resolveBarColorArgb(SpectrumColorStyle.ALBUM_ADAPTIVE, 1.0f, 1.0f, palette4)
        assertNotEquals(midEnergyTreble, peakEnergyTreble)
        assertTrue(
            "Peak treble bar should lift luminance toward highlightPeak",
            hslOf(peakEnergyTreble)[2] > hslOf(midEnergyTreble)[2],
        )
    }

    @Test
    fun kineticSparkPool_albumModeSparksInheritExactBandColorAndBlendTowardHighlightPeak() {
        val bassStop = 0xFFE6403A.toInt() // vivid red bass
        val midStop = 0xFF22B86B.toInt() // vivid emerald mid
        val trebleStop = 0xFF3B82F6.toInt() // vivid cobalt treble
        val highlightPeak = 0xFFFDF4C6.toInt() // luminous ivory-gold highlight
        val palette4 = intArrayOf(bassStop, midStop, trebleStop, highlightPeak)

        val pool = KineticSparkPool()
        val bands = FloatArray(SpectrumAnalyzerDsp.BAND_COUNT)

        fun firstActiveSparkColor(): Int {
            val slot = (0 until KineticSparkPool.MAX_SPARKS).first { pool.active[it] }
            return pool.colorArgb[slot]
        }

        // Launch a bass transient (bin 0, normPos = 0.0)
        bands[0] = 0.45f
        pool.emitFromBands(bands, 0.016f, SpectrumColorStyle.ALBUM_ADAPTIVE, palette4)
        val bassSparkColor = firstActiveSparkColor()
        assertTrue(
            "Bass spark hue should match spectrumLow",
            AlbumPaletteExtractor.circularHueDistanceDeg(hueOf(bassSparkColor), hueOf(bassStop)) < 15f,
        )

        // Reset and launch a mid transient (bin 15, normPos ~= 0.484)
        pool.clear()
        bands.fill(0f)
        bands[15] = 0.45f
        pool.emitFromBands(bands, 0.016f, SpectrumColorStyle.ALBUM_ADAPTIVE, palette4)
        val midSparkColor = firstActiveSparkColor()
        assertTrue(
            "Mid spark hue should match spectrumMid",
            AlbumPaletteExtractor.circularHueDistanceDeg(hueOf(midSparkColor), hueOf(midStop)) < 18f,
        )

        // Reset and launch a treble transient (bin 31, normPos = 1.0) at moderate vs intense peak energy
        pool.clear()
        bands.fill(0f)
        bands[31] = 0.45f
        pool.emitFromBands(bands, 0.016f, SpectrumColorStyle.ALBUM_ADAPTIVE, palette4)
        val moderateTrebleSpark = firstActiveSparkColor()
        assertTrue(
            "Treble spark hue should match spectrumHigh",
            AlbumPaletteExtractor.circularHueDistanceDeg(hueOf(moderateTrebleSpark), hueOf(trebleStop)) < 15f,
        )

        pool.clear()
        bands.fill(0f)
        bands[31] = 0.98f
        pool.emitFromBands(bands, 0.016f, SpectrumColorStyle.ALBUM_ADAPTIVE, palette4)
        val peakTrebleSpark = firstActiveSparkColor()
        assertNotEquals(moderateTrebleSpark, peakTrebleSpark)
        assertTrue(
            "Intense beat peak spark should blend brighter toward highlightPeak",
            hslOf(peakTrebleSpark)[2] > hslOf(moderateTrebleSpark)[2],
        )

        // Verify all 3 frequency-band spark colors are genuinely distinct from one another.
        assertTrue(AlbumPaletteExtractor.circularHueDistanceDeg(hueOf(bassSparkColor), hueOf(midSparkColor)) >= 45f)
        assertTrue(AlbumPaletteExtractor.circularHueDistanceDeg(hueOf(midSparkColor), hueOf(moderateTrebleSpark)) >= 45f)
    }
}
