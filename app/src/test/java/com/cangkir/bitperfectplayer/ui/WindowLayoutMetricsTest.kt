package com.cangkir.bitperfectplayer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowLayoutMetricsTest {

    @Test
    fun `phone portrait 392x872 uses bottom nav, single-pane Now Playing, and full 316dp disc container`() {
        val layout = WindowLayoutMetrics.classify(widthDp = 392, heightDp = 872)

        assertEquals(WindowWidthSizeClass.COMPACT, layout.widthSizeClass)
        assertFalse(layout.useNavigationRail)
        assertFalse(layout.useTwoPaneNowPlaying)
        assertNull(layout.contentMaxWidthDp)

        val containerDp = WindowLayoutMetrics.computeNowPlayingDiscContainerDp(
            availableWidthDp = 392,
            availableHeightDp = 872,
            isTwoPane = layout.useTwoPaneNowPlaying,
        )
        assertEquals(316, containerDp)
        assertEquals(240, WindowLayoutMetrics.computeVinylDiscDiameterDp(containerDp, spectrumEnabled = true))
        assertEquals(272, WindowLayoutMetrics.computeVinylDiscDiameterDp(containerDp, spectrumEnabled = false))
        assertEquals(272, WindowLayoutMetrics.computeSquareArtworkSizeDp(containerDp))
    }

    @Test
    fun `phone landscape 872x392 enables NavigationRail, 2-pane Now Playing, and scales disc to prevent vertical clip`() {
        val layout = WindowLayoutMetrics.classify(widthDp = 872, heightDp = 392)

        assertEquals(WindowWidthSizeClass.MEDIUM, layout.widthSizeClass)
        assertTrue(layout.useNavigationRail)
        assertTrue(layout.useTwoPaneNowPlaying)
        assertNull(layout.contentMaxWidthDp)

        val containerDp = WindowLayoutMetrics.computeNowPlayingDiscContainerDp(
            availableWidthDp = 872,
            availableHeightDp = 392,
            isTwoPane = layout.useTwoPaneNowPlaying,
        )
        assertTrue("Expected disc container scaled down in 392dp landscape, got $containerDp", containerDp in 220..305)
        // Radial spectrum 38dp headroom on all sides (76dp total diameter difference) is preserved
        assertEquals(
            76,
            containerDp - WindowLayoutMetrics.computeVinylDiscDiameterDp(containerDp, spectrumEnabled = true),
        )
    }

    @Test
    fun `foldable and tablet viewports 600x800, 840x480, and 1280x800 classify accurately`() {
        // 600x800 portrait tablet / unfolded inner display
        val mediumPortrait = WindowLayoutMetrics.classify(widthDp = 600, heightDp = 800)
        assertEquals(WindowWidthSizeClass.MEDIUM, mediumPortrait.widthSizeClass)
        assertTrue(mediumPortrait.useNavigationRail)
        assertFalse(mediumPortrait.useTwoPaneNowPlaying)
        assertNull(mediumPortrait.contentMaxWidthDp)
        assertEquals(
            316,
            WindowLayoutMetrics.computeNowPlayingDiscContainerDp(600, 800, isTwoPane = false),
        )

        // 840x480 landscape foldable / medium tablet
        val mediumLandscape = WindowLayoutMetrics.classify(widthDp = 840, heightDp = 480)
        assertEquals(WindowWidthSizeClass.MEDIUM, mediumLandscape.widthSizeClass)
        assertTrue(mediumLandscape.useNavigationRail)
        assertTrue(mediumLandscape.useTwoPaneNowPlaying)
        assertNull(mediumLandscape.contentMaxWidthDp)
        assertEquals(
            316,
            WindowLayoutMetrics.computeNowPlayingDiscContainerDp(840, 480, isTwoPane = true),
        )

        // 1280x800 expanded tablet
        val expandedTablet = WindowLayoutMetrics.classify(widthDp = 1280, heightDp = 800)
        assertEquals(WindowWidthSizeClass.EXPANDED, expandedTablet.widthSizeClass)
        assertTrue(expandedTablet.useNavigationRail)
        assertTrue(expandedTablet.useTwoPaneNowPlaying)
        assertEquals(840, expandedTablet.contentMaxWidthDp)
        assertEquals(
            316,
            WindowLayoutMetrics.computeNowPlayingDiscContainerDp(1280, 800, isTwoPane = true),
        )
    }

    @Test
    fun `split-screen 420x420 stays single-pane with compact bottom nav and scaled disc container`() {
        val splitScreen = WindowLayoutMetrics.classify(widthDp = 420, heightDp = 420)

        assertEquals(WindowWidthSizeClass.COMPACT, splitScreen.widthSizeClass)
        assertFalse(splitScreen.useNavigationRail)
        assertFalse(splitScreen.useTwoPaneNowPlaying)
        assertNull(splitScreen.contentMaxWidthDp)

        val containerDp = WindowLayoutMetrics.computeNowPlayingDiscContainerDp(
            availableWidthDp = 420,
            availableHeightDp = 420,
            isTwoPane = false,
        )
        assertTrue("Expected compact split-screen container in 220..250, got $containerDp", containerDp in 220..250)
    }
}
