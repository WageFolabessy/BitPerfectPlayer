package com.cangkir.bitperfectplayer.ui.library

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cangkir.bitperfectplayer.library.AlbumArtworkLoader
import com.cangkir.bitperfectplayer.library.ArtistAlbumItem
import com.cangkir.bitperfectplayer.ui.InlineSortTriggerButton
import com.cangkir.bitperfectplayer.ui.TrackArtworkThumbnail
import com.cangkir.bitperfectplayer.ui.theme.CoverThemeColorCache
import com.cangkir.bitperfectplayer.ui.theme.CoverThemeLookup
import com.cangkir.bitperfectplayer.ui.theme.DynamicThemeTokens
import com.cangkir.bitperfectplayer.ui.theme.TextMuted

@Composable
internal fun ArtistAlbumsSection(
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
internal fun ArtistTracksSectionHeader(trackCount: Int, sortLabel: String, onOpenSort: () -> Unit) {
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
