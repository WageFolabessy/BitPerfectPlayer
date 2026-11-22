# Architectural & Implementation Decisions (`BitPerfectPlayer`)

This file records autonomous architectural, dependency, and environment decisions made during implementation, including rationale and rejected alternatives.

---

## D0.1 — Build Environment Provisioning & `gradlew` Executable Bit (`T0.1`)
- **Decision:** Provisioned Temurin JDK 25 (`25.0.3+9`, matching `gradle/gradle-daemon-jvm.properties` `toolchainVersion=25`) and Android SDK 37 (`platforms;android-37.0`, `build-tools;37.0.0`, `platform-tools`) outside the repository in `/home/antigravity/`, pointed untracked/gitignored `local.properties` at the local SDK directory, and set the executable mode (`+x`) on `gradlew`.
- **Rationale:** Preserves the exact Android Studio project baseline (`compileSdk { version = release(37) }`, `targetSdk = 37`, `minSdk = 26`, AGP `9.4.1`, Kotlin `2.2.10`, Gradle `9.6.0`) *(baseline later upgraded to AGP `9.5.2`, Kotlin `2.4.21`, and Gradle `9.9.1`)* without downgrading SDK levels or modifying Gradle wrapper/daemon files. Marking `gradlew` executable allows `./gradlew` invocations to succeed directly on Linux/CI hosts.
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

---

## D7.2 — True Gapless Playback, Clean EOS Drain, & Safe-Mode-Only ReplayGain
- **Decision:**
  1. Implemented pure-Kotlin `GaplessTransitionPolicy` + background `preloadExecutor` decoder pre-opening (~8 s before EOS or immediately for short tracks) inside `AudioOutputEngine`, supplied via `PlaybackController.setNextTrack(...)` (`PlaybackQueueManager.peekNext()`).
  2. When consecutive tracks share identical `(sampleRateHz, channelCount, encoding)` and `gaplessEnabled == true`, `AudioOutputEngine` keeps the exact same `AudioTrack` running continuously (`SEAMLESS_REUSE`), switches readers immediately after Track A's last PCM frame, shifts `headBaseFrames = totalFramesWrittenToTrack` so hardware-clocked elapsed time transitions smoothly at `0 ms` the instant Track B reaches the DAC, and posts `onGaplessTransition` to advance queue/metadata/play-count without rebuilding `AudioTrack`.
  3. On format/sample-rate mismatch, `gaplessEnabled == false`, unavailable preload, or end-of-queue, `AudioOutputEngine` cleanly drains `AudioTrack` (`track.stop()` + bounded playback-head drain wait via `drainAudioTrackCleanly`) instead of calling `pause() + flush()`, eliminating the ~100–300 ms end-of-track truncation bug.
  4. Added pure-Kotlin OOM-safe header-only `ReplayGainTagParser` (FLAC `VORBIS_COMMENT`, OGG Vorbis `\x03vorbis`, OGG Opus `OpusTags` + RFC 7845 `R128_*_GAIN` Q7.8 shifted `+5.0 dB` to ReplayGain `-18 LUFS` reference, MP3 ID3v2.3/v2.4 `TXXX`, M4A/MP4 `moov->udta->meta->ilst->----` + optional `iTunNORM` fallback) and zero-allocation in-place `ReplayGainProcessor` (`OFF` default, `TRACK`/`ALBUM`, `-6..+6 dB` preamp, peak-clipping clamp).
  5. Enforced the **mandatory Bit-Perfect invariant**: whenever `safetyMode == AudioOutputMode.BIT_PERFECT_OPT_IN`, `ReplayGainProcessor` and `AudioOutputEngine` unconditionally bypass ReplayGain (`linearGain = 1.0f`, zero sample modification) and Stage 3 (`APP_TO_FRAMEWORK`) in `AudioDiagnosticMapper` explicitly reports `"ReplayGain: Bypassed (Bit-Perfect Active)"`.
- **Rationale:** Reusing a single running `AudioTrack` for matching PCM streams guarantees genuine 0 ms silence gap without injecting crossfades or DSP, while `track.stop()` draining on EOS eliminates abrupt tail truncation. Keeping ReplayGain strictly opt-in (`OFF` by default) and strictly Safe-Mode-only preserves pristine unmodified PCM bits in Bit-Perfect Opt-In mode while solving loud-mastering jumps during casual Safe Mode IEM listening.
- **Rejected Alternatives:** Artificial audio crossfading (modifies PCM samples and violates bit-perfect design); full-file memory reads for tag parsing (risks OOM on large 24-bit FLAC/WAV files); allowing ReplayGain attenuation inside `BIT_PERFECT_OPT_IN` mode (violates unmodified PCM bit-perfect invariant).

---

## D7.3 — Full APK Phase 3: Zero-Dependency `LocalBackupCodec`, Decoupled `SleepTimerController`, & Lazy `TrackInfoBottomSheet`
- **Decision:**
  1. Built a pure-Kotlin, zero-dependency RFC 8259 JSON encoder and strict validating recursive-descent parser inside `LocalBackupCodec` (`schemaVersion = 1`) rather than using Android's `org.json` or adding Gson/Moshi/kotlinx-serialization. Restores use non-destructive Room merge/upsert semantics (idempotent `max` merge for `track_stats`, union merge for `favorites`, recency-preserving upsert for `lyrics`, name-matched deduplicated append for `playlists`) with a `16 MB` SAF read safety cap.
  2. Implemented pure-Kotlin `SleepTimerController` exposed on a dedicated `sleepTimerState: StateFlow<SleepTimerState>` decoupled from `PlaybackControllerState` and collected exclusively inside narrow leaf composables (`SleepTimerTopBarButton`, `SleepTimerSettingsValueLeaf`, `SleepTimerBottomSheet`) so 1-second ticks never recompose `LibraryScreen`, `PlaylistsScreen`, or `BottomNavigationBar`. In `SAFE_VOLUME_MODE`, performs a smooth 3-second linear `AudioTrack.setVolume(...)` fade-out before pausing and restoring `1.0f`; in `BIT_PERFECT_OPT_IN`, pauses cleanly with zero volume scaling. When `End of current track` is armed, suppresses gapless next-track preloading so the active track drains cleanly and pauses without chaining into the next song.
  3. Added on-demand `TrackInfoLoader` executing file-size lookup, header-only `ReplayGainTagParser`, SHA-256 fingerprinting, and Room `track_stats` lookup strictly on `Dispatchers.IO` ONLY when the user opens `TrackInfoBottomSheet` (zero I/O in `LazyColumn` composition).
- **Rationale:** Avoiding `org.json` makes `LocalBackupCodec` 100% deterministic under plain JVM JUnit 4 tests (where Android stubs throw `Method not mocked`) while adding zero external dependencies or APK bloat. Decoupling `sleepTimerState` preserves silky 120 Hz scrolling across all top-level tabs even while a sleep timer counts down.
- **Rejected Alternatives:** Adding Gson/Moshi/Jackson (unnecessary dependency weight); collecting `sleepTimerState` in `BitPerfectAppRoot` (would trigger 1 Hz full-tree recompositions across `LibraryScreen` and `BottomNavigationBar`); reading ReplayGain tags or SHA-256 hashes inside `TrackListRow` (would cause scroll jank).

## D8.1 — Room v3 -> v4 Non-Destructive Migration, Cursor-Only Track/Disc Backfill, `QueueContext`, & Background Context-Aware Sorting
- **Decision:**
  1. Upgraded `BitPerfectDatabase` from `v3` to `v4` with an explicit non-destructive `MIGRATION_3_4` executing `ALTER TABLE cached_tracks ADD COLUMN disc_number INTEGER NOT NULL DEFAULT 0` and `ALTER TABLE cached_tracks ADD COLUMN track_number INTEGER NOT NULL DEFAULT 0`, preserving all 7 existing tables (`cached_tracks`, `lyrics`, `track_history`, `favorite_tracks`, `playlists`, `playlist_tracks`, `track_stats`) untouched.
  2. Read `MediaStore.Audio.Media.TRACK` (and `CD_TRACK_NUMBER` / `DISC_NUMBER` on API 30+) directly inside the single MediaStore cursor query and perform a one-time atomic `@Transaction` backfill for unchanged cached rows with `disc_number == 0 && track_number == 0`, avoiding any `MediaMetadataRetriever` Binder IPC slowdown on existing libraries. For FLAC files, `FlacStreamInfoParser` walks past `STREAMINFO` (seeking past `PICTURE` blocks) to read `VORBIS_COMMENT` `TRACKNUMBER`/`DISCNUMBER` on the same already-open `FileChannel`.
  3. Kept `TrackNumberParser`, `QueueContext`/`QueueContextResolver`, and `LibrarySortPolicy` pure Kotlin (zero `android.*` imports) and executed all sorting inside `LocalLibraryRepository` and `PlaylistRepository` via `.flowOn(Dispatchers.Default)` with independent persisted sort specs per context (`ALL_SONGS`, `ALBUM_DETAIL`, `ARTIST_DETAIL`, `FOLDER_DETAIL`, `RECENT`, `FAVORITES`, `PLAYLIST_DETAIL`, plus group tabs).
