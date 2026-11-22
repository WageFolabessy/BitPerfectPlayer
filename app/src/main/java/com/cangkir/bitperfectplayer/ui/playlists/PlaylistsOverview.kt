package com.cangkir.bitperfectplayer.ui.playlists

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cangkir.bitperfectplayer.audio.PlaybackTrack
import com.cangkir.bitperfectplayer.audio.QueueContext
import com.cangkir.bitperfectplayer.library.AlbumArtworkLoader
import com.cangkir.bitperfectplayer.library.MostPlayedTrackItem
import com.cangkir.bitperfectplayer.library.PlaylistsUiSnapshot
import com.cangkir.bitperfectplayer.library.ResolvedPlaylist
import com.cangkir.bitperfectplayer.ui.TrackArtworkThumbnail
import com.cangkir.bitperfectplayer.ui.theme.CoralWarning
import com.cangkir.bitperfectplayer.ui.theme.TextMuted

private const val MOST_PLAYED_PREVIEW_LIMIT = 5

@Composable
internal fun PlaylistsOverviewList(
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
    val mostPlayedTracks = remember(playlistsState.mostPlayed) { playlistsState.mostPlayed.map { it.track } }
    val mostPlayedPreview = remember(playlistsState.mostPlayed) { playlistsState.mostPlayed.take(MOST_PLAYED_PREVIEW_LIMIT) }

    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item(key = "most_played_header", contentType = "section_header") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
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
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
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
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
                ) {
                    Text(
                        text = "No qualified plays yet. Songs played for at least 30s " +
                            "(or half duration for shorter tracks) are counted automatically.",
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
                    modifier = Modifier.padding(start = 90.dp, end = 16.dp),
                )
            }

            if (playlistsState.mostPlayed.size > MOST_PLAYED_PREVIEW_LIMIT) {
                item(key = "most_played_view_all", contentType = "action_row") {
                    TextButton(
                        onClick = onOpenMostPlayedAll,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).sizeIn(minHeight = 48.dp),
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
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
        }

        item(key = "playlists_header", contentType = "section_header") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
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
                Text("${playlistsState.playlists.size}", style = MaterialTheme.typography.labelSmall, color = TextMuted, maxLines = 1)
            }
        }

        if (playlistsState.playlists.isEmpty()) {
            item(key = "playlists_empty", contentType = "empty_state") {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.PlaylistPlay,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(40.dp),
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("No playlists yet", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Create a local playlist or import an offline .m3u8 file.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = onCreatePlaylistClick, modifier = Modifier.sizeIn(minHeight = 48.dp)) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Create Playlist", maxLines = 1, softWrap = false)
                        }
                        OutlinedButton(onClick = onImportM3u8Click, modifier = Modifier.sizeIn(minHeight = 48.dp)) {
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
                            onPlayTracks(playlist.tracks, 0, QueueContext.playlist(playlist.id, playlist.name))
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
internal fun MostPlayedTrackRow(
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
        TrackArtworkThumbnail(track, albumArtworkLoader, size = 42.dp, cornerRadius = 8.dp)
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
internal fun MostPlayedDetailView(
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
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
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
                Button(
                    onClick = { onPlayTracks(tracks, 0) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    modifier = Modifier.sizeIn(minHeight = 48.dp),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Play All", maxLines = 1, softWrap = false)
                }
                Box {
                    IconButton(onClick = { menuExpanded = true }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = "Most Played queue actions",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (menuExpanded) {
                        DropdownMenu(expanded = true, onDismissRequest = { menuExpanded = false }) {
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
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f))
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
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
                    modifier = Modifier.padding(start = 90.dp, end = 16.dp),
                )
            }
        }
    }
}

@Composable
internal fun PlaylistSummaryRow(
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
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
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
            Text(playlist.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "${playlist.trackCount} ${if (playlist.trackCount == 1) "track" else "tracks"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onPlayAll, enabled = playlist.tracks.isNotEmpty(), modifier = Modifier.sizeIn(48.dp, 48.dp)) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = "Play ${playlist.name}",
                tint = if (playlist.tracks.isNotEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else TextMuted.copy(alpha = 0.4f),
            )
        }
        Box {
            IconButton(onClick = { menuExpanded = true }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                Icon(Icons.Filled.MoreVert, "Playlist options for ${playlist.name}", tint = TextMuted, modifier = Modifier.size(20.dp))
            }
            if (menuExpanded) {
                DropdownMenu(expanded = true, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { onRename().also { menuExpanded = false } })
                    DropdownMenuItem(text = { Text("Export .m3u8") }, onClick = { onExport().also { menuExpanded = false } })
                    DropdownMenuItem(text = {
                        Text("Delete", color = CoralWarning)
                    }, onClick = { onDelete().also { menuExpanded = false } })
                }
            }
        }
    }
}
