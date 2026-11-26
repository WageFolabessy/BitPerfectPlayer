package com.cangkir.bitperfectplayer.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cangkir.bitperfectplayer.audio.PlaybackControllerState
import com.cangkir.bitperfectplayer.audio.PlaybackTrack
import com.cangkir.bitperfectplayer.audio.QueueContext
import com.cangkir.bitperfectplayer.audio.QueueSourceType
import com.cangkir.bitperfectplayer.library.LibrarySnapshot
import com.cangkir.bitperfectplayer.library.PlaylistsUiSnapshot
import com.cangkir.bitperfectplayer.ui.LibraryTab
import com.cangkir.bitperfectplayer.ui.MainUiVisualAssets
import com.cangkir.bitperfectplayer.ui.MainViewModel
import com.cangkir.bitperfectplayer.ui.QueueScreen
import com.cangkir.bitperfectplayer.ui.RootPagerNavigator
import com.cangkir.bitperfectplayer.ui.library.LibraryScreen
import com.cangkir.bitperfectplayer.ui.nowplaying.NowPlayingScreen
import com.cangkir.bitperfectplayer.ui.playlists.PlaylistsScreen
import com.cangkir.bitperfectplayer.ui.settings.SettingsScreen

@Stable
internal data class RootNavigationActions(
    val openAddToPlaylist: (PlaybackTrack) -> Unit,
    val openNowPlaying: () -> Unit,
    val openQueue: () -> Unit,
    val openDiagnostics: () -> Unit,
    val openLyrics: () -> Unit,
    val popBack: () -> Unit,
)

@Stable
internal data class RootShellUiState(
    val libraryState: LibrarySnapshot,
    val librarySearchQuery: String,
    val playlistsState: PlaylistsUiSnapshot,
    val favoriteTrackUris: Set<String>,
    val playbackState: PlaybackControllerState,
)

@Composable
internal fun BitPerfectLibraryDestination(
    viewModel: MainViewModel,
    visualAssets: MainUiVisualAssets,
    shellState: RootShellUiState,
    actions: RootNavigationActions,
    rootPagerNavigator: RootPagerNavigator,
    initialLibraryTab: LibraryTab,
) {
    val dynamicCoverThemeEnabled by viewModel.dynamicCoverThemeEnabled.collectAsStateWithLifecycle()
    val dynamicThemeIntensity by viewModel.dynamicThemeIntensity.collectAsStateWithLifecycle()
    val artistPhotoRevision by viewModel.artistPhotoRevision.collectAsStateWithLifecycle()
    LibraryScreen(
        libraryState = shellState.libraryState,
        favoriteTrackUris = shellState.favoriteTrackUris,
        playbackState = shellState.playbackState,
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
        onAddToPlaylist = actions.openAddToPlaylist,
        onShowTrackInfo = viewModel.library::openTrackInfo,
        onToggleFavorite = { track -> viewModel.library.toggleFavorite(track) },
        onToggleFavoriteFromFavorites = { track ->
            viewModel.library.toggleFavorite(track, showRemovalUndoSnackbar = true)
        },
        onOpenDiagnostics = actions.openDiagnostics,
        onOpenQueue = actions.openQueue,
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
        externalSearchQuery = shellState.librarySearchQuery,
        onExternalSearchQueryChange = viewModel.library::updateSearchQuery,
    )
}

@Composable
internal fun BitPerfectPlaylistsDestination(
    viewModel: MainViewModel,
    visualAssets: MainUiVisualAssets,
    shellState: RootShellUiState,
    actions: RootNavigationActions,
    rootPagerNavigator: RootPagerNavigator,
) {
    val dynamicCoverThemeEnabled by viewModel.dynamicCoverThemeEnabled.collectAsStateWithLifecycle()
    val dynamicThemeIntensity by viewModel.dynamicThemeIntensity.collectAsStateWithLifecycle()
    PlaylistsScreen(
        playlistsState = shellState.playlistsState,
        activeTrackUri = shellState.playbackState.activeTrack?.uriString,
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
        onOpenQueue = actions.openQueue,
        favoriteTrackUris = shellState.favoriteTrackUris,
        favoriteFingerprints = shellState.libraryState.favoriteFingerprints,
        onShuffleTracks = { tracks, context -> viewModel.playTracks(tracks, context = context, shuffled = true) },
        onToggleFavorite = { track -> viewModel.library.toggleFavorite(track) },
        onEnqueueTrack = viewModel::enqueueTrack,
        onEnqueueNext = viewModel::enqueueTrackNext,
        onAddToPlaylist = actions.openAddToPlaylist,
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
}

@Composable
internal fun BitPerfectNowPlayingDestination(
    viewModel: MainViewModel,
    visualAssets: MainUiVisualAssets,
    shellState: RootShellUiState,
    actions: RootNavigationActions,
    isPageActive: Boolean,
    onBack: () -> Unit,
) {
    val nowPlayingArtworkStyle by viewModel.nowPlayingArtworkStyle.collectAsStateWithLifecycle()
    val spectrumVisualizerEnabled by viewModel.spectrumVisualizerEnabled.collectAsStateWithLifecycle()
    val spectrumColorStyle by viewModel.spectrumColorStyle.collectAsStateWithLifecycle()
    val spectrumSparksEnabled by viewModel.spectrumSparksEnabled.collectAsStateWithLifecycle()
    val activeTrack = shellState.playbackState.activeTrack
    val isFav = activeTrack != null &&
        (
            activeTrack.uriString in shellState.favoriteTrackUris ||
                activeTrack.contentFingerprint?.lowercase()?.let { it in shellState.libraryState.favoriteFingerprints } == true
            )
    NowPlayingScreen(
        playbackState = shellState.playbackState,
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
        onAddToPlaylist = actions.openAddToPlaylist,
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
        onOpenQueue = actions.openQueue,
        onOpenLyrics = actions.openLyrics,
        onOpenDiagnostics = actions.openDiagnostics,
        isPageActive = isPageActive,
        coverThemeColorCache = visualAssets.coverThemeColorCache,
        onBackCoverVisibilityChanged = { visible ->
            viewModel.dynamicTheme.setNowPlayingBackCoverVisible(visible, activeTrack?.uriString)
        },
    )
}

@Composable
internal fun BitPerfectQueueDestination(
    viewModel: MainViewModel,
    visualAssets: MainUiVisualAssets,
    shellState: RootShellUiState,
    onBack: () -> Unit,
) {
    val libraryState = shellState.libraryState
    val playlistsState = shellState.playlistsState
    val playbackState = shellState.playbackState
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
internal fun BitPerfectSettingsDestination(
    viewModel: MainViewModel,
    shellState: RootShellUiState,
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
        libraryState = shellState.libraryState,
        playbackState = shellState.playbackState,
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
