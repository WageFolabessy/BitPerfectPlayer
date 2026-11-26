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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.cangkir.bitperfectplayer.audio.PlaybackControllerState
import com.cangkir.bitperfectplayer.audio.PlaybackTrack
import com.cangkir.bitperfectplayer.audio.QueueContext
import com.cangkir.bitperfectplayer.audio.QueueSourceType
import com.cangkir.bitperfectplayer.data.DefaultHomepage
import com.cangkir.bitperfectplayer.library.LibrarySnapshot
import com.cangkir.bitperfectplayer.ui.library.LibraryScreen
import com.cangkir.bitperfectplayer.ui.nowplaying.LocalSwipeExclusionBounds
import com.cangkir.bitperfectplayer.ui.nowplaying.NOW_PLAYING_SWIPE_CONTAINER_TEST_TAG
import com.cangkir.bitperfectplayer.ui.nowplaying.NowPlayingScreen
import com.cangkir.bitperfectplayer.ui.nowplaying.NowPlayingSwipeExclusionBounds
import com.cangkir.bitperfectplayer.ui.nowplaying.PageExclusionGuardBox
import com.cangkir.bitperfectplayer.ui.nowplaying.ROOT_HORIZONTAL_PAGER_TEST_TAG
import com.cangkir.bitperfectplayer.ui.nowplaying.SETTINGS_SWIPE_CONTAINER_TEST_TAG
import com.cangkir.bitperfectplayer.ui.nowplaying.SETTINGS_TAB_SWIPE_CONTAINER_TEST_TAG
import com.cangkir.bitperfectplayer.ui.nowplaying.TOP_LEVEL_HORIZONTAL_PAGER_TEST_TAG
import com.cangkir.bitperfectplayer.ui.playlists.AddToPlaylistDialog
import com.cangkir.bitperfectplayer.ui.playlists.PlaylistsScreen
import com.cangkir.bitperfectplayer.ui.settings.SettingsScreen
import kotlinx.coroutines.launch

private object Routes {
    const val MAIN_SHELL = "main_shell"
    const val LIBRARY = "library"
    const val PLAYLISTS = "playlists"
    const val SETTINGS = "settings"
    const val QUEUE = "queue"
    const val LYRICS = "lyrics"
    const val DIAGNOSTICS = "diagnostics"
}

private data class TopLevelTab(val route: String, val label: String, val selectedIcon: ImageVector, val unselectedIcon: ImageVector)

private val TOP_LEVEL_TABS = listOf(
    TopLevelTab(
        route = Routes.LIBRARY,
        label = "Library",
        selectedIcon = Icons.Filled.LibraryMusic,
        unselectedIcon = Icons.Outlined.LibraryMusic,
    ),
    TopLevelTab(
        route = Routes.PLAYLISTS,
        label = "Playlists",
        selectedIcon = Icons.AutoMirrored.Filled.PlaylistPlay,
        unselectedIcon = Icons.AutoMirrored.Outlined.PlaylistPlay,
    ),
    TopLevelTab(
        route = Routes.SETTINGS,
        label = "Settings",
        selectedIcon = Icons.Filled.Settings,
        unselectedIcon = Icons.Outlined.Settings,
    ),
)

@Stable
internal class RootPagerNavigator {
    var openNowPlaying: () -> Unit = {}
    var openSettingsFromNowPlaying: () -> Unit = {}
    var backToContent: () -> Unit = {}
    var isLibraryDetailOpen: Boolean by mutableStateOf(false)
    var isPlaylistsDetailOpen: Boolean by mutableStateOf(false)
    var isLibraryPageForeground: Boolean by mutableStateOf(true)
    var isPlaylistsPageForeground: Boolean by mutableStateOf(false)
}

@Composable
internal fun rememberRootPagerNavigator(): RootPagerNavigator = remember { RootPagerNavigator() }

private fun topLevelRouteForPage(page: Int): String = when (page) {
    RootPagerPolicy.TOP_LEVEL_PAGE_PLAYLISTS -> Routes.PLAYLISTS
    RootPagerPolicy.TOP_LEVEL_PAGE_SETTINGS -> Routes.SETTINGS
    else -> Routes.LIBRARY
}

