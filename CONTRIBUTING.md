# Contributing to BitPerfectPlayer

Thank you for your interest in contributing to **BitPerfectPlayer** (`com.cangkir.bitperfectplayer`). Because this project handles high-sensitivity audio hardware and strictly enforces a 100% offline privacy model, all contributions must preserve the architectural and safety invariants below.

---

## Development Environment

- **JDK:** 25 (configured in `gradle/gradle-daemon-jvm.properties`)
- **Android SDK:** `compileSdk = 37`, `targetSdk = 37`, `minSdk = 26`
- **Build System:** Gradle Wrapper (`9.9.1`), Android Gradle Plugin `9.5.2`, Kotlin `2.4.21`, KSP `2.3.14`
- **Code Quality & Static Analysis (Build-Time Only):** `ktlint` Gradle plugin `14.2.0` (configured via [`.editorconfig`](.editorconfig)) and `detekt` `2.0.0-alpha.6` (configured via [`config/detekt/detekt.yml`](config/detekt/detekt.yml))
- **Local SDK Config:** Set `ANDROID_HOME` / `ANDROID_SDK_ROOT`, or keep an untracked `local.properties` file pointing to your local Android SDK. Never commit `local.properties` or `.idea/` IDE state.

---

## Mandatory Project Invariants

1. **100% Offline and Privacy-First:**
   - Never add `android.permission.INTERNET`, `android.permission.MANAGE_EXTERNAL_STORAGE`, or `android.permission.RECORD_AUDIO` to `app/src/main/AndroidManifest.xml` or transitively via dependencies.
   - Do not add networking libraries, crash reporters, telemetry, analytics, or online metadata/artwork/lyrics fetchers.
   - Only add dependencies with permissive open-source licenses (**Apache 2.0, MIT, BSD**). Never introduce FFmpeg, heavy NDK toolchains, or GPL/LGPL dependencies.

2. **Hearing Safety Rules (Non-Negotiable):**
   - **Safe Mode (`SAFE_VOLUME_MODE`)** must remain the default on fresh install and whenever any USB audio device connects.
   - **Bit-Perfect Opt-In (`BIT_PERFECT_OPT_IN`)** must never auto-enable and must remain gated behind `HearingSafetyWarningDialog` whenever hardware volume support is unverified.
   - Immediate auto-pause and `AudioManager.clearPreferredMixerAttributes()` cleanup (guarded for API 34+) must occur on USB disconnect/reconnect, route changes, or `AudioManager.ACTION_AUDIO_BECOMING_NOISY`.
   - Digital ReplayGain, `AudioTrack.setVolume(...)`, and live turntable scratch DSP must be strictly isolated to `SAFE_VOLUME_MODE` and unconditionally bypassed in `BIT_PERFECT_OPT_IN`.

3. **Honest Audio Diagnostics:**
   - Keep the 6-stage audio path breakdown accurate and label metrics honestly as `VERIFIED`, `INDICATED`, or `UNVERIFIABLE`.
   - Never claim bit-perfect output on fallback paths, non-USB routes, or API < 34, and preserve the internal DAC firmware verification boundary notice.

4. **Pure-Kotlin Domain Boundary and Database Safety:**
   - Keep core domain modules (content fingerprinting, `.lrc` parsing, WAV header/PCM parsing, playback queue, safety state machine, diagnostics mapping, ReplayGain, sleep timer, sort policy, backup JSON codec, `.m3u8` codec, turntable DSP, FFT spectrum DSP, foreground playback gate, adaptive layout metrics) free of `android.*` imports so they remain fast and deterministic under JVM unit tests.
   - Any Room schema changes in `BitPerfectDatabase` must include explicit non-destructive SQLite `Migration` objects and migration unit tests (never use `fallbackToDestructiveMigration()`).

5. **120 Hz Compose UI Hygiene:**
   - Collect high-frequency `StateFlow` streams (`playbackPositionMs`, `sleepTimerState`) only inside narrow leaf composables.
   - Keep library sorting, artwork decoding, and file I/O off the main thread.
   - Maintain a minimum `48.dp` touch target and clear `contentDescription` labels on interactive controls.

---

## Local Verification Checklist

Before opening a pull request, run the full software verification suite locally (use `./gradlew ktlintFormat` to auto-format Kotlin sources first if needed):

```bash
# 1. Kotlin formatting (ktlint) and static analysis (detekt) — must report 0 issues
./gradlew ktlintCheck detekt

# 2. JVM unit tests, Room SQLite migration tests, and Robolectric Compose UI tests
./gradlew testDebugUnitTest

# 3. Android Lint checks
./gradlew lintDebug

# 4. Debug APK build
./gradlew assembleDebug

# 5. Verify zero forbidden permissions in source and merged manifests
grep -Eni "permission\.(INTERNET|MANAGE_EXTERNAL_STORAGE|RECORD_AUDIO)" \
  app/src/main/AndroidManifest.xml \
  $(find app/build/intermediates/merged_manifest* -name 'AndroidManifest.xml' 2>/dev/null)
```

(Step 5 must return zero matches.)

---

## Pull Request Guidelines

- **Commit Format:** Follow Conventional Commits (`feat:`, `fix:`, `refactor:`, `perf:`, `test:`, `docs:`, `chore:`).
- **Tests:** Include JVM unit tests (or Robolectric UI tests with `@Config(sdk = [34])`) covering normal paths, edge cases, and invalid inputs. Never delete, `@Ignore`, or weaken existing tests.
- **Documentation & Verification Logs:** Keep [`SPEC.md`](SPEC.md), [`DECISIONS.md`](DECISIONS.md), [`docs/TASKS.md`](docs/TASKS.md), and [`docs/HANDOFF_REPORT.md`](docs/HANDOFF_REPORT.md) synchronized when changing architecture, constraints, or release milestones.
- **Verification Honesty:** Clearly state in your PR description which checks were run in software (`ktlintCheck`, `detekt`, `testDebugUnitTest`, `lintDebug`, `assembleDebug`) vs. verified on physical Android hardware and USB-C DACs. Never fabricate hardware test results.
