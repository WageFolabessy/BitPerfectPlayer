# BitPerfectPlayer MVP — Autonomous Build Handoff Report

- **Project:** `com.cangkir.bitperfectplayer` (Kotlin + Jetpack Compose, 100% Offline Android Music Player)
- **Active Branch:** `feat/v1.3` *(branched from `main`; `versionName = "1.3"`, `versionCode = 4`, ready for final `v1.3` audit & POCO F4 testing)*
- **Overall Status:** **`v1.3` Feature-Complete (Monochrome Studio, Dynamic Cover Theme, Multi-Artwork Parser, Artist Detail Redesign, MiniPlayer Queue, & `MediaSession` Polish) — Software-Verified (`versionName = "1.3"`, `versionCode = 4`)** *(305 / 305 automated tests passing across 48 suites with `ktlintCheck`, `detekt`, Android Lint `0` `app/src/` warnings, Debug APK, and R8 Release APK clean)*
- **Tasks Completed:** **17 / 17** (`T0.1` through `T5.4` in `docs/TASKS.md`) + `v1.1` + `v1.2` + `v1.3` Prompts 1–3 verification
- **Active Blockers:** None (`BLOCKED.md` not required)

---

## 1. Implementation Summary

All 5 milestones from `docs/TASKS.md` were implemented sequentially in production Kotlin with zero mock playback engines, zero placeholder buttons, and zero `TODO()` stubs:

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
| **M5: Final Wiring & Audit** | `T5.4` | `404352e` | `BitPerfectAppNav.kt` & `MainActivity.kt` end-to-end Compose Navigation wiring, Android Lint resolution (`MODIFY_AUDIO_SETTINGS` for API 34+ mixer APIs, `minSdk = 26` `MediaFormat` fix), full audit, and `docs/HANDOFF_REPORT.md`. |

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
| `org.robolectric:robolectric` (`testImplementation` only) | `4.18.1` | Headless JVM Compose UI behavioral test execution pinned to API 34 (`@Config(sdk = [34])`) | MIT / Apache 2.0 |

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
7. **Background Playback & Resume State (Including Android 17 `targetSdk = 37` Hardening):**
   - Play audio, lock screen, test notification & lock-screen Play/Pause/Next/Previous/Seek controls via `MediaSession`.
   - On Android 17 (`API 37`) devices/test builds, enable strict background audio hardening enforcement via:
     `adb shell cmd audio set-enable-hardening throw`
     Then background the app, pause, wait or dismiss notification, and resume from the lock screen / notification / headset button (`MediaSession.Callback.onPlay()`); verify `ServiceCompat.startForeground(..., FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)` promotes before `AudioManager.requestAudioFocus(...)` / `AudioTrack.play()` with zero `AUDIOFOCUS_REQUEST_FAILED` rejections, zero silent muting, and zero hardening exceptions.
   - Force-close or swipe app away while paused, relaunch, and confirm last track & playback position restore cleanly in paused state (`autoPlay = false`, zero background FGS start or audio focus request).
8. **Adaptive Landscape Rotation & Split-Screen Multi-Window (`v1.1`):**
   - On POCO F4 in portrait (`< 600.dp`), confirm bottom `NavigationBar`, `MiniPlayerBar`, and single-column `NowPlayingScreen` (`316.dp` radial spectrum ring + `240.dp` spinning vinyl disc) match `v1.0` with zero layout shift.
   - Rotate POCO F4 to landscape (`>= 600.dp`): confirm top-level tabs switch smoothly to the left `NavigationRail` with `MiniPlayerBar` docked in the right content pane, and `NowPlayingScreen` switches to the 2-column studio layout (left pane: proportionally scaled spinning Vinyl Disc + Radial Spectrum Ring with zero vertical clipping; right pane: metadata, seek slider, transport buttons, audio-mode strip, and utility buttons) without interrupting audio playback or vinyl rotational scrubbing.
   - Enter Android split-screen multi-window mode and resize the divider: confirm smooth reflow between compact and wide layouts with all touch targets `>= 48.dp`.

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
     - **About & Privacy:** Concise offline verification badge confirming zero `INTERNET` / `MANAGE_EXTERNAL_STORAGE` permissions plus app version (`PackageInfo.versionName` via `PackageManager.getPackageInfo`).
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

---

## 11. Metadata Track/Disc Ordering, Context-Aware Playback Queue, & Lightweight Context-Aware Sorting

Implemented metadata disc/track extraction (zero scan regression), scoped context-aware playback queues & session restoration, and minimalist background-computed context-aware sorting on `feat/mvp-overnight`:

1. **Metadata Track & Disc Number Extraction + Room v3 -> v4 Migration (`TrackNumberParser.kt`, `BitPerfectDatabase.kt`, `LocalEntities.kt`, `LocalDaos.kt`, `AudioDecoderModels.kt`, `AudioMetadataReader.kt`, `LocalLibraryRepository.kt`):**
   - Added `discNumber: Int = 0`, `trackNumber: Int = 0`, and precomputed `trackNumberLabel` to `PlaybackTrack`, and `disc_number` / `track_number` (`INTEGER NOT NULL DEFAULT 0`) to `CachedTrackEntity`.
   - Upgraded `BitPerfectDatabase` to `version = 4` with non-destructive `MIGRATION_3_4` (`ALTER TABLE cached_tracks ADD COLUMN ...`) preserving all 7 existing tables (`cached_tracks`, `lyrics`, `track_history`, `favorite_tracks`, `playlists`, `playlist_tracks`, `track_stats`) intact (documented in `DECISIONS.md` `D8.1`).
   - Built pure-Kotlin `TrackNumberParser` unpacking Android MediaStore `TRACK` integers (`1003` -> disc `1`, track `3`; `2004` -> disc `2`, track `4`; `7` -> disc `0`, track `7`), parsing fraction/leading-zero tags (`"3/12"`, `"03"`, `"1/2"`), and safely falling back to leading filename numbers (`"04 - Acid Rain.flac"` -> `4`, rejecting 4-digit years like `"2024 - Live.flac"` and ordinals like `"21st Century Breakdown.flac"`) ONLY when both MediaStore and tag track numbers are absent.
   - Queried `MediaStore.Audio.Media.TRACK` (+ API 30+ `CD_TRACK_NUMBER`/`DISC_NUMBER`) directly inside `LocalLibraryRepository.discoverMediaStoreCandidates()` with zero extra `MediaMetadataRetriever` Binder IPC, and added a fast one-time transactional cursor-only backfill (`CachedTrackDao.backfillTrackPositions`) for unchanged cached rows with `disc == 0 && track == 0`.
   - Extended `FlacStreamInfoParser` to walk past `STREAMINFO` (seeking past `PICTURE` blocks) to parse `VORBIS_COMMENT` `TRACKNUMBER`/`DISCNUMBER` on the same open `FileChannel`, and extracted `METADATA_KEY_CD_TRACK_NUMBER` / `METADATA_KEY_DISC_NUMBER` in `AudioMetadataReader` for SAF files.
2. **Context-Aware Playback Queue (`QueueContext.kt`, `PlaybackQueueManager.kt`, `PlaybackController.kt`, `UserPreferencesRepository.kt`, `QueueScreen.kt`, `NowPlayingScreen.kt`):**
   - Created pure-Kotlin `QueueContext` (`ALL_SONGS`, `ALBUM`, `ARTIST`, `FOLDER`, `PLAYLIST`, `RECENT`, `FAVORITES`, `CUSTOM`), `BrowsingQueueContext`, and `QueueContextResolver`.
   - Stored `queueContext` in `PlaybackControllerState` and persisted `last_queue_source_type`, `last_queue_source_key`, and `last_queue_source_label` in `UserPreferencesRepository`.
   - Fixed `PlaybackController.restoreLastSessionIfNeeded(...)` using `QueueContextResolver` so app relaunch restores ONLY the exact saved Album, Artist, Folder, Playlist, Recent, Favorites, or Most Played track list in its sorted order (waiting for Room playlists readiness when restoring a Playlist) rather than all library songs.
   - Updated every playback trigger across `LibraryScreen`, `GroupDetailTrackList`, and `PlaylistsScreen` to pass the exact `QueueContext`, added batch `Play Next` (`enqueueAllNext`) and `Add All to Queue` (`enqueueAll`) actions to every Album/Folder/Artist/Playlist detail header, and surfaced active queue context in `QueueScreen`'s TopAppBar subtitle and subtly in `NowPlayingScreen`.
   - Opening `QueueScreen` from inside a viewed Album/Folder/Artist/Playlist prepares that context paused when `IDLE`, or displays a flat one-tap `"Viewing <Type>: <Name> (<N> tracks)"` + `"Load Queue"` banner without interrupting active playback; added search + `Current Context` / `All Songs` scope toggle inside `AddTrackToQueueDialog`.
3. **Lightweight Context-Aware Sorting (`LibrarySortPolicy.kt`, `LibraryProjector.kt`, `PlaylistRepository.kt`, `SortSheet.kt`, `LibraryScreen.kt`, `PlaylistsScreen.kt`):**
   - Created pure-Kotlin `LibrarySortPolicy` with natural filename comparison (`"2 - Song"` < `"10 - Song"`) and independent persisted per-scope sort preferences: Album Detail defaults to `TRACK_NUMBER ASC` (Disc -> Track -> Natural Filename -> Title), Artist Detail to `ALBUM ASC` (Album -> Disc -> Track), Folder Detail to `TRACK_NUMBER ASC`, All Songs to `TITLE ASC`, Group tabs to `NAME ASC`, Playlists Detail to `CUSTOM_ORDER ASC`, and Recent/Favorites to chronological defaults.
   - Executed ALL sorting strictly on `Dispatchers.Default` via reactive Flows inside `LocalLibraryRepository` and `PlaylistRepository` (zero list sorting inside Compose `ui/`).
   - Added a single subtle right-aligned inline sort trigger (`Icons.AutoMirrored.Filled.Sort` + concise active label, `>= 48.dp` touch target) on existing subtitle/count header rows opening a flat matte-obsidian `ModalBottomSheet` (`TrackSortBottomSheet` / `GroupSortBottomSheet`) with only scope-relevant options and an `Ascending / Descending` toggle.

### Software Verification (`./gradlew testDebugUnitTest lintDebug assembleDebug`)
- **JVM Unit Tests:** **128 / 128 passed** across **26 suites** (`0` failed, `0` errors, `0` skipped, `0` `@Ignore`), adding `TrackNumberParserTest` (5/5), `LibrarySortPolicyTest` (5/5), `QueueContextTest` (3/3), and expanding `PersistenceAndPreferencesTest` (4/4).
- **Kotlin & KSP Compiler Warnings:** `0` Kotlin warnings, `0` KSP warnings.
- **Android Lint (`lintDebug`):** **0 errors**, **0 warnings across all `/app/src/` Kotlin, manifest, and resource files**.
- **Debug APK:** `app/build/outputs/apk/debug/app-debug.apk` (`20.08 MB` / `21,059,968 bytes`).
- **Strict Offline Privacy Audit (Source + All Merged Debug Manifests):** **PASS** — **0 `android.permission.INTERNET`**, **0 `android.permission.MANAGE_EXTERNAL_STORAGE`**.
- **Pure-Kotlin Domain Boundary:** **PASS** — **0 `android.*`, `androidx.*`, or `org.json.*` imports** across all **28** pure-Kotlin domain modules (adding `TrackNumberParser`, `QueueContext`, and `LibrarySortPolicy`).
- **120 Hz UI Hygiene Audit:** **PASS** — `0` sort calls in `ui/*.kt`, `0` high-frequency `playbackPositionMs`/`sleepTimerState` collections in `LibraryScreen`/`PlaylistsScreen`/`QueueScreen`/`DiagnosticsScreen`/`BitPerfectAppNav`, and all 6 `DropdownMenu` instances strictly gated by `if (expanded)`.
- **Physical Hardware Verification Pending (Not Executed in Cloud):** On POCO F4 (Android 14 / HyperOS) + KZ EDX Pro 2 USB-C, verify:
  1. In-place Room v3 -> v4 upgrade preserving all existing playlists, lyrics, favorites, and play counts, followed by instant cursor-only backfill ordering existing albums (e.g., *21st Century Breakdown*) by Disc -> Track #.
  2. Cold-start relaunch restoring ONLY the last played Album / Folder / Playlist queue (paused, never auto-blasting audio) instead of All Songs.
  3. Opening `QueueScreen` from inside an Album/Folder/Playlist while idle (auto-prepared paused) vs. while another context plays (non-intrusive one-tap `"Load Queue"` banner) and `Add Track to Queue` search + scope toggle.
  4. Independent sorting across tabs/details (e.g., sorting All Songs by Hi-Res while Album Detail stays `Track # ↑`) with smooth 120 Hz scrolling.

---

## 12. Automated Headless Compose UI Behavioral Tests (`Robolectric 4.18.1` + `@Config(sdk = [34])`)

### Delivered Setup & Behavioral Test Suite
1. **Robolectric + Compose UI Test Setup (`gradle/libs.versions.toml`, `app/build.gradle.kts`):**
   - Added `org.robolectric:robolectric:4.18.1` (MIT / Apache 2.0 permissive license) exclusively to `testImplementation`, alongside `testImplementation(platform(libs.androidx.compose.bom))` and `testImplementation(libs.androidx.compose.ui.test.junit4)`.
   - Configured `testOptions { unitTests { isIncludeAndroidResources = true } }` in `app/build.gradle.kts`.
   - Pinned Robolectric tests explicitly to `@Config(sdk = [34], application = Application::class)` so they execute deterministically on Android 14 (API 34) under JDK 25 + AGP `9.5.1` + `compileSdk = 37` without requiring an Android emulator or KVM.
2. **Headless Behavioral UI Test Suite (`app/src/test/java/com/cangkir/bitperfectplayer/ui/BitPerfectUiBehaviorTest.kt` — 6 / 6 passing):**
   - `hearingSafetyWarningDialog_displaysMandatoryGateAndInvokesExactCallbacks`: Renders `NowPlayingScreen` with `isPendingSafetyWarning = true`, asserts `"Hearing Safety Warning"`, `"Stay in Safe Mode"`, and `"Enable Bit-Perfect"` are displayed, and verifies clicking each button invokes its exact callback (`onDismissSafetyWarning` / `onConfirmBitPerfectWarning`).
   - `libraryScreen_favoriteHeartTogglePropagatesImmediatelyWhenStateUpdates`: Renders `LibraryScreen`, clicks the favorite heart icon (`"Add Peacemaker to favorites"`), verifies `onToggleFavorite` is invoked with the track, and verifies the semantic description immediately updates to `"Remove Peacemaker from favorites"` when `favoriteTrackUris` updates.
   - `libraryScreen_rendersExactHzUnitsWithoutKhzOrDuplicateContainerCodecAndShowsDuration`: Renders `LibraryScreen` with a `96,000 Hz` 24-bit FLAC track, verifies `"Peacemaker"`, `"FLAC · 24-bit · 96,000 Hz"`, and `"03:24"` are displayed, and asserts zero nodes contain `"kHz"` or `"FLAC (FLAC)"`.
   - `playlistsScreen_createPlaylistDialogAcceptsNameAndInvokesCreateCallback`: Renders `PlaylistsScreen` in empty state, clicks `"Create Playlist"`, verifies the `"New Playlist"` dialog opens, enters `"Audiophile Mix"`, clicks `"Create"`, and verifies `onCreatePlaylist("Audiophile Mix")` is invoked.
   - `trackInfoSheet_displaysExactHzPlayCountAndSha256CopyCallback`: Renders `TrackInfoBottomSheet` with a full `TrackTechnicalInfo` payload, verifies `"96,000 Hz"`, `"24-bit"`, and `"7 plays"` are displayed, and verifies clicking the SHA-256 fingerprint row invokes the clipboard copy callback (`"Copied SHA-256 fingerprint"`).
   - `sleepTimerSheet_rendersPresetsAndInvokesSelectionCallback`: Renders `SleepTimerBottomSheet`, verifies `"Sleep Timer"` and `"End of current track"` are displayed, and verifies clicking `"30 min"` invokes `onSelectPreset(SleepTimerPreset.MIN_30)` and `onDismiss`.

### Software Verification (`./gradlew testDebugUnitTest lintDebug assembleDebug`)
- **JVM Unit + Headless Compose UI Tests:** **134 / 134 passed** across **27 suites** (`0` failed, `0` errors, `0` skipped, `0` `@Ignore`), including **6 / 6** in `BitPerfectUiBehaviorTest`.
- **Kotlin & KSP Compiler Warnings:** `0` Kotlin warnings, `0` KSP warnings.
- **Android Lint (`lintDebug`):** **0 errors**, **0 warnings across all `/app/src/` Kotlin, manifest, and resource files**.
- **Debug APK:** `app/build/outputs/apk/debug/app-debug.apk` (`20.01 MB` / `20,982,352 bytes`).
- **Strict Offline Privacy Audit (Source + All Merged Debug & UnitTest Manifests):** **PASS** — **0 `android.permission.INTERNET`**, **0 `android.permission.MANAGE_EXTERNAL_STORAGE`**.

---

## 13. Final Pre-Hardware Deep Audit Report

### 1. Bugs, Edge Cases & Inefficiencies Discovered & Fixed
1. **Deferred `play()` After `autoPlay = false` Preparation Did Not Record `Recently Played` History (`PlaybackController.kt`):**
   - **Discovery:** Tracks prepared in a paused state (`autoPlay = false` during cold-start session restoration, `prepareContextQueueIfIdle`, `loadContextQueue(autoPlay = false)`, or enqueuing into an empty queue) skipped `recordTrackPlayed` during preparation, and subsequent calls to `play()` saw `engine.state.value.activeTrackUri != null` and started the engine without recording the track into `track_history`.
   - **Fix:** Added `hasRecordedHistoryForActiveTrack` tracking in `PlaybackController` so calling `play()` on a prepared-paused track (or replaying after `PlaybackStatus.COMPLETED`) records `recordTrackPlayed` immediately upon actual playback start.
2. **Post-Completion (`PlaybackStatus.COMPLETED`) Replay & Near-EOS Session Restore (`AudioOutputEngine.kt`, `PlaybackController.kt`):**
   - **Discovery:** When the final track in a queue finished naturally (`PlaybackStatus.COMPLETED`), `drainAudioTrackCleanly` called `AudioTrack.stop()` (leaving `AudioTrack` in `PLAYSTATE_STOPPED` and `MediaCodec` at `outputEos = true`). Pressing `Play` afterward previously called `decoder.seekToMs(0L)` on the post-EOS codec and reused the stopped `AudioTrack` without resetting its playback head base. Additionally, if the app was closed at natural EOS (`positionMs == durationMs`), cold-start restoration restored the track at the very end (`0 ms` left).
   - **Fix:** Updated `AudioOutputEngine.play()` to reload `currentRequest` from `startPositionMs = 0L, autoPlay = true` via `loadTrackLocked` whenever `status == PlaybackStatus.COMPLETED` (initializing a fresh decoder and fresh `AudioTrack` at `0L`), and updated `PlaybackController.restoreLastSessionIfNeeded()` to reset `restoredPos = 0L` when `rawLastPos >= durationMs - 1,500 ms`.
3. **EOS Buffer Drain Responsiveness to Pause/Seek & OEM `playbackHeadPosition` Reset (`AudioOutputEngine.kt`):**
   - **Discovery:** `drainAudioTrackCleanly()` only checked `!stopWorkerFlag.get() && !releasedFlag.get()`, ignoring `isPausedFlag.get()` and `pendingSeekMs.get() >= 0L` during the tail drain window (~250–1,500 ms). Furthermore, OEM HALs that reset `AudioTrack.playbackHeadPosition` to `0` upon completing `AudioTrack.stop()` drain could cause the post-drain `accumulateActivePlayTime(forceFlush = true)` call to see `headFrames = 0 < countHeadFrames` and drop the final ~100–300 ms of listening time.
   - **Fix:** Updated `drainAudioTrackCleanly()` to break immediately on `isPausedFlag.get()`, `pendingSeekMs.get() >= 0L`, or `head < headBefore`, return `maxReachedHead`, credit `maxReachedHead` in `accumulateActivePlayTime`, and honor any seek requested during drain by continuing the playback loop instead of marking the track `COMPLETED`.
4. **Gapless Preloader Trigger When Seeking Directly Into the Final 8 Seconds (`AudioOutputEngine.kt`):**
   - **Discovery:** Seeking directly into the final 8 seconds of a track did not reset `lastUiUpdateNano = 0L` or immediately check `GaplessTransitionPolicy.shouldStartPreload(...)`, allowing very short remaining buffers (< 1.5s) to hit decoder EOS before the next 50 ms UI tick launched the preloader.
   - **Fix:** Reset `lastUiUpdateNano = 0L` and immediately check `GaplessTransitionPolicy.shouldStartPreload(...)` upon completing a seek in `runPlaybackLoop` as well as in `setNextTrack(...)` when already playing inside the final 8-second window.
5. **End-of-Track Sleep Timer Guard on Seamless Gapless Transition (`PlaybackController.kt`):**
   - **Discovery:** If `SleepTimerPreset.END_OF_TRACK` raced with an in-flight same-format `SEAMLESS_REUSE` transition callback, `handleGaplessTransition()` did not check `sleepTimerController.consumeEndOfTrackTrigger()`.
   - **Fix:** Added `sleepTimerController.consumeEndOfTrackTrigger()` handling to `handleGaplessTransition()`.
6. **Playlist Reordering With Unresolved Entries & `.m3u8` Import OOM Cap (`PlaylistRepository.kt`, `PlaybackController.kt`):**
   - **Discovery:** `PlaylistRepository.moveEntryInPlaylist()` indexed directly into `playlistDao.getTracksForPlaylist(playlistId)` rather than aligning with `PlaylistsUiSnapshot` visible resolved entries when orphaned rows exist; `importPlaylistFromM3u8()` used unbounded `input.readBytes()`; and `PlaybackController.removeQueueItem()` skipped reloading when two identical track URIs were queued consecutively and the active first slot was removed.
   - **Fix:** Aligned `moveEntryInPlaylist` with visible resolved entry IDs while preserving unresolved rows in place, added a 4 MB OOM safety cap (`MAX_M3U8_FILE_BYTES`) to `importPlaylistFromM3u8()`, and removed the `newActive.uriString != removed.uriString` guard when `activeIndex == previousActiveIndex` in `PlaybackController.removeQueueItem()`.
7. **Zero-I/O SAF Filename Track/Disc Backfill & Lyrics Fingerprint Caching (`LocalLibraryRepository.kt`, `LyricsRepository.kt`):**
   - **Discovery:** After upgrading to Room v4, unchanged `SAF_TREE`/`SAF_FILE` rows with `discNumber == 0 && trackNumber == 0` were not backfilled from numbered filenames (`"01 - Track.flac"`), and `LyricsRepository` did not cache newly computed SHA-256 fingerprints back into `cached_tracks`/`playlist_tracks`.
   - **Fix:** Added zero-I/O `TrackNumberParser.parseFilenamePosition(entry.fileName)` backfill for unchanged SAF rows in `LocalLibraryRepository.refreshLibrary()` and cached newly computed fingerprints via `persistenceRepository.updateCachedTrackFingerprint(...)` in `LyricsRepository`.
