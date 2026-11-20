package com.cangkir.bitperfectplayer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

private object Routes {
    const val LIBRARY = "library"
    const val NOW_PLAYING = "now_playing"
    const val QUEUE = "queue"
    const val LYRICS = "lyrics"
    const val DIAGNOSTICS = "diagnostics"
}

@Composable
fun BitPerfectAppRoot(
    viewModel: MainViewModel,
) {
    val navController = rememberNavController()
    val libraryState by viewModel.libraryState.collectAsStateWithLifecycle()
    val favoriteTrackUris by viewModel.favoriteTrackUris.collectAsStateWithLifecycle()
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val lyricsUiState by viewModel.lyricsUiState.collectAsStateWithLifecycle()
    val uiMessage by viewModel.uiMessage.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(uiMessage) {
        val msg = uiMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.clearUiMessage()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = Routes.LIBRARY,
        ) {
            composable(Routes.LIBRARY) {
                LibraryScreen(
                    libraryState = libraryState,
                    favoriteTrackUris = favoriteTrackUris,
                    playbackState = playbackState,
                    playbackPositionMsFlow = viewModel.playbackPositionMs,
                    albumArtworkLoader = viewModel.albumArtworkLoader,
                    onRefreshLibrary = viewModel::refreshLibrary,
                    onImportFolder = viewModel::importSafFolder,
                    onImportFile = viewModel::importSafAudioFile,
                    onPlayTracks = { tracks, index ->
                        viewModel.playTracks(tracks, index)
                    },
                    onEnqueueTrack = viewModel::enqueueTrack,
                    onEnqueueNext = viewModel::enqueueTrackNext,
                    onToggleFavorite = viewModel::toggleFavorite,
                    onOpenNowPlaying = {
                        navController.navigate(Routes.NOW_PLAYING) { launchSingleTop = true }
                    },
                    onOpenQueue = {
                        navController.navigate(Routes.QUEUE) { launchSingleTop = true }
                    },
                    onOpenDiagnostics = {
                        navController.navigate(Routes.DIAGNOSTICS) { launchSingleTop = true }
                    },
                    onTogglePlayPause = viewModel::togglePlayPause,
                    onNext = viewModel::next,
                )
            }

            composable(Routes.NOW_PLAYING) {
                val activeTrack = playbackState.activeTrack
                val isFav = activeTrack != null && (
                    activeTrack.uriString in favoriteTrackUris ||
                        activeTrack.contentFingerprint?.lowercase()?.let { it in libraryState.favoriteFingerprints } == true
                    )
                NowPlayingScreen(
                    playbackState = playbackState,
                    playbackPositionMsFlow = viewModel.playbackPositionMs,
                    albumArtworkLoader = viewModel.albumArtworkLoader,
                    isFavorite = isFav,
                    onBack = { navController.popBackStack() },
                    onToggleFavorite = viewModel::toggleFavorite,
                    onTogglePlayPause = viewModel::togglePlayPause,
                    onQuickPause = viewModel::pause,
                    onPrevious = viewModel::previous,
                    onNext = viewModel::next,
                    onSeekTo = viewModel::seekTo,
                    onToggleShuffle = viewModel::toggleShuffle,
                    onCycleRepeatMode = viewModel::cycleRepeatMode,
                    onRequestBitPerfectMode = viewModel::requestBitPerfectMode,
                    onConfirmBitPerfectWarning = viewModel::confirmBitPerfectWarning,
                    onDismissSafetyWarning = viewModel::dismissSafetyWarning,
                    onSwitchToSafeMode = viewModel::switchToSafeMode,
                    onClearAutoPauseNotice = viewModel::clearAutoPauseNotice,
                    onOpenQueue = {
                        navController.navigate(Routes.QUEUE) { launchSingleTop = true }
                    },
                    onOpenLyrics = {
                        navController.navigate(Routes.LYRICS) { launchSingleTop = true }
                    },
                    onOpenDiagnostics = {
                        navController.navigate(Routes.DIAGNOSTICS) { launchSingleTop = true }
                    },
                )
            }

            composable(Routes.QUEUE) {
                QueueScreen(
                    playbackState = playbackState,
                    libraryTracks = libraryState.allSongs,
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

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 68.dp),
        )
    }
}
