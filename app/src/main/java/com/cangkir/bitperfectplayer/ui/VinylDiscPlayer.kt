package com.cangkir.bitperfectplayer.ui

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cangkir.bitperfectplayer.audio.PlaybackTrack
import com.cangkir.bitperfectplayer.audio.VinylScrubMath
import com.cangkir.bitperfectplayer.library.AlbumArtworkLoader
import com.cangkir.bitperfectplayer.ui.theme.IcePrimary
import com.cangkir.bitperfectplayer.ui.theme.ObsidianSurfaceVariant
import com.cangkir.bitperfectplayer.ui.theme.TextPrimary
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToLong

private val VINYL_DISC_DIAMETER = 272.dp
private val VINYL_ARTWORK_DIAMETER = 158.dp // ~58% of 272.dp
private val VINYL_LABEL_RING_DIAMETER = 22.dp
private val VINYL_SPINDLE_DIAMETER = 14.dp

private val VinylBaseInner = Color(0xFF15151A)
private val VinylBaseMid = Color(0xFF101014)
private val VinylBaseOuter = Color(0xFF09090C)
private val VinylOuterRim = Color(0xFF33333D)
private val VinylArtworkBorder = Color(0xFF2B2B33)
private val VinylSpindleHole = Color(0xFF08080B)
private val VinylSpindleRim = Color(0xFF3A3A45)
private val VinylScrubPillSurface = Color(0xEB17171D)
private val VinylScrubPillBorder = Color(0xFF34343E)

/**
 * GPU-accelerated interactive spinning Vinyl Record / Turntable Disc for `NowPlayingScreen`.
 *
 * Performance & Audio Integrity Guarantees:
 * - Rotational animation updates strictly inside `Modifier.graphicsLayer { rotationZ = ... }` for
 *   **0 recompositions and 0 relayouts** at 60/120 fps.
 * - Stationary studio specular sheen sits outside the rotating `graphicsLayer` for realistic depth.
 * - Velocity-sensitive rotational touch scrubbing + catchable bounded flick inertia (`<= 450 ms`)
 *   commits `onSeekTo(...)` **strictly once** upon release/settle (never per drag frame), preventing
 *   `MediaCodec`/`AudioTrack` seek-flush stutter on 96/192 kHz Hi-Res FLAC.
 * - Live scrub preview milliseconds are pushed into [scrubPreviewMsState] without forcing the parent
 *   `NowPlayingScreen` to recompose; only the compact pill leaf and `NowPlayingSeekSection` observe it.
 */
