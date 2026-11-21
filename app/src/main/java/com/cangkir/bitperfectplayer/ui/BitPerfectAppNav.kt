package com.cangkir.bitperfectplayer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.cangkir.bitperfectplayer.audio.BrowsingQueueContext
import com.cangkir.bitperfectplayer.audio.PlaybackTrack
import com.cangkir.bitperfectplayer.audio.QueueSourceType
import com.cangkir.bitperfectplayer.ui.library.LibraryScreen
import com.cangkir.bitperfectplayer.ui.nowplaying.NowPlayingScreen
import com.cangkir.bitperfectplayer.ui.playlists.AddToPlaylistDialog
import com.cangkir.bitperfectplayer.ui.playlists.PlaylistsScreen
import com.cangkir.bitperfectplayer.ui.settings.SettingsScreen
import com.cangkir.bitperfectplayer.ui.theme.IcePrimaryContainer
import com.cangkir.bitperfectplayer.ui.theme.TextMuted

private object Routes {
    const val LIBRARY = "library"
    const val PLAYLISTS = "playlists"
    const val SETTINGS = "settings"
    const val NOW_PLAYING = "now_playing"
    const val QUEUE = "queue"
    const val LYRICS = "lyrics"
    const val DIAGNOSTICS = "diagnostics"
}

private data class TopLevelTab(val route: String, val label: String, val icon: ImageVector)

private val TOP_LEVEL_TABS = listOf(
    TopLevelTab(
        route = Routes.LIBRARY,
        label = "Library",
        icon = Icons.Filled.LibraryMusic,
    ),
    TopLevelTab(
        route = Routes.PLAYLISTS,
        label = "Playlists",
        icon = Icons.AutoMirrored.Filled.PlaylistPlay,
    ),
    TopLevelTab(
        route = Routes.SETTINGS,
        label = "Settings",
        icon = Icons.Filled.Settings,
    ),
)

private val TOP_LEVEL_ROUTES = setOf(Routes.LIBRARY, Routes.PLAYLISTS, Routes.SETTINGS)

