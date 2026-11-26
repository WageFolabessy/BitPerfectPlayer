package com.cangkir.bitperfectplayer.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cangkir.bitperfectplayer.audio.PlaybackTrack
import com.cangkir.bitperfectplayer.audio.QueueContext
import com.cangkir.bitperfectplayer.library.AlbumArtworkLoader
import com.cangkir.bitperfectplayer.library.LibraryGroup
import com.cangkir.bitperfectplayer.ui.DetailActionHeaderRow
import com.cangkir.bitperfectplayer.ui.InlineSortTriggerButton

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
    onShuffleTracks: (List<PlaybackTrack>) -> Unit = { tracks -> onPlayTracks(tracks.shuffled(), 0) },
    onEnqueueAllNext: () -> Unit,
    onEnqueueAll: () -> Unit,
    onEnqueueTrack: (PlaybackTrack) -> Unit,
    onEnqueueNext: (PlaybackTrack) -> Unit,
    onAddToPlaylist: (PlaybackTrack) -> Unit,
    onShowTrackInfo: (PlaybackTrack) -> Unit,
    onToggleFavorite: (PlaybackTrack) -> Unit,
) {
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
        DetailActionHeaderRow(
            hasTracks = group.tracks.isNotEmpty(),
            onPlayAll = { onPlayTracks(group.tracks, 0) },
            onShuffle = { onShuffleTracks(group.tracks) },
            overflowContentDescription = "Queue options for ${groupContext.sourceLabel}",
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
internal fun GroupEmptyState(emptyMessage: String) {
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
}
