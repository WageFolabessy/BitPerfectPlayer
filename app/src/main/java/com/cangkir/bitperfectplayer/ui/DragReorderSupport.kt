package com.cangkir.bitperfectplayer.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.cangkir.bitperfectplayer.audio.QueueDragReorderMath
import com.cangkir.bitperfectplayer.audio.VisibleQueueItemMetric
import kotlin.math.abs

internal val DraggedRowShape = RoundedCornerShape(10.dp)

/**
 * Shared LazyColumn drag-reorder state controller used by both `QueueScreen` and
 * `PlaylistDetailView` (under `Custom Order ASC`). Delegates all midpoint overlap math and edge
 * auto-scroll speed calculations to pure-Kotlin [QueueDragReorderMath].
 */
internal class DragReorderState(val listState: LazyListState) {
    var onMove: (Int, Int) -> Unit = { _, _ -> }
    var onDragEnded: (hadMove: Boolean) -> Unit = {}

    var draggedItemKey by mutableStateOf<Any?>(null)
        private set
    var draggedDistanceY by mutableFloatStateOf(0f)
        private set

    private var pendingExpectedIndex: Int = -1
    private var didMoveDuringDrag: Boolean = false

    val isDragging: Boolean
        get() = draggedItemKey != null

    val hasPendingSwap: Boolean
        get() = pendingExpectedIndex >= 0

    fun findVisibleItemAtY(yPx: Float): LazyListItemInfo? {
        val layoutInfo = listState.layoutInfo
        if (layoutInfo.totalItemsCount <= 1) return null
        val contentY = yPx.toInt() - layoutInfo.beforeContentPadding
        return layoutInfo.visibleItemsInfo.firstOrNull { info ->
            contentY in info.offset until (info.offset + info.size)
        }
    }

    fun onDragStart(itemKey: Any) {
        draggedItemKey = itemKey
        draggedDistanceY = 0f
        pendingExpectedIndex = -1
        didMoveDuringDrag = false
    }

    fun onDragBy(deltaY: Float) {
        if (draggedItemKey == null) return
        draggedDistanceY += deltaY
        checkForSwap()
    }

    fun finishDrag() {
        val wasDragging = draggedItemKey != null
        val moved = didMoveDuringDrag
        reset()
        if (wasDragging) {
            onDragEnded(moved)
        }
    }

    fun reset() {
        draggedItemKey = null
        draggedDistanceY = 0f
        pendingExpectedIndex = -1
        didMoveDuringDrag = false
    }

    fun checkForSwap() {
        val activeKey = draggedItemKey ?: return
        val visibleInfos = listState.layoutInfo.visibleItemsInfo
        val current = visibleInfos.firstOrNull { it.key == activeKey } ?: return
        if (pendingExpectedIndex >= 0) {
            if (current.index != pendingExpectedIndex) return
            pendingExpectedIndex = -1
        }
        val metrics = visibleInfos.map { VisibleQueueItemMetric(it.index, it.offset, it.size) }
        val decision = QueueDragReorderMath.computeDragSwap(
            draggedItem = VisibleQueueItemMetric(current.index, current.offset, current.size),
            draggedDistanceY = draggedDistanceY,
            visibleItems = metrics,
        ) ?: return

        val firstIdx = listState.firstVisibleItemIndex
        val firstOffset = listState.firstVisibleItemScrollOffset
        pendingExpectedIndex = decision.toIndex
        draggedDistanceY += decision.offsetAdjustmentPx
        didMoveDuringDrag = true
        onMove(decision.fromIndex, decision.toIndex)
        if (decision.fromIndex == firstIdx || decision.toIndex == firstIdx) {
            listState.requestScrollToItem(firstIdx, firstOffset)
        }
    }

    fun computeOverscrollDeltaPx(edgeZonePx: Float, maxSpeedPx: Float): Float {
        val activeKey = draggedItemKey ?: return 0f
        val layoutInfo = listState.layoutInfo
        val current = layoutInfo.visibleItemsInfo.firstOrNull { it.key == activeKey } ?: return 0f
        val draggedTop = current.offset + draggedDistanceY
        val draggedBottom = draggedTop + current.size
        return QueueDragReorderMath.computeEdgeAutoScrollPx(
            draggedTop = draggedTop,
            draggedBottom = draggedBottom,
            viewportStart = layoutInfo.viewportStartOffset,
            viewportEnd = layoutInfo.viewportEndOffset,
            edgeZonePx = edgeZonePx,
            maxSpeedPx = maxSpeedPx,
            canScrollBackward = listState.canScrollBackward,
            canScrollForward = listState.canScrollForward,
        )
    }
}

@Composable
internal fun DragReorderLifecycleEffects(dragState: DragReorderState, rowKeys: List<Any>, itemCount: Int) {
    LaunchedEffect(itemCount) {
        if (itemCount <= 1 && dragState.isDragging) {
            dragState.reset()
        }
    }
    LaunchedEffect(rowKeys) {
        if (dragState.isDragging) {
            val activeKey = dragState.draggedItemKey
            if (activeKey == null || activeKey !in rowKeys) {
                dragState.reset()
            } else {
                dragState.checkForSwap()
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose { dragState.reset() }
    }

    val density = LocalDensity.current
    val edgeZonePx = with(density) { 64.dp.toPx() }
    val maxAutoScrollPxPerFrame = with(density) { 10.dp.toPx() }
    LaunchedEffect(dragState.isDragging) {
        if (!dragState.isDragging) return@LaunchedEffect
        while (dragState.isDragging) {
            withFrameNanos { }
            if (dragState.hasPendingSwap) {
                dragState.checkForSwap()
            }
            val scrollDelta = dragState.computeOverscrollDeltaPx(edgeZonePx, maxAutoScrollPxPerFrame)
            if (scrollDelta != 0f) {
                val consumed = dragState.listState.scrollBy(scrollDelta)
                if (consumed != 0f) {
                    dragState.checkForSwap()
                }
            }
        }
    }
}

@Composable
internal fun Modifier.dragHandleReorderPointer(dragState: DragReorderState, enabled: Boolean, handleColumnWidth: Dp = 56.dp): Modifier {
    if (!enabled) return this
    val density = LocalDensity.current
    val haptic: HapticFeedback = LocalHapticFeedback.current
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val handleColumnWidthPx = with(density) { handleColumnWidth.toPx() }
    val dragStartSlopPx = with(density) { 4.dp.toPx() }

    return this.pointerInput(dragState, isRtl, handleColumnWidthPx, dragStartSlopPx) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val inHandleColumn = if (isRtl) {
                down.position.x >= size.width - handleColumnWidthPx
            } else {
                down.position.x <= handleColumnWidthPx
            }
            if (!inHandleColumn) return@awaitEachGesture
            val hitItem = dragState.findVisibleItemAtY(down.position.y) ?: return@awaitEachGesture
            down.consume()
            var prevY = down.position.y
            var started = false
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        change.consume()
                        break
                    }
                    val dy = change.position.y - prevY
                    prevY = change.position.y
                    val totalDeltaY = change.position.y - down.position.y
                    if (!started && abs(totalDeltaY) >= dragStartSlopPx) {
                        started = true
                        dragState.onDragStart(hitItem.key)
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        dragState.onDragBy(totalDeltaY)
                    } else if (started && dy != 0f) {
                        dragState.onDragBy(dy)
                    }
                    change.consume()
                }
            } finally {
                if (started) {
                    dragState.finishDrag()
                } else {
                    dragState.reset()
                }
            }
        }
    }
}
