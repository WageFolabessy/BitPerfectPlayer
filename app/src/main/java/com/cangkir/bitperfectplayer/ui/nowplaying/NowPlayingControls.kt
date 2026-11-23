package com.cangkir.bitperfectplayer.ui.nowplaying

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cangkir.bitperfectplayer.audio.AlbumPaletteExtractor
import com.cangkir.bitperfectplayer.audio.PlaybackTrack
import com.cangkir.bitperfectplayer.audio.RepeatMode
import com.cangkir.bitperfectplayer.audio.TrackTransitionStatePolicy
import com.cangkir.bitperfectplayer.library.AlbumArtworkLoader
import com.cangkir.bitperfectplayer.library.EmbeddedArtworkSlot
import com.cangkir.bitperfectplayer.ui.TrackArtworkThumbnail
import com.cangkir.bitperfectplayer.ui.formatDurationMs
import com.cangkir.bitperfectplayer.ui.rememberDelayedPreparingIndicator
import com.cangkir.bitperfectplayer.ui.theme.ObsidianBackground
import com.cangkir.bitperfectplayer.ui.theme.TextMuted
import kotlinx.coroutines.flow.StateFlow

internal const val NOW_PLAYING_SQUARE_ARTWORK_TEST_TAG = "now_playing_square_artwork"

internal fun extractAlbumPaletteFromBitmap(bitmap: Bitmap?): IntArray {
    val validBitmap = bitmap?.takeIf { !it.isRecycled && it.width > 0 && it.height > 0 }
        ?: return AlbumPaletteExtractor.FALLBACK_STUDIO_PALETTE
    return runCatching {
        val readableBitmap = if (validBitmap.config == Bitmap.Config.HARDWARE) {
            validBitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            validBitmap
        }
        try {
            val w = readableBitmap.width
            val h = readableBitmap.height
            val pixels = IntArray(w * h)
            readableBitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            AlbumPaletteExtractor.extractAccentPalette(pixels, w, h)
        } finally {
            if (readableBitmap !== validBitmap) readableBitmap.recycle()
        }
    }.getOrDefault(AlbumPaletteExtractor.FALLBACK_STUDIO_PALETTE)
}

