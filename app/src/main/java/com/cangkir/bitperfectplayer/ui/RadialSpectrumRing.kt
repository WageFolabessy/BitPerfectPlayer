package com.cangkir.bitperfectplayer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cangkir.bitperfectplayer.audio.AlbumPaletteExtractor
import com.cangkir.bitperfectplayer.audio.KineticSparkPool
import com.cangkir.bitperfectplayer.audio.SpectrumAnalyzer
import com.cangkir.bitperfectplayer.audio.SpectrumAnalyzerDsp
import com.cangkir.bitperfectplayer.audio.SpectrumColorMapper
import com.cangkir.bitperfectplayer.data.SpectrumColorStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// 316.dp container around 240.dp vinyl disc = 38.dp radial headroom on all sides for bars + sparks
val RADIAL_SPECTRUM_CONTAINER_DIAMETER: Dp = 316.dp
val RADIAL_SPECTRUM_VINYL_DIAMETER: Dp = 240.dp

private const val FFT_POLL_INTERVAL_MS = 18L // ~55 Hz off-thread FFT snapshot

// Precomputed 64 unit direction vectors starting at 12 o'clock (-90°) proceeding clockwise.
private val RADIAL_COS_64: FloatArray = FloatArray(SpectrumAnalyzerDsp.RADIAL_BAR_COUNT) { k ->
    val angleRad = -PI / 2.0 + (2.0 * PI * k) / SpectrumAnalyzerDsp.RADIAL_BAR_COUNT
    cos(angleRad).toFloat()
}
private val RADIAL_SIN_64: FloatArray = FloatArray(SpectrumAnalyzerDsp.RADIAL_BAR_COUNT) { k ->
    val angleRad = -PI / 2.0 + (2.0 * PI * k) / SpectrumAnalyzerDsp.RADIAL_BAR_COUNT
    sin(angleRad).toFloat()
}

// Precomputed normalized bass (0.0f) -> treble (1.0f) position for each of the 64 mirrored radial bars.
private val RADIAL_BAND_POS_64: FloatArray = FloatArray(SpectrumAnalyzerDsp.RADIAL_BAR_COUNT) { k ->
    val bandIdx = if (k < SpectrumAnalyzerDsp.BAND_COUNT) {
        SpectrumAnalyzerDsp.BAND_COUNT - 1 - k
    } else {
        k - SpectrumAnalyzerDsp.BAND_COUNT
    }
    bandIdx.toFloat() / (SpectrumAnalyzerDsp.BAND_COUNT - 1).toFloat()
}

// Precomputed normalized bass (0.0f) -> treble (1.0f) position for 32 linear compact bars.
private val LINEAR_BAND_POS_32: FloatArray = FloatArray(SpectrumAnalyzerDsp.BAND_COUNT) { i ->
    i.toFloat() / (SpectrumAnalyzerDsp.BAND_COUNT - 1).toFloat()
}

/**
 * Pre-allocated mutable state buffers for zero-recomposition, zero-GC 60/120 Hz Canvas spectrum
 * and Kinetic Sparks rendering. [drawFrameTick] is read strictly inside `Canvas { ... }`.
 */
private class SpectrumVisualizerBuffers {
    val analyzer = SpectrumAnalyzer()
    val monoWindow = FloatArray(SpectrumAnalyzerDsp.FFT_SIZE)
    val rawBands = FloatArray(SpectrumAnalyzerDsp.BAND_COUNT)
    val targetLock = Any()
    val latestTargetBands = FloatArray(SpectrumAnalyzerDsp.BAND_COUNT)
    val frameTargetCopy = FloatArray(SpectrumAnalyzerDsp.BAND_COUNT)
    val smoothedBands = FloatArray(SpectrumAnalyzerDsp.BAND_COUNT)
    val radialBars = FloatArray(SpectrumAnalyzerDsp.RADIAL_BAR_COUNT)
    val sparkPool = KineticSparkPool()
    val drawFrameTick = mutableLongStateOf(0L)