- **Rationale:** Reading track/disc metadata straight from MediaStore cursors + in-pass FLAC headers keeps initial library scans fast (~2–3 seconds) and immediately fixes album track ordering on upgrade via a ~5 ms transactional backfill. Persisting `QueueContext` prevents app relaunch from dumping all songs into the queue when the user was playing a single album or playlist. Executing sorting strictly on `Dispatchers.Default` guarantees zero list-sorting allocations during 120 Hz Compose `LazyColumn` scroll frames.
- **Rejected Alternatives:** `fallbackToDestructiveMigration()` (would wipe saved lyrics, playlists, favorites, and play counts); opening `MediaMetadataRetriever` on every cached MediaStore row to backfill track numbers (would regress scan times by ~30s); sorting track lists inside Compose `remember {}` blocks during UI composition; sharing a single global sort setting across All Songs and Album Detail (would break album track order whenever the user sorts All Songs).

---

## D9.1 — Safe-Mode-Only Real-Time Turntable Audio DSP (8 MB Rolling Ring Buffer + Click-Free Varispeed Resampler)
- **Decision:**
  1. Implemented pure-Kotlin `TurntablePcmRingBuffer`, `TurntablePcmCodec`, `TurntableSpeedController`, `TurntableResampler`, and `TurntableScratchPolicy` in `TurntableAudioDsp.kt` (zero `android.*`/`androidx.*` imports) and wired them into `AudioOutputEngine.runPlaybackLoop` strictly behind `AudioOutputMode.SAFE_VOLUME_MODE && isPlaying`.
  2. Capped the rolling float ring buffer strictly at **8 MB** (`2,097,152` floats rounded down to a whole multiple of `channelCount`, yielding ~23.8 s stereo history at 44,100 Hz, ~21.8 s at 48,000 Hz, ~10.9 s at 96,000 Hz, and ~5.46 s at 192,000 Hz) with zero per-chunk heap allocations. During normal Safe-Mode playback, unmodified pre-ReplayGain PCM frames are teed into the ring buffer before `writeAlignedPcmFully(...)` applies ReplayGain so gain is never double-applied during scratch or `1.0x` exit draining.
  3. Kept the existing running `AudioTrack` intact during live scratch (0 rebuilds), flushing the ~160 ms lookahead once on entry, throttling writes to ~40 ms lookahead (`sampleRate / 25`) *(later raised to ~65 ms / `sampleRate / 15` with 2 ms sleep in D9.4 / `3268e73`)* in ~15 ms chunks, applying ~12 ms exponential rate smoothing + 4 ms linear click-free gain envelopes on entry/hold/boundary/exit, and freezing `PlayCountTracker` accumulation throughout. On release, cursors still inside the ring buffer use the **Fast Seamless Path** (drain buffered frames at `1.0x` with 4 ms fade-in and zero `MediaCodec.flush()`), while deep rewinds past `oldestAvailableFrame` use the **Deep Rewind Path** (single clean `currentDecoder.seekToMs(targetMs)`).
  4. Enforced strict **Bit-Perfect Isolation**: in `BIT_PERFECT_OPT_IN`, `turntableRingBuffer` is never allocated (immediately released upon switching into Bit-Perfect mode), `beginTurntableScratch()` returns `false`, and `InteractiveVinylDisc` transparently runs silent rotational seek-preview + single `onSeekTo` on release.
- **Rationale:** Provides authentic vinyl DJ tactile audio feedback (forward varispeed up to `+4.0x`, finger-hold braking via a 65 ms idle watchdog, and smooth real-time reverse scratch down to `-4.0x`) without adding native C++/NDK/FFmpeg bloat, without risking OOM on 192 kHz 24-bit tracks, without distorting listening stats, and without compromising a single bit in `BIT_PERFECT_OPT_IN` mode.
- **Rejected Alternatives:** Android `PlaybackParams.setSpeed()` on `AudioTrack` (cannot play negative/reverse rates, fails or glitches on many OEM HALs, and cannot brake cleanly to `0.0x` hold); full-track PCM decoding into RAM (OOM risk on 100+ MB Hi-Res WAV/FLAC files); calling `MediaExtractor.seekTo()` repeatedly during reverse drag (causes severe codec stutter and keyframe snapping on FLAC/MP3/AAC); enabling turntable DSP inside `BIT_PERFECT_OPT_IN` (violates unmodified PCM and exclusive HAL mixer invariants).

---

## D9.2 — Zero-Permission Read-Only Head-Synced FFT Radial Ring Spectrum Visualizer
- **Decision:**
  1. Implemented pure-Kotlin `SpectrumAnalyzerDsp` / `SpectrumAnalyzer` (pre-allocated 1024-point Hann-windowed iterative radix-2 FFT, logarithmic 32-band mapping across `30 Hz..16,000 Hz` with `-62 dB..-4 dB` scaling and gentle high-frequency perceptual tilt, frame-rate-independent fast-attack `25 ms` / smooth-decay `140 ms` ballistics, and seamless 64-bar left/right radial mirroring) plus pure-Kotlin `SpectrumPcmTap` (`48,000`-float ≈ `192 KB` rolling mono buffer with boxcar-averaged Hi-Res decimation strides `1/2/4` and lock-free generation-checked `playbackHeadPosition` window alignment).
  2. Tapped PCM strictly **read-only AFTER `writeAlignedPcmFully(...)` / `writeNormalizedFloatsToTrack(...)`** inside `AudioOutputEngine.runPlaybackLoop`, so `AudioTrack` always receives completely unmodified bytes in `BIT_PERFECT_OPT_IN` mode while the visualizer works seamlessly in Safe Mode (including live turntable scratch), Clean Native, and Bit-Perfect Opt-In mode.
  3. Gated the side-tap strictly behind `NowPlayingScreen`'s `LifecycleEventObserver` (`ON_START` / `ON_STOP` + `onDispose`) AND the persisted `spectrumVisualizerEnabled` DataStore preference (`true` by default): when off-screen, backgrounded, or disabled, `spectrumPcmTap` is immediately released (`null`) and `BitPerfectAudioThread` skips tapping with zero CPU/memory overhead.
  4. Executed FFT snapshots off both the audio and main threads on `Dispatchers.Default` (~55 Hz) and drove 60/120 Hz ballistic smoothing exclusively inside `Canvas` draw invalidation (`drawFrameTick.longValue` read strictly inside `Canvas { ... }`) for **0 Compose recompositions**, stopping the animation loop completely when paused bars decay below `0.002f`.
- **Rationale:** Completely avoids `android.media.audiofx.Visualizer` and `RECORD_AUDIO` (zero scary microphone permissions, works on exclusive USB bit-perfect HAL paths where system `Visualizer` returns zeros), keeps bass/mid/treble frequency mapping accurate on 96/192 kHz Hi-Res files via anti-aliased decimation, eliminates ~160 ms audio-to-visual lookahead lag via `playbackHeadPosition` sync, and preserves silky 120 Hz UI performance.
- **Rejected Alternatives:** `android.media.audiofx.Visualizer` (requires `RECORD_AUDIO` runtime permission and breaks when `MIXER_BEHAVIOR_BIT_PERFECT` bypasses the Android mixer); running FFT inside `BitPerfectAudioThread` (risks audio underruns on Hi-Res streams); storing spectrum bars in Compose `State<List<Float>>` (would trigger 60–120 Hz Compose recompositions and GC churn); adding native FFTW/KissFFT C++ libraries (unnecessary NDK complexity for a 1024-point radix-2 FFT).

---

