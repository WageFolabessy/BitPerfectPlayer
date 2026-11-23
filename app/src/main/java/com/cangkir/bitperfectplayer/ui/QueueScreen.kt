package com.cangkir.bitperfectplayer.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.cangkir.bitperfectplayer.audio.NotificationSubtitleFormatter
import com.cangkir.bitperfectplayer.audio.PlaybackControllerState
import com.cangkir.bitperfectplayer.audio.PlaybackTrack
import com.cangkir.bitperfectplayer.audio.QueueSourceType
import com.cangkir.bitperfectplayer.audio.RepeatMode
import com.cangkir.bitperfectplayer.library.AlbumArtworkLoader
import com.cangkir.bitperfectplayer.ui.theme.TextMuted

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(
    playbackState: PlaybackControllerState,
    libraryTracks: List<PlaybackTrack>,
    activeContextTracks: List<PlaybackTrack>,
    albumArtworkLoader: AlbumArtworkLoader,
    onBack: () -> Unit,
    onSelectQueueIndex: (Int) -> Unit,
    onAddTrackToQueue: (PlaybackTrack) -> Unit,
    onRemoveQueueItem: (Int) -> Unit,
    onMoveQueueItem: (Int, Int) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeatMode: () -> Unit,
) {
    val queue = playbackState.queue
    val activeContext = playbackState.queueContext
    var showAddDialog by remember { mutableStateOf(false) }

    val dialogContextTracks = remember(activeContextTracks, activeContext) {
        if (activeContext.sourceType != QueueSourceType.ALL_SONGS && activeContextTracks.isNotEmpty()) {
            activeContextTracks
        } else {
            emptyList()
        }
    }
    val dialogContextLabel = remember(activeContext, dialogContextTracks) {
        if (dialogContextTracks.isEmpty()) null else activeContext.sourceLabel
    }

    if (showAddDialog) {
        AddTrackToQueueDialog(
            libraryTracks = libraryTracks,
            contextTracks = dialogContextTracks,
            contextLabel = dialogContextLabel,
            onAddTrack = { track ->
                onAddTrackToQueue(track)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false },
        )
    }

    // Initialize directly at the top when the active track is near the start (avoiding any
    // post-first-frame scroll jump), or directly near currentIndex when deep in a long queue.
    val initialScrollIndex = remember {
        if (queue.currentIndex > 3 && queue.currentIndex < queue.items.size) {
            (queue.currentIndex - 1).coerceAtLeast(0)
        } else {
            0
        }
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialScrollIndex)
    val dragState = remember(listState) { DragReorderState(listState) }
    val dragDistanceProvider = remember(dragState) { { dragState.draggedDistanceY } }
    SideEffect {
        dragState.onMove = onMoveQueueItem
    }

    val rowKeys: List<Any> = remember(queue.items, queue.entryIds) {
        if (queue.entryIds.size == queue.items.size) {
            queue.entryIds
        } else {
            val seen = HashMap<String, Int>(queue.items.size)
            queue.items.map { track ->
                val count = (seen[track.uriString] ?: 0) + 1
                seen[track.uriString] = count
                "${track.uriString}#$count"
            }
        }
    }

    DragReorderLifecycleEffects(
        dragState = dragState,
        rowKeys = rowKeys,
        itemCount = queue.items.size,
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Playback Queue",
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = activeContext.formatQueueSubtitle(queue.size),
                            style = MaterialTheme.typography.labelMedium,
                            color = TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = onToggleShuffle,
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Shuffle,
                            contentDescription = if (queue.shuffleEnabled) "Disable shuffle" else "Enable shuffle",
                            tint = if (queue.shuffleEnabled) MaterialTheme.colorScheme.primary else TextMuted,
                        )
                    }
                    IconButton(
                        onClick = onCycleRepeatMode,
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    ) {
                        Icon(
                            imageVector = if (queue.repeatMode == RepeatMode.ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                            contentDescription = "Cycle repeat mode (${queue.repeatMode.name})",
                            tint = if (queue.repeatMode != RepeatMode.OFF) MaterialTheme.colorScheme.primary else TextMuted,
                        )
                    }
                    IconButton(
                        onClick = { showAddDialog = true },
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = "Add track to queue",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = WindowLayoutMetrics.EXPANDED_CONTENT_MAX_WIDTH_DP.dp),
            ) {
                if (queue.isEmpty) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Playback queue is empty. Tap + or select a track from your library.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    return@Column
                }

                // Stationary drag-handle gesture detector scoped strictly to the leading 56.dp
                // DragHandle column so graphicsLayer translation never shifts pointer coordinates
                // and normal vertical list scrolling across the rest of each row stays untouched.
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .dragHandleReorderPointer(
                            dragState = dragState,
                            enabled = queue.items.size > 1,
                        ),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    itemsIndexed(
                        items = queue.items,
                        key = { index, _ -> rowKeys[index] },
                        contentType = { _, _ -> "queue_row" },
                    ) { index, track ->
                        val rowKey = rowKeys[index]
                        val isCurrent = index == queue.currentIndex
                        val isDragged = dragState.draggedItemKey == rowKey
                        val isAnyRowDragging = dragState.isDragging
                        QueueTrackRow(
                            index = index,
                            track = track,
                            isCurrent = isCurrent,
                            isDragged = isDragged,
                            isAnyRowDragging = isAnyRowDragging,
                            dragDistanceProvider = dragDistanceProvider,
                            albumArtworkLoader = albumArtworkLoader,
                            onSelectIndex = onSelectQueueIndex,
                            onRemoveIndex = onRemoveQueueItem,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueTrackRow(
    index: Int,
    track: PlaybackTrack,
    isCurrent: Boolean,
    isDragged: Boolean,
    isAnyRowDragging: Boolean,
    dragDistanceProvider: () -> Float,
    albumArtworkLoader: AlbumArtworkLoader,
    onSelectIndex: (Int) -> Unit,
    onRemoveIndex: (Int) -> Unit,
) {
    val rowBg = when {
        isDragged -> MaterialTheme.colorScheme.surfaceContainerHighest
        isCurrent -> MaterialTheme.colorScheme.surfaceVariant
        else -> Color.Transparent
    }
    val compactSpec = remember(track.container, track.codec, track.bitDepth, track.sampleRateHz) {
        NotificationSubtitleFormatter.formatAudioSpec(track)
    }
    val removeContentDescription = remember(track.title) { "Remove ${track.title} from queue" }
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
                .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .semantics(mergeDescendants = true) {},
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.DragHandle,
                    contentDescription = "Reorder track",
                    tint = if (isDragged) MaterialTheme.colorScheme.primary else TextMuted,
                    modifier = Modifier.size(22.dp),
                )
            }

            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable(enabled = !isAnyRowDragging) { onSelectIndex(index) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TrackArtworkThumbnail(
                    track = track,
                    artworkLoader = albumArtworkLoader,
                    size = 40.dp,
                    cornerRadius = 6.dp,
                    targetSizePx = AlbumArtworkLoader.THUMBNAIL_SIZE_PX,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    PinnedArtistAudioSpecRow(
                        artist = track.artist,
                        compactSpec = compactSpec,
                    )
                }
            }

            IconButton(
                onClick = { onRemoveIndex(index) },
                enabled = !isAnyRowDragging,
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = removeContentDescription,
                )
            }
        }
        if (!isDragged) {
            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                modifier = Modifier.padding(start = 104.dp, end = 16.dp),
            )
        }
    }
}

