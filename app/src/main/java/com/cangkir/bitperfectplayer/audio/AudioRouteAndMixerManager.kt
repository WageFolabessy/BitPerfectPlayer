package com.cangkir.bitperfectplayer.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import android.os.Handler
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.cangkir.bitperfectplayer.diagnostics.LocalDiagnosticLogger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Internal collaborator managing Android audio output device routing, `AudioDeviceCallback`
 * (including cold-start initial-callback suppression), `ACTION_AUDIO_BECOMING_NOISY` broadcast
 * monitoring, `AudioFocusRequest` lifecycle (with Bit-Perfect ducking isolation), and Android 14+
 * (`API 34+`) `MIXER_BEHAVIOR_BIT_PERFECT` negotiation + mandatory `clearPreferredMixerAttributes`
 * cleanup.
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
) {
    private data class MixerNegotiationResult(
        val behavior: NegotiatedMixerBehavior,
        val summary: String,
        val supportedProfileCount: Int = 0,
        val bitPerfectProfileCount: Int = 0,
    )

    @Volatile
    var activeOutputDevice: AudioDeviceInfo? = null
        private set

    @Volatile
    private var lastConfiguredMixerDevice: AudioDeviceInfo? = null

    private val mixerSessionTracker = PreferredMixerSessionTracker()

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

    private var receiversRegistered: Boolean = false
    private val initialDeviceCallbackPending = AtomicBoolean(true)

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
                resumeAfterTransientFocusLoss = isCurrentlyPlaying()
                onFocusLossPause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (safetyController.state().mode == AudioOutputMode.BIT_PERFECT_OPT_IN) {
                    // Never digitally attenuate PCM samples in Bit-Perfect Mode; pause cleanly instead.
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
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                val cmd = safetyController.onBecomingNoisy()
                onSafetyCommand(cmd)
            }
        }
    }

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            val addedSinks = addedDevices.filter { it.isSink }
            if (addedSinks.isEmpty()) return
            val usbAdded = addedSinks.firstOrNull { isUsbOutputDevice(it) }
            val chosen = usbAdded ?: detectPreferredOutputDevice() ?: addedSinks.first()
            activeOutputDevice = chosen
            // AudioManager delivers currently connected devices immediately upon registration.
            // Sync initial device metadata in Safe Mode without firing a spurious cold-start auto-pause banner.
            if (initialDeviceCallbackPending.compareAndSet(true, false)) {
                safetyController.syncInitialOutputDevice(
                    deviceName = resolveDeviceName(chosen),
                    isUsbAudio = isUsbOutputDevice(chosen),
                    isHardwareVolumeVerified = false,
                )
                onDiagnosticsChanged()
                return
            }
            val cmd = safetyController.onAudioDeviceConnected(
                deviceName = resolveDeviceName(chosen),
                isUsbAudio = isUsbOutputDevice(chosen),
                isHardwareVolumeVerified = false, // Conservative assumption: never assume UAC hardware volume
            )
            onSafetyCommand(cmd)
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            val removedSinks = removedDevices.filter { it.isSink }
            if (removedSinks.isEmpty()) return
            val removedUsb = removedSinks.firstOrNull { isUsbOutputDevice(it) }
            val fallback = detectPreferredOutputDevice()
            activeOutputDevice = fallback

            if (removedUsb != null) {
                clearPreferredMixerAttributesOnApi34(removedUsb)
                val cmd = safetyController.onUsbDisconnected(
                    fallbackDeviceName = fallback?.let(::resolveDeviceName) ?: "Internal Speaker",
                )
                onSafetyCommand(cmd)
            } else {
                val cmd = safetyController.onOutputRouteChanged(
                    deviceName = fallback?.let(::resolveDeviceName) ?: "Internal Speaker",
                    isUsbAudio = fallback?.let(::isUsbOutputDevice) == true,
                    isHardwareVolumeVerified = false,
                )
                onSafetyCommand(cmd)
            }
        }
    }

    fun initializeRouteMonitoring() {
        val initialDevice = detectPreferredOutputDevice()
        activeOutputDevice = initialDevice
        if (initialDevice != null) {
            safetyController.syncInitialOutputDevice(
                deviceName = resolveDeviceName(initialDevice),
                isUsbAudio = isUsbOutputDevice(initialDevice),
                isHardwareVolumeVerified = false,
            )
        }
        registerAudioMonitors()
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
    }

    fun unregisterAudioMonitors() {
        if (!receiversRegistered) return
        receiversRegistered = false
        runCatching { audioManager.unregisterAudioDeviceCallback(audioDeviceCallback) }
        runCatching { appContext.unregisterReceiver(becomingNoisyReceiver) }
    }

    fun requestAudioFocusLocked(): Boolean {
        if (hasAudioFocus) return true
        val res = audioManager.requestAudioFocus(audioFocusRequest)
        hasAudioFocus = (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        if (!hasAudioFocus) {
            diagnosticLogger?.logError("AUDIOFOCUS_REQUEST_FAILED") {
                mapOf("result" to res, "mode" to safetyController.state().mode.name)
            }
        }
        return hasAudioFocus
    }

    fun abandonAudioFocusLocked() {
        if (!hasAudioFocus) return
        runCatching { audioManager.abandonAudioFocusRequest(audioFocusRequest) }
        hasAudioFocus = false
    }

    /**
     * Detects the current preferred output device, negotiates `MIXER_BEHAVIOR_BIT_PERFECT` (or
     * native-rate `MIXER_BEHAVIOR_DEFAULT`) when applicable on a USB audio sink while skipping
     * redundant `clearPreferredMixerAttributes()` Binder calls when no preference is active or an
     * identical preference is already confirmed, and returns the active USB [AudioDeviceInfo].
     */
    fun prepareOutputDeviceAndNegotiateMixerLocked(
        pcmFormat: DecodedPcmFormat,
        androidEncoding: Int,
        channelMask: Int,
        encodingNameResolver: (Int) -> String,
    ): AudioDeviceInfo? {
        val currentDevice = detectPreferredOutputDevice().also { activeOutputDevice = it }
        val usbDevice = currentDevice?.takeIf { isUsbOutputDevice(it) }
        val negotiation = negotiateMixerAttributesLocked(
            pcmFormat = pcmFormat,
            androidEncoding = androidEncoding,
            channelMask = channelMask,
            usbDevice = usbDevice,
            encodingNameResolver = encodingNameResolver,
        )
        activeNegotiatedMixerBehavior = negotiation.behavior
        activeNegotiatedMixerSummary = negotiation.summary
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
     * Conditional Android 14+ cleanup: clears a previously applied preferred mixer attribute via
     * [PreferredMixerSessionTracker] (skipping redundant IPC when no preference was applied so
     * Safe Mode track transitions avoid `clearPreferredMixerAttributes` Binder overhead).
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
                    runCatching {
                        audioManager.clearPreferredMixerAttributes(playbackAudioAttributes, targetDevice)
                    }
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
        val device = activeOutputDevice ?: detectPreferredOutputDevice()
        val connType = mapConnectionType(device)
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

    fun detectPreferredOutputDevice(): AudioDeviceInfo? {
        val outputs = runCatching { audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }
            .getOrDefault(emptyArray())
        return outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
            ?: outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_DEVICE }
            ?: outputs.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
            }
            ?: outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
            ?: outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            ?: outputs.firstOrNull()
    }

    fun isUsbOutputDevice(device: AudioDeviceInfo): Boolean =
        device.type == AudioDeviceInfo.TYPE_USB_DEVICE || device.type == AudioDeviceInfo.TYPE_USB_HEADSET

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
}
