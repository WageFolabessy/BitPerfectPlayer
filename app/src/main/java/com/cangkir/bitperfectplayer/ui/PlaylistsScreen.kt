package com.cangkir.bitperfectplayer.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cangkir.bitperfectplayer.audio.BrowsingQueueContext
import com.cangkir.bitperfectplayer.audio.PlaybackTrack
import com.cangkir.bitperfectplayer.audio.QueueContext
import com.cangkir.bitperfectplayer.library.AlbumArtworkLoader
import com.cangkir.bitperfectplayer.library.M3u8PlaylistCodec
import com.cangkir.bitperfectplayer.library.MostPlayedTrackItem
import com.cangkir.bitperfectplayer.library.PlaylistsUiSnapshot
import com.cangkir.bitperfectplayer.library.ResolvedPlaylist
import com.cangkir.bitperfectplayer.library.ResolvedPlaylistEntry
import com.cangkir.bitperfectplayer.library.SortDirection
import com.cangkir.bitperfectplayer.library.TrackSortOption
import com.cangkir.bitperfectplayer.library.TrackSortScope
import com.cangkir.bitperfectplayer.library.TrackSortSpec
import com.cangkir.bitperfectplayer.ui.theme.CoralWarning
import com.cangkir.bitperfectplayer.ui.theme.TextMuted

private const val MOST_PLAYED_PREVIEW_LIMIT = 5

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
                            else ->
                                "${playlistsState.playlists.size} ${if (playlistsState.playlists.size == 1) "playlist" else "playlists"} · ${playlistsState.totalPlays} total ${if (playlistsState.totalPlays == 1) "play" else "plays"}"
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