8. **Edge-to-Edge System Navigation Bar Inset & `LazyColumn` Key/ContentType Hygiene (`LyricsScreen.kt`, `QueueScreen.kt`, `DiagnosticsScreen.kt`):**
   - **Discovery:** Under `enableEdgeToEdge()`, `LyricsScreen`'s custom `bottomBar` (`LyricsCompactSeekBar`) lacked `.navigationBarsPadding()`, `QueueScreen`'s `LazyColumn` lacked an explicit item `key`, and `DiagnosticsScreen` / `LyricsScreen` static `LazyColumn` items lacked `contentType`.
   - **Fix:** Added `.navigationBarsPadding()` to `LyricsCompactSeekBar`, `key = { index, track -> "${index}_${track.uriString}" }` to `QueueScreen`, and explicit `contentType` to all items in `DiagnosticsScreen` and `LyricsScreen`.

### 2. Final Room Database Schema & Verified Migration Chain
- **Schema Version:** **v4** (`BitPerfectDatabase.kt`, 7 entities: `cached_tracks`, `lyrics`, `track_history`, `favorite_tracks`, `playlists`, `playlist_tracks`, `track_stats`).
- **Verified Migration Chain (`BitPerfectDatabaseMigrationTest.kt` — Robolectric + real SQLite + `RoomOpenDelegate.onValidateSchema`):**
  - `MIGRATION_1_2` (v1 -> v2): Creates `cached_tracks` + `index_cached_tracks_title`, preserving `lyrics`, `track_history`, and `favorite_tracks`.
  - `MIGRATION_2_3` (v2 -> v3): Creates `playlists`, `playlist_tracks` (with `FOREIGN KEY(playlist_id) REFERENCES playlists(id) ON UPDATE NO ACTION ON DELETE CASCADE` + `index_playlist_tracks_playlist_id_position_index`), and `track_stats` (+ `index_track_stats_play_count`, `index_track_stats_last_played_at_epoch_ms`).
  - `MIGRATION_3_4` (v3 -> v4): Executes `ALTER TABLE cached_tracks ADD COLUMN disc_number INTEGER NOT NULL DEFAULT 0` and `ALTER TABLE cached_tracks ADD COLUMN track_number INTEGER NOT NULL DEFAULT 0`, preserving all 7 tables and user data intact.

### 3. Final Verification & Build Results (`./gradlew clean testDebugUnitTest lintDebug assembleDebug assembleRelease`)
- **Automated JVM Unit + Robolectric SQLite Migration + Headless Compose UI Tests:** **141 / 141 passed** across **29 test suites** (`0` failed, `0` errors, `0` skipped, `0` `@Ignore`).
- **Compiler & R8 Warnings:** `0` Kotlin compiler warnings (production + test), `0` KSP warnings, `0` R8 warnings.
- **Android Lint (`lintDebug` & `lintVitalRelease`):** **0 errors**, **0 warnings across all `/app/src/` Kotlin, manifest, and resource files**.
- **Debug APK (`assembleDebug`):** `app/build/outputs/apk/debug/app-debug.apk` (`20.03 MB` / `20,998,736 bytes`).
- **Release APK (`assembleRelease` with R8 code & resource optimization):** `app/build/outputs/apk/release/app-release-unsigned.apk` (`2.40 MB` / `2,518,182 bytes`).
- **Strict Offline Privacy Audit (Source + All 5 Merged Debug, Release & UnitTest Manifests):** **PASS** — **0 `android.permission.INTERNET`**, **0 `android.permission.MANAGE_EXTERNAL_STORAGE`**.
- **Pure-Kotlin Domain Boundary:** **PASS** — **0 `android.*`, `androidx.*`, or `org.json.*` imports** across all **28** pure-Kotlin domain modules.

---

## 14. Post-Release UX Polish & Remaining Minor Code-Audit Fixes

Following physical testing of the signed Release APK on POCO F4 (Android 14 / HyperOS) confirming smooth, safe playback, the following UX polish items and remaining minor code-audit fixes were implemented and verified:

### Delivered Changes
1. **Auto-Scroll to Top on Sort & Search Changes (`LibraryScreen.kt`, `PlaylistsScreen.kt`):**
   - Added explicit `scrollResetKey: Any` parameters to `TrackListView`, `GroupListView`, `GroupDetailTrackList`, and `PlaylistDetailView` (keyed by active sort spec across every tab/detail and `Pair(sortPrefs.allSongs, searchQuery.trim())` on `All Songs`).
   - Added `val listState = rememberLazyListState()` paired with a remembered `previousResetKey` guard inside `LaunchedEffect(scrollResetKey)` so initial composition / returning from `NowPlayingScreen` preserves the user's scroll offset, while changing sort order or search query immediately calls `listState.scrollToItem(0)`.
2. **Removed Redundant `Pause` Button From `NowPlayingScreen` TopAppBar (`NowPlayingScreen.kt`, `BitPerfectAppNav.kt`, `MainViewModel.kt`):**
   - Removed the duplicate `"Pause"` `FilledTonalButton` from `NowPlayingScreen`'s `TopAppBar` actions along with the unused `onQuickPause` parameter, `FilledTonalButton` import, and `MainViewModel.pause()`.
   - `NowPlayingScreen`'s `TopAppBar` now cleanly holds only Back navigation, `Now Playing` (+ active queue context subtitle), `SleepTimerTopBarButton`, Favorite toggle, and the `MoreVert` overflow menu, while the primary `64.dp` central Play/Pause transport button remains prominent and accessible.
3. **Real-Time Context-Aware Playback Queue Switching (`QueueContext.kt`, `MainViewModel.kt`, `PlaybackController.kt`, `QueueScreen.kt`, `BitPerfectAppNav.kt`):**
   - Removed the `"Viewing ... Load Queue"` banner from `QueueScreen` along with dead `viewingBannerContext`, `onLoadBrowsingContext`, `browsingContext`, `_browsingQueueContext`, `loadBrowsingContextQueue`, `prepareContextQueueIfIdle`, and `QueueContext.formatViewingBannerText`.
   - Added pure-Kotlin `QueueContextResolver.shouldSwitchToBrowsingContext(...)` and updated `MainViewModel.prepareQueueScreenContext(browsingContext)` so opening `QueueScreen` from a different non-empty scoped view (Album, Artist, Folder, Playlist, Recent, Favorites, or Most Played — never `All Songs`) immediately replaces the active queue via `loadContextQueue(..., autoPlay = false)` (safely pausing prior playback and releasing audio focus cleanly without auto-blasting audio).
   - Opening `QueueScreen` from `NowPlayingScreen`, `All Songs`, or the same already-active context preserves active playback untouched. `QueueScreen`'s TopAppBar continues displaying `activeContext.formatQueueSubtitle(queue.size)`.
4. **Remaining Minor Code-Audit Fixes & Hairline Divider Alignment Polish (`PlaybackController.kt`, `GaplessTransitionPolicy.kt`, `AudioOutputEngine.kt`, `LibraryScreen.kt`, `PlaylistsScreen.kt`):**
   - **End-of-Track Sleep Timer Pause on Seamless Gapless Transition:** Added pure-Kotlin `PostGaplessTransitionDirective` / `GaplessTransitionPolicy.resolvePostTransitionDirective(...)` and updated `PlaybackController.handleGaplessTransition()` so when `pauseAfterTransition == true` on the normal transition path, it explicitly calls `engine.setNextTrack(null)`, `engine.pause()`, and `persistCurrentPositionAsync()`.
   - **Strict `SAFE_VOLUME_MODE` Guard on All `AudioTrack.setVolume(...)` Calls:** Guarded `AudioTrack.setVolume(...)` in `AudioOutputEngine.play()`, `AUDIOFOCUS_GAIN`, and `setSafeModeOutputVolume(...)` strictly with `safetyController.state().mode == AudioOutputMode.SAFE_VOLUME_MODE` so `BIT_PERFECT_OPT_IN` mode never invokes software `AudioTrack.setVolume(...)`. Also released audio focus cleanly when loading a track with `autoPlay = false`.
   - **Pixel-Exact Hairline Divider Alignment:** Aligned `TrackListView` row dividers to `start = if (showTrackNumber) 110.dp else 76.dp`, `GroupListView` row dividers to `start = if (showArtworkThumbnail) 76.dp else 72.dp`, and `MostPlayedTrackRow` dividers (`PlaylistsOverviewList` and `MostPlayedDetailView`) to `start = 90.dp`.

### Software Verification (`./gradlew clean testDebugUnitTest lintDebug assembleDebug assembleRelease`)
- **Automated JVM Unit + Robolectric SQLite Migration + Headless Compose UI Tests:** **146 / 146 passed** across **29 test suites** (`0` failed, `0` errors, `0` skipped, `0` `@Ignore`), adding tests in `QueueContextTest` (`shouldSwitchToBrowsingContext_switchesForDifferentScopedContextAndPreservesSameOrNull`), `GaplessTransitionPolicyTest` (`resolvePostTransitionDirective_pausesAndPersistsWhenEndOfTrackSleepTimerTriggers`), and `BitPerfectUiBehaviorTest` (`nowPlayingScreen_hasNoDuplicateTopBarPauseButtonWhilePrimaryTransportPauseWorks`, `libraryScreen_autoScrollsToTopWhenSortOrderChanges`, `queueScreen_displaysActiveContextSubtitleWithoutViewingBanner`).
- **Compiler & R8 Warnings:** `0` Kotlin compiler warnings (production + test), `0` KSP warnings, `0` R8 warnings.
- **Android Lint (`lintDebug` & `lintVitalRelease`):** **0 errors**, **0 warnings across all `/app/src/` Kotlin, manifest, and resource files**.
- **Debug APK (`assembleDebug`):** `app/build/outputs/apk/debug/app-debug.apk` (`20.03 MB` / `20,998,736 bytes`).
- **Release APK (`assembleRelease` with R8 optimization):** `app/build/outputs/apk/release/app-release-unsigned.apk` (`2.40 MB` / `2,518,182 bytes`).
- **Strict Offline Privacy Audit (Source + All 8 Generated Debug, Release & UnitTest Manifests):** **PASS** — **0 `android.permission.INTERNET`**, **0 `android.permission.MANAGE_EXTERNAL_STORAGE`**.
- **Physical Hardware Verification Pending (Not Executed in Cloud):** On POCO F4 (Android 14 / HyperOS) + KZ EDX Pro 2 USB-C, confirm on-device: (1) auto-scroll-to-top on sort/search changes while preserving scroll position when navigating back from `NowPlayingScreen`, (2) immediate paused queue switching when opening Queue from a different Album/Artist/Folder/Playlist vs. uninterrupted playback from the same Album or `NowPlayingScreen`, (3) clean TopAppBar on `NowPlayingScreen` and aligned dividers in Album Detail / Most Played, and (4) End-of-Track Sleep Timer pause across same-format gapless tracks and real USB-C DAC playback behavior.

---

## 15. Interactive Spinning Vinyl Record / Turntable Disc Mode

### Delivered Architecture & UX
1. **Pure-Kotlin Velocity-Sensitive Rotational Scrub Math (`VinylScrubMath.kt`):**
   - Implemented deterministic turntable geometry & physics with **0 `android.*` / `androidx.*` imports**: `computeShortestAngleDeltaDegrees` (`(-180f, 180f]` seam-safe wrapping), `isWithinScrubRing` (center spindle dead-zone & outer disc bounds), C1-smooth monotonic `computeMsPerFullRotation` (`<= 45 deg/s -> 8s`, `180 deg/s -> 30s`, `>= 540 deg/s -> 120s` via smoothstep), `Double`-precision `computeIncrementalScrubDeltaMs` (preserving sub-millisecond 120 Hz slow-drag accumulation), `smoothAngularVelocity` (EMA rejecting `dt < 1 ms` and resetting across pauses `dt > 200 ms`), `decayInertiaVelocity` (`damping = 7.5f`), `shouldStartInertiaCoast` (`>= 120 deg/s`), `clampScrubPositionMs`, and `formatSignedDeltaMs`.
2. **GPU-Accelerated Interactive Vinyl Disc Composable (`VinylDiscPlayer.kt`):**
   - Built `InteractiveVinylDisc` (`272.dp` diameter) in the matte obsidian studio palette: `graphicsLayer { rotationZ = ... }` isolates 60/120 fps spinning (20 deg/s playback auto-spin, instant freeze on pause without resetting to `0°`) with **0 recompositions and 0 relayouts**.
   - Renders matte dark vinyl (`#15151A` -> `#09090C`) with concentric micro-grooves, subtle track-band gaps, dark-metallic outer rim (`0.75.dp`), centered circular local artwork (`158.dp` via offline `AlbumArtworkLoader` + `remember(bitmap) { bitmap.asImageBitmap() }`), inner label ring, matte obsidian spindle hole (`14.dp`), and a **stationary** anisotropic studio sheen layer outside the rotating `graphicsLayer`.
   - Rotational touch scrubbing (`awaitEachGesture`) consumes drags cleanly over `verticalScroll`, updates a narrow isolated matte scrub HUD pill (`02:14  +00:19`) and `NowPlayingSeekSection` live without recomposing `NowPlayingScreen`, triggers subtle `TextHandleMove` haptic detents every `~22°`, supports catchable bounded flick inertia (`<= 450 ms`, stopping at `< 25 deg/s` or track bounds), ignores zero-movement taps, and commits `onSeekTo(...)` **strictly once** on release/settle to prevent `MediaCodec`/`AudioTrack` stutter on 96/192 kHz FLAC.
3. **Persisted Artwork Style Preference (`PersistenceModels.kt`, `UserPreferencesRepository.kt`, `MainViewModel.kt`, `NowPlayingScreen.kt`, `SettingsScreen.kt`, `BitPerfectAppNav.kt`):**
   - Added `NowPlayingArtworkStyle` (`VINYL_DISC` default, `SQUARE_COVER`) persisted in DataStore Preferences, exposed via `distinctUntilChanged()` `StateFlow`, collected strictly inside `NowPlaying` and `Settings` routes.
   - Added one-tap `"Switch to Square Cover"` / `"Switch to Vinyl Disc"` in `NowPlayingScreen`'s `MoreVert` overflow menu and a **"Now Playing Artwork"** segmented toggle (`Vinyl Disc` / `Square Cover`) in `SettingsScreen`.

### Software Verification (`./gradlew clean testDebugUnitTest lintDebug assembleDebug assembleRelease`)
- **Automated JVM Unit + Robolectric SQLite Migration + Headless Compose UI Tests:** **155 / 155 passed** across **30 test suites** (`0` failed, `0` errors, `0` skipped, `0` `@Ignore`), adding `VinylScrubMathTest` (7/7), extending `PersistenceAndPreferencesTest` (4/4), and extending `BitPerfectUiBehaviorTest` (11/11, including live spinning playback frame advancement, overflow style switching, and `SettingsScreen` toggle).
- **Compiler & R8 Warnings:** `0` Kotlin compiler warnings (production + test), `0` KSP warnings, `0` R8 warnings.
- **Android Lint (`lintDebug` & `lintVitalRelease`):** **0 errors**, **0 warnings across all `/app/src/` Kotlin, manifest, and resource files**.
- **Debug APK (`assembleDebug`):** `app/build/outputs/apk/debug/app-debug.apk` (`20.06 MB` / `21,031,504 bytes`).
- **Release APK (`assembleRelease` with R8 optimization):** `app/build/outputs/apk/release/app-release-unsigned.apk` (`2.43 MB` / `2,550,962 bytes`).
- **Strict Offline Privacy Audit (Source + All 8 Generated Debug, Release & UnitTest Manifests):** **PASS** — **0 `android.permission.INTERNET`**, **0 `android.permission.MANAGE_EXTERNAL_STORAGE`**.
- **Pure-Kotlin Domain Boundary (`VinylScrubMath.kt`):** **PASS** — **0 `android.*` / `androidx.*` imports**.
- **Physical Hardware Verification Pending (Not Executed in Cloud):** On POCO F4 (Android 14 / HyperOS, 120 Hz OLED) + KZ EDX Pro 2 USB-C, confirm tactile feel of slow/medium/fast rotational scrubbing, catchable flick inertia, zero scroll conflict with `verticalScroll`, haptic detent subtlety, zero audio stutter on 96/192 kHz FLAC seek commit, and stationary sheen visual depth.

---

## 16. Option A: Safe-Mode Real-Time Turntable Audio (Varispeed, Hold-to-Stop, & Reverse Scratch)

### Delivered Architecture & UX
1. **Pure-Kotlin Turntable DSP & Fixed-Cap Ring Buffer (`TurntableAudioDsp.kt`, `VinylScrubMath.kt`):**
   - Implemented `TurntablePcmRingBuffer` capped strictly at **8 MB** (`MAX_RING_FLOATS = 2_097_152`, rounded down to a whole multiple of `channelCount`) storing normalized interleaved float PCM (`-1.0f..1.0f`) with absolute monotonic frame indices (`oldestAvailableFrame`..`writeHeadFrame`), O(1) append, O(1) linear fractional-frame interpolation (`readInterpolatedFrame`), and zero-allocation `1.0x` draining (`copyFramesAt1x`).
   - Implemented `TurntablePcmCodec` for zero-allocation little-endian decode/encode across `PCM_FLOAT`, `PCM_16BIT`, `PCM_24BIT_PACKED`, and `PCM_32BIT` with strict saturation clamping (`[-1.0f, 1.0f]`).
   - Implemented `TurntableSpeedController` mapping signed angular velocity (`deg/s`) continuously and odd-symmetrically to playback rate (`[-4.0f..+4.0f]`: deadband `< 6 deg/s -> 0.0f` HOLD, `20 deg/s -> 0.85x`, `90 deg/s -> 1.80x`, `>= 360 deg/s -> 4.0x`, plus compact HUD formatting `2.1×`, `-1.4×`, `HOLD`) and `TurntableScratchPolicy` enforcing strict `SAFE_VOLUME_MODE && isPlaying && hasActiveTrack` eligibility.
   - Implemented `TurntableResampler` with per-sample ~12 ms exponential rate slew smoothing and 4 ms linear click-free gain envelopes on entry, hold, reverse boundary, and exit.
2. **Real-Time Turntable Scratch Loop in `AudioOutputEngine.kt`:**
   - **Strict Bit-Perfect Isolation:** Zero ring-buffer allocation or PCM teeing in `BIT_PERFECT_OPT_IN` mode; `turntableRingBuffer` is immediately released on `stop()`, `release()`, or switching into `BIT_PERFECT_OPT_IN`, and `beginTurntableScratch()` returns `false`.
   - **Pre-ReplayGain Tee:** During normal `SAFE_VOLUME_MODE` playback, unmodified unity-gain PCM frames are teed into the 8 MB ring buffer *before* `writeAlignedPcmFully(...)` applies ReplayGain (preventing double-attenuation during scratch or exit draining), and reset cleanly on track load, seek, gapless transition, or mid-stream format change.
   - **Low-Latency Scratch Loop (0 `AudioTrack` Rebuilds):** Anchors the cursor to the exact hardware playback head, flushes ~160 ms lookahead once on entry, freezes `PlayCountTracker` accumulation so scratching never inflates listening stats, throttles writes to ~40 ms lookahead (`sampleRate / 25`) in ~15 ms chunks, decodes forward ahead up to `+4.0x`, brakes cleanly to silence on `0.0x` hold, plays backwards cleanly through the ring buffer (and smoothly fades to silence while advancing the virtual cursor down to `00:00` on deep rewinds), applies Safe-Mode ReplayGain, and publishes live `_playbackPositionMs` every ~15 ms.
   - **Dual Exit Paths:** Cursors still inside the ring buffer use the **Fast Seamless Path** (drain remaining buffered frames at `1.0x` with 4 ms fade-in and zero `MediaCodec.flush()`, plus gapless preload trigger when near EOS), while deep rewinds past `oldestAvailableFrame` use the **Deep Rewind Path** (flush `AudioTrack`, reset ring buffer, single clean `currentDecoder.seekToMs(targetMs)`, resume `1.0x`). Cleanly aborts on `pause()`, `stop()`, `loadTrack()`, `seekTo()`, `release()`, or any hearing-safety auto-pause event.
3. **Controller, ViewModel, Navigation & `InteractiveVinylDisc` Integration (`PlaybackController.kt`, `MainViewModel.kt`, `BitPerfectAppNav.kt`, `NowPlayingScreen.kt`, `VinylDiscPlayer.kt`):**
   - Wired `beginTurntableScratch()`, `updateTurntableScratchRate(rate)`, and `endTurntableScratch(fallbackTargetMs)` cleanly into `InteractiveVinylDisc`.
   - In `SAFE_VOLUME_MODE && isPlaying`: touch down enters live HOLD (`0.0f`), drag streams live angular velocity playback rates, a 65 ms idle hold watchdog detects frozen fingers and brakes smoothly to `HOLD`, bounded catchable flick inertia (`<= 450 ms`) streams decaying playback rates until settled, and `VinylScrubPillLeaf` displays live time + signed delta + speed badge (`2.1×`, `-1.4×`, `HOLD`) strictly inside the narrow HUD leaf without recomposing `NowPlayingScreen`.
   - When paused OR in `BIT_PERFECT_OPT_IN`: preserves the exact silent seek-preview + single `onSeekTo` commit path with zero turntable DSP involvement.

### Software Verification (`./gradlew clean testDebugUnitTest lintDebug assembleDebug assembleRelease`)
- **Automated JVM Unit + Robolectric SQLite Migration + Headless Compose UI Tests:** **165 / 165 passed** across **31 test suites** (`0` failed, `0` errors, `0` skipped, `0` `@Ignore`), adding `TurntableAudioDspTest` (7/7), extending `VinylScrubMathTest` (8/8), and extending `BitPerfectUiBehaviorTest` (13/13, verifying Safe-Mode live touch `HOLD`/varispeed callbacks and strict Bit-Perfect zero-DSP silent-seek isolation).
- **Compiler & R8 Warnings:** `0` Kotlin compiler warnings (production + test), `0` KSP warnings, `0` R8 warnings.
- **Android Lint (`lintDebug` & `lintVitalRelease`):** **0 errors**, **0 warnings across all `/app/src/` Kotlin, manifest, and resource files**.
- **Debug APK (`assembleDebug`):** `app/build/outputs/apk/debug/app-debug.apk` (`20.63 MB` / `21,633,581 bytes`).
- **Release APK (`assembleRelease` with R8 optimization):** `app/build/outputs/apk/release/app-release-unsigned.apk` (`2.45 MB` / `2,567,346 bytes`).
- **Strict Offline Privacy Audit (Source + All 9 Generated Debug, Release & UnitTest Manifests):** **PASS** — **0 `android.permission.INTERNET`**, **0 `android.permission.MANAGE_EXTERNAL_STORAGE`**.
- **Pure-Kotlin Domain Boundary (`TurntableAudioDsp.kt`, `VinylScrubMath.kt`):** **PASS** — **0 `android.*` / `androidx.*` imports**.
- **Physical Hardware Verification Pending (Not Executed in Cloud):** On POCO F4 (Android 14 / HyperOS) + KZ EDX Pro 2 USB-C, confirm audible quality and responsiveness of live Safe-Mode forward varispeed, finger-hold braking (`HOLD`), reverse scratch within the 8 MB window and deep-rewind fallback past `oldestAvailableFrame`, pop/click-free entry & 1.0x exit resume across 16/24-bit 44,100–192,000 Hz FLAC/WAV/MP3/M4A/OGG, Play Count stability after scratching, immediate auto-pause safety on USB unplug mid-scratch, and strict Bit-Perfect Opt-In silent-seek fallback with zero DSP modification.

