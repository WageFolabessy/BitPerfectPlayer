package com.cangkir.bitperfectplayer.audio

/**
 * Pure-Kotlin snapshot of an Android audio endpoint passed from `AudioDeviceCallback` or
 * `AudioManager.getDevices(...)` so route filtering, deduplication, early `USB_CLASS_AUDIO`
 * broadcast detection, and `ACTION_AUDIO_BECOMING_NOISY` coalescing remain 100% deterministic and
 * JVM unit-testable (zero `android.*` imports).
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
     * A delayed `onAudioDevicesRemoved`, `ACTION_AUDIO_BECOMING_NOISY`, or
     * `ACTION_USB_DEVICE_DETACHED` callback matched an already-handled recent disconnect event (or
     * removed an inactive USB sink). Perform mandatory USB mixer cleanup and refresh device
     * metadata WITHOUT firing a second delayed auto-pause.
     */
    data class CoalescedDisconnectCleanup(val removedUsbDeviceId: Int?, val refreshedDevice: RouteDeviceDescriptor?) : RouteFilterDecision()

    /**
     * A delayed `AudioDeviceCallback.onAudioDevicesAdded` callback arrived after an early
     * `UsbManager.ACTION_USB_DEVICE_ATTACHED` (`USB_CLASS_AUDIO`) broadcast already triggered
     * hearing-safety auto-pause and Safe Mode reset. Upgrade provisional USB device metadata to
     * the real [device] WITHOUT firing a second delayed auto-pause.
     */
    data class CoalescedUsbConnectMetadataSync(val device: RouteDeviceDescriptor) : RouteFilterDecision()

    /** Genuine new output device connection (e.g. USB-C DAC/IEM plugged in): enforce Safe Mode + auto-pause. */
    data class DeviceConnected(val device: RouteDeviceDescriptor) : RouteFilterDecision()

    /** Genuine active USB disconnect without a preceding coalesced disconnect event: auto-pause + mixer cleanup. */
    data class UsbDisconnected(val removedUsbDeviceId: Int, val fallbackDevice: RouteDeviceDescriptor?) : RouteFilterDecision()

    /** Genuine active non-USB output route change: auto-pause + Safe Mode. */
    data class OutputRouteChanged(val fallbackDevice: RouteDeviceDescriptor?) : RouteFilterDecision()
}

/**
 * Pure-Kotlin filter, deduplicator, and multi-source route event coalescer unifying early
 * `UsbManager.ACTION_USB_DEVICE_ATTACHED` / `ACTION_USB_DEVICE_DETACHED` (`USB_CLASS_AUDIO`),
 * `ACTION_AUDIO_BECOMING_NOISY`, and `AudioDeviceCallback.onAudioDevicesAdded` / `onAudioDevicesRemoved`
 * (zero `android.*` imports).
 *
 * Guarantees:
 * 1. Strictly ignores non-sink (`!isSink`) audio endpoints (such as USB headset microphones) and
 *    non-audio USB accessories (`!hasUsbAudioInterface`).
 * 2. Ignores duplicate/redundant callbacks when the active output device ID did not actually change.
 * 3. Immediately triggers hearing-safety auto-pause + Safe Mode reset on the earliest signal of a
 *    USB-C DAC/IEM attach or detach (`ACTION_USB_DEVICE_ATTACHED` / `DETACHED`, `BECOMING_NOISY`,
 *    or `AudioDeviceCallback`) without waiting 3–4 seconds for OEM HAL enumeration/teardown.
 * 4. Coalesces any subsequent delayed callbacks for the same connect or disconnect within
 *    [coalesceWindowMs] into metadata/mixer cleanup only — NEVER firing a second auto-pause after
 *    the user already resumed playback.
 */
class AudioRouteEventFilter(private val coalesceWindowMs: Long = DEFAULT_NOISY_COALESCE_WINDOW_MS) {

    private data class PendingNoisyDisconnect(val timestampMs: Long, val previousDeviceId: Int?, val previousWasUsb: Boolean)

    private var activeDevice: RouteDeviceDescriptor? = null
    private var initialCallbackPending: Boolean = true
    private var pendingNoisyDisconnect: PendingNoisyDisconnect? = null
    private var pendingEarlyUsbAttachTimestampMs: Long? = null
    private var lastConfirmedUsbConnectTimestampMs: Long? = null

    @Synchronized
    fun currentActiveDevice(): RouteDeviceDescriptor? = activeDevice

    @Synchronized
    fun initialize(initialDevice: RouteDeviceDescriptor?) {
        activeDevice = initialDevice?.takeIf { it.isSink }
        pendingNoisyDisconnect = null
        pendingEarlyUsbAttachTimestampMs = null
        lastConfirmedUsbConnectTimestampMs = null
    }

