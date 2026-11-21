# PROJECT SPECIFICATION: Offline Bit-Perfect Music Player for Android (`BitPerfectPlayer`)

## 1. Role, Objective, and Execution Scope

Act as a **Senior Android Engineer, Digital Audio Engineer, and Software Architect** experienced in native Android development (Kotlin, Jetpack Compose), digital audio processing (PCM, Android Audio Framework/HAL), and reliable local-first systems.

**Project Objective:** Build a **100% offline** Android music player on top of the existing fresh Android Studio project (`com.cangkir.bitperfectplayer`) in this repository, delivering complete production code, passing JVM unit tests, clean lint checks, and a cleanly compiled debug APK.

**Core Quality Pillars:**
1. Cleanest achievable audio reproduction path toward bit-perfect output within Android 14+ API and runtime hardware limits.
2. Reliable local music playback that complies with Scoped Storage and protects user hearing.
3. 100% offline lyrics system (`.lrc` and manual input) bound persistently via deterministic content fingerprinting.
4. Simple, fast, responsive native Jetpack Compose UI with a consistent Dark Theme.

**Autonomous Environment Constraints:**
- There is no Phase 1 approval gate. All core architectural decisions are locked in Section 3 below.
- Operational invariants are enforced by `AGENTS.md` and `.agents/rules/*.md`; execution protocols are defined in `.agents/skills/*/SKILL.md`.
- There is no access to physical hardware, USB peripherals, or ADB. All work and verification are strictly software-only (Gradle compilation, JVM unit tests, Android Lint, and manifest inspection).

---

## 2. Project Configuration, Repository Layout & Target Hardware Profile

### Existing Gradle Baseline & Layout (Must Be Preserved)
- **Namespace & Application ID:** `com.cangkir.bitperfectplayer`
- **SDK Levels:** `minSdk = 26`, `compileSdk = 37` (`compileSdk { version = release(37) }`), `targetSdk = 37`
- **Java Compatibility:** `JavaVersion.VERSION_11` (respecting `gradle/gradle-daemon-jvm.properties`)
- **UI Stack:** Kotlin + Jetpack Compose (Material 3) managed via Gradle Version Catalog (`gradle/libs.versions.toml`)
- **Plugins & Optimization:** Preserve `libs.plugins.android.application`, `libs.plugins.kotlin.compose`, the `release { optimization { ... } }` block in `app/build.gradle.kts`, `app/src/main/keepRules/rules.keep`, `res/xml/backup_rules.xml`, and `res/xml/data_extraction_rules.xml`.
- **Source & Test Roots:**
  - Production code: `app/src/main/java/com/cangkir/bitperfectplayer/` (already contains `MainActivity.kt` and `ui/theme/{Color.kt, Theme.kt, Type.kt}`)
  - JVM unit tests: `app/src/test/java/com/cangkir/bitperfectplayer/` (already contains `ExampleUnitTest.kt`)
  - Instrumented tests: `app/src/androidTest/java/com/cangkir/bitperfectplayer/` (keep intact; do not attempt to execute on device/emulator in cloud)

### Target Hardware Profile (Design Reference Only — Not Directly Testable in Cloud)
- **Target Smartphone:** POCO F4, 6 GB RAM / 128 GB storage, Xiaomi HyperOS 1.0.9.0.ULMIDXM, **Android 14 (API 34)**, non-root.
- **Target Audio Device:** KZ EDX Pro 2 Single DD In-Ear Earphone (USB-C cable variant) — high sensitivity **107 dB/mW at 24 ohms**, 24-ohm impedance.
- **Conservative Hardware Assumption:** Never assume the internal DAC chip model or assume hardware volume control (*UAC Hardware Volume*) is supported. All audio path and volume capabilities must be detected defensively at runtime.

---

## 3. Audio Architecture, Hearing Safety, and Verification Rules (LOCKED)

### 3.1 Bit-Perfect Definition and 6-Stage Audio Path
Bit-perfect reproduction means passing the digital PCM stream to audio output without unintended sample value modification—no OS/app resampling, no digital volume attenuation altering PCM samples, no EQ, normalization, crossfade, DSP, or destructive bit-depth truncation.

The app and its diagnostic model must explicitly separate 6 audio path stages:
1. Unmodified lossless/lossy source file.
2. Decoding into a PCM stream.
3. Audio path from the application to the Android Audio Framework.
4. OS audio policy, mixer, and resampling stages (Android Audio Framework / HAL).
5. Output path to the USB Audio device and DAC.
6. Analog conversion stage inside the DAC.

Never equate FLAC/lossless file playback capability with verified bit-perfect output.

