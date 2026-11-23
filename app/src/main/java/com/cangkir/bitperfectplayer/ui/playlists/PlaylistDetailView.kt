package com.cangkir.bitperfectplayer.ui.playlists

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.cangkir.bitperfectplayer.audio.PlaybackTrack
import com.cangkir.bitperfectplayer.library.AlbumArtworkLoader
import com.cangkir.bitperfectplayer.library.ResolvedPlaylist
import com.cangkir.bitperfectplayer.library.ResolvedPlaylistEntry
import com.cangkir.bitperfectplayer.ui.DetailActionHeaderRow
import com.cangkir.bitperfectplayer.ui.DragReorderLifecycleEffects
import com.cangkir.bitperfectplayer.ui.DragReorderState
import com.cangkir.bitperfectplayer.ui.DraggedRowShape
import com.cangkir.bitperfectplayer.ui.InlineSortTriggerButton
import com.cangkir.bitperfectplayer.ui.TrackArtworkThumbnail
import com.cangkir.bitperfectplayer.ui.dragHandleReorderPointer
import com.cangkir.bitperfectplayer.ui.theme.CoralWarning
import com.cangkir.bitperfectplayer.ui.theme.TextMuted

@Composable
internal fun PlaylistDetailView(
    playlist: ResolvedPlaylist,
    sortLabel: String,
    scrollResetKey: Any,
    canReorderManually: Boolean,
    activeTrackUri: String?,
    albumArtworkLoader: AlbumArtworkLoader,
    favoriteTrackUris: Set<String> = emptySet(),
    favoriteFingerprints: Set<String> = emptySet(),
    onOpenSort: () -> Unit,
    onPlayTracks: (List<PlaybackTrack>, Int) -> Unit,
    onShuffleTracks: (List<PlaybackTrack>) -> Unit = { tracks -> onPlayTracks(tracks.shuffled(), 0) },
    onEnqueueAllNext: () -> Unit,
    onEnqueueAll: () -> Unit,
    onCommitOrder: (List<Long>) -> Unit = {},
    onRemoveEntry: (ResolvedPlaylistEntry) -> Unit,
    onExport: () -> Unit,
    onToggleFavorite: (PlaybackTrack) -> Unit = {},
    onEnqueueNext: (PlaybackTrack) -> Unit = {},
    onEnqueueTrack: (PlaybackTrack) -> Unit = {},
    onAddToPlaylist: (PlaybackTrack) -> Unit = {},
    onShowTrackInfo: (PlaybackTrack) -> Unit = {},
) {
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
        DetailActionHeaderRow(
            hasTracks = playlist.tracks.isNotEmpty(),
            onPlayAll = {
                if (playlist.tracks.isNotEmpty()) {
                    onPlayTracks(playlist.tracks, 0)
                }
            },
            onShuffle = {
                if (playlist.tracks.isNotEmpty()) {
                    onShuffleTracks(playlist.tracks)
                }
            },
            overflowContentDescription = "Queue options for ${playlist.name}",
        ) { dismiss ->
            DropdownMenuItem(
                text = { Text("Play Next") },
                onClick = {
                    dismiss()
                    onEnqueueAllNext()
                },
            )
            DropdownMenuItem(
                text = { Text("Add All to Queue") },
                onClick = {
                    dismiss()
                    onEnqueueAll()
                },
            )
            DropdownMenuItem(
                text = { Text("Export .m3u8") },
                onClick = {
                    dismiss()
                    onExport()
                },
            )
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
        val dragState = remember(listState) { DragReorderState(listState) }
        val dragDistanceProvider = remember(dragState) { { dragState.draggedDistanceY } }

        // Keep swaps purely in memory during drag so SQLite only commits once on finger release.
        var displayedEntries by remember(playlist.id) { mutableStateOf(playlist.entries) }
        LaunchedEffect(playlist.entries, dragState.isDragging) {
            if (!dragState.isDragging) {
                displayedEntries = playlist.entries
            }
        }

        var previousResetKey by remember { mutableStateOf(scrollResetKey) }
        LaunchedEffect(scrollResetKey) {
            if (previousResetKey != scrollResetKey) {
                previousResetKey = scrollResetKey
                dragState.reset()
                listState.scrollToItem(0)
            }
        }

        val showDragHandles = canReorderManually && displayedEntries.size > 1
        val rowKeys: List<Any> = remember(displayedEntries) { displayedEntries.map { it.entryId } }
        SideEffect {
            dragState.onMove = { from, to ->
                val mutable = displayedEntries.toMutableList()
                if (from in mutable.indices && to in mutable.indices && from != to) {
                    val item = mutable.removeAt(from)
                    mutable.add(to, item)
                    displayedEntries = mutable
                }
            }
            dragState.onDragEnded = { hadMove ->
                if (hadMove) {
                    onCommitOrder(displayedEntries.map { it.entryId })
                }
            }
        }

        DragReorderLifecycleEffects(
            dragState = dragState,
            rowKeys = rowKeys,
            itemCount = displayedEntries.size,
        )

        val displayedTracks = remember(displayedEntries) { displayedEntries.map { it.track } }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .dragHandleReorderPointer(
                    dragState = dragState,
                    enabled = showDragHandles,
                ),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            itemsIndexed(
                items = displayedEntries,
                key = { _, entry -> entry.entryId },
                contentType = { _, _ -> "playlist_detail_row" },
            ) { index, entry ->
                val isDragged = dragState.draggedItemKey == entry.entryId
                val isFav = entry.track.uriString in favoriteTrackUris ||
                    entry.track.contentFingerprint?.lowercase()?.let { it in favoriteFingerprints } == true
                PlaylistEntryRow(
                    entry = entry,
                    playlistName = playlist.name,
                    showDragHandle = showDragHandles,
                    isDragged = isDragged,
                    isAnyRowDragging = dragState.isDragging,
                    dragDistanceProvider = dragDistanceProvider,
                    isActive = entry.track.uriString == activeTrackUri,
                    isFavorite = isFav,
                    albumArtworkLoader = albumArtworkLoader,
                    onPlay = { onPlayTracks(displayedTracks, index) },
                    onRemove = { onRemoveEntry(entry) },
                    onToggleFavorite = { onToggleFavorite(entry.track) },
                    onAddToPlaylist = { onAddToPlaylist(entry.track) },
                    onEnqueueNext = { onEnqueueNext(entry.track) },
                    onEnqueueTrack = { onEnqueueTrack(entry.track) },
                    onShowTrackInfo = { onShowTrackInfo(entry.track) },
                )
                if (!isDragged) {
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                        modifier = Modifier.padding(
                            start = if (showDragHandles) 112.dp else 76.dp,
                            end = 16.dp,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
internal fun PlaylistEntryRow(
    entry: ResolvedPlaylistEntry,
    playlistName: String,
    showDragHandle: Boolean,
    isDragged: Boolean,
    isAnyRowDragging: Boolean,
    dragDistanceProvider: () -> Float,
    isActive: Boolean,
    isFavorite: Boolean,
    albumArtworkLoader: AlbumArtworkLoader,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
    onToggleFavorite: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onEnqueueNext: () -> Unit,
    onEnqueueTrack: () -> Unit,
    onShowTrackInfo: () -> Unit,
) {
    val track = entry.track
    var menuExpanded by remember { mutableStateOf(false) }

    val rowBg = when {
        isDragged -> MaterialTheme.colorScheme.surfaceContainerHighest
        isActive -> MaterialTheme.colorScheme.surfaceVariant
        else -> Color.Transparent
    }
    val rowRootModifier = if (isDragged) {
        Modifier
            .fillMaxWidth()
            .zIndex(1f)
            .graphicsLayer {
                translationY = dragDistanceProvider()
                scaleX = 1.01f
                scaleY = 1.01f
                shadowElevation = 10f
            }
    } else {
        Modifier.fillMaxWidth()
    }
    val dragBorderModifier = if (isDragged) {
        Modifier.border(
            width = 0.75.dp,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.65f),
            shape = DraggedRowShape,
        )
    } else {
        Modifier
    }

    Column(modifier = rowRootModifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = if (isDragged) 6.dp else 0.dp)
                .clip(if (isDragged) DraggedRowShape else RectangleShape)
                .background(rowBg)
                .then(dragBorderModifier)
                .padding(
                    start = if (showDragHandle) 4.dp else 16.dp,
                    end = 2.dp,
                    top = 6.dp,
                    bottom = 6.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showDragHandle) {
                Box(
                    modifier = Modifier
                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .semantics(mergeDescendants = true) {},
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DragHandle,
                        contentDescription = "Reorder ${track.title}",
                        tint = if (isDragged) MaterialTheme.colorScheme.primary else TextMuted,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }

            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable(enabled = !isAnyRowDragging, onClick = onPlay)
                    .padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TrackArtworkThumbnail(
                    track = track,
                    artworkLoader = albumArtworkLoader,
                    size = 48.dp,
                    cornerRadius = 8.dp,
                    targetSizePx = AlbumArtworkLoader.THUMBNAIL_SIZE_PX,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = track.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (track.durationLabel.isNotEmpty()) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = track.durationLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = track.artist.trim().ifEmpty { "Unknown Artist" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = track.formatBadge,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            IconButton(
                onClick = onRemove,
                enabled = !isAnyRowDragging,
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Remove ${track.title} from $playlistName",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }

            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    enabled = !isAnyRowDragging,
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = "More actions for ${track.title}",
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
                            text = { Text(if (isFavorite) "Remove from Favorites" else "Add to Favorites") },
                            onClick = {
                                menuExpanded = false
                                onToggleFavorite()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Add to Playlist") },
                            onClick = {
                                menuExpanded = false
                                onAddToPlaylist()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Play Next") },
                            onClick = {
                                menuExpanded = false
                                onEnqueueNext()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Add to Queue") },
                            onClick = {
                                menuExpanded = false
                                onEnqueueTrack()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Track Info") },
                            onClick = {
                                menuExpanded = false
                                onShowTrackInfo()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Remove from Playlist", color = CoralWarning) },
                            onClick = {
                                menuExpanded = false
                                onRemove()
                            },
                        )
                    }
                }
            }
        }
    }
}
