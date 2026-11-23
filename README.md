# BitPerfectPlayer

A 100% offline, local-first native Android music player (`com.cangkir.bitperfectplayer`) built with Kotlin and Jetpack Compose. Designed for clean digital PCM reproduction, runtime-adaptive Android 14+ USB DAC bit-perfect mixer negotiation, mandatory hearing protection for high-sensitivity in-ear monitors (IEMs), deterministic offline lyrics binding, and transparent 6-stage audio path diagnostics.

---

## Core Design Principles

1. **100% Offline and Local-First:** Zero `INTERNET`, `RECORD_AUDIO`, `MANAGE_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE`, or `READ_MEDIA_IMAGES` permissions. No networking libraries, telemetry, analytics, ads, cloud sync, or automatic online lookups for metadata, artwork, or lyrics.
2. **Hearing Safety First:** Defaults to **Safe Mode (`SAFE_VOLUME_MODE`)** on fresh installation and whenever any USB audio device connects. **Bit-Perfect Opt-In (`BIT_PERFECT_OPT_IN`)** never auto-activates and requires explicit confirmation through a mandatory `HearingSafetyWarningDialog` whenever hardware volume control is unverified.
3. **Honest Audio Diagnostics:** Explicitly distinguishes verified runtime state from OS-indicated metadata and unverifiable DAC hardware stages. Never equates lossless file decoding with verified bit-perfect output.
4. **Pure-Kotlin Domain Separation:** Core domain logic (SHA-256 content fingerprinting, `.lrc` parsing, WAV RIFF/PCM decoding, playback queue, safety state machine, route-change reconfigure policy, ReplayGain tagging/scaling, FFT spectrum DSP, turntable scratch DSP, WCAG cover-palette extraction, FLAC/MP3 multi-artwork header parsing, release-year extraction/consensus, chronological artist detail & folder artwork selection, notification subtitle/letterbox/custom-action logic, `RootPagerPolicy`, `NowPlayingSwipeMath` & `MiniPlayerSwipeMath`, diagnostic log formatting/rotation/buffering, JSON backup codec, and `.m3u8` codec) lives in `android.*`-free Kotlin modules for deterministic JVM unit testing.

---

## Features

### Audio Engine and Supported Formats

- **Supported Formats:**
  - **Lossless:** FLAC (16-bit and 24-bit), WAV (PCM 16-bit and 24-bit).
  - **Lossy:** MP3, M4A (AAC), OGG (Vorbis / Opus).
  - **Out of Scope:** DSD (`.dsf`/`.dff`), ALAC, APE, WavPack, and `.cue` sheets.
- **Direct RIFF/WAV Decoding (`WavHeaderParser.kt`):** Pure-Kotlin WAV header parser and direct PCM reader (`WavPcmDecoder`) so 24-bit WAV audio is never silently truncated to 16-bit. Guards `AudioFormat.ENCODING_PCM_24BIT_PACKED` and `AudioFormat.ENCODING_PCM_32BIT` behind `Build.VERSION.SDK_INT >= Build.VERSION_CODES.S` (API 31+), and unpacks 24-bit integer WAV PCM into 32-bit float (`AudioFormat.ENCODING_PCM_FLOAT`, preserving full 24-bit mantissa precision) on API 26-30.
- **Native MediaCodec & Direct `audio/raw` Path (`MediaCodecAudioDecoder.kt`, `RawExtractorPcmDecoder.kt`):** Decodes FLAC, MP3, M4A, and OGG via Android `MediaExtractor` and `MediaCodec`, inspects the actual confirmed `MediaFormat` PCM encoding at runtime, and reports down-conversion honestly if the OS decoder emits 16-bit PCM from a 24-bit source. Whenever `MediaExtractor` exposes `audio/raw` (`FLAC (PCM)` on Android 16 / Custom ROMs), routes directly through `RawExtractorPcmDecoder` (bit-identical native 24/32-bit integer passthrough on API 31+ when supported, lossless `PCM_FLOAT` conversion on API 26-30) instead of passing integer PCM into `c2.android.raw.decoder`.
- **Runtime-Adaptive Output Path (`AudioOutputEngine.kt`, `AudioRouteAndMixerManager.kt`, `AudioStartPathPolicy.kt`, `PlaybackCommandCoalescer.kt`, `UsbMixerProfileSelector.kt`):**
  - On Android 14+ (API 34+, guarded by `Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE`) with **Bit-Perfect Opt-In** active on a USB audio sink outside the `ACTION_AUDIO_BECOMING_NOISY` cooldown window, queries `AudioManager.getSupportedMixerAttributes(device)` via pure-Kotlin `UsbMixerProfileSelector`: prefers `AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT` matching format/sample rate, or falls back to `AudioMixerAttributes.MIXER_BEHAVIOR_DEFAULT` locked to the track's native sample rate (keeping system volume active and reporting it honestly—never claiming bit-perfect).
  - **Zero-Blocking Safe Mode & Hot-Plug Path (`D12.14`, `D12.16`, `D12.17`):** In **Safe Mode** (`SAFE_VOLUME_MODE`), `AudioTrack` stays unpinned (`preferredDevice = null`), playback startup strictly skips synchronous `getSupportedMixerAttributes` / `getPreferredMixerAttributes` / `setPreferredMixerAttributes` / `getDevices` / `getAudioDevicesForAttributes` Binder IPC (`AudioStartPathPolicy`), and header ReplayGain scanning skips files when `ReplayGainMode == OFF` (with lazy loading if toggled ON later). Early `UsbManager.ACTION_USB_DEVICE_ATTACHED` / `ACTION_USB_DEVICE_DETACHED` broadcasts filtered to `USB_CLASS_AUDIO` (`AudioRouteEventFilter.hasUsbAudioInterface`) trigger immediate hearing-safety `AUTO_PAUSE` and coalesce delayed `AudioDeviceCallback` / `ACTION_AUDIO_BECOMING_NOISY` events within `8,000 ms`, while `AudioTrack.pause()` + `abandonAudioFocusLocked()` run off the Main thread in a single `controlExecutor` task after immediate UI state update. Healthy unpinned Safe-Mode sessions pause in place (`PAUSE_IN_PLACE_KEEP_TRACK`) on plug/unplug route changes so the first Play tap resumes natively via AudioFlinger without multi-second USB HAL teardown stalls (with automatic `safePlay()` / `DeadAudioTrack` rebuild fallback and unconditional `computeRouteHeadRebase` position continuity), while single-flight `PlaybackCommandCoalescer` + `250 ms` delayed preparing halo prevent spinner flashes and burst toggles.
  - Automatically falls back to a clean native `AudioTrack` path (zero EQ, DSP, or crossfade) when preferred USB profiles are unsupported, on non-USB outputs, or on API < 34, labeling the path honestly.

