---
trigger: always_on
description: "Apply whenever designing, implementing, or reviewing audio playback, AudioTrack, decoders, WAV/FLAC handling, volume safety, USB routing, Foreground Service playback, or audio diagnostics."
---

# Audio Architecture & Hearing Safety Constraints

1. **Runtime-Adaptive Audio Path (API 26–37 Compatible):**
   - Guard all Android 14+ (`API 34+`) mixer attribute calls with `Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE`.
   - When Bit-Perfect Mode is explicitly opted in on API 34+ with an active USB audio output device, query `AudioManager.getSupportedMixerAttributes(device)` for `AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT` matching PCM format/sample rate and apply via `setPreferredMixerAttributes()`.
   - **Mandatory Cleanup:** Always call `AudioManager.clearPreferredMixerAttributes(audioAttributes, device)` on API 34+ when returning to Safe Mode, stopping/releasing playback, disconnecting USB, or changing track sample rates so bit-perfect mixer state never leaks onto the device.
   - When unsupported, on non-USB outputs, on API < 34, or in Safe Mode, fall back transparently to the cleanest native `AudioTrack` path (zero EQ, DSP, or crossfade) and label it honestly (never claim bit-perfect).
   - **Safe-Mode-Only ReplayGain:** Optional local ReplayGain tag attenuation/preamp is permitted **exclusively in `SAFE_VOLUME_MODE`** (default `OFF`) and MUST be **automatically and unconditionally bypassed (`gain = 1.0f`, zero PCM sample modification)** whenever `BIT_PERFECT_OPT_IN` mode is active.
   - **Prohibited:** Do not build a custom user-space USB Audio Class (UAC1/UAC2) isochronous driver with `UsbManager` + C/C++.
2. **Hearing Safety (Mandatory for High-Sensitivity 107 dB/mW IEMs):**
   - Default mode is **Safe Mode (Volume Active)** on fresh install and whenever any USB device is connected.
   - **Bit-Perfect Mode is explicit opt-in only** and must never auto-activate on USB plug-in.
   - Treat Bit-Perfect Mode as potentially fixed-volume (100% / 0 dBFS) whenever `isHardwareVolumeVerified == false`: require an explicit **Hearing Safety Warning Dialog** confirmation before activation.
   - Always trigger immediate **auto-pause** on `AudioManager.ACTION_AUDIO_BECOMING_NOISY`, USB disconnect/reconnect, or output route change, and keep a quick Pause control accessible.
3. **5-Format MVP Decoder & API-Level Rules:**
   - Support **FLAC (16/24-bit), WAV (PCM 16/24-bit), MP3, M4A (AAC), and OGG (Vorbis/Opus)**. Exclude DSD, ALAC, APE, WavPack, and `.cue`.
   - Implement a direct pure-Kotlin RIFF/WAV header parser and PCM reader so 24-bit WAV is never truncated to 16-bit.
   - **API 31 PCM Guard (`minSdk = 26`):** Guard `AudioFormat.ENCODING_PCM_24BIT_PACKED` and `AudioFormat.ENCODING_PCM_32BIT` with `Build.VERSION.SDK_INT >= Build.VERSION_CODES.S`. On API 26–30, unpack 24-bit integer WAV PCM into 32-bit float (`AudioFormat.ENCODING_PCM_FLOAT`, preserving full 24-bit mantissa precision without truncation).
   - Use Android native `MediaExtractor` + `MediaCodec` for FLAC/MP3/M4A/OGG; inspect actual output PCM encoding at runtime and report it honestly if the OS decoder down-converts 24-bit to 16-bit.
4. **Foreground Service Compliance (`targetSdk = 37`):**
   - Declare `android:foregroundServiceType="mediaPlayback"` in `AndroidManifest.xml` and invoke `ServiceCompat.startForeground(...)` passing `ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK` on API 29+.
5. **6-Stage Diagnostics, Pure Domain & Claim Boundaries:**
   - Keep `PlaybackQueueManager`, `AudioSafetyController`, `AudioDiagnosticMapper`, `WavHeaderParser`, `GaplessTransitionPolicy`, `ReplayGainTagParser`, `ReplayGainProcessor`, and `PlayCountTracker` in pure Kotlin (no `android.*` imports) for deterministic JVM unit tests.
   - Separate the 6 audio stages (source file, decoded PCM, app-to-framework, OS policy/mixer/resampler, USB output/DAC path, DAC analog conversion), reporting ReplayGain state honestly in Stage 3 (`OFF`, active Safe-Mode dB, or `Bypassed (Bit-Perfect Active)`).
   - Tag every diagnostic metric as `VERIFIED`, `INDICATED`, or `UNVERIFIABLE`. Internal USB-C DAC firmware processing and analog output must always remain `UNVERIFIABLE` with the maximum claim notice from @SPEC.md Section 3.5.