    /**
     * Returns `true` when an `ACTION_AUDIO_BECOMING_NOISY` or early USB disconnect happened inside
     * the coalesce window and startup should avoid querying or binding to the disconnecting sink.
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
     * Returns the external device ID that recently triggered a disconnect while awaiting its
     * delayed `onAudioDevicesRemoved` callback, or `null` if no suppression is active.
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

    /**
     * Handles an early `UsbManager.ACTION_USB_DEVICE_ATTACHED` broadcast for a verified
     * `USB_CLASS_AUDIO` device before `AudioPolicyService` finishes enumerating the `AudioDeviceInfo`
     * sink. Fires `DeviceConnected` immediately once and coalesces the delayed `onDevicesAdded`.
     */
    @Synchronized
    fun onUsbAudioAttachedEarly(usbDeviceName: String, nowElapsedMs: Long): RouteFilterDecision {
        val current = activeDevice
        val alreadyActiveUsb = current?.isUsbOutput == true && !isWithinNoisyCooldown(nowElapsedMs)
        val recentConfirmedUsb = lastConfirmedUsbConnectTimestampMs?.let {
            isWithinCoalesceWindow(it, nowElapsedMs)
        } == true
        val recentEarlyUsb = pendingEarlyUsbAttachTimestampMs?.let {
            isWithinCoalesceWindow(it, nowElapsedMs)
        } == true
        if (alreadyActiveUsb || recentConfirmedUsb || recentEarlyUsb) {
            return RouteFilterDecision.Ignore
        }

        initialCallbackPending = false
        pendingNoisyDisconnect = null
        pendingEarlyUsbAttachTimestampMs = nowElapsedMs
        val provisional = RouteDeviceDescriptor(
            id = EARLY_USB_PROVISIONAL_DEVICE_ID,
            name = usbDeviceName.trim().ifEmpty { DEFAULT_EARLY_USB_DEVICE_NAME },
            isSink = true,
            isUsbOutput = true,
            isExternalOutput = true,
        )
        activeDevice = provisional
        return RouteFilterDecision.DeviceConnected(provisional)
    }

    /**
     * Handles an early `UsbManager.ACTION_USB_DEVICE_DETACHED` broadcast for a verified
     * `USB_CLASS_AUDIO` device. Immediately triggers `UsbDisconnected` if USB audio was active, or
     * coalesces into `CoalescedDisconnectCleanup` if `BECOMING_NOISY` / `onDevicesRemoved` already
     * handled the disconnect.
     */
    @Synchronized
    fun onUsbAudioDetachedEarly(nowElapsedMs: Long, resolvedFallbackDevice: RouteDeviceDescriptor?): RouteFilterDecision {
        val sanitizedFallback = resolvedFallbackDevice?.takeIf { it.isSink && !it.isUsbOutput }
        val previous = activeDevice
        if (isWithinNoisyCooldown(nowElapsedMs)) {
            val clearId = pendingNoisyDisconnect?.previousDeviceId
                ?: previous?.takeIf { it.isUsbOutput && it.id > 0 }?.id
            activeDevice = sanitizedFallback
            return RouteFilterDecision.CoalescedDisconnectCleanup(
                removedUsbDeviceId = clearId,
                refreshedDevice = sanitizedFallback,
            )
        }

        val hadEarlyAttach = pendingEarlyUsbAttachTimestampMs != null
        pendingEarlyUsbAttachTimestampMs = null
        lastConfirmedUsbConnectTimestampMs = null
        val wasUsbActive = previous?.isUsbOutput == true || hadEarlyAttach
        if (!wasUsbActive) {
            return RouteFilterDecision.Ignore
        }

        val realUsbId = previous?.takeIf { it.isUsbOutput && it.id > 0 }?.id
        pendingNoisyDisconnect = PendingNoisyDisconnect(
            timestampMs = nowElapsedMs,
            previousDeviceId = realUsbId,
            previousWasUsb = true,
        )
        activeDevice = sanitizedFallback
        return RouteFilterDecision.UsbDisconnected(
            removedUsbDeviceId = realUsbId ?: EARLY_USB_PROVISIONAL_DEVICE_ID,
            fallbackDevice = sanitizedFallback,
        )
    }