## D9.3 — Switchable Spectrum Palettes (`Studio`/`Prism`/`Album`), Pure-Kotlin Album Palette Extractor, & Zero-Allocation `KineticSparkPool`
- **Decision:**
  1. Added pure-Kotlin `SpectrumVisualDsp.kt` (zero `android.*`/`androidx.*` imports, zero external palette dependencies) containing `AlbumPaletteExtractor` (coarse-stride ~1,600-sample RGB->HSL 24-hue bucketing with `>= 36°` separation, OLED vibrancy clamping `S in 0.55..0.90`, `L in 0.52..0.72`, monochromatic harmony derivation, and Studio slate-ice fallback on grayscale/missing artwork), `SpectrumColorMapper` (`STUDIO`, 5-stop OLED-tuned `PRISM`, and `ALBUM_ADAPTIVE`), and fixed-capacity `KineticSparkPool` (`MAX_SPARKS = 64` pre-allocated primitive arrays with per-band `85 ms` transient cooldowns and exponential drag physics).
  2. Expanded `RadialSpectrumRing` cleanly to `316.dp` around a centered `240.dp` `InteractiveVinylDisc` (`38.dp` radial headroom) and `CompactSpectrumBarStrip` to `42.dp` (`24.dp` bars + `18.dp` upward spark headroom), drawing sparks as crisp velocity-aligned `drawLine` micro-streaks + `drawCircle` core dots inside the same draw-phase-only `Canvas` (zero GPU blur masks, zero Compose recompositions, zero per-frame allocations) while keeping `AudioOutputEngine.runPlaybackLoop()` completely untouched.
  3. Persisted `spectrumColorStyle` (`PRISM` default) and `spectrumSparksEnabled` (`true` default) in DataStore, exposed via `distinctUntilChanged()` `StateFlow`s collected strictly inside `Routes.SETTINGS` and `Routes.NOW_PLAYING`, with async `Dispatchers.Default` bitmap pixel extraction in `NowPlayingScreen` (software-bitmap-safe for `Bitmap.Config.HARDWARE`).
- **Rationale:** Delivers lively, audiophile-grade OLED reactivity across both Vinyl Disc and Square Cover modes without introducing `androidx.palette` dependencies, GPU blur thermal overhead, Compose recomposition jank, or ART instruction-limit bloat on `AudioOutputEngine`.
- **Rejected Alternatives:** Adding `androidx.palette:palette-ktx` (extra dependency and not pure-Kotlin unit-testable without Robolectric); using `BlurMaskFilter` / `RenderEffect` glow shaders on particles (causes 120 Hz GPU frame drops and heat on mobile GPUs); spawning `data class Spark` objects per beat (causes GC churn during 120 Hz animation).

---

## D9.4 — ART JIT/AOT Playback Loop Split & 65 ms Scratch Headroom (`3268e73`)
- **Decision:** Extracted `PlaybackLoopState` and focused private helper methods (`handlePendingSeekInLoop`, `processTurntableScratchStep`, `decodeAheadForForwardScratch`, `exitTurntableScratchCleanly`, `writeScratchFloatsAndTapSpectrum`, `processRingDrainStepInLoop`, `handleEndOfStreamInLoop`, `handleMidStreamFormatChangeInLoop`, `processNormalPcmChunkInLoop`, and `publishPlaybackPositionAndPreload`) out of `AudioOutputEngine.runPlaybackLoop(...)`, raised the scratch lookahead headroom from `sampleRate / 25` (~40 ms, 4 ms sleep) to `sampleRate / 15` (~65 ms, 2 ms sleep), and pre-fed up to 2 chunks of `1.0x` ring-buffer drain immediately after Fast Seamless scratch exit.
- **Rationale:** Keeps every `AudioOutputEngine` method well under ART's JIT/AOT compiler instruction limit (largest method verified at `807` Debug / `756` Release DEX code units, resolving `Method exceeds compiler instruction limit: 20666` fallback to interpreted execution) while eliminating 44,100 Hz scratch underruns (`AudioTrack W restartIfDisabled`).
- **Rejected Alternatives:** Keeping monolithic `runPlaybackLoop` with capturing local lambdas (forces ART bytecode interpretation on `BitPerfectAudioThread` and increases audio underrun risk).

---

## D10.1 — Late-2026 Kotlin/Compose Toolchain Upgrade & Build-Time `ktlint` + `detekt` Integration
- **Decision:**
  1. Upgraded Kotlin from `2.2.10` to **`2.4.21`** while preserving `AGP 9.5.1` (built-in Kotlin) *(patch-upgraded to `AGP 9.5.2` prior to `v1.1` release)*, `KSP 2.3.14`, `JDK 25`, `Gradle 9.9.1`, `compileSdk = 37`, `targetSdk = 37`, and `minSdk = 26`.
  2. Upgraded Jetpack Compose BOM from `2026.02.01` to **`2026.09.00`** (Compose `1.12.1` + Material 3 `1.4.0`), and migrated `BitPerfectUiBehaviorTest` from deprecated `androidx.compose.ui.test.junit4.createComposeRule` to `androidx.compose.ui.test.junit4.v2.createComposeRule`.
  3. Integrated two strictly build-time-only Gradle plugins (permissive open-source licenses, `0` runtime bytes, `0` permissions):
     - **`org.jlleitschuh.gradle.ktlint` `14.2.0`** (MIT) with root `.editorconfig` (`ktlint_code_style = android_studio`, `max_line_length = 140`, trailing commas enabled, `@Composable` PascalCase naming exception).
     - **`dev.detekt` `2.0.0-alpha.6`** (Apache 2.0) with `config/detekt/detekt.yml` (`buildUponDefaultConfig = true`, zero `detekt-baseline.xml`), fixing every genuine finding directly in production and test code (including a real coroutine `CancellationException` swallowing bug in `LocalLibraryRepository.refreshLibrary` / `toggleFavorite`).
  4. Wired `./gradlew ktlintCheck detekt` directly into `.github/workflows/android-ci.yml`, `CONTRIBUTING.md`, `README.md`, `AGENTS.md`, and `.agents/skills/android-audio-verify/SKILL.md`.
- **Rationale & Empirical Compatibility Findings:**
  - **Compose BOM `2026.11.01` vs `2026.09.00`:** Empirically tested `composeBom = "2026.11.01"` (Compose `1.13.0`), which failed `:app:checkDebugAarMetadata` with 6 AAR metadata errors because `androidx.compose.foundation:foundation-android:1.13.0` and `androidx.compose.ui:ui-android:1.13.0` require `compileSdk >= 37.1` (`minCompileSdkExtension`), whereas this repository's locked baseline targets stable `compileSdk { version = release(37) }` (`android-37.0`) and stable `AGP 9.5.1`. `Compose BOM 2026.09.00` is the highest stable late-2026 Compose BOM compatible with `compileSdk = 37` and compiles cleanly with `0` warnings under Kotlin `2.4.21`.
  - **`dev.detekt` `2.0.0-alpha.6` vs `io.gitlab.arturbosch.detekt` `1.23.8`:** Detekt `1.23.8` does not support Kotlin `2.4` / AGP `9` built-in Kotlin / Gradle `9` Configuration Cache / JDK `25`. `dev.detekt` `2.0.0-alpha.6` is the official Detekt 2.x release built for Kotlin `2.4` + AGP `9` + Gradle `9` + JDK `25`, executes cleanly in ~3–5 s with Configuration Cache reuse, and adds zero runtime code or permissions to the APK.
- **Rejected Alternatives:** Upgrading to alpha `AGP 9.6.0` or non-baseline `compileSdk = 37.1` to force Compose BOM `2026.11.01` (violates locked `AGP 9.5.1` / `compileSdk = 37` stability guardrail); sticking with legacy Detekt `1.23.8` (incompatible with Kotlin `2.4` + AGP `9` + JDK `25`); hiding complexity or exception-handling findings behind a `detekt-baseline.xml` file (masks genuine bugs like `CancellationException` swallowing).

---

