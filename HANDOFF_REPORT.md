# BitPerfectPlayer MVP — Autonomous Build Handoff Report

- **Project:** `com.cangkir.bitperfectplayer` (Kotlin + Jetpack Compose, 100% Offline Android Music Player)
- **Active Branch:** `feat/mvp-overnight`
- **Overall Status:** **Complete (Software-Verified — Awaiting Physical Hardware Verification on POCO F4 + KZ EDX Pro 2 USB-C)**
- **Tasks Completed:** **17 / 17** (`T0.1` through `T5.4` in `TASKS.md`)
- **Active Blockers:** None (`BLOCKED.md` not required)

---

## 1. Implementation Summary

All 5 milestones from `TASKS.md` were implemented sequentially in production Kotlin with zero mock playback engines, zero placeholder buttons, and zero `TODO()` stubs:

| Milestone | Task | Commit | Key Modules Delivered |
| :--- | :--- | :--- | :--- |
| **M0: Setup** | `T0.1` | `5b3ae7d` | Baseline SDK 37 (`compileSdk { version = release(37) }`, `targetSdk = 37`, `minSdk = 26`, JDK 25) verified; `gradlew` marked executable; `DECISIONS.md` initialized. |
| **M0: Setup** | `T0.2` | `073f0dc` | Permissive Apache 2.0 dependency catalog (`gradle/libs.versions.toml`), Room `2.8.5` + KSP `2.3.14`, DataStore `1.1.7`, Navigation Compose, minimal offline manifest permissions, package layout (`audio`, `library`, `lyrics`, `data`, `ui`). |
| **M1: Core Logic & Data** | `T1.1` | `d281d59` | Pure-Kotlin `ContentFingerprintGenerator` (deterministic SHA-256 `<= 128 KB` full / `> 128 KB` head 64 KB + tail 64 KB + BE uint64 size, OOM-safe seekable & 64 KB ring-buffer streaming paths) + `AndroidContentFingerprintProvider`. |
| **M1: Core Logic & Data** | `T1.2` | `7126c9b` | Pure-Kotlin fault-tolerant `LrcParser` (`[mm:ss.xx]`, `[mm:ss.xxx]`, multi-timestamp lines, `[ti:]`/`[ar:]`/`[al:]`/`[offset:]`, UTF-8 / UTF-8 BOM / UTF-16LE / UTF-16BE / ISO-8859-1 decoding, static fallback) + $O(\log n)$ `findActiveLineIndex`. |
| **M1: Core Logic & Data** | `T1.3` | `4fcf6e6` | Pure-Kotlin `PersistenceModels` & `PersistenceSanitizer`, Room `BitPerfectDatabase` (`LyricsEntity`, `TrackHistoryEntity`, `FavoriteTrackEntity`, DAOs, `LocalPersistenceRepository`), and DataStore `UserPreferencesRepository` (Safe Mode enforced as default). |
| **M2: Playback & Safety Domain** | `T2.1` | `2d4cc87` | Pure-Kotlin `PlaybackQueueManager` (set queue, enqueue, enqueueNext, remove, reorder, non-destructive Shuffle, Repeat `OFF`/`ALL`/`ONE`, active index/item restoration). |
| **M2: Playback & Safety Domain** | `T2.2` | `60c3ba8` | Pure-Kotlin `AudioSafetyController` state machine (Safe Mode default, warning gate before unverified Bit-Perfect activation, never auto-enable on USB plug-in, immediate `AUTO_PAUSE` + preferred mixer cleanup on noisy/USB/route events). |
| **M2: Playback & Safety Domain** | `T2.3` | `01eb1e4` | Pure-Kotlin 6-stage `AudioDiagnosticSnapshot` & `AudioDiagnosticMapper` (`VERIFIED` / `INDICATED` / `UNVERIFIABLE` tiers, honest fallback labels, verbatim SPEC 3.5 claim boundary). |
| **M3: Native Audio & Service** | `T3.1` | `3e5fd13` | Pure-Kotlin `WavHeaderParser`, `WavPcmDecoder` (16/24/32-bit RIFF PCM, lossless 24-bit-to-float32 unpacking for API 26–30), `FlacStreamInfoParser`, `AudioFormatClassifier` (strictly 5 MVP formats), `MediaCodecAudioDecoder` (FLAC/MP3/M4A/OGG with API 31+ PCM guards & honest runtime format inspection), and `AudioMetadataReader`. |
| **M3: Native Audio & Service** | `T3.2` | `fcae466` | `AudioOutputEngine` (`AudioTrack` with `PERFORMANCE_MODE_NONE`, API 34+ `getSupportedMixerAttributes`/`setPreferredMixerAttributes`/`clearPreferredMixerAttributes` cleanup, `AudioDeviceCallback`, `ACTION_AUDIO_BECOMING_NOISY`, `AudioFocusRequest`, hardware-clocked `currentPositionMs`). |
| **M3: Native Audio & Service** | `T3.3` | `ebac577` | `PlaybackForegroundService` (`foregroundServiceType="mediaPlayback"`, `ServiceCompat.startForeground`, `Notification.MediaStyle`, platform `MediaSession`), `PlaybackController` (periodic + lifecycle position persistence), `BitPerfectApplication` / `AppContainer`. |
| **M4: Library & Lyrics** | `T4.1` | `22af762` | Pure-Kotlin `LibraryProjector` + `LocalLibraryRepository` (`MediaStore.Audio.Media` + SAF folder/file import with defensive `takePersistableUriPermission`, grouped All Songs / Folders / Artists / Albums / Recently Played / Favorites). |
| **M4: Library & Lyrics** | `T4.2` | `daa6819` | `LyricsRepository`, `LyricsCoordinator`, and `LrcFileMatcher` (fingerprint-bound DB lookup, sibling `.lrc` auto-detect across SAF trees & MediaStore, SAF `.lrc` picker import, manual edit/replace/delete). |
| **M5: Compose Dark UI** | `T5.1` | `8fb3eb3` | Evolved `ui/theme/{Color.kt, Theme.kt, Type.kt}` into consistent audiophile Material 3 Dark Theme, `MainViewModel`, `LibraryScreen` (6 tabs, search, SAF import, runtime permissions), and persistent `MiniPlayerBar` (`>= 48.dp` targets). |
| **M5: Compose Dark UI** | `T5.2` | `a8bc867` | `NowPlayingScreen` (format badges, seekbar, transport controls, quick Pause, Safe Mode vs Bit-Perfect Opt-In controls), mandatory `HearingSafetyWarningDialog`, and `QueueScreen` (add, remove, reorder, jump). |
| **M5: Compose Dark UI** | `T5.3` | `09b27d6` | `LyricsScreen` (`AudioOutputEngine.currentPositionMs`-clocked synced `.lrc` view with auto-scroll & tap-to-seek, static view, SAF `.lrc` import, manual editor) and 6-stage real-time `DiagnosticsScreen`. |
| **M5: Final Wiring & Audit** | `T5.4` | *(this commit)* | `BitPerfectAppNav.kt` & `MainActivity.kt` end-to-end Compose Navigation wiring, Android Lint resolution (`MODIFY_AUDIO_SETTINGS` for API 34+ mixer APIs, `minSdk = 26` `MediaFormat` fix), full audit, and `HANDOFF_REPORT.md`. |

