package com.cangkir.bitperfectplayer.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cangkir.bitperfectplayer.audio.AudioFormatFormatter
import com.cangkir.bitperfectplayer.audio.AudioOutputMode
import com.cangkir.bitperfectplayer.audio.AudioStartPathPolicy
import com.cangkir.bitperfectplayer.audio.NotificationSubtitleFormatter
import com.cangkir.bitperfectplayer.audio.PlaybackControllerState
import com.cangkir.bitperfectplayer.audio.PlaybackTrack
import com.cangkir.bitperfectplayer.audio.TrackTransitionStatePolicy
import com.cangkir.bitperfectplayer.library.AlbumArtworkLoader
import com.cangkir.bitperfectplayer.ui.theme.AmberIndicatedContainer
import com.cangkir.bitperfectplayer.ui.theme.EmeraldSafeContainer
import com.cangkir.bitperfectplayer.ui.theme.OnAmberIndicatedContainer
import com.cangkir.bitperfectplayer.ui.theme.OnEmeraldSafeContainer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

const val MINI_PLAYER_BAR_TEST_TAG = "mini_player_bar"

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
 * Renders a single-line `Artist · Compact Audio Spec` row where long artist names ellipsize while
 * the compact `kHz` audio specification (`FLAC 24-bit · 96 kHz`, `MP3 · 44.1 kHz`, etc.) stays
 * pinned and unclipped on the right. Shared by [MiniPlayerBar] and [QueueScreen].
 */