@Composable
fun InteractiveVinylDisc(
    track: PlaybackTrack,
    isPlaying: Boolean,
    durationMs: Long,
    playbackPositionMsFlow: StateFlow<Long>,
    scrubPreviewMsState: MutableState<Long?>,
    albumArtworkLoader: AlbumArtworkLoader,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()

    val latestOnSeekTo by rememberUpdatedState(onSeekTo)
    val latestPositionFlow by rememberUpdatedState(playbackPositionMsFlow)

    // Angle persists across Play/Pause (freezes immediately when paused, never snaps back to 0°).
    val rotationDegrees = remember { mutableFloatStateOf(0f) }
    val scrubAnchorMs = remember { mutableLongStateOf(0L) }

    var isUserDragging by remember { mutableStateOf(false) }
    var isCoasting by remember { mutableStateOf(false) }
    var coastJob by remember { mutableStateOf<Job?>(null) }

    // Cancel any active coast and clear scrub preview cleanly on track switch or screen disposal.
    DisposableEffect(track.uriString) {
        onDispose {
            coastJob?.cancel()
            coastJob = null
            isCoasting = false
            isUserDragging = false
            scrubPreviewMsState.value = null
        }
    }

    // Smooth 60/120 fps clockwise playback auto-spin (20 deg/s = 18s per revolution).
    // Freezes cleanly at the exact current angle whenever paused, dragging, or coasting.
    LaunchedEffect(isPlaying, isUserDragging, isCoasting) {
        if (!isPlaying || isUserDragging || isCoasting) return@LaunchedEffect
        var lastFrameNanos = withFrameNanos { it }
        while (true) {
            val frameNanos = withFrameNanos { it }
            val dtSec = ((frameNanos - lastFrameNanos) / 1_000_000_000.0).coerceIn(0.0, 0.1)
            lastFrameNanos = frameNanos
            rotationDegrees.floatValue =
                (rotationDegrees.floatValue + (VinylScrubMath.AUTO_SPIN_DEG_PER_SEC * dtSec).toFloat()) % 360f
        }
    }

    Box(
        modifier = modifier
            .size(VINYL_DISC_DIAMETER)
            .semantics {
                contentDescription = "Vinyl disc turntable for ${track.title}. Drag circularly to seek."
            }
            .pointerInput(track.uriString, durationMs) {
                if (durationMs <= 0L) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val centerX = size.width / 2f
                    val centerY = size.height / 2f
                    val outerRadiusPx = minOf(centerX, centerY)
                    val innerDeadZonePx = outerRadiusPx * 0.18f

                    val downDx = down.position.x - centerX
                    val downDy = down.position.y - centerY
                    if (!VinylScrubMath.isWithinScrubRing(downDx, downDy, outerRadiusPx, innerDeadZonePx)) {
                        return@awaitEachGesture
                    }

                    // Catch active inertia coast seamlessly if the user grabs the spinning disc.
                    val caughtPreviewMs = if (isCoasting) {
                        coastJob?.cancel()
                        coastJob = null
                        isCoasting = false
                        scrubPreviewMsState.value
                    } else {
                        null
                    }

                    down.consume()
                    isUserDragging = true

                    val startPosMs = (caughtPreviewMs ?: latestPositionFlow.value).coerceIn(0L, durationMs)
                    if (caughtPreviewMs == null) {
                        scrubAnchorMs.longValue = startPosMs
                    } else {
                        scrubPreviewMsState.value = startPosMs
                    }

                    var previewPosDouble = startPosMs.toDouble()
                    var prevAngleDeg = Math.toDegrees(atan2(downDy.toDouble(), downDx.toDouble())).toFloat()
                    var prevTimeMs = down.uptimeMillis
                    var lastMoveTimeMs = down.uptimeMillis
                    var releaseTimeMs = down.uptimeMillis
                    var smoothedVelocity = 0f
                    var hapticAccumDeg = 0f
                    var didScrubOrCatchCoast = caughtPreviewMs != null

                    val pointerId = down.id
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                            releaseTimeMs = change.uptimeMillis
                            if (!change.pressed) {
                                change.consume()
                                break
                            }

                            val dx = change.position.x - centerX
                            val dy = change.position.y - centerY
                            val nowMs = change.uptimeMillis
                            val dtMs = nowMs - prevTimeMs
                            change.consume()

                            // Skip samples inside the center spindle dead zone to avoid atan2 jitter.
                            if (dx * dx + dy * dy < innerDeadZonePx * innerDeadZonePx) {
                                prevTimeMs = nowMs
                                continue
                            }

                            val currentAngleDeg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                            val deltaDeg = VinylScrubMath.computeShortestAngleDeltaDegrees(prevAngleDeg, currentAngleDeg)
                            prevAngleDeg = currentAngleDeg
                            prevTimeMs = nowMs

                            if (deltaDeg != 0f) {
                                didScrubOrCatchCoast = true
                                lastMoveTimeMs = nowMs
                                val instantVelocity = if (dtMs >= VinylScrubMath.MIN_VALID_DT_MS) {
                                    deltaDeg / (dtMs / 1000f)
                                } else {
                                    smoothedVelocity
                                }
                                smoothedVelocity = VinylScrubMath.smoothAngularVelocity(
                                    previousDegPerSec = smoothedVelocity,
                                    instantDegPerSec = instantVelocity,
                                    dtMs = dtMs,
                                )

                                rotationDegrees.floatValue = (rotationDegrees.floatValue + deltaDeg) % 360f
                                val deltaMs = VinylScrubMath.computeIncrementalScrubDeltaMs(deltaDeg, smoothedVelocity)
                                previewPosDouble = VinylScrubMath.clampScrubPositionMs(
                                    positionMs = previewPosDouble + deltaMs,
                                    durationMs = durationMs,
                                )
                                scrubPreviewMsState.value = previewPosDouble.roundToLong().coerceIn(0L, durationMs)

                                hapticAccumDeg += abs(deltaDeg)
                                if (hapticAccumDeg >= VinylScrubMath.HAPTIC_TICK_INTERVAL_DEG) {
                                    hapticAccumDeg %= VinylScrubMath.HAPTIC_TICK_INTERVAL_DEG
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            } else if (dtMs > VinylScrubMath.MAX_VALID_DT_MS) {
                                smoothedVelocity = 0f
                            }
                        }
                    } finally {
                        isUserDragging = false
                    }

                    // Avoid needless MediaCodec seek flushes on zero-movement taps.
                    if (!didScrubOrCatchCoast) {
                        scrubPreviewMsState.value = null
                        return@awaitEachGesture
                    }

                    // If finger held still on target before lifting, cancel stale flick momentum.
                    val releaseStalenessMs = (releaseTimeMs - lastMoveTimeMs).coerceAtLeast(0L)
                    val releaseVelocity = if (releaseStalenessMs > VinylScrubMath.MAX_RELEASE_STALENESS_MS) {
                        0f
                    } else {
                        smoothedVelocity
                    }

                    val finalDragMs = previewPosDouble.roundToLong().coerceIn(0L, durationMs)
                    if (!VinylScrubMath.shouldStartInertiaCoast(releaseVelocity)) {
                        scrubPreviewMsState.value = null
                        latestOnSeekTo(finalDragMs)
                    } else {
                        isCoasting = true
                        val initialCoastPos = previewPosDouble
                        coastJob = coroutineScope.launch {
                            try {
                                var vel = releaseVelocity
                                var posDouble = initialCoastPos
                                val startNanos = withFrameNanos { it }
                                var lastNanos = startNanos
                                var coastHapticAccum = 0f

                                while (isActive) {
                                    val nowNanos = withFrameNanos { it }
                                    val elapsedMs = (nowNanos - startNanos) / 1_000_000L
                                    val dtSec = ((nowNanos - lastNanos) / 1_000_000_000.0)
                                        .coerceIn(0.0, 0.05)
                                        .toFloat()
                                    lastNanos = nowNanos

                                    val frameDeltaDeg = vel * dtSec
                                    rotationDegrees.floatValue = (rotationDegrees.floatValue + frameDeltaDeg) % 360f
                                    val frameDeltaMs = VinylScrubMath.computeIncrementalScrubDeltaMs(frameDeltaDeg, vel)
                                    posDouble = VinylScrubMath.clampScrubPositionMs(
                                        positionMs = posDouble + frameDeltaMs,
                                        durationMs = durationMs,
                                    )
                                    scrubPreviewMsState.value = posDouble.roundToLong().coerceIn(0L, durationMs)

                                    coastHapticAccum += abs(frameDeltaDeg)
                                    if (coastHapticAccum >= VinylScrubMath.HAPTIC_TICK_INTERVAL_DEG) {
                                        coastHapticAccum %= VinylScrubMath.HAPTIC_TICK_INTERVAL_DEG
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }

                                    vel = VinylScrubMath.decayInertiaVelocity(vel, dtSec)

                                    val hitBoundary = (posDouble <= 0.0 && vel < 0f) ||
                                        (posDouble >= durationMs.toDouble() && vel > 0f)
                                    if (hitBoundary ||
                                        elapsedMs >= VinylScrubMath.INERTIA_MAX_DURATION_MS ||
                                        abs(vel) < VinylScrubMath.INERTIA_STOP_VELOCITY_DEG_PER_SEC
                                    ) {
                                        break
                                    }
                                }

                                val settledMs = posDouble.roundToLong().coerceIn(0L, durationMs)
                                isCoasting = false
                                coastJob = null
                                scrubPreviewMsState.value = null
                                latestOnSeekTo(settledMs)
                            } catch (ce: CancellationException) {
                                throw ce
                            }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // Layer 1: GPU-rotated Vinyl Disc + Circular Album Artwork + Center Spindle
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    rotationZ = rotationDegrees.floatValue
                },
            contentAlignment = Alignment.Center,
        ) {
            VinylGroovesCanvas(modifier = Modifier.fillMaxSize())

            VinylCircularArtwork(
                track = track,
                artworkLoader = albumArtworkLoader,
                modifier = Modifier.size(VINYL_ARTWORK_DIAMETER),
            )

            // Subtle inner label ring around spindle
            Box(
                modifier = Modifier
                    .size(VINYL_LABEL_RING_DIAMETER)
                    .clip(CircleShape)
                    .border(
                        width = 0.75.dp,
                        color = Color.Black.copy(alpha = 0.55f),
                        shape = CircleShape,
                    ),
            )

            // Matte obsidian center spindle hole
            Box(
                modifier = Modifier
                    .size(VINYL_SPINDLE_DIAMETER)
                    .clip(CircleShape)
                    .background(VinylSpindleHole)
                    .border(
                        width = 0.75.dp,
                        color = VinylSpindleRim,
                        shape = CircleShape,
                    ),
            )
        }

        // Layer 2: Stationary overhead studio specular sheen (stays fixed while disc rotates underneath)
        VinylStationarySheenCanvas(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape),
        )

        // Layer 3: Isolated scrub HUD pill (only this narrow leaf recomposes while scrubbing/coasting)
        VinylScrubPillLeaf(
            scrubPreviewMsState = scrubPreviewMsState,
            scrubAnchorMsState = scrubAnchorMs,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 10.dp),
        )
    }
}

