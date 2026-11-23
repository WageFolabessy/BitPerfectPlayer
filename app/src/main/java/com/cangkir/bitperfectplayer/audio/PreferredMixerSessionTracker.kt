package com.cangkir.bitperfectplayer.audio

/**
 * Snapshot of the USB mixer profile currently applied via `AudioManager.setPreferredMixerAttributes`.
 */
data class AppliedUsbMixerProfile(
    val deviceId: Int,
    val behavior: NegotiatedMixerBehavior,
    val sampleRateHz: Int,
    val androidEncoding: Int,
    val channelCount: Int,
)

/**
 * Pure-Kotlin session tracker preventing redundant Android 14+ `clearPreferredMixerAttributes()`
 * Binder calls on consecutive Safe Mode track switches when no preferred mixer attribute is active,
 * while guaranteeing cleanup whenever an applied preference must be cleared or replaced (zero
 * `android.*` imports).
 */
class PreferredMixerSessionTracker {

    private val lock = Any()
    private var currentProfile: AppliedUsbMixerProfile? = null

    val activeProfile: AppliedUsbMixerProfile?
        get() = synchronized(lock) { currentProfile }

    val hasActivePreference: Boolean
        get() = synchronized(lock) { currentProfile != null }

    fun recordApplied(profile: AppliedUsbMixerProfile) {
        synchronized(lock) {
            currentProfile = profile
        }
    }

    fun matchesActive(
        deviceId: Int,
        behavior: NegotiatedMixerBehavior,
        sampleRateHz: Int,
        androidEncoding: Int,
        channelCount: Int,
    ): Boolean = synchronized(lock) {
        val active = currentProfile ?: return false
        active.deviceId == deviceId &&
            active.behavior == behavior &&
            active.sampleRateHz == sampleRateHz &&
            active.androidEncoding == androidEncoding &&
            active.channelCount == channelCount
    }

    fun consumeDeviceIdToClear(explicitDeviceId: Int? = null, forceClearExplicit: Boolean = false): Int? = synchronized(lock) {
        val previous = currentProfile
        if (explicitDeviceId == null) {
            currentProfile = null
            return@synchronized previous?.deviceId
        }
        when {
            previous?.deviceId == explicitDeviceId -> {
                currentProfile = null
                explicitDeviceId
            }
            forceClearExplicit -> explicitDeviceId
            else -> null
        }
    }

    fun reset() {
        synchronized(lock) {
            currentProfile = null
        }
    }
}
