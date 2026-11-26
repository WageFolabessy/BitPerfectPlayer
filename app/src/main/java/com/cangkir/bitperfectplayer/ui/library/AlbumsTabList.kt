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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.PlayArrow
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
import com.cangkir.bitperfectplayer.library.AlbumArtworkLoader
import com.cangkir.bitperfectplayer.library.ArtistDetailHelper
import com.cangkir.bitperfectplayer.library.LibraryGroup
import com.cangkir.bitperfectplayer.ui.TrackArtworkThumbnail
import com.cangkir.bitperfectplayer.ui.theme.ThemeArtworkCandidateSelector

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
    listState: LazyListState = rememberLazyListState(),
    onSelectGroup: (LibraryGroup) -> Unit,
    onPlayGroup: (LibraryGroup) -> Unit,
) {
    if (groups.isEmpty()) {
        GroupEmptyState(emptyMessage)
        return
    }

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
internal fun AlbumDetailArtworkHeader(albumGroup: LibraryGroup, albumArtworkLoader: AlbumArtworkLoader) {
    val stableCandidates = remember(albumGroup.tracks) {
        ThemeArtworkCandidateSelector.selectStableCandidates(albumGroup.tracks)
    }
    val fallbackTrack = stableCandidates.firstOrNull() ?: albumGroup.tracks.firstOrNull() ?: return
    var resolvedCoverTrack by remember(albumGroup.name, fallbackTrack.uriString) {
        mutableStateOf(
            albumArtworkLoader.getCachedFirstAvailableArtwork(
                tracks = stableCandidates,
                targetSizePx = AlbumArtworkLoader.HERO_SIZE_PX,
            )?.first ?: fallbackTrack,
        )
    }
    val albumYear = remember(albumGroup.tracks) {
        ArtistDetailHelper.resolveAlbumReleaseYear(albumGroup.tracks)
    }
    var backCoverBitmap by remember(albumGroup.name, fallbackTrack.uriString) {
        mutableStateOf<Bitmap?>(null)
    }
    var showingBackCover by remember(albumGroup.name, fallbackTrack.uriString) {
        mutableStateOf(false)
    }

    LaunchedEffect(albumGroup.name, stableCandidates) {
        showingBackCover = false
        albumArtworkLoader.loadFirstAvailableArtwork(
            tracks = stableCandidates,
            targetSizePx = AlbumArtworkLoader.HERO_SIZE_PX,
        )?.let { resolvedCoverTrack = it.first }
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
    val headerArtist = remember(albumGroup.tracks, fallbackTrack.artist) {
        albumGroup.tracks
            .asSequence()
            .map { it.artist.trim() }
            .firstOrNull { it.isNotEmpty() && !it.equals("Unknown Artist", ignoreCase = true) }
            ?: fallbackTrack.artist.ifBlank { "Unknown Artist" }
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
                    .background(MaterialTheme.colorScheme.background),
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
                track = resolvedCoverTrack,
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
                    append(headerArtist)
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
