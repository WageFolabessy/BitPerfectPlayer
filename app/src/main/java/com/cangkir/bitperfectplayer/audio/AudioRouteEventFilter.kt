package com.cangkir.bitperfectplayer.audio

/**
 * Pure-Kotlin snapshot of an Android audio endpoint passed from `AudioDeviceCallback` or
 * `AudioManager.getDevices(...)` so route filtering, deduplication, and `ACTION_AUDIO_BECOMING_NOISY`
 * coalescing remain 100% deterministic and JVM unit-testable (zero `android.*` imports).
 */
data class RouteDeviceDescriptor(
    val id: Int,
    val name: String,
    val isSink: Boolean,
    val isUsbOutput: Boolean,
    val isExternalOutput: Boolean,
)

/**
 * Deterministic routing action produced by [AudioRouteEventFilter].
 */
sealed class RouteFilterDecision {
    /** Ignore non-output sources (e.g. USB microphones) or redundant callbacks with unchanged route. */
    data object Ignore : RouteFilterDecision()

    /** Cold-start initial `AudioDeviceCallback` sync (no auto-pause). */
    data class SyncInitialDevice(val device: RouteDeviceDescriptor) : RouteFilterDecision()

    /**
     * `ACTION_AUDIO_BECOMING_NOISY` fired: immediately auto-pause, clear any active USB mixer
     * preference, and refresh the active output device to [fallbackDevice] so the output pill
     * never lingers on the unplugged USB device during OEM HAL teardown delays.
     */
    data class BecomingNoisy(val fallbackDevice: RouteDeviceDescriptor?, val clearUsbDeviceId: Int?) : RouteFilterDecision()

    /**
     * A delayed `onAudioDevicesRemoved` callback matched an already-handled recent
     * `ACTION_AUDIO_BECOMING_NOISY` event (or removed an inactive USB sink). Perform mandatory
     * USB mixer cleanup and refresh device metadata WITHOUT firing a second delayed auto-pause.
     */
    data class CoalescedDisconnectCleanup(val removedUsbDeviceId: Int?, val refreshedDevice: RouteDeviceDescriptor?) : RouteFilterDecision()

    /** Genuine new output device connection (e.g. USB-C DAC/IEM plugged in): enforce Safe Mode + auto-pause. */
    data class DeviceConnected(val device: RouteDeviceDescriptor) : RouteFilterDecision()

    /** Genuine active USB disconnect without a preceding coalesced `BECOMING_NOISY`: auto-pause + mixer cleanup. */
    data class UsbDisconnected(val removedUsbDeviceId: Int, val fallbackDevice: RouteDeviceDescriptor?) : RouteFilterDecision()

    /** Genuine active non-USB output route change: auto-pause + Safe Mode. */
    data class OutputRouteChanged(val fallbackDevice: RouteDeviceDescriptor?) : RouteFilterDecision()
}

/**
 * Pure-Kotlin filter, deduplicator, and `ACTION_AUDIO_BECOMING_NOISY` <-> `onAudioDevicesRemoved`
 * coalescer for Android audio route callbacks (zero `android.*` imports).
 *
 * Guarantees:
 * 1. Strictly ignores non-sink (`!isSink`) endpoints such as USB headset microphones.
 * 2. Ignores duplicate/redundant callbacks when the active output device ID did not actually change.
 * 3. Immediately refreshes active output device metadata on `ACTION_AUDIO_BECOMING_NOISY` and
 *    temporarily suppresses the dying external device ID during OEM HAL teardown delays (e.g., 4 s
 *    on HyperOS) so first-tap Play recovery never binds a fresh `AudioTrack` to an unplugged USB DAC.
 * 4. Coalesces a delayed `onAudioDevicesRemoved` for the same disconnect within
 *    [coalesceWindowMs] into metadata/mixer cleanup only — NEVER firing a second auto-pause after
 *    the user already resumed playback.
 * 5. Always preserves mandatory hearing-safety auto-pause and Safe Mode reset on genuine USB
 *    connect, genuine USB disconnect, `ACTION_AUDIO_BECOMING_NOISY`, and active route changes.
 */
class AudioRouteEventFilter(private val coalesceWindowMs: Long = DEFAULT_NOISY_COALESCE_WINDOW_MS) {

    private data class PendingNoisyDisconnect(val timestampMs: Long, val previousDeviceId: Int?, val previousWasUsb: Boolean)

    private var activeDevice: RouteDeviceDescriptor? = null
    private var initialCallbackPending: Boolean = true
    private var pendingNoisyDisconnect: PendingNoisyDisconnect? = null

    @Synchronized
    fun currentActiveDevice(): RouteDeviceDescriptor? = activeDevice

    @Synchronized
    fun initialize(initialDevice: RouteDeviceDescriptor?) {
        activeDevice = initialDevice?.takeIf { it.isSink }
        pendingNoisyDisconnect = null
    }

    /**
     * Returns `true` when an `ACTION_AUDIO_BECOMING_NOISY` disconnect happened inside the coalesce
     * window and startup should avoid querying or binding to the disconnecting external sink.
     */
    @Synchronized
    fun isWithinNoisyCooldown(nowElapsedMs: Long): Boolean {
        val pending = pendingNoisyDisconnect ?: return false
        if (!isWithinCoalesceWindow(pending.timestampMs, nowElapsedMs)) {
            pendingNoisyDisconnect = null
            return false
        }
        return true
    }

    /**
     * Returns the external device ID that recently triggered `ACTION_AUDIO_BECOMING_NOISY` while
     * awaiting its delayed `onAudioDevicesRemoved` callback, or `null` if no suppression is active.
     */
    @Synchronized
    fun suppressedNoisyExternalDeviceId(nowElapsedMs: Long): Int? {
        if (!isWithinNoisyCooldown(nowElapsedMs)) {
            return null
        }
        return pendingNoisyDisconnect?.previousDeviceId
    }