---

## 17. Read-Only Offline Radial Ring Spectrum Visualizer

### Delivered Architecture & UX
1. **Pure-Kotlin Zero-Allocation FFT & Head-Synced Side-Tap (`SpectrumAnalyzerDsp.kt`, `SpectrumPcmTap.kt`):**
   - Built `SpectrumAnalyzerDsp` / `SpectrumAnalyzer` (zero `android.*` / `androidx.*` imports, zero external DSP dependencies): pre-allocated 1024-point Hann-windowed iterative radix-2 FFT with Hann-weighted DC offset removal, Hi-Res decimation stride calculation (`<= 50 kHz -> 1`, `<= 100 kHz -> 2`, `<= 200 kHz -> 4`), 32 logarithmic bands across `30 Hz..16,000 Hz` (`-62 dB..-4 dB` with gentle `+9 dB` high-frequency perceptual tilt), frame-rate-independent fast-attack (`25 ms`) / smooth-decay (`140 ms`) ballistics, and seamless left/right mirrored 64-bar radial mapping.
   - Built `SpectrumPcmTap` (`48,000` mono floats ≈ `192 KB`) with boxcar-averaged decimation and generation-checked `AudioTrack.playbackHeadPosition` alignment eliminating ~160 ms lookahead audio/visual lag.
2. **Strict Read-Only Bit-Perfect Safety & Lifecycle Gate (`AudioOutputEngine.kt`, `PlaybackController.kt`, `MainViewModel.kt`):**
   - `AudioOutputEngine` taps PCM strictly **read-only AFTER `writeAlignedPcmFully(...)` / `writeNormalizedFloatsToTrack(...)`**, so `AudioTrack` receives completely untouched PCM in `BIT_PERFECT_OPT_IN` mode while the spectrum animates smoothly in Safe Mode (including live turntable scratch), Clean Native, and Bit-Perfect Opt-In mode.
   - Gated via `NowPlayingScreen`'s `LifecycleEventObserver` (`ON_START` / `ON_STOP` + `onDispose`) and persisted `spectrumVisualizerEnabled`: when off-screen, backgrounded, or disabled, `spectrumPcmTap` is immediately released (`null`) for 0% FFT CPU and 0 KB overhead.
3. **Minimalist Matte-Obsidian Visuals & Controls (`RadialSpectrumRing.kt`, `VinylDiscPlayer.kt`, `NowPlayingScreen.kt`, `SettingsScreen.kt`, `PersistenceModels.kt`, `UserPreferencesRepository.kt`):**
   - `RadialSpectrumRing` (`304.dp` container wrapping `248.dp` `InteractiveVinylDisc` with zero touch interception) draws 64 crisp rounded-cap studio ticks (`#3D3D47` -> `#7BA7D9` -> `#D7E5F5` tips) via draw-phase-only `Canvas` invalidation (`0` Compose recompositions at 60/120 Hz; stops idle frames cleanly when paused bars decay below `0.002f`).
   - Added matching `28.dp`-tall 32-bar `CompactSpectrumBarStrip` fallback beneath `Square Cover`, persisted `spectrumVisualizerEnabled` (`true` default) in DataStore, a **"Spectrum Visualizer"** switch in `SettingsScreen` (`DISPLAY`), and `"Hide Spectrum"` / `"Show Spectrum"` in `NowPlayingScreen`'s overflow menu.

### Software Verification (`./gradlew clean testDebugUnitTest lintDebug assembleDebug assembleRelease`)
- **Automated JVM Unit + Robolectric SQLite Migration + Headless Compose UI Tests:** **174 / 174 passed** across **32 test suites** (`0` failed, `0` errors, `0` skipped, `0` `@Ignore`), adding `SpectrumAnalyzerDspTest` (7/7), extending `PersistenceAndPreferencesTest` (4/4), and extending `BitPerfectUiBehaviorTest` (15/15).
- **Compiler & R8 Warnings:** `0` Kotlin compiler warnings (production + test), `0` KSP warnings, `0` R8 warnings.
- **Android Lint (`lintDebug` & `lintVitalRelease`):** **0 errors**, **0 warnings across all `/app/src/` Kotlin, manifest, and resource files**.
- **Debug APK (`assembleDebug`):** `app/build/outputs/apk/debug/app-debug.apk` (`20.14 MB` / `21,113,424 bytes`).
- **Release APK (`assembleRelease` with R8 optimization):** `app/build/outputs/apk/release/app-release-unsigned.apk` (`2.48 MB` / `2,600,130 bytes`).
- **Strict Offline & Zero-Permission Audit (Source + All 9 Generated Debug, Release & UnitTest Manifests):** **PASS** — **0 `INTERNET`**, **0 `RECORD_AUDIO`**, **0 `MANAGE_EXTERNAL_STORAGE`**, **0 `android.media.audiofx.Visualizer` references**.
- **Pure-Kotlin Domain Boundary (`SpectrumAnalyzerDsp.kt`, `SpectrumPcmTap.kt`):** **PASS** — **0 `android.*` / `androidx.*` imports**.
- **Physical Hardware Verification Pending (Not Executed in Cloud):** On POCO F4 (Android 14 / HyperOS, 120 Hz OLED) + KZ EDX Pro 2 USB-C, confirm on-beat visual synchronization across 44,100–192,000 Hz tracks, smooth 120 Hz radial bar ballistics and pause decay, live scratch spectrum reactivity in Safe Mode, unmodified Bit-Perfect USB playback with radial spectrum active, unobstructed vinyl touch scrubbing and vertical scrolling, and zero background CPU draw when screen turns off (`ON_STOP`).

---

## 18. ART JIT/AOT Compiler Refactor & 44,100 Hz Scratch Underrun Fix (`AudioOutputEngine.kt`)

### Root Causes Diagnosed From Physical POCO F4 Logcat (`logcat_4.txt`)
1. `AudioOutputEngine.runPlaybackLoop(...)` had grown to ~825 lines with capturing local functions, hitting `Method exceeds compiler instruction limit: 20666` in ART and falling back to bytecode interpretation on `BitPerfectAudioThread`.
2. Tight `~40 ms` (`sampleRate / 25`) scratch lookahead throttling with `4 ms` sleep plus un-primed scratch exit contributed to a brief 44,100 Hz underrun (`AudioTrack W restartIfDisabled`).

### Delivered Fixes
1. **Extracted `PlaybackLoopState` & Focused Private Helpers (`AudioOutputEngine.kt`):**
   - Replaced capturing local functions and monolithic loop state with a lightweight mutable `private class PlaybackLoopState` (zero per-buffer GC allocations, shared `reconfigureBuffersForFormat(...)`).
   - Split `runPlaybackLoop(...)` into compact private helpers: `handlePendingSeekInLoop`, `processTurntableScratchStep`, `decodeAheadForForwardScratch`, `exitTurntableScratchCleanly`, `writeScratchFloatsAndTapSpectrum`, `processRingDrainStepInLoop`, `handleEndOfStreamInLoop`, `handleMidStreamFormatChangeInLoop`, `processNormalPcmChunkInLoop`, and `publishPlaybackPositionAndPreload`.
   - Verified directly in compiled DEX bytecode: largest `AudioOutputEngine` method is now **807 DEX code units** (Debug) / **756 DEX code units** (Release), with `runPlaybackLoop` at **596 / 509** and `processTurntableScratchStep` at **682 / 516** — well below ART's compiler ceiling.
2. **Eliminated 44,100 Hz Scratch Underrun Headroom Gap:**
   - Raised scratch `AudioTrack` fill throttle headroom from `~40 ms` (`sampleRate / 25`) to `~65 ms` (`sampleRate / 15`) with a `2 ms` wait sleep (down from `4 ms`).
   - On Fast Seamless scratch exit, immediately pre-feeds up to 2 chunks of `1.0x` ring-buffer drain directly after the 4 ms fade-out tail so `AudioTrack` never starves across the scratch-to-normal handoff.

### Software Verification (`./gradlew clean testDebugUnitTest lintDebug assembleDebug assembleRelease`)
- **Automated JVM Unit + Robolectric SQLite Migration + Headless Compose UI Tests:** **174 / 174 passed** across **32 test suites** (`0` failed, `0` errors, `0` skipped, `0` `@Ignore`).
- **Compiler & R8 Warnings:** `0` Kotlin compiler warnings (production + test), `0` KSP warnings, `0` R8 warnings.
- **Android Lint (`lintDebug` & `lintVitalRelease`):** **0 errors**, **0 warnings across all `/app/src/` Kotlin, manifest, and resource files**.
- **Debug APK (`assembleDebug`):** `app/build/outputs/apk/debug/app-debug.apk` (`20.12 MB` / `21,097,040 bytes`).
- **Release APK (`assembleRelease` with R8 optimization):** `app/build/outputs/apk/release/app-release-unsigned.apk` (`2.48 MB` / `2,600,130 bytes`).
- **Strict Offline & Zero-Permission Audit (Source + All 9 Generated Debug, Release & UnitTest Manifests):** **PASS** — **0 `INTERNET`**, **0 `RECORD_AUDIO`**, **0 `MANAGE_EXTERNAL_STORAGE`**.
- **Physical Hardware Verification Pending (Not Executed in Cloud):** Re-verify on POCO F4 (Android 14 / HyperOS) + KZ EDX Pro 2 USB-C that `logcat` shows **0** `Method exceeds compiler instruction limit` warnings for `AudioOutputEngine` and **0** `AudioTrack restartIfDisabled` underruns during rapid 44,100 Hz and 96,000/192,000 Hz turntable scratching and release.

---

## 19. Switchable Spectrum Palettes (`Studio` · `Prism` · `Album`) & Transient `Kinetic Sparks`

### Delivered Architecture & UX
1. **Pure-Kotlin Palettes, Local Cover-Art Extractor, & Zero-Allocation Particle Engine (`SpectrumVisualDsp.kt`, `PersistenceModels.kt`):**
   - Added `SpectrumColorStyle` (`STUDIO("Studio")`, `PRISM("Prism")` default, `ALBUM_ADAPTIVE("Album")`) and `spectrumSparksEnabled` (`true` default) to `UserPreferences` + `UserPreferencesRepository`.
   - Built pure-Kotlin `AlbumPaletteExtractor` (zero `android.*` / `androidx.*` imports, zero external palette libraries): coarse-stride (~1,600-sample) RGB-to-HSL 24-hue bucket analysis ignoring near-black (`L < 0.14`), near-white (`L > 0.90`), and low-chroma gray (`S < 0.18`), selecting up to 3 accents separated by `>= 36°` with OLED vibrancy clamping (`S in 0.55..0.90`, `L in 0.52..0.72`), monochromatic harmony derivation for single-color covers, and Studio slate-ice fallback for grayscale or missing artwork.
   - Built `SpectrumColorMapper` for `STUDIO` (obsidian-slate `#3D3D47` -> `#7BA7D9` -> `#D7E5F5`), 5-stop OLED-tuned `PRISM` (coral-red/orange bass -> amber-gold -> emerald/aqua mids -> cobalt/violet highs), and `ALBUM_ADAPTIVE` bar/tip/spark ARGB interpolation with zero heap allocations.
   - Built `KineticSparkPool` (`MAX_SPARKS = 64` pre-allocated primitive arrays): triggers 1–2 radial/upward sparks per band on sharp upward energy transients (`delta > 0.12f && energy > 0.28f`) gated by per-band `85 ms` cooldowns, integrates velocity with exponential drag (`exp(-4.0 * dt)`), tracks active count, and supports instant `clear()` on track change or toggle-off.
2. **120 Hz Zero-Recomposition Canvas Rendering (`RadialSpectrumRing.kt`):**
   - Expanded `RadialSpectrumRing` to `316.dp` around a centered `240.dp` `InteractiveVinylDisc` (`38.dp` radial headroom: `5.dp` gap + `18.dp` max bars + outward spark flight) and `CompactSpectrumBarStrip` to `42.dp` (`24.dp` bars + `18.dp` upward spark headroom).
   - Rendered Kinetic Sparks strictly inside the existing draw-phase-only `Canvas` using crisp velocity-aligned `drawLine` micro-streaks + `drawCircle` bright core dots — **0 GPU blur filters (`BlurMaskFilter`/`RenderEffect`)**, **0 Compose recompositions**, and **0 per-frame allocations**, halting frame ticks cleanly once paused bars decay (`< 0.002f`) and active sparks reach `0`.
   - Left `AudioOutputEngine.kt` (`runPlaybackLoop()`) **completely untouched** (`0` bytes diff), preserving ART JIT/AOT native compilation and bit-perfect read-only audio isolation.
3. **Scoped State Collection, Async Artwork Extraction, & Controls (`MainViewModel.kt`, `BitPerfectAppNav.kt`, `SettingsScreen.kt`, `NowPlayingScreen.kt`):**
   - Exposed `spectrumColorStyle` and `spectrumSparksEnabled` via `distinctUntilChanged()` `StateFlow`s collected strictly inside `Routes.SETTINGS` and `Routes.NOW_PLAYING`.
   - Added async `Dispatchers.Default` cover-art pixel extraction per `track.uriString` when `ALBUM_ADAPTIVE` is active (handling `Bitmap.Config.HARDWARE` cleanly via a temporary ARGB_8888 copy without recycling cached thumbnails).
   - Added **Spectrum Style (`Studio` · `Prism` · `Album`)** segmented controls and **Kinetic Sparks** toggle inside `SettingsScreen` (`DISPLAY`, visible when spectrum is enabled) plus quick overflow menu items (`"Spectrum Style: ..."`, `"Disable Sparks"` / `"Enable Sparks"`) inside `NowPlayingScreen`.

### Software Verification (`./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease`)
- **Automated JVM Unit + Robolectric SQLite Migration + Headless Compose UI Tests:** **181 / 181 passed** across **33 test suites** (`0` failed, `0` errors, `0` skipped, `0` `@Ignore`), adding `SpectrumVisualDspTest` (5/5) and extending `PersistenceAndPreferencesTest` (4/4) and `BitPerfectUiBehaviorTest` (17/17).
- **Compiler & R8 Warnings:** `0` Kotlin compiler warnings (production + test), `0` KSP warnings, `0` R8 warnings.
- **Android Lint (`lintDebug` & `lintVitalRelease`):** **0 errors**, **0 warnings across all `/app/src/` Kotlin, manifest, and resource files**.
- **Debug APK (`assembleDebug`):** `app/build/outputs/apk/debug/app-debug.apk` (`20.15 MB` / `21,129,808 bytes`).
- **Release APK (`assembleRelease` with R8 optimization):** `app/build/outputs/apk/release/app-release-unsigned.apk` (`2.50 MB` / `2,616,514 bytes`).
- **Strict Offline & Zero-Permission Audit (Source + All 8 Generated Debug, Release & UnitTest Manifests):** **PASS** — **0 `INTERNET`**, **0 `RECORD_AUDIO`**, **0 `MANAGE_EXTERNAL_STORAGE`**, **0 `android.media.audiofx.Visualizer` references**, **0 GPU blur filter usages**.
- **Pure-Kotlin Domain Boundary (`SpectrumVisualDsp.kt`) & Audio Loop Preservation (`AudioOutputEngine.kt`):** **PASS** — **0 `android.*` / `androidx.*` imports** in `SpectrumVisualDsp.kt`; **0 bytes diff** on `AudioOutputEngine.kt`.
- **Physical Hardware Verification Pending (Not Executed in Cloud):** On POCO F4 (Android 14 / HyperOS, 120 Hz OLED) + KZ EDX Pro 2 USB-C, confirm visual legibility and OLED contrast across `Studio`, `Prism`, and `Album` palettes on varied/grayscale/missing cover art, crisp beat-synced `Kinetic Sparks` bursts without clipped bounds or frame drops at 120 Hz in both Vinyl Disc and Square Cover modes, clean spark fade-out on pause/track change, unobstructed turntable touch scrubbing, and unchanged bit-perfect/Hi-Res playback stability.

---

## 20. Release `v1.0` Merge, Documentation, & Signed APK Publication

### Delivered Repository & Release Actions
1. **Branch Consolidation (`feat/mvp-overnight` -> `main`):**
   - Restored 3 CRLF-only line-ending diffs back to `HEAD` and fast-forward merged `feat/mvp-overnight` (`ec2b888`) cleanly into `main`.
   - Pushed `main` to `origin` and deleted `feat/mvp-overnight` both locally and on `origin`.
2. **Root Documentation & Release Build Hygiene (`457d89e`, `6af1209`):**
   - Added [`README.md`](../README.md) documenting architecture, supported formats, hearing safety invariants, 6-stage diagnostics, permissions, Room v4 persistence, build requirements, and hardware safety notices.
   - Updated [`app/.gitignore`](../app/.gitignore) to ignore `/release` so Android Studio's generated signed APK and R8 `mapping.txt` outputs remain untracked.
3. **Annotated Tag & GitHub Release Publication (`v1.0`):**
   - Created and pushed annotated Git tag `v1.0` (`6af1209b186ef9a439df244e358a9d3ab11d89da`, matching `versionName = "1.0"`, `versionCode = 1`).
   - Published GitHub Release **`BitPerfectPlayer v1.0`** (`Latest`) at `https://github.com/WageFolabessy/BitPerfectPlayer/releases/tag/v1.0` attaching `BitPerfectPlayer-v1.0-release.apk` (`2,628,802` bytes).
   - **Verified Signed Release APK Integrity & Privacy:**
     - **APK SHA-256:** `725481a31d4786cecd7c2ed17ee7d0ba38a3a7a7b6d8884486c1b93ea4bd847a`
     - **Signing Scheme:** `Verifies` via APK Signature Scheme v2 (`CN=Endricho Folabessy`, Cert SHA-256: `c4dd7e0c9f5b883f56b268e54bd02361788fc9d8f52c313075c4f0447eef89d4`)
     - **Compiled APK Manifest Privacy Check:** Zero `INTERNET`, `RECORD_AUDIO`, or `MANAGE_EXTERNAL_STORAGE` permissions.

---

## 21. `feat/v1.1` Preparation — Template Test Removal & `docs/` Reorganization

### Delivered Repository Hygiene Actions
1. **Feature Branch Setup:**
   - Created and checked out `feat/v1.1` from clean `main` (`25ac542`).
2. **Removed Leftover Android Studio Template Tests:**
   - Removed `app/src/test/java/com/cangkir/bitperfectplayer/ExampleUnitTest.kt` and `app/src/androidTest/java/com/cangkir/bitperfectplayer/ExampleInstrumentedTest.kt`.
   - Zero production Kotlin audio/UI files under `app/src/main/` were modified.
3. **Relocated Process Documentation into `docs/` & Synchronized All References:**
   - Moved `HANDOFF_REPORT.md` -> `docs/HANDOFF_REPORT.md` and `TASKS.md` -> `docs/TASKS.md` via `git mv`, keeping `README.md`, `SPEC.md`, `DECISIONS.md`, `LICENSE`, `NOTICE`, `CONTRIBUTING.md`, `SECURITY.md`, and `AGENTS.md` at the repository root.
   - Updated every reference and relative Markdown link across `AGENTS.md`, `.agents/skills/overnight-task-loop/SKILL.md`, `README.md`, `CONTRIBUTING.md`, `SPEC.md`, `docs/TASKS.md`, and `docs/HANDOFF_REPORT.md` (verified repo-wide: `0` stale root-path references, `0` broken relative Markdown links).

### Software Verification (`./gradlew clean testDebugUnitTest lintDebug assembleDebug assembleRelease`)
- **Automated JVM Unit + Robolectric SQLite Migration + Headless Compose UI Tests:** **180 / 180 passed** across **32 test suites** (`0` failed, `0` errors, `0` skipped, `0` `@Ignore`).
- **Compiler & R8 Warnings:** `0` Kotlin compiler warnings (production + test), `0` KSP warnings, `0` R8 warnings.
- **Android Lint (`lintDebug` & `lintVitalRelease`):** **0 errors**, **0 warnings across all `/app/src/` Kotlin, manifest, and resource files**.
- **Debug APK (`assembleDebug`):** `app/build/outputs/apk/debug/app-debug.apk` (`20.15 MB` / `21,129,808 bytes`).
- **Release APK (`assembleRelease` with R8 optimization):** `app/build/outputs/apk/release/app-release-unsigned.apk` (`2.50 MB` / `2,616,514 bytes`).
- **Strict Offline & Zero-Permission Audit (Source + All 8 Generated Debug, Release & UnitTest Manifests):** **PASS** — **0 `INTERNET`**, **0 `RECORD_AUDIO`**, **0 `MANAGE_EXTERNAL_STORAGE`**.
- **Physical Hardware Verification:** No production runtime behavior changed in this checkpoint; prior pending POCO F4 + KZ EDX Pro 2 USB-C verification items from Sections 18–19 remain unchanged.

---

## 22. Late-2026 Toolchain Upgrade (`Kotlin 2.4.21` / `Compose BOM 2026.09.00`) & Build-Time `ktlint` + `detekt` Integration

### Delivered Toolchain, Static Analysis, & Quality Upgrades
1. **Verified Late-2026 Toolchain Matrix (`gradle/libs.versions.toml`, `build.gradle.kts`, `app/build.gradle.kts`, `DECISIONS.md` `D10.1`):**
   - Preserved locked baseline: `AGP 9.5.1` (stable), `KSP 2.3.14`, `Gradle 9.9.1`, `JDK 25`, `compileSdk { version = release(37) }`, `targetSdk = 37`, `minSdk = 26`, `JavaVersion.VERSION_11`, `namespace` / `applicationId = "com.cangkir.bitperfectplayer"`, and zero added runtime dependencies.
   - Upgraded **Kotlin `2.2.10` → `2.4.21`** and **Compose BOM `2026.02.01` → `2026.09.00`** (highest stable late-2026 Compose BOM compatible with `compileSdk = 37`; empirically confirmed `2026.11.01` fails `:app:checkDebugAarMetadata` because Compose `1.13.0` requires `compileSdk >= 37.1`).
   - Migrated `BitPerfectUiBehaviorTest.kt` from deprecated `androidx.compose.ui.test.junit4.createComposeRule` to `androidx.compose.ui.test.junit4.v2.createComposeRule` (0 deprecation warnings across production + test code).