### Hearing Safety Safeguards

- **Safe Mode Default (`AudioSafetyController.kt`):** Enforces `SAFE_VOLUME_MODE` on startup, fresh installs, and USB plug-in.
- **Mandatory Warning Gate:** Displays `HearingSafetyWarningDialog` before entering `BIT_PERFECT_OPT_IN` while `isHardwareVolumeVerified == false`, warning that output may run at fixed full scale (100% / 0 dBFS).
- **Immediate Auto-Pause & Paused Route Deferral (`RouteChangeReconfigurePolicy.kt`):** Automatically pauses playback, resets to Safe Mode, and clears preferred mixer attributes upon `AudioManager.ACTION_AUDIO_BECOMING_NOISY`, USB disconnect/reconnect, or output route changes. When a route change arrives while playback is already paused (such as virtual screen-recording sinks), stale USB mixer preferences and 6-stage diagnostics update immediately while `MediaCodec` + `AudioTrack` rebuilds defer cleanly until the next `play()`.
- **Software Volume Isolation:** Guards `AudioTrack.setVolume(...)` strictly to `SAFE_VOLUME_MODE`, ensuring zero digital volume scaling occurs when `BIT_PERFECT_OPT_IN` is active.

### Gapless Playback, Safe-Mode ReplayGain, and Sleep Timer

- **Gapless Playback (`GaplessTransitionPolicy.kt`):** Preloads the next track in the background near track completion (`8,000 ms` lead window) and reuses the running `AudioTrack` (`SEAMLESS_REUSE`) with zero artificial crossfade when sample rate, channel count, and PCM encoding match. On format mismatches or queue end, cleanly drains buffered frames instead of clipping the final milliseconds of audio.
- **Safe-Mode-Only ReplayGain (`ReplayGainTagParser.kt`, `ReplayGainProcessor.kt`):** Header-only parser supporting FLAC `VORBIS_COMMENT`, OGG Vorbis, OGG Opus (including RFC 7845 `R128_TRACK_GAIN` / `R128_ALBUM_GAIN`), MP3 ID3v2 `TXXX`, and M4A/MP4 freeform atoms. Defaults to `OFF`, supports `Track` / `Album` modes with `-6 dB` to `+6 dB` preamp and peak clipping prevention exclusively in `SAFE_VOLUME_MODE`, and is unconditionally bypassed (`gain = 1.0f`) in `BIT_PERFECT_OPT_IN`.
- **Sleep Timer (`SleepTimerController.kt`):** Supports presets (`Off`, `15 min`, `30 min`, `45 min`, `60 min`, and `End of current track`). Performs a smooth 3-second linear fade-out in Safe Mode before pausing, or pauses cleanly without digital volume scaling in Bit-Perfect Opt-In mode.

### 6-Stage Real-Time Audio Diagnostics

`AudioDiagnosticMapper.kt` and `DiagnosticsScreen.kt` expose real-time inspection across six explicit stages:

1. **Source File:** Container, codec, sample rate (exact `Hz`), and bit depth.
2. **Decoded PCM:** Runtime PCM encoding, sample rate, and channel count.
3. **App-to-Framework:** Engine buffer path and ReplayGain state (`OFF`, active Safe-Mode gain, or `Bypassed (Bit-Perfect Opt-In)`).
4. **OS Audio Policy / Mixer / HAL:** Active mixer behavior (`MIXER_BEHAVIOR_BIT_PERFECT`, `MIXER_BEHAVIOR_DEFAULT` native sample-rate lock, or clean native fallback) and sample-rate resampling status.
5. **USB Audio / Output Device Path:** Active `AudioDeviceInfo` output device and connection type.
6. **DAC Analog Conversion:** Internal DAC cable firmware and analog output stage.

Every diagnostic item is tagged `VERIFIED`, `INDICATED`, or `UNVERIFIABLE`, accompanied by the claim boundary notice:

> Verified up to Android Audio HAL / USB Output boundary; internal USB-C DAC cable firmware processing cannot be verified without external hardware instrumentation.