---

## 2. Software Verification Evidence

All verification was performed strictly in a **software-only cloud environment** (zero ADB, zero emulators, zero fabricated hardware results).

### Commands Executed & Results

1. **JVM Unit Tests:** `./gradlew testDebugUnitTest` -> **PASS (`BUILD SUCCESSFUL`)**
   - **Total:** **50 tests executed, 50 passed, 0 failed, 0 errors, 0 skipped, 0 `@Ignore`**
   - Suite breakdown:
     - `ExampleUnitTest`: 1/1
     - `ContentFingerprintGeneratorTest`: 9/9
     - `LrcParserTest`: 8/8
     - `PersistenceAndPreferencesTest`: 4/4 (real DataStore Preferences + entity/sanitizer tests)
     - `PlaybackQueueManagerTest`: 6/6
     - `AudioSafetyControllerTest`: 8/8
     - `AudioDiagnosticMapperTest`: 4/4
     - `WavHeaderParserTest`: 6/6
     - `LibraryProjectorTest`: 2/2
     - `LyricsRepositoryTest`: 2/2
2. **Android Lint:** `./gradlew lintDebug` -> **PASS (`0 errors`, 21 non-blocking baseline/version warnings)**
   - **0 errors and 0 warnings across all Kotlin source files and `AndroidManifest.xml`.**
   - Remaining 21 warnings consist solely of 7 pre-existing unused template colors in `res/values/colors.xml` and 14 informational `GradleDependency` / `NewerVersionAvailable` / `AndroidGradlePluginVersion` notices in `gradle/libs.versions.toml` (baseline AGP `9.4.1`, Kotlin `2.2.10`, and Compose BOM `2026.02.01` intentionally preserved).
   - Lint audit also caught and helped fix 2 genuine runtime issues prior to handoff: missing `android.permission.MODIFY_AUDIO_SETTINGS` (required on Android 14+ by `AudioManager.setPreferredMixerAttributes`/`clearPreferredMixerAttributes`) and an API 29 `MediaFormat(MediaFormat)` copy constructor under `minSdk = 26`.
