package com.cangkir.bitperfectplayer.ui.library

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cangkir.bitperfectplayer.library.AlbumArtworkLoader
import com.cangkir.bitperfectplayer.library.ArtistDetailHelper
import com.cangkir.bitperfectplayer.library.ArtistPhotoStore
import com.cangkir.bitperfectplayer.library.LibraryGroup
import com.cangkir.bitperfectplayer.library.TrackSortSpec
import kotlinx.coroutines.delay

private enum class ArtistListArtworkSource {
    CUSTOM_PHOTO,
    EMBEDDED_ARTIST,
    ALBUM_COVER,
    MONOGRAM,
}

private data class ResolvedArtistRowArtwork(
    val bitmap: Bitmap? = null,
    val source: ArtistListArtworkSource = ArtistListArtworkSource.MONOGRAM,
    val fallbackAlbumName: String? = null,
)

/**
 * `Artists` tab list rendering each artist with the exact same 4-tier artwork priority chain as
 * `ArtistDetailView`:
 * 1. Custom photo from [artistPhotoStore]
 * 2. Embedded `ARTIST` picture (`type=8/7/10/19`) via [albumArtworkLoader]
 * 3. Front Cover of the first album in the artist's oldest-first sorted album list
 * 4. Monochrome Studio monogram fallback.
 */
