package com.cangkir.bitperfectplayer.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioRouteEventFilterTest {

    private val internalSpeaker = RouteDeviceDescriptor(
        id = 1,
        name = "Internal Speaker",
        isSink = true,
        isUsbOutput = false,
        isExternalOutput = false,
    )

    private val usbIemSink = RouteDeviceDescriptor(
        id = 42,
        name = "USB-Audio - Audio hs 2.0 headset",
        isSink = true,
        isUsbOutput = true,
        isExternalOutput = true,
    )

    private val usbIemMicSource = RouteDeviceDescriptor(
        id = 43,
        name = "USB-Audio - Audio hs 2.0 headset",
        isSink = false,
        isUsbOutput = false,
        isExternalOutput = false,
    )

    private val usbIemRepluggedSink = RouteDeviceDescriptor(
        id = 44,
        name = "USB-Audio - Audio hs 2.0 headset",
        isSink = true,
        isUsbOutput = true,
        isExternalOutput = true,
    )

    private val virtualSubmixSink = RouteDeviceDescriptor(
        id = 99,
        name = "Remote Submix",
        isSink = true,
        isUsbOutput = false,
        isExternalOutput = false,
    )

    @Test
    fun nonSinkEndpoints_suchAsUsbHeadsetMicrophones_areStrictlyIgnoredOnAddAndRemove() {
        val filter = AudioRouteEventFilter()
        filter.initialize(usbIemSink)
        // Consume initial callback sync
        assertEquals(
            RouteFilterDecision.SyncInitialDevice(usbIemSink),
            filter.onDevicesAdded(listOf(usbIemSink, usbIemMicSource), preferredActiveAfterAdd = usbIemSink),
        )

        assertEquals(
            RouteFilterDecision.Ignore,
            filter.onDevicesAdded(listOf(usbIemMicSource), preferredActiveAfterAdd = usbIemSink),
        )
        assertEquals(
            RouteFilterDecision.Ignore,
            filter.onDevicesRemoved(
                removedDevices = listOf(usbIemMicSource),
                preferredActiveAfterRemove = usbIemSink,
                nowElapsedMs = 2_000L,
            ),
        )
        assertEquals(usbIemSink, filter.currentActiveDevice())
    }

    @Test
    fun duplicateCallbacksWithUnchangedActiveOutputId_areIgnored() {
        val filter = AudioRouteEventFilter()
        filter.initialize(usbIemSink)
        filter.onDevicesAdded(listOf(usbIemSink), preferredActiveAfterAdd = usbIemSink)

        // Repeated callback reporting the already-active USB device must NOT re-trigger auto-pause
        assertEquals(
            RouteFilterDecision.Ignore,
            filter.onDevicesAdded(listOf(usbIemSink), preferredActiveAfterAdd = usbIemSink),
        )

        // Adding a virtual non-active sink (e.g. screen recorder submix) while USB remains active is ignored
        assertEquals(
            RouteFilterDecision.Ignore,
            filter.onDevicesAdded(listOf(virtualSubmixSink), preferredActiveAfterAdd = usbIemSink),
        )
    }

    @Test
    fun staggeredBecomingNoisyThenDelayedUsbRemoved4sLater_coalescesWithoutSecondAutoPause() {
        val filter = AudioRouteEventFilter(coalesceWindowMs = 8_000L)
        filter.initialize(usbIemSink)
        filter.onDevicesAdded(listOf(usbIemSink), preferredActiveAfterAdd = usbIemSink)

        // 05:48 — POCO F4 fires ACTION_AUDIO_BECOMING_NOISY immediately when USB-C IEM is unplugged
        val noisyDecision = filter.onBecomingNoisy(
            nowElapsedMs = 5_000L,
            resolvedFallbackDevice = internalSpeaker,
        )
        assertTrue(noisyDecision is RouteFilterDecision.BecomingNoisy)
        noisyDecision as RouteFilterDecision.BecomingNoisy
        assertEquals(internalSpeaker, noisyDecision.fallbackDevice)
        assertEquals(usbIemSink.id, noisyDecision.clearUsbDeviceId)
        // Output pill must immediately reflect Internal Speaker and suppress dying USB ID 42 during HAL teardown
        assertEquals(internalSpeaker, filter.currentActiveDevice())
        assertEquals(usbIemSink.id, filter.suppressedNoisyExternalDeviceId(nowElapsedMs = 6_000L))

        // 05:52 (4 seconds later, after user already tapped Play on Speaker) — delayed onAudioDevicesRemoved arrives
        val delayedRemoveDecision = filter.onDevicesRemoved(
            removedDevices = listOf(usbIemSink, usbIemMicSource),
            preferredActiveAfterRemove = internalSpeaker,
            nowElapsedMs = 9_000L,
        )
        assertEquals(
            RouteFilterDecision.CoalescedDisconnectCleanup(
                removedUsbDeviceId = usbIemSink.id,
                refreshedDevice = internalSpeaker,
            ),
            delayedRemoveDecision,
        )
        assertNull(filter.suppressedNoisyExternalDeviceId(nowElapsedMs = 9_500L))
        assertEquals(internalSpeaker, filter.currentActiveDevice())
    }

    @Test
    fun usbDisconnectedWithoutPriorBecomingNoisy_orOutsideCoalesceWindow_emitsUsbDisconnected() {
        val filter = AudioRouteEventFilter(coalesceWindowMs = 8_000L)
        filter.initialize(usbIemSink)
        filter.onDevicesAdded(listOf(usbIemSink), preferredActiveAfterAdd = usbIemSink)

        // Unplugging USB while paused (no ACTION_AUDIO_BECOMING_NOISY broadcast) must still emit UsbDisconnected
        val removeDecision = filter.onDevicesRemoved(
            removedDevices = listOf(usbIemSink),
            preferredActiveAfterRemove = internalSpeaker,
            nowElapsedMs = 10_000L,
        )
        assertEquals(
            RouteFilterDecision.UsbDisconnected(
                removedUsbDeviceId = usbIemSink.id,
                fallbackDevice = internalSpeaker,
            ),
            removeDecision,
        )
        assertEquals(internalSpeaker, filter.currentActiveDevice())
    }

    @Test
    fun repluggingUsbClearsNoisySuppression_andStaleOldUsbRemovalDoesNotKillNewUsbSession() {
        val filter = AudioRouteEventFilter(coalesceWindowMs = 8_000L)
        filter.initialize(usbIemSink)
        filter.onDevicesAdded(listOf(usbIemSink), preferredActiveAfterAdd = usbIemSink)

        // Unplug -> BECOMING_NOISY suppresses old ID 42
        filter.onBecomingNoisy(nowElapsedMs = 1_000L, resolvedFallbackDevice = internalSpeaker)
        assertEquals(usbIemSink.id, filter.suppressedNoisyExternalDeviceId(nowElapsedMs = 2_000L))

        // Rapid replug -> new USB sink ID 44 arrives and clears noisy suppression
        val replugDecision = filter.onDevicesAdded(
            addedDevices = listOf(usbIemRepluggedSink, usbIemMicSource),
            preferredActiveAfterAdd = usbIemRepluggedSink,
        )
        assertEquals(RouteFilterDecision.DeviceConnected(usbIemRepluggedSink), replugDecision)
        assertNull(filter.suppressedNoisyExternalDeviceId(nowElapsedMs = 2_500L))
        assertEquals(usbIemRepluggedSink, filter.currentActiveDevice())

        // Delayed removal of stale old ID 42 arrives while ID 44 is active -> cleanup only, never kill ID 44
        val staleRemoveDecision = filter.onDevicesRemoved(
            removedDevices = listOf(usbIemSink),
            preferredActiveAfterRemove = usbIemRepluggedSink,
            nowElapsedMs = 4_000L,
        )
        assertEquals(
            RouteFilterDecision.CoalescedDisconnectCleanup(
                removedUsbDeviceId = usbIemSink.id,
                refreshedDevice = usbIemRepluggedSink,
            ),
            staleRemoveDecision,
        )
        assertEquals(usbIemRepluggedSink, filter.currentActiveDevice())
    }

    @Test
    fun noisySuppressionExpiresCleanlyAfterCoalesceWindow() {
        val filter = AudioRouteEventFilter(coalesceWindowMs = 8_000L)
        filter.initialize(usbIemSink)
        filter.onDevicesAdded(listOf(usbIemSink), preferredActiveAfterAdd = usbIemSink)

        filter.onBecomingNoisy(nowElapsedMs = 1_000L, resolvedFallbackDevice = internalSpeaker)
        assertEquals(usbIemSink.id, filter.suppressedNoisyExternalDeviceId(nowElapsedMs = 9_000L))
        assertNull(filter.suppressedNoisyExternalDeviceId(nowElapsedMs = 9_001L))
    }

    @Test
    fun hasUsbAudioInterface_matchesOnlyUsbClassAudioDevices() {
        assertTrue(AudioRouteEventFilter.hasUsbAudioInterface(intArrayOf(1)))
        assertTrue(AudioRouteEventFilter.hasUsbAudioInterface(intArrayOf(3, 1, 255)))
        assertEquals(false, AudioRouteEventFilter.hasUsbAudioInterface(intArrayOf()))
        assertEquals(false, AudioRouteEventFilter.hasUsbAudioInterface(intArrayOf(8))) // Mass storage
        assertEquals(false, AudioRouteEventFilter.hasUsbAudioInterface(intArrayOf(3, 10))) // HID + CDC
    }

    @Test
    fun earlyUsbAudioAttach_triggersSingleDeviceConnected_andCoalescesDelayedOnAudioDevicesAdded() {
        val filter = AudioRouteEventFilter(coalesceWindowMs = 8_000L)
        filter.initialize(internalSpeaker)
        filter.onDevicesAdded(listOf(internalSpeaker), preferredActiveAfterAdd = internalSpeaker)

        // T+0ms: UsbManager.ACTION_USB_DEVICE_ATTACHED arrives before AudioPolicyService enumerates sink
        val earlyDecision = filter.onUsbAudioAttachedEarly(
            usbDeviceName = "KZ EDX Pro 2 USB-C",
            nowElapsedMs = 1_000L,
        )
        assertTrue(earlyDecision is RouteFilterDecision.DeviceConnected)
        earlyDecision as RouteFilterDecision.DeviceConnected
        assertEquals(AudioRouteEventFilter.EARLY_USB_PROVISIONAL_DEVICE_ID, earlyDecision.device.id)
        assertEquals("KZ EDX Pro 2 USB-C", earlyDecision.device.name)
        assertTrue(earlyDecision.device.isUsbOutput)

        // Duplicate early attach broadcast within coalesce window is ignored
        assertEquals(
            RouteFilterDecision.Ignore,
            filter.onUsbAudioAttachedEarly(usbDeviceName = "KZ EDX Pro 2 USB-C", nowElapsedMs = 1_100L),
        )

        // T+3,200ms: Delayed AudioDeviceCallback.onAudioDevicesAdded arrives with real AudioDeviceInfo ID 42
        val delayedAddDecision = filter.onDevicesAdded(
            addedDevices = listOf(usbIemSink, usbIemMicSource),
            preferredActiveAfterAdd = usbIemSink,
            nowElapsedMs = 4_200L,
        )
        assertEquals(
            RouteFilterDecision.CoalescedUsbConnectMetadataSync(device = usbIemSink),
            delayedAddDecision,
        )
        assertEquals(usbIemSink, filter.currentActiveDevice())
    }

    @Test
    fun earlyUsbAudioDetach_triggersImmediateDisconnect_andCoalescesDelayedNoisyAndDevicesRemoved() {
        val filter = AudioRouteEventFilter(coalesceWindowMs = 8_000L)
        filter.initialize(usbIemSink)
        filter.onDevicesAdded(listOf(usbIemSink), preferredActiveAfterAdd = usbIemSink)

        // T+0ms: UsbManager.ACTION_USB_DEVICE_DETACHED fires immediately on physical unplug
        val earlyDetach = filter.onUsbAudioDetachedEarly(
            nowElapsedMs = 2_000L,
            resolvedFallbackDevice = internalSpeaker,
        )
        assertEquals(
            RouteFilterDecision.UsbDisconnected(
                removedUsbDeviceId = usbIemSink.id,
                fallbackDevice = internalSpeaker,
            ),
            earlyDetach,
        )
        assertEquals(internalSpeaker, filter.currentActiveDevice())
        assertEquals(usbIemSink.id, filter.suppressedNoisyExternalDeviceId(nowElapsedMs = 2_500L))

        // T+400ms: Delayed ACTION_AUDIO_BECOMING_NOISY arrives -> already handled by early detach, coalesced cleanup only
        assertEquals(
            RouteFilterDecision.CoalescedDisconnectCleanup(
                removedUsbDeviceId = usbIemSink.id,
                refreshedDevice = internalSpeaker,
            ),
            filter.onBecomingNoisy(nowElapsedMs = 2_400L, resolvedFallbackDevice = internalSpeaker),
        )

        // T+3,800ms: Delayed AudioDeviceCallback.onAudioDevicesRemoved arrives -> coalesced cleanup only
        val delayedRemove = filter.onDevicesRemoved(
            removedDevices = listOf(usbIemSink, usbIemMicSource),
            preferredActiveAfterRemove = internalSpeaker,
            nowElapsedMs = 5_800L,
        )
        assertEquals(
            RouteFilterDecision.CoalescedDisconnectCleanup(
                removedUsbDeviceId = usbIemSink.id,
                refreshedDevice = internalSpeaker,
            ),
            delayedRemove,
        )
        assertNull(filter.suppressedNoisyExternalDeviceId(nowElapsedMs = 6_000L))
    }
}