private fun topLevelPageForRoute(route: String): Int = when (route) {
    Routes.PLAYLISTS -> RootPagerPolicy.TOP_LEVEL_PAGE_PLAYLISTS
    Routes.SETTINGS -> RootPagerPolicy.TOP_LEVEL_PAGE_SETTINGS
    else -> RootPagerPolicy.TOP_LEVEL_PAGE_LIBRARY
}

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
    LaunchedEffect(uiSnackbarMessage?.id) {
        val current = uiSnackbarMessage ?: return@LaunchedEffect
        try {
            snackbarHostState.currentSnackbarData?.dismiss()
            val result = snackbarHostState.showSnackbar(
                message = current.message,
                actionLabel = current.actionLabel,
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) {
                current.onAction?.invoke()
            }
        } finally {
            viewModel.clearUiMessage(current.id)
        }
    }

    val openAddToPlaylist = remember { { track: PlaybackTrack -> trackPendingAddToPlaylist = track } }
    val openNowPlaying = remember(rootPagerNavigator) {
        { rootPagerNavigator.openNowPlaying() }
    }
    val openQueue = remember(navController) {
        { navController.navigate(Routes.QUEUE) { launchSingleTop = true } }
    }
    val openDiagnostics = remember(navController) {
        { navController.navigate(Routes.DIAGNOSTICS) { launchSingleTop = true } }
    }
    val openLyrics = remember(navController) {
        { navController.navigate(Routes.LYRICS) { launchSingleTop = true } }
    }
    val popBack = remember(navController) {
        {
            navController.popBackStack()
            Unit
        }
    }

    trackPendingAddToPlaylist?.let { targetTrack ->
        AddToPlaylistDialog(
            track = targetTrack,
            playlists = playlistsState.playlists,
            onSelectPlaylist = { playlist ->
                trackPendingAddToPlaylist = null
                viewModel.playlists.addTrackToPlaylist(
                    playlistId = playlist.id,
                    playlistName = playlist.name,
                    track = targetTrack,
                )
            },
            onCreateAndAdd = { newName ->
                trackPendingAddToPlaylist = null
                viewModel.playlists.createPlaylistAndAddTrack(newName, targetTrack)
            },
            onDismiss = { trackPendingAddToPlaylist = null },
        )
    }

    trackInfoState?.let { info ->
        TrackInfoBottomSheet(
            info = info,
            onCopiedMessage = viewModel::showUiMessage,
            onDismiss = viewModel.library::dismissTrackInfo,
        )
    }

    BitPerfectStartupNavHost(
        startupHomepage = startupHomepage,
        navController = navController,
        snackbarHostState = snackbarHostState,
        miniPlayerContent = {
            MiniPlayerBar(
                playbackState = playbackState,
                playbackPositionMsFlow = viewModel.playbackPositionMs,
                artworkLoader = visualAssets.albumArtworkLoader,
                onOpenNowPlaying = openNowPlaying,
                onTogglePlayPause = viewModel::togglePlayPause,
                onNext = viewModel::next,
                onDismissPlayback = viewModel::dismissActiveSession,
            )
        },
        libraryContent = { initialLibraryTab ->
            val dynamicCoverThemeEnabled by viewModel.dynamicCoverThemeEnabled.collectAsStateWithLifecycle()
            val dynamicThemeIntensity by viewModel.dynamicThemeIntensity.collectAsStateWithLifecycle()
            val artistPhotoRevision by viewModel.artistPhotoRevision.collectAsStateWithLifecycle()
            LibraryScreen(
                libraryState = libraryState,
                favoriteTrackUris = favoriteTrackUris,
                playbackState = playbackState,
                albumArtworkLoader = visualAssets.albumArtworkLoader,
                onRefreshLibrary = viewModel.library::refreshLibrary,
                onImportFolder = viewModel.library::importSafFolder,
                onPlayTracks = { tracks, index, context ->
                    viewModel.playTracks(tracks, index, context)
                },
                onEnqueueTrack = viewModel::enqueueTrack,
                onEnqueueNext = viewModel::enqueueTrackNext,
                onEnqueueTracks = viewModel::enqueueTracks,
                onEnqueueTracksNext = viewModel::enqueueTracksNext,
                onSetTrackSort = viewModel.library::setTrackSort,
                onSetGroupSort = viewModel.library::setGroupSort,
                onAddToPlaylist = openAddToPlaylist,
                onShowTrackInfo = viewModel.library::openTrackInfo,
                onToggleFavorite = { track -> viewModel.library.toggleFavorite(track) },
                onToggleFavoriteFromFavorites = { track ->
                    viewModel.library.toggleFavorite(track, showRemovalUndoSnackbar = true)
                },
                onOpenDiagnostics = openDiagnostics,
                onOpenQueue = openQueue,
                initialTab = initialLibraryTab,
                dynamicCoverThemeEnabled = dynamicCoverThemeEnabled,
                dynamicThemeIntensity = dynamicThemeIntensity,
                coverThemeColorCache = visualAssets.coverThemeColorCache,
                artistPhotoStore = visualAssets.artistPhotoStore,
                artistPhotoRevision = artistPhotoRevision,
                onShuffleTracks = { tracks, context -> viewModel.playTracks(tracks, context = context, shuffled = true) },
                onSetCustomArtistPhoto = { artist, uri -> viewModel.library.updateCustomArtistPhoto(artist, uri) },
                onResetCustomArtistPhoto = { artist -> viewModel.library.updateCustomArtistPhoto(artist, null) },
                onDetailViewOpenChanged = { isOpen -> rootPagerNavigator.isLibraryDetailOpen = isOpen },
                onContextualDynamicThemeChanged = { tokens ->
                    viewModel.setContextualDynamicTheme("library", tokens)
                },
                isForegroundPage = rootPagerNavigator.isLibraryPageForeground,
                externalSearchQuery = librarySearchQuery,
                onExternalSearchQueryChange = viewModel.library::updateSearchQuery,
            )
        },
        playlistsContent = {
            val dynamicCoverThemeEnabled by viewModel.dynamicCoverThemeEnabled.collectAsStateWithLifecycle()
            val dynamicThemeIntensity by viewModel.dynamicThemeIntensity.collectAsStateWithLifecycle()
            PlaylistsScreen(
                playlistsState = playlistsState,
                activeTrackUri = playbackState.activeTrack?.uriString,
                albumArtworkLoader = visualAssets.albumArtworkLoader,
                onPlayTracks = { tracks, index, context ->
                    viewModel.playTracks(tracks, index, context)
                },
                onEnqueueTracks = viewModel::enqueueTracks,
                onEnqueueTracksNext = viewModel::enqueueTracksNext,
                onSetPlaylistSort = viewModel.library::setTrackSort,
                onCreatePlaylist = viewModel.playlists::createPlaylist,
                onRenamePlaylist = viewModel.playlists::renamePlaylist,
                onDeletePlaylist = viewModel.playlists::deletePlaylist,
                onRemovePlaylistEntry = { playlistId, entryId ->
                    viewModel.playlists.removePlaylistEntry(playlistId, entryId)
                },
                onExportPlaylist = viewModel.playlists::exportPlaylistToM3u8,
                onImportPlaylist = viewModel::importPlaylistFromM3u8,
                onOpenQueue = openQueue,
                favoriteTrackUris = favoriteTrackUris,
                favoriteFingerprints = libraryState.favoriteFingerprints,
                onShuffleTracks = { tracks, context -> viewModel.playTracks(tracks, context = context, shuffled = true) },
                onToggleFavorite = { track -> viewModel.library.toggleFavorite(track) },
                onEnqueueTrack = viewModel::enqueueTrack,
                onEnqueueNext = viewModel::enqueueTrackNext,
                onAddToPlaylist = openAddToPlaylist,
                onShowTrackInfo = viewModel.library::openTrackInfo,
                onRemovePlaylistEntryWithTitle = { playlistId, entryId, playlistName, trackTitle ->
                    viewModel.playlists.removePlaylistEntry(playlistId, entryId, playlistName, trackTitle)
                },
                onCommitPlaylistOrder = viewModel.playlists::commitPlaylistOrder,
                dynamicCoverThemeEnabled = dynamicCoverThemeEnabled,
                dynamicThemeIntensity = dynamicThemeIntensity,
                coverThemeColorCache = visualAssets.coverThemeColorCache,
                isForegroundPage = rootPagerNavigator.isPlaylistsPageForeground,
                onDetailViewOpenChanged = { isOpen -> rootPagerNavigator.isPlaylistsDetailOpen = isOpen },
                onContextualDynamicThemeChanged = { tokens ->
                    viewModel.setContextualDynamicTheme("playlists", tokens)
                },
            )
        },
        settingsContent = {
            BitPerfectSettingsDestination(
                viewModel = viewModel,
                libraryState = libraryState,
                playbackState = playbackState,
                onOpenDiagnostics = openDiagnostics,
            )
        },
        hasActiveTrack = playbackState.activeTrack != null,
        rootPagerNavigator = rootPagerNavigator,
        nowPlayingContent = { isPageActive, onBack, _ ->
            BitPerfectNowPlayingDestination(
                viewModel = viewModel,
                visualAssets = visualAssets,
                libraryState = libraryState,
                favoriteTrackUris = favoriteTrackUris,
                playbackState = playbackState,
                isPageActive = isPageActive,
                onBack = onBack,
                onAddToPlaylist = openAddToPlaylist,
                onOpenQueue = openQueue,
                onOpenLyrics = openLyrics,
                onOpenDiagnostics = openDiagnostics,
            )
        },
        settingsFromNowPlayingContent = { onBack ->
            BitPerfectSettingsDestination(
                viewModel = viewModel,
                libraryState = libraryState,
                playbackState = playbackState,
                onOpenDiagnostics = openDiagnostics,
                onBack = onBack,
            )
        },
    ) {
        composable(
            route = Routes.QUEUE,
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None },
        ) {
            BitPerfectQueueDestination(
                viewModel = viewModel,
                visualAssets = visualAssets,
                libraryState = libraryState,
                playlistsState = playlistsState,
                playbackState = playbackState,
                onBack = popBack,
            )
        }

        composable(Routes.LYRICS) {
            LyricsScreen(
                playbackState = playbackState,
                playbackPositionMsFlow = viewModel.playbackPositionMs,
                lyricsUiState = lyricsUiState,
                onBack = popBack,
                onImportLrcUri = viewModel::importLrcForActiveTrack,
                onSaveManualLyrics = viewModel::saveManualLyricsForActiveTrack,
                onDeleteLyrics = viewModel::deleteLyricsForActiveTrack,
                onTogglePlayPause = viewModel::togglePlayPause,
                onSeekTo = viewModel::seekTo,
            )
        }

        composable(Routes.DIAGNOSTICS) {
            DiagnosticsScreen(
                playbackState = playbackState,
                onBack = popBack,
                onSwitchToSafeMode = viewModel.settings::switchToSafeMode,
                onRequestBitPerfectMode = viewModel.settings::requestBitPerfectMode,
                onConfirmBitPerfectWarning = viewModel.settings::confirmBitPerfectWarning,
                onDismissSafetyWarning = viewModel.settings::dismissSafetyWarning,
            )
        }
    }
}

