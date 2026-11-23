package com.cangkir.bitperfectplayer.ui

import kotlin.math.abs

/**
 * Pure-Kotlin gesture math and decision policy for the right-to-left (`right -> left`, `dx < 0`)
 * swipe-to-dismiss gesture on `MiniPlayerBar` (stopping playback and clearing the active
 * Now Playing session).
 *
 * Kept free of `android.*` and `androidx.*` imports so directional locking, leftward translation
 * clamping, opacity decay, and release commit/cancel thresholds execute deterministically under
 * plain JUnit 4 JVM tests.
 */
object MiniPlayerSwipeMath {

    /** Commit threshold fraction of the mini-player container width (`35%`). */
    const val DISMISS_THRESHOLD_FRACTION: Float = 0.35f

    /** Lower bound on the commit distance in `dp`. */
    const val MIN_DISMISS_THRESHOLD_DP: Float = 96f

    /** Upper bound on the commit distance in `dp`. */
    const val MAX_DISMISS_THRESHOLD_DP: Float = 180f

    /** Fast leftward fling velocity threshold (`dp/s`) that commits dismissal with a shorter drag. */
    const val FLING_VELOCITY_THRESHOLD_DP_PER_SEC: Float = 520f

    /** Minimum leftward drag distance (`dp`) required for a fast leftward fling to dismiss. */
    const val MIN_FLING_DRAG_DP: Float = 28f

    /** Horizontal-to-vertical dominance ratio required before locking a horizontal drag. */
    const val HORIZONTAL_LOCK_RATIO: Float = 1.25f

    /** Opposing rightward flick fraction of [FLING_VELOCITY_THRESHOLD_DP_PER_SEC] that cancels a leftward drag. */
    const val OPPOSING_FLICK_CANCEL_FRACTION: Float = 0.6f

    /** Minimum visual alpha (`0.25f`) reached as the mini-player approaches its dismiss threshold. */
    const val MIN_ALPHA: Float = 0.25f

    /** Minimum upward swipe distance (`dp`) to open Now Playing directly from `MiniPlayerBar`. */
    const val UPWARD_OPEN_THRESHOLD_DP: Float = 28f

    /** Vertical-to-horizontal dominance ratio required for swipe-up-to-open. */
    const val VERTICAL_OPEN_RATIO: Float = 1.25f

    /**
     * Returns `true` when an upward vertical drag on `MiniPlayerBar` exceeds [openThresholdPx]
     * (`totalDyPx <= -openThresholdPx`) and clearly dominates horizontal movement.
     */
    fun shouldTriggerSwipeUpToOpen(totalDxPx: Float, totalDyPx: Float, openThresholdPx: Float): Boolean {
        if (!totalDxPx.isFinite() || !totalDyPx.isFinite() || !openThresholdPx.isFinite()) return false
        val safeThreshold = openThresholdPx.takeIf { it > 0f } ?: return false
        return totalDyPx <= -safeThreshold && abs(totalDyPx) > abs(totalDxPx) * VERTICAL_OPEN_RATIO
    }

    /**
     * Returns `true` when cumulative movement from `down` exceeds [touchSlopPx] and is
     * predominantly horizontal (`|dx| > |dy| * 1.25`).
     */
    fun shouldLockHorizontalDrag(totalDxPx: Float, totalDyPx: Float, touchSlopPx: Float): Boolean {
        if (!totalDxPx.isFinite() || !totalDyPx.isFinite() || !touchSlopPx.isFinite()) return false
        val absX = abs(totalDxPx)
        val absY = abs(totalDyPx)
        return absX > touchSlopPx.coerceAtLeast(0f) && absX > absY * HORIZONTAL_LOCK_RATIO
    }

    /**
     * Returns `true` when cumulative vertical movement exceeds [touchSlopPx] and dominates
     * horizontal movement, indicating the gesture should be abandoned before locking.
     */
    fun shouldAbortForVerticalDrag(totalDxPx: Float, totalDyPx: Float, touchSlopPx: Float): Boolean {
        if (!totalDxPx.isFinite() || !totalDyPx.isFinite() || !touchSlopPx.isFinite()) return true
        val absX = abs(totalDxPx)
        val absY = abs(totalDyPx)
        return absY > touchSlopPx.coerceAtLeast(0f) && absY >= absX
    }

