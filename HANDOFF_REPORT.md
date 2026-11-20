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

---

## 7. Post-Hardware-Test Bugfix Summary (POCO F4 Android 14 / HyperOS 1.0.9.0 + KZ EDX Pro 2 USB-C)

Following initial physical testing and logcat/screenshot analysis on POCO F4 (`HyperOS 1.0.9.0`, Android 14) with KZ EDX Pro 2 USB-C, all P0/P1/P2 audio, persistence, state, artwork, UI/UX, lint, and branding issues were resolved on `feat/mvp-overnight` in 5 logical commits:

| Priority | Commit | Scope & Root-Cause Resolution |
| :--- | :--- | :--- |
| **P0 + P2** | `087a7dc` | **Fixed full-scale static/white-noise audio bug & exact `Hz` formatting:** Eliminated dual root cause where (1) `MediaCodecAudioDecoder` requested `ENCODING_PCM_24BIT_PACKED` on 24-bit FLAC while `c2.android.flac.decoder` emitted 16-bit PCM (absent `KEY_PCM_ENCODING` previously fell back to `requestedEncoding` instead of strictly `ENCODING_PCM_16BIT`), and (2) `AudioOutputEngine` used an unaligned `8192`-byte read buffer (`8192 % 6 == 2`) for 24-bit stereo. Now requests `ENCODING_PCM_FLOAT` for `>16-bit` MediaCodec sources, resolves absent `KEY_PCM_ENCODING` strictly to `ENCODING_PCM_16BIT`, defers `AudioTrack` creation/reconfiguration until confirmed `codec.outputFormat` (`formatVersion`), writes `ENCODING_PCM_FLOAT` via `FloatArray` and integer PCM via direct little-endian `ByteBuffer` with partial-write loops and strict frame alignment (`PcmStreamRules`), makes `AudioTrack` teardown idempotent, adds concise `Log.d("BitPerfectAudio", ...)` start diagnostics, standardizes exact `Hz` sample-rate labels (`96,000 Hz`, `44,100 Hz`), and de-duplicates identical container/codec pairs (`FLAC (FLAC)` -> `FLAC`). |
| **P1** | `336e6d0` | **Library persistence & instant optimistic favorites:** Added Room `CachedTrackEntity` + `CachedTrackDao` with non-destructive `MIGRATION_1_2` (preserving `lyrics`, `track_history`, and `favorite_tracks`), immediate SQLite-backed rendering on launch (zero empty-screen flash), incremental background diff sync (`LibrarySyncDiffer` on `uri` + `size` + `dateModified`) preserving SAF tracks across restarts, and single reactive `StateFlow<Set<String>>` + instant optimistic heart toggling (`FavoriteStateReducer`) across `LibraryScreen`, `NowPlayingScreen`, and `Favorites` tab with rollback on DB error. |
| **P1** | `d033a74` | **100% offline local album cover art:** Added zero-dependency `AlbumArtworkLoader` + pure-Kotlin `AlbumArtworkHelper` extracting embedded tags (`MediaMetadataRetriever.embeddedPicture`), API 29+ MediaStore thumbnails (`ContentResolver.loadThumbnail`), and SAF sibling cover images (`cover.jpg`, `cover.png`, `folder.jpg`, `front.jpg`, `album.jpg`, case-insensitive), downsampled with power-of-2 `inSampleSize` into `160px`/`600px` buckets with memory `LruCache` + `cacheDir/artwork/` disk cache, semaphore-bounded IO, and `PlaybackForegroundService` `MediaMetadata.METADATA_KEY_ALBUM_ART` + notification large icon wiring. |
| **P2** | `768a836` | **Minimalist native utilitarian dark UI/UX overhaul:** Replaced navy/neon palette with neutral matte obsidian/charcoal (`#0E0E10` / `#141417` / `#1B1B20` / `#24242A`), `0.5.dp` hairline dividers, and a single restrained slate-ice accent (`#7BA7D9`); wired local album artwork thumbnails across Library, Albums, MiniPlayer, Queue, and Now Playing hero; fixed duration wrapping (`05:5\n1`) with `maxLines = 1, softWrap = false` aligned cleanly on the title row; replaced colored format pills with subtle muted technical metadata (`FLAC · 24-bit · 96,000 Hz`); compacted search bar to `48.dp` single-line + clean track count (`268 tracks`); replaced the oversized Now Playing mode card with a compact clickable audio-mode status strip opening a `ModalBottomSheet` (preserving `HearingSafetyWarningDialog` & quick `Pause`); flattened nested Diagnostics cards while preserving all 6 stages and verbatim SPEC Section 3.5 notice. |
| **P2** | `a125989` | **Custom minimalist adaptive launcher icon & zero-warning resource cleanup:** Replaced the default Android icon with a custom obsidian geometric PCM impulse + concentric signal ring emblem centered inside the inner `66dp` safe zone (`[29.5..78.5]` on `108dp` canvas, zero text, zero neon glow), and removed all 7 unused Android Studio template colors from `res/values/colors.xml` (replacing with used `app_window_background = #FF0E0E10` referenced by `res/values/themes.xml`). |

