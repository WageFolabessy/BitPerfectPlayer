package com.cangkir.bitperfectplayer.audio

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VinylScrubMathTest {

    @Test
    fun computeShortestAngleDeltaDegrees_wrapsCleanlyAcrossPlusMinus180Seam() {
        // Crossing +180 -> -180 clockwise (+2 deg)
        assertEquals(2f, VinylScrubMath.computeShortestAngleDeltaDegrees(179f, -179f), 1e-4f)
        // Crossing -180 -> +180 counter-clockwise (-2 deg)
        assertEquals(-2f, VinylScrubMath.computeShortestAngleDeltaDegrees(-179f, 179f), 1e-4f)
        // Exact boundary normalized into (-180f, 180f]
        assertEquals(180f, VinylScrubMath.computeShortestAngleDeltaDegrees(0f, 180f), 1e-4f)
        assertEquals(180f, VinylScrubMath.computeShortestAngleDeltaDegrees(0f, -180f), 1e-4f)
        // Large wrap (> 360)
        assertEquals(20f, VinylScrubMath.computeShortestAngleDeltaDegrees(350f, 10f), 1e-4f)
        // Non-finite inputs safely return 0
        assertEquals(0f, VinylScrubMath.computeShortestAngleDeltaDegrees(Float.NaN, 45f), 1e-4f)
        assertEquals(0f, VinylScrubMath.computeShortestAngleDeltaDegrees(10f, Float.POSITIVE_INFINITY), 1e-4f)
    }

    @Test
    fun isWithinScrubRing_rejectsSpindleDeadZoneAndOutsideDisc() {
        val outer = 200f
        val inner = 36f

        // Center spindle and inside dead zone -> rejected
        assertFalse(VinylScrubMath.isWithinScrubRing(0f, 0f, outer, inner))
        assertFalse(VinylScrubMath.isWithinScrubRing(20f, 20f, outer, inner)) // r = ~28.3 < 36

        // On inner edge, mid-ring, and outer edge -> accepted
        assertTrue(VinylScrubMath.isWithinScrubRing(36f, 0f, outer, inner))
        assertTrue(VinylScrubMath.isWithinScrubRing(100f, -80f, outer, inner)) // r = ~128.1
        assertTrue(VinylScrubMath.isWithinScrubRing(0f, 200f, outer, inner))

        // Outside outer radius -> rejected
        assertFalse(VinylScrubMath.isWithinScrubRing(150f, 150f, outer, inner)) // r = ~212.1 > 200
        assertFalse(VinylScrubMath.isWithinScrubRing(201f, 0f, outer, inner))

        // Degenerate geometry -> rejected
        assertFalse(VinylScrubMath.isWithinScrubRing(50f, 0f, 0f, 10f))
        assertFalse(VinylScrubMath.isWithinScrubRing(50f, 0f, 30f, 30f))
    }

    @Test
    fun computeMsPerFullRotation_hitsExactControlPointsAndIsMonotonicContinuous() {
        // Endpoint & anchor verification (including symmetric negative velocities)
        assertEquals(8_000.0, VinylScrubMath.computeMsPerFullRotation(0f), 1e-6)
        assertEquals(8_000.0, VinylScrubMath.computeMsPerFullRotation(45f), 1e-6)
        assertEquals(8_000.0, VinylScrubMath.computeMsPerFullRotation(-45f), 1e-6)
        assertEquals(30_000.0, VinylScrubMath.computeMsPerFullRotation(180f), 1e-6)
        assertEquals(30_000.0, VinylScrubMath.computeMsPerFullRotation(-180f), 1e-6)
        assertEquals(120_000.0, VinylScrubMath.computeMsPerFullRotation(540f), 1e-6)
        assertEquals(120_000.0, VinylScrubMath.computeMsPerFullRotation(900f), 1e-6)

        // Smoothstep midpoint check: smoothstep(0.5) = 0.5 exactly
        val midSlowNormal = VinylScrubMath.computeMsPerFullRotation((45f + 180f) / 2f)
        assertEquals(19_000.0, midSlowNormal, 1e-6)
        val midNormalFast = VinylScrubMath.computeMsPerFullRotation((180f + 540f) / 2f)
        assertEquals(75_000.0, midNormalFast, 1e-6)

        // Strictly monotonic non-decreasing across entire speed spectrum without step jumps
        var previous = VinylScrubMath.computeMsPerFullRotation(0f)
        for (speed in 1..700) {
            val current = VinylScrubMath.computeMsPerFullRotation(speed.toFloat())
            assertTrue("Expected monotonic curve at $speed deg/s", current >= previous)
            // Continuity check: 1 deg/s increment never jumps more than 500 ms/rev
            assertTrue("Unexpected discontinuity at $speed deg/s", current - previous < 500.0)
            previous = current
        }
    }

    @Test
    fun computeIncrementalScrubDeltaMs_preservesSubMillisecondDoubleAccumulationAt120Hz() {
        // Simulate 120 Hz ultra-slow precision drag: 0.02 deg per frame @ 2.4 deg/s (8,000 ms / 360 deg)
        // Each frame is 0.4444... ms (< 1 ms): Long rounding per frame would lose everything to 0!
        var accumulated = 0.0
        repeat(180) {
            // 3.6 degrees total -> exact 80.0 ms
            accumulated += VinylScrubMath.computeIncrementalScrubDeltaMs(
                deltaDegrees = 0.02f,
                angularVelocityDegPerSec = 2.4f,
            )
        }
        assertEquals(80.0, accumulated, 1e-4)

        // Counter-clockwise full turn at 180 deg/s -> -30,000 ms
        assertEquals(
            -30_000.0,
            VinylScrubMath.computeIncrementalScrubDeltaMs(-360f, -180f),
            1e-6,
        )

        // Fast clockwise half turn at 600 deg/s -> +60,000 ms
        assertEquals(
            60_000.0,
            VinylScrubMath.computeIncrementalScrubDeltaMs(180f, 600f),
            1e-6,
        )
    }

    @Test
    fun smoothAngularVelocity_appliesEmaAndRejectsDegenerateOrPausedIntervals() {
        // Standard EMA: 100 + 0.30 * (200 - 100) = 130
        assertEquals(130f, VinylScrubMath.smoothAngularVelocity(100f, 200f, alpha = 0.30f, dtMs = 8L), 1e-4f)

        // Degenerate dt < 1 ms ignored -> retains previous velocity without spike
        assertEquals(130f, VinylScrubMath.smoothAngularVelocity(130f, 99_999f, alpha = 0.30f, dtMs = 0L), 1e-4f)

        // Finger pause dt > 200 ms -> resets stale velocity to 0
        assertEquals(0f, VinylScrubMath.smoothAngularVelocity(400f, 10f, alpha = 0.30f, dtMs = 250L), 1e-4f)
    }

    @Test
    fun inertiaDecayAndThreshold_coastOnlyOnDeliberateFlicksAndDecayExponentially() {
        assertFalse(VinylScrubMath.shouldStartInertiaCoast(0f))
        assertFalse(VinylScrubMath.shouldStartInertiaCoast(119.9f))
        assertFalse(VinylScrubMath.shouldStartInertiaCoast(-119.9f))
        assertTrue(VinylScrubMath.shouldStartInertiaCoast(120f))
        assertTrue(VinylScrubMath.shouldStartInertiaCoast(-250f))

        // Exponential decay with damping = 7.5 over 0.1s: 300 * exp(-0.75) ~= 141.71
        val after100ms = VinylScrubMath.decayInertiaVelocity(300f, 0.1f, damping = 7.5f)
        assertTrue(after100ms in 140f..143f)

        // Negative flick decays toward zero while preserving sign
        val negAfter = VinylScrubMath.decayInertiaVelocity(-300f, 0.2f, damping = 7.5f)
        assertTrue(negAfter < 0f && abs(negAfter) < 70f)

        // Zero or negative dt leaves velocity unchanged
        assertEquals(200f, VinylScrubMath.decayInertiaVelocity(200f, 0f), 1e-4f)
    }

    @Test
    fun clampScrubPositionMs_andFormatSignedDeltaMs_behaveDeterministically() {
        assertEquals(0.0, VinylScrubMath.clampScrubPositionMs(-150.5, 200_000L), 1e-6)
        assertEquals(200_000.0, VinylScrubMath.clampScrubPositionMs(250_000.0, 200_000L), 1e-6)
        assertEquals(42_500.25, VinylScrubMath.clampScrubPositionMs(42_500.25, 200_000L), 1e-6)
        assertEquals(0.0, VinylScrubMath.clampScrubPositionMs(Double.NaN, 200_000L), 1e-6)

        assertEquals("00:00", VinylScrubMath.formatSignedDeltaMs(0L))
        assertEquals("00:00", VinylScrubMath.formatSignedDeltaMs(999L))
        assertEquals("00:00", VinylScrubMath.formatSignedDeltaMs(-999L))
        assertEquals("+00:12", VinylScrubMath.formatSignedDeltaMs(12_400L))
        assertEquals("-00:07", VinylScrubMath.formatSignedDeltaMs(-7_900L))
        assertEquals("+01:05", VinylScrubMath.formatSignedDeltaMs(65_000L))
        assertEquals("-1:01:01", VinylScrubMath.formatSignedDeltaMs(-3_661_000L))
    }

    @Test
    fun angularVelocityToPlaybackRate_mapsDeadbandForwardReverseAndCapCleanly() {
        assertEquals(0.0f, VinylScrubMath.angularVelocityToPlaybackRate(0f), 1e-6f)
        assertEquals(0.0f, VinylScrubMath.angularVelocityToPlaybackRate(5.5f), 1e-6f)
        assertEquals(0.0f, VinylScrubMath.angularVelocityToPlaybackRate(-5.5f), 1e-6f)

        assertTrue(VinylScrubMath.angularVelocityToPlaybackRate(20f) in 0.6f..1.0f)
        assertTrue(VinylScrubMath.angularVelocityToPlaybackRate(90f) in 1.6f..2.0f)
        assertEquals(4.0f, VinylScrubMath.angularVelocityToPlaybackRate(360f), 1e-6f)
        assertEquals(-4.0f, VinylScrubMath.angularVelocityToPlaybackRate(-400f), 1e-6f)

        assertEquals("HOLD", VinylScrubMath.formatPlaybackRateBadge(0f))
        assertEquals("2.1×", VinylScrubMath.formatPlaybackRateBadge(2.1f))
        assertEquals("-1.4×", VinylScrubMath.formatPlaybackRateBadge(-1.4f))
    }
}
