package com.cangkir.bitperfectplayer.ui.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.cangkir.bitperfectplayer.ui.BitPerfectAdaptiveTopLevelFrame
import com.cangkir.bitperfectplayer.ui.LibraryTab
import com.cangkir.bitperfectplayer.ui.RootPagerPolicy
import com.cangkir.bitperfectplayer.ui.nowplaying.LocalSwipeExclusionBounds
import com.cangkir.bitperfectplayer.ui.nowplaying.NOW_PLAYING_SWIPE_CONTAINER_TEST_TAG
import com.cangkir.bitperfectplayer.ui.nowplaying.NowPlayingSwipeExclusionBounds
import com.cangkir.bitperfectplayer.ui.nowplaying.PageExclusionGuardBox
import com.cangkir.bitperfectplayer.ui.nowplaying.ROOT_HORIZONTAL_PAGER_TEST_TAG
import com.cangkir.bitperfectplayer.ui.nowplaying.SETTINGS_SWIPE_CONTAINER_TEST_TAG
import com.cangkir.bitperfectplayer.ui.nowplaying.SETTINGS_TAB_SWIPE_CONTAINER_TEST_TAG
import com.cangkir.bitperfectplayer.ui.nowplaying.TOP_LEVEL_HORIZONTAL_PAGER_TEST_TAG

@Composable
internal fun RootMainShellPager(
    pagerState: PagerState,
    topLevelPagerState: PagerState,
    selectedTopLevelRoute: String,
    isDetailViewOpen: Boolean,
    hasNowPlayingPage: Boolean,
    initialLibraryTab: LibraryTab,
    isMainShellActive: Boolean,
    snackbarHostState: SnackbarHostState,
    saveableStateHolder: SaveableStateHolder,
    onSelectTopLevelTab: (String) -> Unit,
    onBackToContent: () -> Unit,
    onOpenSettingsFromNowPlaying: () -> Unit,
    onBackToNowPlaying: () -> Unit,
    miniPlayerContent: @Composable () -> Unit,
    libraryContent: @Composable (LibraryTab) -> Unit,
    playlistsContent: @Composable () -> Unit,
    settingsContent: @Composable () -> Unit,
    nowPlayingContent: (@Composable (isPageActive: Boolean, onBack: () -> Unit, onOpenSettings: () -> Unit) -> Unit)?,
    settingsFromNowPlayingContent: (@Composable (onBack: () -> Unit) -> Unit)?,
) {
    val nowPlayingExclusionBounds = remember { NowPlayingSwipeExclusionBounds() }
    val settingsExclusionBounds = remember { NowPlayingSwipeExclusionBounds() }
    val settingsTabExclusionBounds = remember { NowPlayingSwipeExclusionBounds() }

    val scrollEnabled = RootPagerPolicy.isRootPagerScrollEnabled(
        hasNowPlayingPage = hasNowPlayingPage,
        settledPage = pagerState.settledPage,
        isScrollInProgress = pagerState.isScrollInProgress,
    )

    HorizontalPager(
        state = pagerState,
        beyondViewportPageCount = 0,
        userScrollEnabled = scrollEnabled,
        modifier = Modifier
            .fillMaxSize()
            .testTag(ROOT_HORIZONTAL_PAGER_TEST_TAG),
    ) { page ->
        when (page) {
            RootPagerPolicy.PAGE_CONTENT -> {
                val topLevelSwipeEnabled = RootPagerPolicy.isTopLevelTabPagerScrollEnabled(
                    isDetailViewOpen = isDetailViewOpen,
                    isScrollInProgress = topLevelPagerState.isScrollInProgress,
                )
                BitPerfectAdaptiveTopLevelFrame(
                    isTopLevelRoute = true,
                    currentRoute = selectedTopLevelRoute,
                    snackbarHostState = snackbarHostState,
                    onSelectTab = onSelectTopLevelTab,
                    miniPlayerContent = miniPlayerContent,
                ) { contentModifier ->
                    HorizontalPager(
                        state = topLevelPagerState,
                        beyondViewportPageCount = 0,
                        userScrollEnabled = topLevelSwipeEnabled,
                        modifier = contentModifier.testTag(TOP_LEVEL_HORIZONTAL_PAGER_TEST_TAG),
                    ) { tabPage ->
                        when (tabPage) {
                            RootPagerPolicy.TOP_LEVEL_PAGE_LIBRARY -> {
                                saveableStateHolder.SaveableStateProvider(AppNavRoutes.LIBRARY) {
                                    libraryContent(initialLibraryTab)
                                }
                            }
                            RootPagerPolicy.TOP_LEVEL_PAGE_PLAYLISTS -> {
                                saveableStateHolder.SaveableStateProvider(AppNavRoutes.PLAYLISTS) {
                                    playlistsContent()
                                }
                            }
                            RootPagerPolicy.TOP_LEVEL_PAGE_SETTINGS -> {
                                CompositionLocalProvider(LocalSwipeExclusionBounds provides settingsTabExclusionBounds) {
                                    PageExclusionGuardBox(
                                        exclusionBounds = settingsTabExclusionBounds,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .testTag(SETTINGS_TAB_SWIPE_CONTAINER_TEST_TAG),
                                    ) {
                                        saveableStateHolder.SaveableStateProvider(AppNavRoutes.SETTINGS) {
                                            settingsContent()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            RootPagerPolicy.PAGE_NOW_PLAYING -> {
                CompositionLocalProvider(LocalSwipeExclusionBounds provides nowPlayingExclusionBounds) {
                    PageExclusionGuardBox(
                        exclusionBounds = nowPlayingExclusionBounds,
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag(NOW_PLAYING_SWIPE_CONTAINER_TEST_TAG),
                    ) {
                        nowPlayingContent?.invoke(
                            RootPagerPolicy.isNowPlayingPageForeground(
                                isMainShellActive = isMainShellActive,
                                currentPage = pagerState.currentPage,
                            ),
                            onBackToContent,
                            onOpenSettingsFromNowPlaying,
                        )
                    }
                }
            }
            RootPagerPolicy.PAGE_SETTINGS -> {
                CompositionLocalProvider(LocalSwipeExclusionBounds provides settingsExclusionBounds) {
                    PageExclusionGuardBox(
                        exclusionBounds = settingsExclusionBounds,
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag(SETTINGS_SWIPE_CONTAINER_TEST_TAG),
                    ) {
                        val content = settingsFromNowPlayingContent
                        if (content != null) {
                            content(onBackToNowPlaying)
                        } else {
                            settingsContent()
                        }
                    }
                }
            }
        }
    }
}