- **Opt-In Local Diagnostic Logging (`Settings -> DIAGNOSTIC LOGGING`):** Strictly **OFF by default** with zero extra permissions, zero disk I/O when disabled, and zero hot-path audio/UI thread blocking. When enabled, writes compact, single-line UTC `key=value` records across four bounded/rotated private `.txt` files (`device_summary.txt`, `audio_pipeline.txt`, `performance.txt`, `errors_and_crashes.txt`; `256 KB` cap + 1 `.prev.txt` backup per append channel, `< 2 MB` total) with defense-in-depth PII/URI/path redaction (never logs song titles, artists, albums, filenames, folder names, or raw URIs — uses 8-character SHA-256 prefixes and technical audio metadata only, including `AUTO_PAUSE_LATENCY`, `PLAY_RESUME_LATENCY`, `TRACK_LOAD_LATENCY`, `SLOW_AUDIO_STAGE`, `AUDIOTRACK_WRITE_STALL`, `ROUTE_EVENT`, `ENGINE_LIFECYCLE`, and `ROUTE_HEAD_REBASE`). Includes **Export Logs (.zip)** via SAF, **Share Logs** via scoped `FileProvider`, **Clear Logs**, and `-keepattributes SourceFile,LineNumberTable` in `app/src/main/keepRules/rules.keep` for `retrace`-ready Release APK crash stacks (retain `app/build/outputs/mapping/release/mapping.txt` per release build).

### Library, Artist Detail, Multi-Artwork, Queue, Playlists, and Local Backup

