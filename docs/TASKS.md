# TASKS: `BitPerfectPlayer` MVP (`com.cangkir.bitperfectplayer`)

Execute unchecked `[ ]` items sequentially from top to bottom following `AGENTS.md`, `SPEC.md`, `.agents/rules/*.md`, and the workflow in `.agents/skills/overnight-task-loop/SKILL.md`. Mark `[x]`, commit, and push after each item passes verification.

---

## Milestone 0: Build Environment Verification & Project Setup

- [x] **T0.1 — Audit Build Environment & Verify Fresh Project Baseline**
  - Inspect `gradle/gradle-daemon-jvm.properties`, `gradle.properties`, `gradle/libs.versions.toml`, `settings.gradle.kts`, `build.gradle.kts`, `app/build.gradle.kts`, and `app/src/main/keepRules/rules.keep`.
  - Check available JDK(s) and Android SDK environment (`ANDROID_HOME` / `ANDROID_SDK_ROOT`, installed platforms—specifically checking for SDK 37 matching `compileSdk { version = release(37) }`). If `platforms;android-37` is missing and `sdkmanager` is available, attempt to provision it cleanly without modifying `compileSdk`/`targetSdk`. Ensure `local.properties` is never staged or committed.
  - Run `./gradlew testDebugUnitTest` and `./gradlew assembleDebug` on the untouched baseline project.
  - **Pass criteria:** Gradle syncs cleanly and both baseline `ExampleUnitTest` and debug APK build succeed without downgrading `compileSdk`/`targetSdk`. *(Foundational Blocker: If Android SDK 37 or the required Gradle JVM is unavailable and cannot be provisioned cleanly within the 3-attempt circuit breaker limit, document exact diagnostics in `BLOCKED.md` and `docs/HANDOFF_REPORT.md`, commit/push them, and halt cleanly).*

- [x] **T0.2 — Configure Dependencies, Manifest Permissions, and Package Structure**
  - Add permissive-licensed (Apache 2.0 / MIT / BSD) dependencies via `gradle/libs.versions.toml` aligned with the existing AGP/Kotlin versions:
    - Compose Navigation, Material Icons Extended (if needed), Lifecycle ViewModel Compose, Kotlin Coroutines (`kotlinx-coroutines-android`, `kotlinx-coroutines-test`), and `androidx.documentfile:documentfile` for SAF tree traversal.
    - Local persistence: Room (`room-runtime`, `room-ktx`, `room-compiler` via KSP) + DataStore Preferences (`datastore-preferences`). *(Fallback clause: if KSP/Room has an unresolvable plugin incompatibility with the project's AGP/Kotlin/SDK 37 toolchain, document the exact error in `DECISIONS.md` and use Android's built-in `SQLiteOpenHelper` with clean repository/DAO abstractions instead).*
    - Media controls: Prefer platform `android.media.session.MediaSession` + `Notification.MediaStyle` (zero extra dependency on `minSdk = 26`) or offline-clean `androidx.media:media`; never add networking modules.
  - Configure `app/src/main/AndroidManifest.xml` with minimal Scoped Storage & playback permissions (`READ_MEDIA_AUDIO`, `READ_EXTERNAL_STORAGE` with `android:maxSdkVersion="32"`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `POST_NOTIFICATIONS`). **Strictly NO `android.permission.INTERNET` and NO `MANAGE_EXTERNAL_STORAGE`.**
  - Create clean package directories under `app/src/main/java/com/cangkir/bitperfectplayer/`: `audio`, `library`, `lyrics`, `data`, and `ui` (keeping existing `ui/theme/`). Mirror test packages under `app/src/test/java/com/cangkir/bitperfectplayer/`.
  - **Verification:** Run `.agents/skills/android-audio-verify/SKILL.md` steps 3–5 (`assembleDebug` + manifest privacy audit).

---

## Milestone 1: Pure Logic Modules — Fingerprinting, `.lrc` Parsing, & Local Persistence

- [x] **T1.1 — Deterministic Content Fingerprint Generator (SHA-256)**
  - Implement pure-Kotlin `ContentFingerprintGenerator` (zero `android.*` imports) under `app/src/main/java/com/cangkir/bitperfectplayer/lyrics/`:
    - For `size <= 131072` (128 KB): lowercase 64-char hex `SHA-256(entire file bytes)`.
    - For `size > 128 KB`: lowercase 64-char hex `SHA-256(first 64 KB [65536 bytes] + last 64 KB [65536 bytes] + total file size in bytes as 8-byte big-endian unsigned integer)`.
    - Support OOM-safe random-access head/tail reading via a pure-Kotlin seekable interface (plus a 64 KB ring-buffer streaming fallback for non-seekable streams), with an Android `ContentResolver` adapter bridging `ParcelFileDescriptor`/`InputStream`.
  - Write JVM unit tests (`ContentFingerprintGeneratorTest` under `app/src/test/java/com/cangkir/bitperfectplayer/lyrics/`) covering: empty file (`0` bytes), `< 128 KB`, exact `128 KB`, `> 128 KB` (both seekable and streaming paths producing identical hashes), identical content with different filenames producing identical hashes, 1-byte mutation at start/end/size producing distinct hashes, and I/O failure handling.
  - **Verification:** `./gradlew testDebugUnitTest` 100% green.