### 3.2 MVP Audio Architecture (Runtime-Adaptive — Locked)
1. **Priority Path (API 34+ / Android 14+ on USB Output):**
   - Guard API 34+ calls cleanly with `Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE` to preserve `minSdk = 26` compatibility.
   - Use `AudioTrack` paired with `AudioManager.getSupportedMixerAttributes()` and `AudioManager.setPreferredMixerAttributes()` requesting `AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT` when the active USB output device exposes support at runtime.
   - Always call `AudioManager.clearPreferredMixerAttributes(audioAttributes, device)` on API 34+ when switching back to Safe Mode, stopping/releasing playback, disconnecting USB, or changing track sample rates so bit-perfect mixer preferences never leak across sessions.
2. **Transparent Fallback Path:**
   - If the OS/HAL or USB-C DAC does not expose `MIXER_BEHAVIOR_BIT_PERFECT` (or when playing on non-USB outputs / API < 34), automatically fall back to the cleanest native `AudioTrack` path (zero EQ, DSP, or crossfade; matching native sample rate when supported; optional ReplayGain permitted strictly in `SAFE_VOLUME_MODE` and unconditionally bypassed in `BIT_PERFECT_OPT_IN` mode).
   - Display an honest UI status label (e.g., *"Clean Native Path — Bit-Perfect Not Supported by OS/Device"*), never a false bit-perfect claim.
3. **Out of MVP Scope (Prohibited):**
   - Do **not** build a custom user-space USB Audio Class (UAC1/UAC2) isochronous driver from scratch using `UsbManager` + C/C++.

### 3.3 Hearing Safety and Volume Control (Mandatory & Locked)
Because the target IEM has high sensitivity (107 dB/mW at 24 ohms), bypassing digital volume without verified hardware volume control can output full scale (0 dBFS / 100%) and risk hearing damage.

Mandatory rules:
1. **Default Mode = Safe Mode (Volume Active):** System volume control works normally on fresh install and whenever a new USB audio device is connected. Optional local ReplayGain (`OFF` by default) is permitted exclusively in `SAFE_VOLUME_MODE`.
2. **Bit-Perfect Mode = Explicit Opt-In:** Never enabled automatically on USB plug-in. Disables EQ, DSP, normalization, crossfade, and automatically bypasses ReplayGain (`OFF`, gain = `1.0f`) so PCM samples pass untouched.
3. **Fixed-Volume Safeguards:** Treat Bit-Perfect Mode as potentially fixed-volume (100%) unless hardware volume support is explicitly verified at runtime:
   - Must display a **hearing safety warning dialog** requiring explicit user confirmation before activating Bit-Perfect Mode.
   - Must keep a **quick Pause control** immediately accessible.
   - Must **auto-pause** immediately upon `AudioManager.ACTION_AUDIO_BECOMING_NOISY`, USB disconnect/reconnect, or output route change.

### 3.4 MVP Audio Formats & Decoder Strategy (Locked)
- **Required MVP Formats:**
  - Lossless: **FLAC** (16-bit & 24-bit), **WAV** (PCM 16-bit & 24-bit).
  - Lossy: **MP3**, **M4A (AAC)**, **OGG (Vorbis/Opus)**.
- **Decoder Strategy:**
  - Use Android native decoders (`MediaExtractor` / `MediaCodec`) for FLAC, MP3, M4A/AAC, and OGG/Opus.
  - For WAV PCM (16-bit & 24-bit), implement a direct pure-Kotlin RIFF/WAV header parser and PCM sample reader so 24-bit precision is never silently truncated to 16-bit.
  - **API 31 PCM Encoding Guard:** Guard `AudioFormat.ENCODING_PCM_24BIT_PACKED` and `AudioFormat.ENCODING_PCM_32BIT` with `Build.VERSION.SDK_INT >= Build.VERSION_CODES.S` (API 31). On API 26–30, unpack 24-bit integer WAV PCM losslessly into 32-bit float (`AudioFormat.ENCODING_PCM_FLOAT`, preserving full 24-bit mantissa precision).
  - For 24-bit FLAC via `MediaCodec`, request high-precision PCM output when supported by the runtime API level, inspect the actual decoded PCM encoding at runtime, and report the actual decoded format honestly in the Diagnostics Panel without claiming 24-bit if the OS decoder down-converts to 16-bit.
  - **Prohibited:** Do not pull in heavy FFmpeg/NDK bundles or strict GPL/LGPL dependencies. Only use permissive licenses (Apache 2.0 / MIT / BSD).
- **Out of MVP Scope:** DSD (`.dsf`/`.dff`), ALAC, APE, WavPack, and `.cue` sheets.

### 3.5 In-App Diagnostics Panel & Verification Claim Limits (Mandatory)
Every diagnostic entry in the app must explicitly categorize its confidence level into one of three tiers:
- **Verified:** Read directly at runtime from the file header, `MediaCodec`, `AudioTrack`, or negotiated `AudioMixerAttributes`.
- **Indicated:** Reported by OS metadata or `AudioDeviceInfo`.
- **Unverifiable:** Internal DSP/resampling inside USB-C DAC cable firmware and analog output signal integrity.

