# Architectural & Implementation Decisions (`BitPerfectPlayer`)

This file records autonomous architectural, dependency, and environment decisions made during MVP implementation, including rationale and rejected alternatives.

---

## D0.1 — Build Environment Provisioning & `gradlew` Executable Bit (`T0.1`)
- **Decision:** Provisioned Temurin JDK 25 (`25.0.3+9`, matching `gradle/gradle-daemon-jvm.properties` `toolchainVersion=25`) and Android SDK 37 (`platforms;android-37.0`, `build-tools;37.0.0`, `platform-tools`) outside the repository in `/home/antigravity/`, pointed untracked/gitignored `local.properties` at the local SDK directory, and set the executable mode (`+x`) on `gradlew`.
- **Rationale:** Preserves the exact Android Studio project baseline (`compileSdk { version = release(37) }`, `targetSdk = 37`, `minSdk = 26`, AGP `9.4.1`, Kotlin `2.2.10`, Gradle `9.6.0`) without downgrading SDK levels or modifying Gradle wrapper/daemon files. Marking `gradlew` executable allows `./gradlew` invocations to succeed directly on Linux/CI hosts.
- **Rejected Alternatives:** Downgrading `compileSdk` or `toolchainVersion` (prohibited by workspace invariants); committing `local.properties` (prohibited by Git hygiene rules).
