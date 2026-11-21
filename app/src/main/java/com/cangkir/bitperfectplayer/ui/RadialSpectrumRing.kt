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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cangkir.bitperfectplayer.audio.SpectrumAnalyzer
import com.cangkir.bitperfectplayer.audio.SpectrumAnalyzerDsp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

val RADIAL_SPECTRUM_CONTAINER_DIAMETER: Dp = 304.dp
val RADIAL_SPECTRUM_VINYL_DIAMETER: Dp = 248.dp

private const val FFT_POLL_INTERVAL_MS = 18L // ~55 Hz off-thread FFT snapshot

// Matte obsidian studio spectrum palette (zero rainbow, zero gaudy neon glow)
private val SpectrumRestingSlate = Color(0x663D3D47) // #3D3D47 @ 40% alpha
private val SpectrumAccentIce = Color(0xE67BA7D9)    // #7BA7D9 @ 90% alpha
private val SpectrumTransientTip = Color(0xFAD7E5F5) // #D7E5F5 @ 98% alpha

// Precomputed 64 unit direction vectors starting at 12 o'clock (-90°) proceeding clockwise.
// Zero trig calls per 120 Hz Canvas draw frame.
private val RADIAL_COS_64: FloatArray = FloatArray(SpectrumAnalyzerDsp.RADIAL_BAR_COUNT) { k ->
    val angleRad = -PI / 2.0 + (2.0 * PI * k) / SpectrumAnalyzerDsp.RADIAL_BAR_COUNT
    cos(angleRad).toFloat()
}
private val RADIAL_SIN_64: FloatArray = FloatArray(SpectrumAnalyzerDsp.RADIAL_BAR_COUNT) { k ->
    val angleRad = -PI / 2.0 + (2.0 * PI * k) / SpectrumAnalyzerDsp.RADIAL_BAR_COUNT
    sin(angleRad).toFloat()
}

/**
 * Pre-allocated mutable state buffers for zero-recomposition, zero-GC 60/120 Hz Canvas spectrum
 * rendering. [drawFrameTick] is read strictly inside `Canvas { ... }` draw lambdas.
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
    val drawFrameTick = mutableLongStateOf(0L)

    fun hasResidualEnergy(): Boolean {
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
    }
}

@Composable
private fun rememberSpectrumVisualizerBuffers(
    isEnabled: Boolean,
    isPlaying: Boolean,
    sampleSpectrumWindow: (FloatArray) -> Int,
): SpectrumVisualizerBuffers {
    val buffers = remember { SpectrumVisualizerBuffers() }
    val latestSampler = rememberUpdatedState(sampleSpectrumWindow)

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

    // Coroutine B: 60/120 Hz frame-rate-independent ballistic smoother invalidating ONLY Canvas draw phase.
    // When paused, smoothly decays bars down to resting height and terminates completely (< 0.002f).
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
                buffers.drawFrameTick.longValue = nowNanos
            }
        } else {
            // Paused: if already resting at zero, stop immediately with 0 idle frames.
            if (!buffers.hasResidualEnergy()) {
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
                buffers.drawFrameTick.longValue = nowNanos

                if (maxBand < SpectrumAnalyzerDsp.SETTLE_EPSILON) {
                    buffers.clearAll()
                    buffers.drawFrameTick.longValue = nowNanos + 1L
                    break
                }
            }
        }
    }

    return buffers
}

private fun blendSpectrumColor(energy: Float): Color {
    val clamped = energy.coerceIn(0f, 1f)
    return if (clamped <= 0.75f) {
        lerp(SpectrumRestingSlate, SpectrumAccentIce, clamped / 0.75f)
    } else {
        lerp(SpectrumAccentIce, SpectrumTransientTip, (clamped - 0.75f) / 0.25f)
    }
}

/**
 * Minimalist 64-tick radial ring spectrum visualizer radiating cleanly around the centered
 * `248.dp` `InteractiveVinylDisc` inside a `304.dp` container.
 *
 * Performance & Touch Guarantees:
 * - Zero Compose recompositions at 60/120 Hz (draw invalidation occurs solely inside `Canvas`).
 * - Outer `Canvas` attaches zero pointer modifiers so vinyl scrubbing and vertical scrolling pass
 *   through completely unobstructed.
 */
