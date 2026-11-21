# BitPerfectPlayer — Autonomous Workspace Rules

This repository contains the native Android project **`com.cangkir.bitperfectplayer`** (Kotlin + Jetpack Compose). Follow these workspace invariants on every turn without exception.

## Reference Documents
- Full product and architecture specification: @SPEC.md
- Sequential execution checklist: @docs/TASKS.md
- Build verification & handoff report: @docs/HANDOFF_REPORT.md
- Audio architecture & hearing safety rules: @.agents/rules/audio-safety-and-architecture.md
- Gradle, dependencies & manifest rules: @.agents/rules/gradle-and-manifest.md
- Lyrics fingerprinting & persistence rules: @.agents/rules/lyrics-and-persistence.md
- Autonomous execution workflow skill: @.agents/skills/overnight-task-loop/SKILL.md
- Verification and manifest audit skill: @.agents/skills/android-audio-verify/SKILL.md

## 1. Full Autonomy (Zero-Question Invariant)
- **Never stop to ask questions, request approval, or wait for human confirmation.**
- All MVP architectural decisions are already locked in @SPEC.md.
- For minor unspecified details, choose the simplest, most idiomatic Kotlin/Android approach consistent with the repository and create or append the rationale (plus rejected alternatives) in `DECISIONS.md`.

## 2. Software-Only Verification Honesty
- You are operating in a software-only environment with **no physical hardware**.
- **Never run `adb`, launch emulators, or simulate/fabricate hardware test results** for POCO F4 or KZ EDX Pro 2 USB-C.
- Strictly separate:
  1. Compilation results (`./gradlew assembleDebug`),
  2. Automated JVM unit tests, formatting, & static checks (`./gradlew ktlintCheck detekt testDebugUnitTest lintDebug`),
  3. Unverified physical hardware behaviors (real HyperOS Android 14 HAL behavior, runtime `MIXER_BEHAVIOR_BIT_PERFECT` negotiation, real USB-C DAC output, audible playback, and physical plug/unplug auto-pause).
- Never claim bit-perfect output is proven without hardware verification.

## 3. Code, Privacy & Test Integrity Invariants
- **100% Offline:** `android.permission.INTERNET` is strictly prohibited in both `app/src/main/AndroidManifest.xml` and the merged debug manifest. Never add network libraries, telemetry, analytics, `RECORD_AUDIO`, or `MANAGE_EXTERNAL_STORAGE`.
- **Production-only implementation:** No mock audio playback engines, dead buttons, or `TODO()` stubs on production user flows.
- **Pure-Kotlin domain boundary:** Keep core logic modules (SHA-256 content fingerprinting, `.lrc` parser, WAV header/PCM parser, playback queue, hearing safety state machine, and audio diagnostics mapper) free of `android.*` imports so they execute cleanly and deterministically under plain JUnit 4 JVM tests (`./gradlew testDebugUnitTest`).
- **Zero test tampering:** Never delete, `@Ignore`, skip, or weaken assertions in tests to force a green build.
- **Source & test roots:** Place production Kotlin sources under `app/src/main/java/com/cangkir/bitperfectplayer/` and JVM unit tests under `app/src/test/java/com/cangkir/bitperfectplayer/`. Evolve existing `ui/theme/{Color.kt, Theme.kt, Type.kt}` rather than duplicating theme files.

## 4. Circuit Breaker & Git Safety Invariants
- **Maximum 3 fix attempts per task item** in @docs/TASKS.md. If a task still fails after 3 attempts, reset uncommitted code changes cleanly to `HEAD` (preserving untracked `local.properties` and `.idea/` if present), record exact diagnostics in `BLOCKED.md`, and immediately commit and push `BLOCKED.md` so the record is never lost.
- If a foundational blocker occurs (such as missing Android SDK 37 or incompatible Gradle JVM during `T0.1` that cannot be provisioned cleanly), halt cleanly and write, commit, and push `docs/HANDOFF_REPORT.md`.
- **Git discipline:** Work strictly on the active feature branch. Never commit directly to `main`/`master`, never commit `local.properties` or `app/release/`, never delete branches, and never run `git push --force`. If `git push` fails due to a transient remote/network error, keep the local commit (do not reset passing work or trigger the task circuit breaker), retry on the next checkpoint, and record any remaining unpushed commits in `docs/HANDOFF_REPORT.md`.