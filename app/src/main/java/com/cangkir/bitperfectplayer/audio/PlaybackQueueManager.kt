package com.cangkir.bitperfectplayer.audio

import kotlin.random.Random

/**
 * Playback queue repeat behavior.
 */
enum class RepeatMode {
    OFF,
    ALL,
    ONE,
    ;

    fun nextMode(): RepeatMode = when (this) {
        OFF -> ALL
        ALL -> ONE
        ONE -> OFF
    }
}

/**
 * Immutable snapshot of the active playback queue state.
 */
data class QueueSnapshot<T>(
    val items: List<T> = emptyList(),
    val originalItems: List<T> = emptyList(),
    val currentIndex: Int = -1,
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
) {
    val currentItem: T?
        get() = items.getOrNull(currentIndex)

    val isEmpty: Boolean
        get() = items.isEmpty()

    val size: Int
        get() = items.size
}

/**
 * Pure-Kotlin playback queue manager (zero `android.*` imports).
 *
 * Maintains an internal unique entry ID per queued slot so duplicate tracks remain unambiguous
 * across reordering, removal, and non-destructive Shuffle toggling.
 */
class PlaybackQueueManager<T : Any>(
    private val defaultRandom: Random = Random.Default,
) {
    private data class QueueEntry<T>(
        val id: Long,
        val item: T,
    )

    private var nextEntryId: Long = 1L
    private val originalOrder = mutableListOf<QueueEntry<T>>()
    private val activeOrder = mutableListOf<QueueEntry<T>>()
    private var currentIndex: Int = -1
    private var shuffleEnabled: Boolean = false
    private var repeatMode: RepeatMode = RepeatMode.OFF

    fun snapshot(): QueueSnapshot<T> = QueueSnapshot(
        items = activeOrder.map { it.item },
        originalItems = originalOrder.map { it.item },
        currentIndex = currentIndex,
        shuffleEnabled = shuffleEnabled,
        repeatMode = repeatMode,
    )

    val currentItem: T?
        get() = activeOrder.getOrNull(currentIndex)?.item

    val size: Int
        get() = activeOrder.size

    fun isEmpty(): Boolean = activeOrder.isEmpty()

    fun setQueue(
        items: List<T>,
        startIndex: Int = 0,
        random: Random = defaultRandom,
    ): QueueSnapshot<T> {
        originalOrder.clear()
        activeOrder.clear()
        if (items.isEmpty()) {
            currentIndex = -1
            return snapshot()
        }

        val entries = items.map { QueueEntry(nextEntryId++, it) }
        originalOrder.addAll(entries)

        val clampedStart = startIndex.coerceIn(0, entries.lastIndex)
        if (shuffleEnabled) {
            val selected = entries[clampedStart]
            val rest = entries.filterIndexed { idx, _ -> idx != clampedStart }.shuffled(random)
            activeOrder.add(selected)
            activeOrder.addAll(rest)
            currentIndex = 0
        } else {
            activeOrder.addAll(entries)
            currentIndex = clampedStart
        }
        return snapshot()
    }

    fun clear(): QueueSnapshot<T> {
        originalOrder.clear()
        activeOrder.clear()
        currentIndex = -1
        return snapshot()
    }

    fun enqueue(item: T): QueueSnapshot<T> {
        val entry = QueueEntry(nextEntryId++, item)
        originalOrder.add(entry)
        activeOrder.add(entry)
        if (currentIndex == -1) {
            currentIndex = 0
        }
        return snapshot()
    }

    fun enqueueNext(item: T): QueueSnapshot<T> {
        val entry = QueueEntry(nextEntryId++, item)
        if (activeOrder.isEmpty() || currentIndex !in activeOrder.indices) {
            originalOrder.add(entry)
            activeOrder.add(entry)
            currentIndex = 0
        } else {
            val insertActiveAt = currentIndex + 1
            activeOrder.add(insertActiveAt, entry)
            val currentEntry = activeOrder[currentIndex]
            val origIdx = originalOrder.indexOfFirst { it.id == currentEntry.id }
            if (origIdx >= 0) {
                originalOrder.add(origIdx + 1, entry)
            } else {
                originalOrder.add(entry)
            }
        }
        return snapshot()
    }

    fun removeAt(activeIndex: Int): T? {
        if (activeIndex !in activeOrder.indices) return null
        val removed = activeOrder.removeAt(activeIndex)
        originalOrder.removeAll { it.id == removed.id }

        currentIndex = when {
            activeOrder.isEmpty() -> -1
            activeIndex < currentIndex -> currentIndex - 1
            activeIndex == currentIndex -> currentIndex.coerceAtMost(activeOrder.lastIndex)
            else -> currentIndex
        }
        return removed.item
    }

    fun moveItem(fromActiveIndex: Int, toActiveIndex: Int): Boolean {
        if (fromActiveIndex !in activeOrder.indices || toActiveIndex !in activeOrder.indices) {
            return false
        }
        if (fromActiveIndex == toActiveIndex) return true

        val moved = activeOrder.removeAt(fromActiveIndex)
        activeOrder.add(toActiveIndex, moved)

        if (!shuffleEnabled) {
            val origMoved = originalOrder.removeAt(fromActiveIndex)
            originalOrder.add(toActiveIndex, origMoved)
        }

        currentIndex = when {
            currentIndex == fromActiveIndex -> toActiveIndex
            fromActiveIndex < currentIndex && toActiveIndex >= currentIndex -> currentIndex - 1
            fromActiveIndex > currentIndex && toActiveIndex <= currentIndex -> currentIndex + 1
            else -> currentIndex
        }
        return true
    }

    fun selectIndex(activeIndex: Int): T? {
        if (activeIndex !in activeOrder.indices) return null
        currentIndex = activeIndex
        return activeOrder[currentIndex].item
    }

    fun restoreActiveIndex(index: Int): Boolean {
        if (index !in activeOrder.indices) return false
        currentIndex = index
        return true
    }

    fun restoreActiveItem(predicate: (T) -> Boolean): Boolean {
        val found = activeOrder.indexOfFirst { predicate(it.item) }
        if (found < 0) return false
        currentIndex = found
        return true
    }

    /**
     * Non-destructive shuffle toggle.
     * - Enabling shuffle pins the currently playing track at index 0 and shuffles the remaining tracks.
     * - Disabling shuffle restores the exact original order while keeping the currently playing track active.
     */
    fun setShuffle(enabled: Boolean, random: Random = defaultRandom): QueueSnapshot<T> {
        if (shuffleEnabled == enabled) return snapshot()
        shuffleEnabled = enabled

        if (activeOrder.isEmpty()) {
            currentIndex = -1
            return snapshot()
        }

        val currentEntry = activeOrder.getOrNull(currentIndex)
        activeOrder.clear()

        if (enabled) {
            if (currentEntry != null) {
                val others = originalOrder.filter { it.id != currentEntry.id }.shuffled(random)
                activeOrder.add(currentEntry)
                activeOrder.addAll(others)
                currentIndex = 0
            } else {
                activeOrder.addAll(originalOrder.shuffled(random))
                currentIndex = 0
            }
        } else {
            activeOrder.addAll(originalOrder)
            currentIndex = if (currentEntry != null) {
                activeOrder.indexOfFirst { it.id == currentEntry.id }.coerceAtLeast(0)
            } else {
                0
            }
        }
        return snapshot()
    }

    fun setRepeatMode(mode: RepeatMode): QueueSnapshot<T> {
        repeatMode = mode
        return snapshot()
    }

    fun cycleRepeatMode(): RepeatMode {
        repeatMode = repeatMode.nextMode()
        return repeatMode
    }

    /**
     * Non-mutating peek at the upcoming track respecting [repeatMode], used by gapless preloading.
     */
    fun peekNext(userInitiated: Boolean = false): T? {
        if (activeOrder.isEmpty()) return null

        if (repeatMode == RepeatMode.ONE && !userInitiated) {
            return activeOrder.getOrNull(currentIndex)?.item
        }

        if (currentIndex + 1 < activeOrder.size) {
            return activeOrder[currentIndex + 1].item
        }

        return when (repeatMode) {
            RepeatMode.ALL -> activeOrder[0].item
            RepeatMode.OFF, RepeatMode.ONE -> null
        }
    }

    /**
     * Advances to the next track respecting [repeatMode].
     *
     * When [repeatMode] is [RepeatMode.ONE] and [userInitiated] is `false` (natural track end),
     * stays on and returns the current item. When [userInitiated] is `true`, advances to the next
     * track in the queue (wrapping on [RepeatMode.ALL] or returning `null` at end).
     */
    fun next(userInitiated: Boolean = false): T? {
        if (activeOrder.isEmpty()) return null

        if (repeatMode == RepeatMode.ONE && !userInitiated) {
            return activeOrder.getOrNull(currentIndex)?.item
        }

        if (currentIndex + 1 < activeOrder.size) {
            currentIndex += 1
            return activeOrder[currentIndex].item
        }

        return when (repeatMode) {
            RepeatMode.ALL -> {
                currentIndex = 0
                activeOrder[0].item
            }
            RepeatMode.OFF, RepeatMode.ONE -> null
        }
    }

    /**
     * Moves to the previous track respecting [repeatMode].
     * Wraps to the end of the queue when [repeatMode] is [RepeatMode.ALL]; returns `null` at index 0 otherwise.
     */
    fun previous(): T? {
        if (activeOrder.isEmpty()) return null

        if (currentIndex > 0) {
            currentIndex -= 1
            return activeOrder[currentIndex].item
        }

        return when (repeatMode) {
            RepeatMode.ALL -> {
                currentIndex = activeOrder.lastIndex
                activeOrder[currentIndex].item
            }
            RepeatMode.OFF, RepeatMode.ONE -> null
        }
    }
}