3. **Debug APK Build:** `./gradlew assembleDebug` -> **PASS (`BUILD SUCCESSFUL`)**
   - Artifact path: `app/build/outputs/apk/debug/app-debug.apk` (`20 MB`, non-empty).
4. **Strict Offline Privacy Audit (Source + Merged Debug Manifests):** **PASS**
   - Audited:
     - `app/src/main/AndroidManifest.xml`
     - `app/build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml`
     - `app/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml`
   - Result: **Zero occurrences** of `android.permission.INTERNET` or `android.permission.MANAGE_EXTERNAL_STORAGE`.
   - Final merged manifest permissions:
     - `android.permission.MODIFY_AUDIO_SETTINGS` (normal install-time permission required by Android 14+ mixer APIs; see `DECISIONS.md` D5.4)
     - `android.permission.READ_MEDIA_AUDIO`
     - `android.permission.READ_EXTERNAL_STORAGE` (`android:maxSdkVersion="32"`)
     - `android.permission.FOREGROUND_SERVICE`
     - `android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK`
     - `android.permission.POST_NOTIFICATIONS`
     - `com.cangkir.bitperfectplayer.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (standard AndroidX Core internal permission)
5. **Pure-Kotlin Domain & Baseline Integrity Audit:** **PASS**
   - Zero `android.*` imports across all 10 pure-Kotlin core domain modules (`ContentFingerprintGenerator`, `LrcParser`, `PersistenceModels`, `PlaybackQueueManager`, `AudioSafetyController`, `AudioDiagnosticMapper`, `AudioDecoderModels`, `WavHeaderParser`, `PlaybackTrack`, `LibraryProjector`).
   - Zero `TODO()` stubs in `app/src/main/java/`.
   - Preserved `namespace = "com.cangkir.bitperfectplayer"`, `applicationId = "com.cangkir.bitperfectplayer"`, `compileSdk { version = release(37) }`, `targetSdk = 37`, `minSdk = 26`, `JavaVersion.VERSION_11`, and `release { optimization { ... } }`.
   - `local.properties` and `app/build/` remain untracked/gitignored and never staged.

---

## 3. Dependencies & Licenses

Every library added or upgraded in `gradle/libs.versions.toml` uses a permissive license and introduces zero networking modules or forbidden permissions:

| Dependency / Plugin | Version | Purpose | License |
| :--- | :--- | :--- | :--- |
| `androidx.core:core-ktx` | `1.16.0` | `ServiceCompat.startForeground` (FGS types), `ContextCompat.registerReceiver`, KTX `toUri()` | Apache 2.0 |
| `androidx.activity:activity-compose` | `1.10.1` | Compose Activity integration, SAF & runtime permission launchers | Apache 2.0 |
| `androidx.lifecycle:lifecycle-runtime-ktx` | `2.9.4` | Lifecycle coroutine scopes | Apache 2.0 |
| `androidx.lifecycle:lifecycle-runtime-compose` | `2.9.4` | `collectAsStateWithLifecycle()` | Apache 2.0 |
| `androidx.lifecycle:lifecycle-viewmodel-compose` | `2.9.4` | `viewModel()` factory integration | Apache 2.0 |
| `androidx.compose.material:material-icons-extended` | Compose BOM `2026.02.01` | Vector icons for player, queue, lyrics, safety, and diagnostics UI | Apache 2.0 |
| `androidx.navigation:navigation-compose` | `2.9.8` | Compose navigation graph (`Library`, `Now Playing`, `Queue`, `Lyrics`, `Diagnostics`) | Apache 2.0 |
| `androidx.documentfile:documentfile` | `1.1.0` | Scoped Storage / SAF tree & document traversal | Apache 2.0 |
| `androidx.datastore:datastore-preferences` | `1.1.7` | Local preferences persistence (Safe Mode default, shuffle/repeat, SAF URIs, resume state) | Apache 2.0 |
| `androidx.room:room-runtime`, `room-ktx`, `room-compiler` | `2.8.5` | Local SQLite database for fingerprint-bound lyrics, playback history, and favorites | Apache 2.0 |
| `com.google.devtools.ksp` (Gradle Plugin) | `2.3.14` | Kotlin Symbol Processing for Room DAO/Database code generation | Apache 2.0 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | `1.10.2` | Structured concurrency for audio/library/lyrics I/O | Apache 2.0 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-test` | `1.10.2` | Deterministic JVM coroutine & DataStore unit testing | Apache 2.0 |