    fun hasResidualMotion(): Boolean {
        if (sparkPool.activeCount > 0) return true
        for (i in smoothedBands.indices) {
            if (smoothedBands[i] >= SpectrumAnalyzerDsp.SETTLE_EPSILON) return true
        }
        return false
    }

    fun clearAll() {
        synchronized(targetLock) {
            latestTargetBands.fill(0f)
        }
        frameTargetCopy.fill(0f)
        smoothedBands.fill(0f)
        radialBars.fill(0f)
        sparkPool.clear()
    }
}

@Composable
private fun rememberSpectrumVisualizerBuffers(
    isEnabled: Boolean,
    isPlaying: Boolean,
    colorStyle: SpectrumColorStyle,
    sparksEnabled: Boolean,
    albumPalette: IntArray,
    trackKey: String,
    sampleSpectrumWindow: (FloatArray) -> Int,
): SpectrumVisualizerBuffers {
    val buffers = remember { SpectrumVisualizerBuffers() }
    val latestSampler = rememberUpdatedState(sampleSpectrumWindow)
    val latestColorStyle = rememberUpdatedState(colorStyle)
    val latestSparksEnabled = rememberUpdatedState(sparksEnabled)
    val latestAlbumPalette = rememberUpdatedState(albumPalette)

    // Clear kinetic sparks cleanly on track change or when sparks are toggled off.
    LaunchedEffect(trackKey, sparksEnabled) {
        if (!sparksEnabled || trackKey.isNotEmpty()) {
            buffers.sparkPool.clear()
            buffers.drawFrameTick.longValue = buffers.drawFrameTick.longValue + 1L
        }
    }

    // Repaint resting bars immediately if color style or album palette changes while paused.
    LaunchedEffect(colorStyle, albumPalette) {
        buffers.drawFrameTick.longValue = buffers.drawFrameTick.longValue + 1L
    }

    // Coroutine A: Off-audio-thread, off-main-thread FFT snapshot producer on Dispatchers.Default.
    // Runs strictly when enabled AND actively playing (0% CPU when paused or disabled).
    LaunchedEffect(isEnabled, isPlaying) {
        if (!isEnabled || !isPlaying) {
            synchronized(buffers.targetLock) {
                buffers.latestTargetBands.fill(0f)
            }
            return@LaunchedEffect
        }
        withContext(Dispatchers.Default) {
            while (isActive) {
                val effRate = latestSampler.value(buffers.monoWindow)
                if (effRate > 0) {
                    buffers.analyzer.computeSpectrumBands(
                        monoSamples = buffers.monoWindow,
                        effectiveSampleRateHz = effRate,
                        outBands = buffers.rawBands,
                    )
                    synchronized(buffers.targetLock) {
                        System.arraycopy(buffers.rawBands, 0, buffers.latestTargetBands, 0, buffers.rawBands.size)
                    }
                } else {
                    synchronized(buffers.targetLock) {
                        buffers.latestTargetBands.fill(0f)
                    }
                }
                delay(FFT_POLL_INTERVAL_MS)
            }
        }
    }

    // Coroutine B: 60/120 Hz ballistic smoother + Kinetic Sparks physics invalidating ONLY Canvas draw phase.
    // When paused, finishes decaying both bars (< 0.002f) and active sparks (0) and exits completely (0% idle GPU).
    LaunchedEffect(isEnabled, isPlaying) {
        if (!isEnabled) {
            buffers.clearAll()
            buffers.drawFrameTick.longValue = buffers.drawFrameTick.longValue + 1L
            return@LaunchedEffect
        }

        if (isPlaying) {
            var lastNanos = withFrameNanos { it }
            while (isActive) {
                val nowNanos = withFrameNanos { it }
                val dtSec = ((nowNanos - lastNanos) / 1_000_000_000.0).coerceIn(0.0, 0.1).toFloat()
                lastNanos = nowNanos

                synchronized(buffers.targetLock) {
                    System.arraycopy(
                        buffers.latestTargetBands,
                        0,
                        buffers.frameTargetCopy,
                        0,
                        buffers.frameTargetCopy.size,
                    )
                }
                SpectrumAnalyzerDsp.smoothBandsInPlace(buffers.smoothedBands, buffers.frameTargetCopy, dtSec)
                SpectrumAnalyzerDsp.mapBandsToSymmetricRadialBars(buffers.smoothedBands, buffers.radialBars)

                if (latestSparksEnabled.value) {
                    buffers.sparkPool.emitFromBands(
                        bands32 = buffers.smoothedBands,
                        dtSeconds = dtSec,
                        style = latestColorStyle.value,
                        albumPalette = latestAlbumPalette.value,
                    )
                    buffers.sparkPool.step(dtSec)
                } else if (buffers.sparkPool.activeCount > 0) {
                    buffers.sparkPool.clear()
                }
                buffers.drawFrameTick.longValue = nowNanos
            }
        } else {
            if (!buffers.hasResidualMotion()) {
                buffers.clearAll()
                buffers.drawFrameTick.longValue = buffers.drawFrameTick.longValue + 1L
                return@LaunchedEffect
            }
            buffers.frameTargetCopy.fill(0f)
            var lastNanos = withFrameNanos { it }
            while (isActive) {
                val nowNanos = withFrameNanos { it }
                val dtSec = ((nowNanos - lastNanos) / 1_000_000_000.0).coerceIn(0.0, 0.1).toFloat()
                lastNanos = nowNanos

                val maxBand = SpectrumAnalyzerDsp.smoothBandsInPlace(
                    buffers.smoothedBands,
                    buffers.frameTargetCopy,
                    dtSec,
                )
                SpectrumAnalyzerDsp.mapBandsToSymmetricRadialBars(buffers.smoothedBands, buffers.radialBars)
                val activeSparks = if (buffers.sparkPool.activeCount > 0) {
                    buffers.sparkPool.step(dtSec)
                } else {
                    0
                }
                buffers.drawFrameTick.longValue = nowNanos

                if (maxBand < SpectrumAnalyzerDsp.SETTLE_EPSILON && activeSparks == 0) {
                    buffers.clearAll()
                    buffers.drawFrameTick.longValue = nowNanos + 1L
                    break
                }
            }
        }
    }

    return buffers
}

