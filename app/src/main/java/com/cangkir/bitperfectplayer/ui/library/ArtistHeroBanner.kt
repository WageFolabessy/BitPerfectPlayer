package com.cangkir.bitperfectplayer.ui.library

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cangkir.bitperfectplayer.audio.PlaybackTrack
import com.cangkir.bitperfectplayer.library.AlbumArtworkLoader
import com.cangkir.bitperfectplayer.library.ArtistDetailHelper
import com.cangkir.bitperfectplayer.library.ArtistPhotoStore
import com.cangkir.bitperfectplayer.ui.theme.AmbientArtistHeroWash
import com.cangkir.bitperfectplayer.ui.theme.CoverThemeColorCache

internal enum class ArtistHeroSource {
    LOADING,
    CUSTOM_PHOTO,
    EMBEDDED_ARTIST,
    ALBUM_COVER,
    MONOGRAM,
}

internal data class ResolvedArtistHero(
    val bitmap: Bitmap? = null,
    val source: ArtistHeroSource = ArtistHeroSource.LOADING,
    val themeCacheKey: String? = null,
    val hasCustomPhoto: Boolean = false,
    val isResolved: Boolean = false,
    val fallbackAlbumName: String? = null,
)

@Composable
internal fun rememberResolvedArtistHero(
    artistName: String,
    artistPhotoRevision: Int,
    embeddedCandidates: List<PlaybackTrack>,
    orderedAlbumCoverCandidates: List<PlaybackTrack>,
    albumArtworkLoader: AlbumArtworkLoader,
    artistPhotoStore: ArtistPhotoStore?,
    coverThemeColorCache: CoverThemeColorCache?,
): ResolvedArtistHero {
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

    return resolvedHero
}

@Composable
internal fun ArtistHeroBanner(
    artistName: String,
    resolvedHero: ResolvedArtistHero,
    heroHeight: Dp,
    hasTracks: Boolean,
    onBack: () -> Unit,
    onPickPhoto: () -> Unit,
    onResetPhoto: () -> Unit,
    onEnqueueAllNext: () -> Unit,
    onEnqueueAll: () -> Unit,
) {
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
        // GPU-friendly atmospheric wash driven by the isolated contextual Artist Dynamic Theme.
        AmbientArtistHeroWash(modifier = Modifier.fillMaxSize())

        if (imageBitmap != null) {
            if (resolvedHero.source == ArtistHeroSource.ALBUM_COVER) {
                // Keep album covers full & uncropped (ContentScale.Fit) over the subtle ambient wash.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    backgroundColor.copy(alpha = 0.72f),
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

            ArtistOverflowMenu(
                artistName = artistName,
                hasCustomPhoto = resolvedHero.hasCustomPhoto,
                hasTracks = hasTracks,
                onPickPhoto = onPickPhoto,
                onResetPhoto = onResetPhoto,
                onEnqueueAllNext = onEnqueueAllNext,
                onEnqueueAll = onEnqueueAll,
            )
        }
    }
}