@Composable
private fun PlaylistsOverviewList(
    playlistsState: PlaylistsUiSnapshot,
    activeTrackUri: String?,
    albumArtworkLoader: AlbumArtworkLoader,
    onOpenMostPlayedAll: () -> Unit,
    onPlayTracks: (List<PlaybackTrack>, Int, QueueContext) -> Unit,
    onOpenPlaylist: (ResolvedPlaylist) -> Unit,
    onCreatePlaylistClick: () -> Unit,
    onImportM3u8Click: () -> Unit,
    onRenamePlaylistClick: (ResolvedPlaylist) -> Unit,
    onExportPlaylistClick: (ResolvedPlaylist) -> Unit,
    onDeletePlaylistClick: (ResolvedPlaylist) -> Unit,
) {
    val mostPlayedTracks = remember(playlistsState.mostPlayed) {
        playlistsState.mostPlayed.map { it.track }
    }
    val mostPlayedPreview = remember(playlistsState.mostPlayed) {
        playlistsState.mostPlayed.take(MOST_PLAYED_PREVIEW_LIMIT)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        item(key = "most_played_header", contentType = "section_header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "MOST PLAYED",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                )
                if (mostPlayedTracks.isNotEmpty()) {
                    TextButton(
                        onClick = { onPlayTracks(mostPlayedTracks, 0, QueueContext.mostPlayed()) },
                        modifier = Modifier.sizeIn(minHeight = 48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Play All", maxLines = 1, softWrap = false)
                    }
                } else {
                    Text(
                        text = "${playlistsState.totalPlays} plays",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
            }
        }

        if (mostPlayedPreview.isEmpty()) {
            item(key = "most_played_empty", contentType = "empty_hint") {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
                ) {
                    Text(
                        text = "No qualified plays yet. Songs played for at least 30s (or half duration for shorter tracks) are counted automatically.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    )
                }
            }
        } else {
            itemsIndexed(
                items = mostPlayedPreview,
                key = { _, item -> "mp_preview_${item.track.uriString}" },
                contentType = { _, _ -> "most_played_row" },
            ) { index, item ->
                MostPlayedTrackRow(
                    rank = index + 1,
                    item = item,
                    isActive = item.track.uriString == activeTrackUri,
                    albumArtworkLoader = albumArtworkLoader,
                    onClick = { onPlayTracks(mostPlayedTracks, index, QueueContext.mostPlayed()) },
                )
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                    modifier = Modifier.padding(start = 72.dp, end = 16.dp),
                )
            }

            if (playlistsState.mostPlayed.size > MOST_PLAYED_PREVIEW_LIMIT) {
                item(key = "most_played_view_all", contentType = "action_row") {
                    TextButton(
                        onClick = onOpenMostPlayedAll,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .sizeIn(minHeight = 48.dp),
                    ) {
                        Text(
                            text = "View all ${playlistsState.mostPlayed.size} most played tracks",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }

        item(key = "section_divider", contentType = "divider") {
            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
            )
        }

        item(key = "playlists_header", contentType = "section_header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "YOUR PLAYLISTS",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                )
                Text(
                    text = "${playlistsState.playlists.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }

        if (playlistsState.playlists.isEmpty()) {
            item(key = "playlists_empty", contentType = "empty_state") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.PlaylistPlay,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(40.dp),
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "No playlists yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Create a local playlist or import an offline .m3u8 file.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = onCreatePlaylistClick,
                            modifier = Modifier.sizeIn(minHeight = 48.dp),
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Create Playlist", maxLines = 1, softWrap = false)
                        }
                        OutlinedButton(
                            onClick = onImportM3u8Click,
                            modifier = Modifier.sizeIn(minHeight = 48.dp),
                        ) {
                            Icon(Icons.Filled.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Import .m3u8", maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        } else {
            items(
                items = playlistsState.playlists,
                key = { "pl_${it.id}" },
                contentType = { "playlist_row" },
            ) { playlist ->
                PlaylistSummaryRow(
                    playlist = playlist,
                    albumArtworkLoader = albumArtworkLoader,
                    onOpen = { onOpenPlaylist(playlist) },
                    onPlayAll = {
                        if (playlist.tracks.isNotEmpty()) {
                            onPlayTracks(
                                playlist.tracks,
                                0,
                                QueueContext.playlist(playlist.id, playlist.name),
                            )
                        }
                    },
                    onRename = { onRenamePlaylistClick(playlist) },
                    onExport = { onExportPlaylistClick(playlist) },
                    onDelete = { onDeletePlaylistClick(playlist) },
                )
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                    modifier = Modifier.padding(start = 76.dp, end = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun MostPlayedTrackRow(
    rank: Int,
    item: MostPlayedTrackItem,
    isActive: Boolean,
    albumArtworkLoader: AlbumArtworkLoader,
    onClick: () -> Unit,
) {
    val track = item.track
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isActive) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$rank",
            style = MaterialTheme.typography.labelSmall,
            color = if (isActive) MaterialTheme.colorScheme.primary else TextMuted,
            modifier = Modifier.width(20.dp),
            maxLines = 1,
            softWrap = false,
        )
        TrackArtworkThumbnail(
            track = track,
            artworkLoader = albumArtworkLoader,
            size = 42.dp,
            cornerRadius = 8.dp,
            targetSizePx = AlbumArtworkLoader.THUMBNAIL_SIZE_PX,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
                color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(1.dp))
            Text(
                text = track.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "${item.playCount} ${if (item.playCount == 1) "play" else "plays"}",
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun MostPlayedDetailView(
    items: List<MostPlayedTrackItem>,
    activeTrackUri: String?,
    albumArtworkLoader: AlbumArtworkLoader,
    onPlayTracks: (List<PlaybackTrack>, Int) -> Unit,
    onEnqueueAllNext: (List<PlaybackTrack>) -> Unit,
    onEnqueueAll: (List<PlaybackTrack>) -> Unit,
) {
    val tracks = remember(items) { items.map { it.track } }
    var menuExpanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "${items.size} ranked ${if (items.size == 1) "track" else "tracks"}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (tracks.isNotEmpty()) {
                FilledTonalButton(
                    onClick = { onPlayTracks(tracks, 0) },
                    modifier = Modifier.sizeIn(minHeight = 48.dp),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Play All", maxLines = 1, softWrap = false)
                }
                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = "Most Played queue actions",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (menuExpanded) {
                        DropdownMenu(
                            expanded = true,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Play Next") },
                                onClick = {
                                    menuExpanded = false
                                    onEnqueueAllNext(tracks)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Add All to Queue") },
                                onClick = {
                                    menuExpanded = false
                                    onEnqueueAll(tracks)
                                },
                            )
                        }
                    }
                }
            }
        }
        HorizontalDivider(
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            itemsIndexed(
                items = items,
                key = { _, item -> "mp_full_${item.track.uriString}" },
                contentType = { _, _ -> "most_played_row" },
            ) { index, item ->
                MostPlayedTrackRow(
                    rank = index + 1,
                    item = item,
                    isActive = item.track.uriString == activeTrackUri,
                    albumArtworkLoader = albumArtworkLoader,
                    onClick = { onPlayTracks(tracks, index) },
                )
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                    modifier = Modifier.padding(start = 72.dp, end = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun PlaylistSummaryRow(
    playlist: ResolvedPlaylist,
    albumArtworkLoader: AlbumArtworkLoader,
    onOpen: () -> Unit,
    onPlayAll: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TrackArtworkThumbnail(
            track = playlist.tracks.firstOrNull(),
            artworkLoader = albumArtworkLoader,
            size = 48.dp,
            cornerRadius = 8.dp,
            placeholderIcon = Icons.AutoMirrored.Filled.PlaylistPlay,
            contentDescriptionText = playlist.name,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = playlist.name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "${playlist.trackCount} ${if (playlist.trackCount == 1) "track" else "tracks"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(
            onClick = onPlayAll,
            enabled = playlist.tracks.isNotEmpty(),
            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = "Play ${playlist.name}",
                tint = if (playlist.tracks.isNotEmpty()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    TextMuted.copy(alpha = 0.4f)
                },
            )
        }
        Box {
            IconButton(
                onClick = { menuExpanded = true },
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = "Playlist options for ${playlist.name}",
                    tint = TextMuted,
                    modifier = Modifier.size(20.dp),
                )
            }
            if (menuExpanded) {
                DropdownMenu(
                    expanded = true,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        onClick = {
                            menuExpanded = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Export .m3u8") },
                        onClick = {
                            menuExpanded = false
                            onExport()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete", color = CoralWarning) },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaylistDetailView(
    playlist: ResolvedPlaylist,
    sortLabel: String,
    canReorderManually: Boolean,
    activeTrackUri: String?,
    albumArtworkLoader: AlbumArtworkLoader,
    onOpenSort: () -> Unit,
    onPlayTracks: (List<PlaybackTrack>, Int) -> Unit,
    onEnqueueAllNext: () -> Unit,
    onEnqueueAll: () -> Unit,
    onMoveEntry: (Int, Int) -> Unit,
    onRemoveEntry: (Long) -> Unit,
    onExport: () -> Unit,
) {
    var queueMenuExpanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 6.dp, top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "${playlist.trackCount} ${if (playlist.trackCount == 1) "track" else "tracks"}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            InlineSortTriggerButton(
                label = sortLabel,
                onClick = onOpenSort,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 6.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalButton(
                onClick = {
                    if (playlist.tracks.isNotEmpty()) {
                        onPlayTracks(playlist.tracks, 0)
                    }
                },
                enabled = playlist.tracks.isNotEmpty(),
                modifier = Modifier
                    .weight(1f)
                    .sizeIn(minHeight = 48.dp),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Play All", maxLines = 1, softWrap = false)
            }
            OutlinedButton(
                onClick = onExport,
                modifier = Modifier
                    .weight(1f)
                    .sizeIn(minHeight = 48.dp),
            ) {
                Icon(Icons.Filled.SaveAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Export .m3u8", maxLines = 1, softWrap = false)
            }
            Box {
                IconButton(
                    onClick = { queueMenuExpanded = true },
                    enabled = playlist.tracks.isNotEmpty(),
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = "Queue options for ${playlist.name}",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (queueMenuExpanded) {
                    DropdownMenu(
                        expanded = true,
                        onDismissRequest = { queueMenuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Play Next") },
                            onClick = {
                                queueMenuExpanded = false
                                onEnqueueAllNext()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Add All to Queue") },
                            onClick = {
                                queueMenuExpanded = false
                                onEnqueueAll()
                            },
                        )
                    }
                }
            }
        }

        HorizontalDivider(
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
        )

        if (playlist.entries.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(28.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                    contentDescription = null,
                    tint = TextMuted,
                    modifier = Modifier.size(40.dp),
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Playlist is empty",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Add tracks from the 3-dot menu in Library or from Now Playing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            return
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            itemsIndexed(
                items = playlist.entries,
                key = { _, entry -> entry.entryId },
                contentType = { _, _ -> "playlist_detail_row" },
            ) { index, entry ->
                PlaylistEntryRow(
                    entry = entry,
                    index = index,
                    totalCount = playlist.entries.size,
                    canReorderManually = canReorderManually,
                    isActive = entry.track.uriString == activeTrackUri,
                    albumArtworkLoader = albumArtworkLoader,
                    onPlay = { onPlayTracks(playlist.tracks, index) },
                    onMoveUp = { if (index > 0) onMoveEntry(index, index - 1) },
                    onMoveDown = { if (index < playlist.entries.lastIndex) onMoveEntry(index, index + 1) },
                    onRemove = { onRemoveEntry(entry.entryId) },
                )
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                    modifier = Modifier.padding(start = 72.dp, end = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun PlaylistEntryRow(
    entry: ResolvedPlaylistEntry,
    index: Int,
    totalCount: Int,
    canReorderManually: Boolean,
    isActive: Boolean,
    albumArtworkLoader: AlbumArtworkLoader,
    onPlay: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    val track = entry.track
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isActive) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
            .clickable(onClick = onPlay)
            .padding(start = 16.dp, end = 2.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TrackArtworkThumbnail(
            track = track,
            artworkLoader = albumArtworkLoader,
            size = 44.dp,
            cornerRadius = 8.dp,
            targetSizePx = AlbumArtworkLoader.THUMBNAIL_SIZE_PX,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
                color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "${track.artist} · ${track.formatBadge}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (canReorderManually) {
            IconButton(
                onClick = onMoveUp,
                enabled = index > 0,
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowUp,
                    contentDescription = "Move ${track.title} up",
                    tint = if (index > 0) MaterialTheme.colorScheme.onSurfaceVariant else TextMuted.copy(alpha = 0.3f),
                )
            }
            IconButton(
                onClick = onMoveDown,
                enabled = index < totalCount - 1,
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = "Move ${track.title} down",
                    tint = if (index < totalCount - 1) MaterialTheme.colorScheme.onSurfaceVariant else TextMuted.copy(alpha = 0.3f),
                )
            }
        }
        IconButton(
            onClick = onRemove,
            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.DeleteOutline,
                contentDescription = "Remove ${track.title} from playlist",
                tint = TextMuted,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun PlaylistNameDialog(
    title: String,
    initialName: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember(initialName) { mutableStateOf(initialName) }
    val canConfirm = text.trim().isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("Playlist name") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(text.trim()) },
                enabled = canConfirm,
                modifier = Modifier.sizeIn(minHeight = 48.dp),
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.sizeIn(minHeight = 48.dp),
            ) {
                Text("Cancel")
            }
        },
    )
}

/**
 * Reusable dialog shared by LibraryScreen (3-dot menu) and NowPlayingScreen to add a track
 * to an existing playlist or create a brand-new playlist in one step.
 */
@Composable
fun AddToPlaylistDialog(
    track: PlaybackTrack,
    playlists: List<ResolvedPlaylist>,
    onSelectPlaylist: (ResolvedPlaylist) -> Unit,
    onCreateAndAdd: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var creatingNew by remember { mutableStateOf(playlists.isEmpty()) }
    var newName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        title = {
            Column {
                Text(
                    text = "Add to Playlist",
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (creatingNew) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        singleLine = true,
                        label = { Text("New playlist name") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (playlists.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        TextButton(
                            onClick = { creatingNew = false },
                            modifier = Modifier.sizeIn(minHeight = 48.dp),
                        ) {
                            Text("Choose existing playlist")
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp),
                    ) {
                        items(
                            items = playlists,
                            key = { "pick_pl_${it.id}" },
                            contentType = { "playlist_picker_row" },
                        ) { playlist ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .sizeIn(minHeight = 48.dp)
                                    .clickable { onSelectPlaylist(playlist) }
                                    .padding(horizontal = 4.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = playlist.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "${playlist.trackCount}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextMuted,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                            HorizontalDivider(
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = { creatingNew = true },
                        modifier = Modifier.sizeIn(minHeight = 48.dp),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("New Playlist")
                    }
                }
            }
        },
        confirmButton = {
            if (creatingNew) {
                Button(
                    onClick = { onCreateAndAdd(newName.trim()) },
                    enabled = newName.trim().isNotEmpty(),
                    modifier = Modifier.sizeIn(minHeight = 48.dp),
                ) {
                    Text("Create & Add")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.sizeIn(minHeight = 48.dp),
            ) {
                Text("Cancel")
            }
        },
    )
}
