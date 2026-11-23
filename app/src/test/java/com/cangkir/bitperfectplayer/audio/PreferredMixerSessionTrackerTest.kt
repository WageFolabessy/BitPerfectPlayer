package com.cangkir.bitperfectplayer.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferredMixerSessionTrackerTest {

    @Test
    fun safeModeTrackSwitchesWithoutActivePreference_skipClearBinderCallsCompletely() {
        val tracker = PreferredMixerSessionTracker()

        assertFalse(tracker.hasActivePreference)
        assertNull(tracker.consumeDeviceIdToClear())
        assertNull(tracker.consumeDeviceIdToClear(explicitDeviceId = 42))
        assertFalse(
            tracker.matchesActive(
                deviceId = 42,
                behavior = NegotiatedMixerBehavior.DEFAULT_NATIVE_RATE,
                sampleRateHz = 96_000,
                androidEncoding = PcmStreamRules.ANDROID_ENCODING_PCM_FLOAT,
                channelCount = 2,
            ),
        )
    }

    @Test
    fun bitPerfectOrNativeRatePreference_clearsOnceAndReusesIdenticalConsecutiveProfile() {
        val tracker = PreferredMixerSessionTracker()
        tracker.recordApplied(
            AppliedUsbMixerProfile(
                deviceId = 42,
                behavior = NegotiatedMixerBehavior.BIT_PERFECT,
                sampleRateHz = 96_000,
                androidEncoding = PcmStreamRules.ANDROID_ENCODING_PCM_24BIT_PACKED,
                channelCount = 2,
            ),
        )
        assertTrue(tracker.hasActivePreference)

        // Same USB device & stream format reuses active HAL preference with zero clear/set churn
        assertTrue(
            tracker.matchesActive(
                deviceId = 42,
                behavior = NegotiatedMixerBehavior.BIT_PERFECT,
                sampleRateHz = 96_000,
                androidEncoding = PcmStreamRules.ANDROID_ENCODING_PCM_24BIT_PACKED,
                channelCount = 2,
            ),
        )

        // Different sample rate / mode does not match -> triggers exactly one clear on device 42
        assertFalse(
            tracker.matchesActive(
                deviceId = 42,
                behavior = NegotiatedMixerBehavior.NOT_APPLICABLE_SAFE_MODE,
                sampleRateHz = 44_100,
                androidEncoding = PcmStreamRules.ANDROID_ENCODING_PCM_16BIT,
                channelCount = 2,
            ),
        )
        assertEquals(42, tracker.consumeDeviceIdToClear())
        assertFalse(tracker.hasActivePreference)

        // Subsequent clears in Safe Mode produce zero Binder IPC calls
        assertNull(tracker.consumeDeviceIdToClear())
    }

    @Test
    fun disconnectingUnrelatedUsbDevice_doesNotDropOrClearActiveUsbDevicePreference() {
        val tracker = PreferredMixerSessionTracker()
        tracker.recordApplied(
            AppliedUsbMixerProfile(
                deviceId = 42,
                behavior = NegotiatedMixerBehavior.DEFAULT_NATIVE_RATE,
                sampleRateHz = 48_000,
                androidEncoding = PcmStreamRules.ANDROID_ENCODING_PCM_FLOAT,
                channelCount = 2,
            ),
        )

        // Disconnecting an unrelated USB device 99 skips clear and preserves device 42 tracking
        assertNull(tracker.consumeDeviceIdToClear(explicitDeviceId = 99))
        assertTrue(tracker.hasActivePreference)

        // Disconnecting the active USB device 42 returns 42 once and clears tracking
        assertEquals(42, tracker.consumeDeviceIdToClear(explicitDeviceId = 42))
        assertFalse(tracker.hasActivePreference)
        assertNull(tracker.consumeDeviceIdToClear(explicitDeviceId = 42))
    }
}
