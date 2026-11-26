package com.cangkir.bitperfectplayer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.cangkir.bitperfectplayer.audio.PlaybackTrack
import com.cangkir.bitperfectplayer.data.DefaultHomepage
import com.cangkir.bitperfectplayer.ui.navigation.AdaptiveTopLevelNavigationFrame
import com.cangkir.bitperfectplayer.ui.navigation.AppNavRoutes
import com.cangkir.bitperfectplayer.ui.navigation.BitPerfectLibraryDestination
import com.cangkir.bitperfectplayer.ui.navigation.BitPerfectNowPlayingDestination
import com.cangkir.bitperfectplayer.ui.navigation.BitPerfectPlaylistsDestination
import com.cangkir.bitperfectplayer.ui.navigation.BitPerfectQueueDestination
import com.cangkir.bitperfectplayer.ui.navigation.BitPerfectSettingsDestination
import com.cangkir.bitperfectplayer.ui.navigation.RootGlobalOverlays
import com.cangkir.bitperfectplayer.ui.navigation.RootMainShellPager
import com.cangkir.bitperfectplayer.ui.navigation.RootNavigationActions
import com.cangkir.bitperfectplayer.ui.navigation.RootShellUiState
import kotlinx.coroutines.launch

@Composable
fun BitPerfectAppRoot(viewModel: MainViewModel, visualAssets: MainUiVisualAssets) {
    val initialStartupHomepage by viewModel.initialStartupHomepage.collectAsStateWithLifecycle()
    val resolvedStartupHomepage = initialStartupHomepage
    if (resolvedStartupHomepage == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        )
        return
    }
    BitPerfectAppRootContent(
        viewModel = viewModel,
        visualAssets = visualAssets,
        startupHomepage = resolvedStartupHomepage,
    )
}

