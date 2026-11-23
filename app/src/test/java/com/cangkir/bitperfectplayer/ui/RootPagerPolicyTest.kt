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
                hasActiveTrack = true,
                settingsOpenedFromBottomNav = false,
            ),
        )
        assertFalse(
            RootPagerPolicy.isRootPagerScrollEnabled(
                hasNowPlayingPage = true,
                settledPage = RootPagerPolicy.PAGE_CONTENT,
                isScrollInProgress = false,
                hasActiveTrack = true,
                settingsOpenedFromBottomNav = false,
            ),
        )
        assertTrue(
            RootPagerPolicy.isRootPagerScrollEnabled(
                hasNowPlayingPage = true,
                settledPage = RootPagerPolicy.PAGE_CONTENT,
                isScrollInProgress = true,
                hasActiveTrack = true,
                settingsOpenedFromBottomNav = false,
            ),
        )
        assertTrue(
            RootPagerPolicy.isRootPagerScrollEnabled(
                hasNowPlayingPage = true,
                settledPage = RootPagerPolicy.PAGE_NOW_PLAYING,
                isScrollInProgress = false,
                hasActiveTrack = false,
                settingsOpenedFromBottomNav = false,
            ),
        )
        assertFalse(
            RootPagerPolicy.isRootPagerScrollEnabled(
                hasNowPlayingPage = true,
                settledPage = RootPagerPolicy.PAGE_SETTINGS,
                isScrollInProgress = false,
                hasActiveTrack = false,
                settingsOpenedFromBottomNav = true,
            ),
        )
        assertTrue(
            RootPagerPolicy.isRootPagerScrollEnabled(
                hasNowPlayingPage = true,
                settledPage = RootPagerPolicy.PAGE_SETTINGS,
                isScrollInProgress = false,
                hasActiveTrack = true,
                settingsOpenedFromBottomNav = true,
            ),
        )
        assertTrue(
            RootPagerPolicy.isRootPagerScrollEnabled(
                hasNowPlayingPage = true,
                settledPage = RootPagerPolicy.PAGE_SETTINGS,
                isScrollInProgress = false,
                hasActiveTrack = false,
                settingsOpenedFromBottomNav = false,
            ),
        )
    }

    @Test
    fun resolveBackTargetPageAndForegroundGate_matchSpatialPagerTopology() {
        assertNull(RootPagerPolicy.resolveBackTargetPage(RootPagerPolicy.PAGE_CONTENT, settingsOpenedFromBottomNav = false))
        assertEquals(
            RootPagerPolicy.PAGE_CONTENT,
            RootPagerPolicy.resolveBackTargetPage(RootPagerPolicy.PAGE_NOW_PLAYING, settingsOpenedFromBottomNav = false),
        )
        assertEquals(
            RootPagerPolicy.PAGE_NOW_PLAYING,
            RootPagerPolicy.resolveBackTargetPage(RootPagerPolicy.PAGE_SETTINGS, settingsOpenedFromBottomNav = false),
        )
        assertEquals(
            RootPagerPolicy.PAGE_CONTENT,
            RootPagerPolicy.resolveBackTargetPage(RootPagerPolicy.PAGE_SETTINGS, settingsOpenedFromBottomNav = true),
        )

        assertTrue(RootPagerPolicy.isNowPlayingPageForeground(isMainShellActive = true, currentPage = RootPagerPolicy.PAGE_NOW_PLAYING))
        assertFalse(RootPagerPolicy.isNowPlayingPageForeground(isMainShellActive = false, currentPage = RootPagerPolicy.PAGE_NOW_PLAYING))
        assertFalse(RootPagerPolicy.isNowPlayingPageForeground(isMainShellActive = true, currentPage = RootPagerPolicy.PAGE_CONTENT))
        assertFalse(RootPagerPolicy.isNowPlayingPageForeground(isMainShellActive = true, currentPage = RootPagerPolicy.PAGE_SETTINGS))
    }
}
