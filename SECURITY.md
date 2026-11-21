# Security and Safety Policy

## Supported Versions

| Version | Supported |
| :--- | :--- |
| `1.x` | Yes |

---

## Scope

In addition to standard Android application security, **BitPerfectPlayer** treats the following areas as high-priority security and safety issues:

1. **Offline Privacy Boundaries:** Any regression or dependency change that introduces `INTERNET`, `RECORD_AUDIO`, or `MANAGE_EXTERNAL_STORAGE` permissions, or causes network egress.
2. **Hearing Safety Controls:** Any bug that bypasses `HearingSafetyWarningDialog`, auto-enables `BIT_PERFECT_OPT_IN` on USB plug-in, leaks `MIXER_BEHAVIOR_BIT_PERFECT` attributes across sessions, enables digital volume scaling in Bit-Perfect mode, or fails to auto-pause on USB disconnect or `ACTION_AUDIO_BECOMING_NOISY`.
3. **Untrusted Local File Parsing:** Crashes, memory exhaustion (OOM), path traversal, or parser flaws when reading untrusted audio headers (RIFF/WAV, FLAC, ReplayGain tags), `.lrc` lyrics files, `.m3u8` playlists, or `.json` backup files via Storage Access Framework or `MediaStore`.

---

## Reporting a Vulnerability

Please **do not** open a public GitHub issue for unpatched security, privacy, or hearing-safety vulnerabilities.

Report issues privately through GitHub's **Private Vulnerability Reporting** feature under the **Security** tab of this repository (`https://github.com/WageFolabessy/BitPerfectPlayer/security/advisories/new`).

Please include:
- A clear description of the issue and potential impact (privacy, parser safety, or hearing safety).
- Steps to reproduce, affected Android version / hardware (if device-specific), or a minimal sample input/test case.
- Any suggested remediation if known.