## D10.2 — `AudioOutputEngine` Modular Decomposition into 4 Cohesive Internal Collaborators
- **Decision:**
  1. Decomposed the monolithic `AudioOutputEngine.kt` (`2,754` lines) into a concise **`642`-line coordinator** delegating to 4 focused `internal` collaborators inside `com.cangkir.bitperfectplayer.audio`:
     - **`AudioRouteAndMixerManager.kt` (`484` lines):** Output device detection, `AudioDeviceCallback` (preserving cold-start initial-callback suppression), `ACTION_AUDIO_BECOMING_NOISY` receiver, `AudioFocusRequest` lifecycle (with Bit-Perfect ducking isolation), Android 14+ (`API 34+`) `MIXER_BEHAVIOR_BIT_PERFECT` negotiation + mandatory `clearPreferredMixerAttributesOnApi34(...)` cleanup, and 6-stage `AudioDiagnosticSnapshot` mapping.
     - **`AudioTrackWriter.kt` (`543` lines):** `AudioTrack` creation/configuration (`PERFORMANCE_MODE_NONE`, frame-aligned buffer sizing via `PcmStreamRules.computeAudioTrackBufferSizeBytes`, API 31+ PCM encoding guards), idempotent `safePlay`/`safePause`/`safeFlush`/`safeStopAndRelease`, `drainAudioTrackCleanly(...)`, `readUnsignedPlaybackHeadFrames(...)`, zero-allocation `writeAlignedPcmFully(...)` / `writeNormalizedFloatsToTrack(...)`, and post-write read-only `SpectrumPcmTap` lifecycle & window sampling.
     - **`GaplessPreloadController.kt` (`311` lines):** Dedicated `"BitPerfectGaplessPreload"` single-thread executor, `preloadLock`, `setNextTrack(...)`, `maybeTriggerPreloadForPosition(...)`, generation/URI validation, OOM-safe header-only `readReplayGainMetadata(...)`, `pollSeamlessPreloadedTrack(...)`, and leak-free off-main-thread decoder close.
     - **`TurntableScratchRunner.kt` (`859` lines):** `PlaybackLoopState` per-worker buffer/cursor container + `TurntableScratchRunner` owning the Safe-Mode-only lazy 8 MB `TurntablePcmRingBuffer` lifecycle, pre-ReplayGain PCM ring-buffer teeing, normal PCM chunk writing, lock-free UI -> audio thread scratch rate atomics, `~65 ms` (`sampleRate / 15`) fill-throttled varispeed/reverse resampler loop with `2 ms` sleep, forward scratch decode-ahead, and click-free exit transition (Fast Seamless `1.0x` ring drain with 2 pre-fed chunks vs Deep Rewind `seekToMs`).
  2. Extracted pure-Kotlin domain helpers (`PcmStreamRules.computeAudioTrackBufferSizeBytes`, `TurntableScratchPolicy.computeScratchEntryCursorFrame` / `computeVirtualFrameBounds` / `computeClampedScratchPositionMs` / `canResumeViaSeamlessRingDrain`, and `AudioSafetyController.formatAutoPauseNotice`) covered by deterministic JVM unit tests (`183 / 183` passing tests across `32` suites).
- **Rationale:**
  - Reduces `AudioOutputEngine.kt` by **76.7%** (`2,754` → `642` lines) while preserving 100% of its public API, thread-ownership model (`BitPerfectEngineControl` with `engineLock`, `BitPerfectGaplessPreload` with `preloadLock`, and `BitPerfectAudioThread` with `THREAD_PRIORITY_AUDIO`), hearing safety guarantees, and Bit-Perfect bypass invariants.
  - Eliminates hot-path allocations by wiring shared `AtomicBoolean`/`AtomicLong` flags and transition callbacks at construction time rather than allocating per-chunk lambdas or `Pair` wrappers.
  - Improves ART JIT/AOT compilation headroom: `dexdump` verifies the largest method across `AudioOutputEngine` and all 4 collaborators is now **`457` DEX code units (Debug)** / **`410` DEX code units (Release)** (`TurntableScratchRunner.processTurntableScratchStep`), down from `807` / `756` and ~39x below ART's `16,384` instruction limit.
- **Rejected Alternatives:** Exposing internal collaborators as public APIs or changing `PlaybackController` contracts (unnecessary coupling); passing per-chunk lambda closures into `AudioTrackWriter.writeAlignedPcmFully` (risks heap allocations on `BitPerfectAudioThread`).

---

## D10.3 — Compose UI Feature Sub-Package Decomposition (`ui/library`, `ui/playlists`, `ui/nowplaying`, `ui/settings`)
- **Decision:**
  1. Split the 4 oversized root Compose screen files (`LibraryScreen.kt` `1,338` lines, `PlaylistsScreen.kt` `1,393` lines, `NowPlayingScreen.kt` `1,136` lines, and `SettingsScreen.kt` `957` lines) into 15 focused, cohesive files across 4 feature sub-packages matching their exact directory structure:
     - **`com.cangkir.bitperfectplayer.ui.library`:** `LibraryScreen.kt` (`427` lines), `LibraryHeaderControls.kt` (`369` lines), `LibraryTrackList.kt` (`327` lines), and `LibraryGroupViews.kt` (`301` lines).
     - **`com.cangkir.bitperfectplayer.ui.playlists`:** `PlaylistsScreen.kt` (`405` lines), `PlaylistsOverview.kt` (`455` lines), `PlaylistDetailView.kt` (`327` lines), and `PlaylistDialogs.kt` (`212` lines).
     - **`com.cangkir.bitperfectplayer.ui.nowplaying`:** `NowPlayingScreen.kt` (`463` lines), `NowPlayingControls.kt` (`406` lines), and `NowPlayingSheetsAndDialogs.kt` (`373` lines).
     - **`com.cangkir.bitperfectplayer.ui.settings`:** `SettingsScreen.kt` (`298` lines), `SettingsAudioSection.kt` (`327` lines), `SettingsDisplayAndLibrarySection.kt` (`357` lines), and `SettingsBackupAndAboutSection.kt` (`132` lines).
  2. Kept `VinylDiscPlayer.kt` and `RadialSpectrumRing.kt` in `com.cangkir.bitperfectplayer.ui` so `ui.nowplaying` depends unidirectionally on `ui` without circular package imports or changes to direct component unit tests in `BitPerfectUiBehaviorTest.kt`.
- **Rationale:**
  - Keeps every UI file focused (`132–463` lines) while preserving 100% of visual styling, `>= 48.dp` touch targets, hairline divider alignments, and 120 Hz recomposition isolation (`playbackPositionMs`, `sleepTimerState`, and `vinylScrubPreviewMs` remain collected/read strictly inside narrow leaf composables; `DropdownMenu` popups remain conditionally mounted only when `expanded == true`).
- **Rejected Alternatives:** Splitting `MainViewModel.kt` into per-screen ViewModels (rejected to preserve the single shared manual-DI ViewModel architecture); moving `VinylDiscPlayer.kt` / `RadialSpectrumRing.kt` into `ui/nowplaying/` while keeping shared primitives in `ui/` (Keeping them in `ui/` avoids cross-package visibility churn).

---

## D10.4 — Android 17 (`targetSdk = 37`) Background Audio Hardening & `ForegroundPlaybackGate` Handshake
- **Decision:**
  1. Created pure-Kotlin **`ForegroundPlaybackGate`** (`com.cangkir.bitperfectplayer.audio.ForegroundPlaybackGate`, `0` `android.*` / `androidx.*` imports) tracking `isAppInForeground: Boolean` and `isServiceInForeground: Boolean` with generation-token-scoped promotion awaiting (`awaitServicePromotion(timeoutMs = 1_000L, token): ForegroundHandshakeResult` returning `READY_IMMEDIATELY`, `PROMOTED`, `FAILED_START_NOT_ALLOWED`, `TIMED_OUT`, or `CANCELLED`).
  2. Registered a zero-dependency `Application.ActivityLifecycleCallbacks` counter (`startedActivityCount` on `onActivityStarted` / `onActivityStopped`) in `BitPerfectApplication.onCreate()` to keep `foregroundPlaybackGate.setAppInForeground(startedActivityCount > 0)` accurate at all times.
  3. Hardened `PlaybackForegroundService`:
     - In `onStartCommand`, `ACTION_START`, `ACTION_NEXT`, `ACTION_PREVIOUS`, and `ACTION_PLAY_PAUSE` (when transitioning to play) invoke `ensurePromotedToForeground()` (`ServiceCompat.startForeground(..., ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)`) **first** and immediately signal `foregroundPlaybackGate.markServicePromoted()`.
     - Guarded both `ContextCompat.startForegroundService(...)` and `ServiceCompat.startForeground(...)` against `ForegroundServiceStartNotAllowedException` (API 31+) and `SecurityException`, logging a warning and signaling `foregroundPlaybackGate.markServiceStartFailed(reason)` instead of crashing.
     - Signaled `foregroundPlaybackGate.markServiceStopped()` on `stopForegroundCompat()`, `onDestroy()`, and `Companion.stopService(...)`.
  4. Hardened `PlaybackController` & `AudioOutputEngine`:
     - When `foregroundPlaybackGate.needsForegroundServiceHandshake()` is `false` (`isAppInForeground || isServiceInForeground`), `play()` and `loadActiveTrack(..., autoPlay = true)` execute synchronously with **0 ms added latency**.
     - When starting or resuming playback from the background while `PlaybackForegroundService` is not yet promoted (`needsForegroundServiceHandshake() == true`), `PlaybackController` initiates `PlaybackForegroundService.startService(...)` and awaits `foregroundPlaybackGate.awaitServicePromotion(token)` before invoking `engine.play()` / `engine.loadTrack(..., autoPlay = true)`. If promotion is blocked (`FAILED_START_NOT_ALLOWED` or `TIMED_OUT`), `AudioOutputEngine.notifyBackgroundStartBlocked(...)` keeps playback cleanly in `PlaybackStatus.PAUSED` with an honest user notice (`ForegroundPlaybackGate.BACKGROUND_START_BLOCKED_NOTICE`).
     - In `AudioOutputEngine.play()` and `startPlaybackWorkerLocked()`, if `AudioManager.requestAudioFocus(...)` returns `AUDIOFOCUS_REQUEST_FAILED`, `isPausedFlag` is forced to `true`, `AudioTrack` is explicitly paused (`trackWriter.safePause(track)`), and status reverts to `PlaybackStatus.PAUSED` so `AudioTrack` never writes muted PCM in `PLAYING` state.
     - All `autoPlay = false` paths (cold-start session restore, paused Queue context switch, empty-queue enqueue) cancel any pending handshake and never start `PlaybackForegroundService` or request audio focus.