/**
 * 64-tick radial ring spectrum visualizer + transient Kinetic Sparks wrapping the centered `240.dp`
 * `InteractiveVinylDisc` inside a `316.dp` container (`38.dp` radial headroom on every side).
 *
 * Performance & Touch Guarantees:
 * - Zero Compose recompositions and zero per-frame heap allocations at 60/120 Hz.
 * - Zero `BlurMaskFilter` / heavy GPU shaders (crisp velocity-aligned micro-streaks + core dots).
 * - Outer `Canvas` attaches zero pointer modifiers so vinyl scrubbing and scrolling pass unobstructed.
 */
@Composable
fun RadialSpectrumRing(
    isEnabled: Boolean,
    isPlaying: Boolean,
    sampleSpectrumWindow: (FloatArray) -> Int,
    modifier: Modifier = Modifier,
    colorStyle: SpectrumColorStyle = SpectrumColorStyle.PRISM,
    sparksEnabled: Boolean = true,
    albumPalette: IntArray = AlbumPaletteExtractor.FALLBACK_STUDIO_PALETTE,
    trackKey: String = "",
    content: @Composable BoxScope.() -> Unit,
) {
    val buffers = rememberSpectrumVisualizerBuffers(
        isEnabled = isEnabled,
        isPlaying = isPlaying,
        colorStyle = colorStyle,
        sparksEnabled = sparksEnabled,
        albumPalette = albumPalette,
        trackKey = trackKey,
        sampleSpectrumWindow = sampleSpectrumWindow,
    )
    val latestStyle = rememberUpdatedState(colorStyle)
    val latestPalette = rememberUpdatedState(albumPalette)

    Box(
        modifier = modifier.size(RADIAL_SPECTRUM_CONTAINER_DIAMETER),
        contentAlignment = Alignment.Center,
    ) {
        if (isEnabled) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .semantics {
                        contentDescription = "Audio spectrum visualizer ring"
                    },
            ) {
                // Read draw-phase tick exclusively inside Canvas so parent never recomposes.
                buffers.drawFrameTick.longValue

                val style = latestStyle.value
                val palette = latestPalette.value
                val cx = size.width * 0.5f
                val cy = size.height * 0.5f
                val vinylRadiusPx = (RADIAL_SPECTRUM_VINYL_DIAMETER / 2f).toPx()
                val startRadiusPx = vinylRadiusPx + 5.dp.toPx()
                val minTickPx = 2.5.dp.toPx()
                val maxTickPx = 18.dp.toPx()
                val strokeWidthPx = 2.dp.toPx()
                val dynamicSpanPx = (maxTickPx - minTickPx).coerceAtLeast(0f)

                val bars = buffers.radialBars
                for (k in 0 until SpectrumAnalyzerDsp.RADIAL_BAR_COUNT) {
                    val energy = bars[k]
                    val normPos = RADIAL_BAND_POS_64[k]
                    val cosA = RADIAL_COS_64[k]
                    val sinA = RADIAL_SIN_64[k]
                    val tickLen = minTickPx + dynamicSpanPx * energy
                    val endRadius = startRadiusPx + tickLen

                    val startOffset = Offset(cx + cosA * startRadiusPx, cy + sinA * startRadiusPx)
                    val endOffset = Offset(cx + cosA * endRadius, cy + sinA * endRadius)

                    drawLine(
                        color = Color(SpectrumColorMapper.resolveBarColorArgb(style, normPos, energy, palette)),
                        start = startOffset,
                        end = endOffset,
                        strokeWidth = strokeWidthPx,
                        cap = StrokeCap.Round,
                    )

                    if (energy > 0.68f) {
                        val tipStartRadius = startRadiusPx + tickLen * 0.72f
                        drawLine(
                            color = Color(SpectrumColorMapper.resolveTipColorArgb(style, normPos, energy, palette)),
                            start = Offset(cx + cosA * tipStartRadius, cy + sinA * tipStartRadius),
                            end = endOffset,
                            strokeWidth = strokeWidthPx,
                            cap = StrokeCap.Round,
                        )
                    }
                }

                // Crisp velocity-aligned Kinetic Sparks (no GPU blur masks, zero allocations).
                val pool = buffers.sparkPool
                if (pool.activeCount > 0) {
                    val maxRadiusPx = size.minDimension * 0.5f - 2.dp.toPx()
                    val edgeFadeSpanPx = 7.dp.toPx()
                    val minStreakPx = 3.dp.toPx()
                    val maxStreakPx = 6.5.dp.toPx()

                    for (slot in 0 until KineticSparkPool.MAX_SPARKS) {
                        if (!pool.active[slot]) continue
                        val life = pool.lifetimeSec[slot]
                        if (life <= 0f) continue
                        val progress = (pool.ageSec[slot] / life).coerceIn(0f, 1f)
                        val lifeFade = (1f - progress) * (1f - progress)

                        val angle = pool.angleRad[slot]
                        val cosA = cos(angle)
                        val sinA = sin(angle)
                        val tx = -sinA
                        val ty = cosA

                        val baseRadius = startRadiusPx + minTickPx + dynamicSpanPx * pool.launchEnergy[slot]
                        val curRadius = baseRadius + pool.distanceDp[slot].dp.toPx()
                        val tangentPx = pool.tangentOffsetDp[slot].dp.toPx()

                        val edgeAlpha = ((maxRadiusPx - curRadius) / edgeFadeSpanPx).coerceIn(0f, 1f)
                        val alpha = lifeFade * edgeAlpha
                        if (alpha <= 0.02f) continue

                        val clampedR = curRadius.coerceAtMost(maxRadiusPx)
                        val px = cx + cosA * clampedR + tx * tangentPx
                        val py = cy + sinA * clampedR + ty * tangentPx

                        val vr = pool.radialVelDp[slot]
                        val vt = pool.tangentVelDp[slot]
                        val speed = sqrt(vr * vr + vt * vt).coerceAtLeast(1f)
                        val dirX = (cosA * vr + tx * vt) / speed
                        val dirY = (sinA * vr + ty * vt) / speed
                        val streakLen = (minStreakPx + (maxStreakPx - minStreakPx) * (speed / KineticSparkPool.MAX_RADIAL_VEL_DP).coerceIn(0f, 1f)) *
                            (1f - progress * 0.45f)

                        val sparkColor = Color(pool.colorArgb[slot])
                        val dotSizePx = pool.sizeDp[slot].dp.toPx() * (1f - progress * 0.35f)

                        // Velocity-aligned crisp micro-streak
                        drawLine(
                            color = sparkColor.copy(alpha = (alpha * 0.58f).coerceIn(0f, 1f)),
                            start = Offset(px - dirX * streakLen, py - dirY * streakLen),
                            end = Offset(px, py),
                            strokeWidth = (dotSizePx * 0.65f).coerceAtLeast(1f),
                            cap = StrokeCap.Round,
                        )
                        // Bright core spark dot
                        drawCircle(
                            color = sparkColor.copy(alpha = (alpha * 0.96f).coerceIn(0f, 1f)),
                            radius = (dotSizePx * 0.5f).coerceAtLeast(0.8f),
                            center = Offset(px, py),
                        )
                    }
                }
            }
        }

        content()
    }
}