    @Synchronized
    fun syncObservedActiveDevice(device: RouteDeviceDescriptor?) {
        if (device == null || device.isSink) {
            activeDevice = device
        }
    }

    @Synchronized
    fun onBecomingNoisy(nowElapsedMs: Long, resolvedFallbackDevice: RouteDeviceDescriptor?): RouteFilterDecision {
        val previous = activeDevice
        val previousExternalId = previous?.takeIf { it.isExternalOutput }?.id
        val clearUsbId = previous?.takeIf { it.isUsbOutput }?.id
        pendingNoisyDisconnect = PendingNoisyDisconnect(
            timestampMs = nowElapsedMs,
            previousDeviceId = previousExternalId,
            previousWasUsb = previous?.isUsbOutput == true,
        )
        val sanitizedFallback = resolvedFallbackDevice?.takeIf { it.isSink }
        activeDevice = sanitizedFallback
        return RouteFilterDecision.BecomingNoisy(
            fallbackDevice = sanitizedFallback,
            clearUsbDeviceId = clearUsbId,
        )
    }

    @Synchronized
    fun onDevicesAdded(addedDevices: List<RouteDeviceDescriptor>, preferredActiveAfterAdd: RouteDeviceDescriptor?): RouteFilterDecision {
        val addedSinks = addedDevices.filter { it.isSink }
        if (addedSinks.isEmpty()) return RouteFilterDecision.Ignore

        val usbAdded = addedSinks.firstOrNull { it.isUsbOutput }
        val preferredSink = preferredActiveAfterAdd?.takeIf { it.isSink }
        val chosen = usbAdded ?: preferredSink ?: addedSinks.first()

        if (initialCallbackPending) {
            initialCallbackPending = false
            activeDevice = chosen
            return RouteFilterDecision.SyncInitialDevice(chosen)
        }

        val previous = activeDevice
        val routeActuallyChanged = previous == null ||
            previous.id != chosen.id ||
            previous.isUsbOutput != chosen.isUsbOutput
        if (!routeActuallyChanged) {
            return RouteFilterDecision.Ignore
        }

        // A genuinely new active output sink arrived (e.g. USB-C IEM replugged): clear noisy suppression.
        pendingNoisyDisconnect = null
        activeDevice = chosen
        return RouteFilterDecision.DeviceConnected(chosen)
    }

    @Synchronized
    fun onDevicesRemoved(
        removedDevices: List<RouteDeviceDescriptor>,
        preferredActiveAfterRemove: RouteDeviceDescriptor?,
        nowElapsedMs: Long,
    ): RouteFilterDecision {
        val removedSinks = removedDevices.filter { it.isSink }
        if (removedSinks.isEmpty()) return RouteFilterDecision.Ignore

        val removedUsb = removedSinks.firstOrNull { it.isUsbOutput }
        val fallbackSink = preferredActiveAfterRemove?.takeIf { it.isSink }

        if (matchesRecentNoisyDisconnect(removedSinks, removedUsb, nowElapsedMs)) {
            pendingNoisyDisconnect = null
            activeDevice = fallbackSink
            return RouteFilterDecision.CoalescedDisconnectCleanup(
                removedUsbDeviceId = removedUsb?.id,
                refreshedDevice = fallbackSink,
            )
        }

        val previous = activeDevice
        val removedActive = previous != null && removedSinks.any { it.id == previous.id }
        val routeChanged = previous?.id != fallbackSink?.id
        val removedActiveUsb = removedUsb != null && (removedActive || previous == null || routeChanged)

        if (!removedActive && !routeChanged && !removedActiveUsb) {
            return if (removedUsb != null) {
                RouteFilterDecision.CoalescedDisconnectCleanup(
                    removedUsbDeviceId = removedUsb.id,
                    refreshedDevice = fallbackSink,
                )
            } else {
                RouteFilterDecision.Ignore
            }
        }

        activeDevice = fallbackSink
        return if (removedUsb != null) {
            RouteFilterDecision.UsbDisconnected(
                removedUsbDeviceId = removedUsb.id,
                fallbackDevice = fallbackSink,
            )
        } else {
            RouteFilterDecision.OutputRouteChanged(fallbackDevice = fallbackSink)
        }
    }

    private fun matchesRecentNoisyDisconnect(
        removedSinks: List<RouteDeviceDescriptor>,
        removedUsb: RouteDeviceDescriptor?,
        nowElapsedMs: Long,
    ): Boolean {
        val pending = pendingNoisyDisconnect ?: return false
        if (!isWithinCoalesceWindow(pending.timestampMs, nowElapsedMs)) {
            pendingNoisyDisconnect = null
            return false
        }
        val matchesById = pending.previousDeviceId != null &&
            removedSinks.any { it.id == pending.previousDeviceId }
        val matchesByUsb = pending.previousWasUsb && removedUsb != null
        val matchesGenericExternal = pending.previousDeviceId == null && removedSinks.any { it.isExternalOutput }
        return matchesById || matchesByUsb || matchesGenericExternal
    }

    private fun isWithinCoalesceWindow(eventMs: Long, nowMs: Long): Boolean {
        val delta = nowMs - eventMs
        return delta in 0L..coalesceWindowMs
    }

    companion object {
        /**
         * Window matching staggered OEM USB HAL teardown delays (observed ~4 s on HyperOS between
         * `ACTION_AUDIO_BECOMING_NOISY` and `AudioDeviceCallback.onAudioDevicesRemoved`).
         */
        const val DEFAULT_NOISY_COALESCE_WINDOW_MS: Long = 8_000L
    }
}
