package com.cangkir.bitperfectplayer.ui.library

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cangkir.bitperfectplayer.audio.PlaybackTrack
import com.cangkir.bitperfectplayer.audio.QueueContext
import com.cangkir.bitperfectplayer.library.AlbumArtworkLoader
import com.cangkir.bitperfectplayer.library.ArtistDetailHelper
import com.cangkir.bitperfectplayer.library.ArtistPhotoStore
import com.cangkir.bitperfectplayer.library.FolderArtworkSelector
import com.cangkir.bitperfectplayer.library.LibraryGroup
import com.cangkir.bitperfectplayer.library.TrackSortSpec
import com.cangkir.bitperfectplayer.ui.DetailActionHeaderRow
import com.cangkir.bitperfectplayer.ui.InlineSortTriggerButton
import com.cangkir.bitperfectplayer.ui.TrackArtworkThumbnail
import com.cangkir.bitperfectplayer.ui.theme.ThemeArtworkCandidateSelector
import kotlinx.coroutines.delay

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
private fun AlbumDetailArtworkHeader(albumGroup: LibraryGroup, albumArtworkLoader: AlbumArtworkLoader) {
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

/**
 * `Folders` tab list displaying a crisp, full uncropped Front Cover thumbnail (`ContentScale.Fit`)
 * resolved deterministically from each folder's tracks with a subtle monochrome folder badge,
 * falling back cleanly to a Monochrome Studio folder icon when no cover is available.
 */
@Composable
internal fun FolderGroupListView(
    groups: List<LibraryGroup>,
    scrollResetKey: Any,
    sortLabel: String,
    albumArtworkLoader: AlbumArtworkLoader,
    listState: LazyListState = rememberLazyListState(),
    onOpenSort: () -> Unit,
    onSelectGroup: (LibraryGroup) -> Unit,
    onPlayGroup: (LibraryGroup) -> Unit,
) {
    if (groups.isEmpty()) {
        GroupEmptyState("No music folders scanned yet.")
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
            countLabel = "${groups.size} ${if (groups.size == 1) "folder" else "folders"}",
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
                contentType = { "folder_group_row" },
            ) { group ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectGroup(group) }
                        .padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FolderCoverThumbnail(
                        folderGroup = group,
                        albumArtworkLoader = albumArtworkLoader,
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
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                                modifier = Modifier.size(13.dp),
                            )
                            Text(
                                text = group.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
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
private fun FolderCoverThumbnail(folderGroup: LibraryGroup, albumArtworkLoader: AlbumArtworkLoader) {
    val candidates = remember(folderGroup.tracks) {
        FolderArtworkSelector.orderArtworkCandidates(folderGroup.tracks)
    }
    val cacheKey = remember(folderGroup.name, candidates.firstOrNull()?.uriString, candidates.size) {
        "${folderGroup.name}|${candidates.firstOrNull()?.uriString.orEmpty()}|${candidates.size}"
    }
    var bitmap by remember(cacheKey) {
        mutableStateOf(
            albumArtworkLoader.getCachedFolderArtwork(
                folderName = folderGroup.name,
                candidates = candidates,
                targetSizePx = AlbumArtworkLoader.THUMBNAIL_SIZE_PX,
            ),
        )
    }

    LaunchedEffect(cacheKey) {
        if (bitmap == null && candidates.isNotEmpty()) {
            delay(35L)
            bitmap = albumArtworkLoader.loadFolderArtwork(
                folderName = folderGroup.name,
                candidates = candidates,
                targetSizePx = AlbumArtworkLoader.THUMBNAIL_SIZE_PX,
            )
        }
    }

    val shape = remember { RoundedCornerShape(8.dp) }
    val badgeShape = remember { RoundedCornerShape(4.dp) }
    val currentBitmap = bitmap
    val imageBitmap = remember(currentBitmap) {
        if (currentBitmap != null && !currentBitmap.isRecycled) currentBitmap.asImageBitmap() else null
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
            Image(
                bitmap = imageBitmap,
                contentDescription = "Folder cover for ${folderGroup.name}",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(2.dp)
                    .size(18.dp)
                    .clip(badgeShape)
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.88f))
                    .border(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.7f), badgeShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f),
                    modifier = Modifier.size(11.dp),
                )
            }
        } else {
            Icon(
                imageVector = Icons.Filled.Folder,
                contentDescription = "Folder icon for ${folderGroup.name}",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun GroupEmptyState(emptyMessage: String) {
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
