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
