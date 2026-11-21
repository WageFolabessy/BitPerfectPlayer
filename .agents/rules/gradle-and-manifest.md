---
trigger: always_on
description: "Constraints for Gradle build files, Version Catalog dependencies, keep rules, and AndroidManifest permissions in com.cangkir.bitperfectplayer."
---

# Gradle, Dependencies & Manifest Invariants

1. **Preserve the Android Studio Baseline:**
   - Keep `namespace = "com.cangkir.bitperfectplayer"` and `applicationId = "com.cangkir.bitperfectplayer"`.
   - Keep `compileSdk { version = release(37) }`, `targetSdk = 37`, `minSdk = 26`, and `JavaVersion.VERSION_11`. Never downgrade `compileSdk`/`targetSdk` or rewrite the modern AGP `compileSdk { ... }` DSL.
   - Respect JVM requirements in `gradle/gradle-daemon-jvm.properties`.
   - Preserve `libs.plugins.android.application`, `libs.plugins.kotlin.compose`, the `release { optimization { ... } }` block in `app/build.gradle.kts`, `app/src/main/keepRules/rules.keep`, `app/src/main/res/xml/backup_rules.xml`, and `app/src/main/res/xml/data_extraction_rules.xml`.
2. **Dependency Hygiene & Licensing:**
   - Declare all dependencies and versions cleanly in `gradle/libs.versions.toml` compatible with the project's existing AGP and Kotlin versions.
   - Keep `ktlint` (`.editorconfig`) and `detekt` (`config/detekt/detekt.yml`) strictly as build-time Gradle plugins (permissive MIT / Apache 2.0 licenses, zero runtime dependencies) and preserve zero violations without adding baseline suppression files.
   - Upgrading existing AndroidX dependency versions in `gradle/libs.versions.toml` (such as `coreKtx` to `1.12.0+` for `ServiceCompat.startForeground`, `lifecycleRuntimeKtx`, and `activityCompose`) is permitted as long as they remain compatible with the project's AGP/Kotlin versions, use permissive licenses, and do not transitively introduce `android.permission.INTERNET`.
   - Only add libraries with permissive licenses (**Apache 2.0, MIT, BSD**). Never introduce FFmpeg, heavy NDK bundles, or strict GPL/LGPL libraries.
   - Never add networking libraries or any dependency that transitively merges `android.permission.INTERNET`.
   - For media controls, prefer platform `android.media.session.MediaSession` + `Notification.MediaStyle` (built into API 26+) or offline-clean `androidx.media:media`; avoid pulling heavy `media3-exoplayer` networking modules.
   - Use `androidx.documentfile:documentfile` (Apache 2.0) for clean SAF tree traversal where helpful.
3. **Manifest Permissions (Strict Minimal Set):**
   - Allowed runtime/service permissions: `MODIFY_AUDIO_SETTINGS` (normal install-time permission required by Android 14+ `AudioManager.setPreferredMixerAttributes()` / `clearPreferredMixerAttributes()`, per `DECISIONS.md` `D5.4`), `READ_MEDIA_AUDIO`, `READ_EXTERNAL_STORAGE` (strictly capped with `android:maxSdkVersion="32"`), `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, and `POST_NOTIFICATIONS`.
   - Strictly forbidden: `android.permission.INTERNET`, `android.permission.MANAGE_EXTERNAL_STORAGE`, and `android.permission.RECORD_AUDIO`.
4. **Untracked Local Config & Release Outputs:**
   - Never stage or commit `local.properties` or `app/release/` (signed APK & R8 `mapping.txt` output). Use existing untracked `local.properties` (when present locally) or `ANDROID_HOME` / `ANDROID_SDK_ROOT` environment variables.