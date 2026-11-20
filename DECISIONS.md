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

---

## D6.3 — 100% Offline Local Artwork Pipeline, Utilitarian Dark UI, & Custom Adaptive Launcher Icon
- **Decision:**
  1. Built a dedicated zero-dependency `AlbumArtworkLoader` (backed by pure-Kotlin `AlbumArtworkHelper`) using `MediaMetadataRetriever.embeddedPicture`, API 29+ `ContentResolver.loadThumbnail`, and SAF sibling cover-image lookup (`cover.jpg`, `cover.png`, `folder.jpg`, `front.jpg`, `album.jpg`, case-insensitive), downsampled via power-of-2 `BitmapFactory.Options.inSampleSize` into two reusable buckets (`160px` thumbnails and `600px` hero/MediaSession) with an `LruCache` + `cacheDir/artwork/` disk cache and bounded concurrency (`Semaphore(4)`).
  2. Replaced the navy/neon theme with neutral matte obsidian/charcoal surfaces (`#0E0E10` / `#141417` / `#1B1B20` / `#24242A`), hairline `0.5.dp` dividers, a single restrained slate-ice accent (`#7BA7D9`), muted non-pill metadata rows with non-wrapping durations, a compact `48.dp` single-line search bar, and a Now Playing compact audio-mode status strip opening a `ModalBottomSheet` (preserving `HearingSafetyWarningDialog` and quick `Pause`).
  3. Replaced the default Android template icon with a custom minimalist geometric audiophile emblem (concentric signal ring + 5 quantized symmetric PCM impulse bars centered inside the `66dp` adaptive safe zone) across adaptive XML (`ic_launcher_background`, `ic_launcher_foreground`, `ic_launcher_monochrome`), `mipmap-anydpi`, and lossless `.webp` mipmaps across all 5 densities (`mdpi`–`xxxhdpi`), and removed all 7 unused template colors from `res/values/colors.xml` (replacing them with `app_window_background = #FF0E0E10` referenced by `themes.xml`).
- **Rationale:** Implementing local artwork loading directly with platform APIs guarantees zero external networking dependencies and zero risk of transitive `INTERNET` permissions while remaining fast, smooth, and memory-bounded. Flattening card-in-card borders and replacing loud row badges with clean typographic hierarchy eliminates visual clutter and duration wrapping. Removing unused template color resources drops actionable Android Lint warnings to `0`.
- **Rejected Alternatives:** Adding Coil/Glide/Picasso (risks pulling HTTP/OkHttp transitively and bloats APK for strictly local artwork); leaving duplicate `.png` alongside `.webp` mipmaps (causes Android resource merge conflicts).

---

## D7.1 — Full APK Phase 1: Room `MIGRATION_2_3`, Anti-Skip Play Count Engine, `.m3u8` Codec, & 3-Tab Top-Level Navigation
- **Decision:**
  1. Upgraded `BitPerfectDatabase` from `version = 2` to `version = 3` via explicit non-destructive `MIGRATION_2_3`, adding `playlists`, `playlist_tracks` (with `ON DELETE CASCADE` foreign key to `playlists.id` and composite `(playlist_id, position_index)` index), and `track_stats` (keyed by SHA-256 `content_fingerprint` with `play_count` and `last_played_at_epoch_ms` indices) while preserving `cached_tracks`, `lyrics`, `track_history`, and `favorite_tracks` untouched.
  2. Implemented pure-Kotlin `PlayCountTracker` fed strictly by real active `AudioTrack` hardware-head playback time deltas (ignoring paused time and seek jumps) with anti-skip thresholds (`>= 5s` floor; `(durationMs / 2).coerceIn(10s, 30s)` for `>= 10s` tracks; `max(5s, 80%)` or natural EOS `>= 5s` for `< 10s` tracks; `30s` when duration unknown; at most once per session; reset on track switch, replay after completion, or `RepeatMode.ONE` loop).
  3. Implemented pure-Kotlin `M3u8PlaylistCodec` supporting standard `#EXTM3U` / `#EXTINF` / `#PLAYLIST` plus optional `#EXT-X-BITPERFECT-URI` and `#EXT-X-BITPERFECT-SHA256` hints so re-imported playlists match cleanly even across moved/renamed files while staying portable for external players.
  4. Added a clean 3-tab flat bottom `NavigationBar` (`Library`, `Playlists`, `Settings`) docked directly beneath `MiniPlayerBar` on top-level destinations only (hidden on `NowPlaying`, `Queue`, `Lyrics`, `Diagnostics`), preserving decoupled `playbackPositionMs: StateFlow<Long>` so 20 Hz ticks cause zero top-level recompositions, and keeping `All Songs` clean without extra sort/filter chrome.
- **Rationale:** Explicit SQLite `MIGRATION_2_3` guarantees zero loss of existing cached libraries, favorites, history, or lyrics when upgrading from MVP v2 APKs. Measuring active hardware playback head frames prevents seeking to the end or 2-second skips from polluting `Most Played` statistics.
- **Rejected Alternatives:** Incrementing play count on `loadTrack()` (pollutes stats with accidental taps and rapid skips); using `fallbackToDestructiveMigration()` (would wipe persisted user data on update).


