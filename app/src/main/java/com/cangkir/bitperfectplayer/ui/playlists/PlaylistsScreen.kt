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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cangkir.bitperfectplayer.audio.BrowsingQueueContext
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
    onOpenQueue: (BrowsingQueueContext?) -> Unit,
    onCreatePlaylist: (String) -> Unit,
    onRenamePlaylist: (Long, String) -> Unit,
    onDeletePlaylist: (Long, String) -> Unit,
    onRemovePlaylistEntry: (Long, Long) -> Unit,
    onMovePlaylistEntry: (Long, Int, Int) -> Unit,
    onExportPlaylist: (ResolvedPlaylist, Uri) -> Unit,
    onImportPlaylist: (Uri) -> Unit,
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
                    if (openedPlaylist != null) {
                        IconButton(
                            onClick = {
                                val ctx = if (openedPlaylist.tracks.isNotEmpty()) {
                                    BrowsingQueueContext(
                                        context = QueueContext.playlist(openedPlaylist.id, openedPlaylist.name),
                                        tracks = openedPlaylist.tracks,
                                    )
                                } else {
                                    null
                                }
                                onOpenQueue(ctx)
                            },
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                                contentDescription = "Open playback queue",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
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
                    } else if (showMostPlayedDetail) {
                        IconButton(
                            onClick = {
                                val tracks = playlistsState.mostPlayed.map { it.track }
                                val ctx = if (tracks.isNotEmpty()) {
                                    BrowsingQueueContext(
                                        context = QueueContext.mostPlayed(),
                                        tracks = tracks,
                                    )
                                } else {
                                    null
                                }
                                onOpenQueue(ctx)
                            },
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                                contentDescription = "Open playback queue",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
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
                        onOpenSort = { showPlaylistSortSheet = true },
                        onPlayTracks = { tracks, index ->
                            onPlayTracks(tracks, index, playlistContext)
                        },
                        onEnqueueAllNext = { onEnqueueTracksNext(openedPlaylist.tracks, playlistContext) },
                        onEnqueueAll = { onEnqueueTracks(openedPlaylist.tracks, playlistContext) },
                        onMoveEntry = { from, to -> onMovePlaylistEntry(openedPlaylist.id, from, to) },
                        onRemoveEntry = { entryId -> onRemovePlaylistEntry(openedPlaylist.id, entryId) },
                        onExport = { launchExport(openedPlaylist) },
                    )
                }

                showMostPlayedDetail -> {
                    val mpContext = QueueContext.mostPlayed()
                    MostPlayedDetailView(
                        items = playlistsState.mostPlayed,
                        activeTrackUri = activeTrackUri,
                        albumArtworkLoader = albumArtworkLoader,
                        onPlayTracks = { tracks, index ->
                            onPlayTracks(tracks, index, mpContext)
                        },
                        onEnqueueAllNext = { tracks -> onEnqueueTracksNext(tracks, mpContext) },
                        onEnqueueAll = { tracks -> onEnqueueTracks(tracks, mpContext) },
                    )
                }

                else -> {
                    PlaylistsOverviewList(
                        playlistsState = playlistsState,
                        activeTrackUri = activeTrackUri,
                        albumArtworkLoader = albumArtworkLoader,
                        onOpenMostPlayedAll = { showMostPlayedDetail = true },
                        onPlayTracks = onPlayTracks,
                        onOpenPlaylist = { playlist -> openedPlaylistId = playlist.id },
                        onCreatePlaylistClick = { showCreateDialog = true },
                        onImportM3u8Click = launchImport,
                        onRenamePlaylistClick = { playlistPendingRename = it },
                        onExportPlaylistClick = launchExport,
                        onDeletePlaylistClick = { playlistPendingDelete = it },
                    )
                }
            }
        }
    }
}