@Composable
internal fun ArtistGroupListView(
    groups: List<LibraryGroup>,
    scrollResetKey: Any,
    sortLabel: String,
    albumTrackSortSpec: TrackSortSpec,
    albumArtworkLoader: AlbumArtworkLoader,
    artistPhotoStore: ArtistPhotoStore?,
    artistPhotoRevision: Int,
    listState: LazyListState = rememberLazyListState(),
    onOpenSort: () -> Unit,
    onSelectGroup: (LibraryGroup) -> Unit,
    onPlayGroup: (LibraryGroup) -> Unit,
) {
    if (groups.isEmpty()) {
        GroupEmptyState("No artists found in local library.")
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
            countLabel = "${groups.size} ${if (groups.size == 1) "artist" else "artists"}",
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
                contentType = { "artist_group_row" },
            ) { group ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectGroup(group) }
                        .padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ArtistRowThumbnail(
                        artistGroup = group,
                        albumTrackSortSpec = albumTrackSortSpec,
                        albumArtworkLoader = albumArtworkLoader,
                        artistPhotoStore = artistPhotoStore,
                        artistPhotoRevision = artistPhotoRevision,
                    )
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
                    modifier = Modifier.padding(start = 76.dp, end = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun ArtistRowThumbnail(
    artistGroup: LibraryGroup,
    albumTrackSortSpec: TrackSortSpec,
    albumArtworkLoader: AlbumArtworkLoader,
    artistPhotoStore: ArtistPhotoStore?,
    artistPhotoRevision: Int,
) {
    val artistName = artistGroup.name
    val artworkPlan = remember(artistName, artistGroup.tracks, albumTrackSortSpec) {
        ArtistDetailHelper.buildArtistArtworkPlan(
            artistName = artistName,
            artistTracks = artistGroup.tracks,
            albumTrackSortSpec = albumTrackSortSpec,
        )
    }
    val firstAlbumUri = artworkPlan.orderedAlbumCoverCandidates.firstOrNull()?.uriString

    var resolved by remember(artistName, artistPhotoRevision, firstAlbumUri) {
        val customCached = artistPhotoStore?.getCachedBitmap(artistName)
        val embeddedCached = if (customCached == null) {
            albumArtworkLoader.getCachedArtistEmbeddedBitmap(artistName, AlbumArtworkLoader.THUMBNAIL_SIZE_PX)
        } else {
            null
        }
        val canSyncFallbackToOldestAlbum = customCached == null &&
            embeddedCached == null &&
            (artworkPlan.embeddedArtistCandidates.isEmpty() || albumArtworkLoader.isArtistEmbeddedMissing(artistName))
        val oldestAlbumTrack = artworkPlan.orderedAlbumCoverCandidates.firstOrNull()
        val albumCached = if (canSyncFallbackToOldestAlbum && oldestAlbumTrack != null) {
            albumArtworkLoader.getCachedFromMemory(oldestAlbumTrack, AlbumArtworkLoader.THUMBNAIL_SIZE_PX)
                ?.let { oldestAlbumTrack to it }
        } else {
            null
        }
        mutableStateOf(
            when {
                customCached != null -> ResolvedArtistRowArtwork(
                    bitmap = customCached,
                    source = ArtistListArtworkSource.CUSTOM_PHOTO,
                )
                embeddedCached != null -> ResolvedArtistRowArtwork(
                    bitmap = embeddedCached,
                    source = ArtistListArtworkSource.EMBEDDED_ARTIST,
                )
                albumCached != null -> ResolvedArtistRowArtwork(
                    bitmap = albumCached.second,
                    source = ArtistListArtworkSource.ALBUM_COVER,
                    fallbackAlbumName = albumCached.first.album,
                )
                else -> ResolvedArtistRowArtwork()
            },
        )
    }

    LaunchedEffect(artistName, artistPhotoRevision, artworkPlan) {
        if (resolved.bitmap == null) {
            delay(35L)
        }
        val customBitmap = artistPhotoStore?.loadArtistPhotoBitmap(artistName)
        if (customBitmap != null) {
            resolved = ResolvedArtistRowArtwork(
                bitmap = customBitmap,
                source = ArtistListArtworkSource.CUSTOM_PHOTO,
            )
            return@LaunchedEffect
        }
        val embeddedBitmap = albumArtworkLoader.loadArtistEmbeddedBitmap(
            artistName = artistName,
            candidateTracks = artworkPlan.embeddedArtistCandidates,
            targetSizePx = AlbumArtworkLoader.THUMBNAIL_SIZE_PX,
        )
        if (embeddedBitmap != null) {
            resolved = ResolvedArtistRowArtwork(
                bitmap = embeddedBitmap,
                source = ArtistListArtworkSource.EMBEDDED_ARTIST,
            )
            return@LaunchedEffect
        }
        val albumCover = albumArtworkLoader.loadFirstAvailableArtwork(
            tracks = artworkPlan.orderedAlbumCoverCandidates,
            targetSizePx = AlbumArtworkLoader.THUMBNAIL_SIZE_PX,
        )
        resolved = if (albumCover != null) {
            ResolvedArtistRowArtwork(
                bitmap = albumCover.second,
                source = ArtistListArtworkSource.ALBUM_COVER,
                fallbackAlbumName = albumCover.first.album,
            )
        } else {
            ResolvedArtistRowArtwork(source = ArtistListArtworkSource.MONOGRAM)
        }
    }

    val shape = remember { RoundedCornerShape(8.dp) }
    val monogram = remember(artistName) { ArtistDetailHelper.computeMonogram(artistName) }
    val bitmap = resolved.bitmap
    val imageBitmap = remember(bitmap) {
        if (bitmap != null && !bitmap.isRecycled) bitmap.asImageBitmap() else null
    }

    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(shape)
            .background(
                if (imageBitmap != null) {
                    MaterialTheme.colorScheme.surfaceVariant
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
            )
            .border(
                width = 0.5.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
                shape = shape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (imageBitmap != null) {
            val isAlbumFallback = resolved.source == ArtistListArtworkSource.ALBUM_COVER
            val ratio = imageBitmap.width.toFloat() / imageBitmap.height.coerceAtLeast(1).toFloat()
            val scale = if (isAlbumFallback || ratio !in 0.65f..1.65f) {
                ContentScale.Fit
            } else {
                ContentScale.Crop
            }
            val desc = when (resolved.source) {
                ArtistListArtworkSource.CUSTOM_PHOTO -> "Custom artist photo for $artistName"
                ArtistListArtworkSource.EMBEDDED_ARTIST -> "Embedded artist artwork for $artistName"
                ArtistListArtworkSource.ALBUM_COVER -> {
                    val album = resolved.fallbackAlbumName?.takeIf { it.isNotBlank() }
                    if (album != null) "Artist cover from $album for $artistName" else "Artist cover for $artistName"
                }
                ArtistListArtworkSource.MONOGRAM -> "Artist monogram for $artistName"
            }
            Image(
                bitmap = imageBitmap,
                contentDescription = desc,
                contentScale = scale,
                alignment = if (isAlbumFallback) Alignment.Center else Alignment.TopCenter,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = monogram,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics {
                    contentDescription = "Artist monogram for $artistName"
                },
            )
        }
    }
}
