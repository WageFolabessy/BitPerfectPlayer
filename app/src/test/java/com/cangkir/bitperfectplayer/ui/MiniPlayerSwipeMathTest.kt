package com.cangkir.bitperfectplayer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniPlayerSwipeMathTest {

    @Test
    fun shouldLockHorizontalDrag_requiresExceedingTouchSlopAndHorizontalDominance() {
        assertFalse(MiniPlayerSwipeMath.shouldLockHorizontalDrag(totalDxPx = -10f, totalDyPx = 2f, touchSlopPx = 16f))
        assertFalse(MiniPlayerSwipeMath.shouldLockHorizontalDrag(totalDxPx = -24f, totalDyPx = 22f, touchSlopPx = 16f))
        assertTrue(MiniPlayerSwipeMath.shouldLockHorizontalDrag(totalDxPx = -28f, totalDyPx = 8f, touchSlopPx = 16f))
    }

    @Test
    fun shouldAbortForVerticalDrag_detectsVerticalDominancePastTouchSlop() {
        assertFalse(MiniPlayerSwipeMath.shouldAbortForVerticalDrag(totalDxPx = -8f, totalDyPx = 12f, touchSlopPx = 16f))
        assertTrue(MiniPlayerSwipeMath.shouldAbortForVerticalDrag(totalDxPx = -10f, totalDyPx = 24f, touchSlopPx = 16f))
    }

    @Test
    fun clampLeftwardTranslationPx_allowsOnlyLeftwardTranslationAndClampsRightwardToZero() {
        assertEquals(0f, MiniPlayerSwipeMath.clampLeftwardTranslationPx(rawDragX = 0f, containerWidthPx = 400f), 0.001f)
        assertEquals(0f, MiniPlayerSwipeMath.clampLeftwardTranslationPx(rawDragX = 120f, containerWidthPx = 400f), 0.001f)
        assertEquals(-140f, MiniPlayerSwipeMath.clampLeftwardTranslationPx(rawDragX = -140f, containerWidthPx = 400f), 0.001f)
        assertEquals(-400f, MiniPlayerSwipeMath.clampLeftwardTranslationPx(rawDragX = -520f, containerWidthPx = 400f), 0.001f)
    }

    @Test
    fun computeAlpha_decaysSmoothlyToMinAlphaOnLeftwardDrag() {
        assertEquals(1f, MiniPlayerSwipeMath.computeAlpha(translationX = 0f, dismissThresholdPx = 140f), 0.001f)
        assertEquals(1f, MiniPlayerSwipeMath.computeAlpha(translationX = 40f, dismissThresholdPx = 140f), 0.001f)
        val midAlpha = MiniPlayerSwipeMath.computeAlpha(translationX = -80f, dismissThresholdPx = 140f)
        assertTrue(midAlpha in MiniPlayerSwipeMath.MIN_ALPHA..1f)
        assertEquals(
            MiniPlayerSwipeMath.MIN_ALPHA,
            MiniPlayerSwipeMath.computeAlpha(translationX = -300f, dismissThresholdPx = 140f),
            0.001f,
        )
    }

    @Test
    fun shouldDismissOnRelease_commitsOnLeftwardDistanceOrFastLeftwardFling_andRejectsRightwardOrOpposingFlick() {
        val threshold = MiniPlayerSwipeMath.computeDismissThresholdPx(
            containerWidthPx = 400f,
            minThresholdPx = 96f,
            maxThresholdPx = 180f,
        )
        assertEquals(140f, threshold, 0.001f)

        // Short leftward drag without fling -> spring back (false)
        assertFalse(
            MiniPlayerSwipeMath.shouldDismissOnRelease(
                totalDragX = -60f,
                velocityXPxPerSec = -150f,
                dismissThresholdPx = threshold,
                flingVelocityThresholdPxPerSec = 520f,
                minFlingDragPx = 28f,
            ),
        )

        // Leftward drag >= threshold -> dismiss (true)
        assertTrue(
            MiniPlayerSwipeMath.shouldDismissOnRelease(
                totalDragX = -145f,
                velocityXPxPerSec = -80f,
                dismissThresholdPx = threshold,
                flingVelocityThresholdPxPerSec = 520f,
                minFlingDragPx = 28f,
            ),
        )

        // Fast leftward fling past minFlingDragPx -> dismiss (true)
        assertTrue(
            MiniPlayerSwipeMath.shouldDismissOnRelease(
                totalDragX = -45f,
                velocityXPxPerSec = -680f,
                dismissThresholdPx = threshold,
                flingVelocityThresholdPxPerSec = 520f,
                minFlingDragPx = 28f,
            ),
        )

        // Opposing rightward flick at release cancels dismissal even if dragged past threshold
        assertFalse(
            MiniPlayerSwipeMath.shouldDismissOnRelease(
                totalDragX = -160f,
                velocityXPxPerSec = 400f,
                dismissThresholdPx = threshold,
                flingVelocityThresholdPxPerSec = 520f,
                minFlingDragPx = 28f,
            ),
        )

        // Left-to-right drag (totalDragX > 0) NEVER dismisses
        assertFalse(
            MiniPlayerSwipeMath.shouldDismissOnRelease(
                totalDragX = 220f,
                velocityXPxPerSec = 900f,
                dismissThresholdPx = threshold,
                flingVelocityThresholdPxPerSec = 520f,
                minFlingDragPx = 28f,
            ),
        )
    }

    @Test
    fun shouldTriggerSwipeUpToOpen_requiresUpwardDistanceAndVerticalDominance() {
        assertTrue(MiniPlayerSwipeMath.shouldTriggerSwipeUpToOpen(totalDxPx = 6f, totalDyPx = -60f, openThresholdPx = 56f))
        assertFalse(MiniPlayerSwipeMath.shouldTriggerSwipeUpToOpen(totalDxPx = 6f, totalDyPx = -40f, openThresholdPx = 56f))
        assertFalse(MiniPlayerSwipeMath.shouldTriggerSwipeUpToOpen(totalDxPx = 0f, totalDyPx = 90f, openThresholdPx = 56f))
        assertFalse(MiniPlayerSwipeMath.shouldTriggerSwipeUpToOpen(totalDxPx = -70f, totalDyPx = -65f, openThresholdPx = 56f))
    }
}
