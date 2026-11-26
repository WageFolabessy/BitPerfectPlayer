package com.cangkir.bitperfectplayer.ui.library

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
import androidx.compose.material.icons.filled.Folder
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cangkir.bitperfectplayer.library.AlbumArtworkLoader
import com.cangkir.bitperfectplayer.library.FolderArtworkSelector
import com.cangkir.bitperfectplayer.library.LibraryGroup
import kotlinx.coroutines.delay

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