Required real-time diagnostic fields:
- Source file container, codec, sample rate, and bit depth.
- Decoded PCM format (encoding, sample rate, channel count).
- Active output device and connection type (`AudioDeviceInfo`).
- Active audio engine / output path and negotiated `AudioMixerAttributes`.
- DSP, EQ, and volume control mode status (Safe Mode vs. Bit-Perfect Opt-In).
- Maximum claim notice: *"Verified up to Android Audio HAL / USB Output boundary; internal USB-C DAC cable firmware processing cannot be verified without external hardware instrumentation."*

---

## 4. MVP Application Features

### 4.1 Local Music Player & Library Management
- Scan local audio via `MediaStore` and allow manual folder/file selection via Storage Access Framework (SAF, using `takePersistableUriPermission` defensively), complying with Scoped Storage without excessive permissions (`READ_MEDIA_AUDIO` on API 33+, `READ_EXTERNAL_STORAGE` capped at `android:maxSdkVersion="32"` for API 26–32 compatibility; never `MANAGE_EXTERNAL_STORAGE`).
- Library navigation views: **All Songs, Folders, Artists, Albums, Recently Played, Playback Queue, and Favorites**.
- Graceful handling of missing/corrupted metadata (fallback to filename).
- Full playback controls: Play, Pause, Next, Previous, Seek, Progress Bar, Queue management (add, remove, reorder), Shuffle, and Repeat (`OFF` / `ALL` / `ONE`).
- Background playback via `Foreground Service` (`android:foregroundServiceType="mediaPlayback"`, started via `ServiceCompat.startForeground` with `ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK`) + `MediaSession` (system notification and Android media controls).
- Full lifecycle handling: screen off, app switching, audio focus (calls/notifications), restoring last playback position on relaunch, missing/corrupted files, and USB disconnection.

### 4.2 Offline Lyrics System (`.lrc` & Manual Input)
100% offline—no network lookup or download.

**Track Identity Binding (Mandatory — Deterministic Content Fingerprint):**
Never bind lyrics solely to song title or `MediaStore._ID`. Use a lowercase 64-character hex `SHA-256` content fingerprint as the database Primary Key:
- If file size `<= 128 KB` (`131072` bytes): `SHA-256(entire file bytes)`.
- If file size `> 128 KB`: `SHA-256(first 64 KB [65536 bytes] + last 64 KB [65536 bytes] + total file size in bytes encoded as an 8-byte big-endian unsigned integer)`.
- Use seekable/random-access reads (head + tail 64 KB) for large files to avoid OOM, with a 64 KB ring-buffer streaming fallback if the descriptor is non-seekable.
- Also store filename, file size, and duration as diagnostic/fallback metadata.

**Lyrics Capabilities:**
1. **Manual Input:** Local text editor (clipboard paste supported) to save, edit, replace, and delete lyrics persistently per track. Display untimestamped lyrics as clean scrollable static text.
2. **`.lrc` File Support:**
   - Manual `.lrc` import via SAF.
   - Auto-detect `.lrc` files sharing an identical base filename with the audio file inside accessible SAF/MediaStore folders.
   - Parse standard timestamps (`[mm:ss.xx]`, `[mm:ss.xxx]`, multiple timestamps per line) and parse/handle metadata tags (`[ti:]`, `[ar:]`, `[al:]`, `[offset:]`).
   - Fault-tolerant parsing: malformed timestamps, blank lines, and common encodings (`UTF-8`, `UTF-8 BOM`, `UTF-16LE`, `UTF-16BE`, `ISO-8859-1`) without ever crashing.
3. **Synchronization:**
   - Active line highlighting must follow the actual playback position from the audio engine, not an independent UI timer.
   - Seeking must immediately update the active line and smoothly auto-scroll without audio glitches.

### 4.3 User Interface (UI/UX)
- Native Kotlin + Jetpack Compose (Material 3) with a consistent **Dark Theme**, evolving the existing `app/src/main/java/com/cangkir/bitperfectplayer/ui/theme/{Color.kt, Theme.kt, Type.kt}` files.
- Fast access to Now Playing, Queue, Lyrics, and Audio Diagnostics Panel.
- Minimum **48 dp** touch target size for all primary controls plus accessibility `contentDescription` labels.
- Prioritize smooth scrolling and audio stability over heavy animations.

---

## 5. Offline & Privacy Requirements (Local-First)

