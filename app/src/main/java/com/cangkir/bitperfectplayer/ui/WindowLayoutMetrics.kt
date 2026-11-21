package com.cangkir.bitperfectplayer.ui

enum class WindowWidthSizeClass {
    COMPACT,
    MEDIUM,
    EXPANDED,
}

data class WindowLayoutClass(
    val widthDp: Int,
    val heightDp: Int,
    val widthSizeClass: WindowWidthSizeClass,
    val useNavigationRail: Boolean,
    val useTwoPaneNowPlaying: Boolean,
    val contentMaxWidthDp: Int?,
)

/**
 * Pure-Kotlin adaptive layout classifier and dimension calculator for Android 17 (`targetSdk = 37`)
 * phones, foldables, tablets, and split-screen / multi-window viewports (`0` `android.*` imports).
 */
object WindowLayoutMetrics {
    const val NAVIGATION_RAIL_MIN_WIDTH_DP = 600
    const val TABLET_TWO_PANE_MIN_WIDTH_DP = 720
    const val EXPANDED_CONTENT_TRIGGER_WIDTH_DP = 900
    const val EXPANDED_CONTENT_MAX_WIDTH_DP = 840

    const val MIN_DISC_CONTAINER_DP = 220
    const val MAX_DISC_CONTAINER_DP = 316
    private const val RADIAL_SPECTRUM_HEADROOM_DIAMETER_DP = 76 // 38 dp radial headroom on all sides
    private const val STANDALONE_ARTWORK_MARGIN_DP = 44 // 316 - 44 = 272 dp default hero artwork

    fun classify(widthDp: Int, heightDp: Int): WindowLayoutClass {
        val safeWidth = widthDp.coerceAtLeast(1)
        val safeHeight = heightDp.coerceAtLeast(1)
        val widthClass = when {
            safeWidth < NAVIGATION_RAIL_MIN_WIDTH_DP -> WindowWidthSizeClass.COMPACT
            safeWidth < EXPANDED_CONTENT_TRIGGER_WIDTH_DP -> WindowWidthSizeClass.MEDIUM
            else -> WindowWidthSizeClass.EXPANDED
        }
        return WindowLayoutClass(
            widthDp = safeWidth,
            heightDp = safeHeight,
            widthSizeClass = widthClass,
            useNavigationRail = useNavigationRail(safeWidth),
            useTwoPaneNowPlaying = useTwoPaneNowPlaying(safeWidth, safeHeight),
            contentMaxWidthDp = contentMaxWidthDp(safeWidth),
        )
    }

    fun useNavigationRail(widthDp: Int): Boolean = widthDp >= NAVIGATION_RAIL_MIN_WIDTH_DP

    fun useTwoPaneNowPlaying(widthDp: Int, heightDp: Int): Boolean = (widthDp >= NAVIGATION_RAIL_MIN_WIDTH_DP && widthDp > heightDp) ||
        widthDp >= TABLET_TWO_PANE_MIN_WIDTH_DP

    fun contentMaxWidthDp(widthDp: Int): Int? = if (widthDp >= EXPANDED_CONTENT_TRIGGER_WIDTH_DP) {
        EXPANDED_CONTENT_MAX_WIDTH_DP
    } else {
        null
    }

    /**
     * Smoothly scales the `NowPlayingScreen` Vinyl + Radial Spectrum container (`220..316 dp`)
     * so portrait phones keep the exact `316 dp` centerpiece while landscape phones and compact
     * split-screen windows never clip vertically or overlap transport controls.
     */
    fun computeNowPlayingDiscContainerDp(availableWidthDp: Int, availableHeightDp: Int, isTwoPane: Boolean): Int {
        val safeWidth = availableWidthDp.coerceAtLeast(1)
        val safeHeight = availableHeightDp.coerceAtLeast(1)
        val rawBudget = if (isTwoPane) {
            val paneWidthBudget = ((safeWidth - 64) * 0.46f).toInt()
            val paneHeightBudget = (safeHeight * 0.75f).toInt()
            minOf(paneWidthBudget, paneHeightBudget)
        } else {
            val singleColWidthBudget = safeWidth - 48
            val singleColHeightBudget = (safeHeight * 0.56f).toInt()
            minOf(singleColWidthBudget, singleColHeightBudget)
        }
        return rawBudget.coerceIn(MIN_DISC_CONTAINER_DP, MAX_DISC_CONTAINER_DP)
    }

    fun computeVinylDiscDiameterDp(containerDp: Int, spectrumEnabled: Boolean): Int {
        val clampedContainer = containerDp.coerceIn(MIN_DISC_CONTAINER_DP, MAX_DISC_CONTAINER_DP)
        return if (spectrumEnabled) {
            (clampedContainer - RADIAL_SPECTRUM_HEADROOM_DIAMETER_DP).coerceIn(144, 240)
        } else {
            (clampedContainer - STANDALONE_ARTWORK_MARGIN_DP).coerceIn(176, 272)
        }
    }

    fun computeSquareArtworkSizeDp(containerDp: Int): Int {
        val clampedContainer = containerDp.coerceIn(MIN_DISC_CONTAINER_DP, MAX_DISC_CONTAINER_DP)
        return (clampedContainer - STANDALONE_ARTWORK_MARGIN_DP).coerceIn(176, 272)
    }
}
