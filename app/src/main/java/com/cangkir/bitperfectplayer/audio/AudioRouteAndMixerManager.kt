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
) {
    private data class MixerNegotiationResult(val behavior: NegotiatedMixerBehavior, val summary: String)

    @Volatile
    var activeOutputDevice: AudioDeviceInfo? = null
        private set

    @Volatile
    private var lastConfiguredMixerDevice: AudioDeviceInfo? = null

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
        return hasAudioFocus
    }

    fun abandonAudioFocusLocked() {
        if (!hasAudioFocus) return
        runCatching { audioManager.abandonAudioFocusRequest(audioFocusRequest) }
        hasAudioFocus = false
    }

    /**
     * Clears any stale API 34+ preferred mixer attributes, detects the current preferred output
     * device, negotiates `MIXER_BEHAVIOR_BIT_PERFECT` when opted in on a USB audio sink, and
     * returns the active USB [AudioDeviceInfo] (or `null` for non-USB routes).
     */
    fun prepareOutputDeviceAndNegotiateMixerLocked(
        pcmFormat: DecodedPcmFormat,
        androidEncoding: Int,
        channelMask: Int,
        encodingNameResolver: (Int) -> String,
    ): AudioDeviceInfo? {
        clearPreferredMixerAttributesOnApi34(null)
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
        return usbDevice
    }

    /**
     * Mandatory Android 14+ cleanup: clears any previously set bit-perfect mixer preference so
     * system audio routing and other apps are never left stuck in fixed-volume bit-perfect mode.
     */
    fun clearPreferredMixerAttributesOnApi34(explicitDevice: AudioDeviceInfo?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val targets = linkedSetOf<AudioDeviceInfo>()
        explicitDevice?.let { targets.add(it) }
        lastConfiguredMixerDevice?.let { targets.add(it) }
        activeOutputDevice?.takeIf { isUsbOutputDevice(it) }?.let { targets.add(it) }

        for (device in targets) {
            runCatching {
                audioManager.clearPreferredMixerAttributes(playbackAudioAttributes, device)
            }
        }
        lastConfiguredMixerDevice = null
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
        if (safetyMode == AudioOutputMode.SAFE_VOLUME_MODE) {
            return MixerNegotiationResult(
                behavior = NegotiatedMixerBehavior.NOT_APPLICABLE_SAFE_MODE,
                summary = "Default System Mixer (Safe Mode — Volume Active)",
            )
        }
        if (usbDevice == null) {
            return MixerNegotiationResult(
                behavior = NegotiatedMixerBehavior.UNSUPPORTED_NON_USB_OUTPUT,
                summary = "Default System Mixer (No USB Audio Output Connected)",
            )
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return MixerNegotiationResult(
                behavior = NegotiatedMixerBehavior.UNSUPPORTED_API_BELOW_34,
                summary = "Clean Native AudioTrack (Android ${Build.VERSION.SDK_INT} < API 34)",
            )
        }
        return negotiateApi34BitPerfectMixer(pcmFormat, androidEncoding, channelMask, usbDevice, encodingNameResolver)
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun negotiateApi34BitPerfectMixer(
        pcmFormat: DecodedPcmFormat,
        androidEncoding: Int,
        channelMask: Int,
        usbDevice: AudioDeviceInfo,
        encodingNameResolver: (Int) -> String,
    ): MixerNegotiationResult = try {
        val bitPerfectCandidates = audioManager.getSupportedMixerAttributes(usbDevice).filter {
            it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT
        }
        if (bitPerfectCandidates.isEmpty()) {
            MixerNegotiationResult(
                behavior = NegotiatedMixerBehavior.DEFAULT_MIXER_FALLBACK,
                summary = "Default System Mixer (USB HAL reported 0 bit-perfect profiles)",
            )
        } else {
            // Prefer exact sample rate + encoding + channel mask match.
            val exactMatch = bitPerfectCandidates.firstOrNull { candidate ->
                val fmt = candidate.format
                fmt.sampleRate == pcmFormat.sampleRateHz &&
                    fmt.encoding == androidEncoding &&
                    (fmt.channelMask == channelMask || fmt.channelCount == pcmFormat.channelCount)
            }
            if (exactMatch == null) {
                buildNoExactMatchResult(
                    bitPerfectCandidates = bitPerfectCandidates,
                    sampleRateHz = pcmFormat.sampleRateHz,
                    androidEncoding = androidEncoding,
                    encodingNameResolver = encodingNameResolver,
                )
            } else {
                applyAndVerifyBitPerfectAttribute(usbDevice, exactMatch, encodingNameResolver)
            }
        }
    } catch (e: IllegalArgumentException) {
        fallbackApi34MixerError(usbDevice, e)
    } catch (e: IllegalStateException) {
        fallbackApi34MixerError(usbDevice, e)
    } catch (e: SecurityException) {
        fallbackApi34MixerError(usbDevice, e)
    } catch (e: UnsupportedOperationException) {
        fallbackApi34MixerError(usbDevice, e)
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun buildNoExactMatchResult(
        bitPerfectCandidates: List<AudioMixerAttributes>,
        sampleRateHz: Int,
        androidEncoding: Int,
        encodingNameResolver: (Int) -> String,
    ): MixerNegotiationResult {
        val reported = bitPerfectCandidates
            .take(6)
            .joinToString(", ") { candidate ->
                val fmt = candidate.format
                "${AudioFormatFormatter.formatSampleRateHz(fmt.sampleRate)}/${encodingNameResolver(fmt.encoding)}"
            }
        val targetRate = AudioFormatFormatter.formatSampleRateHz(sampleRateHz)
        val targetEnc = encodingNameResolver(androidEncoding)
        return MixerNegotiationResult(
            behavior = NegotiatedMixerBehavior.DEFAULT_MIXER_FALLBACK,
            summary = "Default System Mixer (No exact bit-perfect match for $targetRate / $targetEnc; HAL supports: $reported)",
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

        val verified = audioManager.getPreferredMixerAttributes(playbackAudioAttributes, usbDevice)
        return if (verified != null && verified.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT) {
            lastConfiguredMixerDevice = usbDevice
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

    private fun fallbackApi34MixerError(usbDevice: AudioDeviceInfo, error: RuntimeException): MixerNegotiationResult {
        clearPreferredMixerAttributesOnApi34(usbDevice)
        return MixerNegotiationResult(
            behavior = NegotiatedMixerBehavior.DEFAULT_MIXER_FALLBACK,
            summary = "Default System Mixer (API 34 negotiation error: ${error.javaClass.simpleName})",
        )
    }
}