@Composable
fun RadialSpectrumRing(
    isEnabled: Boolean,
    isPlaying: Boolean,
    sampleSpectrumWindow: (FloatArray) -> Int,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val buffers = rememberSpectrumVisualizerBuffers(
        isEnabled = isEnabled,
        isPlaying = isPlaying,
        sampleSpectrumWindow = sampleSpectrumWindow,
    )

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

                val cx = size.width * 0.5f
                val cy = size.height * 0.5f
                val vinylRadiusPx = (RADIAL_SPECTRUM_VINYL_DIAMETER / 2f).toPx()
                val startRadiusPx = vinylRadiusPx + 6.dp.toPx()
                val minTickPx = 2.5.dp.toPx()
                val maxTickPx = 20.dp.toPx()
                val strokeWidthPx = 2.dp.toPx()
                val dynamicSpanPx = (maxTickPx - minTickPx).coerceAtLeast(0f)

                val bars = buffers.radialBars
                for (k in 0 until SpectrumAnalyzerDsp.RADIAL_BAR_COUNT) {
                    val energy = bars[k]
                    val cosA = RADIAL_COS_64[k]
                    val sinA = RADIAL_SIN_64[k]
                    val tickLen = minTickPx + dynamicSpanPx * energy
                    val endRadius = startRadiusPx + tickLen

                    val startOffset = Offset(cx + cosA * startRadiusPx, cy + sinA * startRadiusPx)
                    val endOffset = Offset(cx + cosA * endRadius, cy + sinA * endRadius)

                    drawLine(
                        color = blendSpectrumColor(energy),
                        start = startOffset,
                        end = endOffset,
                        strokeWidth = strokeWidthPx,
                        cap = StrokeCap.Round,
                    )

                    // Subtle soft transient tip highlight on strong musical peaks.
                    if (energy > 0.65f) {
                        val tipStartRadius = startRadiusPx + tickLen * 0.72f
                        val tipAlpha = ((energy - 0.65f) / 0.35f).coerceIn(0f, 1f) * 0.75f
                        drawLine(
                            color = SpectrumTransientTip.copy(alpha = tipAlpha),
                            start = Offset(cx + cosA * tipStartRadius, cy + sinA * tipStartRadius),
                            end = endOffset,
                            strokeWidth = strokeWidthPx,
                            cap = StrokeCap.Round,
                        )
                    }
                }
            }
        }

        content()
    }
}

/**
 * Compact `28.dp`-tall 32-bar monochrome studio hairline spectrum strip displayed beneath
 * `Square Cover` artwork when the spectrum visualizer is enabled. Shares the exact same
 * zero-recomposition `Canvas` and off-thread FFT pipeline as [RadialSpectrumRing].
 */
@Composable
fun CompactSpectrumBarStrip(
    isEnabled: Boolean,
    isPlaying: Boolean,
    sampleSpectrumWindow: (FloatArray) -> Int,
    modifier: Modifier = Modifier,
) {
    if (!isEnabled) return
    val buffers = rememberSpectrumVisualizerBuffers(
        isEnabled = true,
        isPlaying = isPlaying,
        sampleSpectrumWindow = sampleSpectrumWindow,
    )

    Canvas(
        modifier = modifier
            .width(272.dp)
            .height(28.dp)
            .semantics {
                contentDescription = "Audio spectrum visualizer bar strip"
            },
    ) {
        buffers.drawFrameTick.longValue

        val count = SpectrumAnalyzerDsp.BAND_COUNT
        val strokeWidthPx = 2.5.dp.toPx()
        val horizontalPadPx = strokeWidthPx
        val usableWidth = (size.width - horizontalPadPx * 2f).coerceAtLeast(1f)
        val stepX = usableWidth / (count - 1).coerceAtLeast(1)
        val baseY = size.height - 2.dp.toPx()
        val minBarPx = 2.dp.toPx()
        val maxBarPx = (size.height - 4.dp.toPx()).coerceAtLeast(minBarPx)
        val spanPx = maxBarPx - minBarPx

        val bands = buffers.smoothedBands
        for (i in 0 until count) {
            val energy = bands[i]
            val x = horizontalPadPx + i * stepX
            val barHeight = minBarPx + spanPx * energy
            val topY = baseY - barHeight

            drawLine(
                color = blendSpectrumColor(energy),
                start = Offset(x, baseY),
                end = Offset(x, topY),
                strokeWidth = strokeWidthPx,
                cap = StrokeCap.Round,
            )

            if (energy > 0.65f) {
                val tipStartY = baseY - barHeight * 0.72f
                val tipAlpha = ((energy - 0.65f) / 0.35f).coerceIn(0f, 1f) * 0.75f
                drawLine(
                    color = SpectrumTransientTip.copy(alpha = tipAlpha),
                    start = Offset(x, tipStartY),
                    end = Offset(x, topY),
                    strokeWidth = strokeWidthPx,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}
