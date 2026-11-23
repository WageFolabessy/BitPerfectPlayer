package com.cangkir.bitperfectplayer.audio

/**
 * Latest-intent control command queued for the single-flight audio control drain loop.
 */
sealed interface CoalescedControlIntent<out T> {
    data class LoadTrack<T>(val request: T, val startAfterLoad: Boolean) : CoalescedControlIntent<T>

    data object Play : CoalescedControlIntent<Nothing>

    data object Pause : CoalescedControlIntent<Nothing>

    data object Stop : CoalescedControlIntent<Nothing>
}

/**
 * Thread-safe, single-flight latest-intent coalescer so rapid Play/Pause or track-skip commands
 * collapse cleanly instead of queueing up behind slow Binder IPC and firing in a burst.
 */
class PlaybackCommandCoalescer<T> {
    private val lock = Any()
    private var pendingIntent: CoalescedControlIntent<T>? = null
    private var activeIntent: CoalescedControlIntent<T>? = null
    private var drainingOrScheduled = false

    fun submitLoadTrack(request: T, startAfterLoad: Boolean): Boolean = synchronized(lock) {
        pendingIntent = CoalescedControlIntent.LoadTrack(request = request, startAfterLoad = startAfterLoad)
        scheduleIfIdleLocked()
    }

    fun submitPlay(): Boolean = synchronized(lock) {
        val current = pendingIntent
        pendingIntent = when (current) {
            is CoalescedControlIntent.LoadTrack<T> -> current.copy(startAfterLoad = true)
            else -> CoalescedControlIntent.Play
        }
        scheduleIfIdleLocked()
    }

    fun submitPause(): Boolean = synchronized(lock) {
        val current = pendingIntent
        pendingIntent = when (current) {
            is CoalescedControlIntent.LoadTrack<T> -> current.copy(startAfterLoad = false)
            else -> CoalescedControlIntent.Pause
        }
        scheduleIfIdleLocked()
    }

    fun submitStop(): Boolean = synchronized(lock) {
        pendingIntent = CoalescedControlIntent.Stop
        scheduleIfIdleLocked()
    }

    fun cancelPendingStartOnExternalPause(): Unit = synchronized(lock) {
        when (val current = pendingIntent) {
            is CoalescedControlIntent.Play -> pendingIntent = null
            is CoalescedControlIntent.LoadTrack<T> -> {
                if (current.startAfterLoad) {
                    pendingIntent = current.copy(startAfterLoad = false)
                }
            }
            else -> Unit
        }
    }

    fun pollNextIntent(): CoalescedControlIntent<T>? = synchronized(lock) {
        val next = pendingIntent
        if (next == null) {
            activeIntent = null
            drainingOrScheduled = false
            return@synchronized null
        }
        pendingIntent = null
        activeIntent = next
        next
    }

    fun markExecutionFinished(): Unit = synchronized(lock) {
        activeIntent = null
    }

    fun onDrainExited(): Boolean = synchronized(lock) {
        activeIntent = null
        if (pendingIntent != null) {
            drainingOrScheduled = true
            true
        } else {
            drainingOrScheduled = false
            false
        }
    }

    fun hasPendingPauseOrStop(): Boolean = synchronized(lock) {
        pendingIntent is CoalescedControlIntent.Pause || pendingIntent is CoalescedControlIntent.Stop
    }

    fun hasPendingLoad(): Boolean = synchronized(lock) {
        pendingIntent is CoalescedControlIntent.LoadTrack<*>
    }

    fun isStartOrLoadInFlight(): Boolean = synchronized(lock) {
        isStartingIntent(pendingIntent) || isStartingIntent(activeIntent)
    }

    fun clearAll(): Unit = synchronized(lock) {
        pendingIntent = null
        activeIntent = null
        drainingOrScheduled = false
    }

    private fun scheduleIfIdleLocked(): Boolean {
        if (drainingOrScheduled) {
            return false
        }
        drainingOrScheduled = true
        return true
    }

    private fun isStartingIntent(intent: CoalescedControlIntent<T>?): Boolean = when (intent) {
        is CoalescedControlIntent.Play -> true
        is CoalescedControlIntent.LoadTrack<T> -> intent.startAfterLoad
        else -> false
    }
}