@Composable
fun PinnedArtistAudioSpecRow(artist: String, compactSpec: String, modifier: Modifier = Modifier) {
    val cleanArtist = remember(artist) { artist.trim() }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            cleanArtist.isNotEmpty() && compactSpec.isNotEmpty() -> {
                Text(
                    text = cleanArtist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    text = " · $compactSpec",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            compactSpec.isNotEmpty() -> {
                Text(
                    text = compactSpec,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            else -> {
                Text(
                    text = cleanArtist.ifEmpty { NotificationSubtitleFormatter.UNKNOWN_ARTIST_LABEL },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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
private fun MiniPlayerProgressBar(
    activeTrack: PlaybackTrack,
    engineTrackUri: String?,
    durationMs: Long,
    playbackPositionMsFlow: StateFlow<Long>,
) {
    val livePositionMs by playbackPositionMsFlow.collectAsStateWithLifecycle()
    val currentPositionMs = TrackTransitionStatePolicy.resolveDisplayPositionMs(
        activeTrack = activeTrack,
        engineTrackUri = engineTrackUri,
        livePositionMs = livePositionMs,
    )
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
 * Minimalist persistent bottom mini-player bar with local album art thumbnail, `>= 48 dp` targets,
 * and right-to-left swipe-to-dismiss (stopping playback and clearing the active Now Playing session).
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
    onDismissPlayback: () -> Unit = {},
) {
    val track = playbackState.activeTrack ?: return
    val currentOnDismissPlayback by rememberUpdatedState(onDismissPlayback)
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val minDismissThresholdPx = with(density) { MiniPlayerSwipeMath.MIN_DISMISS_THRESHOLD_DP.dp.toPx() }
    val maxDismissThresholdPx = with(density) { MiniPlayerSwipeMath.MAX_DISMISS_THRESHOLD_DP.dp.toPx() }
    val flingVelocityThresholdPxPerSec =
        with(density) { MiniPlayerSwipeMath.FLING_VELOCITY_THRESHOLD_DP_PER_SEC.dp.toPx() }
    val minFlingDragPx = with(density) { MiniPlayerSwipeMath.MIN_FLING_DRAG_DP.dp.toPx() }

    var containerWidthPx by remember { mutableFloatStateOf(0f) }
    var isDismissing by remember(track.uriString) { mutableStateOf(false) }
    val translationX = remember { Animatable(0f) }
    LaunchedEffect(track.uriString) {
        translationX.snapTo(0f)
        isDismissing = false
    }
    val dismissThresholdPx = MiniPlayerSwipeMath.computeDismissThresholdPx(
        containerWidthPx = containerWidthPx,
        minThresholdPx = minDismissThresholdPx,
        maxThresholdPx = maxDismissThresholdPx,
    )

    val engineTrackUri = playbackState.engineState.activeTrackUri?.toString()
    val diagnosticsMatchTrack = TrackTransitionStatePolicy.isEngineSyncedToActiveTrack(
        activeTrack = track,
        engineTrackUri = engineTrackUri,
        engineStatus = playbackState.engineState.status,
    )
    val runtimeSourceSampleRateHz = if (diagnosticsMatchTrack) playbackState.engineState.diagnostics.sourceSampleRateHz else 0
    val runtimeSourceBitDepth = if (diagnosticsMatchTrack) playbackState.engineState.diagnostics.sourceBitDepth else null
    val compactSpec = remember(
        track.container,
        track.codec,
        track.bitDepth,
        track.sampleRateHz,
        runtimeSourceSampleRateHz,
        runtimeSourceBitDepth,
    ) {
        NotificationSubtitleFormatter.formatAudioSpec(
            track = track,
            runtimeSampleRateHz = runtimeSourceSampleRateHz,
            runtimeBitDepth = runtimeSourceBitDepth,
        )
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag(MINI_PLAYER_BAR_TEST_TAG)
            .onSizeChanged { containerWidthPx = it.width.toFloat() }
            .pointerInput(
                track.uriString,
                minDismissThresholdPx,
                maxDismissThresholdPx,
                flingVelocityThresholdPxPerSec,
                minFlingDragPx,
            ) {
                val touchSlop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (isDismissing) return@awaitEachGesture
                    val pointerId = down.id
                    val velocityTracker = VelocityTracker()
                    velocityTracker.addPosition(down.uptimeMillis, down.position)

                    var prevPos = down.position
                    var totalDx = 0f
                    var totalDy = 0f
                    var rawDragX = 0f
                    var horizontalLocked = false
                    var gestureAborted = false
                    var dragEnded = false

                    while (!dragEnded && !gestureAborted) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == pointerId } ?: event.changes.firstOrNull()
                        if (change == null || !change.pressed) {
                            dragEnded = true
                        } else {
                            velocityTracker.addPosition(change.uptimeMillis, change.position)
                            val stepDx = change.position.x - prevPos.x
                            val stepDy = change.position.y - prevPos.y
                            prevPos = change.position
                            totalDx += stepDx
                            totalDy += stepDy

                            if (!horizontalLocked) {
                                when {
                                    change.isConsumed ||
                                        MiniPlayerSwipeMath.shouldAbortForVerticalDrag(totalDx, totalDy, touchSlop) -> {
                                        gestureAborted = true
                                    }
                                    MiniPlayerSwipeMath.shouldLockHorizontalDrag(totalDx, totalDy, touchSlop) -> {
                                        horizontalLocked = true
                                        rawDragX = totalDx
                                        if (change.positionChanged()) {
                                            change.consume()
                                        }
                                        val clamped = MiniPlayerSwipeMath.clampLeftwardTranslationPx(
                                            rawDragX = rawDragX,
                                            containerWidthPx = size.width.toFloat(),
                                        )
                                        coroutineScope.launch { translationX.snapTo(clamped) }
                                    }
                                }
                            } else {
                                rawDragX += stepDx
                                if (change.positionChanged()) {
                                    change.consume()
                                }
                                val clamped = MiniPlayerSwipeMath.clampLeftwardTranslationPx(
                                    rawDragX = rawDragX,
                                    containerWidthPx = size.width.toFloat(),
                                )
                                coroutineScope.launch { translationX.snapTo(clamped) }
                            }
                        }
                    }

                    if (horizontalLocked && !gestureAborted) {
                        val effectiveThresholdPx = MiniPlayerSwipeMath.computeDismissThresholdPx(
                            containerWidthPx = size.width.toFloat(),
                            minThresholdPx = minDismissThresholdPx,
                            maxThresholdPx = maxDismissThresholdPx,
                        )
                        val velocityX = velocityTracker.calculateVelocity().x
                        val shouldDismiss = MiniPlayerSwipeMath.shouldDismissOnRelease(
                            totalDragX = rawDragX,
                            velocityXPxPerSec = velocityX,
                            dismissThresholdPx = effectiveThresholdPx,
                            flingVelocityThresholdPxPerSec = flingVelocityThresholdPxPerSec,
                            minFlingDragPx = minFlingDragPx,
                        )
                        if (shouldDismiss) {
                            isDismissing = true
                            val targetOffscreenX = -size.width.toFloat().coerceAtLeast(1f)
                            coroutineScope.launch {
                                try {
                                    translationX.animateTo(
                                        targetValue = targetOffscreenX,
                                        animationSpec = tween(durationMillis = 140, easing = FastOutSlowInEasing),
                                    )
                                    currentOnDismissPlayback()
                                } finally {
                                    isDismissing = false
                                }
                            }
                        } else {
                            coroutineScope.launch {
                                translationX.animateTo(
                                    targetValue = 0f,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                        stiffness = Spring.StiffnessMediumLow,
                                    ),
                                )
                            }
                        }
                    } else if (translationX.value != 0f) {
                        coroutineScope.launch { translationX.snapTo(0f) }
                    }
                }
            }
            .graphicsLayer {
                this.translationX = translationX.value
                this.alpha = MiniPlayerSwipeMath.computeAlpha(
                    translationX = translationX.value,
                    dismissThresholdPx = dismissThresholdPx,
                )
            },
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
            )
            MiniPlayerProgressBar(
                activeTrack = track,
                engineTrackUri = engineTrackUri,
                durationMs = playbackState.durationMs,
                playbackPositionMsFlow = playbackPositionMsFlow,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !isDismissing, onClick = onOpenNowPlaying)
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
                        PinnedArtistAudioSpecRow(
                            artist = track.artist,
                            compactSpec = compactSpec,
                        )
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                val showPreparingIndicator = rememberDelayedPreparingIndicator(playbackState.isPreparingPlayback)
                Box(
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (showPreparingIndicator) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .size(38.dp)
                                .semantics { contentDescription = "Preparing playback" },
                        )
                    }
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

@Composable
internal fun rememberDelayedPreparingIndicator(isPreparingPlayback: Boolean): Boolean {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(isPreparingPlayback) {
        if (!isPreparingPlayback) {
            visible = false
        } else {
            visible = false
            delay(AudioStartPathPolicy.PREPARING_INDICATOR_DELAY_MS)
            visible = true
        }
    }
    return visible
}