2. **Build-Time Formatting (`ktlint`) & Static Analysis (`detekt`) with Zero Baseline Suppression:**
   - Added **`org.jlleitschuh.gradle.ktlint` `14.2.0`** (MIT) configured via root [`.editorconfig`](../.editorconfig) (`android_studio` style, `max_line_length = 140`, trailing commas enabled, `@Composable` naming exception) and formatted the entire repository cleanly (**0 `ktlintCheck` violations**).
   - Added **`dev.detekt` `2.0.0-alpha.6`** (Apache 2.0, Kotlin 2.4 + AGP 9 + Gradle 9 + JDK 25 compatible) configured via [`config/detekt/detekt.yml`](../config/detekt/detekt.yml) (`buildUponDefaultConfig = true`, **0 `detekt-baseline.xml`**).
   - Resolved **all 149 initial Detekt findings directly in code** down to **0 issues**, including fixing a genuine coroutine cancellation bug in `LocalLibraryRepository.kt` (`refreshLibrary` and `toggleFavorite` now explicitly rethrow `CancellationException` and roll back optimistic favorite state before rethrowing), narrowing broad `catch (e: Exception)` blocks across `LocalLibraryRepository`, `MediaCodecAudioDecoder`, and `AudioOutputEngine`, removing redundant `CancellationException` catches in `VinylDiscPlayer`, extracting `LibraryTab.kt`, and cleanly decomposing high-complexity helpers across parsers/codecs/audio engine without touching runtime contracts.
3. **CI & Documentation Integration:**
   - Updated [`.github/workflows/android-ci.yml`](../.github/workflows/android-ci.yml) to enforce `./gradlew ktlintCheck detekt testDebugUnitTest lintDebug assembleDebug --stacktrace` alongside the existing manifest permission privacy audit (retaining pinned action SHAs and `permissions: contents: read`).
   - Updated [`README.md`](../README.md), [`CONTRIBUTING.md`](../CONTRIBUTING.md), [`AGENTS.md`](../AGENTS.md), [`.agents/rules/gradle-and-manifest.md`](../.agents/rules/gradle-and-manifest.md), [`.agents/skills/android-audio-verify/SKILL.md`](../.agents/skills/android-audio-verify/SKILL.md), and [`DECISIONS.md`](../DECISIONS.md) (`D10.1`).

### Software Verification (`./gradlew clean ktlintCheck detekt testDebugUnitTest lintDebug assembleDebug assembleRelease`)
- **Kotlin Formatting (`ktlintCheck`):** **PASS — 0 violations** across `main`, `test`, and Kotlin Gradle scripts.
- **Static Analysis (`detekt`):** **PASS — 0 issues** (0 baseline files).
- **Automated JVM Unit + Robolectric SQLite Migration + Headless Compose UI Tests (`testDebugUnitTest`):** **180 / 180 passed** across **32 test suites** (`0` failed, `0` errors, `0` skipped, `0` `@Ignore`).
- **Compiler, KSP, & R8 Warnings:** **0** Kotlin compiler warnings (production + test), **0** KSP warnings, **0** R8 warnings.
- **Android Lint (`lintDebug` & `lintVitalRelease`):** **0 errors**, **0 warnings across all `/app/src/` Kotlin, manifest, and resource files**.
- **Debug APK (`assembleDebug`):** `app/build/outputs/apk/debug/app-debug.apk` (`20.07 MB` / `21,043,749 bytes`).
- **Release APK (`assembleRelease` with R8 optimization):** `app/build/outputs/apk/release/app-release-unsigned.apk` (`2.56 MB` / `2,680,304 bytes`).
- **ART DEX Compactness Check (`AudioOutputEngine`):** Largest method remains **`807` DEX code units (Debug)** / **`756` DEX code units (Release)** (`handleEndOfStreamInLoop`), well under ART's `16,384` instruction JIT/AOT ceiling.
- **Pure-Kotlin Domain Boundary (`30` domain files):** **PASS — 0 `android.*` / `androidx.*` imports**.
- **Strict Offline & Zero-Permission Audit (Source + All Generated Debug, Release, & UnitTest Manifests):** **PASS — 0 `INTERNET`, 0 `RECORD_AUDIO`, 0 `MANAGE_EXTERNAL_STORAGE`**.
- **Physical Hardware Verification Pending (Not Executed in Cloud):** Real POCO F4 (Android 14 / HyperOS) + KZ EDX Pro 2 USB-C runtime testing (including bit-perfect mixer negotiation, audible playback, live Safe-Mode turntable scratch, and physical USB plug/unplug auto-pause) remains unverified in this software-only environment.

---

## 23. `AudioOutputEngine` Modularization into 4 Cohesive Internal Collaborators (`D10.2`)

### Delivered Architecture & Before/After LOC Breakdown
Refactored the monolithic `AudioOutputEngine.kt` (`2,754` lines at `5e33ebe`) into a clean, readable coordinator (**`642` lines**, a **76.7% reduction**) delegating to 4 focused `internal` collaborators inside `com.cangkir.bitperfectplayer.audio` while preserving 100% of `AudioOutputEngine`'s public API, thread-safety boundaries (`engineLock`, `preloadLock`, `BitPerfectEngineControl`, `BitPerfectGaplessPreload`, `BitPerfectAudioThread` with `Process.THREAD_PRIORITY_AUDIO`), hearing safety invariants, and zero-allocation hot-path contracts:

| File (`com.cangkir.bitperfectplayer.audio`) | Before (`5e33ebe`) | After (`D10.2`) | Primary Responsibility |
| :--- | :---: | :---: | :--- |
| [`AudioOutputEngine.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/audio/AudioOutputEngine.kt) | `2,754` lines | **`642` lines** | Public API (`PlaybackStatus`, `EngineTrackRequest`, `EnginePlaybackState`, 20 public methods/flows), `engineLock` & `BitPerfectEngineControl` lifecycle coordination, track loading, and `BitPerfectAudioThread` orchestration. |
| [`AudioRouteAndMixerManager.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/audio/AudioRouteAndMixerManager.kt) | *(inlined)* | **`484` lines** | Output device detection, `AudioDeviceCallback` (with cold-start initial-callback suppression), `ACTION_AUDIO_BECOMING_NOISY` receiver, `AudioFocusRequest` lifecycle (Bit-Perfect ducking isolation), API 34+ `MIXER_BEHAVIOR_BIT_PERFECT` negotiation + mandatory `clearPreferredMixerAttributesOnApi34(...)` cleanup, and 6-stage `AudioDiagnosticSnapshot` construction. |
| [`AudioTrackWriter.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/audio/AudioTrackWriter.kt) | *(inlined)* | **`543` lines** | `AudioTrack` creation/configuration (`PERFORMANCE_MODE_NONE`, frame-aligned buffer sizing, API 31+ PCM encoding guards), idempotent `safePlay`/`safePause`/`safeFlush`/`safeStopAndRelease`, `drainAudioTrackCleanly(...)`, `readUnsignedPlaybackHeadFrames(...)`, zero-allocation `writeAlignedPcmFully(...)` & `writeNormalizedFloatsToTrack(...)`, and post-write read-only `SpectrumPcmTap` lifecycle & window sampling. |
| [`GaplessPreloadController.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/audio/GaplessPreloadController.kt) | *(inlined)* | **`311` lines** | Dedicated `"BitPerfectGaplessPreload"` single-thread executor, `preloadLock`, `setNextTrack(...)`, `maybeTriggerPreloadForPosition(...)`, generation/URI validation, OOM-safe header-only `readReplayGainMetadata(...)`, `pollSeamlessPreloadedTrack(...)`, and leak-free off-main-thread decoder disposal. |
| [`TurntableScratchRunner.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/audio/TurntableScratchRunner.kt) | *(inlined)* | **`859` lines** | `PlaybackLoopState` per-worker buffer/cursor container + `TurntableScratchRunner` owning the Safe-Mode-only lazy 8 MB `TurntablePcmRingBuffer` lifecycle, pre-ReplayGain PCM ring-buffer teeing, normal PCM chunk writing, lock-free UI -> audio thread scratch rate atomics, `~65 ms` (`sampleRate / 15`) fill-throttled varispeed/reverse resampler loop with `2 ms` sleep, forward scratch decode-ahead, and click-free exit transition (Fast Seamless `1.0x` ring drain vs Deep Rewind `seekToMs`). |

### Pure-Kotlin Domain Helper Extraction & Unit Test Coverage
- [`PcmStreamRules.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/audio/PcmStreamRules.kt) + [`PcmStreamRulesTest.kt`](../app/src/test/java/com/cangkir/bitperfectplayer/audio/PcmStreamRulesTest.kt): Extracted and tested `PcmStreamRules.computeAudioTrackBufferSizeBytes(...)` (`2x` OS minimum buffer with `1024`-frame or `50 ms` floor, rounded up to whole frames across 16-bit, 24-bit packed 6-byte frames, and float32).
- [`TurntableAudioDsp.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/audio/TurntableAudioDsp.kt) (`TurntableScratchPolicy`) + [`TurntableAudioDspTest.kt`](../app/src/test/java/com/cangkir/bitperfectplayer/audio/TurntableAudioDspTest.kt): Extracted and tested `computeScratchEntryCursorFrame(...)`, `computeVirtualFrameBounds(...)`, `computeClampedScratchPositionMs(...)`, and `canResumeViaSeamlessRingDrain(...)`.
- [`AudioSafetyController.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/audio/AudioSafetyController.kt) + [`AudioSafetyControllerTest.kt`](../app/src/test/java/com/cangkir/bitperfectplayer/audio/AudioSafetyControllerTest.kt): Extracted and tested `AudioSafetyController.formatAutoPauseNotice(...)` across all `AutoPauseReason` values.

### Software Verification (`./gradlew clean ktlintCheck detekt testDebugUnitTest lintDebug assembleDebug assembleRelease`)
- **Kotlin Formatting (`ktlintCheck`):** **PASS — 0 violations** across `main`, `test`, and Kotlin Gradle scripts.
- **Static Analysis (`detekt`):** **PASS — 0 issues** (0 baseline files).
- **Automated JVM Unit + Robolectric SQLite Migration + Headless Compose UI Tests (`testDebugUnitTest`):** **183 / 183 passed** across **32 test suites** (`0` failed, `0` errors, `0` skipped, `0` `@Ignore`).
- **Compiler, KSP, & R8 Warnings:** **0** Kotlin compiler warnings (production + test), **0** KSP warnings, **0** R8 warnings.
- **Android Lint (`lintDebug` & `lintVitalRelease`):** **0 errors**, **0 warnings across all `/app/src/` Kotlin, manifest, and resource files**.
- **Debug APK (`assembleDebug`):** `app/build/outputs/apk/debug/app-debug.apk` (`20.08 MB` / `21,060,133 bytes`).
- **Release APK (`assembleRelease` with R8 optimization):** `app/build/outputs/apk/release/app-release-unsigned.apk` (`2.57 MB` / `2,696,688 bytes`).
- **ART DEX Compactness Audit (`dexdump` across `AudioOutputEngine` + 4 Collaborators):**
  - `AudioOutputEngine`: max **`420` DEX code units (Debug)** / **`404` DEX code units (Release)** (`<init>`)
  - `AudioRouteAndMixerManager`: max **`246` (Debug)** / **`186` (Release)**
  - `AudioTrackWriter`: max **`319` (Debug)** / **`273` (Release)**
  - `GaplessPreloadController`: max **`240` (Debug)** / **`176` (Release)**
  - `TurntableScratchRunner`: max **`457` (Debug)** / **`410` (Release)** (`processTurntableScratchStep`)
  - `PlaybackLoopState`: max **`268` (Debug)** / **`242` (Release)**
  - Every method is ~36x–90x below ART's `16,384` instruction JIT/AOT compiler ceiling.
- **Pure-Kotlin Domain Boundary (`35` pure-Kotlin source files, including all `30` core domain files):** **PASS — 0 `android.*` / `androidx.*` imports**.
- **Strict Offline & Zero-Permission Audit (Source + All 8 Generated Debug, Release, & UnitTest Manifests):** **PASS — 0 `INTERNET`, 0 `RECORD_AUDIO`, 0 `MANAGE_EXTERNAL_STORAGE`**.
- **Physical Hardware Verification Pending (Not Executed in Cloud):** Real POCO F4 (Android 14 / HyperOS) + KZ EDX Pro 2 USB-C runtime testing (including bit-perfect mixer negotiation, audible playback, live Safe-Mode turntable scratch, and physical USB plug/unplug auto-pause) remains unverified in this software-only environment.

---

## 24. Compose UI Feature Sub-Package Decomposition (`D10.3`)

### Delivered Sub-Package Architecture & Before/After LOC Breakdown
Split the 4 oversized Compose screen files (`LibraryScreen.kt`, `PlaylistsScreen.kt`, `NowPlayingScreen.kt`, and `SettingsScreen.kt` — `957` to `1,393` lines each) into 15 focused files (`132` to `463` lines each) under 4 cohesive feature sub-packages (`ui/library/`, `ui/playlists/`, `ui/nowplaying/`, `ui/settings/`) while preserving `MainViewModel.kt`, `VinylDiscPlayer.kt`, `RadialSpectrumRing.kt`, all visual styling, `>= 48.dp` touch targets, hairline divider padding alignments, and strict 120 Hz recomposition isolation:

| Feature Sub-Package | Before (`6a5e29b`) | New Cohesive Files (`D10.3`) | After LOC | Key Composables & Responsibilities |
| :--- | :--- | :--- | :---: | :--- |
| **`ui/library/`** | `ui/LibraryScreen.kt` (`1,338` lines) | [`ui/library/LibraryScreen.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/ui/library/LibraryScreen.kt) | **`427` lines** | Screen coordinator, runtime permission & SAF folder launchers, sort bottom sheets, and tab content routing. |
| | | [`ui/library/LibraryHeaderControls.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/ui/library/LibraryHeaderControls.kt) | **`369` lines** | `LibraryTopAppBar`, `LibraryTabRow`, `SubtleListSortHeaderRow`, `PermissionRequestBanner`, and `CompactSearchBarRow`. |
| | | [`ui/library/LibraryTrackList.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/ui/library/LibraryTrackList.kt) | **`327` lines** | `TrackListView` (with auto-scroll-to-top guard on sort/query changes) and `TrackListRow` (conditional `DropdownMenu`). |
| | | [`ui/library/LibraryGroupViews.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/ui/library/LibraryGroupViews.kt) | **`301` lines** | `GroupListView` (Folders, Artists, Albums with auto-scroll-to-top guard) and `GroupDetailTrackList`. |
| **`ui/playlists/`** | `ui/PlaylistsScreen.kt` (`1,393` lines) | [`ui/playlists/PlaylistsScreen.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/ui/playlists/PlaylistsScreen.kt) | **`405` lines** | Screen coordinator, TopAppBar, SAF `.m3u8` export/import launchers, delete confirmation dialog, and view routing. |
| | | [`ui/playlists/PlaylistsOverview.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/ui/playlists/PlaylistsOverview.kt) | **`455` lines** | `PlaylistsOverviewList`, `MostPlayedTrackRow`, `MostPlayedDetailView`, and `PlaylistSummaryRow`. |
| | | [`ui/playlists/PlaylistDetailView.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/ui/playlists/PlaylistDetailView.kt) | **`327` lines** | `PlaylistDetailView` (with auto-scroll-to-top guard) and `PlaylistEntryRow` (manual up/down reorder & remove controls). |
| | | [`ui/playlists/PlaylistDialogs.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/ui/playlists/PlaylistDialogs.kt) | **`212` lines** | `PlaylistNameDialog` (Create / Rename) and shared `AddToPlaylistDialog`. |
| **`ui/nowplaying/`** | `ui/NowPlayingScreen.kt` (`1,136` lines) | [`ui/nowplaying/NowPlayingScreen.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/ui/nowplaying/NowPlayingScreen.kt) | **`463` lines** | Screen coordinator, `ON_START`/`ON_STOP` spectrum tap lifecycle gate, off-thread album palette extraction, TopAppBar, and portrait layout assembly. |
| | | [`ui/nowplaying/NowPlayingControls.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/ui/nowplaying/NowPlayingControls.kt) | **`406` lines** | `SwipeableSquareArtwork`, isolated 20 Hz `NowPlayingSeekSection` leaf, `NowPlayingTransportRow`, `NowPlayingAudioModeStrip`, `NowPlayingUtilityStrip`, and `extractAlbumPaletteFromBitmap`. |
| | | [`ui/nowplaying/NowPlayingSheetsAndDialogs.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/ui/nowplaying/NowPlayingSheetsAndDialogs.kt) | **`373` lines** | `NowPlayingOverflowMenuButton`, `AudioModeSheetContent`, `SafetyAlertBanner`, and mandatory `HearingSafetyWarningDialog`. |
| **`ui/settings/`** | `ui/SettingsScreen.kt` (`957` lines) | [`ui/settings/SettingsScreen.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/ui/settings/SettingsScreen.kt) | **`298` lines** | Screen coordinator, SAF folder & JSON backup launchers, and shared `SettingsSectionHeader`, `SettingsGroupCard`, `SettingsHairline`, `SettingsKeyValueRow`. |
| | | [`ui/settings/SettingsAudioSection.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/ui/settings/SettingsAudioSection.kt) | **`327` lines** | `SettingsAudioEngineSection` (Gapless Playback, ReplayGain mode/pre-amp/clipping, isolated `SleepTimerSettingsValueLeaf` row, 6-Stage Diagnostics row). |
| | | [`ui/settings/SettingsDisplayAndLibrarySection.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/ui/settings/SettingsDisplayAndLibrarySection.kt) | **`357` lines** | `SettingsDisplaySection` (Now Playing Artwork style, Spectrum Visualizer toggle/style/Kinetic Sparks) and `SettingsLibraryStorageSection` (SAF folders & refresh). |
| | | [`ui/settings/SettingsBackupAndAboutSection.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/ui/settings/SettingsBackupAndAboutSection.kt) | **`132` lines** | `SettingsLocalBackupSection` (Export/Restore `.json` via SAF) and `SettingsAboutPrivacySection` (100% Offline badge, App Version, Indexed Local Tracks, Supported Formats). |

### Software Verification (`./gradlew clean ktlintCheck detekt testDebugUnitTest lintDebug assembleDebug assembleRelease`)
- **Kotlin Formatting (`ktlintCheck`):** **PASS — 0 violations** across `main`, `test`, and Kotlin Gradle scripts (every new file matches its sub-package directory path).
- **Static Analysis (`detekt`):** **PASS — 0 issues** (0 baseline files).
- **Automated JVM Unit + Robolectric SQLite Migration + Headless Compose UI Tests (`testDebugUnitTest`):** **183 / 183 passed** across **32 test suites** (`0` failed, `0` errors, `0` skipped, `0` `@Ignore`), including all 14 Robolectric Compose UI behavioral tests in [`BitPerfectUiBehaviorTest.kt`](../app/src/test/java/com/cangkir/bitperfectplayer/ui/BitPerfectUiBehaviorTest.kt) with zero assertion changes.
- **Compiler, KSP, & R8 Warnings:** **0** Kotlin compiler warnings (production + test), **0** KSP warnings, **0** R8 warnings.
- **Android Lint (`lintDebug` & `lintVitalRelease`):** **0 errors**, **0 warnings across all `/app/src/` Kotlin, manifest, and resource files**.
- **Debug APK (`assembleDebug`):** `app/build/outputs/apk/debug/app-debug.apk` (`20.16 MB` / `21,142,230 bytes`).
- **Release APK (`assembleRelease` with R8 optimization):** `app/build/outputs/apk/release/app-release-unsigned.apk` (`2.59 MB` / `2,713,232 bytes`).
- **Strict Offline & Zero-Permission Audit (Source + All 8 Generated Debug, Release, & UnitTest Manifests):** **PASS — 0 `INTERNET`, 0 `RECORD_AUDIO`, 0 `MANAGE_EXTERNAL_STORAGE`**.
- **Physical Hardware Verification Pending (Not Executed in Cloud):** Real POCO F4 (Android 14 / HyperOS) + KZ EDX Pro 2 USB-C runtime testing remains unverified in this software-only environment.

---

## 25. Android 17 (`targetSdk = 37`) Background Audio Hardening & `ForegroundPlaybackGate` (`D10.4`)

### Delivered Architecture & Hardened Startup Flows
1. **Pure-Kotlin Foreground Startup Handshake ([`ForegroundPlaybackGate.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/audio/ForegroundPlaybackGate.kt) + [`ForegroundPlaybackGateTest.kt`](../app/src/test/java/com/cangkir/bitperfectplayer/audio/ForegroundPlaybackGateTest.kt)):**
   - Created pure-Kotlin `ForegroundPlaybackGate` (`0` `android.*` / `androidx.*` imports) tracking `isAppInForeground: Boolean`, `isServiceInForeground: Boolean`, and `lastFailureReason: String?`.
   - `needsForegroundServiceHandshake(): Boolean` returns `false` whenever `isAppInForeground || isServiceInForeground`, guaranteeing **0 ms added latency** for all in-app taps and when `PlaybackForegroundService` is already promoted, and returns `true` only when starting/resuming playback from the background while the service is not yet promoted.
   - Token-scoped `beginHandshake()`, `cancelPendingHandshake()`, and `awaitServicePromotion(timeoutMs = 1_000L, token): ForegroundHandshakeResult` (`READY_IMMEDIATELY`, `PROMOTED`, `FAILED_START_NOT_ALLOWED`, `TIMED_OUT`, `CANCELLED`) ensure rapid `pause()`, `stop()`, or track switches cancel stale waits cleanly with zero leaked coroutines or stuck state.
2. **Zero-Dependency App Foreground State Tracking ([`BitPerfectApplication.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/BitPerfectApplication.kt)):**
   - Added `foregroundPlaybackGate` to `AppContainer` and wired a lightweight `Application.ActivityLifecycleCallbacks` counter (`startedActivityCount` incremented in `onActivityStarted`, decremented with `.coerceAtLeast(0)` in `onActivityStopped`) updating `foregroundPlaybackGate.setAppInForeground(startedActivityCount > 0)`.
3. **Hardened Foreground Service Promotion ([`PlaybackForegroundService.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/audio/PlaybackForegroundService.kt)):**
   - In `onStartCommand`, `ACTION_START`, `ACTION_NEXT`, `ACTION_PREVIOUS`, and `ACTION_PLAY_PAUSE` (when transitioning to play) invoke `ensurePromotedToForeground()` (`ServiceCompat.startForeground(..., ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)`) **first** and immediately notify `foregroundPlaybackGate.markServicePromoted()`.
   - Guarded both `ContextCompat.startForegroundService(...)` and `ServiceCompat.startForeground(...)` against `ForegroundServiceStartNotAllowedException` (API 31+) and `SecurityException`, logging a warning and calling `foregroundPlaybackGate.markServiceStartFailed(reason)` instead of crashing.
   - Called `foregroundPlaybackGate.markServiceStopped()` on `stopForegroundCompat()`, `onDestroy()`, and `Companion.stopService(...)`.