/**
 * Compact `42.dp`-tall (`24.dp` 32-bar zone + `18.dp` upward spark burst headroom) spectrum bar
 * visualizer displayed beneath `Square Cover` artwork. Supports all 3 [SpectrumColorStyle] palettes
 * and upward-launching [KineticSparkPool] particles with zero Compose recompositions.
 */
@Composable
fun CompactSpectrumBarStrip(
    isEnabled: Boolean,
    isPlaying: Boolean,
    sampleSpectrumWindow: (FloatArray) -> Int,
    modifier: Modifier = Modifier,
    colorStyle: SpectrumColorStyle = SpectrumColorStyle.PRISM,
    sparksEnabled: Boolean = true,
    albumPalette: IntArray = AlbumPaletteExtractor.FALLBACK_STUDIO_PALETTE,
    trackKey: String = "",
) {
    if (!isEnabled) return
    val buffers = rememberSpectrumVisualizerBuffers(
        isEnabled = true,
        isPlaying = isPlaying,
        colorStyle = colorStyle,
        sparksEnabled = sparksEnabled,
        albumPalette = albumPalette,
        trackKey = trackKey,
        sampleSpectrumWindow = sampleSpectrumWindow,
    )
    val latestStyle = rememberUpdatedState(colorStyle)
    val latestPalette = rememberUpdatedState(albumPalette)

    Canvas(
        modifier = modifier
            .width(272.dp)
            .height(42.dp)
            .semantics {
                contentDescription = "Audio spectrum visualizer bar strip"
            },
    ) {
        buffers.drawFrameTick.longValue

        val style = latestStyle.value
        val palette = latestPalette.value
        val count = SpectrumAnalyzerDsp.BAND_COUNT
        val strokeWidthPx = 2.5.dp.toPx()
        val horizontalPadPx = strokeWidthPx
        val usableWidth = (size.width - horizontalPadPx * 2f).coerceAtLeast(1f)
        val stepX = usableWidth / (count - 1).coerceAtLeast(1)
        val baseY = size.height - 2.dp.toPx()
        val minBarPx = 2.dp.toPx()
        val maxBarPx = 22.dp.toPx().coerceAtMost((size.height - 18.dp.toPx()).coerceAtLeast(minBarPx))
        val spanPx = (maxBarPx - minBarPx).coerceAtLeast(0f)

        val bands = buffers.smoothedBands
        for (i in 0 until count) {
            val energy = bands[i]
            val normPos = LINEAR_BAND_POS_32[i]
            val x = horizontalPadPx + i * stepX
            val barHeight = minBarPx + spanPx * energy
            val topY = baseY - barHeight

            drawLine(
                color = Color(SpectrumColorMapper.resolveBarColorArgb(style, normPos, energy, palette)),
                start = Offset(x, baseY),
                end = Offset(x, topY),
                strokeWidth = strokeWidthPx,
                cap = StrokeCap.Round,
            )

            if (energy > 0.68f) {
                val tipStartY = baseY - barHeight * 0.72f
                drawLine(
                    color = Color(SpectrumColorMapper.resolveTipColorArgb(style, normPos, energy, palette)),
                    start = Offset(x, tipStartY),
                    end = Offset(x, topY),
                    strokeWidth = strokeWidthPx,
                    cap = StrokeCap.Round,
                )
            }
        }

        // Upward-launching Kinetic Sparks in the 18.dp headroom above the 24.dp bar zone.
        val pool = buffers.sparkPool
        if (pool.activeCount > 0) {
            val topEdgePx = 2.dp.toPx()
            val edgeFadeSpanPx = 6.dp.toPx()
            val minStreakPx = 2.5.dp.toPx()
            val maxStreakPx = 5.5.dp.toPx()

            for (slot in 0 until KineticSparkPool.MAX_SPARKS) {
                if (!pool.active[slot]) continue
                val life = pool.lifetimeSec[slot]
                if (life <= 0f) continue
                val progress = (pool.ageSec[slot] / life).coerceIn(0f, 1f)
                val lifeFade = (1f - progress) * (1f - progress)

                val launchX = horizontalPadPx + pool.normalizedX[slot] * usableWidth
                val launchY = baseY - (minBarPx + spanPx * pool.launchEnergy[slot])
                val px = (launchX + pool.tangentOffsetDp[slot].dp.toPx()).coerceIn(horizontalPadPx, size.width - horizontalPadPx)
                val py = launchY - pool.distanceDp[slot].dp.toPx() * 0.62f

                val edgeAlpha = ((py - topEdgePx) / edgeFadeSpanPx).coerceIn(0f, 1f)
                val alpha = lifeFade * edgeAlpha
                if (alpha <= 0.02f) continue

                val clampedY = py.coerceAtLeast(topEdgePx)
                val vy = -pool.radialVelDp[slot] * 0.62f
                val vx = pool.tangentVelDp[slot]
                val speed = sqrt(vx * vx + vy * vy).coerceAtLeast(1f)
                val dirX = vx / speed
                val dirY = vy / speed
                val streakLen = (minStreakPx + (maxStreakPx - minStreakPx) * (speed / KineticSparkPool.MAX_RADIAL_VEL_DP).coerceIn(0f, 1f)) *
                    (1f - progress * 0.45f)

                val sparkColor = Color(pool.colorArgb[slot])
                val dotSizePx = pool.sizeDp[slot].dp.toPx() * (1f - progress * 0.35f)

                drawLine(
                    color = sparkColor.copy(alpha = (alpha * 0.58f).coerceIn(0f, 1f)),
                    start = Offset(px - dirX * streakLen, clampedY - dirY * streakLen),
                    end = Offset(px, clampedY),
                    strokeWidth = (dotSizePx * 0.65f).coerceAtLeast(1f),
                    cap = StrokeCap.Round,
                )
                drawCircle(
                    color = sparkColor.copy(alpha = (alpha * 0.96f).coerceIn(0f, 1f)),
                    radius = (dotSizePx * 0.5f).coerceAtLeast(0.8f),
                    center = Offset(px, clampedY),
                )
            }
        }
    }
}