### Custom Launcher Icon Assets Delivered
- **Adaptive Vector Layers (`108dp` canvas, emblem strictly inside `66dp` safe zone):**
  - `app/src/main/res/drawable/ic_launcher_background.xml` (matte obsidian subtle gradient `#19191E` -> `#0E0E10`)
  - `app/src/main/res/drawable/ic_launcher_foreground.xml` (concentric `#3D3D47` ring + 5 quantized symmetric PCM impulse bars in `#F2F2F5` with `#7BA7D9` center peak)
  - `app/src/main/res/drawable/ic_launcher_monochrome.xml` (solid `#FFFFFF` vector for Android 13+ Material You themed icons)
  - `app/src/main/res/mipmap-anydpi/ic_launcher.xml` & `app/src/main/res/mipmap-anydpi/ic_launcher_round.xml` (wired to background, foreground, and monochrome layers)
- **Legacy Raster Lossless `.webp` Mipmaps (zero duplicate `.png` files):**
  - `mipmap-mdpi` (`48x48`), `mipmap-hdpi` (`72x72`), `mipmap-xhdpi` (`96x96`), `mipmap-xxhdpi` (`144x144`), `mipmap-xxxhdpi` (`192x192`) — both `ic_launcher.webp` and `ic_launcher_round.webp`.

### Build, Compiler, KSP & Lint Warning Comparison (Before vs. After)

| Check Category | Before (`6932746`) | After (`a125989`) | Notes |
| :--- | :---: | :---: | :--- |
| **Kotlin Compiler Warnings** (`compileDebugKotlin` + `compileDebugUnitTestKotlin`) | `0` | `0` | Clean across all production & test sources |
| **KSP Warnings** (`kspDebugKotlin`) | `0` | `0` | Room `2.8.5` v2 + `MIGRATION_1_2` compiles cleanly |
| **Android Lint Errors** (`lintDebug`) | `0` | `0` | Zero errors |
| **Actionable Code/Resource Lint Warnings** | `7` | `0` | Eliminated all 7 `UnusedResources` template colors in `res/values/colors.xml` |
| **Total `lint-results-debug.txt` Issues** | `21` *(7 unused colors + 14 version notices)* | `0` *(No issues found)* | SDK 37, AGP `9.4.1`, Kotlin `2.2.10`, Compose BOM `2026.02.01` baseline preserved untouched |

### Code-Review Follow-Up Fixes (IPC Throttling & Edge Cases)
1. **Eliminated 20 Hz Notification & `MediaSession` Binder IPC spam (`PlaybackForegroundService` + pure-Kotlin `MediaSessionUpdatePolicy`):**
   - `mediaSession.setMetadata(...)` (including `600x600` album-art bitmaps) now runs ONLY when `activeTrack?.uriString`, `durationMs`, or resolved `artworkBitmap` identity changes.
   - `ServiceCompat.startForeground(...)` and `notificationManager.notify(...)` run ONLY on foreground transition or when `activeTrack?.uriString`, `isPlaying`, `diagnostics.isBitPerfectMixerActive`, or `artworkBitmap` changes.
   - `mediaSession.setPlaybackState(...)` runs ONLY on `status`, `trackUri`, `errorMessage`, or `playbackSpeed` change, or when position diverges by `> 1,500 ms` from the expected elapsed position (`SystemClock.elapsedRealtime()`).
2. **Mid-stream format change safety & `floatBuffer` channel alignment (`AudioOutputEngine`):**
   - Checked EOS (`bytesRead < 0`) prior to format-change handling and skipped writing (`continue`) after a mid-stream `AudioTrack` rebuild + `pcmBuffer` reallocation so freshly zeroed bytes are never written and shrinking buffers cannot cause `ArrayIndexOutOfBoundsException`.
   - Changed `FloatArray(bufferSize / 4 + 1)` to `FloatArray(bufferSize / 4)` so `floatBuffer.size` is always an exact multiple of `channelCount`.
3. **Explicit MediaStore runtime permission guard (`LocalLibraryRepository`):**
   - Checked `ContextCompat.checkSelfPermission` for `READ_MEDIA_AUDIO` (API 33+) / `READ_EXTERNAL_STORAGE` (API <= 32) before querying MediaStore so an empty Scoped Storage cursor never prunes cached MediaStore tracks.
