package com.cangkir.bitperfectplayer.audio

import kotlin.math.abs
import kotlin.math.exp

/**
 * Pure-Kotlin rotational turntable geometry, adaptive velocity-sensitive scrub physics, and
 * inertia decay math for the interactive Vinyl Disc player (zero `android.*` imports).
 */
object VinylScrubMath {
    /** Calm, steady playback spin speed: 20 deg/s = 18 seconds per full 360-degree revolution. */
    const val AUTO_SPIN_DEG_PER_SEC: Float = 20.0f

    /** Velocity curve control points (degrees per second). */
    const val SLOW_VELOCITY_DEG_PER_SEC: Float = 45.0f
    const val NORMAL_VELOCITY_DEG_PER_SEC: Float = 180.0f
    const val FAST_VELOCITY_DEG_PER_SEC: Float = 540.0f

    /** Target seek span (milliseconds per full 360-degree turn) at each velocity control point. */
    const val MS_PER_ROTATION_SLOW: Double = 8_000.0
    const val MS_PER_ROTATION_NORMAL: Double = 30_000.0
    const val MS_PER_ROTATION_FAST: Double = 120_000.0

    /** Default exponential moving average smoothing factor for angular velocity. */
    const val DEFAULT_VELOCITY_SMOOTHING_ALPHA: Float = 0.30f

    /** Minimum and maximum valid inter-sample touch intervals (milliseconds). */
    const val MIN_VALID_DT_MS: Long = 1L
    const val MAX_VALID_DT_MS: Long = 200L

    /** Minimum release angular speed (deg/s) required to trigger bounded flick inertia coasting. */
    const val INERTIA_MIN_RELEASE_VELOCITY_DEG_PER_SEC: Float = 120.0f

    /** Speed threshold (deg/s) below which inertia coast settles immediately. */
    const val INERTIA_STOP_VELOCITY_DEG_PER_SEC: Float = 25.0f

    /** Upper time bound (milliseconds) for inertia coasting before committing the seek. */
    const val INERTIA_MAX_DURATION_MS: Long = 450L

    /** Default exponential friction damping factor for inertia decay (`1/s`). */
    const val DEFAULT_INERTIA_DAMPING: Float = 7.5f

    /** Angular travel interval (degrees) between subtle tactile detent ticks while scrubbing. */
    const val HAPTIC_TICK_INTERVAL_DEG: Float = 22.0f

    /** Maximum stationary hold time before finger release before flick velocity is zeroed. */
    const val MAX_RELEASE_STALENESS_MS: Long = 80L

    /**
     * Computes the shortest signed angular delta from [prevAngleDeg] to [currentAngleDeg],
     * normalized strictly into `(-180f, 180f]` so crossing the `±180°` seam never causes a jump.
     *
     * Under screen coordinates (`+x` right, `+y` down), `atan2(dy, dx)` increases clockwise,
     * so positive deltas represent clockwise (forward) motion and negative deltas counter-clockwise
     * (backward) motion.
     */
    fun computeShortestAngleDeltaDegrees(
        prevAngleDeg: Float,
        currentAngleDeg: Float,
    ): Float {
        if (!prevAngleDeg.isFinite() || !currentAngleDeg.isFinite()) return 0f
        var delta = (currentAngleDeg - prevAngleDeg) % 360f
        if (delta > 180f) {
            delta -= 360f
        } else if (delta <= -180f) {
            delta += 360f
        }
        return if (delta == -0.0f) 0f else delta
    }

    /**
     * Returns `true` strictly when `(dx, dy)` relative to the disc center lies within the
     * interactive scrub ring `[innerDeadZonePx, outerRadiusPx]`, rejecting touches outside the
     * disc boundary or too close to the center spindle where `atan2` jitters.
     */
    fun isWithinScrubRing(
        dx: Float,
        dy: Float,
        outerRadiusPx: Float,
        innerDeadZonePx: Float,
    ): Boolean {
        if (!dx.isFinite() || !dy.isFinite()) return false
        if (!outerRadiusPx.isFinite() || !innerDeadZonePx.isFinite()) return false
        if (outerRadiusPx <= 0f || innerDeadZonePx < 0f || innerDeadZonePx >= outerRadiusPx) return false
        val distSq = dx.toDouble() * dx.toDouble() + dy.toDouble() * dy.toDouble()
        val innerSq = innerDeadZonePx.toDouble() * innerDeadZonePx.toDouble()
        val outerSq = outerRadiusPx.toDouble() * outerRadiusPx.toDouble()
        return distSq >= innerSq && distSq <= outerSq
    }

