package com.cangkir.bitperfectplayer.ui.nowplaying

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

internal const val ROOT_HORIZONTAL_PAGER_TEST_TAG = "root_horizontal_pager"
internal const val TOP_LEVEL_HORIZONTAL_PAGER_TEST_TAG = "top_level_horizontal_pager"
internal const val NOW_PLAYING_SWIPE_CONTAINER_TEST_TAG = "now_playing_swipe_container"
internal const val SETTINGS_SWIPE_CONTAINER_TEST_TAG = "settings_swipe_container"
internal const val SETTINGS_TAB_SWIPE_CONTAINER_TEST_TAG = "settings_tab_swipe_container"

internal val LocalSwipeExclusionBounds = staticCompositionLocalOf<NowPlayingSwipeExclusionBounds?> { null }

/**
 * Mutable root-coordinate exclusion bounds updated on layout without causing recompositions.
 * Prevents the root 3-page `HorizontalPager` from competing with circular
 * [com.cangkir.bitperfectplayer.ui.InteractiveVinylDisc] scrubbing (including the center spindle
 * dead-zone), [SwipeableSquareArtwork], or seek / ReplayGain preamp `Slider` drags.
 */
internal class NowPlayingSwipeExclusionBounds {
    var containerRootOffset: Offset = Offset.Zero
    var vinylDiscCenterInRoot: Offset = Offset.Zero
    var vinylDiscRadiusPx: Float = 0f
    var squareArtworkRectInRoot: Rect = Rect.Zero
    var seekSliderRectInRoot: Rect = Rect.Zero
    var sliderHorizontalPaddingPx: Float = 0f
    var sliderVerticalPaddingPx: Float = 0f

    fun containsLocalPoint(localPoint: Offset): Boolean {
        val rootPoint = containerRootOffset + localPoint
        val inVinylCircle = NowPlayingSwipeMath.isInsideCircle(
            x = rootPoint.x,
            y = rootPoint.y,
            centerX = vinylDiscCenterInRoot.x,
            centerY = vinylDiscCenterInRoot.y,
            radius = vinylDiscRadiusPx,
        )
        val inSquareArtwork = NowPlayingSwipeMath.isInsideRect(
            x = rootPoint.x,
            y = rootPoint.y,
            left = squareArtworkRectInRoot.left,
            top = squareArtworkRectInRoot.top,
            right = squareArtworkRectInRoot.right,
            bottom = squareArtworkRectInRoot.bottom,
        )
        val inSeekSlider = NowPlayingSwipeMath.isInsideRect(
            x = rootPoint.x,
            y = rootPoint.y,
            left = seekSliderRectInRoot.left,
            top = seekSliderRectInRoot.top,
            right = seekSliderRectInRoot.right,
            bottom = seekSliderRectInRoot.bottom,
            horizontalPadding = sliderHorizontalPaddingPx,
            verticalPadding = sliderVerticalPaddingPx,
        )
        return inVinylCircle || inSquareArtwork || inSeekSlider
    }
}

/**
 * Intercepts pointer gestures that start inside [exclusionBounds] (vinyl circle including center
 * spindle, square cover art, seek slider, or ReplayGain preamp slider):
 * - Observes `down` on [PointerEventPass.Initial] without consuming it so child controls receive
 *   normal touch-down events.
 * - Consumes movement deltas on [PointerEventPass.Main] **after** child controls have processed
 *   them and **before** the root `HorizontalPager` sees them, preventing pager scroll conflicts.
 */
@Composable
internal fun PageExclusionGuardBox(
    exclusionBounds: NowPlayingSwipeExclusionBounds,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    exclusionBounds.sliderHorizontalPaddingPx = with(density) { 24.dp.toPx() }
    exclusionBounds.sliderVerticalPaddingPx = with(density) { 8.dp.toPx() }

    Box(
        modifier = modifier
            .onGloballyPositioned { coords ->
                exclusionBounds.containerRootOffset = coords.positionInRoot()
            }
            .pointerInput(exclusionBounds) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    if (!exclusionBounds.containsLocalPoint(down.position)) {
                        return@awaitEachGesture
                    }
                    val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ ->
                        change.consume()
                    }
                    if (drag != null) {
                        horizontalDrag(drag.id) { change ->
                            if (change.positionChanged()) {
                                change.consume()
                            }
                        }
                    }
                }
            },
    ) {
        content()
    }
}