@Composable
private fun BitPerfectNowPlayingDestination(
    viewModel: MainViewModel,
    visualAssets: MainUiVisualAssets,
    libraryState: LibrarySnapshot,
    favoriteTrackUris: Set<String>,
    playbackState: PlaybackControllerState,
    isPageActive: Boolean,
    onBack: () -> Unit,
    onAddToPlaylist: (PlaybackTrack) -> Unit,
    onOpenQueue: () -> Unit,
    onOpenLyrics: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    val nowPlayingArtworkStyle by viewModel.nowPlayingArtworkStyle.collectAsStateWithLifecycle()
    val spectrumVisualizerEnabled by viewModel.spectrumVisualizerEnabled.collectAsStateWithLifecycle()
    val spectrumColorStyle by viewModel.spectrumColorStyle.collectAsStateWithLifecycle()
    val spectrumSparksEnabled by viewModel.spectrumSparksEnabled.collectAsStateWithLifecycle()
    val activeTrack = playbackState.activeTrack
    val isFav = activeTrack != null &&
        (
            activeTrack.uriString in favoriteTrackUris ||
                activeTrack.contentFingerprint?.lowercase()?.let { it in libraryState.favoriteFingerprints } == true
            )
    NowPlayingScreen(
        playbackState = playbackState,
        playbackPositionMsFlow = viewModel.playbackPositionMs,
        sleepTimerStateFlow = viewModel.sleepTimerState,
        albumArtworkLoader = visualAssets.albumArtworkLoader,
        isFavorite = isFav,
        artworkStyle = nowPlayingArtworkStyle,
        onSetArtworkStyle = viewModel.settings::setNowPlayingArtworkStyle,
        spectrumVisualizerEnabled = spectrumVisualizerEnabled,
        onSetSpectrumVisualizerEnabled = viewModel.settings::setSpectrumVisualizerEnabled,
        spectrumColorStyle = spectrumColorStyle,
        onCycleSpectrumColorStyle = { viewModel.settings.setSpectrumColorStyle(spectrumColorStyle.next()) },
        spectrumSparksEnabled = spectrumSparksEnabled,
        onSetSpectrumSparksEnabled = viewModel.settings::setSpectrumSparksEnabled,
        onSetSpectrumVisualizerActive = viewModel::setSpectrumVisualizerActive,
        onSampleSpectrumWindow = viewModel::sampleCurrentSpectrumWindow,
        onBack = onBack,
        onToggleFavorite = viewModel.library::toggleFavorite,
        onAddToPlaylist = onAddToPlaylist,
        onShowTrackInfo = viewModel.library::openTrackInfo,
        onSelectSleepTimerPreset = viewModel.settings::setSleepTimerPreset,
        onTogglePlayPause = viewModel::togglePlayPause,
        onPrevious = viewModel::previous,
        onNext = viewModel::next,
        onSeekTo = viewModel::seekTo,
        onBeginTurntableScratch = viewModel::beginTurntableScratch,
        onUpdateTurntableScratchRate = viewModel::updateTurntableScratchRate,
        onEndTurntableScratch = viewModel::endTurntableScratch,
        onToggleShuffle = viewModel::toggleShuffle,
        onCycleRepeatMode = viewModel::cycleRepeatMode,
        onRequestBitPerfectMode = viewModel.settings::requestBitPerfectMode,
        onConfirmBitPerfectWarning = viewModel.settings::confirmBitPerfectWarning,
        onDismissSafetyWarning = viewModel.settings::dismissSafetyWarning,
        onSwitchToSafeMode = viewModel.settings::switchToSafeMode,
        onClearAutoPauseNotice = viewModel.settings::clearAutoPauseNotice,
        onOpenQueue = onOpenQueue,
        onOpenLyrics = onOpenLyrics,
        onOpenDiagnostics = onOpenDiagnostics,
        isPageActive = isPageActive,
        coverThemeColorCache = visualAssets.coverThemeColorCache,
        onBackCoverVisibilityChanged = { visible ->
            viewModel.dynamicTheme.setNowPlayingBackCoverVisible(visible, activeTrack?.uriString)
        },
    )
}