- 100% offline: no internet access, accounts, backend servers, ads, telemetry, or third-party analytics.
- **STRICTLY PROHIBITED:** Do not add `android.permission.INTERNET` to `AndroidManifest.xml`, and verify that no third-party library injects it into the merged manifest.
- No automatic downloading of metadata, album art, or lyrics.
- Persist all user data locally using Room (or standard Android `SQLiteOpenHelper` only if Room/KSP has an irreconcilable toolchain conflict with the project's AGP/SDK 37 version, documented in `DECISIONS.md`) and DataStore Preferences.

---

## 6. Full APK Phase Extensions

Having graduated from the verified MVP baseline, the Full APK roadmap authorizes the following strictly local, audiophile-grade extensions while keeping **all core invariants locked** (100% offline with zero `INTERNET` or `MANAGE_EXTERNAL_STORAGE` permissions, Safe Mode default, mandatory Hearing Safety Warning Dialog, exact `Hz` diagnostics honesty, and smooth 120 Hz UI scrolling):

1. **User Playlists & Offline `.m3u8` Import/Export:**
   - Persistent local playlists stored in Room (`playlists` and `playlist_tracks`) with create, rename, delete, add/remove track, reorder, and Play All workflows.
   - Standard `.m3u8` export via SAF `CreateDocument` and import via SAF `OpenDocument`, resolving entries back to local library tracks by SHA-256 fingerprint, URI, relative path, or filename.
2. **Accurate Anti-Skip Play Count & Listening Statistics (`track_stats`):**
   - Fingerprint-bound play count and real accumulated listening time (`total_play_time_ms`) tracked strictly from active PCM playback elapsed time (ignoring paused time and seek jumps; never counting `< 5s` skips; counting at most once per track session once the 30s / 50% threshold is met).
   - Smart **Most Played** auto-list sorted by `play_count DESC`.
3. **Gapless Playback:**
   - Seamless consecutive track transitions when adjacent PCM streams match format/sample rate/channel layout without injecting artificial crossfades or DSP.
4. **Safe-Mode-Only ReplayGain:**
   - Optional local ReplayGain metadata attenuation permitted strictly in Safe Mode (`SAFE_VOLUME_MODE`), automatically and unconditionally bypassed in Bit-Perfect Opt-In mode so PCM bits remain untouched.
5. **Sleep Timer:**
   - Local countdown or end-of-track auto-pause timer with clean fade-free or Safe-Mode stop.
6. **Track Technical Info Sheet:**
   - On-demand detailed inspection of local container, codec, exact sample rate (`Hz`), bit depth, channels, file size, path/URI, and SHA-256 content fingerprint.
7. **Local Backup & Restore (SAF):**
   - 100% offline export/import of playlists, favorites, lyrics, and listening statistics to a user-selected local file via SAF.
8. **Interactive Spinning Vinyl Disc & Safe-Mode Live Turntable Scratch DSP:**
   - Optional GPU-accelerated spinning vinyl turntable mode in `NowPlayingScreen` with velocity-sensitive rotational finger scrubbing and catchable bounded flick inertia (`<= 450 ms`).
   - Exclusively in `SAFE_VOLUME_MODE` while actively playing, touching/dragging the vinyl disc engages a real-time pure-Kotlin varispeed resampler backed by a hard-capped `8 MB` rolling PCM float ring buffer (forward varispeed up to `+4.0x`, finger-hold braking to `0.0x` silence, and real-time reverse scratch down to `-4.0x`) with zero `AudioTrack` rebuilds and frozen play-count accumulation.
   - Strictly bypassed in `BIT_PERFECT_OPT_IN` mode (zero ring-buffer allocation or PCM interception; falls back transparently to silent seek-preview + single `seekTo` on release).
9. **Read-Only Offline Radial Ring Spectrum Visualizer:**
   - Zero-permission (`NO RECORD_AUDIO`, `NO android.media.audiofx.Visualizer`) local 1024-point Hann-windowed radix-2 FFT visualizer radiating symmetrically as 64 crisp ticks around `InteractiveVinylDisc` (`316.dp` ring around `240.dp` disc) with a matching compact `42.dp` 32-bar strip under `Square Cover`.
   - Switchable OLED-tuned color palettes (`Studio`, `Prism`, and local cover-art `Album` via pure-Kotlin RGB->HSL extraction) plus optional zero-allocation `Kinetic Sparks` transient particle bursts (`MAX_SPARKS = 64`, crisp velocity-aligned micro-streaks + dots, zero GPU blur masks).
   - Strictly read-only post-write PCM side-tap (`48,000`-float ≈ `192 KB` rolling mono buffer) with Hi-Res decimation (`88.2/96 kHz -> stride 2`, `176.4/192 kHz -> stride 4`) and `AudioTrack.playbackHeadPosition` sync, making it 100% safe in both `SAFE_VOLUME_MODE` and `BIT_PERFECT_OPT_IN` mode.
   - Strictly lifecycle-gated (`ON_START..ON_STOP` on `NowPlayingScreen` only): 0% FFT CPU and 0 KB tap buffer when off-screen, backgrounded, or disabled in Settings/overflow menu.