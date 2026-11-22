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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cangkir.bitperfectplayer.audio.PlaybackTrack
import com.cangkir.bitperfectplayer.audio.VinylScrubMath
import com.cangkir.bitperfectplayer.library.AlbumArtworkLoader
import com.cangkir.bitperfectplayer.ui.theme.IcePrimary
import com.cangkir.bitperfectplayer.ui.theme.ObsidianSurfaceVariant
import com.cangkir.bitperfectplayer.ui.theme.TextPrimary
import com.cangkir.bitperfectplayer.ui.theme.TextSecondary
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToLong
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val VINYL_DISC_DIAMETER = 272.dp

private const val HOLD_WATCHDOG_POLL_MS = 35L
private const val HOLD_IDLE_THRESHOLD_NS = 65_000_000L

private val VinylBaseInner = Color(0xFF15151A)
private val VinylBaseMid = Color(0xFF101014)
private val VinylBaseOuter = Color(0xFF09090C)
private val VinylOuterRim = Color(0xFF33333D)
private val VinylArtworkBorder = Color(0xFF2B2B33)
private val VinylSpindleHole = Color(0xFF08080B)
private val VinylSpindleRim = Color(0xFF3A3A45)
private val VinylScrubPillSurface = Color(0xEB17171D)
private val VinylScrubPillBorder = Color(0xFF34343E)

/** Non-observable mutable holder for imperative turntable gesture/coroutine handles (zero recompositions). */
private class VinylTurntableSessionHolder {
    var isLiveSessionActive: Boolean = false
    var coastJob: Job? = null
    var holdWatchdogJob: Job? = null
    var lastMoveWallNano: Long = 0L
    var holdEngaged: Boolean = true
}

/**
 * GPU-accelerated interactive spinning Vinyl Record / Turntable Disc for `NowPlayingScreen`.
 *
 * Performance & Audio Integrity Guarantees:
 * - Rotational animation updates strictly inside `Modifier.graphicsLayer { rotationZ = ... }` for
 *   **0 recompositions and 0 relayouts** at 60/120 fps.
 * - Stationary studio specular sheen sits outside the rotating `graphicsLayer` for realistic depth.
 * - **Safe Mode while playing (`isSafeMode && isPlaying`):** engages real-time turntable DSP
 *   (forward varispeed, hold-to-stop, and reverse scratch) with bounded flick inertia (`<= 450 ms`),
 *   keeping `scrubPreviewMsState.value = null` so `NowPlayingSeekSection` tracks the live engine
 *   position seamlessly.
 * - **Paused OR Bit-Perfect Opt-In mode:** never invokes turntable DSP; preserves silent
 *   rotational seek-preview and commits `onSeekTo(...)` **strictly once** upon release/settle.
 */