- **Rationale:**
  - Android 17 (`API 37`) enforces strict Background Audio Hardening: calling `AudioManager.requestAudioFocus(...)` or `AudioTrack.play()`/`write()` from the background before holding an active `mediaPlayback` Foreground Service with While-In-Use (WIU) capability causes Android 17 to reject focus (`AUDIOFOCUS_REQUEST_FAILED`) or silently mute audio output (or throw when `adb shell cmd audio set-enable-hardening throw` is enabled).
  - Eliminates the race condition between `ContextCompat.startForegroundService(...)` and `AudioOutputEngine.play()` during background resume (e.g., lock-screen / headset `MediaSession.Callback.onPlay()`) while guaranteeing 0 ms latency for all in-app interactions.
- **Rejected Alternatives:** Adding an unconditional delay before `engine.play()` (adds perceptible latency to in-app taps and is still a race condition); adding `androidx.lifecycle:lifecycle-process` (`ProcessLifecycleOwner`) when a 15-line `ActivityLifecycleCallbacks` counter in `BitPerfectApplication` achieves exact foreground tracking with zero extra dependencies.

---

## D10.5 — Zero-Dependency Adaptive Large-Screen, Foldable, Tablet, & Multi-Window UI (`WindowLayoutMetrics`)
- **Decision:**
  1. Created pure-Kotlin **`WindowLayoutMetrics`** (`com.cangkir.bitperfectplayer.ui.WindowLayoutMetrics`, `0` `android.*` / `androidx.*` imports) providing deterministic layout classification and proportional hero artwork sizing:
     - `useNavigationRail(widthDp: Int): Boolean` -> `widthDp >= 600`
     - `useTwoPaneNowPlaying(widthDp: Int, heightDp: Int): Boolean` -> `(widthDp >= 600 && widthDp > heightDp) || widthDp >= 720`
     - `computeNowPlayingDiscContainerDp(availableWidthDp: Int, availableHeightDp: Int, isTwoPane: Boolean): Int` -> smoothly scales the Vinyl Disc + Radial Spectrum Ring container in `220..316 dp` (keeping the exact `316 dp` container / `240 dp` vinyl disc on portrait phones while scaling cleanly in phone landscape `872x392` and square split-screen `420x420` so it never clips vertically or overlaps controls).
     - `contentMaxWidthDp(widthDp: Int): Int?` -> returns `840` when `widthDp >= 900`, else `null`.
  2. Implemented `BitPerfectAdaptiveTopLevelFrame` in `BitPerfectAppNav.kt` using `BoxWithConstraints`:
     - On compact portrait viewports (`< 600.dp`), renders the exact `v1.0` bottom `MiniPlayerBar` + `NavigationBar` (`BitPerfectBottomNavBar`).
     - On wide/landscape/tablet viewports (`>= 600.dp`) on top-level routes (`Library`, `Playlists`, `Settings`), renders a left-docked matte-obsidian `NavigationRail` (`BitPerfectNavigationRail`) separated by a `0.5.dp` `VerticalDivider` and docks `MiniPlayerBar` at the bottom of the right content pane (keeping the `NavHost` composition slot identical across screen rotations and route transitions).
  3. Implemented adaptive 2-column `NowPlayingScreen` (`ui/nowplaying/NowPlayingScreen.kt`):
     - When `useTwoPaneNowPlaying == false`, renders the exact `v1.0` portrait `verticalScroll` layout.
     - When `useTwoPaneNowPlaying == true`, renders a balanced 2-column studio `Row` (`widthIn(max = 1120.dp)`): left pane (`46%` width) centers `NowPlayingHeroCenterpiece` (`RadialSpectrumRing` + `InteractiveVinylDisc` or `SwipeableSquareArtwork` + `CompactSpectrumBarStrip`) scaled to `computeNowPlayingDiscContainerDp(...)`; right pane (`54%` width, vertically scrollable) renders safety alert banners, track metadata, isolated `NowPlayingSeekSection`, `NowPlayingTransportRow`, `NowPlayingAudioModeStrip`, and `NowPlayingUtilityStrip`.
  4. Centered content within `widthIn(max = 840.dp)` on `SettingsScreen`, `DiagnosticsScreen`, `QueueScreen`, and `LyricsScreen` for comfortable reading on wide displays.
  5. Bumped application version to **`versionName = "1.1"` (`versionCode = 2`)** in `app/build.gradle.kts`.
- **Rationale:**
  - Android 17 (`targetSdk = 37`) mandates adaptive layouts across large screens (`sw >= 600dp`), foldables, landscape rotation, and multi-window split-screen.
  - Using Compose's built-in `BoxWithConstraints` + Material 3 `NavigationRail` + pure-Kotlin `WindowLayoutMetrics` adds **0 external dependencies** (keeping the R8 Release APK lean at `2.62 MB` / `2,746,000 bytes`) and enables fast, deterministic JVM unit tests (`WindowLayoutMetricsTest`) plus Robolectric headless Compose UI tests (`BitPerfectUiBehaviorTest`).
- **Rejected Alternatives:** Pulling in `androidx.compose.material3.adaptive:*` + `androidx.window:window` artifacts (adds extra AAR footprint and transitive complexity for simple `600.dp` / `840.dp` breakpoint rules that `BoxWithConstraints` + `WindowLayoutMetrics` handle in ~100 lines of pure Kotlin).

---

## D11.1 — Direct `MediaExtractor` PCM Reader (`RawExtractorPcmDecoder`) for `audio/raw` (`FLAC (PCM)`) Streams
- **Decision:**
  1. Added pure-Kotlin **`RawSourcePcmEncoding`** and helpers in `PcmStreamRules.kt` (`ANDROID_ENCODING_PCM_8BIT = 3`, `resolveRawExtractorSourceEncoding`, `selectRawOutputEncoding`, `requiresRawPcmConversion`, `buildRawExtractorDecoderLabel`, plus zero-allocation offset-aware `convertPcm24LeToFloat32Bytes`, `convertPcm32LeToFloat32Bytes`, and `convertPcm8UnsignedToPcm16LeBytes`).
  2. Created **`RawExtractorPcmDecoder`** (`com.cangkir.bitperfectplayer.audio.RawExtractorPcmDecoder`) implementing `PcmAudioDecoder` directly on top of `MediaExtractor` whenever `MediaFormat.KEY_MIME == MediaFormat.MIMETYPE_AUDIO_RAW` (`audio/raw`), wired into `MediaCodecAudioDecoder.openMediaCodecDecoder(...)` **before** touching `KEY_PCM_ENCODING` or calling `MediaCodec.createDecoderByType(...)`.
  3. On API 31+ (`Build.VERSION_CODES.S`), verified native integer support via `AudioTrack.getMinBufferSize(...) > 0` before enabling bit-identical `PCM_24BIT_PACKED` / `PCM_32BIT` passthrough; on API 26–30 (or whenever `getMinBufferSize <= 0`), losslessly converts 24-bit/32-bit signed little-endian integer PCM to `PCM_FLOAT` (or unsigned 8-bit to `PCM_16BIT`) using reusable zero-allocation buffers.
  4. Resolved `sourceCodec` cleanly from verified FLAC headers (`"FLAC"` instead of redundant `"FLAC (PCM)"`) and reported exact Stage 2 decoder/encoding labels.
- **Rationale:**
  - On Android 16 (API 36) and several Custom ROMs, `MediaExtractor`'s `FlacExtractor` decodes 24-bit/96 kHz FLAC internally and exposes `audio/raw` integer PCM instead of compressed `audio/flac`. Previously, setting `KEY_PCM_ENCODING = ENCODING_PCM_FLOAT` and instantiating `c2.android.raw.decoder` caused the passthrough codec to emit unmodified integer PCM bytes tagged as IEEE-754 float32, producing full-scale white noise.
  - Reading `audio/raw` directly from `MediaExtractor` eliminates `c2.android.raw.decoder` completely, preserves bit-for-bit integer PCM on API 31+ (making 24-bit/96 kHz FLAC eligible for native integer USB mixer profiles), and guarantees lossless playback across API 26–37.
