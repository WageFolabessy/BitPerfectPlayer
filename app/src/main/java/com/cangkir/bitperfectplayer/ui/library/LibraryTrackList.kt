package com.cangkir.bitperfectplayer.ui.library

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.cangkir.bitperfectplayer.ui.TrackArtworkThumbnail
import com.cangkir.bitperfectplayer.ui.theme.TextMuted

@Composable
internal fun TrackListView(
    tracks: List<PlaybackTrack>,
    scrollResetKey: Any,
    albumArtworkLoader: AlbumArtworkLoader,
    emptyTitle: String,
    emptySubtitle: String,
    activeTrackUri: String?,
    favoriteTrackUris: Set<String>,
    favoriteFingerprints: Set<String>,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    showTrackNumber: Boolean = false,
    onPlayTracks: (List<PlaybackTrack>, Int) -> Unit,
    onEnqueueTrack: (PlaybackTrack) -> Unit,
    onEnqueueNext: (PlaybackTrack) -> Unit,
    onAddToPlaylist: (PlaybackTrack) -> Unit,
    onShowTrackInfo: (PlaybackTrack) -> Unit,
    onToggleFavorite: (PlaybackTrack) -> Unit,
    onImportFolder: (() -> Unit)?,
) {
    if (tracks.isEmpty()) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Filled.MusicNote,
                contentDescription = null,
                tint = TextMuted,
                modifier = Modifier.size(40.dp),
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = emptyTitle,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            if (emptySubtitle.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = emptySubtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            if (onImportFolder != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onImportFolder,
                    modifier = Modifier.sizeIn(minHeight = 48.dp),
                ) {
                    Icon(Icons.Filled.CreateNewFolder, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Import Folder")
                }
            }
        }
        return
    }

    var previousResetKey by remember { mutableStateOf(scrollResetKey) }
    LaunchedEffect(scrollResetKey) {
        if (previousResetKey != scrollResetKey) {
            previousResetKey = scrollResetKey
            listState.scrollToItem(0)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 12.dp),
    ) {
        itemsIndexed(
            items = tracks,
            key = { _, track -> track.uriString },
            contentType = { _, _ -> "track_row" },
        ) { index, track ->
            val isActive = track.uriString == activeTrackUri
            val isFav = track.uriString in favoriteTrackUris ||
                track.contentFingerprint?.lowercase()?.let { it in favoriteFingerprints } == true

            TrackListRow(
                track = track,
                isActive = isActive,
                isFavorite = isFav,
                showTrackNumber = showTrackNumber,
                albumArtworkLoader = albumArtworkLoader,
                onPlay = { onPlayTracks(tracks, index) },
                onToggleFavorite = onToggleFavorite,
                onEnqueueNext = onEnqueueNext,
                onEnqueueTrack = onEnqueueTrack,
                onAddToPlaylist = onAddToPlaylist,
                onShowTrackInfo = onShowTrackInfo,
            )
            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                modifier = Modifier.padding(
                    start = if (showTrackNumber) 110.dp else 76.dp,
                    end = 16.dp,
                ),
            )
        }
    }
}

@Composable
internal fun TrackListRow(
    track: PlaybackTrack,
    isActive: Boolean,
    isFavorite: Boolean,
    showTrackNumber: Boolean,
    albumArtworkLoader: AlbumArtworkLoader,
    onPlay: () -> Unit,
    onToggleFavorite: (PlaybackTrack) -> Unit,
    onEnqueueNext: (PlaybackTrack) -> Unit,
    onEnqueueTrack: (PlaybackTrack) -> Unit,
    onAddToPlaylist: (PlaybackTrack) -> Unit,
    onShowTrackInfo: (PlaybackTrack) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isActive) {
                    MaterialTheme.colorScheme.surfaceVariant
                } else {
                    Color.Transparent
                },
            )
            .clickable(onClick = onPlay)
            .padding(start = 16.dp, end = 2.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showTrackNumber) {
            Text(
                text = track.trackNumberLabel.ifEmpty { "·" },
                style = MaterialTheme.typography.labelMedium,
                color = if (isActive) MaterialTheme.colorScheme.primary else TextMuted,
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.width(28.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
        }

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
                    color = if (isActive) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
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
                text = track.artistAlbumLine,
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

        IconButton(
            onClick = { onToggleFavorite(track) },
            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
        ) {
            Icon(
                imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = if (isFavorite) "Remove ${track.title} from favorites" else "Add ${track.title} to favorites",
                tint = if (isFavorite) MaterialTheme.colorScheme.primary else TextMuted,
                modifier = Modifier.size(20.dp),
            )
        }

        Box {
            IconButton(
                onClick = { menuExpanded = true },
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = "More actions for ${track.title}",
                    tint = TextMuted,
                    modifier = Modifier.size(20.dp),
                )
            }
            // Only compose DropdownMenu popup state when explicitly opened.
            if (menuExpanded) {
                DropdownMenu(
                    expanded = true,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Add to Playlist") },
                        onClick = {
                            menuExpanded = false
                            onAddToPlaylist(track)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Play Next") },
                        onClick = {
                            menuExpanded = false
                            onEnqueueNext(track)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Add to Queue") },
                        onClick = {
                            menuExpanded = false
                            onEnqueueTrack(track)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Track Info") },
                        onClick = {
                            menuExpanded = false
                            onShowTrackInfo(track)
                        },
                    )
                }
            }
        }
    }
}
