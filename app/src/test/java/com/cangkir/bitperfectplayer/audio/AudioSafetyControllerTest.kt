package com.cangkir.bitperfectplayer.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSafetyControllerTest {

    @Test
    fun initializesInSafeVolumeMode_withHardwareVolumeUnverified() {
        val controller = AudioSafetyController()
        val state = controller.state()

        assertEquals(AudioOutputMode.SAFE_VOLUME_MODE, state.mode)
        assertFalse(state.isPendingSafetyWarning)
        assertFalse(state.isHardwareVolumeVerified)
        assertFalse(state.isFixedVolumeRisk)
    }

    @Test
    fun requestBitPerfect_whenHardwareVolumeUnverified_requiresSafetyWarningFirst() {
        val controller = AudioSafetyController()

        val cmd = controller.requestBitPerfectMode()
        assertEquals(SafetyEventType.REQUIRES_SAFETY_WARNING, cmd.type)
        assertNotNull(cmd.warningMessage)
        assertEquals(AudioOutputMode.SAFE_VOLUME_MODE, controller.state().mode)
        assertTrue(controller.state().isPendingSafetyWarning)

        // Dismissing warning keeps Safe Mode
        val dismissCmd = controller.dismissSafetyWarning()
        assertEquals(SafetyEventType.NONE, dismissCmd.type)
        assertEquals(AudioOutputMode.SAFE_VOLUME_MODE, controller.state().mode)
        assertFalse(controller.state().isPendingSafetyWarning)

        // Requesting again and confirming activates Bit-Perfect Mode with fixed-volume risk flag
        controller.requestBitPerfectMode()
        val confirmCmd = controller.confirmBitPerfectWarning()
        assertEquals(SafetyEventType.MODE_CHANGED_TO_BIT_PERFECT, confirmCmd.type)
        assertEquals(AudioOutputMode.BIT_PERFECT_OPT_IN, controller.state().mode)
        assertFalse(controller.state().isPendingSafetyWarning)
        assertTrue(controller.state().isFixedVolumeRisk)
    }

    @Test
    fun confirmWithoutPriorRequest_cannotBypassWarningWhenUnverified() {
        val controller = AudioSafetyController()

        val cmd = controller.confirmBitPerfectWarning()
        assertEquals(SafetyEventType.REQUIRES_SAFETY_WARNING, cmd.type)
        assertEquals(AudioOutputMode.SAFE_VOLUME_MODE, controller.state().mode)
        assertTrue(controller.state().isPendingSafetyWarning)
    }

    @Test
    fun requestBitPerfect_whenHardwareVolumeVerified_activatesDirectly() {
        val controller = AudioSafetyController()
        controller.updateHardwareVolumeVerification(true)

        val cmd = controller.requestBitPerfectMode()
        assertEquals(SafetyEventType.MODE_CHANGED_TO_BIT_PERFECT, cmd.type)
        assertEquals(AudioOutputMode.BIT_PERFECT_OPT_IN, controller.state().mode)
        assertFalse(controller.state().isFixedVolumeRisk)
    }

    @Test
    fun onAudioDeviceConnected_neverAutoEnablesBitPerfect_resetsToSafeAndAutoPauses() {
        val controller = AudioSafetyController()
        controller.requestBitPerfectMode()
        controller.confirmBitPerfectWarning()
        assertEquals(AudioOutputMode.BIT_PERFECT_OPT_IN, controller.state().mode)

        // Plugging in a USB-C DAC/IEM must NEVER stay in or auto-enable Bit-Perfect Mode
        val cmd = controller.onAudioDeviceConnected(
            deviceName = "KZ EDX Pro 2 USB-C",
            isUsbAudio = true,
            isHardwareVolumeVerified = false,
        )
        assertEquals(SafetyEventType.AUTO_PAUSE, cmd.type)
        assertEquals(AutoPauseReason.USB_CONNECTED_ROUTE_CHANGE, cmd.autoPauseReason)
        assertTrue(cmd.requiresClearPreferredMixerAttributes)

        val state = controller.state()
        assertEquals(AudioOutputMode.SAFE_VOLUME_MODE, state.mode)
        assertTrue(state.isUsbAudioConnected)
        assertEquals("KZ EDX Pro 2 USB-C", state.activeDeviceName)
        assertFalse(state.isPendingSafetyWarning)
    }

    @Test
    fun onBecomingNoisy_immediatelyEmitsAutoPauseAndResetsSafeMode() {
        val controller = AudioSafetyController()
        controller.requestBitPerfectMode()
        controller.confirmBitPerfectWarning()

        val cmd = controller.onBecomingNoisy()
        assertEquals(SafetyEventType.AUTO_PAUSE, cmd.type)
        assertEquals(AutoPauseReason.BECOMING_NOISY, cmd.autoPauseReason)
        assertTrue(cmd.requiresClearPreferredMixerAttributes)
        assertEquals(AudioOutputMode.SAFE_VOLUME_MODE, controller.state().mode)
    }

    @Test
    fun onUsbDisconnected_immediatelyEmitsAutoPauseAndClearsPreferredMixer() {
        val controller = AudioSafetyController()
        controller.onAudioDeviceConnected("USB DAC", isUsbAudio = true)
        controller.requestBitPerfectMode()
        controller.confirmBitPerfectWarning()

        val cmd = controller.onUsbDisconnected("Internal Speaker")
        assertEquals(SafetyEventType.AUTO_PAUSE, cmd.type)
        assertEquals(AutoPauseReason.USB_DISCONNECTED, cmd.autoPauseReason)
        assertTrue(cmd.requiresClearPreferredMixerAttributes)
        assertEquals(AudioOutputMode.SAFE_VOLUME_MODE, controller.state().mode)
        assertFalse(controller.state().isUsbAudioConnected)
        assertEquals("Internal Speaker", controller.state().activeDeviceName)
    }

    @Test
    fun onOutputRouteChanged_andSwitchToSafeMode_enforceSafetyAndMixerCleanup() {
        val controller = AudioSafetyController()
        controller.onAudioDeviceConnected("USB DAC", isUsbAudio = true)
        controller.requestBitPerfectMode()
        controller.confirmBitPerfectWarning()

        val safeCmd = controller.switchToSafeMode()
        assertEquals(SafetyEventType.MODE_CHANGED_TO_SAFE, safeCmd.type)
        assertTrue(safeCmd.requiresClearPreferredMixerAttributes)
        assertEquals(AudioOutputMode.SAFE_VOLUME_MODE, controller.state().mode)

        // Route change from USB to Bluetooth or Speaker must auto-pause
        val routeCmd = controller.onOutputRouteChanged(
            deviceName = "Bluetooth Headset",
            isUsbAudio = false,
        )
        assertEquals(SafetyEventType.AUTO_PAUSE, routeCmd.type)
        assertEquals(AutoPauseReason.OUTPUT_ROUTE_CHANGED, routeCmd.autoPauseReason)
        assertEquals(AudioOutputMode.SAFE_VOLUME_MODE, controller.state().mode)
        assertFalse(controller.state().isUsbAudioConnected)
    }

    @Test
    fun syncInitialOutputDevice_setsRealDeviceNameWithoutAutoEnablingBitPerfect() {
        val controller = AudioSafetyController()
        controller.syncInitialOutputDevice(
            deviceName = "USB-Audio - Audio hs 2.0 headset",
            isUsbAudio = true,
            isHardwareVolumeVerified = false,
        )

        val state = controller.state()
        assertEquals(AudioOutputMode.SAFE_VOLUME_MODE, state.mode)
        assertEquals("USB-Audio - Audio hs 2.0 headset", state.activeDeviceName)
        assertTrue(state.isUsbAudioConnected)
        assertFalse(state.isHardwareVolumeVerified)
        assertFalse(state.isPendingSafetyWarning)
        assertEquals(null, state.lastAutoPauseReason)
    }

    @Test
    fun formatAutoPauseNotice_returnsDescriptiveSafetyMessageForEachReason() {
        assertEquals(
            "Auto-paused: audio output disconnected or becoming noisy.",
            AudioSafetyController.formatAutoPauseNotice(AutoPauseReason.BECOMING_NOISY),
        )
        assertEquals(
            "Auto-paused: USB audio device disconnected (Safe Mode restored).",
            AudioSafetyController.formatAutoPauseNotice(AutoPauseReason.USB_DISCONNECTED),
        )
        assertEquals(
            "Auto-paused: USB audio device connected in Safe Mode.",
            AudioSafetyController.formatAutoPauseNotice(AutoPauseReason.USB_CONNECTED_ROUTE_CHANGE),
        )
        assertEquals(
            "Auto-paused: audio output route changed (Safe Mode active).",
            AudioSafetyController.formatAutoPauseNotice(AutoPauseReason.OUTPUT_ROUTE_CHANGED),
        )
        assertEquals(
            "Auto-paused for hearing safety.",
            AudioSafetyController.formatAutoPauseNotice(null),
        )
    }
}
