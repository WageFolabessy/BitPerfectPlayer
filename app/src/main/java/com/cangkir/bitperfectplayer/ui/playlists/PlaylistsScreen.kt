package com.cangkir.bitperfectplayer.ui.playlists

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cangkir.bitperfectplayer.audio.PlaybackTrack
import com.cangkir.bitperfectplayer.audio.QueueContext
import com.cangkir.bitperfectplayer.library.AlbumArtworkLoader
import com.cangkir.bitperfectplayer.library.M3u8PlaylistCodec
import com.cangkir.bitperfectplayer.library.PlaylistsUiSnapshot
import com.cangkir.bitperfectplayer.library.ResolvedPlaylist
import com.cangkir.bitperfectplayer.library.SortDirection
import com.cangkir.bitperfectplayer.library.TrackSortOption
import com.cangkir.bitperfectplayer.library.TrackSortScope
import com.cangkir.bitperfectplayer.library.TrackSortSpec
import com.cangkir.bitperfectplayer.ui.TrackSortBottomSheet
import com.cangkir.bitperfectplayer.ui.theme.CoralWarning
import com.cangkir.bitperfectplayer.ui.theme.CoverThemeColorCache
import com.cangkir.bitperfectplayer.ui.theme.CoverThemeLookup
import com.cangkir.bitperfectplayer.ui.theme.DynamicThemeTokens
import com.cangkir.bitperfectplayer.ui.theme.ThemeArtworkCandidateSelector

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistsScreen(
    playlistsState: PlaylistsUiSnapshot,
    activeTrackUri: String?,
    albumArtworkLoader: AlbumArtworkLoader,
    onPlayTracks: (List<PlaybackTrack>, Int, QueueContext) -> Unit,
    onEnqueueTracks: (List<PlaybackTrack>, QueueContext) -> Unit,
    onEnqueueTracksNext: (List<PlaybackTrack>, QueueContext) -> Unit,
    onSetPlaylistSort: (TrackSortScope, TrackSortSpec) -> Unit,
    onCreatePlaylist: (String) -> Unit,
    onRenamePlaylist: (Long, String) -> Unit,
    onDeletePlaylist: (Long, String) -> Unit,
    onRemovePlaylistEntry: (Long, Long) -> Unit,
    onExportPlaylist: (ResolvedPlaylist, Uri) -> Unit,
    onImportPlaylist: (Uri) -> Unit,
    onOpenQueue: () -> Unit = {},
    favoriteTrackUris: Set<String> = emptySet(),
    favoriteFingerprints: Set<String> = emptySet(),
    onShuffleTracks: (List<PlaybackTrack>, QueueContext) -> Unit = { tracks, ctx -> onPlayTracks(tracks.shuffled(), 0, ctx) },
    onToggleFavorite: (PlaybackTrack) -> Unit = {},
    onEnqueueTrack: (PlaybackTrack) -> Unit = {},
    onEnqueueNext: (PlaybackTrack) -> Unit = {},
    onAddToPlaylist: (PlaybackTrack) -> Unit = {},
    onShowTrackInfo: (PlaybackTrack) -> Unit = {},
    onRemovePlaylistEntryWithTitle: ((Long, Long, String, String) -> Unit)? = null,
    onCommitPlaylistOrder: (Long, List<Long>) -> Unit = { _, _ -> },
    dynamicCoverThemeEnabled: Boolean = false,
    dynamicThemeIntensity: com.cangkir.bitperfectplayer.ui.theme.DynamicThemeIntensity =
        com.cangkir.bitperfectplayer.ui.theme.DynamicThemeIntensity.VIBRANT,
    coverThemeColorCache: CoverThemeColorCache? = null,
    isForegroundPage: Boolean = true,
    onDetailViewOpenChanged: (Boolean) -> Unit = {},
    onContextualDynamicThemeChanged: ((DynamicThemeTokens?) -> Unit)? = null,
) {
    var openedPlaylistId by rememberSaveable { mutableStateOf<Long?>(null) }
    var showMostPlayedDetail by rememberSaveable { mutableStateOf(false) }

    var showCreateDialog by remember { mutableStateOf(false) }
    var showPlaylistSortSheet by remember { mutableStateOf(false) }
    var playlistPendingRename by remember { mutableStateOf<ResolvedPlaylist?>(null) }
    var playlistPendingDelete by remember { mutableStateOf<ResolvedPlaylist?>(null) }
    var playlistPendingExport by remember { mutableStateOf<ResolvedPlaylist?>(null) }

    val openedPlaylist = remember(playlistsState.playlists, openedPlaylistId) {
        openedPlaylistId?.let { id -> playlistsState.playlists.firstOrNull { it.id == id } }
    }

    val isDetailOpen = openedPlaylist != null || showMostPlayedDetail
    val latestOnDetailOpenChanged by rememberUpdatedState(onDetailViewOpenChanged)
    LaunchedEffect(isForegroundPage, isDetailOpen) {
        if (isForegroundPage) {
            latestOnDetailOpenChanged(isDetailOpen)
        }
    }
    DisposableEffect(Unit) {
        onDispose { latestOnDetailOpenChanged(false) }
    }

    val latestOnThemeChanged by rememberUpdatedState(onContextualDynamicThemeChanged)
    val playlistThemeCandidates = remember(openedPlaylist) {
        openedPlaylist?.let { ThemeArtworkCandidateSelector.selectStableCandidates(it.tracks) }.orEmpty()
    }
    LaunchedEffect(
        dynamicCoverThemeEnabled,
        dynamicThemeIntensity,
        isForegroundPage,
        openedPlaylist?.id,
        playlistThemeCandidates,
        coverThemeColorCache,
    ) {
        val cb = latestOnThemeChanged ?: return@LaunchedEffect
        val targetPlaylist = openedPlaylist?.takeIf { dynamicCoverThemeEnabled && isForegroundPage }
        if (targetPlaylist == null || coverThemeColorCache == null) {
            cb(null)
            return@LaunchedEffect
        }
        val peeked = playlistThemeCandidates.firstNotNullOfOrNull {
            (coverThemeColorCache.peekCached(it, intensity = dynamicThemeIntensity) as? CoverThemeLookup.Resolved)?.tokens
        }
        if (peeked != null) {
            cb(peeked)
        }
        val resolved = coverThemeColorCache.resolveFirstChromaticTokens(
            candidates = playlistThemeCandidates,
            intensity = dynamicThemeIntensity,
        )
        cb(resolved)
    }
    DisposableEffect(Unit) {
        onDispose { latestOnThemeChanged?.invoke(null) }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("audio/x-mpegurl"),
    ) { destinationUri ->
        val target = playlistPendingExport
        playlistPendingExport = null
        if (destinationUri != null && target != null) {
            onExportPlaylist(target, destinationUri)
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { sourceUri ->
        if (sourceUri != null) {
            onImportPlaylist(sourceUri)
        }
    }

    val launchExport = remember(exportLauncher) {
        { playlist: ResolvedPlaylist ->
            playlistPendingExport = playlist
            exportLauncher.launch(M3u8PlaylistCodec.suggestExportFileName(playlist.name))
        }
    }

    // Use wildcard MIME so HyperOS / Android SAF always allows picking .m3u8 and .m3u files.
    val launchImport = remember(importLauncher) {
        { importLauncher.launch(arrayOf("*/*")) }
    }

    BackHandler(enabled = openedPlaylist != null || showMostPlayedDetail) {
        if (openedPlaylist != null) {
            openedPlaylistId = null
        } else {
            showMostPlayedDetail = false
        }
    }

    if (showPlaylistSortSheet) {
        TrackSortBottomSheet(
            scope = TrackSortScope.PLAYLIST_DETAIL,
            currentSpec = playlistsState.playlistDetailSort,
            onSelectSpec = { spec -> onSetPlaylistSort(TrackSortScope.PLAYLIST_DETAIL, spec) },
            onDismiss = { showPlaylistSortSheet = false },
        )
    }

    if (showCreateDialog) {
        PlaylistNameDialog(
            title = "New Playlist",
            initialName = "",
            confirmLabel = "Create",
            onConfirm = { name ->
                showCreateDialog = false
                onCreatePlaylist(name)
            },
            onDismiss = { showCreateDialog = false },
        )
    }

    playlistPendingRename?.let { target ->
        PlaylistNameDialog(
            title = "Rename Playlist",
            initialName = target.name,
            confirmLabel = "Save",
            onConfirm = { newName ->
                playlistPendingRename = null
                onRenamePlaylist(target.id, newName)
            },
            onDismiss = { playlistPendingRename = null },
        )
    }

    playlistPendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { playlistPendingDelete = null },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text("Delete Playlist") },
            text = {
                Text(
                    text = "Delete \"${target.name}\"? Your local audio files will not be affected.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        playlistPendingDelete = null
                        if (openedPlaylistId == target.id) {
                            openedPlaylistId = null
                        }
                        onDeletePlaylist(target.id, target.name)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CoralWarning,
                        contentColor = MaterialTheme.colorScheme.background,
                    ),
                    modifier = Modifier.sizeIn(minHeight = 48.dp),
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { playlistPendingDelete = null },
                    modifier = Modifier.sizeIn(minHeight = 48.dp),
                ) {
                    Text("Cancel")
                }
            },
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets.only(
            WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
        ),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = when {
                                openedPlaylist != null -> openedPlaylist.name
                                showMostPlayedDetail -> "Most Played"
                                else -> "Playlists"
                            },
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val subtitle = when {
                            openedPlaylist != null ->
                                "${openedPlaylist.trackCount} ${if (openedPlaylist.trackCount == 1) "track" else "tracks"}"
                            showMostPlayedDetail ->
                                "${playlistsState.totalPlays} total ${if (playlistsState.totalPlays == 1) "play" else "plays"}"
                            else -> {
                                val listUnit = if (playlistsState.playlists.size == 1) "playlist" else "playlists"
                                val playUnit = if (playlistsState.totalPlays == 1) "play" else "plays"
                                "${playlistsState.playlists.size} $listUnit · ${playlistsState.totalPlays} total $playUnit"
                            }
                        }
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    if (openedPlaylist != null || showMostPlayedDetail) {
                        IconButton(
                            onClick = {
                                if (openedPlaylist != null) {
                                    openedPlaylistId = null
                                } else {
                                    showMostPlayedDetail = false
                                }
                            },
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back to Playlists",
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = onOpenQueue,
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                            contentDescription = "Open playback queue",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (openedPlaylist != null) {
                        IconButton(
                            onClick = { launchExport(openedPlaylist) },
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.SaveAlt,
                                contentDescription = "Export ${openedPlaylist.name} as .m3u8",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else if (!showMostPlayedDetail) {
                        IconButton(
                            onClick = launchImport,
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.FileOpen,
                                contentDescription = "Import .m3u8 playlist",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(
                            onClick = { showCreateDialog = true },
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = "Create new playlist",
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when {
                openedPlaylist != null -> {
                    val playlistContext = QueueContext.playlist(openedPlaylist.id, openedPlaylist.name)
                    val canReorderManually = playlistsState.playlistDetailSort.option == TrackSortOption.CUSTOM_ORDER &&
                        playlistsState.playlistDetailSort.direction == SortDirection.ASC
                    PlaylistDetailView(
                        playlist = openedPlaylist,
                        sortLabel = playlistsState.playlistDetailSort.compactLabel,
                        scrollResetKey = playlistsState.playlistDetailSort,
                        canReorderManually = canReorderManually,
                        activeTrackUri = activeTrackUri,
                        albumArtworkLoader = albumArtworkLoader,
                        favoriteTrackUris = favoriteTrackUris,
                        favoriteFingerprints = favoriteFingerprints,
                        onOpenSort = { showPlaylistSortSheet = true },
                        onPlayTracks = { tracks, index ->
                            onPlayTracks(tracks, index, playlistContext)
                        },
                        onShuffleTracks = { tracks -> onShuffleTracks(tracks, playlistContext) },
                        onEnqueueAllNext = { onEnqueueTracksNext(openedPlaylist.tracks, playlistContext) },
                        onEnqueueAll = { onEnqueueTracks(openedPlaylist.tracks, playlistContext) },
                        onCommitOrder = { orderedIds ->
                            onCommitPlaylistOrder(openedPlaylist.id, orderedIds)
                        },
                        onRemoveEntry = { entry ->
                            if (onRemovePlaylistEntryWithTitle != null) {
                                onRemovePlaylistEntryWithTitle(
                                    openedPlaylist.id,
                                    entry.entryId,
                                    openedPlaylist.name,
                                    entry.track.title,
                                )
                            } else {
                                onRemovePlaylistEntry(openedPlaylist.id, entry.entryId)
                            }
                        },
                        onExport = { launchExport(openedPlaylist) },
                        onToggleFavorite = onToggleFavorite,
                        onEnqueueNext = onEnqueueNext,
                        onEnqueueTrack = onEnqueueTrack,
                        onAddToPlaylist = onAddToPlaylist,
                        onShowTrackInfo = onShowTrackInfo,
                    )
                }

                showMostPlayedDetail -> {
                    val mpContext = QueueContext.mostPlayed()
                    MostPlayedDetailView(
                        items = playlistsState.mostPlayed,
                        activeTrackUri = activeTrackUri,
                        albumArtworkLoader = albumArtworkLoader,
                        favoriteTrackUris = favoriteTrackUris,
                        favoriteFingerprints = favoriteFingerprints,
                        onPlayTracks = { tracks, index ->
                            onPlayTracks(tracks, index, mpContext)
                        },
                        onShuffleTracks = { tracks -> onShuffleTracks(tracks, mpContext) },
                        onEnqueueAllNext = { tracks -> onEnqueueTracksNext(tracks, mpContext) },
                        onEnqueueAll = { tracks -> onEnqueueTracks(tracks, mpContext) },
                        onToggleFavorite = onToggleFavorite,
                        onEnqueueNext = onEnqueueNext,
                        onEnqueueTrack = onEnqueueTrack,
                        onAddToPlaylist = onAddToPlaylist,
                        onShowTrackInfo = onShowTrackInfo,
                    )
                }

                else -> {
                    PlaylistsOverviewList(
                        playlistsState = playlistsState,
                        activeTrackUri = activeTrackUri,
                        albumArtworkLoader = albumArtworkLoader,
                        favoriteTrackUris = favoriteTrackUris,
                        favoriteFingerprints = favoriteFingerprints,
                        onOpenMostPlayedAll = { showMostPlayedDetail = true },
                        onPlayTracks = onPlayTracks,
                        onOpenPlaylist = { playlist -> openedPlaylistId = playlist.id },
                        onCreatePlaylistClick = { showCreateDialog = true },
                        onImportM3u8Click = launchImport,
                        onRenamePlaylistClick = { playlistPendingRename = it },
                        onExportPlaylistClick = launchExport,
                        onDeletePlaylistClick = { playlistPendingDelete = it },
                        onToggleFavorite = onToggleFavorite,
                        onEnqueueNext = onEnqueueNext,
                        onEnqueueTrack = onEnqueueTrack,
                        onAddToPlaylist = onAddToPlaylist,
                        onShowTrackInfo = onShowTrackInfo,
                    )
                }
            }
        }
    }
}