4. **Safer 16-bit `MediaFormat` configuration (`MediaCodecAudioDecoder`):**
   - Omitted `MediaFormat.KEY_PCM_ENCODING` for 16-bit sources (relying on Android's native 16-bit default) while still allowing retry without `KEY_PCM_ENCODING` if any vendor codec rejects a configured PCM encoding key.

### Performance & Predictive-Back Optimizations (Post-Device Profiling on POCO F4)
Following physical profiling on POCO F4 (Android 14 / HyperOS) — which confirmed clean 16-bit & 24-bit FLAC playback, instant cached launch, and real-time favorites, while identifying scroll jank (`Skipped 32–74 frames`, `Davey! 735 ms`, `105 ms` GC pauses), a ~38s initial scan, and `WindowOnBackDispatcher` warnings — 5 targeted optimizations were implemented:
1. **Eliminated 20 Hz full-screen recompositions (`AudioOutputEngine` -> `PlaybackController` -> `MainViewModel` -> leaf composables):**
   - Decoupled high-frequency playback position into a dedicated `playbackPositionMs: StateFlow<Long>` separate from low-frequency `EnginePlaybackState` / `PlaybackControllerState`.
   - `BitPerfectAppRoot`, `LibraryScreen`, `TrackListView`, `QueueScreen`, `DiagnosticsScreen`, and static hero/controls of `NowPlayingScreen` no longer collect or recompose on 20 Hz ticks.
   - `playbackPositionMs` is collected exclusively inside narrow leaf composables: `MiniPlayerProgressBar` (`UiComponents.kt`), `NowPlayingSeekSection` (`NowPlayingScreen.kt`), `SyncedLyricsList` (transformed via `.map { findActiveLineIndex(...) }.distinctUntilChanged()` so it recomposes only on active lyric line changes), and `LyricsCompactSeekBar` (`LyricsScreen.kt`).
2. **Eliminated scroll jank in `AlbumArtworkLoader` & `TrackArtworkThumbnail`:**
   - For `160px` thumbnails on MediaStore URIs (API 29+), prefers fast OS-indexed `ContentResolver.loadThumbnail(...)` BEFORE opening large FLACs with `MediaMetadataRetriever`; `600px` hero artwork continues to prefer full-res embedded tags first.
   - Added album-level deduplication (`AlbumArtworkHelper.buildMemoryCacheKey`) so tracks sharing a valid album name decode and cache only ONE bitmap/JPEG (falling back to track identity when album is blank/`"Unknown Album"`).
   - Eliminated Main-Thread SHA-256 hashing (`getCachedFromMemory` uses plain-string keys; SHA-256 disk filename hashing runs strictly on `Dispatchers.IO`).
   - Made JPEG disk cache writes fire-and-forget async on `Dispatchers.IO.limitedParallelism(2)` and throttled `pruneDiskCacheIfOversized()` to once every 50 writes.
   - Cached `bitmap.asImageBitmap()` via `remember(currentBitmap)` and added a `35 ms` fling debounce inside `TrackArtworkThumbnail`'s `LaunchedEffect`.
3. **Optimized `LazyColumn` row rendering (`LibraryScreen`, `QueueScreen`, `PlaybackTrack`, `AudioFormatFormatter`):**
   - Conditionally composes `DropdownMenu` only when `menuExpanded == true`.
   - Added `contentType` across `TrackListView`, `GroupListView`, `QueueScreen`, and `SyncedLyricsList`.
   - Precomputed `formatBadge`, `durationLabel`, and `artistAlbumLine` once on `PlaybackTrack` construction using fast zero-`String.format` integer formatting in `AudioFormatFormatter`, eliminating per-row allocations and redundant `.replace(" • ", " · ")` calls.
4. **Eliminated redundant `MediaMetadataRetriever` Binder calls during initial MediaStore scan (`AudioMetadataReader` + `MetadataScanPolicy` + `LocalLibraryRepository`):**
   - Added `readMetadataForMediaStore(...)`: reads direct `FileChannel` headers (`FlacStreamInfoParser` / `WavHeaderParser`, < 1 ms each) for FLAC/WAV and skips `MediaMetadataRetriever` completely whenever cursor metadata + direct header already supply `sampleRateHz > 0` and `durationMs > 0`. Full `readMetadata()` remains active for SAF files and missing-field fallbacks.
5. **Enabled Android 13+/14+ predictive back (`AndroidManifest.xml`):**
   - Added `android:enableOnBackInvokedCallback="true"` (`tools:targetApi="33"`) to `<application>` to eliminate repeated `WindowOnBackDispatcher` warnings.

### Updated Software Verification Results (`./gradlew testDebugUnitTest lintDebug assembleDebug` on Gradle `9.9.1` / AGP `9.5.1`)
- **JVM Unit Tests:** **79 / 79 passed** (`0` failed, `0` errors, `0` skipped, `0` `@Ignore`) across 16 test classes (including newly added `MetadataScanPolicyTest` [3/3], expanded `AlbumArtworkHelperTest` [7/7], `MediaSessionUpdatePolicyTest` [4/4], `PcmStreamRulesTest` [7/7], and `LibraryPersistenceAndFavoritesTest` [3/3]).
- **Kotlin & KSP Compiler Warnings:** `0` Kotlin warnings, `0` KSP warnings.
- **Android Lint (`lintDebug`):** **0 errors**, **0 warnings across all `/app/src/` Kotlin, manifest, and resource files** (15 remaining informational notices are strictly upstream dependency version suggestions in `gradle/libs.versions.toml` and `ObsoleteLintCustomCheck` inside AndroidX `lifecycle-runtime` AAR under AGP 9.5.1).
- **Debug APK:** `app/build/outputs/apk/debug/app-debug.apk` (`20 MB`).
- **Strict Offline Privacy Audit (Source + All Merged Debug Manifests):** **PASS** — **0 occurrences** of `android.permission.INTERNET` or `android.permission.MANAGE_EXTERNAL_STORAGE`.
- **Pure-Kotlin Domain Boundary:** **PASS** — **0 `android.*` imports** across all 18 pure-Kotlin domain modules (`ContentFingerprintGenerator`, `LrcParser`, `PersistenceModels`, `PlaybackQueueManager`, `AudioSafetyController`, `AudioDiagnosticMapper`, `AudioDecoderModels`, `WavHeaderParser`, `PlaybackTrack`, `PcmStreamRules`, `AudioFormatFormatter`, `MediaSessionUpdatePolicy`, `MetadataScanPolicy`, `LibraryProjector`, `LibrarySyncDiffer`, `FavoriteStateReducer`, `AlbumArtworkHelper`, `MediaScanFilter`).
- **Physical Hardware Verification Pending (Not Executed in Cloud):** Re-install the rebuilt debug APK on POCO F4 (Android 14 / HyperOS) + KZ EDX Pro 2 USB-C to verify 60 fps scrolling during active playback, fast initial MediaStore scan duration, and zero `WindowOnBackDispatcher` logcat warnings.

---

## 8. Full APK Phase 1 Summary (Room v3, Anti-Skip Play Count, Playlists & `.m3u8`, 3-Tab Navigation)

Graduated `BitPerfectPlayer` into Full APK Phase 1 on `feat/mvp-overnight` while strictly maintaining all offline, hearing safety, exact `Hz`, and 120 Hz UI smoothness invariants:

1. **Specification & Room Database v2 -> v3 Non-Destructive Migration (`SPEC.md`, `DECISIONS.md`, `BitPerfectDatabase.kt`, `LocalEntities.kt`, `LocalDaos.kt`, `LocalPersistenceRepository.kt`):**
   - Added Section 6 (*Full APK Phase Extensions*) to `SPEC.md` and `D7.1` to `DECISIONS.md`.
   - Upgraded `BitPerfectDatabase` to `version = 3` with explicit SQLite `MIGRATION_2_3` adding `playlists`, `playlist_tracks` (`ON DELETE CASCADE` FK to `playlists.id` + `(playlist_id, position_index)` index), and `track_stats` (SHA-256 `content_fingerprint` PK + `play_count` / `last_played_at_epoch_ms` indices) while preserving `cached_tracks`, `lyrics`, `track_history`, and `favorite_tracks` untouched (100% parity with KSP-generated schema, 0 KSP warnings).
2. **Accurate Anti-Skip Play Count Engine (`PlayCountTracker.kt`, `AudioOutputEngine.kt`, `PlaybackController.kt`, `PlayCountTrackerTest.kt`):**
   - Pure-Kotlin `PlayCountTracker` (zero `android.*` imports) measures strictly real active `AudioTrack` playback head deltas (ignoring pause time and seek jumps; single-tick capped at `2,000 ms`).
   - Enforces `< 5s` hard floor (skips strictly never increment play count), `(durationMs / 2).coerceIn(10s, 30s)` for `>= 10s` tracks, `max(5s, 80%)` or natural EOS `>= 5s` for `< 10s` tracks, `30s` for unknown duration, max 1 count per session, and clean session reset on track switch, replay after completion, or `RepeatMode.ONE` loop.
   - Persists `play_count` and real accumulated `total_play_time_ms` into Room `track_stats` bound to SHA-256 `content_fingerprint`.
3. **Offline Playlists & `.m3u8` Import/Export (`M3u8PlaylistCodec.kt`, `PlaylistRepository.kt`, `PlaylistsScreen.kt`, `M3u8PlaylistCodecTest.kt`):**
   - Pure-Kotlin `M3u8PlaylistCodec` (zero `android.*` imports) exports standard `#EXTM3U` / `#PLAYLIST` / `#EXTINF` playlists with optional `#EXT-X-BITPERFECT-URI` and `#EXT-X-BITPERFECT-SHA256` hints, parses external `.m3u8`/`.m3u` files (including UTF-8 BOM, Windows backslashes, URL-encoded paths), matches back to library tracks by fingerprint -> URI -> relative path -> filename, and provides deterministic entry reordering.
   - Room-backed `PlaylistRepository` delivers reactive `playlistsSnapshot` (playlists, resolved tracks, ranked `Most Played`, and total plays) + CRUD + SAF `CreateDocument` export and `OpenDocument` import.
   - Minimalist obsidian `PlaylistsScreen` displays compact **Most Played** preview + full ranked view at top, user playlists with counts/Play All/Rename/Export `.m3u8`/Delete, playlist detail view with Move Up/Down/Remove, and reusable `AddToPlaylistDialog` wired into both `LibraryScreen` (3-dot menu) and `NowPlayingScreen` (`TopAppBar` action).
4. **Clean 3-Tab Bottom Navigation & Minimalist `SettingsScreen` (`BitPerfectAppNav.kt`, `SettingsScreen.kt`, `LibraryScreen.kt`):**
   - Docked flat Material 3 `NavigationBar` (`Library` · `Playlists` · `Settings`) directly under `MiniPlayerBar` on top-level routes only (automatically hidden on `NowPlaying`, `Queue`, `Lyrics`, and `Diagnostics`), with top-only child `Scaffold` insets preventing double navigation-bar padding.
   - Kept all 6 `LibraryScreen` tabs (`All Songs`, `Folders`, `Artists`, `Albums`, `Recent`, `Favorites`) clean without Sort or Filter chrome on `All Songs`.
   - Added `SettingsScreen` hosting linked SAF folder management (view/link/unlink), library rescan, 6-Stage Diagnostics shortcut, and 100% offline privacy summary.
   - Preserved 20 Hz `playbackPositionMs` isolation: `LibraryScreen`, `PlaylistsScreen`, `SettingsScreen`, `QueueScreen`, `DiagnosticsScreen`, and `BitPerfectBottomNavBar` have zero references to `playbackPositionMs`.

### Full APK Phase 1 Software Verification (`./gradlew testDebugUnitTest lintDebug assembleDebug`)
- **JVM Unit Tests:** **90 / 90 passed** across **18 suites** (`0` failed, `0` errors, `0` skipped, `0` `@Ignore`), including `PlayCountTrackerTest` (7/7) and `M3u8PlaylistCodecTest` (4/4).
- **Kotlin & KSP Compiler Warnings:** `0` Kotlin warnings, `0` KSP warnings.
- **Android Lint (`lintDebug`):** **0 errors**, **0 warnings across all `/app/src/` Kotlin, manifest, and resource files** (15 remaining informational notices are strictly upstream dependency version notices in `gradle/libs.versions.toml` and AAR `ObsoleteLintCustomCheck`).
- **Debug APK:** `app/build/outputs/apk/debug/app-debug.apk` (`19.95 MB`).
- **Strict Offline Privacy Audit (Source + All Merged Debug Manifests):** **PASS** — **0 `android.permission.INTERNET`**, **0 `android.permission.MANAGE_EXTERNAL_STORAGE`**.
- **Pure-Kotlin Domain Boundary:** **PASS** — **0 `android.*` imports** across all **20** pure-Kotlin domain modules (adding `PlayCountTracker` and `M3u8PlaylistCodec`).
- **Physical Hardware Verification Pending (Not Executed in Cloud):** On POCO F4 (Android 14 / HyperOS) + KZ EDX Pro 2 USB-C, verify in-place Room v2 -> v3 upgrade preservation, live `Most Played` increment after 30s real listening (vs. non-increment on 3s skip or seek-to-end), SAF `.m3u8` export/import round-trip, and smooth 3-tab switching during active playback.

---

## 9. Full APK Phase 2 Summary (True Gapless Playback, Clean EOS Drain, & Safe-Mode-Only ReplayGain)

Implemented true gapless playback, eliminated end-of-track audio truncation, and added Safe-Mode-only ReplayGain with mandatory Bit-Perfect bypass on `feat/mvp-overnight`:

1. **Updated Audio Safety Rules & Specification (`.agents/rules/audio-safety-and-architecture.md`, `SPEC.md`, `DECISIONS.md`):**
   - Explicitly authorized optional local **ReplayGain** (`OFF` by default) exclusively in `SAFE_VOLUME_MODE`, with mandatory automatic bypass (`gain = 1.0f`, zero PCM sample modification) whenever `BIT_PERFECT_OPT_IN` mode is active. Documented rationale in `DECISIONS.md` (`D7.2`).
2. **True Gapless Playback & Zero End-of-Track Clipping (`GaplessTransitionPolicy.kt`, `PlaybackQueueManager.kt`, `AudioOutputEngine.kt`, `PlaybackController.kt`):**
   - Persisted `gaplessEnabled: Boolean = true` in `UserPreferencesRepository` / `PersistenceModels`.
   - Added pure-Kotlin `GaplessTransitionPolicy` (zero `android.*` imports) deciding `SEAMLESS_REUSE` vs `DRAIN_AND_REBUILD` vs `STOP_END_OF_QUEUE`, preload trigger lead window (`8,000 ms`, or immediate for short tracks), and bounded drain timeouts (`250..1,500 ms`).
   - Added non-mutating `PlaybackQueueManager.peekNext(...)` and wired `PlaybackController.setNextTrack(...)` across all queue, shuffle, repeat, and active-track changes.
   - Pre-opens and primes the next `PcmAudioDecoder` asynchronously on a dedicated background `BitPerfectGaplessPreload` thread with strict generation/URI validation and guaranteed cancellation/close on manual Next/Previous/Seek/Stop/Queue-change/Release (zero `MediaCodec` or FD leaks).
   - **Same PCM format (`sampleRateHz`, `channelCount`, `encoding`) with `gaplessEnabled == true`:** Reuses the exact running `AudioTrack` (`SEAMLESS_REUSE`), swaps decoders immediately after the last PCM frame with 0 ms silence gap, anchors `headBaseFrames = totalFramesWrittenToTrack` for seamless `0 ms` hardware clock handoff, and invokes `onGaplessTransition` so `PlaybackController` advances queue/history/play-count without rebuilding `AudioTrack`.
   - **Format mismatch or end-of-queue:** Cleanly drains `AudioTrack`'s remaining buffered frames via `drainAudioTrackCleanly` (`track.stop()` + bounded `playbackHeadPosition` wait) instead of `pause() + flush()`, preventing ~100–300 ms end-of-track truncation.
3. **Pure-Kotlin ReplayGain Tag Parser, Safe-Mode Gain Processor, Stage 3 Diagnostics, & Settings Controls (`ReplayGainTagParser.kt`, `ReplayGainProcessor.kt`, `AudioDiagnosticMapper.kt`, `SettingsScreen.kt`):**
   - Created pure-Kotlin OOM-safe header-only `ReplayGainTagParser` (zero `android.*` imports, positional reads on `SeekableByteSource`) supporting FLAC `VORBIS_COMMENT`, OGG Vorbis `\x03vorbis`, OGG Opus `OpusTags` (+ RFC 7845 `R128_TRACK_GAIN` / `R128_ALBUM_GAIN`), MP3 ID3v2.3/v2.4 `TXXX` (ISO-8859-1, UTF-8, UTF-16 BOM), and M4A/MP4 `moov->udta->meta->ilst->----` freeform atoms (+ optional `iTunNORM` fallback).
   - Created pure-Kotlin zero-allocation in-place `ReplayGainProcessor` (`OFF` default, `TRACK`, `ALBUM`, `-6.0..+6.0 dB` preamp, peak-clipping clamp `1.0f / peak`), scaling `PCM_FLOAT`, `PCM_16BIT`, `PCM_24BIT_PACKED`, and `PCM_32BIT` ONLY in `SAFE_VOLUME_MODE` and strictly bypassing (`gain = 1.0f`, zero sample modification) whenever `BIT_PERFECT_OPT_IN` is active.
   - Updated `AudioDiagnosticMapper` Stage 3 (`APP_TO_FRAMEWORK`) to honestly report `ReplayGain: OFF`, `ReplayGain: Track (-4.2 dB, Safe Mode)`, or `ReplayGain: Bypassed (Bit-Perfect Active)`.
   - Persisted `replayGainMode`, `replayGainPreampDb`, and `replayGainPreventClipping` in `UserPreferencesRepository` and exposed clean obsidian Gapless + ReplayGain controls in `SettingsScreen`.

### Full APK Phase 2 Software Verification (`./gradlew testDebugUnitTest lintDebug assembleDebug`)
- **JVM Unit Tests:** **108 / 108 passed** across **21 suites** (`0` failed, `0` errors, `0` skipped, `0` `@Ignore`), including `GaplessTransitionPolicyTest` (6/6), `ReplayGainProcessorTest` (6/6), `ReplayGainTagParserTest` (5/5), updated `AudioDiagnosticMapperTest` (5/5), and updated `PersistenceAndPreferencesTest` (4/4).
- **Kotlin & KSP Compiler Warnings:** `0` Kotlin warnings, `0` KSP warnings.
- **Android Lint (`lintDebug`):** **0 errors**, **0 warnings across all `/app/src/` Kotlin, manifest, and resource files**.
- **Debug APK:** `app/build/outputs/apk/debug/app-debug.apk` (`19.97 MB`).
- **Strict Offline Privacy Audit (Source + All Merged Debug Manifests):** **PASS** — **0 `android.permission.INTERNET`**, **0 `android.permission.MANAGE_EXTERNAL_STORAGE`**.
- **Pure-Kotlin Domain Boundary:** **PASS** — **0 `android.*` imports** across all **23** pure-Kotlin domain modules (adding `GaplessTransitionPolicy`, `ReplayGainProcessor`, and `ReplayGainTagParser`).
- **Physical Hardware Verification Pending (Not Executed in Cloud):** On POCO F4 (Android 14 / HyperOS) + KZ EDX Pro 2 USB-C, verify continuous live-album/gapless FLAC transitions with 0 ms silence gap, clean unclipped track endings on sample-rate switches (e.g., 96,000 Hz -> 44,100 Hz), audible ReplayGain leveling in Safe Mode, and instant automatic ReplayGain bypass upon opting into Bit-Perfect Mode (verified in Diagnostics Stage 3).

---

## 10. Full APK Phase 3 Summary (Full Settings Screen, Offline Local Backup & Restore, Sleep Timer, Track Info Sheet, & Daily UX Polish)

Completed the Full-APK daily user experience on `feat/mvp-overnight` while keeping the 100% offline boundary, hearing safety invariants, and 120 Hz scroll/UI isolation strictly intact:

1. **Full Utilitarian `SettingsScreen` (`SettingsScreen.kt`):**
   - Replaced the earlier Settings shell with a flat, grouped obsidian dark screen (`#0E0E10` background, `#141417` grouped cards, `0.5.dp` hairline dividers, `#7BA7D9` accent, zero neon clutter, `>= 48.dp` touch targets) structured into four sections:
     - **Audio Engine:** Gapless Playback toggle, ReplayGain mode selector (`Off` / `Track` / `Album`), Pre-amp slider (`-6 dB` to `+6 dB`), Prevent Clipping toggle with explicit note *"Automatically bypassed in Bit-Perfect Opt-In mode."*, Sleep Timer row with isolated countdown leaf (`SleepTimerSettingsValueLeaf`), and 6-Stage Audio Diagnostics shortcut.
     - **Library & Storage (100% Offline):** Linked SAF folders list displaying persisted folder name/URI with per-folder Remove action (`viewModel.removeSafFolder(...)`), plus **Add Folder (SAF)** and **Refresh Library** controls.
     - **Local Backup & Restore (100% Offline via SAF):** **Export Backup (`.json`)** via `CreateDocument("application/json")` and **Import / Restore Backup (`.json`)** via `OpenDocument` using non-destructive merge/upsert.
     - **About & Privacy:** Concise offline verification badge confirming zero `INTERNET` / `MANAGE_EXTERNAL_STORAGE` permissions plus app version (`BuildConfig.VERSION_NAME`).
2. **100% Offline Local Backup & Restore (`LocalBackupCodec.kt`, `LocalBackupRepository.kt`, `LocalDaos.kt`, `LocalEntities.kt`, `LocalPersistenceRepository.kt`):**
   - Built pure-Kotlin `LocalBackupCodec` (zero `android.*` / zero `org.json.*` / zero external serialization dependencies) implementing an RFC 8259 JSON encoder and strict validating recursive-descent parser with `schemaVersion = 1` and explicit rejection of malformed JSON or unsupported schema versions.
   - Backs up and restores **Playlists** (with ordered track entries), **Saved Lyrics (keyed by SHA-256 content fingerprint)**, **Favorites**, and **Play Count / Listening Stats** via SAF `content://` streams with a 16 MB read guard.
   - Non-destructive merge/upsert preserves existing playlists/favorites/lyrics while deduplicating playlist names/entries and taking `max(playCount)` / `max(totalPlayTimeMs)` / `max(lastPlayedAtEpochMs)` on track stats conflicts.
3. **Sleep Timer Engine & Safe-Mode Linear Fade (`SleepTimerController.kt`, `AudioOutputEngine.kt`, `PlaybackController.kt`, `SleepTimerSheet.kt`):**
   - Added pure-Kotlin `SleepTimerController` supporting presets `Off`, `15 min`, `30 min`, `45 min`, `60 min`, and `End of current track`.
   - In `SAFE_VOLUME_MODE`: applies a smooth 3-second linear volume fade-out (`1.0f -> 0.0f` over 30 steps) via `AudioOutputEngine.setSafeModeOutputVolume(...)` before pausing and immediately restoring unity gain (`1.0f`).
   - In `BIT_PERFECT_OPT_IN`: strictly performs **zero** digital volume scaling (`AudioOutputEngine.setSafeModeOutputVolume` refuses scaling in Bit-Perfect mode) and pauses cleanly on timer expiration.
   - In `End of current track` mode: `PlaybackController.syncNextTrackToEngine()` suppresses gapless next-track preloading (`setNextTrack(null)`) so the active track drains cleanly and pauses at natural track completion without advancing.
   - Exposed `sleepTimerState: StateFlow<SleepTimerState>` independently of `playbackState` and collected it exclusively inside narrow leaf composables (`SleepTimerTopBarButton`, `SleepTimerSettingsValueLeaf`, and `SleepTimerBottomSheet`) so 1 Hz countdown ticks never recompose `LibraryScreen`, `PlaylistsScreen`, `SettingsScreen` root, `QueueScreen`, `DiagnosticsScreen`, `BottomNavigationBar`, or `BitPerfectAppRoot`.
4. **Track Info / Technical Details Bottom Sheet (`TrackInfoLoader.kt`, `TrackInfoSheet.kt`):**
   - Accessible from `"Track Info"` inside both `LibraryScreen`'s 3-dot `DropdownMenu` and `NowPlayingScreen`'s overflow menu.
   - Lazily loads file size, header-only ReplayGain Track/Album Gain & Peak tags (`ReplayGainTagParser`), deterministic SHA-256 content fingerprint, and Room `track_stats` on `Dispatchers.IO` ONLY when the sheet is opened (zero I/O during `LazyColumn` scroll).
   - Displays Metadata & File details (Title, Artist, Album, Folder/Relative Path, Filename, File Size in MB/KB, Duration), Audio Stream details (Container/Codec, exact Sample Rate in `Hz`, Bit Depth, ReplayGain tags), and Local Identity & Stats (abbreviated SHA-256 with tap-to-copy to clipboard, Play Count, Total Listening Time, Last Played date).
5. **Now Playing & Library UX Polish (`NowPlayingScreen.kt`, `LibraryScreen.kt`, `BitPerfectAppNav.kt`):**
   - Added horizontal swipe-to-skip (`detectHorizontalDragGestures`, `76.dp` threshold with damped artwork `translationX` feedback) on the `NowPlayingScreen` artwork hero so vertical scroll never triggers accidental skips.
   - Added `"Add to Playlist"`, `"Track Info"`, and `"Sleep Timer"` inside `NowPlayingScreen`'s conditionally-mounted overflow `DropdownMenu` (`if (overflowMenuExpanded)`), alongside the compact `SleepTimerTopBarButton` badge.
   - Decluttered `LibraryScreen`'s `TopAppBar` to three balanced icon actions (`Import Folder`, `Queue`, `Diagnostics`), removing the redundant single-file import icon.

### Full APK Phase 3 Software Verification (`./gradlew testDebugUnitTest lintDebug assembleDebug`)
- **JVM Unit Tests:** **115 / 115 passed** across **23 suites** (`0` failed, `0` errors, `0` skipped, `0` `@Ignore`), including `LocalBackupCodecTest` (3/3) and `SleepTimerControllerTest` (4/4).
- **Kotlin & KSP Compiler Warnings:** `0` Kotlin warnings, `0` KSP warnings.
- **Android Lint (`lintDebug`):** **0 errors**, **0 warnings across all `/app/src/` Kotlin, manifest, and resource files** (15 remaining findings are strictly upstream dependency version notices in `gradle/libs.versions.toml` and AAR `ObsoleteLintCustomCheck`).
- **Debug APK:** `app/build/outputs/apk/debug/app-debug.apk` (`20.02 MB` / `20,988,012 bytes`).
- **Strict Offline Privacy Audit (Source + All Merged Debug Manifests):** **PASS** — **0 `android.permission.INTERNET`**, **0 `android.permission.MANAGE_EXTERNAL_STORAGE`**.
- **Pure-Kotlin Domain Boundary:** **PASS** — **0 `android.*` or `org.json.*` imports** across all **25** pure-Kotlin domain modules (adding `LocalBackupCodec` and `SleepTimerController`).
- **120 Hz UI Hygiene Audit:** **PASS** — `0` `playbackPositionMs` or `sleepTimerState` collections in `LibraryScreen`, `PlaylistsScreen`, `SettingsScreen` root, `QueueScreen`, `DiagnosticsScreen`, `BottomNavigationBar`, or `BitPerfectAppNav`; all `DropdownMenu` instances strictly gated by `if (expanded)`.
- **Physical Hardware Verification Pending (Not Executed in Cloud):** On POCO F4 (Android 14 / HyperOS) + KZ EDX Pro 2 USB-C, verify:
  1. Settings SAF folder add/remove + full `.json` backup export/restore round-trip via system document picker.
  2. Audible 3-second Safe-Mode linear fade-out on Sleep Timer expiry (and zero digital volume scaling in Bit-Perfect Opt-In mode), plus clean `End of current track` pause with gapless enabled.
  3. Lazy Track Info sheet opening smoothly from both `LibraryScreen` and `NowPlayingScreen`, copying the SHA-256 fingerprint, and rendering real ReplayGain tags and play stats.
  4. Artwork horizontal swipe-to-skip feel on `NowPlayingScreen` and 120 Hz scroll smoothness on `LibraryScreen` while Sleep Timer and playback are active simultaneously.





