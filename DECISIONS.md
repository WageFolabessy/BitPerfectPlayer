# Architectural & Implementation Decisions (`BitPerfectPlayer`)

This file records autonomous architectural, dependency, and environment decisions made during MVP implementation, including rationale and rejected alternatives.

---

## D0.1 — Build Environment Provisioning & `gradlew` Executable Bit (`T0.1`)
- **Decision:** Provisioned Temurin JDK 25 (`25.0.3+9`, matching `gradle/gradle-daemon-jvm.properties` `toolchainVersion=25`) and Android SDK 37 (`platforms;android-37.0`, `build-tools;37.0.0`, `platform-tools`) outside the repository in `/home/antigravity/`, pointed untracked/gitignored `local.properties` at the local SDK directory, and set the executable mode (`+x`) on `gradlew`.
- **Rationale:** Preserves the exact Android Studio project baseline (`compileSdk { version = release(37) }`, `targetSdk = 37`, `minSdk = 26`, AGP `9.4.1`, Kotlin `2.2.10`, Gradle `9.6.0`) without downgrading SDK levels or modifying Gradle wrapper/daemon files. Marking `gradlew` executable allows `./gradlew` invocations to succeed directly on Linux/CI hosts.
- **Rejected Alternatives:** Downgrading `compileSdk` or `toolchainVersion` (prohibited by workspace invariants); committing `local.properties` (prohibited by Git hygiene rules).

---

## D0.2 — Dependency Selection & Offline Media Controls (`T0.2`)
- **Decision:** Configured permissive Apache 2.0 dependencies via `gradle/libs.versions.toml`: Room `2.8.5` with KSP `2.3.14` (KSP 2 decoupled plugin compatible with AGP 9.4.1 built-in Kotlin and Kotlin 2.2.10), DataStore Preferences `1.1.7`, Navigation Compose `2.9.8`, Material Icons Extended (via Compose BOM `2026.02.01`), Lifecycle Runtime/ViewModel Compose `2.9.4`, `core-ktx` `1.16.0` (for `ServiceCompat.startForeground` with `FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK`), `activity-compose` `1.10.1`, `documentfile` `1.1.0`, and `kotlinx-coroutines-{android,test}` `1.10.2`. Selected platform `android.media.session.MediaSession` + `android.app.Notification.MediaStyle` for system media controls.
- **Rationale:** Every dependency is Apache 2.0, supports `minSdk = 26` / `compileSdk = 37`, passes KSP + AGP 9.4.1 cleanly, and introduces zero `INTERNET` or `MANAGE_EXTERNAL_STORAGE` permissions into the merged debug manifest. Platform `MediaSession` + `Notification.MediaStyle` requires zero extra libraries and eliminates transitive network-module risk.
- **Rejected Alternatives:** `media3-exoplayer` (unnecessary footprint and risk of pulling network/HTTP data-source modules); `SQLiteOpenHelper` fallback (unnecessary because Room 2.8.5 + KSP 2.3.14 resolved and compiled cleanly).

---

## D1.2 — `.lrc` `[offset:]` Timing Convention (`T1.2`)
- **Decision:** Applied the standard LRC specification convention in `LrcParser`: positive `[offset:+N]` shifts lyrics earlier (`effectiveTimestampMs = (rawTimestampMs - offsetMs).coerceAtLeast(0L)`) and negative `[offset:-N]` delays lyrics.
- **Rationale:** Matches standard LRC authoring tools and players (Winamp/MiniLyrics/Kodi/VLC) where `+` shifts lyric text up/earlier in time, with a safe floor clamp at `0L` so lines never become unreachable.
- **Rejected Alternatives:** Inverting offset sign (`raw + offset`), which contradicts standard `.lrc` files exported by lyric tools.

---

## D3.2 — `AudioTrack` Performance Mode & Transient Audio-Duck Behavior (`T3.2`)
- **Decision:** Configured `AudioTrack.Builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_NONE)` and paused playback (rather than digitally scaling volume) on `AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK` whenever Bit-Perfect Mode is active.
- **Rationale:** `PERFORMANCE_MODE_LOW_LATENCY` routes through Android's fast mixer (typically fixed at 48 kHz) on many HALs, preventing native sample-rate bit-perfect USB paths; `PERFORMANCE_MODE_NONE` preserves direct USB/bit-perfect eligibility. Pausing on transient ducking in Bit-Perfect Mode avoids software volume attenuation (which alters PCM sample values and has no effect on fixed-volume paths) while Safe Mode continues to duck cleanly at `0.25f`.
- **Rejected Alternatives:** `PERFORMANCE_MODE_LOW_LATENCY` (risks forced HAL resampling); applying digital gain reduction during Bit-Perfect Mode.

---

