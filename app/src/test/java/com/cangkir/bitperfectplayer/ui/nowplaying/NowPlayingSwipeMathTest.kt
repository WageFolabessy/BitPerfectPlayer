package com.cangkir.bitperfectplayer.ui.nowplaying

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingSwipeMathTest {

    @Test
    fun exclusionHitTesting_checksCircleIncludingCenterAndRectBounds() {
        assertTrue(NowPlayingSwipeMath.isInsideCircle(500f, 500f, centerX = 500f, centerY = 500f, radius = 200f))
        assertTrue(NowPlayingSwipeMath.isInsideCircle(300f, 500f, centerX = 500f, centerY = 500f, radius = 200f))
        assertFalse(NowPlayingSwipeMath.isInsideCircle(299f, 500f, centerX = 500f, centerY = 500f, radius = 200f))
        assertFalse(NowPlayingSwipeMath.isInsideCircle(500f, 500f, centerX = 500f, centerY = 500f, radius = 0f))

        assertTrue(NowPlayingSwipeMath.isInsideRect(100f, 700f, left = 40f, top = 680f, right = 900f, bottom = 740f))
        assertFalse(NowPlayingSwipeMath.isInsideRect(100f, 679f, left = 40f, top = 680f, right = 900f, bottom = 740f))
        assertTrue(
            NowPlayingSwipeMath.isInsideRect(
                x = 20f,
                y = 674f,
                left = 40f,
                top = 680f,
                right = 900f,
                bottom = 740f,
                horizontalPadding = 24f,
                verticalPadding = 8f,
            ),
        )
        assertFalse(
            NowPlayingSwipeMath.isInsideRect(
                x = 100f,
                y = 700f,
                left = 0f,
                top = 0f,
                right = 0f,
                bottom = 0f,
                horizontalPadding = 24f,
                verticalPadding = 8f,
            ),
        )
    }

    @Test
    fun axisLocking_distinguishesHorizontalSwipeFromVerticalScroll() {
        val touchSlop = 16f
        assertTrue(NowPlayingSwipeMath.shouldLockHorizontalSwipe(totalDx = 48f, totalDy = 10f, touchSlopPx = touchSlop))
        assertTrue(NowPlayingSwipeMath.shouldLockHorizontalSwipe(totalDx = -48f, totalDy = -12f, touchSlopPx = touchSlop))
        assertFalse(NowPlayingSwipeMath.shouldLockHorizontalSwipe(totalDx = 12f, totalDy = 0f, touchSlopPx = touchSlop))
        assertFalse(NowPlayingSwipeMath.shouldLockHorizontalSwipe(totalDx = 30f, totalDy = 26f, touchSlopPx = touchSlop))

        assertTrue(NowPlayingSwipeMath.shouldAbortForVerticalScroll(totalDx = 10f, totalDy = 40f, touchSlopPx = touchSlop))
        assertFalse(NowPlayingSwipeMath.shouldAbortForVerticalScroll(totalDx = 40f, totalDy = 20f, touchSlopPx = touchSlop))
    }

    @Test
    fun thresholdResistanceAndProgress_stayBoundedAndSymmetric() {
        val threshold = NowPlayingSwipeMath.computeCommitThresholdPx(
            containerWidthPx = 1080f,
            minThresholdPx = 180f,
            maxThresholdPx = 300f,
        )
        assertEquals(237.6f, threshold, 0.01f)
        assertEquals(180f, NowPlayingSwipeMath.computeCommitThresholdPx(500f, 180f, 300f), 0.01f)
        assertEquals(300f, NowPlayingSwipeMath.computeCommitThresholdPx(2000f, 180f, 300f), 0.01f)

        val rightVis = NowPlayingSwipeMath.computeVisualTranslationPx(rawDragPx = 220f, maxVisualOffsetPx = 100f)
        val leftVis = NowPlayingSwipeMath.computeVisualTranslationPx(rawDragPx = -220f, maxVisualOffsetPx = 100f)
        assertEquals(0f, NowPlayingSwipeMath.computeVisualTranslationPx(0f, 100f), 0.001f)
        assertEquals(-leftVis, rightVis, 0.001f)
        assertTrue(rightVis in 0f..100f)

        assertEquals(0f, NowPlayingSwipeMath.computeProgress(0f, threshold), 0.001f)
        assertEquals(0.5f, NowPlayingSwipeMath.computeProgress(-threshold * 0.5f, threshold), 0.01f)
        assertEquals(1f, NowPlayingSwipeMath.computeProgress(threshold * 2f, threshold), 0.001f)
    }

    @Test
    fun decideSwipeOutcome_handlesDistanceFlingShortDragAndOpposingFlickCancel() {
        val threshold = 220f
        val flingVel = 1200f
        val minFlingDrag = 70f

        assertEquals(
            NowPlayingSwipeDirection.SWIPE_RIGHT_BACK,
            NowPlayingSwipeMath.decideSwipeOutcome(230f, 100f, threshold, flingVel, minFlingDrag),
        )
        assertEquals(
            NowPlayingSwipeDirection.SWIPE_LEFT_SETTINGS,
            NowPlayingSwipeMath.decideSwipeOutcome(-230f, -100f, threshold, flingVel, minFlingDrag),
        )

        // Fast fling past minimum drag commits even below full distance threshold
        assertEquals(
            NowPlayingSwipeDirection.SWIPE_RIGHT_BACK,
            NowPlayingSwipeMath.decideSwipeOutcome(95f, 1500f, threshold, flingVel, minFlingDrag),
        )
        assertEquals(
            NowPlayingSwipeDirection.SWIPE_LEFT_SETTINGS,
            NowPlayingSwipeMath.decideSwipeOutcome(-95f, -1500f, threshold, flingVel, minFlingDrag),
        )

        // Short tap/jitter below minimum drag never commits even with high velocity
        assertEquals(
            NowPlayingSwipeDirection.NONE,
            NowPlayingSwipeMath.decideSwipeOutcome(30f, 1800f, threshold, flingVel, minFlingDrag),
        )
        // Strongly opposing flick back toward origin cancels navigation
        assertEquals(
            NowPlayingSwipeDirection.NONE,
            NowPlayingSwipeMath.decideSwipeOutcome(240f, -900f, threshold, flingVel, minFlingDrag),
        )
        assertEquals(
            NowPlayingSwipeDirection.NONE,
            NowPlayingSwipeMath.decideSwipeOutcome(-240f, 900f, threshold, flingVel, minFlingDrag),
        )
    }
}