@Composable
private fun AddTrackToQueueDialog(
    libraryTracks: List<PlaybackTrack>,
    contextTracks: List<PlaybackTrack>,
    contextLabel: String?,
    onAddTrack: (PlaybackTrack) -> Unit,
    onDismiss: () -> Unit,
) {
    val hasContextScope = contextTracks.isNotEmpty() && contextLabel != null
    var useCurrentContextScope by remember(hasContextScope) { mutableStateOf(hasContextScope) }
    var searchQuery by remember { mutableStateOf("") }

    val basePool = if (hasContextScope && useCurrentContextScope) contextTracks else libraryTracks
    val uniquePool = remember(basePool) { basePool.distinctBy { it.uriString } }
    val filteredTracks = remember(uniquePool, searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) {
            uniquePool
        } else {
            uniquePool.filter { track ->
                track.title.contains(q, ignoreCase = true) ||
                    track.artist.contains(q, ignoreCase = true) ||
                    track.album.contains(q, ignoreCase = true) ||
                    track.fileName.contains(q, ignoreCase = true)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        title = { Text("Add Track to Queue") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Compact search input
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.65f)),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(start = 10.dp, end = if (searchQuery.isNotEmpty()) 0.dp else 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            if (searchQuery.isEmpty()) {
                                Text(
                                    text = "Search tracks...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextMuted,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                            BasicTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodySmall.copy(
                                    color = MaterialTheme.colorScheme.onSurface,
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        if (searchQuery.isNotEmpty()) {
                            IconButton(
                                onClick = { searchQuery = "" },
                                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "Clear search",
                                    tint = TextMuted,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }

                // Quick scope toggle: Current Context vs All Songs
                if (hasContextScope) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        QueueDialogScopeChip(
                            label = "Current Context",
                            selected = useCurrentContextScope,
                            onClick = { useCurrentContextScope = true },
                            modifier = Modifier.weight(1f),
                        )
                        QueueDialogScopeChip(
                            label = "All Songs",
                            selected = !useCurrentContextScope,
                            onClick = { useCurrentContextScope = false },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (filteredTracks.isEmpty()) {
                    Text(
                        text = if (searchQuery.isNotBlank()) "No matching tracks." else "No tracks available.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(
                            items = filteredTracks,
                            key = { it.uriString },
                            contentType = { "dialog_track_row" },
                        ) { track ->
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onAddTrack(track) }
                                        .padding(vertical = 10.dp, horizontal = 4.dp),
                                ) {
                                    val compactSpec = remember(
                                        track.container,
                                        track.codec,
                                        track.bitDepth,
                                        track.sampleRateHz,
                                    ) {
                                        NotificationSubtitleFormatter.formatAudioSpec(track)
                                    }
                                    Text(
                                        text = track.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    PinnedArtistAudioSpecRow(
                                        artist = track.artist,
                                        compactSpec = compactSpec,
                                    )
                                }
                                HorizontalDivider(
                                    thickness = 0.5.dp,
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.sizeIn(minHeight = 48.dp),
            ) {
                Text("Close")
            }
        },
    )
}

@Composable
private fun QueueDialogScopeChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .sizeIn(minHeight = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = 0.5.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
        ),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