- [x] **T1.2 — Fault-Tolerant `.lrc` Parser & Active Line Resolver**
  - Implement pure-Kotlin `LrcParser` (zero `android.*` imports) under `app/src/main/java/com/cangkir/bitperfectplayer/lyrics/` supporting:
    - Standard timestamps (`[mm:ss.xx]`, `[mm:ss.xxx]`) and multi-timestamp lines (e.g., `[00:10.00][01:20.50]Chorus`).
    - Metadata tags (`[ti:]`, `[ar:]`, `[al:]`) and millisecond `[offset:]` application.
    - Byte-array decoding for `UTF-8`, `UTF-8 BOM`, `UTF-16LE`, `UTF-16BE`, and `ISO-8859-1` fallback.
    - Crash-proof handling of malformed timestamps, blank lines, and plain untimestamped text (returned cleanly as static lyrics).
  - Implement deterministic `findActiveLineIndex(lines, positionMs)` supporting forward/backward seeks.
  - Write comprehensive JVM unit tests (`LrcParserTest`) covering all formats, encodings, offsets, seeks, and corrupted inputs.
  - **Verification:** `./gradlew testDebugUnitTest` 100% green.

- [x] **T1.3 — Local Persistence Layer (Database & DataStore Preferences)**
  - Implement local database schema under `app/src/main/java/com/cangkir/bitperfectplayer/data/`:
    - `LyricsRecord` / `LyricsEntity` (Primary Key = `contentFingerprint`, raw lyrics text, `isSyncedLrc`, source type `MANUAL`/`LRC_FILE`, filename, file size, duration, `updatedAt`).
    - `TrackHistoryRecord` (recently played tracks with timestamp) and `FavoriteTrackRecord` (keyed by `contentFingerprint`).
    - Full CRUD/DAO operations (upsert, lookup by fingerprint, delete, query recently played, toggle favorite).
  - Implement `UserPreferencesRepository` using DataStore Preferences storing: audio safety mode (default Safe Mode), shuffle/repeat settings, persisted SAF tree URIs, and last playback state (`trackUri`, `contentFingerprint`, `positionMs`).
  - Write JVM unit tests verifying default preferences (Safe Mode enforced as default), model mapping, and input sanitization.
  - **Verification:** `./gradlew testDebugUnitTest assembleDebug` 100% green.

---

## Milestone 2: Playback Domain, Hearing Safety Policy, & Diagnostics Model

- [x] **T2.1 — Playback Queue Manager (`PlaybackQueueManager`)**
  - Implement pure-Kotlin queue logic (zero `android.*` imports) under `app/src/main/java/com/cangkir/bitperfectplayer/audio/`: set queue, enqueue/add, remove, reorder, next/previous, non-destructive `Shuffle` toggle, `Repeat` (`OFF`, `ALL`, `ONE`), and active index restoration.
  - Write JVM unit tests (`PlaybackQueueManagerTest`) covering empty queue, single track, multi-track navigation, shuffle on/off preservation, and all repeat modes.
  - **Verification:** `./gradlew testDebugUnitTest` 100% green.

- [x] **T2.2 — Hearing Safety & Audio Mode State Machine (`AudioSafetyController`)**
  - Implement pure-Kotlin `AudioSafetyController` (zero `android.*` imports) under `app/src/main/java/com/cangkir/bitperfectplayer/audio/`:
    - Always initializes in `SAFE_VOLUME_MODE`.
    - Requesting `BIT_PERFECT_OPT_IN` must emit `REQUIRES_SAFETY_WARNING` whenever `isHardwareVolumeVerified == false`.
    - On `onAudioDeviceConnected()` (new USB device plugged in), never auto-enable Bit-Perfect Mode (remain in or reset to `SAFE_VOLUME_MODE`).
    - On `onBecomingNoisy()`, `onUsbDisconnected()`, or `onOutputRouteChanged()`, immediately emit an `AUTO_PAUSE` command.
  - Write strict JVM unit tests (`AudioSafetyControllerTest`) verifying every safety transition.
  - **Verification:** `./gradlew testDebugUnitTest` 100% green.