@Composable
private fun BitPerfectQueueDestination(
    viewModel: MainViewModel,
    visualAssets: MainUiVisualAssets,
    libraryState: LibrarySnapshot,
    playlistsState: com.cangkir.bitperfectplayer.library.PlaylistsUiSnapshot,
    playbackState: PlaybackControllerState,
    onBack: () -> Unit,
) {
    val queueContext = playbackState.queueContext
    val activeContextTracks = remember(
        queueContext,
        libraryState.albums,
        libraryState.artists,
        libraryState.folders,
        libraryState.recentlyPlayed,
        libraryState.favorites,
        playlistsState.playlists,
        playlistsState.mostPlayed,
    ) {
        val key = queueContext.sourceKey ?: queueContext.sourceLabel
        when (queueContext.sourceType) {
            QueueSourceType.ALBUM -> libraryState.albums.firstOrNull { it.name == key }?.tracks.orEmpty()
            QueueSourceType.ARTIST -> libraryState.artists.firstOrNull { it.name == key }?.tracks.orEmpty()
            QueueSourceType.FOLDER -> libraryState.folders.firstOrNull { it.name == key }?.tracks.orEmpty()
            QueueSourceType.PLAYLIST -> playlistsState.playlists.firstOrNull { it.id.toString() == key }?.tracks.orEmpty()
            QueueSourceType.RECENT -> libraryState.recentlyPlayed
            QueueSourceType.FAVORITES -> libraryState.favorites
            QueueSourceType.CUSTOM -> if (queueContext.sourceKey == QueueContext.MOST_PLAYED_KEY) {
                playlistsState.mostPlayed.map { it.track }
            } else {
                emptyList()
            }
            QueueSourceType.ALL_SONGS -> emptyList()
        }
    }
    QueueScreen(
        playbackState = playbackState,
        libraryTracks = libraryState.allSongs,
        activeContextTracks = activeContextTracks,
        albumArtworkLoader = visualAssets.albumArtworkLoader,
        onBack = onBack,
        onSelectQueueIndex = viewModel::playTrackAtQueueIndex,
        onAddTrackToQueue = viewModel::enqueueTrack,
        onRemoveQueueItem = viewModel::removeQueueItem,
        onMoveQueueItem = viewModel::moveQueueItem,
        onToggleShuffle = viewModel::toggleShuffle,
        onCycleRepeatMode = viewModel::cycleRepeatMode,
    )
}

