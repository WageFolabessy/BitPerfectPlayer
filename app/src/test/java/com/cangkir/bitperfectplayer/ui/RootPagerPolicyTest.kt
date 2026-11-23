package com.cangkir.bitperfectplayer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RootPagerPolicyTest {

    @Test
    fun isRootPagerScrollEnabled_gatesContentSettingsAndInFlightDragsCorrectly() {
        assertFalse(
            RootPagerPolicy.isRootPagerScrollEnabled(
                hasNowPlayingPage = false,
                settledPage = RootPagerPolicy.PAGE_NOW_PLAYING,
                isScrollInProgress = false,
            ),
        )
        assertFalse(
            RootPagerPolicy.isRootPagerScrollEnabled(
                hasNowPlayingPage = true,
                settledPage = RootPagerPolicy.PAGE_CONTENT,
                isScrollInProgress = false,
            ),
        )
        assertTrue(
            RootPagerPolicy.isRootPagerScrollEnabled(
                hasNowPlayingPage = true,
                settledPage = RootPagerPolicy.PAGE_CONTENT,
                isScrollInProgress = true,
            ),
        )
        assertTrue(
            RootPagerPolicy.isRootPagerScrollEnabled(
                hasNowPlayingPage = true,
                settledPage = RootPagerPolicy.PAGE_NOW_PLAYING,
                isScrollInProgress = false,
            ),
        )
        assertTrue(
            RootPagerPolicy.isRootPagerScrollEnabled(
                hasNowPlayingPage = true,
                settledPage = RootPagerPolicy.PAGE_SETTINGS,
                isScrollInProgress = false,
            ),
        )
    }

    @Test
    fun topLevelTabPagerPolicy_locksOnlyInDetailViewsAndInstantJumpsNonAdjacentTabs() {
        assertTrue(RootPagerPolicy.isTopLevelTabPagerScrollEnabled(isDetailViewOpen = false, isScrollInProgress = false))
        assertFalse(RootPagerPolicy.isTopLevelTabPagerScrollEnabled(isDetailViewOpen = true, isScrollInProgress = false))
        assertTrue(RootPagerPolicy.isTopLevelTabPagerScrollEnabled(isDetailViewOpen = true, isScrollInProgress = true))

        assertTrue(
            RootPagerPolicy.shouldInstantJumpTopLevelTab(
                fromPage = RootPagerPolicy.TOP_LEVEL_PAGE_LIBRARY,
                toPage = RootPagerPolicy.TOP_LEVEL_PAGE_SETTINGS,
            ),
        )
        assertTrue(
            RootPagerPolicy.shouldInstantJumpTopLevelTab(
                fromPage = RootPagerPolicy.TOP_LEVEL_PAGE_SETTINGS,
                toPage = RootPagerPolicy.TOP_LEVEL_PAGE_LIBRARY,
            ),
        )
        assertFalse(
            RootPagerPolicy.shouldInstantJumpTopLevelTab(
                fromPage = RootPagerPolicy.TOP_LEVEL_PAGE_LIBRARY,
                toPage = RootPagerPolicy.TOP_LEVEL_PAGE_PLAYLISTS,
            ),
        )

        assertEquals(
            RootPagerPolicy.TOP_LEVEL_PAGE_SETTINGS,
            RootPagerPolicy.resolveSelectedTopLevelPage(
                currentPage = RootPagerPolicy.TOP_LEVEL_PAGE_LIBRARY,
                pendingTapPage = RootPagerPolicy.TOP_LEVEL_PAGE_SETTINGS,
            ),
        )
        assertEquals(
            RootPagerPolicy.TOP_LEVEL_PAGE_LIBRARY,
            RootPagerPolicy.resolveTopLevelBackTargetPage(
                currentPage = RootPagerPolicy.TOP_LEVEL_PAGE_SETTINGS,
                lastNonSettingsPage = RootPagerPolicy.TOP_LEVEL_PAGE_LIBRARY,
            ),
        )
        assertNull(
            RootPagerPolicy.resolveTopLevelBackTargetPage(
                currentPage = RootPagerPolicy.TOP_LEVEL_PAGE_PLAYLISTS,
                lastNonSettingsPage = RootPagerPolicy.TOP_LEVEL_PAGE_LIBRARY,
            ),
        )
    }

    @Test
    fun resolveBackTargetPageAndForegroundGate_matchSpatialPagerTopology() {
        assertNull(RootPagerPolicy.resolveBackTargetPage(RootPagerPolicy.PAGE_CONTENT))
        assertEquals(RootPagerPolicy.PAGE_CONTENT, RootPagerPolicy.resolveBackTargetPage(RootPagerPolicy.PAGE_NOW_PLAYING))
        assertEquals(RootPagerPolicy.PAGE_NOW_PLAYING, RootPagerPolicy.resolveBackTargetPage(RootPagerPolicy.PAGE_SETTINGS))

        assertTrue(RootPagerPolicy.isNowPlayingPageForeground(isMainShellActive = true, currentPage = RootPagerPolicy.PAGE_NOW_PLAYING))
        assertFalse(RootPagerPolicy.isNowPlayingPageForeground(isMainShellActive = false, currentPage = RootPagerPolicy.PAGE_NOW_PLAYING))
        assertFalse(RootPagerPolicy.isNowPlayingPageForeground(isMainShellActive = true, currentPage = RootPagerPolicy.PAGE_CONTENT))
        assertFalse(RootPagerPolicy.isNowPlayingPageForeground(isMainShellActive = true, currentPage = RootPagerPolicy.PAGE_SETTINGS))
    }
}