- [x] **T2.3 — 6-Stage Audio Diagnostics Model (`AudioDiagnosticMapper`)**
  - Implement pure-Kotlin `AudioDiagnosticSnapshot` and `AudioDiagnosticMapper` (zero `android.*` imports) representing all 6 audio stages with explicit `VerificationLevel` tags (`VERIFIED`, `INDICATED`, `UNVERIFIABLE`).
  - Ensure UI status automatically emits an honest fallback label (e.g., *"Clean Native Path — Bit-Perfect Not Supported by OS/Device"*) whenever negotiated mixer behavior is not `MIXER_BEHAVIOR_BIT_PERFECT`, and always marks internal USB-C DAC firmware / analog output as `UNVERIFIABLE` with the maximum claim notice from `SPEC.md` Section 3.5.
  - Write JVM unit tests (`AudioDiagnosticMapperTest`) covering Bit-Perfect active, clean native fallback, and Safe Mode states.
  - **Verification:** `./gradlew testDebugUnitTest` 100% green.

---

## Milestone 3: Native Android Audio Engine, 5-Format Decoders, & Foreground Service

- [x] **T3.1 — 5-Format Audio Decoders & Metadata Reader**
  - Implement `AudioMetadataReader` extracting title, artist, album, duration, container/codec, sample rate, and bit depth with graceful fallback to filename when tags are missing/corrupted.
  - Implement a direct pure-Kotlin `WavHeaderParser` and `WavPcmDecoder` parsing RIFF/`fmt `/`data` chunks for 16-bit and 24-bit (and 32-bit if present) PCM without truncating 24-bit precision, supporting byte-accurate seeking and lossless 24-bit-to-float32 unpacking helper for API 26–30 compatibility.
  - Implement `MediaCodecAudioDecoder` (`MediaExtractor` + `MediaCodec`) for FLAC (16/24-bit), MP3, M4A/AAC, and OGG (Vorbis/Opus), guarding `ENCODING_PCM_24BIT_PACKED`/`ENCODING_PCM_32BIT` with `Build.VERSION.SDK_INT >= Build.VERSION_CODES.S`, inspecting actual output PCM encoding honestly, and handling corrupted/unsupported files via typed errors without crashing.
  - Write JVM unit tests (`WavHeaderParserTest`) testing valid 16-bit WAV, valid 24-bit WAV, 24-bit-to-float32 conversion accuracy, extra/non-standard RIFF chunks, and truncated/malformed WAV headers.
  - **Verification:** `./gradlew testDebugUnitTest assembleDebug` 100% green.

- [x] **T3.2 — `AudioOutputEngine` (`AudioTrack` + Android 14+ Mixer Attributes)**
  - Implement `AudioOutputEngine` under `app/src/main/java/com/cangkir/bitperfectplayer/audio/`:
    - Inspect active output devices via `AudioManager.getDevices()`, monitor changes via `AudioDeviceCallback`, and listen for `AudioManager.ACTION_AUDIO_BECOMING_NOISY`.
    - When `Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE` (API 34+), Bit-Perfect Mode is explicitly enabled, and a USB audio device is active: query `AudioManager.getSupportedMixerAttributes(device)` for `AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT` matching the PCM format/sample rate and apply via `setPreferredMixerAttributes()`.
    - Always call `AudioManager.clearPreferredMixerAttributes(audioAttributes, device)` on API 34+ when returning to Safe Mode, stopping/releasing playback, disconnecting USB, or changing track sample rates.
    - Guard `ENCODING_PCM_24BIT_PACKED`/`ENCODING_PCM_32BIT` with `Build.VERSION_CODES.S` and use `ENCODING_PCM_FLOAT` for 24-bit WAV on API 26–30.
    - Otherwise (or when in Safe Mode): open the cleanest native `AudioTrack` without DSP, EQ, normalization, or crossfade and publish accurate state to `AudioDiagnosticSnapshot`.
    - Track actual playback time (`currentPositionMs`) from `AudioTrack` frames/timestamps for precise seek bar and lyrics synchronization.
    - Handle `AudioFocusRequest` cleanly for incoming calls and notifications.
  - **Verification:** `./gradlew testDebugUnitTest assembleDebug` 100% green.

- [x] **T3.3 — `PlaybackForegroundService` & `MediaSession` Integration**
  - Implement `PlaybackForegroundService` declared with `android:foregroundServiceType="mediaPlayback"` in `AndroidManifest.xml`, started via `ServiceCompat.startForeground(...)` with `ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK`.
  - Expose system notification (`Notification.MediaStyle`) & `MediaSession` controls (`Play`, `Pause`, `Next`, `Previous`, `Seek`), surviving screen-off/background transitions and persisting the latest playback position to DataStore periodically and on pause/stop.
  - **Verification:** `./gradlew testDebugUnitTest assembleDebug` 100% green.