*Media Controls Note:* System media notification (`Notification.MediaStyle`) and `MediaSession` use Android platform APIs (`android.media.session.MediaSession`, `android.app.Notification.MediaStyle`) built into API 26+, adding zero external dependencies.

---

## 4. Audio & Volume Safety Status

### Verified (Code, Static Lint, & JVM Unit-Test Level)
- Safe Mode (`SAFE_VOLUME_MODE`) is enforced as the default in both `AudioSafetyController` and `UserPreferencesRepository`.
- Requesting `BIT_PERFECT_OPT_IN` while `isHardwareVolumeVerified == false` is blocked until explicit confirmation through `HearingSafetyWarningDialog`.
- `AudioSafetyController` never auto-enables Bit-Perfect Mode upon USB connection; connecting/disconnecting USB, changing output routes, or receiving `ACTION_AUDIO_BECOMING_NOISY` resets to Safe Mode, requests `clearPreferredMixerAttributes`, and immediately emits `AUTO_PAUSE`.
- Android 14+ (`Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE`) guards wrap `getSupportedMixerAttributes`, `setPreferredMixerAttributes`, `getPreferredMixerAttributes`, and `clearPreferredMixerAttributes`, with mandatory cleanup on Safe Mode switch, stop/release, USB disconnect, and track/sample-rate changes.
- Android 12+ (`Build.VERSION.SDK_INT >= Build.VERSION_CODES.S`) guards wrap `ENCODING_PCM_24BIT_PACKED` and `ENCODING_PCM_32BIT`; `WavPcmDecoder` losslessly unpacks 24-bit WAV PCM into 32-bit IEEE float (`ENCODING_PCM_FLOAT`, verified bit-exact across 24-bit range in `WavHeaderParserTest`) on API 26–30.
- `AudioDiagnosticMapper` explicitly separates all 6 audio stages, never claims bit-perfect on fallback/Safe Mode/non-USB paths, flags OS decoder bit-depth down-conversion honestly, and marks Stage 6 (internal USB-C DAC firmware & analog output) as `UNVERIFIABLE` with the verbatim SPEC Section 3.5 claim notice.

### Unverified (Awaiting Physical POCO F4 + KZ EDX Pro 2 USB-C Hardware Testing)
- **Not verified in cloud:** Real Xiaomi HyperOS 1.0.9.0 (Android 14 / API 34) Audio HAL behavior on POCO F4.
- **Not verified in cloud:** Whether POCO F4 + KZ EDX Pro 2 USB-C actually exposes `AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT` from `AudioManager.getSupportedMixerAttributes(usbDevice)` at runtime for 44.1 / 48 / 96 kHz PCM formats.
- **Not verified in cloud:** Whether KZ EDX Pro 2 USB-C supports UAC Hardware Volume or outputs fixed full-scale (100% / 0 dBFS) audio when `MIXER_BEHAVIOR_BIT_PERFECT` is engaged.
- **Not verified in cloud:** Whether Qualcomm/HyperOS `MediaCodec` FLAC decoder outputs native 24-bit PCM or down-converts 24-bit FLAC to 16-bit PCM (the app will report whichever output encoding `MediaCodec` actually provides).
- **Not verified in cloud:** Audible playback quality, glitch-free screen-off background playback under HyperOS battery management, physical USB-C plug/unplug auto-pause latency, and real MediaStore/SAF provider behavior.

---

## 5. Blockers, Technical Risks & Limitations

