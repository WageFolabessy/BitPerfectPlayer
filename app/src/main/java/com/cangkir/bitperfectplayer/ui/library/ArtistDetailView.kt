package com.cangkir.bitperfectplayer.ui.library

import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cangkir.bitperfectplayer.audio.PlaybackTrack
import com.cangkir.bitperfectplayer.audio.QueueContext
import com.cangkir.bitperfectplayer.library.AlbumArtworkLoader
import com.cangkir.bitperfectplayer.library.ArtistAlbumItem
import com.cangkir.bitperfectplayer.library.ArtistDetailHelper
import com.cangkir.bitperfectplayer.library.ArtistPhotoStore
import com.cangkir.bitperfectplayer.library.EmbeddedArtworkSlot
import com.cangkir.bitperfectplayer.library.LibraryGroup
import com.cangkir.bitperfectplayer.library.TrackSortSpec
import com.cangkir.bitperfectplayer.ui.InlineSortTriggerButton
import com.cangkir.bitperfectplayer.ui.TrackArtworkThumbnail
import com.cangkir.bitperfectplayer.ui.theme.BitPerfectPlayerTheme
import com.cangkir.bitperfectplayer.ui.theme.CoverThemeColorCache
import com.cangkir.bitperfectplayer.ui.theme.CoverThemeLookup
import com.cangkir.bitperfectplayer.ui.theme.DynamicThemeTokens
import com.cangkir.bitperfectplayer.ui.theme.LocalActiveDynamicTokens
import com.cangkir.bitperfectplayer.ui.theme.TextMuted

private enum class ArtistHeroSource {
    LOADING,
    CUSTOM_PHOTO,
    EMBEDDED_ARTIST,
    ALBUM_COVER,
    MONOGRAM,
}

private data class ResolvedArtistHero(
    val bitmap: Bitmap? = null,
    val source: ArtistHeroSource = ArtistHeroSource.LOADING,
    val themeCacheKey: String? = null,
    val hasCustomPhoto: Boolean = false,
    val isResolved: Boolean = false,
    val fallbackAlbumName: String? = null,
)

/**
 * Studio-grade Artist Detail page featuring:
 * - Full-bleed Hero Banner (custom photo -> embedded `ARTIST` picture -> oldest-first album cover ->
 *   monochrome monogram) with overlaid status-bar-padded Back & overflow controls;
 * - Bold Artist Identity + total statistics (`N albums · M tracks · Xh Y min`) + `Play All` & `Shuffle`;
 * - Horizontal `Albums` carousel sorted oldest release year first (chronological) with uncropped
 *   `ContentScale.Fit` artwork and dynamic cover-tinted info cards (or Monochrome Studio surfaces);
 * - Single unified vertical `LazyColumn` continuing directly into the `TRACKS` section;
 * - Contextual Local Dynamic Cover Theme derived from the resolved Artist Hero artwork.
 */
