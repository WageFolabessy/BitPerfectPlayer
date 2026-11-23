package com.cangkir.bitperfectplayer.ui

import kotlin.math.abs

/**
 * Pure-Kotlin state and navigation policy for:
 * 1. The outer 3-page root `HorizontalPager` (`[Content] <-> [Now Playing] <-> [Settings]`), and
 * 2. The inner 3-page top-level `HorizontalPager` (`[Library] <-> [Playlists] <-> [Settings]`) inside [PAGE_CONTENT],
 * kept free of `android.*` and `androidx.*` imports for deterministic JVM unit testing.
 */
object RootPagerPolicy {
    const val PAGE_CONTENT: Int = 0
    const val PAGE_NOW_PLAYING: Int = 1
    const val PAGE_SETTINGS: Int = 2
    const val PAGE_COUNT: Int = 3

    const val TOP_LEVEL_PAGE_LIBRARY: Int = 0
    const val TOP_LEVEL_PAGE_PLAYLISTS: Int = 1
    const val TOP_LEVEL_PAGE_SETTINGS: Int = 2
    const val TOP_LEVEL_PAGE_COUNT: Int = 3

    /**
     * Enables outer horizontal swiping only when [hasNowPlayingPage] is `true` AND either a drag is
     * already in flight (never abort past the 50% midpoint) OR settled on [PAGE_NOW_PLAYING] /
     * [PAGE_SETTINGS] (`false` on [PAGE_CONTENT] so inner top-level & Library pagers own swipes).
     */
    fun isRootPagerScrollEnabled(hasNowPlayingPage: Boolean, settledPage: Int, isScrollInProgress: Boolean): Boolean {
        if (!hasNowPlayingPage) return false
        if (isScrollInProgress) return true
        return settledPage == PAGE_NOW_PLAYING || settledPage == PAGE_SETTINGS
    }

    /**
     * Resolves System Back / TopAppBar Back destination page for the outer pager, or `null` when
     * settled on [PAGE_CONTENT].
     */
    fun resolveBackTargetPage(currentPage: Int): Int? = when (currentPage) {
        PAGE_SETTINGS -> PAGE_NOW_PLAYING
        PAGE_NOW_PLAYING -> PAGE_CONTENT
        else -> null
    }

    /**
     * Enables horizontal swiping across `[Library] <-> [Playlists] <-> [Settings]` whenever a drag
     * is already in progress or no inner detail view (Album, Artist, Folder, Playlist, Most Played)
     * is open.
     */
    fun isTopLevelTabPagerScrollEnabled(isDetailViewOpen: Boolean, isScrollInProgress: Boolean): Boolean =
        isScrollInProgress || !isDetailViewOpen

    /**
     * Returns the immediately highlighted top-level tab index, honoring an in-flight [pendingTapPage]
     * so non-adjacent bottom-nav taps (`Library <-> Settings`) never flicker through `Playlists`.
     */
    fun resolveSelectedTopLevelPage(currentPage: Int, pendingTapPage: Int?): Int =
        (pendingTapPage ?: currentPage).coerceIn(0, TOP_LEVEL_PAGE_COUNT - 1)

    /**
     * Returns `true` for non-adjacent top-level jumps (`Library <-> Settings`) so the pager snaps
     * cleanly without sliding an intermediate `Playlists` screen past the viewport.
     */
    fun shouldInstantJumpTopLevelTab(fromPage: Int, toPage: Int): Boolean = abs(toPage - fromPage) > 1

    /**
     * Resolves System Back target when settled on the top-level `Settings` tab (`PAGE_CONTENT`),
     * returning to [lastNonSettingsPage] (`Library` or `Playlists`), or `null` otherwise.
     */
    fun resolveTopLevelBackTargetPage(currentPage: Int, lastNonSettingsPage: Int): Int? = if (currentPage == TOP_LEVEL_PAGE_SETTINGS) {
        lastNonSettingsPage.coerceIn(TOP_LEVEL_PAGE_LIBRARY, TOP_LEVEL_PAGE_PLAYLISTS)
    } else {
        null
    }

    /**
     * Returns `true` strictly when the main pager shell is the active NavHost destination AND
     * [PAGE_NOW_PLAYING] is currently visible (used to gate the read-only spectrum tap & vinyl spin).
     */
    fun isNowPlayingPageForeground(isMainShellActive: Boolean, currentPage: Int): Boolean =
        isMainShellActive && currentPage == PAGE_NOW_PLAYING
}
