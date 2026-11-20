---
name: android-audio-verify
description: Runs Gradle JVM unit tests, Android Lint, debug APK builds, source and merged AndroidManifest privacy audits (verifying zero INTERNET or MANAGE_EXTERNAL_STORAGE permissions), and Git cleanliness checks for com.cangkir.bitperfectplayer. Use before checkpointing milestones or completing final handoff.
---

# Android Software Verification & Privacy Audit Skill

Use this workflow to verify software health without touching ADB, emulators, or physical devices.

## Verification Steps

1. **JVM Unit Tests:**
   ```bash
   ./gradlew testDebugUnitTest
   ```
   - Confirm exit code `0` and verify no test cases were skipped or `@Ignore`d.

2. **Android Lint (Required for Final Audit & Major Milestones):**
   ```bash
   ./gradlew lintDebug
   ```
   - Resolve all error-level lint findings (especially API level guards for `API 31+` PCM encodings and `API 34+` mixer/foreground-service calls under `minSdk = 26`).

3. **Debug APK Build:**
   ```bash
   ./gradlew assembleDebug
   ```
   - Confirm `app/build/outputs/apk/debug/app-debug.apk` exists and is non-empty.

4. **Strict Offline Privacy Audit (Source + Merged Manifest):**
   Run this deterministic check (must exit `0` with zero matches):
   ```bash
   set -euo pipefail
   MANIFESTS="app/src/main/AndroidManifest.xml $(find app/build/intermediates/merged_manifest* -name 'AndroidManifest.xml' 2>/dev/null || true)"
   echo "Auditing manifests: $MANIFESTS"
   if grep -Eni "permission\.(INTERNET|MANAGE_EXTERNAL_STORAGE)" $MANIFESTS; then
     echo "FAIL: Forbidden permission detected in manifest!"
     exit 1
   fi
   echo "PASS: Zero INTERNET or MANAGE_EXTERNAL_STORAGE permissions found."
   ```
   - Also verify at least one merged debug `AndroidManifest.xml` was found after `assembleDebug`.
   - If any transitive dependency injects `android.permission.INTERNET`, remove or replace that dependency with an offline-clean alternative; do not leave it unaddressed.

5. **Baseline & Git Hygiene Check:**
   - Verify `app/build.gradle.kts` still preserves `com.cangkir.bitperfectplayer`, `compileSdk { version = release(37) }`, `targetSdk = 37`, `minSdk = 26`, `JavaVersion.VERSION_11`, and the `release { optimization { ... } }` block.
   - Verify via `git status` that `local.properties` and `app/build/` artifacts are untracked/ignored and never staged.