- **Rejected Alternatives:** Passing `audio/raw` through `c2.android.raw.decoder` without mutating `KEY_PCM_ENCODING` (adds unnecessary Codec2 IPC/copy overhead and still fails on API 26–30 when `FlacExtractor` emits 24-bit packed integer PCM that `AudioTrack` cannot play natively without float conversion).

---

## D11.2 — Live Turntable Post-Paused-Load Startup Ordering, Start-Threshold Headroom, & `TurntableFlushHeadTracker`
- **Decision:**
  1. Ordered cold worker startup in `AudioOutputEngine.play()` and `activateLoadedTrackLocked()` so `startPlaybackWorkerLocked(...)` (returning `Boolean`) requests focus, clears `stopWorkerFlag = false`, sets `isPausedFlag = false`, calls `safePlay(track)`, and spawns `BitPerfectAudioThread` **before** `_state` publishes `PlaybackStatus.PLAYING` (eliminating the race where `beginTurntableScratch()` observed `stopWorkerFlag == true` immediately after `loadTrack(autoPlay = false) -> play()`).
  2. Added pure-Kotlin **`TurntableScratchPolicy.computeEffectiveScratchMaxAheadFrames(...)`** and **`TurntableFlushHeadTracker`** in `TurntableAudioDsp.kt`:
     - On API 31+, `AudioTrackWriter.configureScratchStartThreshold(...)` lowers `AudioTrack.startThresholdInFrames` to `~65 ms` (`sampleRate / 15`) on scratch entry (and `restoreDefaultStartThreshold(...)` restores full-buffer threshold on exit), while `computeEffectiveScratchMaxAheadFrames` guarantees `maxAheadFrames > actualStartThreshold` across all API 26–37 devices so AudioFlinger never deadlocks waiting for `startThresholdInFrames` while the scratch loop waits for `playbackHeadPosition` to advance.
     - `TurntableFlushHeadTracker` tracks a dynamic `headOrigin` + `framesWrittenSinceOrigin` and rebases cleanly whenever an asynchronous `AudioTrack.flush()` drops `playbackHeadPosition` back to `0` after `play()`, eliminating the stale pre-flush head fill-throttle deadlock (silence + frozen `+00:00` delta).
  3. Added `PlaybackLoopState.ensureSafeModeScratchBuffers(...)` (strictly Safe-Mode-only), `reconcileAsyncFlushReset(...)` for seek flushes, and bounded transient 0-byte retry (`<= 3` reads) in `decodeAheadForForwardScratch(...)` for freshly seeked codecs.
- **Rationale:**
  - Directly resolves both hardware/HAL deadlock mechanisms behind post-`loadTrack(autoPlay = false) -> play()` turntable silence (stale pre-flush `playbackHeadPosition` + `startThresholdInFrames > maxAheadFrames`) while preserving strict zero-DSP isolation in `BIT_PERFECT_OPT_IN`.