@Composable
internal fun ArtistDetailView(
    artistGroup: LibraryGroup,
    albumTrackSortSpec: TrackSortSpec,
    artistTrackSortLabel: String,
    scrollResetKey: Any,
    activeTrackUri: String?,
    favoriteTrackUris: Set<String>,
    favoriteFingerprints: Set<String>,
    albumArtworkLoader: AlbumArtworkLoader,
    artistPhotoStore: ArtistPhotoStore?,
    artistPhotoRevision: Int,
    dynamicCoverThemeEnabled: Boolean,
    coverThemeColorCache: CoverThemeColorCache?,
    onBack: () -> Unit,
    onOpenAlbum: (ArtistAlbumItem) -> Unit,
    onOpenSort: () -> Unit,
    onPlayTracks: (List<PlaybackTrack>, Int) -> Unit,
    onShuffleTracks: (List<PlaybackTrack>) -> Unit,
    onEnqueueAllNext: () -> Unit,
    onEnqueueAll: () -> Unit,
    onEnqueueTrack: (PlaybackTrack) -> Unit,
    onEnqueueNext: (PlaybackTrack) -> Unit,
    onAddToPlaylist: (PlaybackTrack) -> Unit,
    onShowTrackInfo: (PlaybackTrack) -> Unit,
    onToggleFavorite: (PlaybackTrack) -> Unit,
    onSetCustomArtistPhoto: (String, Uri) -> Unit,
    onResetCustomArtistPhoto: (String) -> Unit,
    isForegroundPage: Boolean = true,
    onContextualDynamicThemeChanged: ((DynamicThemeTokens?) -> Unit)? = null,
) {
    val artistName = artistGroup.name
    val artistDetail = remember(artistName, artistGroup.tracks, albumTrackSortSpec) {
        ArtistDetailHelper.buildArtistDetail(
            artistName = artistName,
            artistTracks = artistGroup.tracks,
            albumTrackSortSpec = albumTrackSortSpec,
        )
    }
    val embeddedCandidates = remember(artistDetail) {
        ArtistDetailHelper.selectEmbeddedArtistArtworkCandidates(
            albums = artistDetail.albums,
            allTracks = artistDetail.allTracks,
        )
    }
    val orderedAlbumCoverCandidates = artistDetail.orderedAlbumArtworkCandidates
    val firstAlbumUri = orderedAlbumCoverCandidates.firstOrNull()?.uriString

    // Seed initial hero synchronously from memory caches without flashing an album cover while an
    // uncached embedded ARTIST artwork lookup is still in flight on Dispatchers.IO.
    var resolvedHero by remember(artistName, artistPhotoRevision, firstAlbumUri) {
        val customCached = artistPhotoStore?.getCachedBitmap(artistName)
        val embeddedCached = if (customCached == null) {
            albumArtworkLoader.getCachedArtistEmbeddedBitmap(artistName, AlbumArtworkLoader.HERO_SIZE_PX)
        } else {
            null
        }
        val canSyncFallbackToOldestAlbum = customCached == null &&
            embeddedCached == null &&
            (embeddedCandidates.isEmpty() || albumArtworkLoader.isArtistEmbeddedMissing(artistName))
        val oldestAlbumTrack = orderedAlbumCoverCandidates.firstOrNull()
        val albumCached = if (canSyncFallbackToOldestAlbum && oldestAlbumTrack != null) {
            albumArtworkLoader.getCachedFromMemory(oldestAlbumTrack, AlbumArtworkLoader.HERO_SIZE_PX)
                ?.let { oldestAlbumTrack to it }
        } else {
            null
        }
        mutableStateOf(
            when {
                customCached != null -> ResolvedArtistHero(
                    bitmap = customCached,
                    source = ArtistHeroSource.CUSTOM_PHOTO,
                    themeCacheKey = artistPhotoStore.themeCacheKeyFor(artistName, artistPhotoRevision),
                    hasCustomPhoto = true,
                    isResolved = true,
                )
                embeddedCached != null -> ResolvedArtistHero(
                    bitmap = embeddedCached,
                    source = ArtistHeroSource.EMBEDDED_ARTIST,
                    themeCacheKey = "artist_embedded:${artistName.trim().lowercase()}",
                    hasCustomPhoto = false,
                    isResolved = true,
                )
                albumCached != null -> ResolvedArtistHero(
                    bitmap = albumCached.second,
                    source = ArtistHeroSource.ALBUM_COVER,
                    themeCacheKey = coverThemeColorCache?.cacheKeyFor(albumCached.first),
                    hasCustomPhoto = false,
                    isResolved = true,
                    fallbackAlbumName = albumCached.first.album,
                )
                else -> ResolvedArtistHero(source = ArtistHeroSource.LOADING, isResolved = false)
            },
        )
    }

    LaunchedEffect(artistName, artistPhotoRevision, embeddedCandidates, orderedAlbumCoverCandidates) {
        val customBitmap = artistPhotoStore?.loadArtistPhotoBitmap(artistName)
        if (customBitmap != null) {
            resolvedHero = ResolvedArtistHero(
                bitmap = customBitmap,
                source = ArtistHeroSource.CUSTOM_PHOTO,
                themeCacheKey = artistPhotoStore.themeCacheKeyFor(artistName, artistPhotoRevision),
                hasCustomPhoto = true,
                isResolved = true,
            )
            return@LaunchedEffect
        }
        val embeddedBitmap = albumArtworkLoader.loadArtistEmbeddedBitmap(
            artistName = artistName,
            candidateTracks = embeddedCandidates,
            targetSizePx = AlbumArtworkLoader.HERO_SIZE_PX,
        )
        if (embeddedBitmap != null) {
            resolvedHero = ResolvedArtistHero(
                bitmap = embeddedBitmap,
                source = ArtistHeroSource.EMBEDDED_ARTIST,
                themeCacheKey = "artist_embedded:${artistName.trim().lowercase()}",
                hasCustomPhoto = false,
                isResolved = true,
            )
            return@LaunchedEffect
        }
        val fallbackAlbumCover = albumArtworkLoader.loadFirstAvailableArtwork(
            tracks = orderedAlbumCoverCandidates,
            targetSizePx = AlbumArtworkLoader.HERO_SIZE_PX,
        )
        resolvedHero = if (fallbackAlbumCover != null) {
            ResolvedArtistHero(
                bitmap = fallbackAlbumCover.second,
                source = ArtistHeroSource.ALBUM_COVER,
                themeCacheKey = coverThemeColorCache?.cacheKeyFor(fallbackAlbumCover.first),
                hasCustomPhoto = false,
                isResolved = true,
                fallbackAlbumName = fallbackAlbumCover.first.album,
            )
        } else {
            ResolvedArtistHero(
                bitmap = null,
                source = ArtistHeroSource.MONOGRAM,
                themeCacheKey = null,
                hasCustomPhoto = false,
                isResolved = true,
            )
        }
    }

    // Zero-permission Android Photo Picker with GetContent("image/*") fallback
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) onSetCustomArtistPhoto(artistName, uri)
    }
    val fallbackImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) onSetCustomArtistPhoto(artistName, uri)
    }
    val launchPickArtistPhoto = remember(photoPickerLauncher, fallbackImageLauncher) {
        {
            val launched = runCatching {
                photoPickerLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            }.isSuccess
            if (!launched) {
                runCatching { fallbackImageLauncher.launch("image/*") }
            }
        }
    }

    val heroSlot = when (resolvedHero.source) {
        ArtistHeroSource.CUSTOM_PHOTO, ArtistHeroSource.EMBEDDED_ARTIST -> EmbeddedArtworkSlot.ARTIST
        else -> EmbeddedArtworkSlot.FRONT_COVER
    }
    ArtistDetailDynamicThemeWrapper(
        enabled = dynamicCoverThemeEnabled,
        isForegroundPage = isForegroundPage,
        themeCacheKey = resolvedHero.themeCacheKey,
        heroSlot = heroSlot,
        heroBitmap = resolvedHero.bitmap,
        fallbackAlbumCandidates = orderedAlbumCoverCandidates,
        isHeroResolved = resolvedHero.isResolved,
        coverThemeColorCache = coverThemeColorCache,
        onContextualDynamicThemeChanged = onContextualDynamicThemeChanged,
    ) {
        val listState = rememberLazyListState()
        var previousResetKey by remember { mutableStateOf(scrollResetKey) }
        LaunchedEffect(scrollResetKey) {
            if (previousResetKey != scrollResetKey) {
                previousResetKey = scrollResetKey
                listState.scrollToItem(0)
            }
        }

        val density = LocalDensity.current
        val windowHeightDp = with(density) {
            LocalWindowInfo.current.containerSize.height.toDp()
        }
        val isCompactHeight = windowHeightDp < 500.dp ||
            LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        val heroHeight = if (isCompactHeight) 220.dp else 280.dp
        val artistContext = remember(artistName) { QueueContext.artist(artistName) }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            item(key = "artist_hero", contentType = "artist_hero") {
                ArtistHeroBanner(
                    artistName = artistName,
                    resolvedHero = resolvedHero,
                    heroHeight = heroHeight,
                    hasTracks = artistDetail.allTracks.isNotEmpty(),
                    onBack = onBack,
                    onPickPhoto = launchPickArtistPhoto,
                    onResetPhoto = { onResetCustomArtistPhoto(artistName) },
                    onEnqueueAllNext = onEnqueueAllNext,
                    onEnqueueAll = onEnqueueAll,
                )
            }

            item(key = "artist_identity_controls", contentType = "artist_identity") {
                ArtistIdentityAndControls(
                    artistName = artistName,
                    summaryLine = artistDetail.summaryLine,
                    hasTracks = artistDetail.allTracks.isNotEmpty(),
                    onPlayAll = { onPlayTracks(artistDetail.allTracks, 0) },
                    onShuffle = { onShuffleTracks(artistDetail.allTracks) },
                )
            }

            if (artistDetail.albums.isNotEmpty()) {
                item(key = "artist_albums_carousel", contentType = "artist_albums") {
                    ArtistAlbumsSection(
                        albums = artistDetail.albums,
                        albumArtworkLoader = albumArtworkLoader,
                        dynamicCoverThemeEnabled = dynamicCoverThemeEnabled,
                        coverThemeColorCache = coverThemeColorCache,
                        onOpenAlbum = onOpenAlbum,
                    )
                }
            }

            item(key = "artist_tracks_header", contentType = "artist_tracks_header") {
                ArtistTracksSectionHeader(
                    trackCount = artistDetail.allTracks.size,
                    sortLabel = artistTrackSortLabel,
                    onOpenSort = onOpenSort,
                )
            }

            itemsIndexed(
                items = artistDetail.allTracks,
                key = { _, track -> "${artistContext.sourceKey}:${track.uriString}" },
                contentType = { _, _ -> "artist_track_row" },
            ) { index, track ->
                val isActive = track.uriString == activeTrackUri
                val isFav = track.uriString in favoriteTrackUris ||
                    track.contentFingerprint?.lowercase()?.let { it in favoriteFingerprints } == true

                TrackListRow(
                    track = track,
                    isActive = isActive,
                    isFavorite = isFav,
                    showTrackNumber = false,
                    albumArtworkLoader = albumArtworkLoader,
                    onPlay = { onPlayTracks(artistDetail.allTracks, index) },
                    onToggleFavorite = onToggleFavorite,
                    onEnqueueNext = onEnqueueNext,
                    onEnqueueTrack = onEnqueueTrack,
                    onAddToPlaylist = onAddToPlaylist,
                    onShowTrackInfo = onShowTrackInfo,
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
private fun ArtistHeroBanner(
    artistName: String,
    resolvedHero: ResolvedArtistHero,
    heroHeight: androidx.compose.ui.unit.Dp,
    hasTracks: Boolean,
    onBack: () -> Unit,
    onPickPhoto: () -> Unit,
    onResetPhoto: () -> Unit,
    onEnqueueAllNext: () -> Unit,
    onEnqueueAll: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val backgroundColor = MaterialTheme.colorScheme.background
    val heroBitmap = resolvedHero.bitmap
    val imageBitmap = remember(heroBitmap) {
        if (heroBitmap != null && !heroBitmap.isRecycled) heroBitmap.asImageBitmap() else null
    }
    val monogram = remember(artistName) { ArtistDetailHelper.computeMonogram(artistName) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(heroHeight)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (imageBitmap != null) {
            if (resolvedHero.source == ArtistHeroSource.ALBUM_COVER) {
                // Keep album covers full & uncropped (ContentScale.Fit) over a matte studio backdrop.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.surfaceContainerHighest,
                                    backgroundColor,
                                ),
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    val heroDesc = resolvedHero.fallbackAlbumName?.takeIf { it.isNotBlank() }?.let { album ->
                        "Artist hero cover from $album for $artistName"
                    } ?: "Artist hero artwork for $artistName"
                    Image(
                        bitmap = imageBitmap,
                        contentDescription = heroDesc,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 40.dp, bottom = 18.dp, start = 24.dp, end = 24.dp),
                    )
                }
            } else {
                // Portrait / studio artist photography: smart aspect-ratio framing preserving faces.
                val ratio = imageBitmap.width.toFloat() / imageBitmap.height.coerceAtLeast(1).toFloat()
                val scale = if (ratio in 0.45f..2.6f) ContentScale.Crop else ContentScale.Fit
                Image(
                    bitmap = imageBitmap,
                    contentDescription = "Artist photo for $artistName",
                    contentScale = scale,
                    alignment = Alignment.TopCenter,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else if (!resolvedHero.isResolved) {
            // Matte-obsidian placeholder while uncached embedded ARTIST art lookup completes on Dispatchers.IO.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.surfaceVariant,
                                backgroundColor,
                            ),
                        ),
                    )
                    .semantics { contentDescription = "Loading artist artwork for $artistName" },
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.surfaceContainerHighest,
                                MaterialTheme.colorScheme.surfaceVariant,
                                backgroundColor,
                            ),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.65f)),
                    modifier = Modifier.size(104.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = monogram,
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }

        // Top subtle scrim for guaranteed Back/Overflow icon legibility on bright photos
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.52f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )

        // Smooth bottom vertical scrim blending seamlessly into the Obsidian background
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(128.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            backgroundColor.copy(alpha = 0.68f),
                            backgroundColor,
                        ),
                    ),
                ),
        )

        // Overlaid status-bar-padded Back & Overflow controls
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.42f)),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to Artists",
                    tint = Color.White,
                )
            }

            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier
                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.42f)),
                ) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = "Artist options for $artistName",
                        tint = Color.White,
                    )
                }
                if (menuExpanded) {
                    DropdownMenu(
                        expanded = true,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Set Artist Photo") },
                            onClick = {
                                menuExpanded = false
                                onPickPhoto()
                            },
                        )
                        if (resolvedHero.hasCustomPhoto) {
                            DropdownMenuItem(
                                text = { Text("Reset Artist Photo") },
                                onClick = {
                                    menuExpanded = false
                                    onResetPhoto()
                                },
                            )
                        }
                        if (hasTracks) {
                            DropdownMenuItem(
                                text = { Text("Play Next") },
                                onClick = {
                                    menuExpanded = false
                                    onEnqueueAllNext()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Add All to Queue") },
                                onClick = {
                                    menuExpanded = false
                                    onEnqueueAll()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtistIdentityAndControls(
    artistName: String,
    summaryLine: String,
    hasTracks: Boolean,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Text(
            text = artistName,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = summaryLine,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onPlayAll,
                enabled = hasTracks,
                shape = RoundedCornerShape(24.dp),
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
            FilledTonalButton(
                onClick = onShuffle,
                enabled = hasTracks,
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
                modifier = Modifier
                    .weight(1f)
                    .sizeIn(minHeight = 48.dp),
            ) {
                Icon(Icons.Filled.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Shuffle", maxLines = 1, softWrap = false)
            }
        }
    }
}

@Composable
private fun ArtistAlbumsSection(
    albums: List<ArtistAlbumItem>,
    albumArtworkLoader: AlbumArtworkLoader,
    dynamicCoverThemeEnabled: Boolean,
    coverThemeColorCache: CoverThemeColorCache?,
    onOpenAlbum: (ArtistAlbumItem) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 18.dp, bottom = 6.dp),
    ) {
        Text(
            text = "Albums",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(modifier = Modifier.height(10.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(
                items = albums,
                key = { it.albumName },
                contentType = { "artist_album_card" },
            ) { album ->
                ArtistAlbumCard(
                    album = album,
                    albumArtworkLoader = albumArtworkLoader,
                    dynamicCoverThemeEnabled = dynamicCoverThemeEnabled,
                    coverThemeColorCache = coverThemeColorCache,
                    onClick = { onOpenAlbum(album) },
                )
            }
        }
    }
}

@Composable
private fun ArtistAlbumCard(
    album: ArtistAlbumItem,
    albumArtworkLoader: AlbumArtworkLoader,
    dynamicCoverThemeEnabled: Boolean,
    coverThemeColorCache: CoverThemeColorCache?,
    onClick: () -> Unit,
) {
    val repTrack = album.representativeTrack
    var albumTokens by remember(dynamicCoverThemeEnabled, repTrack?.uriString) {
        val peeked = if (dynamicCoverThemeEnabled && repTrack != null && coverThemeColorCache != null) {
            (coverThemeColorCache.peekCached(repTrack) as? CoverThemeLookup.Resolved)?.tokens
        } else {
            null
        }
        mutableStateOf<DynamicThemeTokens?>(peeked)
    }

    LaunchedEffect(dynamicCoverThemeEnabled, repTrack?.uriString) {
        albumTokens = if (dynamicCoverThemeEnabled && repTrack != null && coverThemeColorCache != null) {
            coverThemeColorCache.resolveTokens(repTrack)
        } else {
            null
        }
    }

    val targetContainer = if (dynamicCoverThemeEnabled && albumTokens != null) {
        Color(albumTokens!!.primaryContainerArgb)
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val targetTitleColor = if (dynamicCoverThemeEnabled && albumTokens != null) {
        Color(albumTokens!!.onPrimaryContainerArgb)
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val targetSubtitleColor = if (dynamicCoverThemeEnabled && albumTokens != null) {
        Color(albumTokens!!.onPrimaryContainerArgb).copy(alpha = 0.78f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    val animatedContainer by animateColorAsState(
        targetValue = targetContainer,
        animationSpec = tween(durationMillis = 240),
        label = "albumCardTint",
    )
    val animatedTitleColor by animateColorAsState(
        targetValue = targetTitleColor,
        animationSpec = tween(durationMillis = 240),
        label = "albumCardTitle",
    )
    val animatedSubtitleColor by animateColorAsState(
        targetValue = targetSubtitleColor,
        animationSpec = tween(durationMillis = 240),
        label = "albumCardSubtitle",
    )

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = animatedContainer,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
        modifier = Modifier
            .width(152.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Open album ${album.albumName}, ${album.metadataLine}" },
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            TrackArtworkThumbnail(
                track = repTrack,
                artworkLoader = albumArtworkLoader,
                size = 152.dp,
                cornerRadius = 0.dp,
                targetSizePx = AlbumArtworkLoader.THUMBNAIL_SIZE_PX,
                placeholderIcon = Icons.Filled.Album,
                contentDescriptionText = "Cover art for ${album.albumName}",
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 9.dp),
            ) {
                Text(
                    text = album.albumName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = animatedTitleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = album.metadataLine,
                    style = MaterialTheme.typography.labelSmall,
                    color = animatedSubtitleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ArtistTracksSectionHeader(trackCount: Int, sortLabel: String, onOpenSort: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = "TRACKS",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.4.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "$trackCount",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextMuted,
                )
            }
            InlineSortTriggerButton(
                label = sortLabel,
                onClick = onOpenSort,
            )
        }
        HorizontalDivider(
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
        )
    }
}

/**
 * Contextual Artist Dynamic Theme handler:
 * - When [onContextualDynamicThemeChanged] is supplied (main app shell), pushes resolved artist
 *   hero tokens up to root `BitPerfectPlayerTheme` so TopBar, MiniPlayer, BottomNav, and canvas
 *   all adapt together and revert cleanly on Back or page switch.
 * - Otherwise (standalone preview/tests), wraps [ArtistDetailView] locally.
 */
@Composable
private fun ArtistDetailDynamicThemeWrapper(
    enabled: Boolean,
    isForegroundPage: Boolean = true,
    themeCacheKey: String?,
    heroSlot: EmbeddedArtworkSlot,
    heroBitmap: Bitmap?,
    fallbackAlbumCandidates: List<PlaybackTrack>,
    isHeroResolved: Boolean,
    coverThemeColorCache: CoverThemeColorCache?,
    onContextualDynamicThemeChanged: ((DynamicThemeTokens?) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val parentTokens = LocalActiveDynamicTokens.current
    val latestOnThemeChanged by rememberUpdatedState(onContextualDynamicThemeChanged)
    var lookup by remember(themeCacheKey, heroSlot, fallbackAlbumCandidates) {
        val initialLookup = if (themeCacheKey != null && coverThemeColorCache != null) {
            val direct = coverThemeColorCache.peekCachedByKey(themeCacheKey, slot = heroSlot)
            val needsMonochromeBorrow = direct is CoverThemeLookup.Resolved &&
                direct.tokens == null &&
                heroSlot == EmbeddedArtworkSlot.ARTIST &&
                fallbackAlbumCandidates.isNotEmpty()
            if (needsMonochromeBorrow) {
                val cachedFallback = fallbackAlbumCandidates.firstNotNullOfOrNull { candidate ->
                    (coverThemeColorCache.peekCached(candidate) as? CoverThemeLookup.Resolved)?.tokens
                }
                if (cachedFallback != null) CoverThemeLookup.Resolved(cachedFallback) else CoverThemeLookup.Pending
            } else {
                direct
            }
        } else {
            CoverThemeLookup.Pending
        }
        mutableStateOf(initialLookup)
    }

    val activeCache = coverThemeColorCache?.takeIf { enabled && isForegroundPage }
    LaunchedEffect(activeCache, themeCacheKey, heroSlot, heroBitmap, fallbackAlbumCandidates) {
        if (activeCache != null && themeCacheKey != null && heroBitmap != null) {
            if (lookup is CoverThemeLookup.Pending) {
                lookup = CoverThemeLookup.Resolved(
                    activeCache.resolveArtistHeroTokens(
                        heroKey = themeCacheKey,
                        heroSlot = heroSlot,
                        heroBitmap = heroBitmap,
                        fallbackAlbumCandidates = fallbackAlbumCandidates,
                    ),
                )
            }
        }
    }

    val targetTokens = when {
        !enabled || !isForegroundPage || coverThemeColorCache == null -> null
        themeCacheKey == null -> if (isHeroResolved) null else parentTokens
        else -> when (val cur = lookup) {
            CoverThemeLookup.Pending -> parentTokens
            is CoverThemeLookup.Resolved -> cur.tokens
        }
    }

    if (onContextualDynamicThemeChanged != null) {
        LaunchedEffect(enabled, isForegroundPage, targetTokens) {
            latestOnThemeChanged?.invoke(if (enabled && isForegroundPage) targetTokens else null)
        }
        DisposableEffect(Unit) {
            onDispose { latestOnThemeChanged?.invoke(null) }
        }
        content()
        return
    }

    if (!enabled || coverThemeColorCache == null) {
        content()
        return
    }

    BitPerfectPlayerTheme(
        darkTheme = true,
        dynamicColor = false,
        dynamicTokens = targetTokens,
        content = content,
    )
}