@Composable
private fun VinylGroovesCanvas(
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val radius = size.minDimension / 2f
        val center = Offset(size.width / 2f, size.height / 2f)
        val innerLabelRadius = radius * 0.58f

        // Matte obsidian vinyl base gradient (#15151A -> #101014 -> #09090C)
        drawCircle(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0.0f to VinylBaseInner,
                    0.56f to VinylBaseInner,
                    0.78f to VinylBaseMid,
                    1.0f to VinylBaseOuter,
                ),
                center = center,
                radius = radius,
            ),
            radius = radius,
            center = center,
        )

        // Crisp, restrained concentric micro-grooves across the playing surface
        val grooveStart = innerLabelRadius + 5.dp.toPx()
        val grooveEnd = radius - 4.dp.toPx()
        val grooveCount = 22
        val grooveSpan = (grooveEnd - grooveStart).coerceAtLeast(1f)
        val thinStroke = 0.65.dp.toPx()
        val bandGapStroke = 1.4.dp.toPx()

        for (i in 0 until grooveCount) {
            val fraction = i.toFloat() / (grooveCount - 1).toFloat()
            val r = grooveStart + grooveSpan * fraction
            val isTrackBandGap = (i == 7 || i == 15)
            if (isTrackBandGap) {
                drawCircle(
                    color = Color.Black.copy(alpha = 0.42f),
                    radius = r,
                    center = center,
                    style = Stroke(width = bandGapStroke),
                )
            } else {
                val highlightAlpha = if (i % 3 == 0) 0.065f else 0.032f
                drawCircle(
                    color = Color.White.copy(alpha = highlightAlpha),
                    radius = r,
                    center = center,
                    style = Stroke(width = thinStroke),
                )
            }
        }

        // Subtle lead-out groove ring right outside the center label
        drawCircle(
            color = Color.Black.copy(alpha = 0.55f),
            radius = innerLabelRadius + 2.dp.toPx(),
            center = center,
            style = Stroke(width = 1.2.dp.toPx()),
        )

        // Restrained dark-metallic outer bevel rim (0.75.dp)
        drawCircle(
            color = VinylOuterRim,
            radius = radius - 0.5.dp.toPx(),
            center = center,
            style = Stroke(width = 0.75.dp.toPx()),
        )
    }
}