@Composable
internal fun SwipeableSquareArtwork(
    track: PlaybackTrack,
    albumArtworkLoader: AlbumArtworkLoader,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    artworkSize: androidx.compose.ui.unit.Dp = 272.dp,
    onBoundsInRootChanged: (Rect) -> Unit = {},
) {
    DisposableEffect(Unit) {
        onDispose { onBoundsInRootChanged(Rect.Zero) }
    }
    val swipeThresholdPx = with(LocalDensity.current) { 76.dp.toPx() }
    var artworkDragOffsetPx by remember(track.uriString) { mutableFloatStateOf(0f) }
    var showBackCover by remember(track.uriString) { mutableStateOf(false) }
    var backCoverBitmap by remember(track.uriString, track.dateModifiedSeconds) {
        mutableStateOf<Bitmap?>(
            albumArtworkLoader.getCachedTrackSlotBitmap(
                track = track,
                slot = EmbeddedArtworkSlot.BACK_COVER,
                targetSizePx = AlbumArtworkLoader.HERO_SIZE_PX,
            ),
        )
    }

    LaunchedEffect(track.uriString, track.dateModifiedSeconds) {
        showBackCover = false
        if (backCoverBitmap == null) {
            backCoverBitmap = albumArtworkLoader.loadTrackSlotBitmap(
                track = track,
                slot = EmbeddedArtworkSlot.BACK_COVER,
                targetSizePx = AlbumArtworkLoader.HERO_SIZE_PX,
            )
        }
    }

    val currentBackBitmap = backCoverBitmap
    val backImageBitmap = remember(currentBackBitmap) {
        if (currentBackBitmap != null && !currentBackBitmap.isRecycled) currentBackBitmap.asImageBitmap() else null
    }

    Box(
        modifier = Modifier
            .size(artworkSize)
            .testTag(NOW_PLAYING_SQUARE_ARTWORK_TEST_TAG)
            .onGloballyPositioned { coords -> onBoundsInRootChanged(coords.boundsInRoot()) }
            .pointerInput(track.uriString, swipeThresholdPx) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val finalDrag = artworkDragOffsetPx
                        artworkDragOffsetPx = 0f
                        when {
                            finalDrag <= -swipeThresholdPx -> onNext()
                            finalDrag >= swipeThresholdPx -> onPrevious()
                        }
                    },
                    onDragCancel = {
                        artworkDragOffsetPx = 0f
                    },
                    onHorizontalDrag = { _, dragAmount ->
                        artworkDragOffsetPx = (artworkDragOffsetPx + dragAmount)
                            .coerceIn(-swipeThresholdPx * 1.6f, swipeThresholdPx * 1.6f)
                    },
                )
            }
            .graphicsLayer {
                translationX = artworkDragOffsetPx * 0.32f
            },
    ) {
        if (showBackCover && backImageBitmap != null) {
            Box(
                modifier = Modifier
                    .size(artworkSize)
                    .clip(RoundedCornerShape(16.dp))
                    .background(ObsidianBackground),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    bitmap = backImageBitmap,
                    contentDescription = "Back cover artwork for ${track.title}",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(artworkSize),
                )
            }
        } else {
            TrackArtworkThumbnail(
                track = track,
                artworkLoader = albumArtworkLoader,
                size = artworkSize,
                cornerRadius = 16.dp,
                targetSizePx = AlbumArtworkLoader.HERO_SIZE_PX,
            )
        }

        if (backImageBitmap != null) {
            IconButton(
                onClick = { showBackCover = !showBackCover },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.58f)),
            ) {
                Icon(
                    imageVector = Icons.Filled.Flip,
                    contentDescription = if (showBackCover) "Show front cover" else "Show back cover",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * Narrow leaf composable collecting 20 Hz playback position and optional live vinyl scrub preview
 * so Now Playing's hero artwork, metadata, transport controls, and audio-mode strip never recompose
 * during playback or rotational scrubbing.
 */
@Composable
internal fun NowPlayingSeekSection(
    activeTrack: PlaybackTrack,
    engineTrackUri: String?,
    durationMs: Long,
    playbackPositionMsFlow: StateFlow<Long>,
    externalScrubPreviewMsState: State<Long?>,
    onSeekTo: (Long) -> Unit,
    onSliderBoundsInRootChanged: (Rect) -> Unit = {},
) {
    DisposableEffect(Unit) {
        onDispose { onSliderBoundsInRootChanged(Rect.Zero) }
    }
    val livePositionMs by playbackPositionMsFlow.collectAsStateWithLifecycle()
    val maxPos = durationMs.coerceAtLeast(1L)
    val syncedPosMs = TrackTransitionStatePolicy.resolveDisplayPositionMs(
        activeTrack = activeTrack,
        engineTrackUri = engineTrackUri,
        livePositionMs = livePositionMs,
    )
    val currentPosMs = syncedPosMs.coerceIn(0L, maxPos)
    val externalPreviewMs = externalScrubPreviewMsState.value
    var isScrubbing by remember(activeTrack.uriString) { mutableStateOf(false) }
    var scrubPositionMs by remember(activeTrack.uriString) { mutableFloatStateOf(0f) }

    val displayedPosMs = when {
        isScrubbing -> scrubPositionMs.toLong().coerceIn(0L, maxPos)
        externalPreviewMs != null -> externalPreviewMs.coerceIn(0L, maxPos)
        else -> currentPosMs
    }
    val sliderMax = maxPos.toFloat()
    val durationLabel = remember(durationMs) { formatDurationMs(durationMs) }

    Slider(
        value = displayedPosMs.toFloat().coerceIn(0f, sliderMax),
        onValueChange = { newVal ->
            isScrubbing = true
            scrubPositionMs = newVal
        },
        onValueChangeFinished = {
            val target = scrubPositionMs.toLong()
            isScrubbing = false
            onSeekTo(target)
        },
        valueRange = 0f..sliderMax,
        colors = SliderDefaults.colors(
            thumbColor = MaterialTheme.colorScheme.primary,
            activeTrackColor = MaterialTheme.colorScheme.primary,
            inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords -> onSliderBoundsInRootChanged(coords.boundsInRoot()) },
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = formatDurationMs(displayedPosMs),
            style = MaterialTheme.typography.labelSmall,
            color = if (externalPreviewMs != null) MaterialTheme.colorScheme.primary else TextMuted,
            maxLines = 1,
            softWrap = false,
        )
        Text(
            text = durationLabel,
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
internal fun NowPlayingTransportRow(
    isPlaying: Boolean,
    shuffleEnabled: Boolean,
    repeatMode: RepeatMode,
    onToggleShuffle: () -> Unit,
    onPrevious: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onCycleRepeatMode: () -> Unit,
    isPreparingPlayback: Boolean = false,
) {
    val showPreparingHalo = rememberDelayedPreparingIndicator(isPreparingPlayback)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onToggleShuffle,
            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Shuffle,
                contentDescription = if (shuffleEnabled) "Disable shuffle" else "Enable shuffle",
                tint = if (shuffleEnabled) MaterialTheme.colorScheme.primary else TextMuted,
            )
        }

        IconButton(
            onClick = onPrevious,
            modifier = Modifier.sizeIn(minWidth = 52.dp, minHeight = 52.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.SkipPrevious,
                contentDescription = "Previous track",
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }

        Box(
            modifier = Modifier.size(68.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (showPreparingHalo) {
                CircularProgressIndicator(
                    strokeWidth = 2.5.dp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(68.dp)
                        .semantics { contentDescription = "Preparing playback" },
                )
            }
            FilledIconButton(
                onClick = onTogglePlayPause,
                modifier = Modifier.size(62.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pause playback" else "Play audio",
                    modifier = Modifier.size(32.dp),
                )
            }
        }

        IconButton(
            onClick = onNext,
            modifier = Modifier.sizeIn(minWidth = 52.dp, minHeight = 52.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.SkipNext,
                contentDescription = "Next track",
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }

        IconButton(
            onClick = onCycleRepeatMode,
            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
        ) {
            Icon(
                imageVector = if (repeatMode == RepeatMode.ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                contentDescription = "Repeat mode: ${repeatMode.name}",
                tint = if (repeatMode != RepeatMode.OFF) MaterialTheme.colorScheme.primary else TextMuted,
            )
        }
    }
}

@Composable
internal fun NowPlayingAudioModeStrip(modeDotColor: Color, modeShortLabel: String, activeDeviceName: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .sizeIn(minHeight = 48.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(modeDotColor),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "$modeShortLabel · $activeDeviceName",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = "Configure audio output mode",
                tint = TextMuted,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
internal fun NowPlayingUtilityStrip(
    onOpenQueue: () -> Unit,
    onOpenLyrics: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    isLyricsActive: Boolean = false,
    isBitPerfectActive: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onOpenQueue,
            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                contentDescription = "Open playback queue",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        IconButton(
            onClick = onOpenLyrics,
            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Lyrics,
                contentDescription = "Lyrics",
                tint = if (isLyricsActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        IconButton(
            onClick = onOpenDiagnostics,
            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.GraphicEq,
                contentDescription = "Audio Path",
                tint = if (isBitPerfectActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