    /**
     * Computes the pixel commit threshold (`35%` of [containerWidthPx], clamped to
     * `[minThresholdPx, maxThresholdPx]`).
     */
    fun computeDismissThresholdPx(containerWidthPx: Float, minThresholdPx: Float, maxThresholdPx: Float): Float {
        val safeMin = minThresholdPx.takeIf { it.isFinite() && it > 0f } ?: 1f
        val safeMax = maxThresholdPx.takeIf { it.isFinite() && it >= safeMin } ?: safeMin
        if (!containerWidthPx.isFinite() || containerWidthPx <= 0f) return safeMin
        return (containerWidthPx * DISMISS_THRESHOLD_FRACTION).coerceIn(safeMin, safeMax)
    }

    /**
     * Clamps raw horizontal drag `dx` so only right-to-left (`<= 0f`) translation is permitted.
     * Rightward drags (`rawDragX > 0f`) always clamp to `0f`.
     */
    fun clampLeftwardTranslationPx(rawDragX: Float, containerWidthPx: Float): Float {
        if (!rawDragX.isFinite() || rawDragX >= 0f) return 0f
        val minBound = if (containerWidthPx.isFinite() && containerWidthPx > 0f) {
            -containerWidthPx
        } else {
            rawDragX
        }
        return rawDragX.coerceIn(minBound, 0f)
    }

    /**
     * Maps leftward [translationX] (`<= 0f`) to a smooth visual opacity in `[MIN_ALPHA, 1.0f]`
     * as the bar slides toward [dismissThresholdPx].
     */
    fun computeAlpha(translationX: Float, dismissThresholdPx: Float): Float {
        if (!translationX.isFinite() || translationX >= 0f) return 1f
        if (!dismissThresholdPx.isFinite() || dismissThresholdPx <= 0f) return 1f
        val progress = (abs(translationX) / (dismissThresholdPx * 1.15f)).coerceIn(0f, 1f)
        return (1f - progress * (1f - MIN_ALPHA)).coerceIn(MIN_ALPHA, 1f)
    }

    /**
     * Evaluates whether releasing a horizontal drag on `MiniPlayerBar` should commit session
     * dismissal (`true`) or spring back to `0f` (`false`).
     *
     * Rules:
     * - Only right-to-left drags (`totalDragX < 0f`) can dismiss; `totalDragX >= 0f` never dismisses.
     * - If the user flicked back toward the right at `> 60%` of [flingVelocityThresholdPxPerSec],
     *   the dismissal is cancelled (`false`).
     * - Commits if `|totalDragX| >= dismissThresholdPx`, OR if `|totalDragX| >= minFlingDragPx`
     *   with leftward velocity `<= -flingVelocityThresholdPxPerSec`.
     */
    fun shouldDismissOnRelease(
        totalDragX: Float,
        velocityXPxPerSec: Float,
        dismissThresholdPx: Float,
        flingVelocityThresholdPxPerSec: Float,
        minFlingDragPx: Float,
    ): Boolean {
        if (!totalDragX.isFinite() || totalDragX >= 0f) return false
        val safeThreshold = dismissThresholdPx.takeIf { it.isFinite() && it > 0f } ?: return false
        val safeVelocityThreshold =
            flingVelocityThresholdPxPerSec.takeIf { it.isFinite() && it > 0f } ?: Float.MAX_VALUE
        val safeMinFlingDrag = minFlingDragPx.takeIf { it.isFinite() && it >= 0f } ?: 0f
        val safeVelocity = if (velocityXPxPerSec.isFinite()) velocityXPxPerSec else 0f

        // Opposing rightward flick cancels dismissal.
        if (safeVelocity > safeVelocityThreshold * OPPOSING_FLICK_CANCEL_FRACTION) {
            return false
        }

        val leftwardDistance = -totalDragX
        return leftwardDistance >= safeThreshold ||
            (leftwardDistance >= safeMinFlingDrag && safeVelocity <= -safeVelocityThreshold)
    }
}