@Composable
private fun VinylStationarySheenCanvas(
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val radius = size.minDimension / 2f
        val center = Offset(size.width / 2f, size.height / 2f)
        val innerLabelRadius = radius * 0.58f
        val grooveBandWidth = (radius - innerLabelRadius - 1.dp.toPx()).coerceAtLeast(1f)
        val grooveMidRadius = innerLabelRadius + grooveBandWidth / 2f

        // Classic opposing anisotropic turntable sheen across the micro-grooves (stationary on screen)
        drawCircle(
            brush = Brush.sweepGradient(
                colorStops = arrayOf(
                    0.00f to Color.Transparent,
                    0.07f to Color.White.copy(alpha = 0.085f),
                    0.15f to Color.Transparent,
                    0.48f to Color.Transparent,
                    0.57f to Color.White.copy(alpha = 0.075f),
                    0.66f to Color.Transparent,
                    1.00f to Color.Transparent,
                ),
                center = center,
            ),
            radius = grooveMidRadius,
            center = center,
            style = Stroke(width = grooveBandWidth),
        )

        // Subtle soft diagonal top-left studio reflection across the whole disc
        drawCircle(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.055f),
                    Color.Transparent,
                    Color.Transparent,
                ),
                start = Offset(0f, 0f),
                end = Offset(size.width * 0.75f, size.height * 0.75f),
            ),
            radius = radius,
            center = center,
        )
    }
}