- **Zero build/code blockers.** All 17 tasks passed verification cleanly.
- **HyperOS Background Policy Risk:** Xiaomi HyperOS aggressively kills background services unless battery optimization is set to *No restrictions* for media apps. `PlaybackForegroundService` uses `foregroundServiceType="mediaPlayback"` and `ServiceCompat.startForeground`, which is standard Android compliance, but user-level HyperOS battery settings should be checked during device testing.
- **OS FLAC Decoder Bit-Depth Risk:** Android's built-in `MediaCodec` FLAC decoder varies by SoC/OEM regarding 24-bit output support. `MediaCodecAudioDecoder` requests 24-bit PCM on API 31+ and falls back cleanly if rejected, reporting the true runtime `MediaFormat.KEY_PCM_ENCODING` in the Diagnostics Panel. For guaranteed unmodified 24-bit PCM without `MediaCodec`, `WavPcmDecoder` reads RIFF/WAV files directly.
- **Hardware Volume Detection Conservatism:** Android does not provide a reliable universal API to guarantee UAC Hardware Volume attenuation inside arbitrary USB-C earphone cables; `AudioOutputEngine` intentionally keeps `isHardwareVolumeVerified = false` so the Hearing Safety Warning Dialog is always required before opting into Bit-Perfect Mode.

---

## 6. Priority Hardware Verification Checklist (POCO F4 + KZ EDX Pro 2 USB-C)

> **HEARING SAFETY FIRST:** Keep the KZ EDX Pro 2 earphones **OUT OF YOUR EARS** during Tests 2, 3, and 4 until volume behavior is personally confirmed!

1. **Install & Offline Sanity Check:**
   - Install `app/build/outputs/apk/debug/app-debug.apk` on POCO F4.
   - Verify in Android App Info -> Permissions that there is **no Network/Internet permission** and only local Audio / Notifications are requested.
2. **Safe Mode Default & USB Plug-In Safety (IEMs out of ears):**
   - Launch app on fresh install; confirm status reads **Safe Mode (Volume Active)**.
   - Start playback of a 16-bit/44.1 kHz MP3 or FLAC on phone speaker, lower volume, then plug in KZ EDX Pro 2 USB-C.
   - Confirm playback **auto-pauses immediately**, mode remains **Safe Mode**, and hardware volume buttons smoothly adjust loudness when resumed.
3. **Bit-Perfect Opt-In & Hearing Safety Warning Dialog (IEMs out of ears):**
   - Open **Now Playing** or **Audio Diagnostics** and tap **Bit-Perfect Opt-In**.
   - Confirm the **Hearing Safety Warning Dialog** appears; tap **Stay in Safe Mode** first and verify mode stays Safe.
   - Tap **Bit-Perfect Opt-In** again and confirm activation. Open **Audio Diagnostics** and inspect Stage 4 (`OS Audio Policy / Mixer / HAL`) and Stage 5 (`USB Audio / Output Device Path`):
     - Record whether POCO F4 HyperOS HAL reports `MIXER_BEHAVIOR_BIT_PERFECT` or honestly falls back to *"Clean Native Path — Bit-Perfect Not Supported by OS/Device"*.
     - Test physical volume rocker while playing a quiet/moderate track (IEMs held away from ears!) to verify whether output is fixed 100% or hardware-attenuated.
4. **Unplug Auto-Pause & Mixer Cleanup:**
   - While playing in Bit-Perfect Opt-In mode on USB-C, physically unplug KZ EDX Pro 2 USB-C.
   - Confirm playback **auto-pauses immediately** (never blasts through phone speakers), mode resets to **Safe Mode**, and normal volume works in other apps afterward (confirming `clearPreferredMixerAttributes` cleaned up HAL state).
5. **5-Format Playback & 24-Bit Diagnostics Accuracy:**
   - Test one sample each of **WAV (16-bit & 24-bit)**, **FLAC (16-bit & 24-bit)**, **MP3**, **M4A (AAC)**, and **OGG (Vorbis/Opus)**.
   - Confirm 24-bit WAV reports `WavPcmDecoder (Direct PCM)` + `PCM_24BIT_PACKED` in Diagnostics.
   - Check 24-bit FLAC Diagnostics to confirm whether POCO F4's `MediaCodec` preserves 24-bit or honestly flags OS down-conversion to 16-bit.
6. **Offline Lyrics & Fingerprint Persistence:**
   - Test SAF folder import containing `<song>.flac` + `<song>.lrc` (verify auto-detection, synced active-line scrolling, and immediate jump on forward/backward seek).
   - Test manual lyrics editor (paste, save, edit, delete), then rename the audio file externally and confirm lyrics remain bound via SHA-256 content fingerprint.
7. **Background Playback & Resume State:**
   - Play audio, lock screen, test notification & lock-screen Play/Pause/Next/Previous/Seek controls via `MediaSession`.
   - Force-close or swipe app away while paused, relaunch, and confirm last track & playback position restore cleanly in paused state.
