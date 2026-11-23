package com.cangkir.bitperfectplayer.ui.nowplaying

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

internal const val NOW_PLAYING_SWIPE_CONTAINER_TEST_TAG = "now_playing_swipe_container"
internal const val SETTINGS_SWIPE_CONTAINER_TEST_TAG = "settings_swipe_container"

/**
 * Mutable root-coordinate exclusion bounds updated on layout without causing recompositions.
 * Prevents `NowPlayingScreen` and `SettingsScreen` horizontal pager swipes from competing with
 * circular [com.cangkir.bitperfectplayer.ui.InteractiveVinylDisc] scrubbing (including the center
 * spindle dead-zone), [SwipeableSquareArtwork], or seek / ReplayGain preamp `Slider` drags.
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
 * Lightweight 1:1 [HorizontalPager] container for `NowPlayingScreen` with zero floating pill
 * badges and zero 3D/page-flip effects:
 * - Page `0`: triggers [onSwipeBack] when settled (left -> right swipe returns to previous screen).
 * - Page `1`: active `NowPlayingScreen` wrapped in [PageExclusionGuardBox] so vinyl, square cover,
 *   and seek slider drags never scroll the pager.
 * - Page `2`: triggers [onSwipeSettings] when settled (right -> left swipe opens `Settings`).
 */
@Composable
internal fun NowPlayingSwipeContainer(
    exclusionBounds: NowPlayingSwipeExclusionBounds,
    onSwipeBack: () -> Unit,
    onSwipeSettings: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    exclusionBounds.sliderHorizontalPaddingPx = with(density) { 24.dp.toPx() }
    exclusionBounds.sliderVerticalPaddingPx = with(density) { 8.dp.toPx() }

    val latestOnSwipeBack by rememberUpdatedState(onSwipeBack)
    val latestOnSwipeSettings by rememberUpdatedState(onSwipeSettings)

    val pagerState = rememberPagerState(
        initialPage = 1,
        pageCount = { 3 },
    )
    val flingBehavior = PagerDefaults.flingBehavior(
        state = pagerState,
        decayAnimationSpec = exponentialDecay(frictionMultiplier = 100f),
        snapPositionalThreshold = NowPlayingSwipeMath.COMMIT_THRESHOLD_FRACTION,
        snapAnimationSpec = tween(durationMillis = 16, easing = FastOutSlowInEasing),
    )

    LaunchedEffect(pagerState.settledPage, pagerState.isScrollInProgress) {
        if (!pagerState.isScrollInProgress) {
            when (pagerState.settledPage) {
                0 -> {
                    latestOnSwipeBack()
                    pagerState.scrollToPage(1)
                }
                2 -> {
                    latestOnSwipeSettings()
                    pagerState.scrollToPage(1)
                }
            }
        }
    }

    HorizontalPager(
        state = pagerState,
        beyondViewportPageCount = 0,
        flingBehavior = flingBehavior,
        modifier = modifier
            .fillMaxSize()
            .testTag(NOW_PLAYING_SWIPE_CONTAINER_TEST_TAG),
    ) { page ->
        when (page) {
            1 -> {
                PageExclusionGuardBox(
                    exclusionBounds = exclusionBounds,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    content()
                }
            }
            else -> {
                Box(modifier = Modifier.fillMaxSize())
            }
        }
    }
}

/**
 * Lightweight 1:1 [HorizontalPager] container for `SettingsScreen` (zero floating pills):
 * - When [onSwipeLeftToRight] is non-null (either returning to `Now Playing` when opened from
 *   `Now Playing`, or opening `Now Playing` from the bottom-nav `Settings` tab when an active
 *   track exists), renders a 2-page [HorizontalPager] starting on Page `1`:
 *   - Swiping left -> right (`Page 1 -> Page 0`) slides 1:1 and invokes [onSwipeLeftToRight].
 *   - Swiping right -> left is at the rightmost page boundary (`Page 1`) and is a strict no-op.
 * - When [onSwipeLeftToRight] is `null` (bottom-nav `Settings` tab with `activeTrack == null`),
 *   horizontal paging is disabled and any horizontal swipe is a strict no-op.
 */
@Composable
internal fun SettingsTabSwipeContainer(
    exclusionBounds: NowPlayingSwipeExclusionBounds,
    onSwipeLeftToRight: (() -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    exclusionBounds.sliderHorizontalPaddingPx = with(density) { 24.dp.toPx() }
    exclusionBounds.sliderVerticalPaddingPx = with(density) { 8.dp.toPx() }

    if (onSwipeLeftToRight == null) {
        PageExclusionGuardBox(
            exclusionBounds = exclusionBounds,
            modifier = modifier
                .fillMaxSize()
                .testTag(SETTINGS_SWIPE_CONTAINER_TEST_TAG),
        ) {
            content()
        }
        return
    }

    val latestOnSwipeLeftToRight by rememberUpdatedState(onSwipeLeftToRight)
    val pagerState = rememberPagerState(
        initialPage = 1,
        pageCount = { 2 },
    )
    val flingBehavior = PagerDefaults.flingBehavior(
        state = pagerState,
        decayAnimationSpec = exponentialDecay(frictionMultiplier = 100f),
        snapPositionalThreshold = NowPlayingSwipeMath.COMMIT_THRESHOLD_FRACTION,
        snapAnimationSpec = tween(durationMillis = 16, easing = FastOutSlowInEasing),
    )

    LaunchedEffect(pagerState.settledPage, pagerState.isScrollInProgress) {
        if (!pagerState.isScrollInProgress && pagerState.settledPage == 0) {
            latestOnSwipeLeftToRight()
            pagerState.scrollToPage(1)
        }
    }

    HorizontalPager(
        state = pagerState,
        beyondViewportPageCount = 0,
        flingBehavior = flingBehavior,
        modifier = modifier
            .fillMaxSize()
            .testTag(SETTINGS_SWIPE_CONTAINER_TEST_TAG),
    ) { page ->
        when (page) {
            1 -> {
                PageExclusionGuardBox(
                    exclusionBounds = exclusionBounds,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    content()
                }
            }
            else -> {
                Box(modifier = Modifier.fillMaxSize())
            }
        }
    }
}

/**
 * Intercepts pointer gestures that start inside [exclusionBounds] (vinyl circle including center
 * spindle, square cover art, seek slider, or ReplayGain preamp slider):
 * - Observes `down` on [PointerEventPass.Initial] without consuming it so child controls receive
 *   normal touch-down events.
 * - Consumes movement deltas on [PointerEventPass.Main] **after** child controls have processed
 *   them and **before** the parent [HorizontalPager] sees them, preventing pager scroll conflicts.
 */
@Composable
internal fun PageExclusionGuardBox(
    exclusionBounds: NowPlayingSwipeExclusionBounds,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
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
                    val pointerId = down.id
                    var tracking = true
                    while (tracking) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val change = event.changes.firstOrNull { it.id == pointerId } ?: event.changes.firstOrNull()
                        if (change == null || !change.pressed) {
                            tracking = false
                        } else if (change.positionChanged()) {
                            change.consume()
                        }
                    }
                }
            },
    ) {
        content()
    }
}
