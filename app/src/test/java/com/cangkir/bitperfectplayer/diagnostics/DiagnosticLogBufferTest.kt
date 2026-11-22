package com.cangkir.bitperfectplayer.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticLogBufferTest {

    @Test
    fun offerAndDrainInto_preservesFifoOrderWithinCapacity() {
        val buffer = DiagnosticLogBuffer(capacity = 4)
        assertTrue(buffer.offer(QueuedLogRecord(DiagnosticLogChannel.AUDIO_PIPELINE, "line-1")))
        assertTrue(buffer.offer(QueuedLogRecord(DiagnosticLogChannel.PERFORMANCE, "line-2")))
        assertTrue(buffer.offer(QueuedLogRecord(DiagnosticLogChannel.ERRORS_AND_CRASHES, "line-3")))
        assertEquals(3, buffer.size())

        val drained = mutableListOf<QueuedLogRecord>()
        val dropped = buffer.drainInto(drained)

        assertEquals(0L, dropped)
        assertEquals(0, buffer.size())
        assertEquals(listOf("line-1", "line-2", "line-3"), drained.map { it.payload })
    }

    @Test
    fun offerOverflow_dropsOldestAndTracksDroppedCountsAccurately() {
        val buffer = DiagnosticLogBuffer(capacity = 3)
        assertTrue(buffer.offer(QueuedLogRecord(DiagnosticLogChannel.AUDIO_PIPELINE, "e1")))
        assertTrue(buffer.offer(QueuedLogRecord(DiagnosticLogChannel.AUDIO_PIPELINE, "e2")))
        assertTrue(buffer.offer(QueuedLogRecord(DiagnosticLogChannel.AUDIO_PIPELINE, "e3")))
        // Next two offers evict e1 and e2
        assertFalse(buffer.offer(QueuedLogRecord(DiagnosticLogChannel.AUDIO_PIPELINE, "e4")))
        assertFalse(buffer.offer(QueuedLogRecord(DiagnosticLogChannel.AUDIO_PIPELINE, "e5")))

        assertEquals(3, buffer.size())
        assertEquals(2L, buffer.totalDroppedCount())

        val drained = mutableListOf<QueuedLogRecord>()
        val droppedSinceDrain = buffer.drainInto(drained)
        assertEquals(2L, droppedSinceDrain)
        assertEquals(listOf("e3", "e4", "e5"), drained.map { it.payload })

        // Subsequent drain without overflow reports 0 pending drops while total stays 2
        val drainedAgain = mutableListOf<QueuedLogRecord>()
        assertEquals(0L, buffer.drainInto(drainedAgain))
        assertEquals(2L, buffer.totalDroppedCount())
    }

    @Test
    fun clear_emptiesBufferedRecordsAndResetsPendingDropCounter() {
        val buffer = DiagnosticLogBuffer(capacity = 2)
        buffer.offer(QueuedLogRecord(DiagnosticLogChannel.PERFORMANCE, "a"))
        buffer.offer(QueuedLogRecord(DiagnosticLogChannel.PERFORMANCE, "b"))
        buffer.offer(QueuedLogRecord(DiagnosticLogChannel.PERFORMANCE, "c"))
        assertEquals(2, buffer.size())

        buffer.clear()
        assertEquals(0, buffer.size())
        val out = mutableListOf<QueuedLogRecord>()
        assertEquals(0L, buffer.drainInto(out))
        assertTrue(out.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonPositiveCapacity_throwsIllegalArgumentException() {
        DiagnosticLogBuffer(capacity = 0)
    }
}
