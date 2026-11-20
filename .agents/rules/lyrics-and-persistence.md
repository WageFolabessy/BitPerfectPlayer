---
trigger: always_on
description: "Apply whenever implementing or modifying SHA-256 content fingerprinting, .lrc lyrics parsing, synced lyrics highlighting, Room/SQLite persistence, or DataStore preferences."
---

# Lyrics Fingerprinting & Local Persistence Constraints

1. **Deterministic Content Fingerprint (Primary Key):**
   - Never bind persisted lyrics solely to track title or `MediaStore._ID`.
   - Compute a lowercase 64-character hex `SHA-256` deterministically:
     - If `fileSize <= 131072` bytes (128 KB): `SHA-256(entire file bytes)`.
     - If `fileSize > 131072` bytes: `SHA-256(first 65536 bytes + last 65536 bytes + total file size encoded as 8-byte big-endian unsigned integer)`.
   - **OOM-Safe Reading:** Keep `ContentFingerprintGenerator` pure Kotlin (zero `android.*` imports) accepting an abstract seekable source or stream. For Android `content://` URIs, prefer random-access seeking via `ParcelFileDescriptor` / `FileChannel` to read only the head and tail 64 KB chunks without loading large FLAC/WAV files into RAM, with a fixed-size 64 KB ring-buffer streaming fallback if a provider is non-seekable.
   - Store filename, file size, and duration alongside the fingerprint as diagnostic metadata.
2. **Fault-Tolerant Offline `.lrc` Parser:**
   - Keep `LrcParser` and active-line resolution pure Kotlin (zero `android.*` imports) for fast JVM unit tests.
   - Support `[mm:ss.xx]`, `[mm:ss.xxx]`, multiple timestamps per line, metadata tags (`[ti:]`, `[ar:]`, `[al:]`), and `[offset:]` in milliseconds.
   - Decode byte arrays handling `UTF-8`, `UTF-8 BOM`, `UTF-16LE`, `UTF-16BE`, and `ISO-8859-1` fallback.
   - Never crash on corrupted timestamps, empty files, or untimestamped plain text (render untimestamped text cleanly as static scrollable lyrics).
   - Active line highlighting must derive strictly from the audio engine's actual playback clock (`currentPositionMs`), not an independent UI timer.
3. **Local Database & Preferences:**
   - Prefer Room (`room-runtime`, `room-ktx`, `room-compiler` via KSP) + DataStore Preferences (`datastore-preferences`).
   - **Toolchain Fallback:** If KSP/Room hits an irreconcilable compatibility conflict with AGP/Kotlin/SDK 37 in the build environment, document the exact error in `DECISIONS.md` and implement the same clean DAO/repository contracts using Android's built-in `SQLiteOpenHelper`.
   - Persist default preferences with Safe Mode active.