@Composable
fun BitPerfectAppRoot(viewModel: MainViewModel) {
    val navController = rememberNavController()
    val libraryState by viewModel.libraryState.collectAsStateWithLifecycle()
    val playlistsState by viewModel.playlistsState.collectAsStateWithLifecycle()
    val favoriteTrackUris by viewModel.favoriteTrackUris.collectAsStateWithLifecycle()
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val lyricsUiState by viewModel.lyricsUiState.collectAsStateWithLifecycle()
    val trackInfoState by viewModel.trackInfoState.collectAsStateWithLifecycle()
    val uiMessage by viewModel.uiMessage.collectAsStateWithLifecycle()

    var trackPendingAddToPlaylist by remember { mutableStateOf<PlaybackTrack?>(null) }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val isTopLevelRoute = currentRoute == null || currentRoute in TOP_LEVEL_ROUTES

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(uiMessage) {
        val msg = uiMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.clearUiMessage()
    }

    val openAddToPlaylist = remember { { track: PlaybackTrack -> trackPendingAddToPlaylist = track } }
    val openNowPlaying = remember(navController) {
        { navController.navigate(Routes.NOW_PLAYING) { launchSingleTop = true } }
    }
    val openQueueWithContext = remember(navController, viewModel) {
        { browsingContext: BrowsingQueueContext? ->
            viewModel.prepareQueueScreenContext(browsingContext)
            navController.navigate(Routes.QUEUE) { launchSingleTop = true }
        }
    }
    val openQueueDefault = remember(openQueueWithContext) {
        { openQueueWithContext(null) }
    }
    val openDiagnostics = remember(navController) {
        { navController.navigate(Routes.DIAGNOSTICS) { launchSingleTop = true } }
    }
    val selectTopLevelTab = remember(navController) {
        { route: String ->
            navController.navigate(route) {
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    trackPendingAddToPlaylist?.let { targetTrack ->
        AddToPlaylistDialog(
            track = targetTrack,
            playlists = playlistsState.playlists,
            onSelectPlaylist = { playlist ->
                trackPendingAddToPlaylist = null
                viewModel.addTrackToPlaylist(
                    playlistId = playlist.id,
                    playlistName = playlist.name,
                    track = targetTrack,
                )
            },
            onCreateAndAdd = { newName ->
                trackPendingAddToPlaylist = null
                viewModel.createPlaylistAndAddTrack(newName, targetTrack)
            },
            onDismiss = { trackPendingAddToPlaylist = null },
        )
    }

    trackInfoState?.let { info ->
        TrackInfoBottomSheet(
            info = info,
            onCopiedMessage = viewModel::showUiMessage,
            onDismiss = viewModel::dismissTrackInfo,
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = if (isTopLevelRoute) Modifier else Modifier.navigationBarsPadding(),
            )
        },
        bottomBar = {
            if (isTopLevelRoute) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    MiniPlayerBar(
                        playbackState = playbackState,
                        playbackPositionMsFlow = viewModel.playbackPositionMs,
                        artworkLoader = viewModel.albumArtworkLoader,
                        onOpenNowPlaying = openNowPlaying,
                        onTogglePlayPause = viewModel::togglePlayPause,
                        onNext = viewModel::next,
                    )
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                    )
                    BitPerfectBottomNavBar(
                        currentRoute = currentRoute ?: Routes.LIBRARY,
                        onSelectTab = selectTopLevelTab,
                    )
                }
            }
        },
    ) { rootInnerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.LIBRARY,
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = rootInnerPadding.calculateBottomPadding()),
        ) {
            composable(Routes.LIBRARY) {
                LibraryScreen(
                    libraryState = libraryState,
                    favoriteTrackUris = favoriteTrackUris,
                    playbackState = playbackState,
                    albumArtworkLoader = viewModel.albumArtworkLoader,
                    onRefreshLibrary = viewModel::refreshLibrary,
                    onImportFolder = viewModel::importSafFolder,
                    onPlayTracks = { tracks, index, context ->
                        viewModel.playTracks(tracks, index, context)
                    },
                    onEnqueueTrack = viewModel::enqueueTrack,
                    onEnqueueNext = viewModel::enqueueTrackNext,
                    onEnqueueTracks = viewModel::enqueueTracks,
                    onEnqueueTracksNext = viewModel::enqueueTracksNext,
                    onSetTrackSort = viewModel::setTrackSort,
                    onSetGroupSort = viewModel::setGroupSort,
                    onAddToPlaylist = openAddToPlaylist,
                    onShowTrackInfo = viewModel::openTrackInfo,
                    onToggleFavorite = viewModel::toggleFavorite,
                    onOpenQueue = openQueueWithContext,
                    onOpenDiagnostics = openDiagnostics,
                )
            }

            composable(Routes.PLAYLISTS) {
                PlaylistsScreen(
                    playlistsState = playlistsState,
                    activeTrackUri = playbackState.activeTrack?.uriString,
                    albumArtworkLoader = viewModel.albumArtworkLoader,
                    onPlayTracks = { tracks, index, context ->
                        viewModel.playTracks(tracks, index, context)
                    },
                    onEnqueueTracks = viewModel::enqueueTracks,
                    onEnqueueTracksNext = viewModel::enqueueTracksNext,
                    onSetPlaylistSort = viewModel::setTrackSort,
                    onOpenQueue = openQueueWithContext,
                    onCreatePlaylist = viewModel::createPlaylist,
                    onRenamePlaylist = viewModel::renamePlaylist,
                    onDeletePlaylist = viewModel::deletePlaylist,
                    onRemovePlaylistEntry = viewModel::removePlaylistEntry,
                    onMovePlaylistEntry = viewModel::movePlaylistEntry,
                    onExportPlaylist = viewModel::exportPlaylistToM3u8,
                    onImportPlaylist = viewModel::importPlaylistFromM3u8,
                )
            }

            composable(Routes.SETTINGS) {
                val nowPlayingArtworkStyle by viewModel.nowPlayingArtworkStyle.collectAsStateWithLifecycle()
                val spectrumVisualizerEnabled by viewModel.spectrumVisualizerEnabled.collectAsStateWithLifecycle()
                val spectrumColorStyle by viewModel.spectrumColorStyle.collectAsStateWithLifecycle()
                val spectrumSparksEnabled by viewModel.spectrumSparksEnabled.collectAsStateWithLifecycle()
                SettingsScreen(
                    libraryState = libraryState,
                    playbackState = playbackState,
                    sleepTimerStateFlow = viewModel.sleepTimerState,
                    artworkStyle = nowPlayingArtworkStyle,
                    onSetArtworkStyle = viewModel::setNowPlayingArtworkStyle,
                    spectrumVisualizerEnabled = spectrumVisualizerEnabled,
                    onSetSpectrumVisualizerEnabled = viewModel::setSpectrumVisualizerEnabled,
                    spectrumColorStyle = spectrumColorStyle,
                    onSetSpectrumColorStyle = viewModel::setSpectrumColorStyle,
                    spectrumSparksEnabled = spectrumSparksEnabled,
                    onSetSpectrumSparksEnabled = viewModel::setSpectrumSparksEnabled,
                    onImportFolder = viewModel::importSafFolder,
                    onRemoveSafFolder = viewModel::removeSafFolder,
                    onRefreshLibrary = viewModel::refreshLibrary,
                    onSetGaplessEnabled = viewModel::setGaplessEnabled,
                    onSetReplayGainMode = viewModel::setReplayGainMode,
                    onSetReplayGainPreampDb = viewModel::setReplayGainPreampDb,
                    onSetReplayGainPreventClipping = viewModel::setReplayGainPreventClipping,
                    onSelectSleepTimerPreset = viewModel::setSleepTimerPreset,
                    onExportLocalBackup = viewModel::exportLocalBackup,
                    onImportLocalBackup = viewModel::importLocalBackup,
                    onOpenDiagnostics = openDiagnostics,
                )
            }

            composable(Routes.NOW_PLAYING) {
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
                    albumArtworkLoader = viewModel.albumArtworkLoader,
                    isFavorite = isFav,
                    artworkStyle = nowPlayingArtworkStyle,
                    onSetArtworkStyle = viewModel::setNowPlayingArtworkStyle,
                    spectrumVisualizerEnabled = spectrumVisualizerEnabled,
                    onSetSpectrumVisualizerEnabled = viewModel::setSpectrumVisualizerEnabled,
                    spectrumColorStyle = spectrumColorStyle,
                    onCycleSpectrumColorStyle = viewModel::cycleSpectrumColorStyle,
                    spectrumSparksEnabled = spectrumSparksEnabled,
                    onSetSpectrumSparksEnabled = viewModel::setSpectrumSparksEnabled,
                    onSetSpectrumVisualizerActive = viewModel::setSpectrumVisualizerActive,
                    onSampleSpectrumWindow = viewModel::sampleCurrentSpectrumWindow,
                    onBack = { navController.popBackStack() },
                    onToggleFavorite = viewModel::toggleFavorite,
                    onAddToPlaylist = openAddToPlaylist,
                    onShowTrackInfo = viewModel::openTrackInfo,
                    onSelectSleepTimerPreset = viewModel::setSleepTimerPreset,
                    onTogglePlayPause = viewModel::togglePlayPause,
                    onPrevious = viewModel::previous,
                    onNext = viewModel::next,
                    onSeekTo = viewModel::seekTo,
                    onBeginTurntableScratch = viewModel::beginTurntableScratch,
                    onUpdateTurntableScratchRate = viewModel::updateTurntableScratchRate,
                    onEndTurntableScratch = viewModel::endTurntableScratch,
                    onToggleShuffle = viewModel::toggleShuffle,
                    onCycleRepeatMode = viewModel::cycleRepeatMode,
                    onRequestBitPerfectMode = viewModel::requestBitPerfectMode,
                    onConfirmBitPerfectWarning = viewModel::confirmBitPerfectWarning,
                    onDismissSafetyWarning = viewModel::dismissSafetyWarning,
                    onSwitchToSafeMode = viewModel::switchToSafeMode,
                    onClearAutoPauseNotice = viewModel::clearAutoPauseNotice,
                    onOpenQueue = openQueueDefault,
                    onOpenLyrics = {
                        navController.navigate(Routes.LYRICS) { launchSingleTop = true }
                    },
                    onOpenDiagnostics = openDiagnostics,
                )
            }

            composable(Routes.QUEUE) {
                val activeContextTracks = remember(playbackState.queueContext, libraryState, playlistsState) {
                    val ctx = playbackState.queueContext
                    val key = ctx.sourceKey ?: ctx.sourceLabel
                    when (ctx.sourceType) {
                        QueueSourceType.ALBUM -> libraryState.albums.firstOrNull { it.name == key }?.tracks.orEmpty()
                        QueueSourceType.ARTIST -> libraryState.artists.firstOrNull { it.name == key }?.tracks.orEmpty()
                        QueueSourceType.FOLDER -> libraryState.folders.firstOrNull { it.name == key }?.tracks.orEmpty()
                        QueueSourceType.PLAYLIST -> playlistsState.playlists.firstOrNull { it.id.toString() == key }?.tracks.orEmpty()
                        QueueSourceType.RECENT -> libraryState.recentlyPlayed
                        QueueSourceType.FAVORITES -> libraryState.favorites
                        QueueSourceType.CUSTOM -> if (ctx.sourceKey == com.cangkir.bitperfectplayer.audio.QueueContext.MOST_PLAYED_KEY) {
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
                    albumArtworkLoader = viewModel.albumArtworkLoader,
                    onBack = { navController.popBackStack() },
                    onSelectQueueIndex = viewModel::playTrackAtQueueIndex,
                    onAddTrackToQueue = viewModel::enqueueTrack,
                    onRemoveQueueItem = viewModel::removeQueueItem,
                    onMoveQueueItem = viewModel::moveQueueItem,
                    onToggleShuffle = viewModel::toggleShuffle,
                    onCycleRepeatMode = viewModel::cycleRepeatMode,
                )
            }

            composable(Routes.LYRICS) {
                LyricsScreen(
                    playbackState = playbackState,
                    playbackPositionMsFlow = viewModel.playbackPositionMs,
                    lyricsUiState = lyricsUiState,
                    onBack = { navController.popBackStack() },
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
                    onBack = { navController.popBackStack() },
                    onSwitchToSafeMode = viewModel::switchToSafeMode,
                    onRequestBitPerfectMode = viewModel::requestBitPerfectMode,
                    onConfirmBitPerfectWarning = viewModel::confirmBitPerfectWarning,
                    onDismissSafetyWarning = viewModel::dismissSafetyWarning,
                )
            }
        }
    }
}

@Composable
private fun BitPerfectBottomNavBar(currentRoute: String, onSelectTab: (String) -> Unit) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        TOP_LEVEL_TABS.forEach { tab ->
            val selected = currentRoute == tab.route
            NavigationBarItem(
                selected = selected,
                onClick = { onSelectTab(tab.route) },
                icon = {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = tab.label,
                    )
                },
                label = {
                    Text(
                        text = tab.label,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        softWrap = false,
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = IcePrimaryContainer,
                    unselectedIconColor = TextMuted,
                    unselectedTextColor = TextMuted,
                ),
            )
        }
    }
}