@Composable
private fun BitPerfectSettingsDestination(
    viewModel: MainViewModel,
    libraryState: LibrarySnapshot,
    playbackState: PlaybackControllerState,
    onOpenDiagnostics: () -> Unit,
    onBack: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val defaultHomepage by viewModel.defaultHomepage.collectAsStateWithLifecycle()
    val nowPlayingArtworkStyle by viewModel.nowPlayingArtworkStyle.collectAsStateWithLifecycle()
    val spectrumVisualizerEnabled by viewModel.spectrumVisualizerEnabled.collectAsStateWithLifecycle()
    val spectrumColorStyle by viewModel.spectrumColorStyle.collectAsStateWithLifecycle()
    val spectrumSparksEnabled by viewModel.spectrumSparksEnabled.collectAsStateWithLifecycle()
    val dynamicCoverThemeEnabled by viewModel.dynamicCoverThemeEnabled.collectAsStateWithLifecycle()
    val dynamicThemeIntensity by viewModel.dynamicThemeIntensity.collectAsStateWithLifecycle()
    val diagnosticLoggingEnabled by viewModel.diagnosticLoggingEnabled.collectAsStateWithLifecycle()
    val diagnosticLogStats by viewModel.diagnosticLogStats.collectAsStateWithLifecycle()
    SettingsScreen(
        libraryState = libraryState,
        playbackState = playbackState,
        sleepTimerStateFlow = viewModel.sleepTimerState,
        defaultHomepage = defaultHomepage,
        onSetDefaultHomepage = viewModel.settings::setDefaultHomepage,
        artworkStyle = nowPlayingArtworkStyle,
        onSetArtworkStyle = viewModel.settings::setNowPlayingArtworkStyle,
        spectrumVisualizerEnabled = spectrumVisualizerEnabled,
        onSetSpectrumVisualizerEnabled = viewModel.settings::setSpectrumVisualizerEnabled,
        spectrumColorStyle = spectrumColorStyle,
        onSetSpectrumColorStyle = viewModel.settings::setSpectrumColorStyle,
        spectrumSparksEnabled = spectrumSparksEnabled,
        onSetSpectrumSparksEnabled = viewModel.settings::setSpectrumSparksEnabled,
        dynamicCoverThemeEnabled = dynamicCoverThemeEnabled,
        onSetDynamicCoverThemeEnabled = viewModel.settings::setDynamicCoverThemeEnabled,
        dynamicThemeIntensity = dynamicThemeIntensity,
        onSetDynamicThemeIntensity = viewModel.settings::setDynamicThemeIntensity,
        diagnosticLoggingEnabled = diagnosticLoggingEnabled,
        diagnosticLogStats = diagnosticLogStats,
        onSetDiagnosticLoggingEnabled = viewModel.settings::setDiagnosticLoggingEnabled,
        onExportDiagnosticLogs = viewModel.settings::exportDiagnosticLogs,
        onShareDiagnosticLogs = {
            viewModel.settings.shareDiagnosticLogs { shareIntent ->
                runCatching {
                    context.startActivity(shareIntent)
                }.onFailure {
                    viewModel.showUiMessage("No app available to share diagnostic logs")
                }
            }
        },
        onClearDiagnosticLogs = viewModel.settings::clearDiagnosticLogs,
        onRefreshDiagnosticLogStats = viewModel.settings::refreshDiagnosticLogStats,
        onImportFolder = viewModel.library::importSafFolder,
        onRemoveSafFolder = viewModel.library::removeSafFolder,
        onRefreshLibrary = viewModel.library::refreshLibrary,
        onSetGaplessEnabled = viewModel::setGaplessEnabled,
        onSetReplayGainMode = viewModel::setReplayGainMode,
        onSetReplayGainPreampDb = viewModel::setReplayGainPreampDb,
        onSetReplayGainPreventClipping = viewModel::setReplayGainPreventClipping,
        onSelectSleepTimerPreset = viewModel.settings::setSleepTimerPreset,
        onExportLocalBackup = viewModel.settings::exportLocalBackup,
        onImportLocalBackup = viewModel.settings::importLocalBackup,
        onOpenDiagnostics = onOpenDiagnostics,
        onBack = onBack,
    )
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
    val isMainShellActive = currentNavRoute == null || currentNavRoute == Routes.MAIN_SHELL

    val selectedTopLevelPage = RootPagerPolicy.resolveSelectedTopLevelPage(
        currentPage = topLevelPagerState.currentPage,
        pendingTapPage = pendingTapTopLevelPage,
    )
    val selectedTopLevelRoute = topLevelRouteForPage(selectedTopLevelPage)

    LaunchedEffect(topLevelPagerState.settledPage) {
        val settled = topLevelPagerState.settledPage
        if (settled == RootPagerPolicy.TOP_LEVEL_PAGE_LIBRARY || settled == RootPagerPolicy.TOP_LEVEL_PAGE_PLAYLISTS) {
            lastNonSettingsPage = settled
        }
    }

    LaunchedEffect(isMainShellActive, pagerState.currentPage, selectedTopLevelPage) {
        val onContentShell = isMainShellActive && pagerState.currentPage == RootPagerPolicy.PAGE_CONTENT
        val isLibraryTab = selectedTopLevelPage == RootPagerPolicy.TOP_LEVEL_PAGE_LIBRARY
        val isPlaylistsTab = selectedTopLevelPage == RootPagerPolicy.TOP_LEVEL_PAGE_PLAYLISTS
        rootPagerNavigator.isLibraryPageForeground = onContentShell && isLibraryTab
        rootPagerNavigator.isPlaylistsPageForeground = onContentShell && isPlaylistsTab
    }

    DisposableEffect(rootPagerNavigator, navController, pagerState, coroutineScope, hasNowPlayingPage) {
        rootPagerNavigator.openNowPlaying = {
            if (hasNowPlayingPage) {
                if (navController.currentDestination?.route != Routes.MAIN_SHELL) {
                    navController.popBackStack(Routes.MAIN_SHELL, inclusive = false)
                }
                coroutineScope.launch {
                    pagerState.animateScrollToPage(
                        page = RootPagerPolicy.PAGE_NOW_PLAYING,
                        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                    )
                }
            }
        }
        rootPagerNavigator.openSettingsFromNowPlaying = {
            if (navController.currentDestination?.route != Routes.MAIN_SHELL) {
                navController.popBackStack(Routes.MAIN_SHELL, inclusive = false)
            }
            coroutineScope.launch {
                pagerState.animateScrollToPage(
                    page = RootPagerPolicy.PAGE_SETTINGS,
                    animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                )
            }
        }
        rootPagerNavigator.backToContent = {
            if (navController.currentDestination?.route != Routes.MAIN_SHELL) {
                navController.popBackStack(Routes.MAIN_SHELL, inclusive = false)
            }
            coroutineScope.launch {
                pagerState.animateScrollToPage(
                    page = RootPagerPolicy.PAGE_CONTENT,
                    animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                )
            }
        }
        onDispose { }
    }

    val selectTopLevelTab = remember(navController, pagerState, topLevelPagerState, coroutineScope) {
        { route: String ->
            if (navController.currentDestination?.route != Routes.MAIN_SHELL) {
                navController.popBackStack(Routes.MAIN_SHELL, inclusive = false)
            }
            val targetPage = topLevelPageForRoute(route)
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
            startDestination = Routes.MAIN_SHELL,
            modifier = Modifier.fillMaxSize(),
            enterTransition = { fadeIn(animationSpec = tween(durationMillis = 120)) },
            exitTransition = { fadeOut(animationSpec = tween(durationMillis = 90)) },
            popEnterTransition = { fadeIn(animationSpec = tween(durationMillis = 120)) },
            popExitTransition = { fadeOut(animationSpec = tween(durationMillis = 90)) },
        ) {
            composable(Routes.MAIN_SHELL) {
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
private fun RootMainShellPager(
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
                                saveableStateHolder.SaveableStateProvider(Routes.LIBRARY) {
                                    libraryContent(initialLibraryTab)
                                }
                            }
                            RootPagerPolicy.TOP_LEVEL_PAGE_PLAYLISTS -> {
                                saveableStateHolder.SaveableStateProvider(Routes.PLAYLISTS) {
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
                                        saveableStateHolder.SaveableStateProvider(Routes.SETTINGS) {
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

@Composable
internal fun BitPerfectAdaptiveTopLevelFrame(
    isTopLevelRoute: Boolean,
    currentRoute: String,
    snackbarHostState: SnackbarHostState,
    onSelectTab: (String) -> Unit,
    miniPlayerContent: @Composable () -> Unit,
    mainContent: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavigationRail = remember(maxWidth) {
            WindowLayoutMetrics.useNavigationRail(maxWidth.value.toInt())
        }

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = {
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = if (isTopLevelRoute && !useNavigationRail) Modifier else Modifier.navigationBarsPadding(),
                )
            },
            bottomBar = {
                if (isTopLevelRoute && !useNavigationRail) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        miniPlayerContent()
                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                        )
                        BitPerfectBottomNavBar(
                            currentRoute = currentRoute,
                            onSelectTab = onSelectTab,
                        )
                    }
                }
            },
        ) { rootInnerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = rootInnerPadding.calculateBottomPadding()),
            ) {
                if (useNavigationRail && isTopLevelRoute) {
                    BitPerfectNavigationRail(
                        currentRoute = currentRoute,
                        onSelectTab = onSelectTab,
                    )
                    VerticalDivider(
                        modifier = Modifier.fillMaxHeight(),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                ) {
                    mainContent(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    )
                    if (useNavigationRail && isTopLevelRoute) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding(),
                        ) {
                            miniPlayerContent()
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun BitPerfectBottomNavBar(currentRoute: String, onSelectTab: (String) -> Unit) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        modifier = Modifier.semantics {
            contentDescription = "Bottom navigation bar"
        },
    ) {
        TOP_LEVEL_TABS.forEach { tab ->
            val selected = currentRoute == tab.route
            NavigationBarItem(
                selected = selected,
                onClick = { onSelectTab(tab.route) },
                modifier = Modifier
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .semantics {
                        contentDescription = tab.label
                    },
                icon = {
                    Icon(
                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                        contentDescription = null,
                    )
                },
                label = null,
                alwaysShowLabel = false,
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}

@Composable
internal fun BitPerfectNavigationRail(currentRoute: String, onSelectTab: (String) -> Unit) {
    NavigationRail(
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxHeight()
            .semantics {
                contentDescription = "Side navigation rail"
            },
    ) {
        Spacer(modifier = Modifier.height(12.dp))
        TOP_LEVEL_TABS.forEach { tab ->
            val selected = currentRoute == tab.route
            NavigationRailItem(
                selected = selected,
                onClick = { onSelectTab(tab.route) },
                modifier = Modifier
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .semantics {
                        contentDescription = tab.label
                    },
                icon = {
                    Icon(
                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                        contentDescription = null,
                    )
                },
                label = null,
                alwaysShowLabel = false,
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}