4. **Hardened Playback Startup & Audio Focus Failure Handling ([`PlaybackController.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/audio/PlaybackController.kt), [`AudioOutputEngine.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/audio/AudioOutputEngine.kt)):**
   - In `PlaybackController.play()` and `loadActiveTrack(..., autoPlay = true)`, when `foregroundPlaybackGate.needsForegroundServiceHandshake()` is `true`, `PlaybackController` triggers `PlaybackForegroundService.startService(appContext, foregroundPlaybackGate)` and awaits `awaitServicePromotion(token)` on `scope` before calling `engine.play()` or `engine.loadTrack(..., autoPlay = true)`.
   - If background promotion fails (`FAILED_START_NOT_ALLOWED` or `TIMED_OUT`), `AudioOutputEngine.notifyBackgroundStartBlocked(...)` keeps status cleanly in `PlaybackStatus.PAUSED` (loading the track paused when `loadActiveTrack` was called) with an honest user notice (`ForegroundPlaybackGate.BACKGROUND_START_BLOCKED_NOTICE`).
   - In `AudioOutputEngine.play()` and `startPlaybackWorkerLocked()`, if `AudioManager.requestAudioFocus(...)` fails (`AUDIOFOCUS_REQUEST_FAILED`), `isPausedFlag` is forced to `true`, `AudioTrack` is explicitly paused (`trackWriter.safePause(track)`), and status reverts to `PlaybackStatus.PAUSED` so `AudioTrack` never writes muted PCM in `PLAYING` state.
   - All `autoPlay = false` paths (cold-start session restore, paused Queue context switch, empty-queue enqueue) cancel any pending handshake and never trigger `PlaybackForegroundService` startup or audio focus requests.

### Software Verification (`./gradlew clean ktlintCheck detekt testDebugUnitTest lintDebug assembleDebug assembleRelease`)
- **Kotlin Formatting (`ktlintCheck`):** **PASS — 0 violations** across `main`, `test`, and Kotlin Gradle scripts.
- **Static Analysis (`detekt`):** **PASS — 0 issues** (0 baseline files).
- **Automated JVM Unit + Robolectric SQLite Migration + Headless Compose UI Tests (`testDebugUnitTest`):** **190 / 190 passed** across **33 test suites** (`0` failed, `0` errors, `0` skipped, `0` `@Ignore`), including 7/7 unit tests in [`ForegroundPlaybackGateTest.kt`](../app/src/test/java/com/cangkir/bitperfectplayer/audio/ForegroundPlaybackGateTest.kt).
- **Compiler, KSP, & R8 Warnings:** **0** Kotlin compiler warnings (production + test), **0** KSP warnings, **0** R8 warnings.
- **Android Lint (`lintDebug` & `lintVitalRelease`):** **0 errors**, **0 warnings across all `/app/src/` Kotlin, manifest, and resource files**.
- **Debug APK (`assembleDebug`):** `app/build/outputs/apk/debug/app-debug.apk` (`20.16 MB` / `21,142,230 bytes`).
- **Release APK (`assembleRelease` with R8 optimization):** `app/build/outputs/apk/release/app-release-unsigned.apk` (`2.59 MB` / `2,713,232 bytes`).
- **Pure-Kotlin Domain Boundary (`ForegroundPlaybackGate.kt`):** **PASS — 0 `android.*` / `androidx.*` imports**.
- **Strict Offline & Zero-Permission Audit (Source + All 8 Generated Debug, Release, & UnitTest Manifests):** **PASS — 0 `INTERNET`, 0 `RECORD_AUDIO`, 0 `MANAGE_EXTERNAL_STORAGE`**.
- **Physical Hardware Verification Pending (Not Executed in Cloud):** Real POCO F4 (Android 14 / HyperOS) + KZ EDX Pro 2 USB-C runtime testing and Android 17 (`API 37`) `adb shell cmd audio set-enable-hardening throw` verification remain unverified in this software-only environment.

---

## 26. Adaptive Large-Screen, Foldable, & Multi-Window UI (`WindowLayoutMetrics`) & `v1.1` Release Readiness (`D10.5`)

### Delivered Architecture & Adaptive Layouts
1. **Pure-Kotlin Adaptive Layout Classifier ([`WindowLayoutMetrics.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/ui/WindowLayoutMetrics.kt) + [`WindowLayoutMetricsTest.kt`](../app/src/test/java/com/cangkir/bitperfectplayer/ui/WindowLayoutMetricsTest.kt)):**
   - Pure-Kotlin classifier (`0` `android.*` / `androidx.*` imports) computing `useNavigationRail(widthDp)` (`widthDp >= 600`), `useTwoPaneNowPlaying(widthDp, heightDp)` (`(widthDp >= 600 && widthDp > heightDp) || widthDp >= 720`), `contentMaxWidthDp(widthDp)` (`840` when `widthDp >= 900`, else `null`), and `computeNowPlayingDiscContainerDp(availableWidthDp, availableHeightDp, isTwoPane)` (`220..316 dp`, preserving `316 dp` container / `240 dp` vinyl disc on portrait phones and scaling proportionally in landscape and compact split-screen).
2. **Adaptive Top-Level Navigation ([`BitPerfectAppNav.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/ui/BitPerfectAppNav.kt)):**
   - Added `BitPerfectAdaptiveTopLevelFrame` using `BoxWithConstraints` at `BitPerfectAppRoot`:
     - `< 600.dp`: renders the exact `v1.0` bottom `MiniPlayerBar` + `BitPerfectBottomNavBar`.
     - `>= 600.dp` on top-level tabs (`Library`, `Playlists`, `Settings`): renders a left-docked matte-obsidian `BitPerfectNavigationRail` separated by a `0.5.dp` `VerticalDivider` and docks `MiniPlayerBar` cleanly at the bottom of the right content pane while keeping `NavHost` in a stable composition slot.