## D5.4 — `MODIFY_AUDIO_SETTINGS` Normal Permission for Android 14+ Bit-Perfect Mixer APIs (`T5.4`)
- **Decision:** Declared `<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />` in `app/src/main/AndroidManifest.xml`.
- **Rationale:** Android Lint (`[MissingPermission]`) and the Android 14 SDK (`@RequiresPermission(Manifest.permission.MODIFY_AUDIO_SETTINGS)`) strictly require `MODIFY_AUDIO_SETTINGS` to invoke `AudioManager.setPreferredMixerAttributes()` and `AudioManager.clearPreferredMixerAttributes()`. `MODIFY_AUDIO_SETTINGS` is a normal install-time audio permission (no runtime prompt, zero network/storage/privacy exposure), whereas `INTERNET` and `MANAGE_EXTERNAL_STORAGE` remain strictly absent from both source and merged manifests.
- **Rejected Alternatives:** Suppressing `[MissingPermission]` via `@SuppressLint` (would cause `SecurityException` on physical Android 14 devices when enabling Bit-Perfect Mode or clearing preferred mixer attributes).

---

## D6.1 — P0 `MediaCodec` PCM Negotiation, Frame-Aligned `AudioTrack` Writes, & Exact `Hz` Formatting
- **Decision (P0 Audio Fix):**
  1. For high-bit-depth (`> 16-bit`, e.g., 24-bit FLAC) sources decoded through `MediaCodec`, request `AudioFormat.ENCODING_PCM_FLOAT` rather than `ENCODING_PCM_24BIT_PACKED`.
  2. Never assume `MediaCodec` honored a requested `KEY_PCM_ENCODING`; when `KEY_PCM_ENCODING` is absent from `codec.outputFormat`, resolve strictly to `AudioFormat.ENCODING_PCM_16BIT` (`PcmStreamRules.resolveMediaCodecOutputEncoding`).
  3. Defer `AudioTrack` creation until after `MediaCodecAudioDecoder.primeOutputFormat()` processes `INFO_OUTPUT_FORMAT_CHANGED` / first output buffer, and dynamically recreate `AudioTrack` if `formatVersion` changes mid-stream.
  4. Write `ENCODING_PCM_FLOAT` via `AudioTrack.write(FloatArray, offset, count, WRITE_BLOCKING)` and integer PCM via direct little-endian `ByteBuffer` `AudioTrack.write(ByteBuffer, remaining, WRITE_BLOCKING)`, looping on partial writes and enforcing exact PCM frame alignment (`channelCount * bytesPerSample`) for buffer sizing and carry remainders.
  5. Guard `AudioTrack.play()`, `pause()`, `flush()`, and `stop()` by checking `state` and `playState` so teardown is strictly idempotent.
- **Decision (P2 Sample-Rate Notation & Label De-duplication):** Standardized all sample-rate displays across domain formatters, Diagnostics, Library, MiniPlayer, and Now Playing on exact comma-formatted `Hz` units (`AudioFormatFormatter.formatSampleRateHz`, e.g., `96,000 Hz`, `48,000 Hz`, `44,100 Hz`) and collapsed identical container/codec pairs (e.g., `FLAC (FLAC)` -> `FLAC`).
- **Rationale:** Physical POCO F4 logcat showed `c2.android.flac.decoder` emitting 16-bit PCM while `AudioTrack` was opened as `AUDIO_FORMAT_PCM_24_BIT_PACKED` (`format 0x6`), plus an unaligned `8192`-byte read buffer (`8192 % 6 == 2`) for 24-bit stereo, causing MSB scrambling (`maxAmplitude 32512`). IEEE-754 float32 preserves all 24 integer bits losslessly and is natively supported by AOSP `c2.android.flac.decoder` and AudioFlinger. Exact `Hz` units match Android `AudioFormat`/`AudioMixerAttributes` telemetry directly without decimal rounding ambiguity (`44.1 kHz` vs `44,100 Hz`).
- **Rejected Alternatives:** Passing `ByteArray` to `AudioTrack.write()` on `ENCODING_PCM_FLOAT` (fails with `ERROR_BAD_VALUE = -2`); falling back to `requestedEncoding` when `KEY_PCM_ENCODING` is omitted from `outputFormat` (causes 16-bit/24-bit framing mismatch).

---

## D6.2 — Room v1→v2 Non-Destructive Cache Migration & Optimistic Favorite Reactivity
- **Decision:** Upgraded `BitPerfectDatabase` to `version = 2` with an explicit SQLite `MIGRATION_1_2` creating `cached_tracks` (preserving existing `lyrics`, `track_history`, and `favorite_tracks` tables untouched) and intentionally retained `exportSchema = false` (producing 0 KSP schema warnings while keeping the repository free of generated JSON schema artifacts for a local-first MVP). Backed `LocalLibraryRepository` directly with `CachedTrackDao.observeAll()` and added `LibrarySyncDiffer` (`uri` + `size` + `dateModified` incremental diff) + `FavoriteStateReducer` (optimistic URI override merged over Room's `favorite_tracks` Flow).
- **Rationale:** Cold launch immediately renders cached tracks from SQLite without flashing an empty screen or re-parsing unchanged files, while SAF folders/files persist cleanly. Optimistic favorite overrides guarantee next-frame heart-icon reactivity across `LibraryScreen`, `NowPlayingScreen`, and the `Favorites` tab even on first tap before SHA-256 hashing finishes.
- **Rejected Alternatives:** `fallbackToDestructiveMigration()` (would wipe user lyrics, playback history, and favorites on update); waiting for SHA-256 computation before updating heart icon state (causes perceptible UI lag on large FLAC/WAV files).