@Composable
private fun BitPerfectAppRootContent(viewModel: MainViewModel, visualAssets: MainUiVisualAssets, startupHomepage: DefaultHomepage) {
    val navController = rememberNavController()
    val rootPagerNavigator = rememberRootPagerNavigator()
    val libraryState by viewModel.libraryState.collectAsStateWithLifecycle()
    val librarySearchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val playlistsState by viewModel.playlistsState.collectAsStateWithLifecycle()
    val favoriteTrackUris by viewModel.favoriteTrackUris.collectAsStateWithLifecycle()
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val lyricsUiState by viewModel.lyricsUiState.collectAsStateWithLifecycle()
    val trackInfoState by viewModel.trackInfoState.collectAsStateWithLifecycle()
    val uiSnackbarMessage by viewModel.uiSnackbarMessage.collectAsStateWithLifecycle()

    var trackPendingAddToPlaylist by remember { mutableStateOf<PlaybackTrack?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    val shellState = RootShellUiState(
        libraryState = libraryState,
        librarySearchQuery = librarySearchQuery,
        playlistsState = playlistsState,
        favoriteTrackUris = favoriteTrackUris,
        playbackState = playbackState,
    )
    val actions = remember(navController, rootPagerNavigator) {
        RootNavigationActions(
            openAddToPlaylist = { track -> trackPendingAddToPlaylist = track },
            openNowPlaying = { rootPagerNavigator.openNowPlaying() },
            openQueue = { navController.navigate(AppNavRoutes.QUEUE) { launchSingleTop = true } },
            openDiagnostics = { navController.navigate(AppNavRoutes.DIAGNOSTICS) { launchSingleTop = true } },
            openLyrics = { navController.navigate(AppNavRoutes.LYRICS) { launchSingleTop = true } },
            popBack = { navController.popBackStack() },
        )
    }

    RootGlobalOverlays(
        viewModel = viewModel,
        playlistsState = playlistsState,
        trackPendingAddToPlaylist = trackPendingAddToPlaylist,
        onDismissAddToPlaylist = { trackPendingAddToPlaylist = null },
        trackInfoState = trackInfoState,
        uiSnackbarMessage = uiSnackbarMessage,
        snackbarHostState = snackbarHostState,
    )

    BitPerfectStartupNavHost(
        startupHomepage = startupHomepage,
        navController = navController,
        snackbarHostState = snackbarHostState,
        miniPlayerContent = {
            MiniPlayerBar(
                playbackState = playbackState,
                playbackPositionMsFlow = viewModel.playbackPositionMs,
                artworkLoader = visualAssets.albumArtworkLoader,
                onOpenNowPlaying = actions.openNowPlaying,
                onTogglePlayPause = viewModel::togglePlayPause,
                onNext = viewModel::next,
                onDismissPlayback = viewModel::dismissActiveSession,
            )
        },
        libraryContent = { initialLibraryTab ->
            BitPerfectLibraryDestination(
                viewModel = viewModel,
                visualAssets = visualAssets,
                shellState = shellState,
                actions = actions,
                rootPagerNavigator = rootPagerNavigator,
                initialLibraryTab = initialLibraryTab,
            )
        },
        playlistsContent = {
            BitPerfectPlaylistsDestination(
                viewModel = viewModel,
                visualAssets = visualAssets,
                shellState = shellState,
                actions = actions,
                rootPagerNavigator = rootPagerNavigator,
            )
        },
        settingsContent = {
            BitPerfectSettingsDestination(
                viewModel = viewModel,
                shellState = shellState,
                onOpenDiagnostics = actions.openDiagnostics,
            )
        },
        hasActiveTrack = playbackState.activeTrack != null,
        rootPagerNavigator = rootPagerNavigator,
        nowPlayingContent = { isPageActive, onBack, _ ->
            BitPerfectNowPlayingDestination(
                viewModel = viewModel,
                visualAssets = visualAssets,
                shellState = shellState,
                actions = actions,
                isPageActive = isPageActive,
                onBack = onBack,
            )
        },
        settingsFromNowPlayingContent = { onBack ->
            BitPerfectSettingsDestination(
                viewModel = viewModel,
                shellState = shellState,
                onOpenDiagnostics = actions.openDiagnostics,
                onBack = onBack,
            )
        },
    ) {
        composable(
            route = AppNavRoutes.QUEUE,
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None },
        ) {
            BitPerfectQueueDestination(
                viewModel = viewModel,
                visualAssets = visualAssets,
                shellState = shellState,
                onBack = actions.popBack,
            )
        }

        composable(AppNavRoutes.LYRICS) {
            LyricsScreen(
                playbackState = playbackState,
                playbackPositionMsFlow = viewModel.playbackPositionMs,
                lyricsUiState = lyricsUiState,
                onBack = actions.popBack,
                onImportLrcUri = viewModel::importLrcForActiveTrack,
                onSaveManualLyrics = viewModel::saveManualLyricsForActiveTrack,
                onDeleteLyrics = viewModel::deleteLyricsForActiveTrack,
                onTogglePlayPause = viewModel::togglePlayPause,
                onSeekTo = viewModel::seekTo,
            )
        }

        composable(AppNavRoutes.DIAGNOSTICS) {
            DiagnosticsScreen(
                playbackState = playbackState,
                onBack = actions.popBack,
                onSwitchToSafeMode = viewModel.settings::switchToSafeMode,
                onRequestBitPerfectMode = viewModel.settings::requestBitPerfectMode,
                onConfirmBitPerfectWarning = viewModel.settings::confirmBitPerfectWarning,
                onDismissSafetyWarning = viewModel.settings::dismissSafetyWarning,
            )
        }
    }
}

