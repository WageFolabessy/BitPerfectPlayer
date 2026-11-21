package com.cangkir.bitperfectplayer.audio

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackQueueManagerTest {

    @Test
    fun emptyQueue_handlesAllOperationsGracefully() {
        val manager = PlaybackQueueManager<String>()

        assertTrue(manager.isEmpty())
        assertEquals(0, manager.size)
        assertNull(manager.currentItem)
        assertEquals(-1, manager.snapshot().currentIndex)
        assertNull(manager.next())
        assertNull(manager.previous())
        assertNull(manager.removeAt(0))
        assertFalse(manager.moveItem(0, 1))
        assertFalse(manager.restoreActiveIndex(0))
        assertNull(manager.selectIndex(0))
    }

    @Test
    fun singleTrackQueue_respectsAllRepeatModes() {
        val manager = PlaybackQueueManager<String>()
        manager.setQueue(listOf("track-1"))
        assertEquals("track-1", manager.currentItem)

        // Repeat OFF: next and previous return null
        manager.setRepeatMode(RepeatMode.OFF)
        assertNull(manager.next())
        assertNull(manager.previous())
        assertEquals("track-1", manager.currentItem)

        // Repeat ONE: automatic next replays current track; user skip at end returns null
        manager.setRepeatMode(RepeatMode.ONE)
        assertEquals("track-1", manager.next(userInitiated = false))
        assertNull(manager.next(userInitiated = true))
        assertNull(manager.previous())

        // Repeat ALL: next and previous wrap onto the single track
        manager.setRepeatMode(RepeatMode.ALL)
        assertEquals("track-1", manager.next())
        assertEquals("track-1", manager.previous())
    }

    @Test
    fun multiTrackNavigation_andRepeatModes() {
        val manager = PlaybackQueueManager<String>()
        manager.setQueue(listOf("A", "B", "C"), startIndex = 0)

        // Repeat OFF
        assertEquals("A", manager.currentItem)
        assertNull(manager.previous())
        assertEquals("B", manager.next())
        assertEquals("C", manager.next())
        assertNull(manager.next())
        assertEquals("C", manager.currentItem)
        assertEquals("B", manager.previous())

        // Repeat ALL wraps around forward and backward
        manager.setRepeatMode(RepeatMode.ALL)
        assertEquals("C", manager.next())
        assertEquals("A", manager.next())
        assertEquals("C", manager.previous())

        // Repeat ONE keeps current track on automatic completion, advances on user next
        manager.selectIndex(1) // B
        manager.setRepeatMode(RepeatMode.ONE)
        assertEquals("B", manager.next(userInitiated = false))
        assertEquals("C", manager.next(userInitiated = true))
    }

    @Test
    fun shuffleToggle_isNonDestructiveAndPreservesCurrentTrack() {
        val tracks = listOf("T1", "T2", "T3", "T4", "T5", "T6")
        val manager = PlaybackQueueManager<String>()
        manager.setQueue(tracks, startIndex = 2) // T3 active
        assertEquals("T3", manager.currentItem)

        val shuffled = manager.setShuffle(true, random = Random(42))
        assertTrue(shuffled.shuffleEnabled)
        assertEquals("T3", shuffled.currentItem)
        assertEquals(0, shuffled.currentIndex)
        assertEquals(tracks.toSet(), shuffled.items.toSet())
        assertEquals(tracks, shuffled.originalItems)

        // Advance inside shuffled queue to another track
        val nextTrack = manager.next()
        assertTrue(nextTrack != null && nextTrack != "T3")

        // Turning shuffle off restores exact original order and preserves active track
        val restored = manager.setShuffle(false)
        assertFalse(restored.shuffleEnabled)
        assertEquals(tracks, restored.items)
        assertEquals(nextTrack, restored.currentItem)
        assertEquals(tracks.indexOf(nextTrack), restored.currentIndex)
    }

    @Test
    fun enqueueRemoveReorderAndRestore_preserveConsistency() {
        val manager = PlaybackQueueManager<String>()
        manager.enqueue("A")
        manager.enqueue("B")
        manager.enqueue("C")
        manager.enqueueNext("A2") // Inserted right after A (index 0)
        assertEquals(listOf("A", "A2", "B", "C"), manager.snapshot().items)
        assertEquals("A", manager.currentItem)

        // Select B (index 2), then move index 0 ("A") to index 3 -> [A2, B, C, A], currentIndex becomes 1 ("B")
        manager.selectIndex(2)
        assertEquals("B", manager.currentItem)
        assertTrue(manager.moveItem(0, 3))
        assertEquals(listOf("A2", "B", "C", "A"), manager.snapshot().items)
        assertEquals("B", manager.currentItem)
        assertEquals(1, manager.snapshot().currentIndex)

        // Remove item before current (index 0: "A2") -> [B, C, A], currentIndex becomes 0 ("B")
        assertEquals("A2", manager.removeAt(0))
        assertEquals("B", manager.currentItem)
        assertEquals(0, manager.snapshot().currentIndex)

        // Remove active item (index 0: "B") -> [C, A], currentIndex stays 0 ("C")
        assertEquals("B", manager.removeAt(0))
        assertEquals("C", manager.currentItem)

        // Restore by predicate and index
        assertTrue(manager.restoreActiveItem { it == "A" })
        assertEquals("A", manager.currentItem)
        assertTrue(manager.restoreActiveIndex(0))
        assertEquals("C", manager.currentItem)
        assertFalse(manager.restoreActiveIndex(99))
    }

    @Test
    fun duplicateTracksInQueue_remainUnambiguousAcrossShuffle() {
        val manager = PlaybackQueueManager<String>()
        manager.setQueue(listOf("Same", "Other", "Same"), startIndex = 2)
        manager.setShuffle(true, random = Random(7))
        assertEquals("Same", manager.currentItem)
        assertEquals(3, manager.size)

        val restored = manager.setShuffle(false)
        assertEquals(listOf("Same", "Other", "Same"), restored.items)
        assertEquals(2, restored.currentIndex)
    }
}
