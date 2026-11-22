package com.cangkir.bitperfectplayer.diagnostics

/**
 * Single pre-formatted diagnostic entry queued for asynchronous background persistence.
 */
data class QueuedLogRecord(val channel: DiagnosticLogChannel, val payload: String)

/**
 * Pure-Kotlin bounded ring queue with drop-oldest overflow policy (zero `android.*` / `androidx.*`
 * imports). Producers never block even under burst logging conditions.
 */
class DiagnosticLogBuffer(val capacity: Int = DEFAULT_CAPACITY) {
    init {
        require(capacity > 0) { "DiagnosticLogBuffer capacity must be > 0" }
    }

    private val lock = Any()
    private val ring = arrayOfNulls<QueuedLogRecord>(capacity)
    private var head = 0
    private var tail = 0
    private var count = 0
    private var totalDropped = 0L
    private var pendingDroppedSinceDrain = 0L

    /**
     * Enqueues [record] immediately without blocking. If the buffer is full, drops the oldest
     * entry and increments the overflow counter. Returns `true` if accepted without dropping, or
     * `false` if an oldest entry had to be evicted.
     */
    fun offer(record: QueuedLogRecord): Boolean = synchronized(lock) {
        var droppedOldest = false
        if (count == capacity) {
            ring[head] = null
            head = (head + 1) % capacity
            count--
            totalDropped++
            pendingDroppedSinceDrain++
            droppedOldest = true
        }
        ring[tail] = record
        tail = (tail + 1) % capacity
        count++
        !droppedOldest
    }

    /**
     * Drains all buffered records in FIFO order into [destination] and returns the number of
     * oldest entries dropped due to overflow since the previous drain.
     */
    fun drainInto(destination: MutableList<QueuedLogRecord>): Long = synchronized(lock) {
        while (count > 0) {
            val item = ring[head]
            ring[head] = null
            head = (head + 1) % capacity
            count--
            if (item != null) {
                destination.add(item)
            }
        }
        val dropped = pendingDroppedSinceDrain
        pendingDroppedSinceDrain = 0L
        dropped
    }

    fun clear() = synchronized(lock) {
        for (i in ring.indices) {
            ring[i] = null
        }
        head = 0
        tail = 0
        count = 0
        pendingDroppedSinceDrain = 0L
    }

    fun size(): Int = synchronized(lock) { count }

    fun totalDroppedCount(): Long = synchronized(lock) { totalDropped }

    companion object {
        const val DEFAULT_CAPACITY = 512
    }
}