@Suppress("UNUSED_PARAMETER")
@Composable
internal fun BitPerfectStartupNavHost(
    startupHomepage: DefaultHomepage,
    navController: NavHostController = rememberNavController(),
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    miniPlayerContent: @Composable () -> Unit = {},
    libraryContent: @Composable (LibraryTab) -> Unit,
    playlistsContent: @Composable () -> Unit,
    settingsContent: @Composable () -> Unit,
    hasActiveTrack: Boolean = false,
    rootPagerNavigator: RootPagerNavigator = rememberRootPagerNavigator(),
    nowPlayingContent: (@Composable (isPageActive: Boolean, onBack: () -> Unit, onOpenSettings: () -> Unit) -> Unit)? = null,
    settingsFromNowPlayingContent: (@Composable (onBack: () -> Unit) -> Unit)? = null,
    extraRoutes: NavGraphBuilder.() -> Unit = {},
) {
    // Latch start tab & initial Library sub-tab strictly ONCE so later preference changes never
    // hijack active navigation.
    val startTopLevelPage = rememberSaveable {
        if (startupHomepage.isPlaylistsRoute) {
            RootPagerPolicy.TOP_LEVEL_PAGE_PLAYLISTS
        } else {
            RootPagerPolicy.TOP_LEVEL_PAGE_LIBRARY
        }
    }
    val initialLibraryTab = rememberSaveable { startupHomepage.toInitialLibraryTab() }
    var lastNonSettingsPage by rememberSaveable { mutableIntStateOf(startTopLevelPage) }

    val pagerState = rememberPagerState(
        initialPage = RootPagerPolicy.PAGE_CONTENT,
        pageCount = { RootPagerPolicy.PAGE_COUNT },
    )
    val topLevelPagerState = rememberPagerState(
        initialPage = startTopLevelPage,
        pageCount = { RootPagerPolicy.TOP_LEVEL_PAGE_COUNT },
    )
    var pendingTapTopLevelPage by remember { mutableStateOf<Int?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val saveableStateHolder = rememberSaveableStateHolder()
    val hasNowPlayingPage = nowPlayingContent != null

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentNavRoute = backStackEntry?.destination?.route
    val isMainShellActive = currentNavRoute == null || currentNavRoute == AppNavRoutes.MAIN_SHELL

    val selectedTopLevelPage = RootPagerPolicy.resolveSelectedTopLevelPage(
        currentPage = topLevelPagerState.currentPage,
        pendingTapPage = pendingTapTopLevelPage,
    )
    val selectedTopLevelRoute = AppNavRoutes.topLevelRouteForPage(selectedTopLevelPage)

    LaunchedEffect(topLevelPagerState.settledPage) {
        val settled = topLevelPagerState.settledPage
        if (settled == RootPagerPolicy.TOP_LEVEL_PAGE_LIBRARY || settled == RootPagerPolicy.TOP_LEVEL_PAGE_PLAYLISTS) {
            lastNonSettingsPage = settled
        }
    }

    LaunchedEffect(isMainShellActive, pagerState.currentPage, selectedTopLevelPage) {
        val onContentShell = isMainShellActive && pagerState.currentPage == RootPagerPolicy.PAGE_CONTENT
        rootPagerNavigator.isLibraryPageForeground = onContentShell && selectedTopLevelPage == RootPagerPolicy.TOP_LEVEL_PAGE_LIBRARY
        rootPagerNavigator.isPlaylistsPageForeground = onContentShell && selectedTopLevelPage == RootPagerPolicy.TOP_LEVEL_PAGE_PLAYLISTS
    }

    DisposableEffect(rootPagerNavigator, navController, pagerState, coroutineScope, hasNowPlayingPage) {
        fun navigateRootPage(targetPage: Int) {
            if (navController.currentDestination?.route != AppNavRoutes.MAIN_SHELL) {
                navController.popBackStack(AppNavRoutes.MAIN_SHELL, inclusive = false)
            }
            coroutineScope.launch {
                pagerState.animateScrollToPage(
                    page = targetPage,
                    animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                )
            }
        }
        rootPagerNavigator.openNowPlaying = { if (hasNowPlayingPage) navigateRootPage(RootPagerPolicy.PAGE_NOW_PLAYING) }
        rootPagerNavigator.openSettingsFromNowPlaying = { navigateRootPage(RootPagerPolicy.PAGE_SETTINGS) }
        rootPagerNavigator.backToContent = { navigateRootPage(RootPagerPolicy.PAGE_CONTENT) }
        onDispose { }
    }

    val selectTopLevelTab = remember(navController, pagerState, topLevelPagerState, coroutineScope) {
        { route: String ->
            if (navController.currentDestination?.route != AppNavRoutes.MAIN_SHELL) {
                navController.popBackStack(AppNavRoutes.MAIN_SHELL, inclusive = false)
            }
            val targetPage = AppNavRoutes.topLevelPageForRoute(route)
            val fromPage = topLevelPagerState.currentPage
            pendingTapTopLevelPage = targetPage
            coroutineScope.launch {
                try {
                    if (pagerState.currentPage != RootPagerPolicy.PAGE_CONTENT) {
                        pagerState.scrollToPage(RootPagerPolicy.PAGE_CONTENT)
                    }
                    if (RootPagerPolicy.shouldInstantJumpTopLevelTab(fromPage, targetPage)) {
                        topLevelPagerState.scrollToPage(targetPage)
                    } else if (topLevelPagerState.currentPage != targetPage || topLevelPagerState.currentPageOffsetFraction != 0f) {
                        topLevelPagerState.animateScrollToPage(
                            page = targetPage,
                            animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
                        )
                    }
                } finally {
                    if (pendingTapTopLevelPage == targetPage) {
                        pendingTapTopLevelPage = null
                    }
                }
            }
            Unit
        }
    }

    val backToNowPlaying = remember(pagerState, coroutineScope) {
        {
            coroutineScope.launch {
                pagerState.animateScrollToPage(
                    page = RootPagerPolicy.PAGE_NOW_PLAYING,
                    animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                )
            }
            Unit
        }
    }

    val outerBackTarget = RootPagerPolicy.resolveBackTargetPage(currentPage = pagerState.currentPage)
    val innerBackTarget = if (pagerState.currentPage == RootPagerPolicy.PAGE_CONTENT) {
        RootPagerPolicy.resolveTopLevelBackTargetPage(
            currentPage = topLevelPagerState.currentPage,
            lastNonSettingsPage = lastNonSettingsPage,
        )
    } else {
        null
    }
    BackHandler(enabled = isMainShellActive && (outerBackTarget != null || innerBackTarget != null)) {
        if (outerBackTarget != null) {
            coroutineScope.launch {
                pagerState.animateScrollToPage(
                    page = outerBackTarget,
                    animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                )
            }
        } else if (innerBackTarget != null) {
            val fromPage = topLevelPagerState.currentPage
            coroutineScope.launch {
                if (RootPagerPolicy.shouldInstantJumpTopLevelTab(fromPage, innerBackTarget)) {
                    topLevelPagerState.scrollToPage(innerBackTarget)
                } else {
                    topLevelPagerState.animateScrollToPage(
                        page = innerBackTarget,
                        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
                    )
                }
            }
        }
    }

    val needsOverlaySnackbarHost = !isMainShellActive ||
        pagerState.settledPage != RootPagerPolicy.PAGE_CONTENT

    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = AppNavRoutes.MAIN_SHELL,
            modifier = Modifier.fillMaxSize(),
            enterTransition = { fadeIn(animationSpec = tween(durationMillis = 120)) },
            exitTransition = { fadeOut(animationSpec = tween(durationMillis = 90)) },
            popEnterTransition = { fadeIn(animationSpec = tween(durationMillis = 120)) },
            popExitTransition = { fadeOut(animationSpec = tween(durationMillis = 90)) },
        ) {
            composable(AppNavRoutes.MAIN_SHELL) {
                RootMainShellPager(
                    pagerState = pagerState,
                    topLevelPagerState = topLevelPagerState,
                    selectedTopLevelRoute = selectedTopLevelRoute,
                    isDetailViewOpen = when (selectedTopLevelPage) {
                        RootPagerPolicy.TOP_LEVEL_PAGE_LIBRARY -> rootPagerNavigator.isLibraryDetailOpen
                        RootPagerPolicy.TOP_LEVEL_PAGE_PLAYLISTS -> rootPagerNavigator.isPlaylistsDetailOpen
                        else -> false
                    },
                    hasNowPlayingPage = hasNowPlayingPage,
                    initialLibraryTab = initialLibraryTab,
                    isMainShellActive = isMainShellActive,
                    snackbarHostState = snackbarHostState,
                    saveableStateHolder = saveableStateHolder,
                    onSelectTopLevelTab = selectTopLevelTab,
                    onBackToContent = rootPagerNavigator.backToContent,
                    onOpenSettingsFromNowPlaying = rootPagerNavigator.openSettingsFromNowPlaying,
                    onBackToNowPlaying = backToNowPlaying,
                    miniPlayerContent = miniPlayerContent,
                    libraryContent = libraryContent,
                    playlistsContent = playlistsContent,
                    settingsContent = settingsContent,
                    nowPlayingContent = nowPlayingContent,
                    settingsFromNowPlayingContent = settingsFromNowPlayingContent,
                )
            }
            extraRoutes()
        }

        if (needsOverlaySnackbarHost) {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 12.dp),
            )
        }
    }
}

@Composable
internal fun BitPerfectAdaptiveTopLevelFrame(
    isTopLevelRoute: Boolean,
    currentRoute: String,
    snackbarHostState: SnackbarHostState,
    onSelectTab: (String) -> Unit,
    miniPlayerContent: @Composable () -> Unit,
    mainContent: @Composable (Modifier) -> Unit,
) {
    AdaptiveTopLevelNavigationFrame(
        isTopLevelRoute = isTopLevelRoute,
        currentRoute = currentRoute,
        snackbarHostState = snackbarHostState,
        onSelectTab = onSelectTab,
        miniPlayerContent = miniPlayerContent,
        mainContent = mainContent,
    )
}
