package com.cangkir.bitperfectplayer.ui.library

import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import com.cangkir.bitperfectplayer.audio.PlaybackTrack
import com.cangkir.bitperfectplayer.audio.QueueContext
import com.cangkir.bitperfectplayer.library.AlbumArtworkLoader
import com.cangkir.bitperfectplayer.library.ArtistAlbumItem
import com.cangkir.bitperfectplayer.library.ArtistDetailHelper
import com.cangkir.bitperfectplayer.library.ArtistPhotoStore
import com.cangkir.bitperfectplayer.library.EmbeddedArtworkSlot
import com.cangkir.bitperfectplayer.library.LibraryGroup
import com.cangkir.bitperfectplayer.library.TrackSortSpec
import com.cangkir.bitperfectplayer.ui.theme.BitPerfectPlayerTheme
import com.cangkir.bitperfectplayer.ui.theme.CoverThemeColorCache
import com.cangkir.bitperfectplayer.ui.theme.CoverThemeLookup
import com.cangkir.bitperfectplayer.ui.theme.DynamicThemeIntensity
import com.cangkir.bitperfectplayer.ui.theme.DynamicThemeTokens
import com.cangkir.bitperfectplayer.ui.theme.LocalActiveDynamicTokens

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
    dynamicThemeIntensity: DynamicThemeIntensity = DynamicThemeIntensity.VIBRANT,
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
    val resolvedHero = rememberResolvedArtistHero(
        artistName = artistName,
        artistPhotoRevision = artistPhotoRevision,
        embeddedCandidates = embeddedCandidates,
        orderedAlbumCoverCandidates = orderedAlbumCoverCandidates,
        albumArtworkLoader = albumArtworkLoader,
        artistPhotoStore = artistPhotoStore,
        coverThemeColorCache = coverThemeColorCache,
    )

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
        dynamicThemeIntensity = dynamicThemeIntensity,
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
    dynamicThemeIntensity: DynamicThemeIntensity = DynamicThemeIntensity.VIBRANT,
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
    var lookup by remember(themeCacheKey, heroSlot, fallbackAlbumCandidates, dynamicThemeIntensity) {
        val initialLookup = if (themeCacheKey != null && coverThemeColorCache != null) {
            val direct = coverThemeColorCache.peekCachedByKey(
                key = themeCacheKey,
                slot = heroSlot,
                intensity = dynamicThemeIntensity,
            )
            val needsMonochromeBorrow = direct is CoverThemeLookup.Resolved &&
                direct.tokens == null &&
                heroSlot == EmbeddedArtworkSlot.ARTIST &&
                fallbackAlbumCandidates.isNotEmpty()
            if (needsMonochromeBorrow) {
                val cachedFallback = fallbackAlbumCandidates.firstNotNullOfOrNull { candidate ->
                    (
                        coverThemeColorCache.peekCached(
                            track = candidate,
                            intensity = dynamicThemeIntensity,
                        ) as? CoverThemeLookup.Resolved
                        )?.tokens
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
    LaunchedEffect(activeCache, themeCacheKey, heroSlot, heroBitmap, fallbackAlbumCandidates, dynamicThemeIntensity) {
        if (activeCache != null && themeCacheKey != null && heroBitmap != null) {
            if (lookup is CoverThemeLookup.Pending) {
                lookup = CoverThemeLookup.Resolved(
                    activeCache.resolveArtistHeroTokens(
                        heroKey = themeCacheKey,
                        heroSlot = heroSlot,
                        heroBitmap = heroBitmap,
                        fallbackAlbumCandidates = fallbackAlbumCandidates,
                        intensity = dynamicThemeIntensity,
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
