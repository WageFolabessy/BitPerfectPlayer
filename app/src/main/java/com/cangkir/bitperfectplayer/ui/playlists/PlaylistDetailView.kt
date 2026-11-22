package com.cangkir.bitperfectplayer.ui.playlists

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.cangkir.bitperfectplayer.library.AlbumArtworkLoader
import com.cangkir.bitperfectplayer.library.ResolvedPlaylist
import com.cangkir.bitperfectplayer.library.ResolvedPlaylistEntry
import com.cangkir.bitperfectplayer.ui.InlineSortTriggerButton
import com.cangkir.bitperfectplayer.ui.TrackArtworkThumbnail
import com.cangkir.bitperfectplayer.ui.theme.TextMuted

@Composable
internal fun PlaylistDetailView(
    playlist: ResolvedPlaylist,
    sortLabel: String,
    scrollResetKey: Any,
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
            Button(
                onClick = {
                    if (playlist.tracks.isNotEmpty()) {
                        onPlayTracks(playlist.tracks, 0)
                    }
                },
                enabled = playlist.tracks.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
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

        val listState = rememberLazyListState()
        var previousResetKey by remember { mutableStateOf(scrollResetKey) }
        LaunchedEffect(scrollResetKey) {
            if (previousResetKey != scrollResetKey) {
                previousResetKey = scrollResetKey
                listState.scrollToItem(0)
            }
        }

        LazyColumn(
            state = listState,
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
internal fun PlaylistEntryRow(
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