@Composable
private fun VinylCircularArtwork(
    track: PlaybackTrack,
    artworkLoader: AlbumArtworkLoader,
    modifier: Modifier = Modifier,
) {
    val targetSizePx = AlbumArtworkLoader.HERO_SIZE_PX
    val cacheIdentity = remember(track.uriString, track.album, track.artist, track.dateModifiedSeconds, targetSizePx) {
        artworkLoader.memoryKeyFor(track, targetSizePx)
    }
    var bitmap by remember(cacheIdentity) {
        mutableStateOf<Bitmap?>(artworkLoader.getCachedFromMemory(track, targetSizePx))
    }

    LaunchedEffect(cacheIdentity) {
        if (bitmap == null) {
            bitmap = artworkLoader.loadArtwork(track, targetSizePx)
        }
    }

    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(ObsidianSurfaceVariant)
            .border(width = 1.dp, color = VinylArtworkBorder, shape = CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        val currentBitmap = bitmap
        val imageBitmap = remember(currentBitmap) {
            if (currentBitmap != null && !currentBitmap.isRecycled) currentBitmap.asImageBitmap() else null
        }
        if (imageBitmap != null) {
            Image(
                bitmap = imageBitmap,
                contentDescription = "Album art for ${track.title}",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = Icons.Filled.MusicNote,
                contentDescription = "Default album artwork for ${track.title}",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(48.dp),
            )
        }
    }
}

/**
 * Narrow leaf composable rendering the live target timestamp and signed delta pill (`02:14  +00:19`)
 * exclusively while the user is actively scrubbing or coasting the vinyl disc.
 */
@Composable
private fun VinylScrubPillLeaf(
    scrubPreviewMsState: State<Long?>,
    scrubAnchorMsState: LongState,
    modifier: Modifier = Modifier,
) {
    val previewMs = scrubPreviewMsState.value ?: return
    val deltaMs = previewMs - scrubAnchorMsState.longValue

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = VinylScrubPillSurface,
        border = BorderStroke(0.5.dp, VinylScrubPillBorder),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = formatDurationMs(previewMs),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
                maxLines = 1,
                softWrap = false,
            )
            Text(
                text = VinylScrubMath.formatSignedDeltaMs(deltaMs),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = IcePrimary,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}