- **Rejected Alternatives:** Removing the scratch fill-throttle completely (causes AudioTrack's full 150–250 ms buffer to fill, adding sluggish tactile scratch lag); unconditionally forcing `AudioTrack.startThresholdInFrames = 1` during normal playback (risks startup underruns on Bluetooth/USB routes).

---

## D11.3 — Android 14+ (`API 34+`) USB `MIXER_BEHAVIOR_DEFAULT` Preferred Sample-Rate Negotiation (`UsbMixerProfileSelector`)
- **Decision:**
  1. Created pure-Kotlin **`UsbMixerProfileSelector`** (`com.cangkir.bitperfectplayer.audio.UsbMixerProfileSelector`, `0` `android.*` / `androidx.*` imports) computing an ordered `UsbMixerNegotiationPlan` (`BIT_PERFECT` -> `DEFAULT_NATIVE_RATE` -> `NONE`):
     - In `BIT_PERFECT_OPT_IN`: selects exact `MIXER_BEHAVIOR_BIT_PERFECT` match first; if 0 matching bit-perfect profiles exist (or applying/confirming bit-perfect fails), falls back to `MIXER_BEHAVIOR_DEFAULT` matching the track's native sample rate and channel count (preferring exact encoding -> `PCM_FLOAT` -> `PCM_32BIT` -> `PCM_24BIT_PACKED` -> `PCM_16BIT`).
     - In `SAFE_VOLUME_MODE`: **strictly never selects `MIXER_BEHAVIOR_BIT_PERFECT`**, negotiating `MIXER_BEHAVIOR_DEFAULT` solely to lock native USB sample rate while keeping Safe Mode system volume control 100% intact.
  2. Extended `NegotiatedMixerBehavior` with `DEFAULT_NATIVE_RATE` and `AudioDiagnosticSnapshot` with `isNativeSampleRateMixerActive: Boolean = false` (keeping `isBitPerfectMixerActive == false` so neither Diagnostics, Settings, nor Media Notification ever falsely claims Bit-Perfect when `MIXER_BEHAVIOR_DEFAULT` is active).
  3. Wired `AudioRouteAndMixerManager` to execute the API 34+ negotiation plan, verify via `getPreferredMixerAttributes(...)`, and unconditionally clear preferred attributes via `clearPreferredMixerAttributesOnApi34(...)` on stop, release, route change, mode switch, and track reload.
- **Rationale:**
  - On USB-C dongles/ROMs whose Android 14–16 HAL exposes 0 `MIXER_BEHAVIOR_BIT_PERFECT` profiles yet advertises `MIXER_BEHAVIOR_DEFAULT` at `44.1 / 48 / 96 / 192 kHz`, negotiating preferred `MIXER_BEHAVIOR_DEFAULT` at the track's native sample rate avoids unnecessary OS resampling to 48 kHz while preserving full volume safety and honest 6-stage diagnostics.
- **Rejected Alternatives:** Leaving `SAFE_VOLUME_MODE` and 0-bit-perfect HALs locked to default 48 kHz mixing without requesting the USB HAL's native sample-rate default mixer profile; labeling `DEFAULT_NATIVE_RATE` as "Bit-Perfect" (strictly rejected per honesty invariants).

---

## D11.4 — Strictly Passive Read-Only Queue Top-Bar Navigation
- **Decision:**
  1. Made tapping the top-bar Queue icon (`Icons.AutoMirrored.Filled.QueueMusic`) 100% passive (read-only navigation to `QueueScreen`) across every screen and detail view (`All Songs`, `Folders`, `Artists`, `Albums`, `Recent`, `Favorites`, `Playlists`, Album/Artist/Folder/Playlist/Most Played details, and `Now Playing`).
  2. Removed the obsolete auto-context-switching pathway (`BrowsingQueueContext`, `QueueContextResolver.shouldSwitchToBrowsingContext(...)`, `MainViewModel.prepareQueueScreenContext(...)`, and `PlaybackController.loadContextQueue(...)`) and unified `onOpenQueue: () -> Unit` across `BitPerfectAppNav.kt`, `LibraryScreen.kt`, and `PlaylistsScreen.kt`.
- **Rationale:**
  - Opening the playback queue to inspect upcoming tracks must never silently overwrite the active queue or interrupt/pause playback simply because the user is browsing another tab, album, artist, folder, or playlist.
  - Queue replacement now happens exclusively when the user explicitly plays a track or invokes Play/Enqueue actions.
- **Rejected Alternatives:** Showing a confirmation modal whenever opening the Queue icon from another view (adds friction to a read-only inspection gesture).

---

## D11.5 — Zero-Dependency Pure-Compose Drag-and-Drop Reordering in `QueueScreen`
- **Decision:**
  1. Replaced the Up/Down arrow reorder buttons (`ArrowUpward` / `ArrowDownward`) in `QueueScreen.kt` with a tactile leading `Icons.Rounded.DragHandle` inside a dedicated `48.dp x 48.dp` `Box` (`contentDescription = "Reorder track"`, isolated via `Modifier.semantics(mergeDescendants = true) {}` and decoupled from the middle track-selection `clickable` `Row` and far-right `Remove` `IconButton`).
  2. Added `entryIds: List<Long>` to `QueueSnapshot<T>` in `PlaybackQueueManager.kt` (backed by monotonically unique internal `QueueEntry.id` values) so every `LazyColumn` row has a globally unique stable key even when duplicate track URIs exist in the queue.
  3. Added pure-Kotlin `VisibleQueueItemMetric`, `DragSwapDecision`, and `QueueDragReorderMath` (`computeDragSwap` with `2 px` hysteresis + exact offset continuity adjustment, and `computeEdgeAutoScrollPx`) in `QueueContext.kt` (0 `android.*` / `androidx.*` imports).
  4. Attached a stationary `pointerInput` gesture handler (`PointerEventPass.Initial`, RTL-aware leading `56.dp` handle column) on the `LazyColumn` itself so GPU-only `graphicsLayer { translationY; scaleX; scaleY; shadowElevation }` transforms on the dragged row never shift pointer coordinates mid-drag and normal vertical scrolling across the rest of each row stays untouched. Preserved `firstVisibleItemIndex` scroll anchoring via `requestScrollToItem`, prevented duplicate swaps via `pendingExpectedIndex`, added `withFrameNanos` edge auto-scrolling, and guaranteed drag reset via `DisposableEffect` and single/empty-queue guards.
- **Rationale:**
  - Delivers smooth 120 Hz drag-and-drop queue reordering with zero third-party libraries (keeping the R8 Release APK lean at `2.63 MB`), zero duplicate-key crashes, and active-track identity anchored accurately through `PlaybackQueueManager.moveItem(from, to)`.
- **Rejected Alternatives:** Adding an external Compose reorderable library (unnecessary dependency weight and transitive API churn); placing `pointerInput` inside the translated `QueueTrackRow` (causes coordinate feedback oscillation when `graphicsLayer.translationY` shifts the pointer receiver every frame).

---

## D11.6 — Full-List `All Songs` Search Playback Selection (Option 1)
- **Decision:**
  1. Added pure-Kotlin `QueueContextResolver.resolveFullListPlaybackSelection(fullTrackList, tappedTrack, fallbackTracks, fallbackIndex)` in `QueueContext.kt` matching `tappedTrack` by stable `uriString` inside the full sorted `allSongs` list (with safe bounded fallback if absent).
  2. Wired `LibraryScreen.kt`'s `All Songs` `TrackListView.onPlayTracks` so tapping a track while `searchQuery.isNotBlank()` queues the full sorted `libraryState.allSongs` list starting at that track's exact index under `QueueContext.allSongs()`.
- **Rationale:**
  - Previously, tapping a track from a 1-result search created a 1-track queue where Next/Previous/Shuffle could not move to other songs. Loading the full sorted `All Songs` queue at the tapped track's index keeps search fast while preserving full library playback continuity.
- **Rejected Alternatives:** Queueing only the filtered search hits (breaks Next/Previous/Shuffle whenever a specific title query matches 1 track); appending search results after the current queue (violates direct tap-to-play expectation).

---

## D11.7 — Configurable Default Homepage (`DefaultHomepage`) with One-Shot Startup Latch & Backward-Compatible JSON Backup
- **Decision:**
  1. Added pure-Kotlin `DefaultHomepage` enum (`ALL_SONGS`, `FOLDERS`, `ARTISTS`, `ALBUMS`, `RECENT`, `FAVORITES`, `PLAYLISTS`, default `ALL_SONGS`, `0` `android.*` imports) in `PersistenceModels.kt`, persisted under DataStore key `default_homepage` in `UserPreferencesRepository.kt`.
  2. Included optional top-level `"defaultHomepage"` in `LocalBackupPayload` / `LocalBackupCodec` (`schemaVersion = 1`) and wired `UserPreferencesRepository` into `LocalBackupRepository` so offline `.json` exports save `defaultHomepage` and restores apply it while legacy v1.0/v1.1 backups missing the field safely default to `DefaultHomepage.ALL_SONGS`.
  3. Latched `MainViewModel.initialStartupHomepage` strictly once in `init` (`compareAndSet(null, loaded)`) and routed startup through `BitPerfectStartupNavHost` (`rememberSaveable` start destination + initial `LibraryTab`) so cold launch lands directly on `Playlists` or the chosen `Library` tab with zero screen flash and never hijacks navigation when the user switches tabs or edits the preference in Settings.
  4. Added a matte-obsidian `"Default Homepage"` row and `DefaultHomepageBottomSheet` (`ModalBottomSheet` with `>= 48.dp` rows) in `SettingsDisplayAndLibrarySection.kt`.
- **Rationale:**
  - Lets users who organize around Albums, Folders, Favorites, or Playlists open directly into their preferred view without breaking navigation restoration, configuration changes, or legacy backup files.
- **Rejected Alternatives:** Reactive `LaunchedEffect(defaultHomepage)` navigating whenever the preference changes (would kick the user out of Settings immediately upon selecting a new default homepage); bumping `LocalBackupCodec.CURRENT_SCHEMA_VERSION` to `2` (unnecessary breaking change when an optional field preserves 100% forward/backward schema compatibility).

---

## D11.8 — Full Uncropped Album Artwork (`ContentScale.Fit`) & Option B Circular Vinyl Disc + `v1.2` Version Bump
- **Decision:**
  1. Extracted pure-Kotlin `ArtworkDimensions` and `AlbumArtworkHelper.computeProportionalTargetDimensions(srcWidth, srcHeight, maxDimensionPx, toleranceScale)` (`Double` scale precision, `0` `android.*` imports) and delegated `AlbumArtworkLoader.constrainMaxDimension` to it so square, landscape (`16:9`, `4:3`), and portrait covers strictly preserve their original aspect ratio during decode and cache generation (never center-cropped).
  2. Updated `TrackArtworkThumbnail` in `UiComponents.kt` (shared across `LibraryTrackList`, `LibraryGroupViews`, `PlaylistsOverview`, `PlaylistDetailView`, `QueueScreen`, `MiniPlayerBar`, and `SwipeableSquareArtwork`) to render `Image(..., contentScale = ContentScale.Fit)` on a matte-dark `MaterialTheme.colorScheme.surfaceVariant` container backdrop with rounded corners.
  3. Preserved authentic **Option B** circular vinyl disc framing in `VinylDiscPlayer.kt` (`CircleShape` + uniform, distortion-free `ContentScale.Crop` over the aspect-preserved bitmap).
  4. Bumped `app/build.gradle.kts` to **`versionName = "1.2"` (`versionCode = 3`)** (and updated `SettingsScreen.kt` fallback version string to `"1.2"`).
- **Rationale:**
  - Square `1:1` covers fill rectangular/square containers edge-to-edge seamlessly, while non-square covers (`16:9`, `4:3`, portrait) stay 100% uncropped across every browsing and playback surface without compromising the authentic circular vinyl label aesthetic.
- **Rejected Alternatives:** Forcing a shrunken square inside the circular vinyl center label (Option A; degrades turntable visual fidelity); mutating container aspect ratios per row in `LazyColumn` (breaks vertical rhythm and hairline divider alignment across track lists).

---

## D11.9 — Opt-In Local Diagnostic Logging Subsystem, Bounded Rotation, Zero-PII Export/Share, & R8 Stacktrace Retention
- **Decision:**
  1. **Default OFF & Opt-In Only:** Added `diagnosticLoggingEnabled: Boolean = false` to `UserPreferences` (`UserPreferencesRepository`), intentionally excluded from `LocalBackupCodec` `.json` restore so importing a backup never silently enables diagnostic recording. When OFF, `LocalDiagnosticLogger` performs only a single `@Volatile Boolean` check in `inline` log helpers (zero lambda/map/string allocations), writes zero files, spawns no background writer thread, and captures zero crash traces.
  2. **Pure-Kotlin Domain Core (`com.cangkir.bitperfectplayer.diagnostics`, `0` `android.*`/`androidx.*` imports):**
     - `DiagnosticLogChannel`: separates output cleanly across 4 files inside app-private `filesDir/diagnostic_logs/` (`device_summary.txt`, `audio_pipeline.txt`, `performance.txt`, `errors_and_crashes.txt`) plus `.prev.txt` backups.
     - `DiagnosticLogFormatter`: emits agent-readable single-line UTC ISO-8601 `YYYY-MM-DDTHH:mm:ss.SSSZ <LEVEL> <CHANNEL> <EVENT> key=value` records, `--- CRASH_START ---`/`--- CRASH_END ---` blocks, allowlisted audio extension extraction, 8-char lowercase SHA-256 prefixing, and defense-in-depth redaction of forbidden PII keys, `content://`/`file://` URIs, and `/storage/`/`/sdcard/` paths.
     - `DiagnosticLogRotationPolicy`: caps each append channel at `256 KB` with max `1` `.prev.txt` backup (`< 2 MB` total local footprint) and writes `device_summary.txt` atomically.
     - `DiagnosticLogBuffer`: bounded `512`-entry drop-oldest ring buffer so producers never block on disk I/O.
  3. **Non-Blocking Android Runtime, Zero-Permission Export/Share, & R8 Keep Rules:**
     - `LocalDiagnosticLogger` uses a lazily started low-priority daemon thread (`BitPerfectDiagWriter`, `ReentrantLock` + `Condition`, 3-second batch flush + flush on pause/stop/background/export).
     - `DiagnosticCrashHandler` chains `Thread.setDefaultUncaughtExceptionHandler` strictly when enabled and always delegates to the previous handler.
     - `DiagnosticLogExporter` uses standard JDK `ZipOutputStream` (zero external libraries) to bundle `README_LOGS.txt` + all non-empty log files via SAF `ActivityResultContracts.CreateDocument("application/zip")` (**Export Logs (.zip)**) and non-exported `androidx.core.content.FileProvider` scoped strictly to `cacheDir/diagnostic_exports/` via `res/xml/file_paths.xml` (**Share Logs**), adding **zero** Android permissions.
     - Added `-keepattributes SourceFile,LineNumberTable` to `app/src/main/keepRules/rules.keep` (AGP `9.5.2`'s active R8 keep rules file; `app/proguard-rules.pro` does not exist in this baseline). Teams publishing signed Release APKs should archive `app/build/outputs/mapping/release/mapping.txt` alongside each build to deobfuscate Release crash blocks with `retrace`.
  4. **Modular Settings UI (`SettingsDiagnosticLoggingSection.kt`):** Added a dedicated `DIAGNOSTIC LOGGING` matte-obsidian card in `SettingsScreen.kt` with opt-in toggle, live `Stored Logs` footprint (`3 files · 14.2 KB` / `No logs stored`), `>= 48.dp` **Export Logs (.zip)**, **Share Logs**, and **Clear Logs** actions.
- **Rationale:**
  - Gives non-root Release APK users a clean, privacy-preserving, one-tap way to collect structured audio HAL, `audio/raw`, USB mixer, turntable, FGS handshake, latency/underrun, and crash diagnostics on real devices without touching `adb`, degrading 120 Hz rendering, or risking audio glitches or library PII leakage.
- **Rejected Alternatives:** Writing a single unbounded monolithic log file (unreadable by agents and risks unbounded disk growth); auto-enabling logging on fresh install (violates strict opt-in privacy posture); writing to public `Downloads/` with legacy storage permissions (unnecessary permissions); adding external logging or ZIP libraries such as Timber/Zip4j (unnecessary APK bloat when JDK `ZipOutputStream` + pure Kotlin keeps the R8 Release APK at `2.70 MB`).

---

## D11.10 — `v1.2` Adversarial 7-Domain Audit Hardening
- **Decision:**
  1. **Strict `trackFormat`-Only Raw PCM Framing (`RawExtractorPcmDecoder`):** Removed `?: flacInfo?.bitsPerSample` fallback from `reportedBitsPerSample` so direct `audio/raw` byte framing always matches the extractor's actual wire format (`KEY_PCM_ENCODING` -> `"bits-per-sample"` / `"bit-depth"` -> `PCM_16BIT`), while keeping `flacInfo?.bitsPerSample` exclusively for honest Stage 1 `sourceBitDepth` reporting.
  2. **Allocation-Free Float32 PCM Conversion (`PcmStreamRules`):** Converted little-endian PCM bytes to `FloatArray` using direct bit shifts + `Float.fromBits(...)` instead of allocating a temporary `HeapByteBuffer` on every write buffer.
  3. **Exception-Safe USB Mixer Cleanup (`AudioTrackWriter`):** Added `try { ...; trackBuilt = true } finally { if (!trackBuilt) clearPreferredMixerAttributesOnApi34(usbDevice) }` around `AudioTrack.Builder().build()` so preferred mixer attributes can never leak on construction failure.
  4. **Debounced Mid-Drag Gapless Sync (`PlaybackController`):** Debounced `syncNextTrackToEngine()` by `250 ms` during `moveQueueItem` (canceled on direct sync/`stop`/`release`) so dragging across `currentIndex + 1` in the last 8 seconds of playback never thrashes `GaplessPreloadController`.
  5. **Bounded Crash Lock, Zero-CPU Disabled Park, Lazy Cold-Start Summary, & Expanded PII Redaction (`LocalDiagnosticLogger`, `DiagnosticLogFormatter`, `BitPerfectApplication`):** Guarded `writeCrashSync` with `fileWriteLock.tryLock(500L, TimeUnit.MILLISECONDS)`, parked `BitPerfectDiagWriter` indefinitely via `signalCondition.await()` when disabled, gated `buildDeviceSummaryFields` behind `prefs.diagnosticLoggingEnabled` + `playbackControllerLazy.isInitialized()`, and added `redactFilenameAndPaths(...)` covering bare filenames, `/data/user/...`, `/data/data/...`, and SAF volume-colon document IDs.
- **Rationale:**
  - Eliminates real-world failure modes (16-bit vs 24-bit raw framing mismatch, per-buffer GC churn, USB mixer attribute leaks, mid-drag decoder thrashing, crash-handler disk deadlocks, eager cold-start controller initialization on IO, and accidental filename leaks in exception messages) with minimal, localized, zero-regression changes.
- **Rejected Alternatives:** Swallowing `RuntimeException` in `AudioTrackWriter` (breaks detekt `TooGenericExceptionCaught` and obscures real builder errors); terminating and recreating `BitPerfectDiagWriter` thread on every toggle (unnecessary thread lifecycle churn compared to an indefinite `Condition.await()` park).

---

## D11.11 — `v1.2` Real-Device POCO F4 Logcat & Video Polish (Queue Transition, Deferred Paused Route Reconfigure, & Settings JIT Split)
- **Decision:**
  1. **Zero-Overlap `QueueScreen` Navigation & Lean Row Composition (`BitPerfectAppNav.kt`, `QueueScreen.kt`, `UiComponents.kt`):**
     - Replaced Navigation Compose's default 700 ms crossfade on `NavHost` with crisp `120 ms` / `90 ms` fades and set `EnterTransition.None` / `ExitTransition.None` on `Routes.QUEUE` so `LibraryScreen`'s lower-positioned track rows never bleed through `QueueScreen` during `bottomBar` hide/show and both full `LazyColumn` screens never lay out simultaneously on open/close.
     - Initialized `rememberLazyListState(initialFirstVisibleItemIndex = ...)` directly (`0` when `currentIndex <= 3`, or `(currentIndex - 1)` when deeper in the queue), eliminated the `O(N)` `.map { it as Any }` copy for `entryIds`, passed stable primitives/shared callbacks to `QueueTrackRow`, skipped `graphicsLayer` RenderNodes on stationary rows, and memoized `RoundedCornerShape(cornerRadius)` in `TrackArtworkThumbnail`.
  2. **Deferred Paused Route-Change Reconfigure (`RouteChangeReconfigurePolicy.kt`, `AudioOutputEngine.kt`):**
     - Extracted pure-Kotlin `RouteChangeReconfigurePolicy` (`0` `android.*` imports) so when an `AUTO_PAUSE` route/device/noisy event (such as virtual screen-recording audio sinks) arrives while playback is already paused, `AudioOutputEngine` still clears stale USB preferred mixer attributes, updates 6-stage diagnostics, preserves Safe Mode auto-pause notices, and sets `pendingRouteReconfigureOnPlay = true` — avoiding redundant `MediaCodec` + `AudioTrack` teardown/re-creation cycles (`mId: 0 -> 1 -> 2`) while paused, then cleanly reloading once on the next `play()` (keeping paused `seekTo()` and post-paused Live Turntable scrubbing 100% functional). Explicit Safe Mode / Bit-Perfect toggles and active-playback auto-pauses continue to reconfigure immediately.
  3. **ART-JIT-Lean `SettingsAudioEngineSection` (`SettingsAudioSection.kt`, `SettingsScreen.kt`):**
     - Grouped parameters into `SettingsAudioEngineUiState` and memoized `SettingsAudioEngineCallbacks`, and decomposed the 255-line inner lambda into 6 compact `private @Composable` helpers (`SettingsGaplessPlaybackRow`, `SettingsReplayGainSection`, `SettingsReplayGainModeSelector`, `SettingsReplayGainActiveControls`, `SettingsSleepTimerRow`, `SettingsDiagnosticsShortcutRow`) to eliminate the ~6.8 MB ART JIT spike observed in device logcat while preserving exact UI layout and behavior.
- **Rationale:**
  - Directly resolves all three real-device POCO F4 video and logcat observations with surgical, testable, zero-regression changes prior to merging `v1.2` into `main`.
- **Rejected Alternatives:** Filtering out `TYPE_REMOTE_SUBMIX` in `AudioDeviceCallback` (risks ignoring genuine OEM/HAL route transitions on Android 14–16 instead of cleanly deferring paused reloads for *all* routes); adding a post-composition `LaunchedEffect { listState.scrollToItem(...) }` in `QueueScreen` (causes visible post-first-frame scroll jumps).