    /**
     * Computes the continuous, monotonically increasing seek sensitivity (milliseconds per full
     * `360°` turn) as a function of rotational speed `|angularVelocityDegPerSec|`:
     * - `<= 45 deg/s` -> `8,000.0 ms` per rotation (fine precision scrubbing)
     * - `180 deg/s` -> `30,000.0 ms` per rotation (natural scrub)
     * - `>= 540 deg/s` -> `120,000.0 ms` per rotation (rapid jump / flick)
     *
     * Uses C1-smooth cubic smoothstep interpolation across `[45..180]` and `[180..540]`.
     */
    fun computeMsPerFullRotation(angularVelocityDegPerSec: Float): Double {
        if (!angularVelocityDegPerSec.isFinite()) return MS_PER_ROTATION_SLOW
        val speed = abs(angularVelocityDegPerSec).toDouble()
        return when {
            speed <= SLOW_VELOCITY_DEG_PER_SEC.toDouble() -> MS_PER_ROTATION_SLOW
            speed < NORMAL_VELOCITY_DEG_PER_SEC.toDouble() -> {
                val t = (speed - SLOW_VELOCITY_DEG_PER_SEC) /
                    (NORMAL_VELOCITY_DEG_PER_SEC - SLOW_VELOCITY_DEG_PER_SEC)
                MS_PER_ROTATION_SLOW + (MS_PER_ROTATION_NORMAL - MS_PER_ROTATION_SLOW) * smoothstep(t)
            }
            speed == NORMAL_VELOCITY_DEG_PER_SEC.toDouble() -> MS_PER_ROTATION_NORMAL
            speed < FAST_VELOCITY_DEG_PER_SEC.toDouble() -> {
                val t = (speed - NORMAL_VELOCITY_DEG_PER_SEC) /
                    (FAST_VELOCITY_DEG_PER_SEC - NORMAL_VELOCITY_DEG_PER_SEC)
                MS_PER_ROTATION_NORMAL + (MS_PER_ROTATION_FAST - MS_PER_ROTATION_NORMAL) * smoothstep(t)
            }
            else -> MS_PER_ROTATION_FAST
        }
    }

    /**
     * Computes the signed fractional millisecond seek increment for a single frame of angular
     * travel [deltaDegrees] at [angularVelocityDegPerSec]. Returning `Double` preserves sub-ms
     * fractional increments across 120 Hz slow-drag frames so accumulation never stalls at zero.
     */
    fun computeIncrementalScrubDeltaMs(
        deltaDegrees: Float,
        angularVelocityDegPerSec: Float,
    ): Double {
        if (!deltaDegrees.isFinite()) return 0.0
        val msPerRotation = computeMsPerFullRotation(abs(angularVelocityDegPerSec))
        return (deltaDegrees.toDouble() / 360.0) * msPerRotation
    }

    /**
     * Applies a light exponential moving average to suppress touch-digitizer velocity spikes while
     * ignoring degenerate timestamps (`dtMs < 1 ms`) and resetting velocity across finger pauses
     * (`dtMs > 200 ms`).
     */
    fun smoothAngularVelocity(
        previousDegPerSec: Float,
        instantDegPerSec: Float,
        alpha: Float = DEFAULT_VELOCITY_SMOOTHING_ALPHA,
        dtMs: Long = 16L,
    ): Float {
        val prev = if (previousDegPerSec.isFinite()) previousDegPerSec else 0f
        if (dtMs < MIN_VALID_DT_MS) return prev
        if (dtMs > MAX_VALID_DT_MS) return 0f
        if (!instantDegPerSec.isFinite()) return prev
        val clampedAlpha = if (alpha.isFinite()) alpha.coerceIn(0f, 1f) else DEFAULT_VELOCITY_SMOOTHING_ALPHA
        return prev + clampedAlpha * (instantDegPerSec - prev)
    }

    /**
     * Decays angular velocity smoothly via exponential turntable friction:
     * `velocity * exp(-damping * dtSeconds)`.
     */
    fun decayInertiaVelocity(
        velocityDegPerSec: Float,
        dtSeconds: Float,
        damping: Float = DEFAULT_INERTIA_DAMPING,
    ): Float {
        if (!velocityDegPerSec.isFinite()) return 0f
        if (!dtSeconds.isFinite() || dtSeconds <= 0f) return velocityDegPerSec
        val safeDamping = if (damping.isFinite()) damping.coerceAtLeast(0f).toDouble() else DEFAULT_INERTIA_DAMPING.toDouble()
        return (velocityDegPerSec.toDouble() * exp(-safeDamping * dtSeconds.toDouble())).toFloat()
    }

    /**
     * Returns `true` only for deliberate fast flicks (`|velocity| >= 120 deg/s`) so careful slow
     * precision scrubs commit immediately on finger release with zero coast lag.
     */
    fun shouldStartInertiaCoast(releaseVelocityDegPerSec: Float): Boolean =
        releaseVelocityDegPerSec.isFinite() &&
            abs(releaseVelocityDegPerSec) >= INERTIA_MIN_RELEASE_VELOCITY_DEG_PER_SEC

    /**
     * Clamps an accumulated floating-point scrub position cleanly within `[0.0, durationMs]`.
     */
    fun clampScrubPositionMs(positionMs: Double, durationMs: Long): Double {
        val maxMs = durationMs.coerceAtLeast(0L).toDouble()
        if (!positionMs.isFinite()) return 0.0
        return positionMs.coerceIn(0.0, maxMs)
    }

    /**
     * Formats a signed millisecond scrub delta concisely for the live turntable HUD badge
     * (e.g., `"+00:12"`, `"-00:07"`, `"00:00"`).
     */
    fun formatSignedDeltaMs(deltaMs: Long): String {
        val absMs = if (deltaMs == Long.MIN_VALUE) Long.MAX_VALUE else abs(deltaMs)
        val absSeconds = absMs / 1000L
        if (absSeconds == 0L) return "00:00"
        val sign = if (deltaMs > 0L) "+" else "-"
        return sign + AudioFormatFormatter.formatDurationMs(absSeconds * 1000L)
    }

    private fun smoothstep(t: Double): Double {
        val x = t.coerceIn(0.0, 1.0)
        return x * x * (3.0 - 2.0 * x)
    }
}
