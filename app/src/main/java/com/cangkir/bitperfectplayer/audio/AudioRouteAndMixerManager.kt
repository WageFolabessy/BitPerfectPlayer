package com.cangkir.bitperfectplayer.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.cangkir.bitperfectplayer.BuildConfig
import com.cangkir.bitperfectplayer.diagnostics.DiagnosticLogLevel
import com.cangkir.bitperfectplayer.diagnostics.LocalDiagnosticLogger
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Internal collaborator managing Android audio output device routing, `AudioDeviceCallback` +
 * early `UsbManager.ACTION_USB_DEVICE_ATTACHED` / `ACTION_USB_DEVICE_DETACHED` (`USB_CLASS_AUDIO`)
 * detection (including `isSink` filtering, duplicate-route deduplication, cold-start initial-callback
 * suppression, and multi-source connect/disconnect coalescing via [AudioRouteEventFilter]),
 * `AudioFocusRequest` lifecycle (with Bit-Perfect ducking isolation), and Android 14+ (`API 34+`)
 * `MIXER_BEHAVIOR_BIT_PERFECT` negotiation + mandatory `clearPreferredMixerAttributes` cleanup.
 */
internal class AudioRouteAndMixerManager(
    private val appContext: Context,
    private val audioManager: AudioManager,
    private val mainHandler: Handler,
    private val playbackAudioAttributes: AudioAttributes,
    private val safetyController: AudioSafetyController,
    private val onSafetyCommand: (SafetyCommand) -> Unit,
    private val onDiagnosticsChanged: () -> Unit,
    private val onFocusLossPause: () -> Unit,
    private val onFocusGainResume: () -> Unit,
    private val onDuckSafeModeVolume: (Float) -> Unit,
    private val isCurrentlyPlaying: () -> Boolean,
    private val isReleased: () -> Boolean,
    private val diagnosticLogger: LocalDiagnosticLogger? = null,
    private val currentEngineStatusName: () -> String = { "UNKNOWN" },
    private val isPlaybackActiveOrStarting: () -> Boolean = isCurrentlyPlaying,
    private val onPausedSafeModeRouteRefreshed: (coalesceDiagnostics: Boolean) -> Unit = { onDiagnosticsChanged() },
) {
    private data class MixerNegotiationResult(
        val behavior: NegotiatedMixerBehavior,
        val summary: String,
        val supportedProfileCount: Int = 0,
        val bitPerfectProfileCount: Int = 0,
    )

    private data class RouteSafetyDispatchRequest(
        val trigger: String,
        val decision: RouteFilterDecision,
        val nowElapsedMs: Long,
        val sinkCount: Int,
        val hasUsbSink: Boolean,
        val usbTransitionKind: UsbTransitionKind?,
        val clearDevice: AudioDeviceInfo?,
        val shouldClearMixer: Boolean,
        val resolvedDeviceName: String,
        val resolvedIsUsbAudio: Boolean,
        val safetyTransition: () -> SafetyCommand,
    )

    @Volatile
    var activeOutputDevice: AudioDeviceInfo? = null
        private set

    @Volatile
    private var lastConfiguredMixerDevice: AudioDeviceInfo? = null

    private val mixerSessionTracker = PreferredMixerSessionTracker()
    private val routeEventFilter = AudioRouteEventFilter()
    private val usbRouteStabilityTracker = UsbRouteStabilityTracker()
    private val routeMonitorExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "BitPerfectRouteMonitor").apply { isDaemon = true }
    }
    private val sinksLock = Any()
    private val knownOutputSinks = LinkedHashMap<Int, AudioDeviceInfo>()
    private val refreshPending = AtomicBoolean(false)
    private val refreshRunning = AtomicBoolean(false)
    private val initialSyncNeeded = AtomicBoolean(false)
    private val routeFilterInitialized = AtomicBoolean(false)

    @Volatile
    var activeNegotiatedMixerBehavior: NegotiatedMixerBehavior =
        NegotiatedMixerBehavior.NOT_APPLICABLE_SAFE_MODE
        private set

    @Volatile
    var activeNegotiatedMixerSummary: String = "Default System Mixer (Safe Mode)"
        private set

    @Volatile
    var hasAudioFocus: Boolean = false
        private set

    @Volatile
    var resumeAfterTransientFocusLoss: Boolean = false

    val hasActivePreferredMixer: Boolean
        get() = mixerSessionTracker.hasActivePreference

    fun evaluatePlayResume(nowElapsedMs: Long, firstPlayRequestElapsedMs: Long): PlayResumeRouteGuardResult =
        usbRouteStabilityTracker.evaluatePlayResume(
            nowElapsedMs = nowElapsedMs,
            firstPlayRequestElapsedMs = firstPlayRequestElapsedMs,
        )

    fun isUsbRouteActivelyFlapping(nowElapsedMs: Long): Boolean = usbRouteStabilityTracker.isActivelyFlapping(nowElapsedMs)

    fun shouldBindPreferredUsbDevice(hasUsbSink: Boolean): Boolean =
        AudioStartPathPolicy.shouldBindPreferredUsbDevice(safetyController.state().mode, hasUsbSink)

    private var receiversRegistered: Boolean = false

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        diagnosticLogger?.logAudio("AUDIO_FOCUS") {
            mapOf("change" to focusChange, "mode" to safetyController.state().mode.name)
        }
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasAudioFocus = false
                resumeAfterTransientFocusLoss = false
                onFocusLossPause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                hasAudioFocus = false
                resumeAfterTransientFocusLoss = isCurrentlyPlaying()
                onFocusLossPause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (safetyController.state().mode == AudioOutputMode.BIT_PERFECT_OPT_IN) {
                    // Never digitally attenuate PCM samples in Bit-Perfect Mode; pause cleanly instead.
                    hasAudioFocus = false
                    resumeAfterTransientFocusLoss = isCurrentlyPlaying()
                    onFocusLossPause()
                } else {
                    onDuckSafeModeVolume(0.25f)
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasAudioFocus = true
                if (safetyController.state().mode == AudioOutputMode.SAFE_VOLUME_MODE) {
                    onDuckSafeModeVolume(1.0f)
                }
                if (resumeAfterTransientFocusLoss && !isReleased()) {
                    resumeAfterTransientFocusLoss = false
                    onFocusGainResume()
                }
            }
        }
    }

    private val audioFocusRequest: AudioFocusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(playbackAudioAttributes)
            .setAcceptsDelayedFocusGain(true)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener(focusChangeListener, mainHandler)
            .build()

    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AudioManager.ACTION_AUDIO_BECOMING_NOISY || isReleased()) return
            val nowMs = SystemClock.elapsedRealtime()
            val previousDevice = activeOutputDevice
            val wasUsbBefore = previousDevice?.let(::isUsbOutputDevice) == true ||
                safetyController.state().isUsbAudioConnected
            val excludeNoisyId = previousDevice?.takeIf(::isExternalOutputDevice)?.id
            removeCachedSinkById(excludeNoisyId)
            val fallbackDevice = selectPreferredFromCachedSinks(excludeDeviceId = excludeNoisyId)
            val decision = routeEventFilter.onBecomingNoisy(
                nowElapsedMs = nowMs,
                resolvedFallbackDevice = fallbackDevice?.let(::toRouteDescriptor),
            )
            activeOutputDevice = fallbackDevice
            val fallbackName = fallbackDevice?.let(::resolveDeviceName) ?: "Internal Speaker"
            val fallbackIsUsb = fallbackDevice?.let(::isUsbOutputDevice) == true
            val sinkCount = if (previousDevice != null) 1 else 0
            when (decision) {
                is RouteFilterDecision.BecomingNoisy -> {
                    val usbDetach = wasUsbBefore || decision.clearUsbDeviceId != null
                    dispatchRouteSafetyOrSilentRefresh(
                        RouteSafetyDispatchRequest(
                            trigger = "BECOMING_NOISY",
                            decision = decision,
                            nowElapsedMs = nowMs,
                            sinkCount = sinkCount,
                            hasUsbSink = usbDetach,
                            usbTransitionKind = if (usbDetach) UsbTransitionKind.DETACH else null,
                            clearDevice = if (decision.clearUsbDeviceId != null) previousDevice else null,
                            shouldClearMixer = decision.clearUsbDeviceId != null || wasUsbBefore,
                            resolvedDeviceName = fallbackName,
                            resolvedIsUsbAudio = fallbackIsUsb,
                            safetyTransition = {
                                safetyController.onBecomingNoisy(
                                    fallbackDeviceName = fallbackName,
                                    fallbackIsUsbAudio = fallbackIsUsb,
                                )
                            },
                        ),
                    )
                }
                is RouteFilterDecision.CoalescedDisconnectCleanup -> {
                    logRouteEvent(
                        trigger = "BECOMING_NOISY",
                        decision = decision,
                        sinkCount = sinkCount,
                        hasUsbSink = previousDevice?.let(::isUsbOutputDevice) == true,
                        coalesced = true,
                    )
                    if (decision.removedUsbDeviceId != null) {
                        clearPreferredMixerAttributesAsyncOnApi34(previousDevice)
                    }
                    syncCoalescedMetadataAndNotify(fallbackName, fallbackIsUsb, nowMs)
                }
                else -> logRouteEvent(
                    trigger = "BECOMING_NOISY",
                    decision = decision,
                    sinkCount = sinkCount,
                    hasUsbSink = previousDevice?.let(::isUsbOutputDevice) == true,
                    coalesced = false,
                )
            }
        }
    }

    private val usbAudioDeviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (isReleased()) return
            val action = intent?.action ?: return
            if (action != UsbManager.ACTION_USB_DEVICE_ATTACHED && action != UsbManager.ACTION_USB_DEVICE_DETACHED) {
                return
            }
            val usbDevice = extractUsbDeviceFromIntent(intent) ?: return
            if (!isUsbAudioClassDevice(usbDevice)) return
            val nowMs = SystemClock.elapsedRealtime()
            if (action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
                handleEarlyUsbAudioAttached(usbDevice, nowMs)
            } else {
                handleEarlyUsbAudioDetached(nowMs)
            }
        }
    }

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            if (isReleased()) return
            val nowMs = SystemClock.elapsedRealtime()
            addKnownOutputSinks(addedDevices)
            val sinkCount = addedDevices.count { it.isSink }
            val hasUsbSink = addedDevices.any { it.isSink && isUsbOutputDevice(it) }
            val preferredAfter = selectPreferredFromCachedSinks(ignoreNoisySuppression = true)
            val decision = routeEventFilter.onDevicesAdded(
                addedDevices = addedDevices.map(::toRouteDescriptor),
                preferredActiveAfterAdd = preferredAfter?.let(::toRouteDescriptor),
                nowElapsedMs = nowMs,
            )
            when (decision) {
                RouteFilterDecision.Ignore -> logRouteEvent(
                    trigger = "DEVICES_ADDED",
                    decision = decision,
                    sinkCount = sinkCount,
                    hasUsbSink = hasUsbSink,
                    coalesced = true,
                )
                is RouteFilterDecision.SyncInitialDevice -> {
                    logRouteEvent("DEVICES_ADDED", decision, sinkCount, hasUsbSink, coalesced = false)
                    routeFilterInitialized.set(true)
                    val chosen = addedDevices.firstOrNull { it.isSink && it.id == decision.device.id } ?: preferredAfter
                    activeOutputDevice = chosen
                    safetyController.syncInitialOutputDevice(
                        deviceName = decision.device.name,
                        isUsbAudio = decision.device.isUsbOutput,
                        isHardwareVolumeVerified = false,
                    )
                    onDiagnosticsChanged()
                }
                is RouteFilterDecision.CoalescedUsbConnectMetadataSync -> {
                    logRouteEvent("DEVICES_ADDED", decision, sinkCount, hasUsbSink, coalesced = true)
                    routeFilterInitialized.set(true)
                    val chosen = addedDevices.firstOrNull { it.isSink && it.id == decision.device.id } ?: preferredAfter
                    activeOutputDevice = chosen
                    syncCoalescedMetadataAndNotify(decision.device.name, decision.device.isUsbOutput, nowMs)
                }
                is RouteFilterDecision.DeviceConnected -> {
                    routeFilterInitialized.set(true)
                    val chosen = addedDevices.firstOrNull { it.isSink && it.id == decision.device.id } ?: preferredAfter
                    activeOutputDevice = chosen
                    dispatchRouteSafetyOrSilentRefresh(
                        RouteSafetyDispatchRequest(
                            trigger = "DEVICES_ADDED",
                            decision = decision,
                            nowElapsedMs = nowMs,
                            sinkCount = sinkCount,
                            hasUsbSink = hasUsbSink,
                            usbTransitionKind = if (decision.device.isUsbOutput) UsbTransitionKind.ATTACH else null,
                            clearDevice = null,
                            shouldClearMixer = decision.device.isUsbOutput,
                            resolvedDeviceName = decision.device.name,
                            resolvedIsUsbAudio = decision.device.isUsbOutput,
                            safetyTransition = {
                                safetyController.onAudioDeviceConnected(
                                    deviceName = decision.device.name,
                                    isUsbAudio = decision.device.isUsbOutput,
                                    // Conservative assumption: never assume UAC hardware volume
                                    isHardwareVolumeVerified = false,
                                )
                            },
                        ),
                    )
                }
                else -> logRouteEvent("DEVICES_ADDED", decision, sinkCount, hasUsbSink, coalesced = false)
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            if (isReleased()) return
            val nowMs = SystemClock.elapsedRealtime()
            removeKnownOutputSinks(removedDevices)
            val sinkCount = removedDevices.count { it.isSink }
            val hasUsbSink = removedDevices.any { it.isSink && isUsbOutputDevice(it) }
            val removedExternalSinkId = removedDevices.firstOrNull { it.isSink && isExternalOutputDevice(it) }?.id
            val fallback = selectPreferredFromCachedSinks(
                excludeDeviceId = removedExternalSinkId,
                ignoreNoisySuppression = true,
            )
            val decision = routeEventFilter.onDevicesRemoved(
                removedDevices = removedDevices.map(::toRouteDescriptor),
                preferredActiveAfterRemove = fallback?.let(::toRouteDescriptor),
                nowElapsedMs = nowMs,
            )
            val fallbackName = fallback?.let(::resolveDeviceName) ?: "Internal Speaker"
            val fallbackIsUsb = fallback?.let(::isUsbOutputDevice) == true
            when (decision) {
                RouteFilterDecision.Ignore -> logRouteEvent(
                    trigger = "DEVICES_REMOVED",
                    decision = decision,
                    sinkCount = sinkCount,
                    hasUsbSink = hasUsbSink,
                    coalesced = true,
                )
                is RouteFilterDecision.CoalescedDisconnectCleanup -> {
                    logRouteEvent("DEVICES_REMOVED", decision, sinkCount, hasUsbSink, coalesced = true)
                    val removedUsb = decision.removedUsbDeviceId?.let { id ->
                        removedDevices.firstOrNull { it.isSink && it.id == id }
                    }
                    if (removedUsb != null) {
                        clearPreferredMixerAttributesAsyncOnApi34(removedUsb)
                    }
                    activeOutputDevice = fallback
                    syncCoalescedMetadataAndNotify(fallbackName, fallbackIsUsb, nowMs)
                }
                is RouteFilterDecision.UsbDisconnected -> {
                    val removedUsb = removedDevices.firstOrNull { it.isSink && it.id == decision.removedUsbDeviceId }
                    activeOutputDevice = fallback
                    dispatchRouteSafetyOrSilentRefresh(
                        RouteSafetyDispatchRequest(
                            trigger = "DEVICES_REMOVED",
                            decision = decision,
                            nowElapsedMs = nowMs,
                            sinkCount = sinkCount,
                            hasUsbSink = hasUsbSink,
                            usbTransitionKind = UsbTransitionKind.DETACH,
                            clearDevice = removedUsb,
                            shouldClearMixer = true,
                            resolvedDeviceName = fallbackName,
                            resolvedIsUsbAudio = fallbackIsUsb,
                            safetyTransition = {
                                safetyController.onUsbDisconnected(fallbackDeviceName = fallbackName)
                            },
                        ),
                    )
                }
                is RouteFilterDecision.OutputRouteChanged -> {
                    activeOutputDevice = fallback
                    dispatchRouteSafetyOrSilentRefresh(
                        RouteSafetyDispatchRequest(
                            trigger = "DEVICES_REMOVED",
                            decision = decision,
                            nowElapsedMs = nowMs,
                            sinkCount = sinkCount,
                            hasUsbSink = hasUsbSink,
                            usbTransitionKind = null,
                            clearDevice = null,
                            shouldClearMixer = !fallbackIsUsb,
                            resolvedDeviceName = fallbackName,
                            resolvedIsUsbAudio = fallbackIsUsb,
                            safetyTransition = {
                                safetyController.onOutputRouteChanged(
                                    deviceName = fallbackName,
                                    isUsbAudio = fallbackIsUsb,
                                    isHardwareVolumeVerified = false,
                                )
                            },
                        ),
                    )
                }
                else -> logRouteEvent("DEVICES_REMOVED", decision, sinkCount, hasUsbSink, coalesced = false)
            }
        }
    }

    private fun extractUsbDeviceFromIntent(intent: Intent): UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    } else {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
    }

    private fun isUsbAudioClassDevice(usbDevice: UsbDevice): Boolean = runCatching {
        val count = usbDevice.interfaceCount
        if (count <= 0) return@runCatching false
        val classes = IntArray(count) { idx -> usbDevice.getInterface(idx).interfaceClass }
        AudioRouteEventFilter.hasUsbAudioInterface(classes)
    }.getOrDefault(false)

    private fun handleEarlyUsbAudioAttached(usbDevice: UsbDevice, nowMs: Long) {
        val productName = runCatching { usbDevice.productName?.trim().orEmpty() }
            .getOrDefault("")
            .ifEmpty { AudioRouteEventFilter.DEFAULT_EARLY_USB_DEVICE_NAME }
        val decision = routeEventFilter.onUsbAudioAttachedEarly(productName, nowMs)
        if (decision is RouteFilterDecision.DeviceConnected) {
            routeFilterInitialized.set(true)
            dispatchRouteSafetyOrSilentRefresh(
                RouteSafetyDispatchRequest(
                    trigger = "USB_ATTACHED_EARLY",
                    decision = decision,
                    nowElapsedMs = nowMs,
                    sinkCount = 1,
                    hasUsbSink = true,
                    usbTransitionKind = UsbTransitionKind.ATTACH,
                    clearDevice = null,
                    shouldClearMixer = true,
                    resolvedDeviceName = decision.device.name,
                    resolvedIsUsbAudio = true,
                    safetyTransition = {
                        safetyController.onAudioDeviceConnected(
                            deviceName = decision.device.name,
                            isUsbAudio = true,
                            isHardwareVolumeVerified = false,
                        )
                    },
                ),
            )
        } else {
            logRouteEvent(
                trigger = "USB_ATTACHED_EARLY",
                decision = decision,
                sinkCount = 1,
                hasUsbSink = true,
                coalesced = decision is RouteFilterDecision.Ignore,
            )
        }
    }

    private fun handleEarlyUsbAudioDetached(nowMs: Long) {
        val previousUsbDevice = activeOutputDevice?.takeIf(::isUsbOutputDevice) ?: removeCachedUsbSinks()
        removeCachedUsbSinks()
        val fallback = selectPreferredFromCachedSinks(ignoreNoisySuppression = true)
        val decision = routeEventFilter.onUsbAudioDetachedEarly(
            nowElapsedMs = nowMs,
            resolvedFallbackDevice = fallback?.let(::toRouteDescriptor),
        )
        activeOutputDevice = fallback
        val fallbackName = fallback?.let(::resolveDeviceName) ?: "Internal Speaker"
        val fallbackIsUsb = fallback?.let(::isUsbOutputDevice) == true
        when (decision) {
            RouteFilterDecision.Ignore -> logRouteEvent(
                trigger = "USB_DETACHED_EARLY",
                decision = decision,
                sinkCount = 1,
                hasUsbSink = true,
                coalesced = true,
            )
            is RouteFilterDecision.CoalescedDisconnectCleanup -> {
                logRouteEvent(
                    trigger = "USB_DETACHED_EARLY",
                    decision = decision,
                    sinkCount = 1,
                    hasUsbSink = true,
                    coalesced = true,
                )
                if (previousUsbDevice != null || decision.removedUsbDeviceId != null) {
                    clearPreferredMixerAttributesAsyncOnApi34(previousUsbDevice)
                }
                syncCoalescedMetadataAndNotify(fallbackName, fallbackIsUsb, nowMs)
            }
            is RouteFilterDecision.UsbDisconnected -> {
                dispatchRouteSafetyOrSilentRefresh(
                    RouteSafetyDispatchRequest(
                        trigger = "USB_DETACHED_EARLY",
                        decision = decision,
                        nowElapsedMs = nowMs,
                        sinkCount = 1,
                        hasUsbSink = true,
                        usbTransitionKind = UsbTransitionKind.DETACH,
                        clearDevice = previousUsbDevice,
                        shouldClearMixer = true,
                        resolvedDeviceName = fallbackName,
                        resolvedIsUsbAudio = fallbackIsUsb,
                        safetyTransition = {
                            safetyController.onUsbDisconnected(fallbackDeviceName = fallbackName)
                        },
                    ),
                )
            }
            else -> logRouteEvent(
                trigger = "USB_DETACHED_EARLY",
                decision = decision,
                sinkCount = 1,
                hasUsbSink = true,
                coalesced = false,
            )
        }
    }

    private fun dispatchRouteSafetyOrSilentRefresh(request: RouteSafetyDispatchRequest) {
        val isRapidFlapping = if (request.usbTransitionKind != null) {
            usbRouteStabilityTracker.recordUsbTransition(request.usbTransitionKind, request.nowElapsedMs)
        } else {
            usbRouteStabilityTracker.isActivelyFlapping(request.nowElapsedMs)
        }
        val safetyState = safetyController.state()
        val disposition = UsbRouteStabilityPolicy.decideRouteAutoPauseDisposition(
            isActivelyPlayingOrStarting = isPlaybackActiveOrStarting(),
            safetyMode = safetyState.mode,
            isPendingSafetyWarning = safetyState.isPendingSafetyWarning,
            isRapidFlappingBurst = isRapidFlapping,
        )
        if (disposition == RouteAutoPauseDisposition.FULL_SAFETY_AUTO_PAUSE) {
            logRouteEvent(
                trigger = request.trigger,
                decision = request.decision,
                sinkCount = request.sinkCount,
                hasUsbSink = request.hasUsbSink,
                coalesced = false,
            )
            if (request.clearDevice != null) {
                clearPreferredMixerAttributesAsyncOnApi34(request.clearDevice)
            }
            val cmd = request.safetyTransition()
            onSafetyCommand(cmd)
            return
        }
        val coalesceDiag = disposition == RouteAutoPauseDisposition.SILENT_ROUTE_REFRESH_COALESCED
        logRouteEvent(
            trigger = request.trigger,
            decision = request.decision,
            sinkCount = request.sinkCount,
            hasUsbSink = request.hasUsbSink,
            coalesced = true,
            decisionNameOverride = if (coalesceDiag) {
                "PausedSafeRouteRefreshCoalesced"
            } else {
                "PausedSafeRouteRefresh"
            },
        )
        if (request.shouldClearMixer || mixerSessionTracker.hasActivePreference) {
            clearPreferredMixerAttributesAsyncOnApi34(request.clearDevice)
        }
        resumeAfterTransientFocusLoss = false
        safetyController.syncActiveOutputDeviceMetadata(
            deviceName = request.resolvedDeviceName,
            isUsbAudio = request.resolvedIsUsbAudio,
        )
        onPausedSafeModeRouteRefreshed(coalesceDiag)
    }

    private fun syncCoalescedMetadataAndNotify(deviceName: String, isUsbAudio: Boolean, nowMs: Long) {
        safetyController.syncActiveOutputDeviceMetadata(
            deviceName = deviceName,
            isUsbAudio = isUsbAudio,
        )
        val safetyState = safetyController.state()
        val disposition = UsbRouteStabilityPolicy.decideRouteAutoPauseDisposition(
            isActivelyPlayingOrStarting = isPlaybackActiveOrStarting(),
            safetyMode = safetyState.mode,
            isPendingSafetyWarning = safetyState.isPendingSafetyWarning,
            isRapidFlappingBurst = usbRouteStabilityTracker.isActivelyFlapping(nowMs),
        )
        if (disposition == RouteAutoPauseDisposition.SILENT_ROUTE_REFRESH_COALESCED) {
            onPausedSafeModeRouteRefreshed(true)
        } else {
            onDiagnosticsChanged()
        }
    }

    private fun logRouteEvent(
        trigger: String,
        decision: RouteFilterDecision,
        sinkCount: Int,
        hasUsbSink: Boolean,
        coalesced: Boolean,
        decisionNameOverride: String? = null,
    ) {
        diagnosticLogger?.logAudio("ROUTE_EVENT") {
            val activeDev = activeOutputDevice
            linkedMapOf(
                "trigger" to trigger,
                "decision" to (decisionNameOverride ?: decision.javaClass.simpleName),
                "sinkCount" to sinkCount,
                "hasUsbSink" to hasUsbSink,
                "activeConn" to mapConnectionType(activeDev).name,
                "activeType" to (activeDev?.type ?: 0),
                "coalesced" to coalesced,
                "mode" to safetyController.state().mode.name,
                "status" to currentEngineStatusName(),
            )
        }
    }

    fun initializeRouteMonitoring() {
        registerAudioMonitors()
        scheduleAsyncOutputDeviceRefresh(isInitialSync = true)
    }

    fun registerAudioMonitors() {
        if (receiversRegistered) return
        receiversRegistered = true
        runCatching {
            audioManager.registerAudioDeviceCallback(audioDeviceCallback, mainHandler)
        }
        runCatching {
            ContextCompat.registerReceiver(
                appContext,
                becomingNoisyReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
        runCatching {
            val usbFilter = IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            ContextCompat.registerReceiver(
                appContext,
                usbAudioDeviceReceiver,
                usbFilter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
    }

    fun unregisterAudioMonitors() {
        if (receiversRegistered) {
            receiversRegistered = false
            runCatching { audioManager.unregisterAudioDeviceCallback(audioDeviceCallback) }
            runCatching { appContext.unregisterReceiver(becomingNoisyReceiver) }
            runCatching { appContext.unregisterReceiver(usbAudioDeviceReceiver) }
        }
        synchronized(sinksLock) {
            knownOutputSinks.clear()
        }
        usbRouteStabilityTracker.reset()
        if (isReleased()) {
            runCatching { routeMonitorExecutor.shutdownNow() }
        }
    }

    fun requestAudioFocusLocked(forceRequest: Boolean = false): Boolean {
        if (hasAudioFocus && !forceRequest) return true
        val startMs = SystemClock.elapsedRealtime()
        val res = audioManager.requestAudioFocus(audioFocusRequest)
        val elapsedMs = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(0L)
        recordSlowStageIfNeeded(
            stage = "requestAudioFocus",
            elapsedMs = elapsedMs,
            extra = mapOf("result" to res),
        )
        hasAudioFocus = (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        if (!hasAudioFocus) {
            diagnosticLogger?.logError("AUDIOFOCUS_REQUEST_FAILED") {
                mapOf("result" to res, "mode" to safetyController.state().mode.name)
            }
        }
        return hasAudioFocus
    }

    fun abandonAudioFocusLocked(): Long {
        if (!hasAudioFocus) return 0L
        val startMs = SystemClock.elapsedRealtime()
        runCatching { audioManager.abandonAudioFocusRequest(audioFocusRequest) }
        hasAudioFocus = false
        val elapsedMs = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(0L)
        recordSlowStageIfNeeded(stage = "abandonAudioFocus", elapsedMs = elapsedMs)
        return elapsedMs
    }

    private fun recordSlowStageIfNeeded(stage: String, elapsedMs: Long, extra: Map<String, Any?> = emptyMap()) {
        if (AudioStartPathPolicy.shouldLogSlowAudioStage(BuildConfig.DEBUG, elapsedMs)) {
            Log.w(TAG, "Slow audio stage [$stage]: ${elapsedMs}ms $extra")
        }
        if (AudioStartPathPolicy.shouldRecordSlowAudioStage(elapsedMs)) {
            diagnosticLogger?.logPerf("SLOW_AUDIO_STAGE", level = DiagnosticLogLevel.WARN) {
                val fields = LinkedHashMap<String, Any?>(4 + extra.size)
                fields["stage"] = stage
                fields["elapsedMs"] = elapsedMs
                fields["mode"] = safetyController.state().mode.name
                fields.putAll(extra)
                fields
            }
        }
    }

    /**
     * Resolves the current output device from the in-memory sink cache (zero synchronous
     * `getDevices`/`getSupportedMixerAttributes` IPC in Safe Mode or during `BECOMING_NOISY`
     * cooldown) and negotiates API 34+ USB mixer attributes strictly for opted-in Bit-Perfect USB.
     */
    fun prepareOutputDeviceAndNegotiateMixerLocked(
        pcmFormat: DecodedPcmFormat,
        androidEncoding: Int,
        channelMask: Int,
        encodingNameResolver: (Int) -> String,
    ): AudioDeviceInfo? {
        val mixerStartMs = SystemClock.elapsedRealtime()
        val noisyCooldown = routeEventFilter.isWithinNoisyCooldown(mixerStartMs)
        val safetyMode = safetyController.state().mode
        val cachedDevice = selectPreferredFromCachedSinks()
        val currentDevice = when {
            cachedDevice != null -> cachedDevice
            safetyMode == AudioOutputMode.BIT_PERFECT_OPT_IN && !noisyCooldown -> detectPreferredOutputDevice()
            else -> activeOutputDevice?.takeIf { !noisyCooldown || !isExternalOutputDevice(it) }
        }
        if (currentDevice != null || noisyCooldown) {
            activeOutputDevice = currentDevice
            routeEventFilter.syncObservedActiveDevice(currentDevice?.let(::toRouteDescriptor))
            if (currentDevice != null) {
                safetyController.syncActiveOutputDeviceMetadata(
                    deviceName = resolveDeviceName(currentDevice),
                    isUsbAudio = isUsbOutputDevice(currentDevice),
                )
            }
        }
        val usbDevice = if (noisyCooldown) null else currentDevice?.takeIf { isUsbOutputDevice(it) }
        val strategy = AudioStartPathPolicy.decideMixerNegotiationStrategy(
            sdkInt = Build.VERSION.SDK_INT,
            mode = safetyMode,
            isNoisyCooldownActive = noisyCooldown,
            hasUsbSink = usbDevice != null,
        )
        val negotiation = when (strategy) {
            MixerNegotiationStrategy.SKIP_IN_SAFE_MODE -> {
                if (mixerSessionTracker.hasActivePreference) {
                    clearPreferredMixerAttributesAsyncOnApi34(null)
                }
                when {
                    safetyMode == AudioOutputMode.SAFE_VOLUME_MODE -> safeModeDefaultMixerResult()
                    usbDevice == null -> MixerNegotiationResult(
                        behavior = NegotiatedMixerBehavior.UNSUPPORTED_NON_USB_OUTPUT,
                        summary = "Default System Mixer (No USB Audio Output Connected)",
                    )
                    else -> MixerNegotiationResult(
                        behavior = NegotiatedMixerBehavior.UNSUPPORTED_API_BELOW_34,
                        summary = "Clean Native AudioTrack (Android ${Build.VERSION.SDK_INT} < API 34)",
                    )
                }
            }
            MixerNegotiationStrategy.SKIP_DURING_NOISY_COOLDOWN -> {
                if (mixerSessionTracker.hasActivePreference) {
                    clearPreferredMixerAttributesAsyncOnApi34(null)
                }
                if (safetyMode == AudioOutputMode.SAFE_VOLUME_MODE) {
                    safeModeDefaultMixerResult()
                } else {
                    MixerNegotiationResult(
                        behavior = NegotiatedMixerBehavior.DEFAULT_MIXER_FALLBACK,
                        summary = "Default System Mixer (Audio route stabilizing)",
                    )
                }
            }
            MixerNegotiationStrategy.APPLY_BIT_PERFECT_USB -> {
                negotiateMixerAttributesLocked(
                    pcmFormat = pcmFormat,
                    androidEncoding = androidEncoding,
                    channelMask = channelMask,
                    usbDevice = usbDevice,
                    encodingNameResolver = encodingNameResolver,
                )
            }
        }
        activeNegotiatedMixerBehavior = negotiation.behavior
        activeNegotiatedMixerSummary = negotiation.summary
        val mixerElapsedMs = (SystemClock.elapsedRealtime() - mixerStartMs).coerceAtLeast(0L)
        recordSlowStageIfNeeded(
            stage = "mixerNegotiation",
            elapsedMs = mixerElapsedMs,
            extra = mapOf("strategy" to strategy.name, "usb" to (usbDevice != null)),
        )
        diagnosticLogger?.logAudio("USB_MIXER_NEGOTIATED") {
            linkedMapOf(
                "mode" to safetyController.state().mode.name,
                "behavior" to negotiation.behavior.name,
                "usb" to (usbDevice != null),
                "conn" to mapConnectionType(currentDevice).name,
                "sr" to pcmFormat.sampleRateHz,
                "enc" to encodingNameResolver(androidEncoding),
                "ch" to pcmFormat.channelCount,
                "supportedProfiles" to negotiation.supportedProfileCount,
                "bitPerfectProfiles" to negotiation.bitPerfectProfileCount,
                "api" to Build.VERSION.SDK_INT,
            )
        }
        return usbDevice
    }

    /**
     * Offloads `AudioManager.clearPreferredMixerAttributes()` IPC onto [routeMonitorExecutor] so
     * Safe Mode transitions, `ACTION_AUDIO_BECOMING_NOISY`, and USB hot-plug never block Main or
     * playback startup threads.
     */
    fun clearPreferredMixerAttributesAsyncOnApi34(explicitDevice: AudioDeviceInfo?, forceClearExplicit: Boolean = false) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val deviceIdToClear = mixerSessionTracker.consumeDeviceIdToClear(
                explicitDeviceId = explicitDevice?.id,
                forceClearExplicit = forceClearExplicit,
            )
            val targetDevice = if (deviceIdToClear != null) {
                lastConfiguredMixerDevice?.takeIf { it.id == deviceIdToClear }
                    ?: explicitDevice?.takeIf { it.id == deviceIdToClear }
                    ?: activeOutputDevice?.takeIf { it.id == deviceIdToClear }
            } else {
                null
            }
            if (!mixerSessionTracker.hasActivePreference) {
                lastConfiguredMixerDevice = null
            }
            if (targetDevice != null && !isReleased()) {
                runCatching {
                    routeMonitorExecutor.execute {
                        if (!isReleased()) {
                            val clearStartMs = SystemClock.elapsedRealtime()
                            runCatching {
                                audioManager.clearPreferredMixerAttributes(playbackAudioAttributes, targetDevice)
                            }
                            val clearMs = (SystemClock.elapsedRealtime() - clearStartMs).coerceAtLeast(0L)
                            recordSlowStageIfNeeded(stage = "clearPreferredMixer", elapsedMs = clearMs)
                        }
                    }
                }.onFailure {
                    val clearStartMs = SystemClock.elapsedRealtime()
                    runCatching { audioManager.clearPreferredMixerAttributes(playbackAudioAttributes, targetDevice) }
                    val clearMs = (SystemClock.elapsedRealtime() - clearStartMs).coerceAtLeast(0L)
                    recordSlowStageIfNeeded(stage = "clearPreferredMixer", elapsedMs = clearMs)
                }
            }
        } else {
            mixerSessionTracker.reset()
            lastConfiguredMixerDevice = null
        }
        activeNegotiatedMixerBehavior = NegotiatedMixerBehavior.NOT_APPLICABLE_SAFE_MODE
        activeNegotiatedMixerSummary = "Default System Mixer (Safe Mode)"
    }

    /**
     * Conditional Android 14+ cleanup used during engine shutdown or intra-Bit-Perfect negotiation
     * fallbacks. Safe Mode and hot-plug teardown use [clearPreferredMixerAttributesAsyncOnApi34].
     */
    fun clearPreferredMixerAttributesOnApi34(explicitDevice: AudioDeviceInfo?, forceClearExplicit: Boolean = false) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val deviceIdToClear = mixerSessionTracker.consumeDeviceIdToClear(
                explicitDeviceId = explicitDevice?.id,
                forceClearExplicit = forceClearExplicit,
            )
            if (deviceIdToClear != null) {
                val targetDevice = lastConfiguredMixerDevice?.takeIf { it.id == deviceIdToClear }
                    ?: explicitDevice?.takeIf { it.id == deviceIdToClear }
                    ?: activeOutputDevice?.takeIf { it.id == deviceIdToClear }
                if (targetDevice != null) {
                    val clearStartMs = SystemClock.elapsedRealtime()
                    runCatching {
                        audioManager.clearPreferredMixerAttributes(playbackAudioAttributes, targetDevice)
                    }
                    val clearMs = (SystemClock.elapsedRealtime() - clearStartMs).coerceAtLeast(0L)
                    recordSlowStageIfNeeded(stage = "clearPreferredMixer", elapsedMs = clearMs)
                }
            }
            if (!mixerSessionTracker.hasActivePreference) {
                lastConfiguredMixerDevice = null
            }
        } else {
            mixerSessionTracker.reset()
            lastConfiguredMixerDevice = null
        }
        activeNegotiatedMixerBehavior = NegotiatedMixerBehavior.NOT_APPLICABLE_SAFE_MODE
        activeNegotiatedMixerSummary = "Default System Mixer (Safe Mode)"
    }

    fun resolveDiagnosticDeviceAndConnection(fallbackDeviceName: String): Pair<String, OutputConnectionType> {
        val device = activeOutputDevice ?: selectPreferredFromCachedSinks()
        val connType = when {
            device != null -> mapConnectionType(device)
            safetyController.state().isUsbAudioConnected -> OutputConnectionType.USB_DEVICE
            else -> OutputConnectionType.INTERNAL_SPEAKER
        }
        val deviceName = device?.let(::resolveDeviceName) ?: fallbackDeviceName
        return deviceName to connType
    }

    fun buildDiagnosticSnapshot(
        safety: AudioSafetyState,
        activeFormat: DecodedPcmFormat?,
        audioTrackSampleRateHz: Int,
        audioTrackEncodingLabel: String,
        replayGainResolution: ReplayGainResolution,
    ): AudioDiagnosticSnapshot {
        val (deviceName, connType) = resolveDiagnosticDeviceAndConnection(safety.activeDeviceName)
        return AudioDiagnosticMapper.map(
            AudioDiagnosticInput(
                isTrackLoaded = activeFormat != null,
                sourceContainer = activeFormat?.sourceContainer ?: "None",
                sourceCodec = activeFormat?.sourceCodec ?: "None",
                sourceSampleRateHz = activeFormat?.sourceSampleRateHz ?: 0,
                sourceBitDepth = activeFormat?.sourceBitDepth,
                sourceHeaderVerified = activeFormat?.sourceHeaderVerified ?: false,
                decodedPcmEncodingLabel = activeFormat?.encoding?.displayLabel ?: "None",
                decodedEffectiveBitDepth = activeFormat?.effectiveBitDepth ?: 0,
                decodedSampleRateHz = activeFormat?.sampleRateHz ?: 0,
                decodedChannelCount = activeFormat?.channelCount ?: 0,
                decoderName = activeFormat?.decoderName ?: "Idle",
                audioTrackSampleRateHz = audioTrackSampleRateHz,
                audioTrackEncodingLabel = audioTrackEncodingLabel,
                safetyMode = safety.mode,
                isHardwareVolumeVerified = safety.isHardwareVolumeVerified,
                apiLevel = Build.VERSION.SDK_INT,
                outputDeviceName = deviceName,
                outputConnectionType = connType,
                negotiatedMixerBehavior = activeNegotiatedMixerBehavior,
                negotiatedMixerSummary = activeNegotiatedMixerSummary,
                replayGainSummary = replayGainResolution.diagnosticLabel,
                isReplayGainModifyingSamples = replayGainResolution.isModifyingSamples,
            ),
        )
    }

    fun buildPendingTrackDiagnosticsSnapshot(
        safety: AudioSafetyState,
        container: String,
        codec: String,
        sampleRateHz: Int,
        bitDepth: Int?,
        replayGainResolution: ReplayGainResolution,
    ): AudioDiagnosticSnapshot {
        val (deviceName, connType) = resolveDiagnosticDeviceAndConnection(safety.activeDeviceName)
        return TrackTransitionStatePolicy.buildPendingTrackDiagnostics(
            safetyState = safety,
            apiLevel = Build.VERSION.SDK_INT,
            outputDeviceName = deviceName,
            outputConnectionType = connType,
            negotiatedMixerBehavior = activeNegotiatedMixerBehavior,
            negotiatedMixerSummary = activeNegotiatedMixerSummary,
            replayGainSummary = replayGainResolution.diagnosticLabel,
            container = container,
            codec = codec,
            sampleRateHz = sampleRateHz,
            bitDepth = bitDepth,
        )
    }

    fun detectPreferredOutputDevice(excludeDeviceId: Int? = null, ignoreNoisySuppression: Boolean = false): AudioDeviceInfo? {
        val effectiveExcludeId = effectiveExcludeDeviceId(excludeDeviceId, ignoreNoisySuppression)
        if (effectiveExcludeId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val routed = runCatching { audioManager.getAudioDevicesForAttributes(playbackAudioAttributes) }
                .getOrNull()
                ?.firstOrNull { it.isSink && it.id != effectiveExcludeId }
            if (routed != null) return routed
        }
        val outputs = runCatching { audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }
            .getOrDefault(emptyArray())
            .filter { it.isSink }
        replaceKnownOutputSinks(outputs)
        return rankPreferredOutputSink(outputs, effectiveExcludeId)
    }

    private fun selectPreferredFromCachedSinks(excludeDeviceId: Int? = null, ignoreNoisySuppression: Boolean = false): AudioDeviceInfo? {
        val effectiveExcludeId = effectiveExcludeDeviceId(excludeDeviceId, ignoreNoisySuppression)
        val snapshot = synchronized(sinksLock) { knownOutputSinks.values.toList() }
        return rankPreferredOutputSink(snapshot, effectiveExcludeId)
    }

    private fun effectiveExcludeDeviceId(excludeDeviceId: Int?, ignoreNoisySuppression: Boolean): Int? =
        excludeDeviceId ?: if (ignoreNoisySuppression) {
            null
        } else {
            routeEventFilter.suppressedNoisyExternalDeviceId(SystemClock.elapsedRealtime())
        }

    private fun rankPreferredOutputSink(sinks: List<AudioDeviceInfo>, excludeDeviceId: Int?): AudioDeviceInfo? {
        val outputs = sinks.filter { it.isSink && (excludeDeviceId == null || it.id != excludeDeviceId) }
        return outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
            ?: outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_DEVICE }
            ?: outputs.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
            }
            ?: outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
            ?: outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            ?: outputs.firstOrNull()
    }

    private fun replaceKnownOutputSinks(sinks: List<AudioDeviceInfo>) {
        synchronized(sinksLock) {
            knownOutputSinks.clear()
            for (device in sinks) {
                if (device.isSink) knownOutputSinks[device.id] = device
            }
        }
    }

    private fun addKnownOutputSinks(devices: Array<out AudioDeviceInfo>) {
        synchronized(sinksLock) {
            for (device in devices) {
                if (device.isSink) knownOutputSinks[device.id] = device
            }
        }
    }

    private fun removeKnownOutputSinks(devices: Array<out AudioDeviceInfo>) {
        synchronized(sinksLock) {
            for (device in devices) {
                knownOutputSinks.remove(device.id)
            }
        }
    }

    private fun removeCachedSinkById(deviceId: Int?) {
        if (deviceId == null) return
        synchronized(sinksLock) {
            knownOutputSinks.remove(deviceId)
        }
    }

    private fun removeCachedUsbSinks(): AudioDeviceInfo? {
        synchronized(sinksLock) {
            val usbIds = knownOutputSinks.values.filter { isUsbOutputDevice(it) }.map { it.id }
            var firstRemoved: AudioDeviceInfo? = null
            for (id in usbIds) {
                val removed = knownOutputSinks.remove(id)
                if (firstRemoved == null && removed != null) {
                    firstRemoved = removed
                }
            }
            return firstRemoved
        }
    }

    private fun scheduleAsyncOutputDeviceRefresh(isInitialSync: Boolean = false) {
        if (isReleased()) return
        if (isInitialSync) initialSyncNeeded.set(true)
        refreshPending.set(true)
        if (!refreshRunning.compareAndSet(false, true)) return
        runCatching {
            routeMonitorExecutor.execute {
                try {
                    while (!isReleased() && refreshPending.getAndSet(false)) {
                        runOutputDeviceRefreshOnce()
                    }
                } finally {
                    refreshRunning.set(false)
                    if (!isReleased() && refreshPending.get()) {
                        scheduleAsyncOutputDeviceRefresh()
                    }
                }
            }
        }.onFailure {
            refreshRunning.set(false)
        }
    }

    private fun runOutputDeviceRefreshOnce() {
        val outputs = runCatching { audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }
            .getOrDefault(emptyArray())
            .filter { it.isSink }
        replaceKnownOutputSinks(outputs)
        val shouldInitialize = initialSyncNeeded.getAndSet(false) && routeFilterInitialized.compareAndSet(false, true)
        val excludeId = if (shouldInitialize) null else routeEventFilter.suppressedNoisyExternalDeviceId(SystemClock.elapsedRealtime())
        val preferred = rankPreferredOutputSink(outputs, excludeId)
        if (isReleased()) return
        activeOutputDevice = preferred
        if (shouldInitialize) {
            routeEventFilter.initialize(preferred?.let(::toRouteDescriptor))
            if (preferred != null) {
                safetyController.syncInitialOutputDevice(
                    deviceName = resolveDeviceName(preferred),
                    isUsbAudio = isUsbOutputDevice(preferred),
                    isHardwareVolumeVerified = false,
                )
            }
            onDiagnosticsChanged()
        } else if (preferred != null) {
            routeEventFilter.syncObservedActiveDevice(toRouteDescriptor(preferred))
            safetyController.syncActiveOutputDeviceMetadata(
                deviceName = resolveDeviceName(preferred),
                isUsbAudio = isUsbOutputDevice(preferred),
            )
            onDiagnosticsChanged()
        }
    }

    fun isUsbOutputDevice(device: AudioDeviceInfo): Boolean =
        device.type == AudioDeviceInfo.TYPE_USB_DEVICE || device.type == AudioDeviceInfo.TYPE_USB_HEADSET

    fun isExternalOutputDevice(device: AudioDeviceInfo): Boolean {
        if (isUsbOutputDevice(device)) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (device.type == AudioDeviceInfo.TYPE_BLE_HEADSET || device.type == AudioDeviceInfo.TYPE_BLE_SPEAKER) {
                return true
            }
        }
        return device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
    }

    fun mapConnectionType(device: AudioDeviceInfo?): OutputConnectionType {
        if (device == null) return OutputConnectionType.INTERNAL_SPEAKER
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (device.type == AudioDeviceInfo.TYPE_BLE_HEADSET || device.type == AudioDeviceInfo.TYPE_BLE_SPEAKER) {
                return OutputConnectionType.BLUETOOTH
            }
        }
        return when (device.type) {
            AudioDeviceInfo.TYPE_USB_HEADSET -> OutputConnectionType.USB_HEADSET
            AudioDeviceInfo.TYPE_USB_DEVICE -> OutputConnectionType.USB_DEVICE
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES ->
                OutputConnectionType.WIRED_HEADSET
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO ->
                OutputConnectionType.BLUETOOTH
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE ->
                OutputConnectionType.INTERNAL_SPEAKER
            else -> OutputConnectionType.OTHER
        }
    }

    fun resolveDeviceName(device: AudioDeviceInfo): String {
        val product = device.productName?.toString()?.trim().orEmpty()
        return product.ifEmpty { mapConnectionType(device).displayName }
    }

    private fun toRouteDescriptor(device: AudioDeviceInfo): RouteDeviceDescriptor = RouteDeviceDescriptor(
        id = device.id,
        name = resolveDeviceName(device),
        isSink = device.isSink,
        isUsbOutput = device.isSink && isUsbOutputDevice(device),
        isExternalOutput = device.isSink && isExternalOutputDevice(device),
    )

    private fun negotiateMixerAttributesLocked(
        pcmFormat: DecodedPcmFormat,
        androidEncoding: Int,
        channelMask: Int,
        usbDevice: AudioDeviceInfo?,
        encodingNameResolver: (Int) -> String,
    ): MixerNegotiationResult {
        val safetyMode = safetyController.state().mode
        if (usbDevice == null) {
            clearPreferredMixerAttributesOnApi34(null)
            return if (safetyMode == AudioOutputMode.SAFE_VOLUME_MODE) {
                safeModeDefaultMixerResult()
            } else {
                MixerNegotiationResult(
                    behavior = NegotiatedMixerBehavior.UNSUPPORTED_NON_USB_OUTPUT,
                    summary = "Default System Mixer (No USB Audio Output Connected)",
                )
            }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            clearPreferredMixerAttributesOnApi34(null)
            return if (safetyMode == AudioOutputMode.SAFE_VOLUME_MODE) {
                safeModeDefaultMixerResult()
            } else {
                MixerNegotiationResult(
                    behavior = NegotiatedMixerBehavior.UNSUPPORTED_API_BELOW_34,
                    summary = "Clean Native AudioTrack (Android ${Build.VERSION.SDK_INT} < API 34)",
                )
            }
        }
        return negotiateApi34UsbMixer(
            safetyMode = safetyMode,
            pcmFormat = pcmFormat,
            androidEncoding = androidEncoding,
            channelMask = channelMask,
            usbDevice = usbDevice,
            encodingNameResolver = encodingNameResolver,
        )
    }

    private fun safeModeDefaultMixerResult(): MixerNegotiationResult = MixerNegotiationResult(
        behavior = NegotiatedMixerBehavior.NOT_APPLICABLE_SAFE_MODE,
        summary = "Default System Mixer (Safe Mode — Volume Active)",
    )

    private fun formatDefaultNativeModeNote(safetyMode: AudioOutputMode): String = if (safetyMode == AudioOutputMode.SAFE_VOLUME_MODE) {
        "Native Rate, Safe Mode Volume Active"
    } else {
        "Native Rate, Bit-Perfect Unavailable, Volume Active"
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun negotiateApi34UsbMixer(
        safetyMode: AudioOutputMode,
        pcmFormat: DecodedPcmFormat,
        androidEncoding: Int,
        channelMask: Int,
        usbDevice: AudioDeviceInfo,
        encodingNameResolver: (Int) -> String,
    ): MixerNegotiationResult = try {
        val supported = audioManager.getSupportedMixerAttributes(usbDevice)
        val candidates = supported.mapIndexed { idx, attr ->
            val fmt = attr.format
            UsbMixerCandidate(
                index = idx,
                isBitPerfect = attr.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT,
                sampleRateHz = fmt.sampleRate,
                androidEncoding = fmt.encoding,
                channelCount = fmt.channelCount,
                channelMask = fmt.channelMask,
            )
        }
        val plan = UsbMixerProfileSelector.buildNegotiationPlan(
            candidates = candidates,
            safetyMode = safetyMode,
            targetSampleRateHz = pcmFormat.sampleRateHz,
            targetAndroidEncoding = androidEncoding,
            targetChannelCount = pcmFormat.channelCount,
            targetChannelMask = channelMask,
        )
        val bpCount = candidates.count { it.isBitPerfect }
        val reused = tryReuseActiveMixerPreference(
            usbDevice = usbDevice,
            supported = supported,
            plan = plan,
            safetyMode = safetyMode,
            encodingNameResolver = encodingNameResolver,
        )
        if (reused != null) {
            reused.copy(supportedProfileCount = supported.size, bitPerfectProfileCount = bpCount)
        } else {
            clearPreferredMixerAttributesOnApi34(null)
            executeApi34NegotiationPlan(
                safetyMode = safetyMode,
                pcmFormat = pcmFormat,
                androidEncoding = androidEncoding,
                usbDevice = usbDevice,
                supported = supported,
                plan = plan,
                encodingNameResolver = encodingNameResolver,
            ).copy(
                supportedProfileCount = supported.size,
                bitPerfectProfileCount = bpCount,
            )
        }
    } catch (e: IllegalArgumentException) {
        fallbackApi34MixerError(safetyMode, usbDevice, e)
    } catch (e: IllegalStateException) {
        fallbackApi34MixerError(safetyMode, usbDevice, e)
    } catch (e: SecurityException) {
        fallbackApi34MixerError(safetyMode, usbDevice, e)
    } catch (e: UnsupportedOperationException) {
        fallbackApi34MixerError(safetyMode, usbDevice, e)
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun tryReuseActiveMixerPreference(
        usbDevice: AudioDeviceInfo,
        supported: List<AudioMixerAttributes>,
        plan: UsbMixerNegotiationPlan,
        safetyMode: AudioOutputMode,
        encodingNameResolver: (Int) -> String,
    ): MixerNegotiationResult? {
        val primaryIdx = plan.primaryCandidateIndex ?: return null
        val plannedBehavior = when (plan.primaryKind) {
            UsbMixerSelectionKind.BIT_PERFECT -> NegotiatedMixerBehavior.BIT_PERFECT
            UsbMixerSelectionKind.DEFAULT_NATIVE_RATE -> NegotiatedMixerBehavior.DEFAULT_NATIVE_RATE
            UsbMixerSelectionKind.NONE -> return null
        }
        val chosen = supported.getOrNull(primaryIdx) ?: return null
        val matchesTracked = mixerSessionTracker.matchesActive(
            deviceId = usbDevice.id,
            behavior = plannedBehavior,
            sampleRateHz = chosen.format.sampleRate,
            androidEncoding = chosen.format.encoding,
            channelCount = chosen.format.channelCount,
        )
        if (!matchesTracked) return null

        val verified = audioManager.getPreferredMixerAttributes(playbackAudioAttributes, usbDevice) ?: return null
        val expectedHalBehavior = if (plannedBehavior == NegotiatedMixerBehavior.BIT_PERFECT) {
            AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT
        } else {
            AudioMixerAttributes.MIXER_BEHAVIOR_DEFAULT
        }
        val verifiedMatches = verified.mixerBehavior == expectedHalBehavior &&
            verified.format.sampleRate == chosen.format.sampleRate &&
            verified.format.encoding == chosen.format.encoding
        if (!verifiedMatches) return null

        lastConfiguredMixerDevice = usbDevice
        val verifiedRate = AudioFormatFormatter.formatSampleRateHz(verified.format.sampleRate)
        val verifiedEnc = encodingNameResolver(verified.format.encoding)
        val summary = if (plannedBehavior == NegotiatedMixerBehavior.BIT_PERFECT) {
            "MIXER_BEHAVIOR_BIT_PERFECT ($verifiedRate, $verifiedEnc)"
        } else {
            "MIXER_BEHAVIOR_DEFAULT ($verifiedRate, $verifiedEnc — ${formatDefaultNativeModeNote(safetyMode)})"
        }
        return MixerNegotiationResult(behavior = plannedBehavior, summary = summary)
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun executeApi34NegotiationPlan(
        safetyMode: AudioOutputMode,
        pcmFormat: DecodedPcmFormat,
        androidEncoding: Int,
        usbDevice: AudioDeviceInfo,
        supported: List<AudioMixerAttributes>,
        plan: UsbMixerNegotiationPlan,
        encodingNameResolver: (Int) -> String,
    ): MixerNegotiationResult {
        val primaryIdx = plan.primaryCandidateIndex
        return when (plan.primaryKind) {
            UsbMixerSelectionKind.BIT_PERFECT -> {
                val bpResult = applyAndVerifyBitPerfectAttribute(usbDevice, supported[primaryIdx!!], encodingNameResolver)
                if (bpResult.behavior == NegotiatedMixerBehavior.BIT_PERFECT) {
                    bpResult
                } else {
                    val fallbackIdx = plan.fallbackDefaultCandidateIndex
                    if (fallbackIdx != null) {
                        applyAndVerifyDefaultNativeRateAttribute(
                            usbDevice = usbDevice,
                            attribute = supported[fallbackIdx],
                            targetSampleRateHz = pcmFormat.sampleRateHz,
                            safetyMode = safetyMode,
                            encodingNameResolver = encodingNameResolver,
                        ) ?: bpResult
                    } else {
                        bpResult
                    }
                }
            }
            UsbMixerSelectionKind.DEFAULT_NATIVE_RATE -> {
                applyAndVerifyDefaultNativeRateAttribute(
                    usbDevice = usbDevice,
                    attribute = supported[primaryIdx!!],
                    targetSampleRateHz = pcmFormat.sampleRateHz,
                    safetyMode = safetyMode,
                    encodingNameResolver = encodingNameResolver,
                )
                    ?: buildFallbackWhenNoProfileConfirmed(
                        safetyMode = safetyMode,
                        supported = supported,
                        sampleRateHz = pcmFormat.sampleRateHz,
                        androidEncoding = androidEncoding,
                        encodingNameResolver = encodingNameResolver,
                        nativeRateProfileRejected = true,
                    )
            }
            UsbMixerSelectionKind.NONE -> {
                buildFallbackWhenNoProfileConfirmed(safetyMode, supported, pcmFormat.sampleRateHz, androidEncoding, encodingNameResolver)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun buildFallbackWhenNoProfileConfirmed(
        safetyMode: AudioOutputMode,
        supported: List<AudioMixerAttributes>,
        sampleRateHz: Int,
        androidEncoding: Int,
        encodingNameResolver: (Int) -> String,
        nativeRateProfileRejected: Boolean = false,
    ): MixerNegotiationResult {
        if (safetyMode == AudioOutputMode.SAFE_VOLUME_MODE) {
            return safeModeDefaultMixerResult()
        }
        val targetRate = AudioFormatFormatter.formatSampleRateHz(sampleRateHz)
        if (nativeRateProfileRejected) {
            return MixerNegotiationResult(
                behavior = NegotiatedMixerBehavior.DEFAULT_MIXER_FALLBACK,
                summary = "Default System Mixer (USB HAL did not confirm native-rate default profile for $targetRate)",
            )
        }
        val bitPerfectCandidates = supported.filter { it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT }
        if (bitPerfectCandidates.isEmpty()) {
            return MixerNegotiationResult(
                behavior = NegotiatedMixerBehavior.DEFAULT_MIXER_FALLBACK,
                summary = "Default System Mixer (USB HAL reported 0 matching bit-perfect or native-rate profiles)",
            )
        }
        val reported = bitPerfectCandidates
            .take(6)
            .joinToString(", ") { candidate ->
                val fmt = candidate.format
                "${AudioFormatFormatter.formatSampleRateHz(fmt.sampleRate)}/${encodingNameResolver(fmt.encoding)}"
            }
        val targetEnc = encodingNameResolver(androidEncoding)
        return MixerNegotiationResult(
            behavior = NegotiatedMixerBehavior.DEFAULT_MIXER_FALLBACK,
            summary = "Default System Mixer (No matching USB profile for $targetRate / $targetEnc; HAL bit-perfect: $reported)",
        )
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun applyAndVerifyBitPerfectAttribute(
        usbDevice: AudioDeviceInfo,
        exactMatch: AudioMixerAttributes,
        encodingNameResolver: (Int) -> String,
    ): MixerNegotiationResult {
        val applied = audioManager.setPreferredMixerAttributes(
            playbackAudioAttributes,
            usbDevice,
            exactMatch,
        )
        if (!applied) {
            return MixerNegotiationResult(
                behavior = NegotiatedMixerBehavior.DEFAULT_MIXER_FALLBACK,
                summary = "Default System Mixer (setPreferredMixerAttributes returned false)",
            )
        }
        lastConfiguredMixerDevice = usbDevice
        mixerSessionTracker.recordApplied(
            AppliedUsbMixerProfile(
                deviceId = usbDevice.id,
                behavior = NegotiatedMixerBehavior.BIT_PERFECT,
                sampleRateHz = exactMatch.format.sampleRate,
                androidEncoding = exactMatch.format.encoding,
                channelCount = exactMatch.format.channelCount,
            ),
        )

        val verified = audioManager.getPreferredMixerAttributes(playbackAudioAttributes, usbDevice)
        return if (verified != null && verified.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT) {
            val verifiedRate = AudioFormatFormatter.formatSampleRateHz(verified.format.sampleRate)
            val verifiedEnc = encodingNameResolver(verified.format.encoding)
            MixerNegotiationResult(
                behavior = NegotiatedMixerBehavior.BIT_PERFECT,
                summary = "MIXER_BEHAVIOR_BIT_PERFECT ($verifiedRate, $verifiedEnc)",
            )
        } else {
            clearPreferredMixerAttributesOnApi34(usbDevice)
            MixerNegotiationResult(
                behavior = NegotiatedMixerBehavior.DEFAULT_MIXER_FALLBACK,
                summary = "Default System Mixer (HAL did not confirm bit-perfect preference)",
            )
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun applyAndVerifyDefaultNativeRateAttribute(
        usbDevice: AudioDeviceInfo,
        attribute: AudioMixerAttributes,
        targetSampleRateHz: Int,
        safetyMode: AudioOutputMode,
        encodingNameResolver: (Int) -> String,
    ): MixerNegotiationResult? {
        // Hard safety guard: never accidentally apply MIXER_BEHAVIOR_BIT_PERFECT through the default-rate helper.
        if (attribute.mixerBehavior != AudioMixerAttributes.MIXER_BEHAVIOR_DEFAULT) return null
        val applied = audioManager.setPreferredMixerAttributes(
            playbackAudioAttributes,
            usbDevice,
            attribute,
        )
        if (!applied) return null
        lastConfiguredMixerDevice = usbDevice
        mixerSessionTracker.recordApplied(
            AppliedUsbMixerProfile(
                deviceId = usbDevice.id,
                behavior = NegotiatedMixerBehavior.DEFAULT_NATIVE_RATE,
                sampleRateHz = attribute.format.sampleRate,
                androidEncoding = attribute.format.encoding,
                channelCount = attribute.format.channelCount,
            ),
        )

        val verified = audioManager.getPreferredMixerAttributes(playbackAudioAttributes, usbDevice)
        val confirmedDefaultNativeRate = verified != null &&
            verified.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_DEFAULT &&
            verified.format.sampleRate == targetSampleRateHz
        return if (confirmedDefaultNativeRate) {
            val verifiedRate = AudioFormatFormatter.formatSampleRateHz(verified.format.sampleRate)
            val verifiedEnc = encodingNameResolver(verified.format.encoding)
            val modeNote = formatDefaultNativeModeNote(safetyMode)
            MixerNegotiationResult(
                behavior = NegotiatedMixerBehavior.DEFAULT_NATIVE_RATE,
                summary = "MIXER_BEHAVIOR_DEFAULT ($verifiedRate, $verifiedEnc — $modeNote)",
            )
        } else {
            clearPreferredMixerAttributesOnApi34(usbDevice)
            null
        }
    }

    private fun fallbackApi34MixerError(
        safetyMode: AudioOutputMode,
        usbDevice: AudioDeviceInfo,
        error: RuntimeException,
    ): MixerNegotiationResult {
        diagnosticLogger?.logError("USB_MIXER_ERROR") {
            linkedMapOf(
                "mode" to safetyMode.name,
                "errorType" to error.javaClass.simpleName,
                "msg" to (error.message ?: "unknown"),
                "api" to Build.VERSION.SDK_INT,
            )
        }
        clearPreferredMixerAttributesOnApi34(usbDevice, forceClearExplicit = mixerSessionTracker.hasActivePreference)
        return if (safetyMode == AudioOutputMode.SAFE_VOLUME_MODE) {
            safeModeDefaultMixerResult()
        } else {
            MixerNegotiationResult(
                behavior = NegotiatedMixerBehavior.DEFAULT_MIXER_FALLBACK,
                summary = "Default System Mixer (API 34 negotiation error: ${error.javaClass.simpleName})",
            )
        }
    }

    companion object {
        private const val TAG = "BitPerfectAudio"
    }
}
