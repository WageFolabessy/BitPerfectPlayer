package com.cangkir.bitperfectplayer.ui

/**
 * Pure-Kotlin state and navigation policy for the root 3-page `HorizontalPager`
 * (`[Content (Library / Playlists)] <-> [Now Playing] <-> [Settings]`), kept free of `android.*`
 * and `androidx.*` imports for deterministic JVM unit testing.
 */
object RootPagerPolicy {
    const val PAGE_CONTENT: Int = 0
    const val PAGE_NOW_PLAYING: Int = 1
    const val PAGE_SETTINGS: Int = 2
    const val PAGE_COUNT: Int = 3

    /**
     * Enables user horizontal swiping only when:
     * - [hasNowPlayingPage] is `true`, and
     * - either a scroll/drag/settle is already in flight (never abort mid-gesture at the 50% midpoint),
     *   or the pager is currently settled on [PAGE_NOW_PLAYING],
     *   or on [PAGE_SETTINGS] when opened from Now Playing OR opened from bottom nav with an active track.
     *
     * On [PAGE_CONTENT] (`Library` / `Playlists`), horizontal scrolling stays disabled so
     * `LibraryScreen`'s inner 6-tab `HorizontalPager` owns all horizontal swipes without conflict.
     */
    fun isRootPagerScrollEnabled(
        hasNowPlayingPage: Boolean,
        settledPage: Int,
        isScrollInProgress: Boolean,
        hasActiveTrack: Boolean,
        settingsOpenedFromBottomNav: Boolean,
    ): Boolean {
        if (!hasNowPlayingPage) return false
        if (isScrollInProgress) return true
        return when (settledPage) {
            PAGE_NOW_PLAYING -> true
            PAGE_SETTINGS -> !settingsOpenedFromBottomNav || hasActiveTrack
            else -> false
        }
    }

    /**
     * Resolves the System Back / TopAppBar Back destination page from [currentPage], or `null` when
     * settled on [PAGE_CONTENT] so inner Library Back handlers or normal activity exit apply.
     */
    fun resolveBackTargetPage(currentPage: Int, settingsOpenedFromBottomNav: Boolean): Int? = when (currentPage) {
        PAGE_SETTINGS -> if (settingsOpenedFromBottomNav) PAGE_CONTENT else PAGE_NOW_PLAYING
        PAGE_NOW_PLAYING -> PAGE_CONTENT
        else -> null
    }

    /**
     * Returns `true` strictly when the main pager shell is the active NavHost destination AND
     * [PAGE_NOW_PLAYING] is currently visible (used to gate the read-only spectrum tap & vinyl spin).
     */
    fun isNowPlayingPageForeground(isMainShellActive: Boolean, currentPage: Int): Boolean =
        isMainShellActive && currentPage == PAGE_NOW_PLAYING
}