    @Synchronized
    fun onBecomingNoisy(nowElapsedMs: Long, resolvedFallbackDevice: RouteDeviceDescriptor?): RouteFilterDecision {
        val sanitizedFallback = resolvedFallbackDevice?.takeIf { it.isSink }
        val previous = activeDevice
        // If an early USB detach or prior noisy/remove callback already transitioned away from the
        // external output within the coalesce window, refresh metadata without a second auto-pause.
        if (isWithinNoisyCooldown(nowElapsedMs) && previous?.isExternalOutput != true) {
            activeDevice = sanitizedFallback
            return RouteFilterDecision.CoalescedDisconnectCleanup(
                removedUsbDeviceId = pendingNoisyDisconnect?.previousDeviceId,
                refreshedDevice = sanitizedFallback,
            )
        }

        val previousExternalId = previous?.takeIf { it.isExternalOutput && it.id > 0 }?.id
        val clearUsbId = previous?.takeIf { it.isUsbOutput && it.id > 0 }?.id
        val wasUsb = previous?.isUsbOutput == true || pendingEarlyUsbAttachTimestampMs != null
        pendingEarlyUsbAttachTimestampMs = null
        lastConfirmedUsbConnectTimestampMs = null
        pendingNoisyDisconnect = PendingNoisyDisconnect(
            timestampMs = nowElapsedMs,
            previousDeviceId = previousExternalId,
            previousWasUsb = wasUsb,
        )
        activeDevice = sanitizedFallback
        return RouteFilterDecision.BecomingNoisy(
            fallbackDevice = sanitizedFallback,
            clearUsbDeviceId = clearUsbId,
        )
    }

    @Synchronized
    fun onDevicesAdded(
        addedDevices: List<RouteDeviceDescriptor>,
        preferredActiveAfterAdd: RouteDeviceDescriptor?,
        nowElapsedMs: Long = 0L,
    ): RouteFilterDecision {
        val addedSinks = addedDevices.filter { it.isSink }
        if (addedSinks.isEmpty()) return RouteFilterDecision.Ignore

        val usbAdded = addedSinks.firstOrNull { it.isUsbOutput }
        val preferredSink = preferredActiveAfterAdd?.takeIf { it.isSink }
        val chosen = usbAdded ?: preferredSink ?: addedSinks.first()

        if (initialCallbackPending) {
            initialCallbackPending = false
            activeDevice = chosen
            if (chosen.isUsbOutput) {
                lastConfirmedUsbConnectTimestampMs = nowElapsedMs
            }
            return RouteFilterDecision.SyncInitialDevice(chosen)
        }

        val earlyAttachMs = pendingEarlyUsbAttachTimestampMs
        if (chosen.isUsbOutput && earlyAttachMs != null) {
            pendingEarlyUsbAttachTimestampMs = null
            if (isWithinCoalesceWindow(earlyAttachMs, nowElapsedMs)) {
                pendingNoisyDisconnect = null
                lastConfirmedUsbConnectTimestampMs = nowElapsedMs
                activeDevice = chosen
                return RouteFilterDecision.CoalescedUsbConnectMetadataSync(chosen)
            }
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
        pendingEarlyUsbAttachTimestampMs = null
        if (chosen.isUsbOutput) {
            lastConfirmedUsbConnectTimestampMs = nowElapsedMs
        }
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

        if (removedUsb != null) {
            pendingEarlyUsbAttachTimestampMs = null
            lastConfirmedUsbConnectTimestampMs = null
        }

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

        // Record a short disconnect cooldown so a trailing ACTION_USB_DEVICE_DETACHED or
        // BECOMING_NOISY arriving right after onDevicesRemoved coalesces instead of double-pausing.
        pendingNoisyDisconnect = PendingNoisyDisconnect(
            timestampMs = nowElapsedMs,
            previousDeviceId = removedUsb?.id ?: previous?.takeIf { it.isExternalOutput }?.id,
            previousWasUsb = removedUsb != null || previous?.isUsbOutput == true,
        )
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
         * Window matching staggered OEM USB HAL teardown/bringup delays (observed ~3–4 s on HyperOS
         * between `ACTION_USB_DEVICE_ATTACHED`/`DETACHED`, `ACTION_AUDIO_BECOMING_NOISY`, and
         * `AudioDeviceCallback`).
         */
        const val DEFAULT_NOISY_COALESCE_WINDOW_MS: Long = 8_000L

        /** Android `UsbConstants.USB_CLASS_AUDIO` (`0x01`). */
        const val USB_CLASS_AUDIO_INTERFACE: Int = 1

        /** Synthetic provisional descriptor ID used between early USB attach and AudioDeviceInfo enumeration. */
        const val EARLY_USB_PROVISIONAL_DEVICE_ID: Int = -1001

        const val DEFAULT_EARLY_USB_DEVICE_NAME: String = "USB Audio Device"

        /**
         * Pure-Kotlin check returning `true` strictly when at least one USB interface reports
         * `interfaceClass == USB_CLASS_AUDIO` (`1`), ignoring USB chargers, hubs, storage, HID, etc.
         */
        fun hasUsbAudioInterface(interfaceClasses: IntArray): Boolean = interfaceClasses.any { it == USB_CLASS_AUDIO_INTERFACE }
    }
}