- **Scoped Storage Library (`LocalLibraryRepository.kt`, `MediaScanFilter.kt`):** Queries `MediaStore` while filtering out messaging voice notes (such as WhatsApp `PTT-` / `AUD-` clips), call/voice recordings, ringtones, alarms, notifications, and short sound effects (< 15 seconds). Supports manual Storage Access Framework (SAF) folder/file selection with persistable URI permissions.
- **Navigation, Horizontal Sub-Tab & Root/Top-Level `HorizontalPager` Swipes, Configurable Default Homepage, and Views (`DefaultHomepage`, `BitPerfectAppNav.kt`, `RootPagerPolicy.kt`, `LibraryScreen.kt`, `NowPlayingSwipeGesture.kt`):** Single hoisted icon-only (`>= 48.dp`, Filled/Outlined active toggle) bottom navigation bar (or adaptive side `NavigationRail` on `>= 600.dp`) framing `[Library] <-> [Playlists] <-> [Settings]` (`Playlists` includes smart `Most Played`) so non-adjacent tab jumps never animate through intermediate tabs and top-level horizontal swiping works seamlessly past the rightmost `Folders` boundary even when idle (`activeTrack == null`); smooth built-in `HorizontalPager` swipe navigation and bidirectional tab sync across all 6 **Library** sub-tabs (`All Songs`, `Artists`, `Albums`, `Recent`, `Favorites`, `Folders`) preserving independent per-tab scroll positions, search, and drill-down navigation, concise header status badges (`Safe Mode`, `Clean Native`, `Bit-Perfect USB`), plus a live 3-page root `HorizontalPager` unifying `[Content] <-> [Now Playing] <-> [Settings]` (`touchSlop`-aware `PageExclusionGuardBox` protecting `InteractiveVinylDisc` / center spindle, `SwipeableSquareArtwork`, seek `Slider`, and ReplayGain preamp `Slider` even during 120 Hz fine drags, floating non-shifting auto-pause alert overlay, stationary 2-line-slot transport controls, `MiniPlayerBar` upward swipe to open `Now Playing` & leftward dismiss, gesture-bar inset clearance on full-screen `Settings`, and a clean icon-only `NowPlayingUtilityStrip`), **Queue**, **Lyrics**, **Diagnostics**, and a **Track Info** technical details sheet. Users can select any Library tab or `Playlists` as their **Default Homepage** in Settings (applied once on cold launch without hijacking subsequent navigation).
- **Studio Artist Detail, Chronological (Oldest-First) Albums, & Unified `Artists` Tab Artwork (`ArtistDetailView.kt`, `ArtistDetailHelper.kt`, `ReleaseYearParser.kt`, `ArtistPhotoStore.kt`):** Both the `Artists` tab and `Artist Detail` Hero share one exact 4-tier artwork priority chain: offline custom artist photo (via Android's zero-permission Photo Picker with pure-Kotlin JPEG EXIF orientation correction and **0** `READ_MEDIA_IMAGES`) -> embedded `ARTIST` artwork (`8/7/10/19`) -> Front Cover of the **first album in the artist's oldest-first sorted album list** -> Monochrome Studio monogram. `Artist Detail` pairs the Hero Banner with summary stats, `Play All` & `Shuffle`, a strictly chronological **oldest-first** horizontal `Albums` carousel (consensus release year across all tracks in `1900..2100`, undated last, A–Z tie-breaker), a ruled `TRACKS` section, isolated Contextual Local Artist Dynamic Theme, and full parent backstack (`Artists -> Artist Detail -> Album Detail -> Back -> Artist Detail`).
- **Front Cover Thumbnails in `Folders` Tab (`FolderArtworkSelector.kt`, `LibraryGroupViews.kt`):** Keeps the `Folders` tab, `Folder Detail`, and `DefaultHomepage.FOLDERS` intact while upgrading each folder row with a full uncropped Front Cover thumbnail (`ContentScale.Fit`, deterministic track ordering, shared `AlbumArtworkLoader` caches) and a subtle monochrome folder badge/subtitle cue, falling back cleanly to a Monochrome Studio folder icon when coverless.
- **Full-List `All Songs` Search Playback:** Tapping a filtered search result in `All Songs` queues the full sorted `All Songs` library starting at that track's index so Next, Previous, and Shuffle continue seamlessly.
- **Metadata Ordering, Release-Year Backfill, & Sorting (`TrackNumberParser.kt`, `ReleaseYearParser.kt`, `LibrarySortPolicy.kt`):** Extracts disc numbers, track numbers, and release years (`1900..2100`) from MediaStore tags, FLAC `VORBIS_COMMENT` (`DATE`/`ORIGINALDATE`/`YEAR`), fast header-only ID3v2 (`TDRC`/`TYER`/`TDOR`), and `MediaMetadataRetriever` fallback, automatically backfilling missing release years for upgraded `v1.2` databases across both MediaStore and SAF sources. Persists independent per-scope sort preferences (`Track #`, `Album`, `Title`, `Sample Rate`, etc.) computed off the main thread.
- **Selective Passive Top-Bar Queue, Pure-Compose Drag-and-Drop Reorder, & Compact 2-Line `MiniPlayerBar` with Right-to-Left Session Dismissal (`QueueContext.kt`, `PlaybackQueueManager.kt`, `QueueScreen.kt`, `MiniPlayerSwipeMath.kt`, `UiComponents.kt`):** The strictly passive (read-only) Queue icon is placed cleanly on top app bars for `Library`, `Playlists` (`Playlist Detail` / `Most Played`), `Album Detail` (both standalone and Artist-child albums), and `Folder Detail` — plus `NowPlayingScreen` — while keeping the `Artist Detail` Hero Banner overlay uncluttered (Back + 3-dot overflow only) and keeping `MiniPlayerBar` clean with only **Play/Pause** and **Next**. Opening Queue never pauses playback or overwrites the active queue. Preserves queue source scope (`ALL_SONGS`, `ALBUM`, `ARTIST`, `FOLDER`, `PLAYLIST`, `RECENT`, `FAVORITES`, `CUSTOM`), restores the exact scoped queue on cold relaunch (paused), supports Shuffle and Repeat (`OFF` / `ALL` / `ONE`), and offers zero-dependency pure-Compose drag-and-drop track reordering via a dedicated `48.dp` drag handle with live midpoint swapping and edge auto-scrolling. `MiniPlayerBar` keeps a compact 2-line layout pinning a locale-independent `kHz` audio spec via `NotificationSubtitleFormatter.formatAudioSpec` (`Avenged Se... · FLAC 24-bit · 96 kHz`) so long artist names ellipsize while the format specification never truncates, and supports a right-to-left (`dx < 0`) swipe-to-dismiss gesture that stops playback and completely clears the active Now Playing session.
- **Anti-Skip Play Count (`PlayCountTracker.kt`):** Tracks real active `AudioTrack` playback time (ignoring pauses, seek jumps, and `< 5 s` skips; requiring `30 s` or `50%` listen threshold) bound to SHA-256 content fingerprints.
- **Playlists and `.m3u8` Import/Export (`PlaylistMembershipPolicy.kt`, `DragReorderSupport.kt`, `M3u8PlaylistCodec.kt`, `PlaylistRepository.kt`):** Local playlist CRUD with duplicate-track prevention (`"Added"` badge in picker dialog + unique insertion feedback), shared `DragHandle` drag-and-drop reordering in `CUSTOM_ORDER ASC` matching `QueueScreen`, `Remove + Undo` Snackbar recovery for playlist entries (and `Favorites` tab un-favorites), consistent `DetailActionHeaderRow` (`Play All`, `Shuffle`, overflow `.m3u8` export), and `.m3u8` SAF import/export resolving tracks by SHA-256 fingerprint, URI, relative path, or non-blank filename.
- **Messy-Metadata & Edge-Case Resilience (`MetadataScanPolicy.kt`, `PlaybackTrack.kt`, `LibraryProjector.kt`):** Defensive sanitization of blank/whitespace tags (`Unknown Artist`, `Unknown Album`, filename display fallbacks), symbol-only artist monograms, and disabled seek guards when duration is unknown (`<= 0 ms`).
- **100% Offline Local Backup and Restore (`LocalBackupCodec.kt`, `LocalBackupRepository.kt`):** Custom dependency-free RFC 8259 JSON encoder and strict parser (`schemaVersion = 1`) exporting and non-destructively merging playlists, fingerprint-bound lyrics, favorites, listening stats, `defaultHomepage`, and `dynamicCoverThemeEnabled` (with full backward compatibility for v1.0–v1.2 backups) via SAF `.json` files.
- **Full Uncropped Local Artwork & On-Demand FLAC/MP3 Multi-Artwork (`AlbumArtworkLoader.kt`, `AlbumArtworkHelper.kt`, `EmbeddedArtworkParser.kt`):** Extracts embedded cover art (preferring header-only FLAC/MP3 `FRONT_COVER` before `MediaMetadataRetriever`), MediaStore thumbnails, and SAF sibling cover images (`cover.jpg`, `folder.jpg`, `front.jpg`, `album.jpg`, `.png`) with aspect-ratio-preserving scaling, cold-open SAF contention yielding (`openingTrackUri`), and bounded memory/disk caching (`<= 512 px` album/disc-keyed `DISC` slot cache up to `24 MB` with adjacent `PLAYING`-only preloading). Renders full uncropped artwork (`ContentScale.Fit` on a matte-obsidian backdrop) across all lists, playlists, Queue, MiniPlayer, Album Detail, and Square Cover (with an unobtrusive Front/Back flip toggle shown only when a Back Cover is present), while displaying embedded `DISC` art (holding a neutral matte-obsidian center label until uncached `DISC` probes settle, never flashing Front Cover prematurely) inside the circular Vinyl Disc center label (`CircleShape`).
- **Polished `MediaSession` Controls & Android 17 (`targetSdk = 37`) Hardening (`PlaybackForegroundService.kt`, `ForegroundPlaybackGate.kt`, `NotificationSubtitleFormatter.kt`, `NotificationArtworkFramer.kt`, `MediaSessionCustomActions.kt`):**
  - `PlaybackForegroundService` (`foregroundServiceType="mediaPlayback"`) using `ServiceCompat.startForeground(...)`, custom pure-monochrome status icon (`ic_stat_bitperfect.xml`), platform `android.media.session.MediaSession`, and `Notification.MediaStyle`.
  - Automatically centers non-square (`width != height`) album covers onto a matte-obsidian (`#111114`) square canvas (`<= 512x512`, off-main) so lock-screen and shade cards never crop landscape/portrait artwork, while square `1:1` covers pass through borderless.
  - Displays a concise, locale-independent audiophile subtitle (`Artist · Codec Bit-Depth · Sample Rate`, e.g., `The Beatles · FLAC 24-bit · 96 kHz`, `Artist Name · MP3 · 44.1 kHz`) synced across `MediaMetadata` and notification text.
  - Exposes `Favorite` (`ACTION_TOGGLE_FAVORITE`) and `Shuffle` (`ACTION_TOGGLE_SHUFFLE`) `MediaSession` custom actions and notification controls with live bidirectional state sync even when backgrounded. *(Note: Android SystemUI governs final media card layout — AOSP, Pixel, and Custom ROMs render custom actions alongside transport buttons, while some OEM skins such as certain HyperOS versions may cap visible notification controls to the 3 primary transport buttons.)*
  - Pure-Kotlin `ForegroundPlaybackGate` awaits active `mediaPlayback` Foreground Service While-In-Use (WIU) promotion on background resume before `AudioManager.requestAudioFocus(...)` or `AudioTrack.play()`/`write()` (with **0 ms added latency** when already in-app or promoted), guarding against `ForegroundServiceStartNotAllowedException` and `SecurityException`.

### Offline Lyrics System

- **Deterministic Content Fingerprinting (`ContentFingerprintGenerator.kt`):** Binds lyrics to a lowercase 64-character hex SHA-256 primary key rather than fragile track titles or `MediaStore._ID` values:
  - `fileSize <= 131,072` bytes (128 KB): `SHA-256(entire file bytes)`
  - `fileSize > 131,072` bytes: `SHA-256(first 65,536 bytes + last 65,536 bytes + 8-byte big-endian unsigned file size)`
  - Uses seekable random-access reads (`ParcelFileDescriptor` / `FileChannel`) with a 64 KB ring-buffer streaming fallback for non-seekable streams.
- **Fault-Tolerant `.lrc` Parser (`LrcParser.kt`):** Supports `[mm:ss.xx]`, `[mm:ss.xxx]`, multi-timestamp lines, metadata tags (`[ti:]`, `[ar:]`, `[al:]`, `[offset:]`), `UTF-8`, `UTF-8 BOM`, `UTF-16LE`, `UTF-16BE`, and `ISO-8859-1` fallback, plus static display for plain untimestamped text.
- **Sync and Editing:** Auto-detects sibling `.lrc` files, imports `.lrc` files via SAF, includes a local manual lyrics editor, and derives active line highlighting directly from `AudioOutputEngine` playback position.

### Monochrome Studio Theme, Dynamic Theme, Vinyl Turntable, Spectrum Visualizer, and Adaptive Layout

- **Monochrome Studio Default Theme & Launcher Icon (`ui/theme/`, `res/drawable/ic_launcher_*.xml`):** Matte-obsidian dark surfaces (`#0E0E10` background, `#161619` / `#1E1E22` surface cards, `0.5.dp` hairline dividers), warm ivory primary accent (`#F2F2F0` with `#111113` high-contrast active foreground ink), brushed silver secondary (`#B8B8BE`), preserved semantic safety/diagnostic colors (`EmeraldSafe`, `AmberIndicated`, `CoralWarning`), minimum `48.dp` touch targets, and a matching Monochrome Studio adaptive & legacy launcher icon.
- **Opt-In WCAG-Clamped Dynamic Theme (`CoverThemePaletteGenerator.kt`, `CoverThemeColorCache.kt`, `ThemeArtworkCandidateSelector.kt`):** Off by default (`Settings -> Display -> Dynamic Theme`). Quantizes downsampled cover art (`<= 32x32`, off-main on `Dispatchers.Default`), falls back gracefully to Monochrome Studio on B&W/grayscale covers, resolves stable chromatic candidates across albums/playlists even when Track #1 is coverless, programmatically clamps WCAG 2.1 contrast (`>= 4.5:1` accents, `>= 7.0:1` primary text, dark obsidian background), and hoists active playing or opened detail themes cleanly to root `BitPerfectPlayerTheme` (`300 ms`) so bottom navigation, `NavigationRail`, `MiniPlayerBar`, and detail views adapt together.
- **Adaptive Phones, Foldables, Tablets, & Multi-Window Layout (`WindowLayoutMetrics.kt`, `BitPerfectAppNav.kt`, `ui/nowplaying/`):**
  - **Compact Portrait (`< 600.dp`):** Bottom `NavigationBar` with persistent `MiniPlayerBar` and single-column `NowPlayingScreen` (`316.dp` radial spectrum ring around `240.dp` vinyl disc).
  - **Wide / Landscape / Foldable / Tablet (`>= 600.dp`):** Left-docked matte-obsidian `NavigationRail` separated by a `0.5.dp` `VerticalDivider` with `MiniPlayerBar` docked strictly in the right content pane; balanced 2-column `NowPlayingScreen` (`46%` left pane with proportionally scaled `220..316.dp` Vinyl Disc + Radial Spectrum Ring so it never clips vertically in landscape or split-screen; `54%` right pane with metadata, isolated `NowPlayingSeekSection`, transport controls, audio-mode strip, and utility buttons); and centered `840.dp` max-width containers on `SettingsScreen`, `DiagnosticsScreen`, `QueueScreen`, and `LyricsScreen`.
- **Interactive Vinyl Disc (`VinylDiscPlayer.kt`, `VinylScrubMath.kt`, `TurntableAudioDsp.kt`):** Switchable between **Vinyl Disc** (default) and **Square Cover** modes. Renders a GPU-rotated vinyl disc (`graphicsLayer { rotationZ }`) with velocity-sensitive finger scrubbing and catchable bounded flick inertia:
  - In **Safe Mode** while playing, touching/dragging engages a pure-Kotlin real-time varispeed/reverse turntable resampler (`-4.0x` to `+4.0x` and `HOLD` braking) backed by a capped 8 MB float PCM ring buffer, dynamic `TurntableFlushHeadTracker`, and API 31+ `startThresholdInFrames` headroom with zero `AudioTrack` rebuilds.
  - In **Bit-Perfect Opt-In** mode (or when paused), turntable DSP and ring buffering are completely bypassed, falling back to silent seek preview and a single `seekTo` on release.
- **Read-Only Radial Ring Spectrum Visualizer (`SpectrumAnalyzerDsp.kt`, `SpectrumPcmTap.kt`, `SpectrumVisualDsp.kt`, `RadialSpectrumRing.kt`):**
  - Zero-permission (`no RECORD_AUDIO`, `no android.media.audiofx.Visualizer`) 1024-point Hann-windowed radix-2 FFT driving 64 radial ticks around the vinyl disc (or a compact `42.dp` 32-bar strip under Square Cover).
  - Reads from a post-write PCM side-tap (`48,000`-float mono ring buffer, ~192 KB) with sample-rate decimation for 88.2-192 kHz streams and `AudioTrack.playbackHeadPosition` alignment, leaving PCM output strictly unmodified in both Safe Mode and Bit-Perfect Opt-In mode.
  - Lifecycle-gated (`ON_START..ON_STOP` on `NowPlayingScreen` only) so FFT CPU usage and tap buffer allocation drop to zero off-screen or when disabled.
  - Switchable **Studio** (brushed silver/ivory), **Prism**, and local cover-art **Album** (`AlbumPaletteExtractor`) color palettes, plus optional pre-allocated **Kinetic Sparks** (`MAX_SPARKS = 64`).

---

## Permissions and Privacy

Declared permissions in [`app/src/main/AndroidManifest.xml`](app/src/main/AndroidManifest.xml):

| Permission | Scope | Purpose |
| :--- | :--- | :--- |
| `MODIFY_AUDIO_SETTINGS` | Install-time | Required by Android 14+ `AudioManager.setPreferredMixerAttributes()` and `clearPreferredMixerAttributes()` |
| `READ_MEDIA_AUDIO` | API 33+ | Local MediaStore audio library discovery |
| `READ_EXTERNAL_STORAGE` | `maxSdkVersion="32"` | Local MediaStore audio discovery on Android 8.0-12L (API 26-32) |
| `FOREGROUND_SERVICE` | Install-time | Persistent background playback service |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | API 34+ | `mediaPlayback` foreground service compliance |
| `POST_NOTIFICATIONS` | API 33+ | Media playback control notification |

Strictly prohibited and verified absent from both source and merged manifests:
- `android.permission.INTERNET`
- `android.permission.MANAGE_EXTERNAL_STORAGE`
- `android.permission.WRITE_EXTERNAL_STORAGE`
- `android.permission.RECORD_AUDIO`
- `android.permission.READ_MEDIA_IMAGES`

---

## Architecture and Package Layout

```text
app/src/main/java/com/cangkir/bitperfectplayer/
|-- BitPerfectApplication.kt      # Application container, repository wiring, & foreground lifecycle tracking
|-- MainActivity.kt               # Single-activity Compose entry point (edge-to-edge, Global Dynamic Cover Theme)
|-- audio/                        # Decoders (incl. RawExtractorPcmDecoder), WAV parser, modular AudioOutputEngine,
|                                 # UsbMixerProfileSelector, RouteChangeReconfigurePolicy, ForegroundPlaybackGate,
|                                 # NotificationSubtitleFormatter, MediaSessionCustomActions, safety, diagnostics, DSP
|-- data/                         # Room v5 DB (MIGRATION_1_2..4_5), entities, DAOs, DataStore prefs, JSON backup codec
|-- diagnostics/                  # Opt-in local diagnostic logging (pure formatter/rotation/ring buffer, logger, crash handler, ZIP exporter)
|-- library/                      # MediaStore/SAF scanning, ReleaseYearParser, EmbeddedArtworkParser (FLAC/MP3), ArtistDetailHelper,
|                                 # ArtistPhotoStore, FolderArtworkSelector, AlbumArtworkLoader, NotificationArtworkHelper/Framer, sorting, playlists, .m3u8
|-- lyrics/                       # SHA-256 content fingerprint generator, .lrc parser, repository
`-- ui/                           # Adaptive navigation (WindowLayoutMetrics), ViewModel, selective top-bar Queue, compact 2-line MiniPlayerBar, drag-and-drop Queue, vinyl/spectrum,
    |-- library/                  # LibraryScreen, ArtistDetailView, header controls, track list, & group views
    |-- nowplaying/               # Adaptive 1/2-pane NowPlayingScreen, controls (Front/Back flip), & hearing safety dialog
    |-- playlists/                # PlaylistsScreen, overview, detail view, & dialogs
    |-- settings/                 # SettingsScreen, audio engine, display/library, backup/about, & diagnostic logging sections
    `-- theme/                    # Monochrome Studio Color/Theme/Type, CoverThemePaletteGenerator, & CoverThemeColorCache
```

Local persistence uses Room (`BitPerfectDatabase.kt`, schema `version = 5`) with seven entities and verified non-destructive SQLite migrations (`MIGRATION_1_2`, `MIGRATION_2_3`, `MIGRATION_3_4`, `MIGRATION_4_5`):
- `cached_tracks` (including release `year`)
- `lyrics`
- `track_history`
- `favorite_tracks`
- `playlists`
- `playlist_tracks`
- `track_stats`

Preferences (Safe Mode default, shuffle, repeat, gapless toggle, ReplayGain settings, default homepage, Dynamic Theme toggle, artwork style, spectrum style/sparks, opt-in diagnostic logging flag, SAF URIs, per-scope sort state, and last queue context) are stored locally in Jetpack DataStore Preferences.

---

## Tech Stack and Build Requirements

- **Application ID / Namespace:** `com.cangkir.bitperfectplayer` (`versionName = "1.3"`, `versionCode = 4`)
- **SDK Levels:** `minSdk = 26` (Android 8.0), `compileSdk = 37`, `targetSdk = 37`
- **Toolchain:** Gradle Wrapper `9.9.1`, Gradle Daemon JVM `25`, Java Compatibility `JavaVersion.VERSION_11`
- **Core Stack:** Android Gradle Plugin `9.5.2`, Kotlin `2.4.21`, KSP `2.3.14`
- **Code Quality & Static Analysis (Build-Time Only):** `ktlint` Gradle plugin `14.2.0` (MIT, [`.editorconfig`](.editorconfig)) and `detekt` `2.0.0-alpha.6` (Apache 2.0, [`config/detekt/detekt.yml`](config/detekt/detekt.yml))
- **AndroidX & Libraries (all permissive Apache 2.0 / MIT dependencies; no FFmpeg, NDK, or GPL/LGPL libraries):**
  - Jetpack Compose BOM `2026.09.00` (Material 3, UI, Material Icons Extended)
  - Navigation Compose `2.9.8`
  - Room `2.8.5` (`room-runtime`, `room-ktx`, `room-compiler`)
  - DataStore Preferences `1.1.7`
  - DocumentFile `1.1.0`
  - Lifecycle (`runtime-ktx`, `runtime-compose`, `viewmodel-compose`) `2.9.4`
  - Activity Compose `1.10.1`, Core KTX `1.16.0`
  - Kotlinx Coroutines `1.10.2`
  - Testing: JUnit `4.13.2`, Coroutines Test `1.10.2`, Robolectric `4.18.1` (`@Config(sdk = [34])`), Compose UI Test JUnit4

---

## Building and Testing

### Prerequisites

- Android SDK with platform `android-37` installed (configured via `ANDROID_HOME` / `ANDROID_SDK_ROOT` or an untracked `local.properties` file).
- JDK 25 (or Gradle toolchain provisioning configured in `gradle/gradle-daemon-jvm.properties`).

### Common Commands

```bash
# Run Kotlin formatting (ktlint) and static analysis (detekt)
./gradlew ktlintCheck detekt

# Auto-format Kotlin sources according to .editorconfig
./gradlew ktlintFormat

# Run all JVM unit tests, Room SQLite migration tests, and headless Compose UI tests
./gradlew testDebugUnitTest

# Run Android Lint on the debug variant
./gradlew lintDebug

# Build the debug APK (app/build/outputs/apk/debug/app-debug.apk)
./gradlew assembleDebug

# Build the R8-optimized unsigned release APK (app/build/outputs/apk/release/app-release-unsigned.apk)
./gradlew assembleRelease
```

To verify that no dependency merges `INTERNET`, `RECORD_AUDIO`, `MANAGE_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE`, or `READ_MEDIA_IMAGES` into the debug or release manifests after building:

```bash
grep -E "INTERNET|RECORD_AUDIO|MANAGE_EXTERNAL_STORAGE|WRITE_EXTERNAL_STORAGE|READ_MEDIA_IMAGES" \
  app/build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml \
  app/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml
```

(Should return no matches.)

### Side-by-Side Debug & Release Variants

| Variant | Application ID | Version Name | Launcher Label | Provider Authorities (`FileProvider` / Startup) |
| :--- | :--- | :--- | :--- | :--- |
| **`debug`** | `com.cangkir.bitperfectplayer.debug` | `<versionName>-dev` | **BitPerfect Dev** | `com.cangkir.bitperfectplayer.debug.*` |
| **`release`** | `com.cangkir.bitperfectplayer` | `<versionName>` | **BitPerfectPlayer** | `com.cangkir.bitperfectplayer.*` |

Debug builds install cleanly alongside the signed production release APK on the same device without signature or provider authority conflicts (`adb shell monkey -p com.cangkir.bitperfectplayer.debug 1` / `adb logcat --pid=$(adb shell pidof -s com.cangkir.bitperfectplayer.debug)`).

---

## Verification Scope and Hardware Safety Notice

- **Automated Software Verification:** Core pure-Kotlin domain logic (including `AudioStartPathPolicy`, `PlaybackCommandCoalescer`, `UsbMixerProfileSelector`, `AudioRouteEventFilter`, `RouteChangeReconfigurePolicy`, `PreferredMixerSessionTracker`, `TrackTransitionStatePolicy`, `RootPagerPolicy`, `NowPlayingSwipeMath`, `MiniPlayerSwipeMath`, `PlaylistMembershipPolicy`, `ThemeArtworkCandidateSelector`, `MetadataScanPolicy`, `PcmStreamRules`, `TurntableFlushHeadTracker`, `QueueDragReorderMath`, `DefaultHomepage`, `LibraryTab`, `AlbumArtworkHelper`, `CoverThemePaletteGenerator`, `EmbeddedArtworkParser`, `ReleaseYearParser`, `ArtistDetailHelper`, `FolderArtworkSelector`, `NotificationSubtitleFormatter`, `NotificationArtworkHelper`, `MediaSessionCustomActions`, `MediaSessionUpdatePolicy`, `DiagnosticLogFormatter`, `DiagnosticLogRotationPolicy`, `DiagnosticLogBuffer`, `ForegroundPlaybackGate`, and `WindowLayoutMetrics`), Room v1-to-v5 schema migrations, Robolectric native-Skia bitmap letterboxing & custom artist photo EXIF storage, and headless Compose UI behaviors (including real hoisted top-level `[Library] <-> [Playlists] <-> [Settings]` + root `[Content] <-> [Now Playing] <-> [Settings]` `HorizontalPager` navigation without bottom-nav state jumps, `touchSlop`-aware `PageExclusionGuardBox` 120 Hz slider dragging & control protection, `MiniPlayerBar` upward swipe-to-open & smooth `140 ms` leftward swipe-to-dismiss session clearing, floating non-shifting auto-pause alert banner, stationary 2-line-slot `NowPlaying` transport controls, full-screen `Settings` navigation-bar inset clearance, synced gapless `engineState` seek-slider scrubbing, the mandatory `HearingSafetyWarningDialog` gate, icon-only top-level navigation & `NowPlayingUtilityStrip`, bidirectional `HorizontalPager` swipe across all 6 Library sub-tabs with `Folders` rightmost, playlist `DragHandle` reordering, duplicate-track protection + `"Added"` badge, `Remove + Undo` Snackbars, root-hoisted `Dynamic Theme` propagation, messy-metadata & zero-duration guards, `250 ms` delayed preparing indicator & zero Play-icon flicker during track switches, Vinyl `DISC` neutral loading placeholder & intra-album slot caching, selective top-bar passive Queue navigation with `Artist Detail` exclusion, compact non-truncating 2-line kHz `MiniPlayerBar` audio spec, immediate track-switch `NowPlayingScreen` spec/duration/position seeding, `DEFAULT_NATIVE_RATE` vs fallback diagnostics & `ReplayGain: Bypassed (Bit-Perfect Opt-In)`, non-truncating `Add Folder` / `Refresh` Settings buttons on `360dp`, full-list `All Songs` search playback, `DefaultHomepage` cold startup & config-change restoration, chronological oldest-first Artist Detail + parent backstack, unified 4-tier `Artists` tab artwork, `Folders` tab Front Cover thumbnails + `Folder Detail`, Back Cover flip + Vinyl `DISC` art, opt-in `DIAGNOSTIC LOGGING` settings controls, and adaptive `NavigationRail` / 2-column `NowPlayingScreen` layouts) are covered by **412 automated tests across 63 suites** under `./gradlew testDebugUnitTest`, alongside `ktlintCheck`, `detekt`, Android Lint, and R8 release builds.
- **Physical Hardware Limits:** Runtime behavior of `AudioManager.MIXER_BEHAVIOR_BIT_PERFECT` and `MIXER_BEHAVIOR_DEFAULT` sample-rate negotiation, Android 16 / OEM `audio/raw` and `MediaCodec` 24-bit output support, UAC hardware volume attenuation, internal USB-C DAC firmware processing, and OEM SystemUI media notification button slots depend on the target device's Android Audio HAL, SystemUI skin, and DAC hardware. The reference hardware profile used during specification is a POCO F4 (Android 14 / HyperOS) paired with a KZ EDX Pro 2 USB-C IEM (107 dB/mW at 24 ohms).
- **Safety Warning:** Before toggling **Bit-Perfect Opt-In** on an unfamiliar phone or USB-C DAC, keep high-sensitivity earphones out of your ears until you confirm whether volume buttons attenuate output or the DAC plays at fixed 100% full scale.

---

## Repository Documentation

- [`SPEC.md`](SPEC.md) -- Full product specification, locked audio architecture, and 6-stage verification contract
- [`DECISIONS.md`](DECISIONS.md) -- Architectural decisions and rationale log
- [`docs/TASKS.md`](docs/TASKS.md) -- MVP execution checklist
- [`docs/HANDOFF_REPORT.md`](docs/HANDOFF_REPORT.md) -- Build verification and implementation history report
- [`CONTRIBUTING.md`](CONTRIBUTING.md) -- Contribution guidelines, architectural invariants, and local verification steps
- [`SECURITY.md`](SECURITY.md) -- Security, privacy, and hearing-safety vulnerability reporting policy

---

## License

Copyright 2026 Endricho Folabessy.

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this repository except in compliance with the License. You may obtain a copy of the License at `http://www.apache.org/licenses/LICENSE-2.0`, or see [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE) in this repository.
