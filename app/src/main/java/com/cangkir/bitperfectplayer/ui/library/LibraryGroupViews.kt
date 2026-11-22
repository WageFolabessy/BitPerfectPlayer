package com.cangkir.bitperfectplayer.ui.library

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Flip
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cangkir.bitperfectplayer.audio.PlaybackTrack
import com.cangkir.bitperfectplayer.audio.QueueContext
import com.cangkir.bitperfectplayer.library.AlbumArtworkLoader
import com.cangkir.bitperfectplayer.library.ArtistDetailHelper
import com.cangkir.bitperfectplayer.library.LibraryGroup
import com.cangkir.bitperfectplayer.ui.InlineSortTriggerButton
import com.cangkir.bitperfectplayer.ui.TrackArtworkThumbnail
import com.cangkir.bitperfectplayer.ui.theme.ObsidianBackground

@Composable
internal fun GroupListView(
    groups: List<LibraryGroup>,
    scrollResetKey: Any,
    singularUnit: String,
    pluralUnit: String,
    sortLabel: String,
    onOpenSort: () -> Unit,
    icon: ImageVector,
    showArtworkThumbnail: Boolean,
    albumArtworkLoader: AlbumArtworkLoader,
    emptyMessage: String,
    onSelectGroup: (LibraryGroup) -> Unit,
    onPlayGroup: (LibraryGroup) -> Unit,
) {
    if (groups.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = emptyMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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

    Column(modifier = Modifier.fillMaxSize()) {
        SubtleListSortHeaderRow(
            countLabel = "${groups.size} ${if (groups.size == 1) singularUnit else pluralUnit}",
            sortLabel = sortLabel,
            onOpenSort = onOpenSort,
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            items(
                items = groups,
                key = { it.name },
                contentType = { "group_row" },
            ) { group ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectGroup(group) }
                        .padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (showArtworkThumbnail) {
                        TrackArtworkThumbnail(
                            track = group.tracks.firstOrNull(),
                            artworkLoader = albumArtworkLoader,
                            size = 48.dp,
                            cornerRadius = 8.dp,
                            placeholderIcon = icon,
                            contentDescriptionText = group.name,
                        )
                    } else {
                        Surface(
                            modifier = Modifier.size(44.dp),
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = group.name,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = group.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(
                        onClick = { onPlayGroup(group) },
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = "Play all tracks in ${group.name}",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                    modifier = Modifier.padding(
                        start = if (showArtworkThumbnail) 76.dp else 72.dp,
                        end = 16.dp,
                    ),
                )
            }
        }
    }
}

@Composable
internal fun GroupDetailTrackList(
    group: LibraryGroup,
    groupContext: QueueContext,
    sortLabel: String,
    scrollResetKey: Any,
    showTrackNumber: Boolean,
    activeTrackUri: String?,
    favoriteTrackUris: Set<String>,
    favoriteFingerprints: Set<String>,
    albumArtworkLoader: AlbumArtworkLoader,
    onOpenSort: () -> Unit,
    onPlayTracks: (List<PlaybackTrack>, Int) -> Unit,
    onEnqueueAllNext: () -> Unit,
    onEnqueueAll: () -> Unit,
    onEnqueueTrack: (PlaybackTrack) -> Unit,
    onEnqueueNext: (PlaybackTrack) -> Unit,
    onAddToPlaylist: (PlaybackTrack) -> Unit,
    onShowTrackInfo: (PlaybackTrack) -> Unit,
    onToggleFavorite: (PlaybackTrack) -> Unit,
) {
    var groupMenuExpanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        if (showTrackNumber && group.tracks.isNotEmpty()) {
            AlbumDetailArtworkHeader(
                albumGroup = group,
                albumArtworkLoader = albumArtworkLoader,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 6.dp, top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = group.subtitle,
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
                onClick = { onPlayTracks(group.tracks, 0) },
                enabled = group.tracks.isNotEmpty(),
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
            Box {
                IconButton(
                    onClick = { groupMenuExpanded = true },
                    enabled = group.tracks.isNotEmpty(),
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = "Queue options for ${groupContext.sourceLabel}",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (groupMenuExpanded) {
                    DropdownMenu(
                        expanded = true,
                        onDismissRequest = { groupMenuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Play Next") },
                            onClick = {
                                groupMenuExpanded = false
                                onEnqueueAllNext()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Add All to Queue") },
                            onClick = {
                                groupMenuExpanded = false
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
        TrackListView(
            tracks = group.tracks,
            scrollResetKey = scrollResetKey,
            albumArtworkLoader = albumArtworkLoader,
            emptyTitle = "Empty group",
            emptySubtitle = "",
            activeTrackUri = activeTrackUri,
            favoriteTrackUris = favoriteTrackUris,
            favoriteFingerprints = favoriteFingerprints,
            showTrackNumber = showTrackNumber,
            onPlayTracks = onPlayTracks,
            onEnqueueTrack = onEnqueueTrack,
            onEnqueueNext = onEnqueueNext,
            onAddToPlaylist = onAddToPlaylist,
            onShowTrackInfo = onShowTrackInfo,
            onToggleFavorite = onToggleFavorite,
            onImportFolder = null,
        )
    }
}

@Composable
private fun AlbumDetailArtworkHeader(albumGroup: LibraryGroup, albumArtworkLoader: AlbumArtworkLoader) {
    val representativeTrack = albumGroup.tracks.firstOrNull() ?: return
    val albumYear = remember(albumGroup.tracks) {
        albumGroup.tracks.maxOfOrNull { ArtistDetailHelper.sanitizeYear(it.year) } ?: 0
    }
    var backCoverBitmap by remember(albumGroup.name, representativeTrack.uriString) {
        mutableStateOf<Bitmap?>(null)
    }
    var showingBackCover by remember(albumGroup.name, representativeTrack.uriString) {
        mutableStateOf(false)
    }

    LaunchedEffect(albumGroup.name, representativeTrack.uriString) {
        showingBackCover = false
        backCoverBitmap = albumArtworkLoader.loadAlbumBackCoverBitmap(
            albumName = albumGroup.name,
            tracks = albumGroup.tracks,
            targetSizePx = AlbumArtworkLoader.HERO_SIZE_PX,
        )
    }

    val currentBackBitmap = backCoverBitmap
    val backImageBitmap = remember(currentBackBitmap) {
        if (currentBackBitmap != null && !currentBackBitmap.isRecycled) currentBackBitmap.asImageBitmap() else null
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (showingBackCover && backImageBitmap != null) {
            Box(
                modifier = Modifier
                    .size(104.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(ObsidianBackground),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    bitmap = backImageBitmap,
                    contentDescription = "Back cover artwork for ${albumGroup.name}",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else {
            TrackArtworkThumbnail(
                track = representativeTrack,
                artworkLoader = albumArtworkLoader,
                size = 104.dp,
                cornerRadius = 12.dp,
                targetSizePx = AlbumArtworkLoader.HERO_SIZE_PX,
                placeholderIcon = Icons.Filled.Album,
                contentDescriptionText = "Front cover artwork for ${albumGroup.name}",
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = albumGroup.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = buildString {
                    append(representativeTrack.artist)
                    if (albumYear > 0) append(" · $albumYear")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (backImageBitmap != null) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { showingBackCover = !showingBackCover },
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Flip,
                        contentDescription = if (showingBackCover) "Show front cover" else "Show back cover",
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (showingBackCover) "Front Cover" else "Back Cover",
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}