---

## Milestone 4: Local Library Scanner & Lyrics Integration

- [x] **T4.1 — `LocalLibraryRepository` (`MediaStore` + SAF Scanner)**
  - Scan MVP audio formats (`.flac`, `.wav`, `.mp3`, `.m4a`, `.ogg`, `.opus`) via `MediaStore.Audio.Media`.
  - Support adding folders and individual audio files via SAF (`ACTION_OPEN_DOCUMENT_TREE` / `ACTION_OPEN_DOCUMENT`) with defensive `takePersistableUriPermission`.
  - Provide grouped projections for All Songs, Folders, Artists, Albums, Recently Played, and Favorites, handling moved/deleted files gracefully.
  - **Verification:** `./gradlew testDebugUnitTest assembleDebug` 100% green.

- [x] **T4.2 — `LyricsRepository` (SAF Picker, `.lrc` Auto-Detect, & Manual Editor)**
  - Wire `ContentFingerprintGenerator`, `LrcParser`, and the local database:
    - Compute SHA-256 fingerprint for the active track using OOM-safe head/tail reads and look up persisted lyrics.
    - If absent, attempt `.lrc` auto-detection for a matching base filename inside accessible SAF/MediaStore folders, parse it, and persist it.
    - Expose manual `.lrc` import via SAF and manual lyrics save/edit/replace/delete operations.
  - **Verification:** `./gradlew testDebugUnitTest assembleDebug` 100% green.

---

## Milestone 5: Jetpack Compose UI (Dark Theme), Full Wiring, & Final Handoff

- [x] **T5.1 — Dark Theme & Library Screens**
  - Update the existing `app/src/main/java/com/cangkir/bitperfectplayer/ui/theme/{Color.kt, Theme.kt, Type.kt}` files to enforce a cohesive Material 3 Dark Theme, ensuring `>= 48.dp` touch targets for primary controls and accessibility `contentDescription` labels.
  - Build Library screens with tabs/sections for All Songs, Folders, Artists, Albums, Recently Played, and Favorites; SAF folder/file import action; persistent mini-player bar with quick Play/Pause; and clean runtime permission flows for `READ_MEDIA_AUDIO` (API 33+), `READ_EXTERNAL_STORAGE` (API 26–32), and `POST_NOTIFICATIONS` (API 33+).
  - **Verification:** `./gradlew assembleDebug` 100% green.

- [x] **T5.2 — Now Playing, Queue Management, & Hearing Safety Dialog**
  - Build the Now Playing screen: track metadata & format badge, seekable progress bar, Play/Pause/Prev/Next/Shuffle/Repeat controls, quick Pause action, Queue sheet/screen (add/remove/reorder), and quick navigation to Lyrics and Audio Diagnostics.
  - Add the Safe Mode vs. Bit-Perfect (opt-in) toggle wired to `AudioSafetyController`, presenting the mandatory **Hearing Safety Warning Dialog** before enabling potentially fixed-volume Bit-Perfect Mode.
  - **Verification:** `./gradlew assembleDebug` 100% green.

- [x] **T5.3 — Synced Lyrics Screen & Real-Time Audio Diagnostics Panel**
  - Build the Lyrics screen:
    - Synced `.lrc` view highlighting the active line from `AudioOutputEngine.currentPositionMs` with smooth `LazyColumn` auto-scrolling (including immediately after seek).
    - Scrollable static lyrics view for untimestamped text.
    - Actions to load `.lrc` via SAF and open the local manual lyrics editor (paste, save, edit, replace, delete).
  - Build the real-time Audio Diagnostics screen/sheet displaying all required fields from `SPEC.md` Section 3.5 with clear **Verified**, **Indicated**, and **Unverifiable** badges plus the maximum verification claim notice.
  - **Verification:** `./gradlew assembleDebug` 100% green.

- [x] **T5.4 — Full End-to-End Wiring, Final Audit, & `docs/HANDOFF_REPORT.md`**
  - Wire all layers (Application container/DI, Repositories, `AudioOutputEngine`, `PlaybackForegroundService`, ViewModels, and Compose Navigation) cleanly in `MainActivity.kt` with zero placeholder buttons or dummy flows.
  - Execute the full audit procedure in `.agents/skills/android-audio-verify/SKILL.md` (`testDebugUnitTest`, `lintDebug`, `assembleDebug`, deterministic source & merged manifest privacy audit, and `git status` check).
  - Write `docs/HANDOFF_REPORT.md` per `.agents/skills/overnight-task-loop/SKILL.md`, check off this task, commit, and push.
  - **Verification:** All commands succeed cleanly and `app/build/outputs/apk/debug/app-debug.apk` exists.