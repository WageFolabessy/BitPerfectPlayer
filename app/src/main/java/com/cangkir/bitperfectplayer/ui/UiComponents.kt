package com.cangkir.bitperfectplayer.ui

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cangkir.bitperfectplayer.audio.AudioFormatFormatter
import com.cangkir.bitperfectplayer.audio.AudioOutputMode
import com.cangkir.bitperfectplayer.audio.PlaybackControllerState
import com.cangkir.bitperfectplayer.audio.PlaybackTrack
import com.cangkir.bitperfectplayer.library.AlbumArtworkLoader
import com.cangkir.bitperfectplayer.ui.theme.AmberIndicatedContainer
import com.cangkir.bitperfectplayer.ui.theme.EmeraldSafeContainer
import com.cangkir.bitperfectplayer.ui.theme.OnAmberIndicatedContainer
import com.cangkir.bitperfectplayer.ui.theme.OnEmeraldSafeContainer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

fun formatDurationMs(durationMs: Long): String = AudioFormatFormatter.formatDurationMs(durationMs)

/**
 * Displays local offline album cover art for [track] via [artworkLoader], falling back cleanly to
 * a minimalist monochrome placeholder when no embedded or sibling folder artwork is present.
 *
 * Performance guarantees:
 * - Zero SHA-256 hashing on the Main Thread (`getCachedFromMemory` uses lightweight plain string keys).
 * - Caches `bitmap.asImageBitmap()` via `remember(currentBitmap)` so wrappers aren't rebuilt.
 * - Brief 35 ms fling debounce inside `LaunchedEffect` prevents cancelled scroll rows from flooding
 *   the decode semaphore.
 */
@Composable
fun TrackArtworkThumbnail(
    track: PlaybackTrack?,
    artworkLoader: AlbumArtworkLoader,
    size: Dp,
    cornerRadius: Dp,
    modifier: Modifier = Modifier,
    targetSizePx: Int = AlbumArtworkLoader.THUMBNAIL_SIZE_PX,
    placeholderIcon: ImageVector = Icons.Filled.MusicNote,
    contentDescriptionText: String? = null,
) {
    val cacheIdentity = remember(track?.uriString, track?.album, track?.artist, track?.dateModifiedSeconds, targetSizePx) {
        if (track == null) "" else artworkLoader.memoryKeyFor(track, targetSizePx)
    }
    var bitmap by remember(cacheIdentity) {
        mutableStateOf<Bitmap?>(
            track?.let { artworkLoader.getCachedFromMemory(it, targetSizePx) },
        )
    }

    LaunchedEffect(cacheIdentity) {
        if (track != null && bitmap == null) {
            delay(35L)
            bitmap = artworkLoader.loadArtwork(track, targetSizePx)
        }
    }

    val shape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
    val iconSize = (size * 0.42f).coerceIn(18.dp, 52.dp)
    val currentBitmap = bitmap
    val imageBitmap = remember(currentBitmap) {
        if (currentBitmap != null && !currentBitmap.isRecycled) currentBitmap.asImageBitmap() else null
    }

    Box(
        modifier = modifier
            .size(size)
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
                contentDescription = contentDescriptionText ?: track?.let { "Album art for ${it.title}" },
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = placeholderIcon,
                contentDescription = contentDescriptionText,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

/**
 * Restrained flat technical tag used for fingerprints and diagnostic badges (never loud row pills).
 */
@Composable
fun FormatBadgeChip(
    text: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    borderColor: Color = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = containerColor,
        border = BorderStroke(0.5.dp, borderColor),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/**
 * Utilitarian segmented Safe Mode vs Bit-Perfect Opt-In switch shared across the Audio Mode sheet
 * and the Diagnostics screen.
 */
@Composable
fun AudioModeSegmentedSwitch(
    mode: AudioOutputMode,
    isBitPerfectMixerActive: Boolean,
    onSwitchToSafeMode: () -> Unit,
    onRequestBitPerfectMode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isSafe = mode == AudioOutputMode.SAFE_VOLUME_MODE
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.65f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Button(
                onClick = onSwitchToSafeMode,
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                modifier = Modifier
                    .weight(1f)
                    .sizeIn(minHeight = 48.dp),
                colors = if (isSafe) {
                    ButtonDefaults.buttonColors(
                        containerColor = EmeraldSafeContainer,
                        contentColor = OnEmeraldSafeContainer,
                    )
                } else {
                    ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            ) {
                Text(
                    text = "Safe Mode",
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = if (isSafe) FontWeight.SemiBold else FontWeight.Normal,
                )
            }

            Button(
                onClick = onRequestBitPerfectMode,
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                modifier = Modifier
                    .weight(1.1f)
                    .sizeIn(minHeight = 48.dp),
                colors = if (!isSafe) {
                    ButtonDefaults.buttonColors(
                        containerColor = if (isBitPerfectMixerActive) MaterialTheme.colorScheme.primary else AmberIndicatedContainer,
                        contentColor = if (isBitPerfectMixerActive) MaterialTheme.colorScheme.onPrimary else OnAmberIndicatedContainer,
                    )
                } else {
                    ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            ) {
                Text(
                    text = "Bit-Perfect Opt-In",
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = if (!isSafe) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

/**
 * Narrow leaf composable collecting 20 Hz playback progress so neither [MiniPlayerBar]'s content
 * nor host screens recompose during playback.
 */
@Composable
private fun MiniPlayerProgressBar(durationMs: Long, playbackPositionMsFlow: StateFlow<Long>) {
    val currentPositionMs by playbackPositionMsFlow.collectAsStateWithLifecycle()
    val progress = if (durationMs > 0L) {
        (currentPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier
            .fillMaxWidth()
            .height(2.dp),
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    )
}

/**
 * Minimalist persistent bottom mini-player bar with local album art thumbnail and >= 48 dp targets.
 */
@Composable
fun MiniPlayerBar(
    playbackState: PlaybackControllerState,
    playbackPositionMsFlow: StateFlow<Long>,
    artworkLoader: AlbumArtworkLoader,
    onOpenNowPlaying: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val track = playbackState.activeTrack ?: return

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
            )
            MiniPlayerProgressBar(
                durationMs = playbackState.durationMs,
                playbackPositionMsFlow = playbackPositionMsFlow,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenNowPlaying)
                    .semantics { contentDescription = "Open Now Playing for ${track.title}" }
                    .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TrackArtworkThumbnail(
                        track = track,
                        artworkLoader = artworkLoader,
                        size = 44.dp,
                        cornerRadius = 8.dp,
                        targetSizePx = AlbumArtworkLoader.THUMBNAIL_SIZE_PX,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = track.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "${track.artist} · ${track.formatBadge}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Spacer(modifier = Modifier.width(4.dp))

                IconButton(
                    onClick = onTogglePlayPause,
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                ) {
                    Icon(
                        imageVector = if (playbackState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playbackState.isPlaying) "Pause playback" else "Resume playback",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(26.dp),
                    )
                }

                IconButton(
                    onClick = onNext,
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.SkipNext,
                        contentDescription = "Next track",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}
