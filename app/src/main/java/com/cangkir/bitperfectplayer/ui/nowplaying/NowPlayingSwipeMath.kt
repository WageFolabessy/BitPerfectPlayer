package com.cangkir.bitperfectplayer.ui.nowplaying

import kotlin.math.abs

enum class NowPlayingSwipeDirection {
    NONE,
    SWIPE_RIGHT_BACK,
    SWIPE_LEFT_SETTINGS,
}

/**
 * Pure-Kotlin geometry, gesture arbitration, resistance, and threshold rules for `NowPlayingScreen`
 * horizontal navigation swipes (zero `android.*` / `androidx.*` imports).
 */
object NowPlayingSwipeMath {
    const val HORIZONTAL_LOCK_RATIO = 1.25f
    const val COMMIT_THRESHOLD_FRACTION = 0.22f
    const val MIN_COMMIT_THRESHOLD_DP = 72f
    const val MAX_COMMIT_THRESHOLD_DP = 112f
    const val FLING_VELOCITY_THRESHOLD_DP_PER_SEC = 520f
    const val MIN_FLING_DRAG_DP = 28f
    const val MAX_VISUAL_OFFSET_DP = 44f
    const val OPPOSING_FLICK_CANCEL_FRACTION = 0.6f

    fun isInsideCircle(x: Float, y: Float, centerX: Float, centerY: Float, radius: Float): Boolean {
        if (radius <= 0f) return false
        val dx = x - centerX
        val dy = y - centerY
        return dx * dx + dy * dy <= radius * radius
    }

    fun isInsideRect(
        x: Float,
        y: Float,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        horizontalPadding: Float = 0f,
        verticalPadding: Float = 0f,
    ): Boolean {
        if (right <= left || bottom <= top) return false
        val hPad = horizontalPadding.coerceAtLeast(0f)
        val vPad = verticalPadding.coerceAtLeast(0f)
        return x in (left - hPad)..(right + hPad) && y in (top - vPad)..(bottom + vPad)
    }

    fun shouldLockHorizontalSwipe(totalDx: Float, totalDy: Float, touchSlopPx: Float): Boolean {
        val absDx = abs(totalDx)
        val absDy = abs(totalDy)
        return absDx > touchSlopPx && absDx > absDy * HORIZONTAL_LOCK_RATIO
    }

    fun shouldAbortForVerticalScroll(totalDx: Float, totalDy: Float, touchSlopPx: Float): Boolean {
        val absDx = abs(totalDx)
        val absDy = abs(totalDy)
        return absDy > touchSlopPx && absDy > absDx
    }

    fun computeCommitThresholdPx(containerWidthPx: Float, minThresholdPx: Float, maxThresholdPx: Float): Float {
        val safeMin = minThresholdPx.coerceAtLeast(1f)
        val safeMax = maxThresholdPx.coerceAtLeast(safeMin)
        if (containerWidthPx <= 0f) return safeMin
        return (containerWidthPx * COMMIT_THRESHOLD_FRACTION).coerceIn(safeMin, safeMax)
    }

    fun computeVisualTranslationPx(rawDragPx: Float, maxVisualOffsetPx: Float): Float {
        if (rawDragPx == 0f || maxVisualOffsetPx <= 0f) return 0f
        val absDrag = abs(rawDragPx)
        val damped = maxVisualOffsetPx * (absDrag / (absDrag + maxVisualOffsetPx * 1.2f))
        return if (rawDragPx > 0f) damped else -damped
    }

    fun computeProgress(rawDragPx: Float, commitThresholdPx: Float): Float {
        if (commitThresholdPx <= 0f) return 0f
        return (abs(rawDragPx) / commitThresholdPx).coerceIn(0f, 1f)
    }

    fun decideSwipeOutcome(
        totalDragPx: Float,
        velocityPxPerSec: Float,
        commitThresholdPx: Float,
        flingVelocityThresholdPxPerSec: Float,
        minFlingDragPx: Float,
    ): NowPlayingSwipeDirection {
        val opposingCancelThreshold = flingVelocityThresholdPxPerSec * OPPOSING_FLICK_CANCEL_FRACTION
        return when {
            totalDragPx >= commitThresholdPx && velocityPxPerSec > -opposingCancelThreshold ->
                NowPlayingSwipeDirection.SWIPE_RIGHT_BACK
            totalDragPx >= minFlingDragPx && velocityPxPerSec >= flingVelocityThresholdPxPerSec ->
                NowPlayingSwipeDirection.SWIPE_RIGHT_BACK
            totalDragPx <= -commitThresholdPx && velocityPxPerSec < opposingCancelThreshold ->
                NowPlayingSwipeDirection.SWIPE_LEFT_SETTINGS
            totalDragPx <= -minFlingDragPx && velocityPxPerSec <= -flingVelocityThresholdPxPerSec ->
                NowPlayingSwipeDirection.SWIPE_LEFT_SETTINGS
            else -> NowPlayingSwipeDirection.NONE
        }
    }
}
