package com.cangkir.bitperfectplayer.ui.nowplaying

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal const val NOW_PLAYING_SWIPE_CONTAINER_TEST_TAG = "now_playing_swipe_container"

/**
 * Mutable root-coordinate exclusion bounds updated on layout without causing recompositions.
 * Prevents `NowPlayingScreen` screen-level horizontal swipes from competing with circular
 * [InteractiveVinylDisc] scrubbing (including center spindle), [SwipeableSquareArtwork], or seek
 * [Slider] drags.
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

private data class SwipeReleaseMetrics(val totalDragPx: Float, val velocityX: Float)

@Composable
internal fun NowPlayingSwipeContainer(
    exclusionBounds: NowPlayingSwipeExclusionBounds,
    onSwipeBack: () -> Unit,
    onSwipeSettings: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val minCommitPx = with(density) { NowPlayingSwipeMath.MIN_COMMIT_THRESHOLD_DP.dp.toPx() }
    val maxCommitPx = with(density) { NowPlayingSwipeMath.MAX_COMMIT_THRESHOLD_DP.dp.toPx() }
    val flingVelocityPx = with(density) { NowPlayingSwipeMath.FLING_VELOCITY_THRESHOLD_DP_PER_SEC.dp.toPx() }
    val minFlingDragPx = with(density) { NowPlayingSwipeMath.MIN_FLING_DRAG_DP.dp.toPx() }
    val maxVisualOffsetPx = with(density) { NowPlayingSwipeMath.MAX_VISUAL_OFFSET_DP.dp.toPx() }
    exclusionBounds.sliderHorizontalPaddingPx = with(density) { 24.dp.toPx() }
    exclusionBounds.sliderVerticalPaddingPx = with(density) { 8.dp.toPx() }

    val dragOffsetPx = remember { mutableFloatStateOf(0f) }
    val containerWidthPx = remember { mutableFloatStateOf(0f) }
    val coroutineScope = rememberCoroutineScope()
    val settleJobHolder = remember { arrayOfNulls<Job>(1) }

    val latestOnSwipeBack by rememberUpdatedState(onSwipeBack)
    val latestOnSwipeSettings by rememberUpdatedState(onSwipeSettings)

    fun launchSpringReset(startValue: Float) {
        settleJobHolder[0]?.cancel()
        if (abs(startValue) < 0.5f) {
            dragOffsetPx.floatValue = 0f
            return
        }
        settleJobHolder[0] = coroutineScope.launch {
            AnimationState(initialValue = startValue).animateTo(
                targetValue = 0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            ) {
                dragOffsetPx.floatValue = value
            }
        }
    }

    Box(
        modifier = modifier
            .testTag(NOW_PLAYING_SWIPE_CONTAINER_TEST_TAG)
            .onGloballyPositioned { coords ->
                exclusionBounds.containerRootOffset = coords.positionInRoot()
                containerWidthPx.floatValue = coords.size.width.toFloat()
            }
            .pointerInput(exclusionBounds, minCommitPx, maxCommitPx, flingVelocityPx, minFlingDragPx) {
                detectNowPlayingBodyHorizontalSwipe(
                    exclusionBounds = exclusionBounds,
                    onGestureLockStart = {
                        settleJobHolder[0]?.cancel()
                        settleJobHolder[0] = null
                    },
                    onDragDelta = { dx -> dragOffsetPx.floatValue = dx },
                    onGestureEnd = { release ->
                        val commitThreshold = NowPlayingSwipeMath.computeCommitThresholdPx(
                            containerWidthPx = containerWidthPx.floatValue,
                            minThresholdPx = minCommitPx,
                            maxThresholdPx = maxCommitPx,
                        )
                        val outcome = release?.let {
                            NowPlayingSwipeMath.decideSwipeOutcome(
                                totalDragPx = it.totalDragPx,
                                velocityPxPerSec = it.velocityX,
                                commitThresholdPx = commitThreshold,
                                flingVelocityThresholdPxPerSec = flingVelocityPx,
                                minFlingDragPx = minFlingDragPx,
                            )
                        } ?: NowPlayingSwipeDirection.NONE

                        when (outcome) {
                            NowPlayingSwipeDirection.SWIPE_RIGHT_BACK -> {
                                dragOffsetPx.floatValue = 0f
                                latestOnSwipeBack()
                            }
                            NowPlayingSwipeDirection.SWIPE_LEFT_SETTINGS -> {
                                dragOffsetPx.floatValue = 0f
                                latestOnSwipeSettings()
                            }
                            NowPlayingSwipeDirection.NONE -> launchSpringReset(dragOffsetPx.floatValue)
                        }
                    },
                )
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = NowPlayingSwipeMath.computeVisualTranslationPx(
                        rawDragPx = dragOffsetPx.floatValue,
                        maxVisualOffsetPx = maxVisualOffsetPx,
                    )
                },
        ) {
            content()
        }

        NowPlayingSwipeEdgeIndicator(
            dragOffsetPxState = dragOffsetPx,
            containerWidthPxState = containerWidthPx,
            minCommitPx = minCommitPx,
            maxCommitPx = maxCommitPx,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private suspend fun PointerInputScope.detectNowPlayingBodyHorizontalSwipe(
    exclusionBounds: NowPlayingSwipeExclusionBounds,
    onGestureLockStart: () -> Unit,
    onDragDelta: (Float) -> Unit,
    onGestureEnd: (SwipeReleaseMetrics?) -> Unit,
) {
    val touchSlop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = true)
        if (exclusionBounds.containsLocalPoint(down.position)) {
            return@awaitEachGesture
        }
        val velocityTracker = VelocityTracker()
        var lockedHorizontal = false
        var lastTotalDx = 0f
        var completedCleanly = false

        try {
            val outcome = trackHorizontalSwipeUntilRelease(
                pointerId = down.id,
                startPosition = down.position,
                startTimeMs = down.uptimeMillis,
                touchSlop = touchSlop,
                velocityTracker = velocityTracker,
                onLock = {
                    lockedHorizontal = true
                    onGestureLockStart()
                },
                onUpdate = { totalDx ->
                    lastTotalDx = totalDx
                    onDragDelta(totalDx)
                },
            )
            completedCleanly = outcome.releasedByUser
            if (lockedHorizontal) {
                val release = if (outcome.releasedByUser) {
                    SwipeReleaseMetrics(
                        totalDragPx = lastTotalDx,
                        velocityX = velocityTracker.calculateVelocity().x,
                    )
                } else {
                    null
                }
                onGestureEnd(release)
            }
        } finally {
            if (!completedCleanly && lockedHorizontal) {
                onGestureEnd(null)
            }
        }
    }
}

private data class TrackResult(val releasedByUser: Boolean)

private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.trackHorizontalSwipeUntilRelease(
    pointerId: PointerId,
    startPosition: Offset,
    startTimeMs: Long,
    touchSlop: Float,
    velocityTracker: VelocityTracker,
    onLock: () -> Unit,
    onUpdate: (Float) -> Unit,
): TrackResult {
    var locked = false
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Main)
        val change = event.changes.firstOrNull { it.id == pointerId } ?: return TrackResult(false)
        if (!change.pressed) {
            if (locked) change.consume()
            return TrackResult(releasedByUser = locked)
        }
        if (change.isConsumed) {
            return TrackResult(releasedByUser = false)
        }
        val totalDx = change.position.x - startPosition.x
        val totalDy = change.position.y - startPosition.y
        if (!locked) {
            if (NowPlayingSwipeMath.shouldAbortForVerticalScroll(totalDx, totalDy, touchSlop)) {
                return TrackResult(releasedByUser = false)
            }
            if (NowPlayingSwipeMath.shouldLockHorizontalSwipe(totalDx, totalDy, touchSlop)) {
                locked = true
                onLock()
                velocityTracker.addPosition(startTimeMs, startPosition)
                velocityTracker.addPosition(change.uptimeMillis, change.position)
                change.consume()
                onUpdate(totalDx)
            }
        } else {
            if (change.positionChanged()) {
                velocityTracker.addPosition(change.uptimeMillis, change.position)
            }
            change.consume()
            onUpdate(totalDx)
        }
    }
}

@Composable
private fun NowPlayingSwipeEdgeIndicator(
    dragOffsetPxState: FloatState,
    containerWidthPxState: FloatState,
    minCommitPx: Float,
    maxCommitPx: Float,
    modifier: Modifier = Modifier,
) {
    val dragPx = dragOffsetPxState.floatValue
    if (abs(dragPx) < 1f) return

    val thresholdPx = NowPlayingSwipeMath.computeCommitThresholdPx(
        containerWidthPx = containerWidthPxState.floatValue,
        minThresholdPx = minCommitPx,
        maxThresholdPx = maxCommitPx,
    )
    val progress = NowPlayingSwipeMath.computeProgress(dragPx, thresholdPx)
    val committed = progress >= 1f
    val isBack = dragPx > 0f

    Box(modifier = modifier) {
        Surface(
            modifier = Modifier
                .align(if (isBack) Alignment.CenterStart else Alignment.CenterEnd)
                .padding(horizontal = 16.dp)
                .graphicsLayer { alpha = 0.35f + 0.65f * progress },
            shape = RoundedCornerShape(20.dp),
            color = if (committed) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.94f)
            },
            contentColor = if (committed) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
            tonalElevation = 0.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (isBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = "Back",
                        style = MaterialTheme.typography.labelMedium,
                    )
                } else {
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}