@Composable
fun InteractiveVinylDisc(
    track: PlaybackTrack,
    isPlaying: Boolean,
    isSafeMode: Boolean,
    durationMs: Long,
    playbackPositionMsFlow: StateFlow<Long>,
    scrubPreviewMsState: MutableState<Long?>,
    albumArtworkLoader: AlbumArtworkLoader,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier,
    discDiameter: Dp = VINYL_DISC_DIAMETER,
    onBeginTurntableScratch: () -> Boolean = { false },
    onUpdateTurntableScratchRate: (Float) -> Unit = {},
    onEndTurntableScratch: (Long?) -> Unit = {},
) {
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()

    val latestOnSeekTo by rememberUpdatedState(onSeekTo)
    val latestOnBeginScratch by rememberUpdatedState(onBeginTurntableScratch)
    val latestOnUpdateScratchRate by rememberUpdatedState(onUpdateTurntableScratchRate)
    val latestOnEndScratch by rememberUpdatedState(onEndTurntableScratch)
    val latestPositionFlow by rememberUpdatedState(playbackPositionMsFlow)
    val latestIsPlaying by rememberUpdatedState(isPlaying)
    val latestIsSafeMode by rememberUpdatedState(isSafeMode)

    // Angle persists across Play/Pause (freezes immediately when paused, never snaps back to 0°).
    val rotationDegrees = remember { mutableFloatStateOf(0f) }
    val scrubAnchorMs = remember { mutableLongStateOf(0L) }
    // Narrow leaf state for live scratch speed badge (NEVER read in InteractiveVinylDisc body).
    val liveScratchSpeedState = remember { mutableStateOf<Float?>(null) }
    val session = remember { VinylTurntableSessionHolder() }

    var isUserDragging by remember { mutableStateOf(false) }
    var isCoasting by remember { mutableStateOf(false) }

    fun abortLiveSessionIfActive() {
        session.holdWatchdogJob?.cancel()
        session.holdWatchdogJob = null
        session.coastJob?.cancel()
        session.coastJob = null
        isCoasting = false
        liveScratchSpeedState.value = null
        if (session.isLiveSessionActive) {
            session.isLiveSessionActive = false
            latestOnEndScratch(null)
        }
    }

    // Cancel active coast/scratch cleanly on track switch or screen disposal.
    DisposableEffect(track.uriString) {
        onDispose {
            abortLiveSessionIfActive()
            isUserDragging = false
            scrubPreviewMsState.value = null
        }
    }

    // Immediately abort any live turntable session if playback pauses, auto-pauses, or switches to Bit-Perfect.
    LaunchedEffect(isPlaying, isSafeMode) {
        if ((!isPlaying || !isSafeMode) && session.isLiveSessionActive) {
            abortLiveSessionIfActive()
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

    val artworkDiameter = discDiameter * 0.58f
    val labelRingDiameter = (discDiameter * 0.08f).coerceAtLeast(18.dp)
    val spindleDiameter = (discDiameter * 0.052f).coerceAtLeast(12.dp)

    Box(
        modifier = modifier
            .size(discDiameter)
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
                    val caughtCoast = isCoasting
                    val caughtPreviewMs = if (caughtCoast) {
                        session.coastJob?.cancel()
                        session.coastJob = null
                        isCoasting = false
                        scrubPreviewMsState.value
                    } else {
                        null
                    }

                    down.consume()
                    isUserDragging = true

                    // Engage Safe-Mode live turntable DSP if actively playing in Safe Mode (or continue caught live coast).
                    val canEngageLiveScratch = latestIsPlaying && latestIsSafeMode
                    val liveMode = if (caughtCoast && session.isLiveSessionActive && canEngageLiveScratch) {
                        true
                    } else if (!caughtCoast && canEngageLiveScratch) {
                        val started = latestOnBeginScratch()
                        session.isLiveSessionActive = started
                        started
                    } else {
                        if (session.isLiveSessionActive) {
                            session.isLiveSessionActive = false
                            latestOnEndScratch(null)
                        }
                        false
                    }

                    val startPosMs = (caughtPreviewMs ?: latestPositionFlow.value).coerceIn(0L, durationMs)
                    if (!caughtCoast) {
                        scrubAnchorMs.longValue = startPosMs
                    }

                    if (liveMode) {
                        scrubPreviewMsState.value = null
                        liveScratchSpeedState.value = 0f
                        latestOnUpdateScratchRate(0f)
                        session.lastMoveWallNano = System.nanoTime()
                        session.holdEngaged = true
                        session.holdWatchdogJob?.cancel()
                        session.holdWatchdogJob = coroutineScope.launch {
                            while (isActive && session.isLiveSessionActive) {
                                delay(HOLD_WATCHDOG_POLL_MS)
                                if (!session.isLiveSessionActive) break
                                if (!session.holdEngaged &&
                                    (System.nanoTime() - session.lastMoveWallNano) > HOLD_IDLE_THRESHOLD_NS
                                ) {
                                    session.holdEngaged = true
                                    liveScratchSpeedState.value = 0f
                                    latestOnUpdateScratchRate(0f)
                                }
                            }
                        }
                    } else if (caughtPreviewMs != null) {
                        scrubPreviewMsState.value = startPosMs
                    }

                    var previewPosDouble = startPosMs.toDouble()
                    var prevAngleDeg = Math.toDegrees(atan2(downDy.toDouble(), downDx.toDouble())).toFloat()
                    var prevTimeMs = down.uptimeMillis
                    var lastMoveTimeMs = down.uptimeMillis
                    var releaseTimeMs = down.uptimeMillis
                    var smoothedVelocity = 0f
                    var hapticAccumDeg = 0f
                    var didScrubOrCatchCoast = caughtCoast

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

                            if (liveMode) {
                                // If externally aborted mid-drag (auto-pause / Bit-Perfect switch), stay passive until finger lifts.
                                if (session.isLiveSessionActive && deltaDeg != 0f) {
                                    didScrubOrCatchCoast = true
                                    lastMoveTimeMs = nowMs
                                    session.lastMoveWallNano = System.nanoTime()
                                    session.holdEngaged = false

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

                                    val rate = VinylScrubMath.angularVelocityToPlaybackRate(smoothedVelocity)
                                    liveScratchSpeedState.value = rate
                                    latestOnUpdateScratchRate(rate)

                                    hapticAccumDeg += abs(deltaDeg)
                                    if (hapticAccumDeg >= VinylScrubMath.HAPTIC_TICK_INTERVAL_DEG) {
                                        hapticAccumDeg %= VinylScrubMath.HAPTIC_TICK_INTERVAL_DEG
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                } else if (dtMs > VinylScrubMath.MAX_VALID_DT_MS) {
                                    smoothedVelocity = 0f
                                    session.holdEngaged = true
                                    liveScratchSpeedState.value = 0f
                                    latestOnUpdateScratchRate(0f)
                                }
                            } else {
                                // Silent seek-preview mode (Paused or Bit-Perfect Opt-In): untouched zero-DSP path.
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
                        }
                    } finally {
                        session.holdWatchdogJob?.cancel()
                        session.holdWatchdogJob = null
                        isUserDragging = false
                    }

                    // Live Turntable Release Path
                    if (liveMode) {
                        if (!session.isLiveSessionActive) {
                            liveScratchSpeedState.value = null
                            return@awaitEachGesture
                        }
                        val releaseStalenessMs = (releaseTimeMs - lastMoveTimeMs).coerceAtLeast(0L)
                        val releaseVelocity = if (session.holdEngaged ||
                            !didScrubOrCatchCoast ||
                            releaseStalenessMs > VinylScrubMath.MAX_RELEASE_STALENESS_MS
                        ) {
                            0f
                        } else {
                            smoothedVelocity
                        }

                        if (!VinylScrubMath.shouldStartInertiaCoast(releaseVelocity)) {
                            session.isLiveSessionActive = false
                            liveScratchSpeedState.value = null
                            latestOnEndScratch(null)
                        } else {
                            isCoasting = true
                            session.coastJob = coroutineScope.launch {
                                var vel = releaseVelocity
                                val startNanos = withFrameNanos { it }
                                var lastNanos = startNanos
                                var coastHapticAccum = 0f

                                while (isActive && session.isLiveSessionActive) {
                                    val nowNanos = withFrameNanos { it }
                                    val elapsedMs = (nowNanos - startNanos) / 1_000_000L
                                    val dtSec = ((nowNanos - lastNanos) / 1_000_000_000.0)
                                        .coerceIn(0.0, 0.05)
                                        .toFloat()
                                    lastNanos = nowNanos

                                    val frameDeltaDeg = vel * dtSec
                                    rotationDegrees.floatValue = (rotationDegrees.floatValue + frameDeltaDeg) % 360f

                                    val rate = VinylScrubMath.angularVelocityToPlaybackRate(vel)
                                    liveScratchSpeedState.value = rate
                                    latestOnUpdateScratchRate(rate)

                                    coastHapticAccum += abs(frameDeltaDeg)
                                    if (coastHapticAccum >= VinylScrubMath.HAPTIC_TICK_INTERVAL_DEG) {
                                        coastHapticAccum %= VinylScrubMath.HAPTIC_TICK_INTERVAL_DEG
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }

                                    vel = VinylScrubMath.decayInertiaVelocity(vel, dtSec)

                                    val livePos = latestPositionFlow.value
                                    val hitBoundary = (livePos <= 0L && vel < 0f) ||
                                        (livePos >= durationMs && vel > 0f)
                                    if (hitBoundary ||
                                        elapsedMs >= VinylScrubMath.INERTIA_MAX_DURATION_MS ||
                                        abs(vel) < VinylScrubMath.INERTIA_STOP_VELOCITY_DEG_PER_SEC
                                    ) {
                                        break
                                    }
                                }

                                isCoasting = false
                                session.coastJob = null
                                liveScratchSpeedState.value = null
                                if (session.isLiveSessionActive) {
                                    session.isLiveSessionActive = false
                                    latestOnEndScratch(null)
                                }
                            }
                        }
                        return@awaitEachGesture
                    }

                    // Silent Seek-Preview Release Path (Paused or Bit-Perfect Opt-In)
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
                        session.coastJob = coroutineScope.launch {
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
                            session.coastJob = null
                            scrubPreviewMsState.value = null
                            latestOnSeekTo(settledMs)
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
                modifier = Modifier.size(artworkDiameter),
            )

            // Subtle inner label ring around spindle
            Box(
                modifier = Modifier
                    .size(labelRingDiameter)
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
                    .size(spindleDiameter)
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
            liveScratchSpeedState = liveScratchSpeedState,
            scrubAnchorMsState = scrubAnchorMs,
            playbackPositionMsFlow = playbackPositionMsFlow,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 10.dp),
        )
    }
}

@Composable
private fun VinylGroovesCanvas(modifier: Modifier = Modifier) {
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
private fun VinylStationarySheenCanvas(modifier: Modifier = Modifier) {
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
private fun VinylCircularArtwork(track: PlaybackTrack, artworkLoader: AlbumArtworkLoader, modifier: Modifier = Modifier) {
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
            // Option B: Preserve the authentic circular vinyl center label using uniform,
            // distortion-free center-fill over the aspect-preserved bitmap from AlbumArtworkLoader.
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
 * Narrow leaf composable rendering the live target timestamp, signed delta, and optional live
 * turntable speed badge (`02:14  +00:19  2.1×` / `HOLD`) exclusively while actively scrubbing or
 * coasting the vinyl disc.
 */
@Composable
private fun VinylScrubPillLeaf(
    scrubPreviewMsState: State<Long?>,
    liveScratchSpeedState: State<Float?>,
    scrubAnchorMsState: LongState,
    playbackPositionMsFlow: StateFlow<Long>,
    modifier: Modifier = Modifier,
) {
    val liveSpeed = liveScratchSpeedState.value
    val previewMs = scrubPreviewMsState.value
    if (liveSpeed == null && previewMs == null) return

    val displayMs = if (liveSpeed != null) {
        val enginePos by playbackPositionMsFlow.collectAsStateWithLifecycle()
        enginePos
    } else {
        previewMs ?: return
    }
    val deltaMs = displayMs - scrubAnchorMsState.longValue

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
                text = formatDurationMs(displayMs),
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
            if (liveSpeed != null) {
                Text(
                    text = VinylScrubMath.formatPlaybackRateBadge(liveSpeed),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = TextSecondary,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}