3. **Adaptive 2-Column `NowPlayingScreen` ([`ui/nowplaying/NowPlayingScreen.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/ui/nowplaying/NowPlayingScreen.kt), [`RadialSpectrumRing.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/ui/RadialSpectrumRing.kt), [`ui/nowplaying/NowPlayingControls.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/ui/nowplaying/NowPlayingControls.kt)):**
   - Single-column portrait (`useTwoPaneNowPlaying == false`) and balanced 2-column studio `Row` (`useTwoPaneNowPlaying == true`, `46%` left pane with scaled `NowPlayingHeroCenterpiece` and `54%` right pane with scrollable `NowPlayingTrackControlsSection`) share the exact same hero and control composables with 120 Hz recomposition isolation intact.
4. **Centered Max-Width Containers on Wide Displays (`840.dp`):**
   - Centered inner content within `widthIn(max = 840.dp)` across [`SettingsScreen.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/ui/settings/SettingsScreen.kt), [`DiagnosticsScreen.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/ui/DiagnosticsScreen.kt), [`QueueScreen.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/ui/QueueScreen.kt), and [`LyricsScreen.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/ui/LyricsScreen.kt).
5. **Version Bump & Documentation Alignment (`v1.1`):**
   - Bumped [`app/build.gradle.kts`](../app/build.gradle.kts) to `versionCode = 2`, `versionName = "1.1"`.
   - Updated [`README.md`](../README.md), [`SPEC.md`](../SPEC.md), [`DECISIONS.md`](../DECISIONS.md) (`D10.5`), and [`docs/HANDOFF_REPORT.md`](HANDOFF_REPORT.md).

### Final `v1.1` Software Verification (`./gradlew clean ktlintCheck detekt testDebugUnitTest lintDebug assembleDebug assembleRelease`)
- **Kotlin Formatting (`ktlintCheck`):** **PASS — 0 violations** across `main`, `test`, and Kotlin Gradle scripts.
- **Static Analysis (`detekt`):** **PASS — 0 issues** (0 baseline files).
- **Automated JVM Unit + Robolectric SQLite Migration + Headless Compose UI Tests (`testDebugUnitTest`):** **197 / 197 passed** across **34 test suites** (`0` failed, `0` errors, `0` skipped, `0` `@Ignore`), including `4 / 4` in [`WindowLayoutMetricsTest.kt`](../app/src/test/java/com/cangkir/bitperfectplayer/ui/WindowLayoutMetricsTest.kt) and `17 / 17` in [`BitPerfectUiBehaviorTest.kt`](../app/src/test/java/com/cangkir/bitperfectplayer/ui/BitPerfectUiBehaviorTest.kt) (covering wide `840x480` `NavigationRail`, wide `840x480` 2-column `NowPlayingScreen` + rotational vinyl scrubbing, and compact `392x800` portrait `NavigationBar`).
- **Compiler, KSP, & R8 Warnings:** **0** Kotlin compiler warnings (production + test), **0** KSP warnings, **0** R8 warnings.
- **Android Lint (`lintDebug` & `lintVitalRelease`):** **0 errors**, **0 warnings across all `/app/src/` Kotlin, manifest, and resource files**.
- **Debug APK (`assembleDebug`):** `app/build/outputs/apk/debug/app-debug.apk` (`20.19 MB` / `21,175,006 bytes`).
- **Release APK (`assembleRelease` with R8 optimization):** `app/build/outputs/apk/release/app-release-unsigned.apk` (`2.62 MB` / `2,746,000 bytes`).
- **Pure-Kotlin Domain Boundary (`WindowLayoutMetrics.kt`, `ForegroundPlaybackGate.kt`, + all 30 core domain files):** **PASS — 0 `android.*` / `androidx.*` imports**.
- **Strict Offline & Zero-Permission Audit (Source + All 8 Generated Debug, Release, & UnitTest Manifests):** **PASS — 0 `INTERNET`, 0 `RECORD_AUDIO`, 0 `MANAGE_EXTERNAL_STORAGE`**.
- **Physical Hardware Verification Pending (Not Executed in Cloud):** Real POCO F4 (Android 14 / HyperOS) + KZ EDX Pro 2 USB-C portrait/landscape/split-screen runtime testing and Android 17 (`API 37`) `adb shell cmd audio set-enable-hardening throw` verification remain unverified in this software-only environment.

---

## 27. Release `v1.1` Finalization, `AGP 9.5.2` Patch, Merge to `main`, & Signed APK Publication

### Delivered Repository & Release Actions
1. **Toolchain Patch & Documentation Synchronization (`feat/v1.1`):**
   - Committed and pushed [`gradle/libs.versions.toml`](../gradle/libs.versions.toml) (`agp = "9.5.1"` -> `"9.5.2"`, `e7e4843`).
   - Synchronized [`README.md`](../README.md), [`CONTRIBUTING.md`](../CONTRIBUTING.md), [`SPEC.md`](../SPEC.md), [`DECISIONS.md`](../DECISIONS.md), and [`docs/HANDOFF_REPORT.md`](HANDOFF_REPORT.md) with `AGP 9.5.2`, `versionName = "1.1"` (`versionCode = 2`), `197` automated tests across `34` suites, and the `v1.1` pure-Kotlin domain additions (`ForegroundPlaybackGate`, `WindowLayoutMetrics`).
2. **Branch Merge (`feat/v1.1` -> `main`):**
   - Fast-forward merged `feat/v1.1` into `main` (retaining `feat/v1.1`) and pushed `main` to `origin`.
3. **Annotated Tag & GitHub Release Publication (`v1.1`):**
   - Created and pushed annotated Git tag `v1.1` matching `versionName = "1.1"`, `versionCode = 2`.
   - Published GitHub Release **`BitPerfectPlayer v1.1`** (`Latest`) at `https://github.com/WageFolabessy/BitPerfectPlayer/releases/tag/v1.1` attaching `BitPerfectPlayer-v1.1-release.apk` (`2,754,192` bytes).
   - **Verified Signed Release APK Integrity & Privacy:**
     - **APK SHA-256:** `87590aa8095269aae9dd02df3220c297c8ed1647529f51da34d7783e3b0a370e`
     - **Signing Scheme:** `Verifies` via APK Signature Scheme v2 (`CN=Endricho Folabessy`, Cert SHA-256: `c4dd7e0c9f5b883f56b268e54bd02361788fc9d8f52c313075c4f0447eef89d4`, matching `v1.0` for seamless in-place upgrade)
     - **Compiled APK Manifest Privacy Check:** Zero `INTERNET`, `RECORD_AUDIO`, or `MANAGE_EXTERNAL_STORAGE` permissions.

---

## 28. `feat/v1.2` Audio Engine Fixes & USB Native Sample-Rate Upgrade (`D11.1`–`D11.3`)

### Delivered Audio Engine Fixes
1. **Fix 1 — `audio/raw` (`FLAC (PCM)`) White-Noise Elimination ([`PcmStreamRules.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/audio/PcmStreamRules.kt), [`RawExtractorPcmDecoder.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/audio/RawExtractorPcmDecoder.kt), [`MediaCodecAudioDecoder.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/audio/MediaCodecAudioDecoder.kt)):**
   - Added pure-Kotlin `RawSourcePcmEncoding`, `resolveRawExtractorSourceEncoding(...)`, `selectRawOutputEncoding(...)`, `buildRawExtractorDecoderLabel(...)`, and zero-allocation offset-aware `convertPcm24LeToFloat32Bytes`, `convertPcm32LeToFloat32Bytes`, and `convertPcm8UnsignedToPcm16LeBytes`.
   - Delegated `MediaFormat.MIMETYPE_AUDIO_RAW` (`audio/raw`) directly to `RawExtractorPcmDecoder` before modifying `KEY_PCM_ENCODING` or creating `MediaCodec` (`c2.android.raw.decoder`), preventing raw integer PCM bytes from being mislabeled as IEEE-754 float32 on Android 16 (API 36) / Custom ROMs.
   - Uses bit-identical native `PCM_24BIT_PACKED` / `PCM_32BIT` passthrough on API 31+ when confirmed by `AudioTrack.getMinBufferSize(...) > 0`, falling back losslessly to `PCM_FLOAT` on API 26–30 or unsupported HALs, and reports verified FLAC/PCM source & decoder telemetry cleanly in Stages 1–2.
2. **Fix 2 — Live Turntable Post-Paused-Load (`loadTrack(autoPlay = false) -> play()`) Silence & Deadlock Fix ([`TurntableAudioDsp.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/audio/TurntableAudioDsp.kt), [`AudioTrackWriter.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/audio/AudioTrackWriter.kt), [`AudioOutputEngine.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/audio/AudioOutputEngine.kt), [`TurntableScratchRunner.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/audio/TurntableScratchRunner.kt)):**
   - Ordered `AudioOutputEngine.play()` and `activateLoadedTrackLocked()` so `startPlaybackWorkerLocked(...)` clears `stopWorkerFlag`, starts `AudioTrack`, and spawns `BitPerfectAudioThread` before `_state` publishes `PlaybackStatus.PLAYING`.
   - Added pure-Kotlin `TurntableFlushHeadTracker` (dynamic `headOrigin` rebasing on async `AudioTrack.flush()` reset-to-`0`) and `TurntableScratchPolicy.computeEffectiveScratchMaxAheadFrames(...)` (coupled with API 31+ `AudioTrack.startThresholdInFrames` lowering on scratch entry and full-buffer restoration on exit) so post-flush stale head readings and `startThresholdInFrames > maxAheadFrames` can never deadlock scratch rendering into silence / frozen `+00:00` delta.
   - Added Safe-Mode-only lazy buffer verification (`ensureSafeModeLoopScratchReady`), seek async-flush reconciliation (`reconcileAsyncFlushReset`), and bounded transient 0-byte codec warmup retries in `decodeAheadForForwardScratch`. Zero DSP allocation or interception in `BIT_PERFECT_OPT_IN`.
3. **Fix 3 — Android 14+ (`API 34+`) USB `MIXER_BEHAVIOR_DEFAULT` Preferred Sample-Rate Negotiation ([`UsbMixerProfileSelector.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/audio/UsbMixerProfileSelector.kt), [`AudioRouteAndMixerManager.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/audio/AudioRouteAndMixerManager.kt), [`AudioDiagnosticMapper.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/audio/AudioDiagnosticMapper.kt), [`DiagnosticsScreen.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/ui/DiagnosticsScreen.kt)):**
   - Added pure-Kotlin `UsbMixerProfileSelector` (`BIT_PERFECT` > `DEFAULT_NATIVE_RATE` > `NONE` in `BIT_PERFECT_OPT_IN`; strictly `DEFAULT_NATIVE_RATE` only — never `BIT_PERFECT` — in `SAFE_VOLUME_MODE`).
   - Added `NegotiatedMixerBehavior.DEFAULT_NATIVE_RATE` and `AudioDiagnosticSnapshot.isNativeSampleRateMixerActive` (keeping `isBitPerfectMixerActive == false`), updating Stage 4 (`VERIFIED` on API 34+ confirmation) and `DiagnosticsScreen` badges (`"BIT-PERFECT HAL ACTIVE"` vs `"SAFE MODE · NATIVE RATE USB"` vs `"NATIVE RATE USB MIXER"` vs `"CLEAN NATIVE FALLBACK"`).
   - Preserved mandatory `clearPreferredMixerAttributesOnApi34(...)` cleanup on track switch, Safe Mode switch, stop, release, and USB disconnect.

### Software Verification (`./gradlew clean ktlintCheck detekt testDebugUnitTest lintDebug assembleDebug assembleRelease`)
- **Kotlin Formatting (`ktlintCheck`):** **PASS — 0 violations** across `main`, `test`, and Kotlin Gradle scripts.
- **Static Analysis (`detekt`):** **PASS — 0 issues** (0 baseline files).
- **Automated JVM Unit + Robolectric Tests (`testDebugUnitTest`):** **211 / 211 passed** across **35 test suites** (`0` failed, `0` errors, `0` skipped, `0` `@Ignore`), including new pure-Kotlin tests in [`PcmStreamRulesTest.kt`](../app/src/test/java/com/cangkir/bitperfectplayer/audio/PcmStreamRulesTest.kt) (+5), [`TurntableAudioDspTest.kt`](../app/src/test/java/com/cangkir/bitperfectplayer/audio/TurntableAudioDspTest.kt) (+3), [`UsbMixerProfileSelectorTest.kt`](../app/src/test/java/com/cangkir/bitperfectplayer/audio/UsbMixerProfileSelectorTest.kt) (5/5), and [`AudioDiagnosticMapperTest.kt`](../app/src/test/java/com/cangkir/bitperfectplayer/audio/AudioDiagnosticMapperTest.kt) (+1).
- **Compiler, KSP, & R8 Warnings:** **0** Kotlin compiler warnings, **0** KSP warnings, **0** R8 warnings.
- **Android Lint (`lintDebug` & `lintVitalRelease`):** **0 errors**, **0 warnings across all `/app/src/` files**.
- **Debug APK (`assembleDebug`):** `app/build/outputs/apk/debug/app-debug.apk` (`20.21 MB` / `21,191,390 bytes`).
- **Release APK (`assembleRelease` with R8 optimization):** `app/build/outputs/apk/release/app-release-unsigned.apk` (`2.62 MB` / `2,746,000 bytes`).
- **ART JIT/AOT DEX Instruction Spot-Check (`dexdump`):** Largest method across `AudioOutputEngine`, `TurntableScratchRunner`, `RawExtractorPcmDecoder`, `AudioRouteAndMixerManager`, and `MediaCodecAudioDecoder` is **`645` DEX instructions** (25× below ART's `16,384` instruction JIT ceiling).
- **Pure-Kotlin Domain Boundary (`PcmStreamRules.kt`, `TurntableAudioDsp.kt`, `UsbMixerProfileSelector.kt`, `AudioDiagnosticMapper.kt`):** **PASS — 0 `android.*` / `androidx.*` imports**.
- **Strict Offline & Zero-Permission Audit (Source + All 8 Generated Debug, Release, & UnitTest Manifests):** **PASS — 0 `INTERNET`, 0 `RECORD_AUDIO`, 0 `MANAGE_EXTERNAL_STORAGE`**.

### Physical Hardware Verification Pending (Not Executed in Cloud)
- **Android 16 (API 36) / Custom ROM 24-bit/96 kHz FLAC (`audio/raw`):** Confirm crisp, noise-free playback via `RawExtractorPcmDecoder` and verify Stage 1–2 diagnostics show `FLAC` + `MediaExtractor Direct PCM (...)`.
- **Live Turntable Post-Auto-Pause Scratch:** Trigger a real USB plug-in or screen-recorder `AUTO_PAUSE` event (forcing `loadTrack(..., autoPlay = false)`), tap Play, and immediately touch/scrub forward (`2x–4x`), hold (`0x`), and reverse (`-2x`) without seeking first; confirm immediate audible scratch response and live delta movement.
- **API 34+ USB `MIXER_BEHAVIOR_DEFAULT` Native Sample-Rate Negotiation:** Play 44.1 kHz and 96 kHz lossless tracks on a USB-C DAC (KZ EDX Pro 2 USB-C) in both Safe Mode and Bit-Perfect Opt-In on a HAL with 0 bit-perfect profiles; verify Stage 4 reports `MIXER_BEHAVIOR_DEFAULT` locked to native track Hz with volume active, and verify clean `clearPreferredMixerAttributes` teardown on stop/unplug.

---

## 29. `feat/v1.2` Playback Queue & Library UX Upgrades (`D11.4`–`D11.6`)

### Delivered UX Improvements
1. **Strictly Passive Read-Only Queue Top-Bar Icon ([`BitPerfectAppNav.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/ui/BitPerfectAppNav.kt), [`LibraryScreen.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/ui/library/LibraryScreen.kt), [`PlaylistsScreen.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/ui/playlists/PlaylistsScreen.kt), [`MainViewModel.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/ui/MainViewModel.kt), [`PlaybackController.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/audio/PlaybackController.kt), [`QueueContext.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/audio/QueueContext.kt)):**
   - Tapping the top-bar Queue icon (`Icons.AutoMirrored.Filled.QueueMusic`) from any screen (`All Songs`, `Folders`, `Artists`, `Albums`, `Recent`, `Favorites`, `Playlists`, Album/Artist/Folder/Playlist/Most Played details, `Now Playing`) now passively opens `QueueScreen` showing the active playback queue without pausing playback or replacing the queue.
   - Removed dead auto-context-switching APIs (`BrowsingQueueContext`, `QueueContextResolver.shouldSwitchToBrowsingContext`, `MainViewModel.prepareQueueScreenContext`, `PlaybackController.loadContextQueue`).
2. **Pure-Compose Drag-and-Drop Reordering in `QueueScreen` ([`QueueScreen.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/ui/QueueScreen.kt), [`PlaybackQueueManager.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/audio/PlaybackQueueManager.kt), [`QueueContext.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/audio/QueueContext.kt)):**
   - Removed Up/Down arrow reorder buttons (`ArrowUpward` / `ArrowDownward`) completely and added a leading `Icons.Rounded.DragHandle` (`48.dp x 48.dp` dedicated touch/semantics target, `contentDescription = "Reorder track"`, decoupled from middle track-selection click and far-right `Remove` button; hairline divider aligned at `104.dp`).
   - Added stable per-slot `entryIds: List<Long>` to `QueueSnapshot<T>` (eliminating duplicate-key crashes even when identical track URIs appear multiple times) and pure-Kotlin `QueueDragReorderMath` (`computeDragSwap` with `2 px` hysteresis + `computeEdgeAutoScrollPx`).
   - Mounted stationary `pointerInput` (`PointerEventPass.Initial`, RTL-aware leading `56.dp` handle column) on the `LazyColumn` so GPU-only `graphicsLayer` transforms never shift pointer coordinates, normal vertical scrolling across the rest of each row stays untouched, `firstVisibleItemIndex` stays anchored via `requestScrollToItem`, edge auto-scrolling runs smoothly via `withFrameNanos`, and `PlaybackQueueManager.moveItem(from, to)` keeps `currentIndex` anchored to the active playing track.
3. **Full-List `All Songs` Search Playback ([`QueueContext.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/audio/QueueContext.kt), [`LibraryScreen.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/ui/library/LibraryScreen.kt)):**
   - Added pure-Kotlin `QueueContextResolver.resolveFullListPlaybackSelection(...)` matching the tapped search result by stable `uriString` inside the full sorted `libraryState.allSongs` list (with safe bounded fallback).
   - Tapping any filtered search hit in `All Songs` now loads the entire sorted `All Songs` queue starting at that track's index under `QueueContext.allSongs()`, keeping Next/Previous/Shuffle functional across the full library.

### Software Verification (`./gradlew clean ktlintCheck detekt testDebugUnitTest lintDebug assembleDebug assembleRelease`)
- **Kotlin Formatting (`ktlintCheck`):** **PASS — 0 violations** across `main`, `test`, and Kotlin Gradle scripts.
- **Static Analysis (`detekt`):** **PASS — 0 issues** (0 baseline files).
- **Automated JVM Unit + Robolectric Tests (`testDebugUnitTest`):** **217 / 217 passed** across **35 test suites** (`0` failed, `0` errors, `0` skipped, `0` `@Ignore`), including:
  - [`QueueContextTest.kt`](../app/src/test/java/com/cangkir/bitperfectplayer/audio/QueueContextTest.kt): `5 / 5` (covering `resolveFullListPlaybackSelection`, `QueueDragReorderMath` downward/upward midpoint swaps + hysteresis + edge auto-scroll, and session restore).
  - [`PlaybackQueueManagerTest.kt`](../app/src/test/java/com/cangkir/bitperfectplayer/audio/PlaybackQueueManagerTest.kt): `7 / 7` (verifying `moveItem` active-track identity + `entryIds` across all drag directions, duplicate URIs, and out-of-bounds guards).
  - [`BitPerfectUiBehaviorTest.kt`](../app/src/test/java/com/cangkir/bitperfectplayer/ui/BitPerfectUiBehaviorTest.kt): `24 / 24` (+4 new UI behavior tests verifying strictly passive Queue icon across Library tabs/details and Playlists overview/detail, pure-Compose drag-handle reordering with zero arrow buttons, and full-list `All Songs` search playback).
- **Compiler, KSP, & R8 Warnings:** **0** Kotlin compiler warnings, **0** KSP warnings, **0** R8 warnings.
- **Android Lint (`lintDebug` & `lintVitalRelease`):** **0 errors**, **0 warnings across all `/app/src/` files**.
- **Debug APK (`assembleDebug`):** `app/build/outputs/apk/debug/app-debug.apk` (`20.23 MB` / `21,207,774 bytes`).
- **Release APK (`assembleRelease` with R8 optimization):** `app/build/outputs/apk/release/app-release-unsigned.apk` (`2.63 MB` / `2,762,384 bytes`; zero third-party drag-and-drop libraries).
- **Pure-Kotlin Domain Boundary (`QueueContext.kt`, `PlaybackQueueManager.kt`):** **PASS — 0 `android.*` / `androidx.*` imports**.
- **Strict Offline & Zero-Permission Audit (Source + All 6 Generated Debug, Release, & UnitTest Manifests):** **PASS — 0 `INTERNET`, 0 `RECORD_AUDIO`, 0 `MANAGE_EXTERNAL_STORAGE`**.

### Physical Hardware Verification Pending (Not Executed in Cloud)
- **Touch Ergonomics & 120 Hz Drag-and-Drop Feel:** Verify tactile haptic feedback, smooth row elevation/swapping across multi-track drags (including top/bottom edge auto-scrolling on 50+ track queues), and unaffected normal vertical scrolling on physical POCO F4 120 Hz display.

---

## 30. `v1.2` Release Finalization — Default Homepage, Uncropped Artwork (Option B), & Version `1.2` (`D11.7`–`D11.8`)

### Delivered Features & Release Metadata
1. **Configurable Default Homepage ([`PersistenceModels.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/data/PersistenceModels.kt), [`UserPreferencesRepository.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/data/UserPreferencesRepository.kt), [`LocalBackupCodec.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/data/LocalBackupCodec.kt), [`LocalBackupRepository.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/data/LocalBackupRepository.kt), [`MainViewModel.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/ui/MainViewModel.kt), [`BitPerfectAppNav.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/ui/BitPerfectAppNav.kt), [`LibraryTab.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/ui/LibraryTab.kt), [`LibraryScreen.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/ui/library/LibraryScreen.kt), [`SettingsScreen.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/ui/settings/SettingsScreen.kt), [`SettingsDisplayAndLibrarySection.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/ui/settings/SettingsDisplayAndLibrarySection.kt)):**
   - Added pure-Kotlin `DefaultHomepage` (`ALL_SONGS`, `FOLDERS`, `ARTISTS`, `ALBUMS`, `RECENT`, `FAVORITES`, `PLAYLISTS`; default `ALL_SONGS`) persisted in Jetpack DataStore and backward-compatible offline `.json` backups (`LocalBackupCodec` `schemaVersion = 1`; legacy v1.0/v1.1 backups missing `"defaultHomepage"` safely default to `ALL_SONGS`).
   - Latched `MainViewModel.initialStartupHomepage` strictly once at startup and routed through `BitPerfectStartupNavHost` (`rememberSaveable` start destination and initial `LibraryTab`) so cold launch lands directly on `Playlists` or the chosen `Library` tab with zero flicker and never hijacks subsequent user tab switches or Settings edits.
   - Added a matte-obsidian `"Default Homepage"` selector row and `DefaultHomepageBottomSheet` (`ModalBottomSheet` with `>= 48.dp` rows) inside `SettingsDisplaySection`.
2. **Full Uncropped Album Artwork — Option B ([`AlbumArtworkHelper.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/library/AlbumArtworkHelper.kt), [`AlbumArtworkLoader.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/library/AlbumArtworkLoader.kt), [`UiComponents.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/ui/UiComponents.kt), [`VinylDiscPlayer.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/ui/VinylDiscPlayer.kt)):**
   - Extracted pure-Kotlin `ArtworkDimensions` and `AlbumArtworkHelper.computeProportionalTargetDimensions(...)` (`Double`-precision proportional max-bound scaling; never center-crops non-square covers during decode or disk-cache generation) and delegated `AlbumArtworkLoader.constrainMaxDimension` to it.
   - Switched `TrackArtworkThumbnail` (shared across `LibraryTrackList`, `LibraryGroupViews`, `PlaylistsOverview`, `PlaylistDetailView`, `QueueScreen`, `MiniPlayerBar`, and `SwipeableSquareArtwork`) to `ContentScale.Fit` on a rounded matte-dark `surfaceVariant` container backdrop so `1:1` covers fill seamlessly while `16:9`, `4:3`, and portrait covers display 100% intact with zero cropped edges.
   - Preserved the authentic circular Vinyl Disc center label (`CircleShape` + uniform distortion-free `ContentScale.Crop` over the aspect-preserved bitmap — Option B).
3. **Version Bump & Documentation Alignment:**
   - Updated [`app/build.gradle.kts`](../app/build.gradle.kts) to **`versionCode = 3`, `versionName = "1.2"`** (and updated `SettingsScreen.kt` fallback version to `"1.2"`).
   - Synchronized [`README.md`](../README.md), [`SPEC.md`](../SPEC.md), [`DECISIONS.md`](../DECISIONS.md), and [`docs/HANDOFF_REPORT.md`](HANDOFF_REPORT.md).

### Final `v1.2` Software Verification (`./gradlew clean ktlintCheck detekt testDebugUnitTest lintDebug` + `assembleDebug assembleRelease`)
- **Kotlin Formatting (`ktlintCheck`):** **PASS — 0 violations** across `main`, `test`, and Kotlin Gradle scripts.
- **Static Analysis (`detekt`):** **PASS — 0 issues** (0 baseline files).
- **Automated JVM Unit + Room Migration + Robolectric Compose UI Tests (`testDebugUnitTest`):** **222 / 222 passed** across **35 test suites** (`0` failed, `0` errors, `0` skipped, `0` `@Ignore`), including:
  - [`PersistenceAndPreferencesTest.kt`](../app/src/test/java/com/cangkir/bitperfectplayer/data/PersistenceAndPreferencesTest.kt): `4 / 4` (verifying `DefaultHomepage.ALL_SONGS` fresh default, DataStore round-trip, and `fromStoredName` fallbacks).
  - [`LocalBackupCodecTest.kt`](../app/src/test/java/com/cangkir/bitperfectplayer/data/LocalBackupCodecTest.kt): `4 / 4` (+1 verifying `defaultHomepage` JSON round-trip and legacy v1.0/v1.1 backward compatibility).
  - [`AlbumArtworkHelperTest.kt`](../app/src/test/java/com/cangkir/bitperfectplayer/library/AlbumArtworkHelperTest.kt): `8 / 8` (+1 verifying square `1000x1000 -> 512x512`, landscape `1600x900 -> 512x288`, portrait `900x1600 -> 288x512`, 4:3, tolerance band, and extreme banner aspect preservation).
  - [`BitPerfectUiBehaviorTest.kt`](../app/src/test/java/com/cangkir/bitperfectplayer/ui/BitPerfectUiBehaviorTest.kt): `27 / 27` (+3 verifying Settings Default Homepage modal picker, `DefaultHomepage.PLAYLISTS` cold start with zero navigation hijack on later pref changes, and `DefaultHomepage.ALBUMS` cold start with manual tab switching).
- **Compiler, KSP, & R8 Warnings:** **0** Kotlin compiler warnings, **0** KSP warnings, **0** R8 warnings.
- **Android Lint (`lintDebug` & `lintVitalRelease`):** **0 errors**, **0 warnings across all `/app/src/` Kotlin, manifest, and resource files**.
- **Merged Release Manifest Version:** Verified `android:versionCode="3"` and `android:versionName="1.2"`.
- **Debug APK (`assembleDebug`):** `app/build/outputs/apk/debug/app-debug.apk` (`20.24 MB` / `21,224,150 bytes`).
- **Release APK (`assembleRelease` with R8 optimization):** `app/build/outputs/apk/release/app-release-unsigned.apk` (`2.65 MB` / `2,778,776 bytes`).
- **Pure-Kotlin Domain Boundary (`PersistenceModels.kt`, `LocalBackupCodec.kt`, `AlbumArtworkHelper.kt`, `LibraryTab.kt` + all core domain modules):** **PASS — 0 `android.*` / `androidx.*` imports**.
- **Strict Offline & Zero-Permission Audit (Source + All 6 Generated Debug, Release, & UnitTest Manifests):** **PASS — 0 `INTERNET`, 0 `RECORD_AUDIO`, 0 `MANAGE_EXTERNAL_STORAGE`**.

### Complete `v1.2` Physical Hardware Verification Checklist (Not Executed in Cloud)
1. **POCO F4 (HyperOS / Android 14) + KZ EDX Pro 2 USB-C:**
   - [ ] **Safe Mode Live Turntable After Auto-Pause:** Trigger a real USB plug-in or route-change `AUTO_PAUSE` (leaving the track loaded via `autoPlay = false`), press Play, and immediately grab/scrub forward (`2x–4x`), hold (`0x`), and reverse (`-2x`) without seeking first; confirm immediate audible scratch response and smooth delta HUD updates.
   - [ ] **USB-C DAC `MIXER_BEHAVIOR_DEFAULT` Native Sample-Rate Lock:** Play 44.1 kHz and 96 kHz lossless tracks in both Safe Mode and Bit-Perfect Opt-In on a USB DAC / HAL with 0 bit-perfect profiles; verify Stage 4 reports `MIXER_BEHAVIOR_DEFAULT` locked to the native track Hz with system volume working normally, and verify `clearPreferredMixerAttributes` cleanly resets on stop/unplug.
   - [ ] **Drag-and-Drop Queue Reordering (120 Hz):** Drag queue rows up/down via the leading `48.dp` handle, including multi-row drags and edge auto-scrolling on 50+ track queues; verify haptic feedback, smooth row elevation, active-track highlight staying anchored to the playing song, and normal vertical scrolling across the rest of each row.
   - [ ] **Passive Queue Top-Bar Icon:** While playing an album or playlist, browse to other Library tabs, Artist/Album/Folder details, and Playlists detail, tapping the top-bar Queue icon each time; verify playback never pauses and the active queue is never overwritten.
   - [ ] **Full-List `All Songs` Search Playback:** Search in `All Songs` until 1 result remains, tap it, and confirm Next/Previous/Shuffle traverse the entire sorted `All Songs` library seamlessly.
   - [ ] **Default Homepage Cold Launch & Backup Round-Trip:** Set Default Homepage to `Playlists` (and then `Albums` / `Favorites`) in Settings, confirm Settings does not hijack navigation immediately, force-close & cold-launch the app to confirm direct startup on the chosen view, and verify SAF `.json` backup export/restore preserves `defaultHomepage`.
   - [ ] **Full Uncropped Non-Square Album Cover Artwork (Option B):** Inspect tracks with `16:9` landscape and portrait cover art across `All Songs`, `Albums`, `Playlists`, `Queue`, `MiniPlayer`, and `Now Playing` Square Cover mode (confirm 100% uncropped `ContentScale.Fit` on matte-obsidian backdrop), and switch to Vinyl Disc mode to confirm distortion-free circular vinyl center framing.
2. **Android 16 (API 36) / Custom ROM:**
   - [ ] **`audio/raw` 24-bit/96 kHz FLAC Playback:** Play a 24-bit/96 kHz FLAC file where `MediaExtractor` exposes `audio/raw`; confirm clean noise-free audio via `RawExtractorPcmDecoder` and verify Stages 1–2 diagnostics report `FLAC` + `MediaExtractor Direct PCM (...)`.

---

## 31. `v1.2` Opt-In Local Diagnostic Logging Subsystem (`D11.9`)

### Delivered Architecture & Files
1. **Pure-Kotlin Diagnostic Core (`com.cangkir.bitperfectplayer.diagnostics`, `0` `android.*` / `androidx.*` imports):**
   - [`DiagnosticLogChannel.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/diagnostics/DiagnosticLogChannel.kt): Defines `DiagnosticLogLevel`, the 4 bounded files (`device_summary.txt`, `audio_pipeline.txt`, `performance.txt`, `errors_and_crashes.txt`) + `.prev.txt` backup mapping, and `DiagnosticLogStats.formatSummary()`.
   - [`DiagnosticLogFormatter.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/diagnostics/DiagnosticLogFormatter.kt): Single-line UTC ISO-8601 `key=value` formatter, `--- CRASH_START ---`/`--- CRASH_END ---` crash wrapper, allowlisted audio extension extractor, 8-char SHA-256 prefix helper, `README_LOGS.txt` generator, and defense-in-depth PII key + `content://`/`file://` + `/storage/` path redaction.
   - [`DiagnosticLogRotationPolicy.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/diagnostics/DiagnosticLogRotationPolicy.kt): `256 KB` active-file rotation with max 1 `.prev.txt` backup per append channel (`< 2 MB` total local cap), atomic snapshot writes for `device_summary.txt`, storage stats computation, and full cleanup.
   - [`DiagnosticLogBuffer.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/diagnostics/DiagnosticLogBuffer.kt): Bounded `512`-entry drop-oldest ring queue with overflow tracking.
2. **Android Runtime, Crash Handler, Zero-Permission ZIP Exporter, Manifest & R8 Rules:**
   - [`LocalDiagnosticLogger.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/diagnostics/LocalDiagnosticLogger.kt): Strictly default-OFF (`isEnabled = false`) logger with zero-allocation `inline` guards when disabled, lazy `BitPerfectDiagWriter` background thread (`ReentrantLock` + `Condition`, 3-second batching + explicit flush), synchronous crash capture, and live `storageStats: StateFlow<DiagnosticLogStats>`.
   - [`DiagnosticCrashHandler.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/diagnostics/DiagnosticCrashHandler.kt): Chained `Thread.UncaughtExceptionHandler` capturing bounded crash traces strictly when opted in and always delegating to the previous handler.
   - [`DiagnosticLogExporter.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/diagnostics/DiagnosticLogExporter.kt): Standard-JDK `ZipOutputStream` packager (`README_LOGS.txt` + active/rotated `.txt` files) for SAF `CreateDocument("application/zip")` export and scoped `FileProvider` `Intent.ACTION_SEND` sharing.
   - [`file_paths.xml`](../app/src/main/res/xml/file_paths.xml), [`AndroidManifest.xml`](../app/src/main/AndroidManifest.xml) (non-exported `androidx.core.content.FileProvider` scoped strictly to `cacheDir/diagnostic_exports/`; zero new permissions), and [`rules.keep`](../app/src/main/keepRules/rules.keep) (`-keepattributes SourceFile,LineNumberTable`).
3. **Strategic Zero-PII Instrumentation & Modular Settings UI:**
   - Persisted `diagnosticLoggingEnabled: Boolean = false` in [`PersistenceModels.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/data/PersistenceModels.kt) and [`UserPreferencesRepository.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/data/UserPreferencesRepository.kt) (never auto-enabled by `.json` backup restore).
   - Instrumented [`BitPerfectApplication.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/BitPerfectApplication.kt), [`MainActivity.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/MainActivity.kt), [`MediaCodecAudioDecoder.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/audio/MediaCodecAudioDecoder.kt), [`AudioRouteAndMixerManager.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/audio/AudioRouteAndMixerManager.kt), [`AudioTrackWriter.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/audio/AudioTrackWriter.kt), [`TurntableScratchRunner.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/audio/TurntableScratchRunner.kt), [`AudioOutputEngine.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/audio/AudioOutputEngine.kt), [`PlaybackController.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/audio/PlaybackController.kt), and [`PlaybackForegroundService.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/audio/PlaybackForegroundService.kt) strictly on discrete lifecycle transitions (zero per-buffer or per-frame logging).
   - Created [`SettingsDiagnosticLoggingSection.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/ui/settings/SettingsDiagnosticLoggingSection.kt) and wired it into [`MainViewModel.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/ui/MainViewModel.kt), [`SettingsScreen.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/ui/settings/SettingsScreen.kt), and [`BitPerfectAppNav.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/ui/BitPerfectAppNav.kt).

### Final Clean Verification Evidence (`./gradlew clean ktlintCheck detekt testDebugUnitTest lintDebug` + `assembleDebug assembleRelease`)
- **Kotlin Formatting (`ktlintCheck`):** **PASS — 0 violations** across `main`, `test`, and Kotlin Gradle scripts.
- **Static Analysis (`detekt`):** **PASS — 0 issues** (0 baseline files).
- **Automated JVM Unit + Room Migration + Robolectric Compose UI Tests (`testDebugUnitTest`):** **243 / 243 passed** across **39 test suites** (`0` failed, `0` errors, `0` skipped, `0` `@Ignore`, `0` `TODO()`), including:
  - [`DiagnosticLogFormatterTest.kt`](../app/src/test/java/com/cangkir/bitperfectplayer/diagnostics/DiagnosticLogFormatterTest.kt): `9 / 9`
  - [`DiagnosticLogRotationPolicyTest.kt`](../app/src/test/java/com/cangkir/bitperfectplayer/diagnostics/DiagnosticLogRotationPolicyTest.kt): `4 / 4`
  - [`DiagnosticLogBufferTest.kt`](../app/src/test/java/com/cangkir/bitperfectplayer/diagnostics/DiagnosticLogBufferTest.kt): `4 / 4`
  - [`LocalDiagnosticLoggerTest.kt`](../app/src/test/java/com/cangkir/bitperfectplayer/diagnostics/LocalDiagnosticLoggerTest.kt): `3 / 3` (default-OFF zero-disk-write invariant, 4-channel ZIP export + `README_LOGS.txt` inspection, `clearAllLogs`, and `DiagnosticCrashHandler` chaining)
  - [`PersistenceAndPreferencesTest.kt`](../app/src/test/java/com/cangkir/bitperfectplayer/data/PersistenceAndPreferencesTest.kt): `4 / 4` (`diagnosticLoggingEnabled` default `false` + round-trip toggle)
  - [`BitPerfectUiBehaviorTest.kt`](../app/src/test/java/com/cangkir/bitperfectplayer/ui/BitPerfectUiBehaviorTest.kt): `28 / 28` (+1 verifying Settings `DIAGNOSTIC LOGGING` default-OFF state, disabled action buttons, toggle ON live footprint, Share click, and Clear reset)
- **Compiler, KSP, & R8 Warnings:** **0** Kotlin compiler warnings, **0** KSP warnings, **0** R8 warnings.
- **Android Lint (`lintDebug` & `lintVitalRelease`):** **0 errors**, **0 total warnings**.
- **Version Integrity:** Merged Release manifest confirmed `android:versionCode="3"` and `android:versionName="1.2"`.
- **Debug APK (`assembleDebug`):** `app/build/outputs/apk/debug/app-debug.apk` (`20.32 MB` / `21,306,566 bytes`).
- **Release APK (`assembleRelease` with R8 optimization):** `app/build/outputs/apk/release/app-release-unsigned.apk` (`2.70 MB` / `2,828,380 bytes`).
- **R8 Mapping Artifact (`retrace` support):** `app/build/outputs/mapping/release/mapping.txt` (`36.17 MB` / `37,922,287 bytes`, non-empty).
- **Pure-Kotlin Domain Boundary (`DiagnosticLogChannel`, `DiagnosticLogFormatter`, `DiagnosticLogRotationPolicy`, `DiagnosticLogBuffer`):** **PASS — 0 `android.*` / `androidx.*` imports**.
- **Strict Offline & Zero-Permission Audit (XML-parsed Source + All 5 Merged Debug/Release/UnitTest Manifests):** **PASS — 0 `INTERNET`, 0 `RECORD_AUDIO`, 0 `MANAGE_EXTERNAL_STORAGE`, 0 `WRITE_EXTERNAL_STORAGE`**; `FileProvider` confirmed `exported="false"`, `grantUriPermissions="true"`, `authorities="com.cangkir.bitperfectplayer.fileprovider"`.

### Additional Hardware Verification Checklist (Not Executed in Cloud)
- [ ] **Release APK Field Diagnostics Workflow:** Install signed `v1.2` Release APK on physical POCO F4 / Android 16 device, confirm `DIAGNOSTIC LOGGING` defaults OFF (`No logs stored`), toggle ON, play a 24-bit/96 kHz FLAC + test USB-C DAC + live turntable scrub, verify **Export Logs (.zip)** via Android SAF saves a valid zip (`README_LOGS.txt` + `.txt` channels with zero song titles/paths), verify **Share Logs** opens the Android Share Sheet cleanly via `FileProvider`, and verify **Clear Logs** deletes all stored log/cache files.

---

## 32. `v1.2` Adversarial 7-Domain Code & Runtime Audit & Remediation Summary (`D11.10`)

Following completion of all `v1.2` features on `feat/v1.2`, a full diff-wide adversarial audit (`main..HEAD`) was conducted across all 7 target domains. Confirmed findings were fixed directly with zero behavioral regressions and covered by new automated regression tests.

### Domain-by-Domain Audit Findings & Fixes

1. **Domain 1 — Android 16 `audio/raw` Direct PCM Bypass & Hot-Path Allocations:**
   - **Finding 1A (Fixed — [`RawExtractorPcmDecoder.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/audio/RawExtractorPcmDecoder.kt)):** Removed the `?: flacInfo?.bitsPerSample` fallback when computing `reportedBitsPerSample` so PCM framing derives strictly from `MediaExtractor`'s `trackFormat` (`KEY_PCM_ENCODING` -> `"bits-per-sample"` / `"bit-depth"` -> `PCM_16BIT`), preventing an extractor emitting down-converted 16-bit `audio/raw` without `KEY_PCM_ENCODING` from being misframed as 24-bit packed PCM (while preserving `flacInfo?.bitsPerSample` for honest Stage 1 `sourceBitDepth` reporting). Also wrapped `IllegalStateException` from `extractor.readSampleData` as `AudioDecodeException.IoError`.
   - **Finding 1B (Fixed — [`PcmStreamRules.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/audio/PcmStreamRules.kt)):** Rewrote `PcmStreamRules.convertLittleEndianBytesToFloats` with direct little-endian bit shifting + `Float.fromBits(...)`, eliminating a per-buffer `HeapByteBuffer` (`ByteBuffer.wrap`) allocation on every float32 PCM write to `AudioTrack`.
   - **Regression Test:** `PcmStreamRulesTest.convertLittleEndianBytesToFloats_handlesOffsetsBoundsAndSignBitsBitExactly`.
2. **Domain 2 — Safe-Mode Live Turntable Scratch & `TurntableFlushHeadTracker`:**
   - **Audit Verdict: CLEAN.** Verified all 4 start/resume paths (`loadTrack(autoPlay = false)` -> `play()`, cold-start restore -> `play()`, `pause()` -> `play()`, and noisy/USB/route `AUTO_PAUSE` -> `play()`) keep `hasPendingAudioTrackFlush` synchronized and guarantee the 8 MB `TurntablePcmRingBuffer` is never allocated in `BIT_PERFECT_OPT_IN` and always freed on `stop()`, `release()`, and Bit-Perfect mode switch.
3. **Domain 3 — USB-C DAC `MIXER_BEHAVIOR_DEFAULT` Sample-Rate Lock & Mixer Cleanup:**
   - **Finding 3A (Fixed — [`AudioTrackWriter.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/audio/AudioTrackWriter.kt)):** Guarded `AudioTrack.Builder().build()` inside `createConfiguredAudioTrackLocked` with a `try { ... } finally { if (!trackBuilt) routeAndMixerManager.clearPreferredMixerAttributesOnApi34(usbDevice) }` rollback so preferred USB mixer attributes can never leak even if `AudioTrack` construction throws during initial open or mid-stream format switches.
4. **Domain 4 — Passive Queue Navigation, Pure-Compose Drag-and-Drop, & Full-List Search:**
   - **Finding 4A (Fixed — [`QueueScreen.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/ui/QueueScreen.kt) & [`PlaylistDialogs.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/ui/playlists/PlaylistDialogs.kt)):** Deduplicated `basePool` via `.distinctBy { it.uriString }` in `AddTrackToQueueDialog` so opening the picker while the active context is a Playlist with duplicate tracks can never crash `LazyColumn` with duplicate keys. Also wrapped row + `HorizontalDivider` siblings inside a single `Column(Modifier.fillMaxWidth())` root in both `AddTrackToQueueDialog` and `AddToPlaylistDialog`.
   - **Finding 4B (Fixed — [`PlaybackController.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/audio/PlaybackController.kt)):** Debounced `syncNextTrackToEngine()` in `moveQueueItem` (`250 ms` `queueReorderSyncJob`, canceled on direct `syncNextTrackToEngine`/`stop`/`release`) so dragging across `currentIndex + 1` in the final 8 seconds of a track never thrashes `GaplessPreloadController` with repeated decoder open/close cycles.
   - **Finding 4C (Fixed — [`QueueScreen.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/ui/QueueScreen.kt)):** Reset `dragState` inside `LaunchedEffect(rowKeys)` whenever `dragState.draggedItemKey !in rowKeys`, protecting against external queue replacement mid-drag when `queue.items.size > 1`.
   - **Passive Queue Check: CLEAN.** Verified zero lingering auto-switch queue helpers across navigation and library screens.
   - **Regression Test:** `BitPerfectUiBehaviorTest.queueScreen_duplicateTracksInQueueAndAddDialog_neverCrashLazyColumn`.
5. **Domain 5 — Default Homepage Preference & Configuration-Change Restoration:**
   - **Audit Verdict: CLEAN.** Confirmed one-shot async DataStore latch before mounting `NavHost`, safe preservation across configuration changes via `MainViewModel` + `rememberNavController()` + `rememberSaveable`, and `LocalBackupCodec` backward/forward compatibility (without touching `diagnosticLoggingEnabled`).
   - **Regression Test:** `BitPerfectUiBehaviorTest.startupNavHost_stateRestorationAfterConfigChange_preservesActiveRouteWithoutSnappingToDefaultHomepage` (`StateRestorationTester`).
6. **Domain 6 — Uncropped Album Artwork (Option B):**
   - **Audit Verdict: CLEAN.** Verified proportional decode (`AlbumArtworkHelper` / `AlbumArtworkLoader`), `ContentScale.Fit` on `surfaceVariant` in `TrackArtworkThumbnail` across all browsing/queue/mini-player/square-cover surfaces, and distortion-free `CircleShape` + `ContentScale.Crop` preserved strictly on `VinylDiscPlayer`.
7. **Domain 7 — Opt-In Local Diagnostic Logging (PII Redaction, Crash Safety, Zero Overhead When OFF, UI Layout):**
   - **Finding 7A (Fixed — [`DiagnosticLogFormatter.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/diagnostics/DiagnosticLogFormatter.kt), [`MediaCodecAudioDecoder.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/audio/MediaCodecAudioDecoder.kt), [`AudioOutputEngine.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/audio/AudioOutputEngine.kt)):** Extended `redactSensitivePatterns` to scrub `/data/user/\d+/...`, `/data/data/...`, and SAF volume-colon doc IDs (`primary:Music/...`, `XXXX-XXXX:...`), added `redactFilenameAndPaths(raw, knownFileName)`, and applied it to `DECODER_OPEN_ERROR` and `PLAYBACK_WORKER_ERROR` so bare filenames embedded in exception messages can never leak into `errors_and_crashes.txt`.
   - **Finding 7B (Fixed — [`LocalDiagnosticLogger.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/diagnostics/LocalDiagnosticLogger.kt)):** Replaced unbounded synchronized lock acquisition with `ReentrantLock()` + bounded `fileWriteLock.tryLock(500L, TimeUnit.MILLISECONDS)` inside `writeCrashSync(...)` so `DiagnosticCrashHandler` can never deadlock or hang on disk I/O before delegating to the previous `UncaughtExceptionHandler`.
   - **Finding 7C (Fixed — [`BitPerfectApplication.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/BitPerfectApplication.kt)):** Skipped `buildDeviceSummaryFields(prefs)` completely when `!prefs.diagnosticLoggingEnabled` and gated `playbackControllerLazy.isInitialized()` so cold start never eagerly initializes `PlaybackController`/`AudioOutputEngine` on `Dispatchers.IO` or allocates diagnostic maps while logging is OFF.
   - **Finding 7D (Fixed — [`LocalDiagnosticLogger.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/diagnostics/LocalDiagnosticLogger.kt)):** Parked `BitPerfectDiagWriter` indefinitely via `signalCondition.await()` when `!isEnabled && !flushRequested && buffer.size() == 0`, eliminating unnecessary 3-second wakeups after toggling logging OFF.
   - **Finding 7E (Fixed — [`SettingsDiagnosticLoggingSection.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/ui/settings/SettingsDiagnosticLoggingSection.kt)):** Adjusted `"Export Logs (.zip)"` vs `"Share Logs"` button weights (`1.28f` vs `1f`), compact horizontal padding (`10.dp`), and `TextOverflow.Ellipsis` so `"Export Logs (.zip)"` never clips on narrow `360–393.dp` phones.
   - **Regression Tests:** `DiagnosticLogFormatterTest.redactFilenameAndPaths_stripsBareFilenamesDataPathsAndSafDocIds` and `LocalDiagnosticLoggerTest.exporter_whenEmptyOrDisabled_returnsGracefulErrorWithoutCreatingZip`.

### Post-Audit Clean Verification Evidence (`./gradlew clean ktlintCheck detekt testDebugUnitTest lintDebug` + `assembleDebug assembleRelease`)
- **Kotlin Formatting (`ktlintCheck`):** **PASS — 0 violations**.
- **Static Analysis (`detekt`):** **PASS — 0 issues** (0 baseline files).
- **Automated JVM Unit + Room Migration + Robolectric Compose UI Tests (`testDebugUnitTest`):** **248 / 248 passed** across **39 test suites** (`0` failed, `0` errors, `0` skipped, `0` `@Ignore`, `0` `TODO()`).
- **Compiler, KSP, & R8 Warnings:** **0** Kotlin compiler warnings, **0** KSP warnings, **0** R8 warnings.
- **Android Lint (`lintDebug` & `lintVitalRelease`):** **0 errors**, **0 total warnings**.
- **Version Integrity:** Merged Release manifest confirmed `android:versionCode="3"` and `android:versionName="1.2"`.
- **Debug APK (`assembleDebug`):** `app/build/outputs/apk/debug/app-debug.apk` (`20.32 MB` / `21,306,566 bytes`).
- **Release APK (`assembleRelease` with R8 optimization):** `app/build/outputs/apk/release/app-release-unsigned.apk` (`2.70 MB` / `2,828,380 bytes`).
- **R8 Mapping Artifact (`retrace` support):** `app/build/outputs/mapping/release/mapping.txt` (`36.17 MB` / `37,922,289 bytes`, non-empty).
- **Strict Offline & Zero-Permission Audit (XML-parsed Source + All 8 Merged/Packaged Debug, Release, & UnitTest Manifests — 9 manifests total):** **PASS — 0 `INTERNET`, 0 `RECORD_AUDIO`, 0 `MANAGE_EXTERNAL_STORAGE`, 0 `WRITE_EXTERNAL_STORAGE`**; `FileProvider` confirmed `exported="false"`, `grantUriPermissions="true"`, `authorities="com.cangkir.bitperfectplayer.fileprovider"`.

---

## 33. `v1.2` Real-Device Logcat & Video Polish & Final Pre-Merge Verification (`D11.11`)

Addressed the 3 minor findings observed in real-device POCO F4 video testing and `logcat-v1.2.txt`, audited all repository documentation, and passed full clean verification prior to merging `feat/v1.2` into `main`:

### Delivered Surgical Fixes
1. **`QueueScreen` Initial Scroll Flicker & Entry/Exit Frame Cost ([`BitPerfectAppNav.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/ui/BitPerfectAppNav.kt), [`QueueScreen.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/ui/QueueScreen.kt), [`UiComponents.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/ui/UiComponents.kt)):**
   - Replaced Navigation Compose's 700 ms default crossfade on `NavHost` with crisp `120 ms` / `90 ms` fades and set zero-overlap `EnterTransition.None` / `ExitTransition.None` on `Routes.QUEUE` so `LibraryScreen`'s lower-positioned rows never bleed through `QueueScreen` during `bottomBar` hide/show and both full `LazyColumn` screens never re-layout simultaneously on open/close.
   - Initialized `rememberLazyListState(initialFirstVisibleItemIndex = ...)` directly (`0` when `currentIndex <= 3`, or `(currentIndex - 1)` when deeper in a long queue), removed the `O(N)` `entryIds.map { it as Any }` copy, memoized `popBack`, `dragDistanceProvider`, row subtitle/contentDescription strings, and `TrackArtworkThumbnail` `RoundedCornerShape`, passed stable primitives & shared callbacks to `QueueTrackRow`, and skipped per-row `graphicsLayer` RenderNodes on stationary rows while preserving smooth 120 Hz drag-and-drop behavior.
2. **Deferred Decoder + `AudioTrack` Rebuild on Route Change While Paused ([`RouteChangeReconfigurePolicy.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/audio/RouteChangeReconfigurePolicy.kt), [`AudioOutputEngine.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/audio/AudioOutputEngine.kt)):**
   - Extracted pure-Kotlin `RouteChangeReconfigurePolicy` (`0` `android.*` / `androidx.*` imports) and added `pendingRouteReconfigureOnPlay` in `AudioOutputEngine`.
   - When an `AUTO_PAUSE` route/device/noisy event (such as virtual screen-recording sinks) arrives while playback is already paused, `AudioOutputEngine` still clears stale USB preferred mixer attributes, updates 6-stage diagnostics, and preserves Safe Mode auto-pause notices without tearing down and immediately re-instantiating `MediaCodec` + `AudioTrack` (`mId: 0 -> 1 -> 2`). On the next `play()`, the engine reloads once on the current route while preserving paused `seekTo()` and post-paused Live Turntable scrubbing.
   - Added pure-Kotlin [`RouteChangeReconfigurePolicyTest.kt`](../app/src/test/java/com/cangkir/bitperfectplayer/audio/RouteChangeReconfigurePolicyTest.kt) (`6 / 6` tests).
3. **ART-JIT-Lean `SettingsAudioEngineSection` ([`SettingsAudioSection.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/ui/settings/SettingsAudioSection.kt), [`SettingsScreen.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/ui/settings/SettingsScreen.kt)):**
   - Grouped parameters into `SettingsAudioEngineUiState` and memoized `SettingsAudioEngineCallbacks`, and decomposed the monolithic 255-line card lambda into 6 compact `private @Composable` helpers (`SettingsGaplessPlaybackRow`, `SettingsReplayGainSection`, `SettingsReplayGainModeSelector`, `SettingsReplayGainActiveControls`, `SettingsSleepTimerRow`, `SettingsDiagnosticsShortcutRow`) with zero UI, copy, or touch-target changes.

### Final Pre-Merge Clean Verification (`./gradlew clean ktlintCheck detekt testDebugUnitTest lintDebug` + `assembleDebug assembleRelease`)
- **Kotlin Formatting (`ktlintCheck`):** **PASS — 0 violations**.
- **Static Analysis (`detekt`):** **PASS — 0 issues** (0 baseline files).
- **Automated JVM Unit + Room Migration + Robolectric Compose UI Tests (`testDebugUnitTest`):** **254 / 254 passed** across **40 test suites** (`0` failed, `0` errors, `0` skipped, `0` `@Ignore`, `0` `TODO()`).
- **Compiler, KSP, & R8 Warnings:** **0** Kotlin compiler warnings, **0** KSP warnings, **0** R8 warnings.
- **Android Lint (`lintDebug` & `lintVitalRelease`):** **0 errors**, **0 total warnings**.
- **Pure-Kotlin Domain Boundary (`RouteChangeReconfigurePolicy.kt`):** **PASS — 0 `android.*` / `androidx.*` imports**.
- **Version Integrity:** Merged Release manifest confirmed `android:versionCode="3"` and `android:versionName="1.2"`.
- **Debug APK (`assembleDebug`):** `app/build/outputs/apk/debug/app-debug.apk` (`20.34 MB` / `21,322,950 bytes`).
- **Unsigned Release APK (`assembleRelease` with R8 optimization):** `app/build/outputs/apk/release/app-release-unsigned.apk` (`2.70 MB` / `2,828,380 bytes`).
- **R8 Mapping Artifact (`retrace` support):** `app/build/outputs/mapping/release/mapping.txt` (`36.20 MB` / `37,958,618 bytes`).
- **Strict Offline & Zero-Permission Audit (XML-parsed Source + All 8 Merged/Packaged Debug, Release, & UnitTest Manifests — 9 manifests total):** **PASS — 0 `INTERNET`, 0 `RECORD_AUDIO`, 0 `MANAGE_EXTERNAL_STORAGE`, 0 `WRITE_EXTERNAL_STORAGE`**; `FileProvider` confirmed `exported="false"`, `grantUriPermissions="true"`, `authorities="com.cangkir.bitperfectplayer.fileprovider"`.

---

## 34. `v1.3` Monochrome Studio Theme, Monochrome App Icon, & Two-Layer Dynamic Cover Theme (`D12.1`–`D12.2`)

### Delivered Features & Visual Overhaul
1. **Monochrome Studio Default Theme ([`Color.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/ui/theme/Color.kt), [`Theme.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/ui/theme/Theme.kt), [`SpectrumVisualDsp.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/audio/SpectrumVisualDsp.kt), & App-Wide UI Audit):**
   - Shifted the entire default dark palette from legacy slate-blue (`IcePrimary` / `#7BA7D9`) to **Monochrome Studio**: matte-obsidian surfaces (`#0E0E10`, `#161619`, `#1E1E22`, `#26262C`), warm off-white ivory accent (`StudioIvoryPrimary = #F2F2F0`, `OnStudioIvoryPrimary = #111113`), brushed silver secondary (`StudioSilverSecondary = #B8B8BE`), and distinct muted dark gray inactive states (`#2A2A30` / `#7C7C84`).
   - Enforced high-contrast active (`primary` fill + `onPrimary` obsidian ink) vs inactive states across navigation bars/rails, library tabs, primary Play/Pause & Play All buttons, sliders, `studioSwitchColors()` toggles, and Settings segmented pills, while eliminating every hardcoded old blue color reference across 17+ UI files and updating `AlbumPaletteExtractor.FALLBACK_STUDIO_PALETTE` / `SpectrumColorMapper.STUDIO` to brushed silver/ivory.
   - Preserved all vivid semantic status indicators (`EmeraldSafe` green, `AmberIndicated` amber, `CoralWarning` red) across [`DiagnosticsScreen.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/ui/DiagnosticsScreen.kt), audio safety badges, and destructive actions.
2. **Monochrome Studio App Logo & Legacy WebP Mipmaps ([`ic_launcher_background.xml`](../app/src/main/res/drawable/ic_launcher_background.xml), [`ic_launcher_foreground.xml`](../app/src/main/res/drawable/ic_launcher_foreground.xml), [`ic_launcher_monochrome.xml`](../app/src/main/res/drawable/ic_launcher_monochrome.xml), `mipmap-{mdpi..xxxhdpi}/ic_launcher*.webp`):**
   - Updated the adaptive foreground symmetric PCM waveform mark (`#A8A8B0` outer bars, `#D4D4D6` inner bars, `#F2F2F0` ivory center peak, `#4A4A52` precision ring) over a `#18181B -> #0E0E10` obsidian background, preserved Android 13+ themed icon geometry (`ic_launcher_monochrome.xml`), and regenerated all 10 lossless `.webp` mipmaps (`mdpi` through `xxxhdpi`, square and round) with zero lingering blue pixels.
3. **Pure-Kotlin Cover Color Extractor & Two-Layer Dynamic Cover Theme ([`CoverThemePaletteGenerator.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/ui/theme/CoverThemePaletteGenerator.kt), [`CoverThemeColorCache.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/ui/theme/CoverThemeColorCache.kt), [`MainViewModel.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/ui/MainViewModel.kt), [`MainActivity.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/MainActivity.kt), [`LibraryScreen.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/ui/library/LibraryScreen.kt), [`SettingsDisplayAndLibrarySection.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/ui/settings/SettingsDisplayAndLibrarySection.kt), [`LocalBackupCodec.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/data/LocalBackupCodec.kt)):**
   - Implemented pure-Kotlin `CoverThemePaletteGenerator` (`0` `android.*`/`androidx.*` imports) with 36-bucket quantized hue extraction, monochrome/grayscale rejection (`null` fallback to Monochrome Studio), and programmatic WCAG 2.1 contrast clamping (`>= 4.5:1` interactive accents/containers/variants, `>= 7.0:1` primary text on dark canvas, `background luminance < 0.05`, restrained studio primary luminance `<= 0.65`).
   - Built `CoverThemeColorCache` (`Dispatchers.Default`, `<= 32x32` software pixel sampling, `128`-entry `LruCache`, safe `HARDWARE` bitmap copy handling).
   - Added opt-in **`Dynamic Cover Theme`** toggle (default `false` / OFF) in Settings -> Display, DataStore persistence, and backward-compatible `LocalBackupCodec` `.json` export/import, driving both the **Global Layer** (`300 ms` animated theme tracking the active playing cover) and the **Local Album Layer** (`AlbumDetailDynamicThemeWrapper` isolating opened Album Details without recomposing unrelated global UI).
4. **Version Metadata Bump ([`app/build.gradle.kts`](../app/build.gradle.kts), [`SettingsScreen.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/ui/settings/SettingsScreen.kt)):**
   - Updated to **`versionCode = 4`, `versionName = "1.3"`**.

### Final Clean `v1.3` Verification Evidence (`/home/endricho/.local/bin/gradlew-wsl clean ktlintCheck detekt testDebugUnitTest lintDebug assembleDebug assembleRelease`)
- **Kotlin Formatting (`ktlintCheck`):** **PASS — 0 violations** across `main`, `test`, and Gradle scripts.
- **Static Analysis (`detekt`):** **PASS — 0 issues** (0 baseline suppressions).
- **Automated JVM Unit + Room Migration + Robolectric Compose UI Tests (`testDebugUnitTest`):** **263 / 263 passed** across **41 test suites** (`0` failed, `0` errors, `0` skipped, `0` `@Ignore`), including:
  - [`CoverThemePaletteGeneratorTest.kt`](../app/src/test/java/com/cangkir/bitperfectplayer/ui/theme/CoverThemePaletteGeneratorTest.kt): `5 / 5` (monochrome/grayscale/empty `null` fallback, extreme white/neon-yellow/neon-lime/cyan/magenta/crimson/deep-navy covers, and full 0°–355° hue sweep WCAG verification).
  - [`PersistenceAndPreferencesTest.kt`](../app/src/test/java/com/cangkir/bitperfectplayer/data/PersistenceAndPreferencesTest.kt): `4 / 4` (verifying `dynamicCoverThemeEnabled = false` fresh default and DataStore round-trip).
  - [`LocalBackupCodecTest.kt`](../app/src/test/java/com/cangkir/bitperfectplayer/data/LocalBackupCodecTest.kt): `5 / 5` (+1 verifying `dynamicCoverThemeEnabled` JSON round-trip and legacy v1.0–v1.2 fallback to `false`).
  - [`BitPerfectUiBehaviorTest.kt`](../app/src/test/java/com/cangkir/bitperfectplayer/ui/BitPerfectUiBehaviorTest.kt): `33 / 33` (+3 verifying Settings `Dynamic Cover Theme` toggle default OFF & clicks, nested Album dynamic theme scope isolation + `300 ms` Monochrome/Global transitions, and `LibraryScreen` Album Detail dynamic scope open & Back exit).
- **Compiler & KSP Warnings:** **0** Kotlin/KSP warnings across Debug, Release, and UnitTest compilations.
- **Android Lint (`lintDebug` & `lintVitalRelease`):** **0 errors**, **0 warnings across all `/app/src/` files** (remaining 14 SARIF notices are strictly pre-existing `gradle/libs.versions.toml` `GradleDependency` and cached `lifecycle-runtime` `ObsoleteLintCustomCheck` advisories).
- **Pure-Kotlin Boundary & Blue-Removal Audits:** **PASS — 0 `android.*`/`androidx.*` imports in `CoverThemePaletteGenerator.kt`; 0 remaining `IcePrimary` / `#7BA7D9` references in `app/src/main/`; all 10 lossless `.webp` mipmaps verified RGBA with ivory center peak and zero blue dominance.**
- **Merged Manifest & Offline Privacy Audit (Source + All 8 Generated Debug/Release/UnitTest Manifests — 9 manifests total):** **PASS — 0 `INTERNET`, 0 `RECORD_AUDIO`, 0 `MANAGE_EXTERNAL_STORAGE`, 0 `WRITE_EXTERNAL_STORAGE`**; Release manifest verified `versionCode="4"` and `versionName="1.3"`.
- **Debug APK (`assembleDebug`):** `app/build/outputs/apk/debug/app-debug.apk` (`21,357,802 bytes` / `20.37 MB`).
- **Unsigned Release APK (`assembleRelease` with R8):** `app/build/outputs/apk/release/app-release-unsigned.apk` (`2,863,240 bytes` / `2.73 MB`).
- **R8 Mapping Artifact (`retrace` support):** `app/build/outputs/mapping/release/mapping.txt` (`37,855,959 bytes` / `36.10 MB`).

### Physical Hardware Verification Pending (Not Executed in Software-Only Environment)
- [ ] **Monochrome Studio Launcher Icon & Android 13+ Themed Icon:** Install `v1.3` APK on physical POCO F4 (HyperOS / Android 14) and confirm the obsidian/silver/ivory app icon renders cleanly on home screen, app drawer, Settings app list, and Material You themed icon mode without legacy blue tints.
- [ ] **Two-Layer Dynamic Cover Theme & 120 Hz Transitions:** Enable Settings -> Display -> `Dynamic Cover Theme`, play tracks with bright white, neon yellow, crimson, deep black, and B&W artwork while inspecting Global theme transitions (`300 ms`), verify `MiniPlayerBar` & `BottomNav` active/inactive contrast, open an Album Detail on the `Albums` tab to verify isolated Local Album cover tinting and clean Back restoration, and confirm smooth 120 Hz radial spectrum + vinyl rotation.
- [ ] **Audio Safety Invariant Regression Check on Device:** Verify Bit-Perfect Opt-In USB-C DAC playback (`MIXER_BEHAVIOR_BIT_PERFECT` or `DEFAULT`), Hearing Safety gate, plug/unplug auto-pause, and zero DSP/ReplayGain/volume scaling remain completely unchanged while `Dynamic Cover Theme` is active.

---

## 35. `v1.3` Multi-Artwork Header Parser, Studio Artist Detail Redesign, `Disc`/`Back Cover` Wiring, & `MiniPlayerBar` Queue (`D12.3`–`D12.6`)

### Delivered Features
1. **Pure-Kotlin On-Demand Embedded Multi-Artwork Header Parser ([`EmbeddedArtworkParser.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/library/EmbeddedArtworkParser.kt), [`AlbumArtworkLoader.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/library/AlbumArtworkLoader.kt)):**
   - Parses `FLAC` `METADATA_BLOCK_PICTURE` (`blockType == 6`, plus optional leading ID3v2 prefix) and `MP3` `ID3` v2.3/v2.4 `APIC` frames (including extended headers, synchsafe/fallback sizes, v2.4 Data Length Indicator, tag/frame unsynchronization, and UTF-16 `0x00 0x00` description terminators) strictly on demand off-main on `Dispatchers.IO` (never during bulk library scans) with a strict `8 MB` cap per picture block and graceful `null` handling on WAV/M4A/OGG or malformed streams.
   - Extracts prioritized `ARTIST` (`8 -> 7 -> 10 -> 19`), `DISC` (`6`), `BACK_COVER` (`4`), and `FRONT_COVER` (`3`) slots into a dedicated bounded `8 MB` `slotMemoryCache` + `slotMissingKeys` negative cache.
2. **Release Year Metadata & Room `v4 -> v5` Non-Destructive Migration ([`PlaybackTrack.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/audio/PlaybackTrack.kt), [`BitPerfectDatabase.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/data/BitPerfectDatabase.kt), [`LocalEntities.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/data/LocalEntities.kt), [`LocalDaos.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/data/LocalDaos.kt), [`AudioDecoderModels.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/audio/AudioDecoderModels.kt), [`AudioMetadataReader.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/audio/AudioMetadataReader.kt), [`LocalLibraryRepository.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/library/LocalLibraryRepository.kt)):**
   - Added `year: Int = 0` (`ALTER TABLE cached_tracks ADD COLUMN year INTEGER NOT NULL DEFAULT 0` via `MIGRATION_4_5`), populated from `MediaStore.Audio.Media.YEAR`, FLAC `VORBIS_COMMENT` (`DATE`/`YEAR`/`ORIGINALDATE`), and `MediaMetadataRetriever` fallback, with in-place cursor-only `backfillTrackYears` for existing v4 cached rows.
3. **Studio Artist Detail Redesign, Custom Artist Photo Picker, & Contextual Local Dynamic Theme ([`ArtistDetailHelper.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/library/ArtistDetailHelper.kt), [`ArtistPhotoStore.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/library/ArtistPhotoStore.kt), [`ArtistDetailView.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/ui/library/ArtistDetailView.kt), [`LibraryScreen.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/ui/library/LibraryScreen.kt), [`CoverThemeColorCache.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/ui/theme/CoverThemeColorCache.kt), [`PlaybackController.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/audio/PlaybackController.kt)):**
   - Single-`LazyColumn` studio Hero layout with priority Hero artwork (offline custom photo via zero-permission Android Photo Picker `PickVisualMedia` + `GetContent` fallback with pure-Kotlin JPEG EXIF orientation correction -> embedded `ARTIST` -> newest album cover `ContentScale.Fit` -> monochrome monogram), smooth bottom vertical scrim, overlaid status-bar-padded Back & 3-dot overflow menu (`Set Artist Photo`, `Reset Artist Photo`, `Play Next`, `Add All to Queue`), total stats (`N albums · M tracks · Xh Y min`), `Play All` & `Shuffle` (`playQueueShuffled`), newest-release-year-first horizontal `Albums` carousel (`ContentScale.Fit` + dynamic cover-tinted WCAG-clamped bottom info block), ruled `TRACKS` section, isolated Contextual Artist Dynamic Theme (refreshing immediately on custom photo set/reset while keeping `MiniPlayerBar` on Global theme), and parent backstack (`Artists -> Artist Detail -> Album Detail -> Back -> Artist Detail -> Back -> Artists`).
4. **Wired `Disc` & `Back Cover` Artwork (Opsi 1) ([`VinylDiscPlayer.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/ui/VinylDiscPlayer.kt), [`NowPlayingControls.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/ui/nowplaying/NowPlayingControls.kt), [`LibraryGroupViews.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/ui/library/LibraryGroupViews.kt)):**
   - `VinylDiscPlayer` (`VinylCircularArtwork`) lazily loads embedded `DISC` art (falling back cleanly to Front Cover while preserving Option B circular label framing and 120 Hz zero-decode animation frames).
   - `SwipeableSquareArtwork` and `AlbumDetailArtworkHeader` lazily probe embedded `BACK_COVER`: render zero extra UI when absent, or a `>= 48.dp` Front/Back flip toggle displaying full uncropped `ContentScale.Fit` Back Cover that auto-resets on track/album change.
5. **Moved Passive Queue Icon to `MiniPlayerBar` ([`UiComponents.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/ui/UiComponents.kt), [`LibraryHeaderControls.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/ui/library/LibraryHeaderControls.kt), [`PlaylistsScreen.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/ui/playlists/PlaylistsScreen.kt), [`BitPerfectAppNav.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/ui/BitPerfectAppNav.kt)):**
   - Removed Queue icon from Library/Detail/Playlists top app bars and placed the passive `>= 48.dp` Queue button on `MiniPlayerBar` alongside Play/Pause and Next (keeping Queue intact on `NowPlayingScreen`).

### Clean Software Verification Evidence (`./gradlew clean ktlintCheck detekt testDebugUnitTest lintDebug` + `assembleDebug assembleRelease`)
- **Kotlin Formatting (`ktlintCheck`):** **PASS — 0 violations**.
- **Static Analysis (`detekt`):** **PASS — 0 issues** (0 baseline suppressions).
- **Automated JVM Unit + Room v5 Migration + Robolectric Compose UI Tests (`testDebugUnitTest`):** **283 / 283 passed** across **44 test suites** (`0` failed, `0` errors, `0` skipped, `0` `@Ignore`, `0` `TODO()`), including:
  - [`EmbeddedArtworkParserTest.kt`](../app/src/test/java/com/cangkir/bitperfectplayer/library/EmbeddedArtworkParserTest.kt): `7 / 7`
  - [`ArtistDetailHelperTest.kt`](../app/src/test/java/com/cangkir/bitperfectplayer/library/ArtistDetailHelperTest.kt): `5 / 5`
  - [`ArtistPhotoStoreTest.kt`](../app/src/test/java/com/cangkir/bitperfectplayer/library/ArtistPhotoStoreTest.kt): `2 / 2` (offline save/downscale/reload/reset + pure-Kotlin little/big-endian JPEG EXIF orientation parser)
  - [`BitPerfectDatabaseMigrationTest.kt`](../app/src/test/java/com/cangkir/bitperfectplayer/data/BitPerfectDatabaseMigrationTest.kt): `2 / 2` (full v1 -> v5 and v2/v3 -> v5 migration + `backfillTrackYears`)
  - [`BitPerfectUiBehaviorTest.kt`](../app/src/test/java/com/cangkir/bitperfectplayer/ui/BitPerfectUiBehaviorTest.kt): `39 / 39` (updated Library/Playlists top-bar Queue omission tests + 6 new tests for `MiniPlayerBar` passive Queue icon, `ArtistDetailView` hero/stats/albums/shuffle/overflow, `Artists -> Artist -> Album -> Back` backstack, Back Cover hidden when absent, Back Cover flip + track-change auto-reset when present, and Vinyl Disc embedded `DISC` preference)
- **Compiler, KSP, & R8 Warnings:** **0** Kotlin warnings, **0** KSP warnings, **0** R8 warnings.
- **Android Lint (`lintDebug` & `lintVitalRelease`):** **0 errors**, **0 warnings across all `app/src/` Kotlin, manifest, and resource files** (6 remaining SARIF advisories are strictly upstream dependency version notices in `gradle/libs.versions.toml` / AAR).
- **Pure-Kotlin Domain Boundary:** **PASS — 0 `android.*` / `androidx.*` imports** in `EmbeddedArtworkParser.kt` and `ArtistDetailHelper.kt`.
- **Merged Manifest & Offline Privacy Audit (Source + All 8 Generated Debug/Release/UnitTest Manifests — 9 manifests total):** **PASS — 0 `INTERNET`, 0 `RECORD_AUDIO`, 0 `MANAGE_EXTERNAL_STORAGE`, 0 `WRITE_EXTERNAL_STORAGE`, 0 `READ_MEDIA_IMAGES`**; Release manifest verified `versionCode="4"`, `versionName="1.3"`, and identical minimal permission set.
- **Debug APK (`assembleDebug`):** `app/build/outputs/apk/debug/app-debug.apk` (`21,472,490 bytes` / `20.48 MB`).
- **Unsigned Release APK (`assembleRelease` with R8):** `app/build/outputs/apk/release/app-release-unsigned.apk` (`2,928,784 bytes` / `2.79 MB`).
- **R8 Mapping Artifact (`retrace` support):** `app/build/outputs/mapping/release/mapping.txt` (`37,979,120 bytes` / `36.22 MB`).

### Additional Physical Hardware Verification Pending (Not Executed in Software-Only Environment)
- [ ] **Room `v4 -> v5` In-Place Upgrade & Release Year Backfill:** Upgrade over an existing `v1.2` install on POCO F4 and confirm zero data loss (`lyrics`, `favorites`, `playlists`, `track_stats`) and automatic album release-year population + newest-first ordering inside Artist Detail.
- [ ] **Artist Detail Hero, Custom Photo Picker, & Contextual Dynamic Theme:** Open Artist Detail on POCO F4, test `Set Artist Photo` via system Android Photo Picker (confirming zero runtime photo permission prompt and correct portrait EXIF orientation), observe immediate Hero + local Dynamic Theme refresh while `MiniPlayerBar` retains the playing track's Global theme, test `Reset Artist Photo`, and confirm `Artists -> Artist Detail -> Album Detail -> Back -> Artist Detail -> Back -> Artists`.
- [ ] **Multi-Artwork FLAC/MP3 Tags (`ARTIST`, `DISC`, `BACK_COVER`) & `MiniPlayerBar` Queue Icon:** Play FLAC/MP3 tracks containing embedded types `8`/`6`/`4` and confirm crisp circular `DISC` art on the 120 Hz spinning Vinyl Disc, subtle Front/Back toggle on Square Cover & Album Detail (and zero toggle UI on tracks without type `4`), and smooth passive Queue opening directly from `MiniPlayerBar`.

---

## 36. `v1.3` `MediaSession` Notification & Lock-Screen Polish, Documentation Sync, & Final Feature-Complete Verification (`D12.7`–`D12.8`)

### Delivered Features
1. **Custom Monochrome Status Bar & Notification Small Icon ([`ic_stat_bitperfect.xml`](../app/src/main/res/drawable/ic_stat_bitperfect.xml), [`PlaybackForegroundService.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/audio/PlaybackForegroundService.kt)):**
   - Replaced legacy `ic_notif_music.xml` (deleted so zero unused resources remain) with `ic_stat_bitperfect.xml` (`24dp`, pure `#FFFFFFFF` precision signal ring + 5 quantized PCM bars on transparent background matching `ic_launcher_monochrome.xml`).
2. **Automatic Square Dark Letterboxing for Non-Square Media Covers ([`NotificationArtworkHelper.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/library/NotificationArtworkHelper.kt), [`NotificationArtworkFramer.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/library/NotificationArtworkFramer.kt), [`PlaybackForegroundService.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/audio/PlaybackForegroundService.kt)):**
   - Pure-Kotlin `NotificationArtworkHelper` (`0` `android.*`/`androidx.*` imports) and Android `NotificationArtworkFramer` center non-square (`width != height`) album covers proportionally onto a matte-obsidian (`#111114`) square canvas capped at `<= 512x512` off-main on `Dispatchers.Default` (never mutating/recycling `AlbumArtworkLoader` bitmaps and preventing Binder `TransactionTooLargeException`), while square `1:1` covers pass through without dark borders.
3. **Concise Locale-Independent Audiophile Media Notification Subtitle ([`NotificationSubtitleFormatter.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/audio/NotificationSubtitleFormatter.kt), [`AudioDiagnosticMapper.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/audio/AudioDiagnosticMapper.kt), [`PlaybackForegroundService.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/audio/PlaybackForegroundService.kt)):**
   - Formats `Artist · Codec Bit-Depth · Sample Rate` (e.g., `Green Day · FLAC 16-bit · 44.1 kHz`, `The Beatles · FLAC 24-bit · 96 kHz`, `Artist Name · MP3 · 44.1 kHz`, graceful fallback to `artist` alone) across `METADATA_KEY_ARTIST`, `METADATA_KEY_DISPLAY_SUBTITLE`, and `Notification.setContentText`.
   - Enriches missing scanned rate/bit-depth strictly from `AudioDiagnosticSnapshot.sourceSampleRateHz` / `sourceBitDepth` when `engineState.activeTrackUri == activeTrack.uriString`, ensuring lossy MP3/AAC/OGG tracks never display decoded PCM bit depth and rapid track transitions never leak stale diagnostics.
4. **`MediaSession` Custom Actions (`Favorite` & `Shuffle`) with Bidirectional Live Sync ([`MediaSessionCustomActions.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/audio/MediaSessionCustomActions.kt), [`MediaSessionUpdatePolicy.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/audio/MediaSessionUpdatePolicy.kt), [`PlaybackForegroundService.kt`](../app/src/main/java/com/cangkir/bitperfectplayer/audio/PlaybackForegroundService.kt), `ic_notif_favorite_{filled,outline}.xml`, `ic_notif_shuffle_{on,off}.xml`):**
   - Registers `PlaybackState.CustomAction` entries (`ACTION_TOGGLE_FAVORITE`, `ACTION_TOGGLE_SHUFFLE`) and matching notification actions with distinct pure-monochrome `#FFFFFFFF` active/inactive icons, dispatching directly via `libraryRepository.toggleFavorite(...)` and `playbackController.toggleShuffle()` so controls function reliably even when the UI is backgrounded.
   - Observes `libraryRepository.librarySnapshot` (`favoriteUris` / `favoriteFingerprints`) and `playbackController.state` (`queue.shuffleEnabled`) for instant bidirectional sync with in-app UI while keeping 20 Hz engine ticks at **0** redundant Binder IPC calls and preserving Android 17 (`API 37`) `ForegroundPlaybackGate` compliance untouched.
   - **OEM / Android SystemUI Transparency Note:** Android SystemUI governs final lock-screen and shade media card rendering. Standard AOSP / Pixel / Custom ROM SystemUI exposes up to 5 controls including `Favorite` and `Shuffle`, whereas certain OEM skins (including some HyperOS versions) may cap visible notification media buttons to the 3 primary transport controls (`Previous`, `Play/Pause`, `Next`).
5. **Full `v1.3` Documentation Alignment (`README.md`, `SPEC.md`, `DECISIONS.md`, `docs/HANDOFF_REPORT.md`):**
   - Updated all documentation covering Prompts 1–3 (`versionName = "1.3"`, `versionCode = 4`, Room `v5`, Monochrome Studio default theme & launcher icon, opt-in 3-level Dynamic Cover Theme, FLAC/MP3 embedded multi-artwork parser, Studio Artist Detail redesign + zero-permission Photo Picker, `Disc`/`Back Cover` wiring, passive `MiniPlayerBar` Queue, `MediaSession` polish + OEM SystemUI note, and `0` `READ_MEDIA_IMAGES`).

### Final Clean `v1.3` Software Verification Evidence (`./gradlew clean ktlintCheck detekt testDebugUnitTest lintDebug` + `assembleDebug assembleRelease`)
- **Kotlin Formatting (`ktlintCheck`):** **PASS — 0 violations**.
- **Static Analysis (`detekt`):** **PASS — 0 issues** (0 baseline suppressions).
- **Automated JVM Unit + Room v5 Migration + Robolectric Skia + Compose UI Tests (`testDebugUnitTest`):** **305 / 305 passed** across **48 test suites** (`0` failed, `0` errors, `0` skipped, `0` `@Ignore`, `0` `TODO()`), including newly added/expanded suites:
  - [`NotificationSubtitleFormatterTest.kt`](../app/src/test/java/com/cangkir/bitperfectplayer/audio/NotificationSubtitleFormatterTest.kt): `7 / 7` (FLAC 16/24-bit, WAV, MP3/AAC/Opus/Vorbis lossy bit-depth omission, fallbacks, German comma-locale invariance, runtime enrichment)
  - [`NotificationArtworkHelperTest.kt`](../app/src/test/java/com/cangkir/bitperfectplayer/library/NotificationArtworkHelperTest.kt): `4 / 4` (square pass-through & `512 px` cap, landscape letterbox, portrait pillarbox, invalid dims)
  - [`NotificationArtworkFramerTest.kt`](../app/src/test/java/com/cangkir/bitperfectplayer/library/NotificationArtworkFramerTest.kt): `6 / 6` (Robolectric `@GraphicsMode(NATIVE)` Skia pixel-accurate `#111114` letterbox/pillarbox margin & uncropped center verification, square borderless pass-through, `> 512 px` downscale, null/recycled safety, monochrome vector resource loading)
  - [`MediaSessionCustomActionsTest.kt`](../app/src/test/java/com/cangkir/bitperfectplayer/audio/MediaSessionCustomActionsTest.kt): `3 / 3` (stateful Favorite/Shuffle icon/label specs, no-active-track handling, command resolution)
  - [`MediaSessionUpdatePolicyTest.kt`](../app/src/test/java/com/cangkir/bitperfectplayer/audio/MediaSessionUpdatePolicyTest.kt): `5 / 5` (+1 verifying immediate updates on subtitle, Favorite, and Shuffle toggles with `0` redundant 20 Hz ticks)
  - [`AudioDiagnosticMapperTest.kt`](../app/src/test/java/com/cangkir/bitperfectplayer/audio/AudioDiagnosticMapperTest.kt): `7 / 7` (+1 verifying source-only rate/bit-depth propagation for lossless vs lossy vs idle)
- **Compiler, KSP, & R8 Warnings:** **0** Kotlin compiler warnings, **0** KSP warnings, **0** R8 warnings.
- **Android Lint (`lintDebug` & `lintVitalRelease`):** **0 errors**, **0 warnings across all `app/src/` Kotlin, manifest, and resource files** (6 remaining SARIF advisories are strictly upstream dependency version notices in `gradle/libs.versions.toml` / AAR).
- **Pure-Kotlin Domain Boundary:** **PASS — 0 `android.*` / `androidx.*` imports** in `NotificationSubtitleFormatter.kt`, `NotificationArtworkHelper.kt`, `MediaSessionCustomActions.kt`, `MediaSessionUpdatePolicy.kt`, and `AudioDiagnosticMapper.kt`.
- **Merged Manifest & Offline Privacy Audit (Source + All 8 Generated Debug/Release/UnitTest Manifests — 9 manifests total):** **PASS — 0 `INTERNET`, 0 `RECORD_AUDIO`, 0 `MANAGE_EXTERNAL_STORAGE`, 0 `WRITE_EXTERNAL_STORAGE`, 0 `READ_MEDIA_IMAGES`**; Release merged manifest verified `android:versionCode="4"` and `android:versionName="1.3"`.
- **Debug APK (`assembleDebug`):** `app/build/outputs/apk/debug/app-debug.apk` (`21,475,630 bytes` / `20.48 MB`).
- **Unsigned Release APK (`assembleRelease` with R8):** `app/build/outputs/apk/release/app-release-unsigned.apk` (`2,947,900 bytes` / `2.81 MB`).
- **R8 Mapping Artifact (`retrace` support):** `app/build/outputs/mapping/release/mapping.txt` (`38,033,582 bytes` / `36.27 MB`).

### Additional Physical Hardware Verification Pending (Not Executed in Software-Only Environment)
- [ ] **Notification Status Badge & Audiophile Subtitle:** Play 16-bit/44.1 kHz FLAC, 24-bit/96 kHz FLAC, and MP3 tracks on physical POCO F4 (HyperOS / Android 14) and confirm the crisp pure-white `ic_stat_bitperfect` waveform badge in the status bar and accurate `Artist · Codec Bit-Depth · Sample Rate` subtitles in both the notification shade and lock screen.
- [ ] **Square Dark Letterboxing vs Square Pass-Through:** Play a non-square (`16:9` or portrait) album cover and a `1:1` square cover; confirm the non-square cover displays completely uncropped inside dark `#111114` letterbox/pillarbox margins on the lock-screen/shade media card while the `1:1` cover fills edge-to-edge with zero borders.
- [ ] **`MediaSession` Custom Actions & Background Sync (SystemUI / OEM Dependent):** Verify `Favorite` and `Shuffle` custom actions on AOSP/Custom ROM SystemUI or Bluetooth/Wear controllers (and observe whether HyperOS caps visible shade buttons to the 3 primary transport buttons), confirming bidirectional icon/state sync when toggled in-app vs from the notification/session while backgrounded.

