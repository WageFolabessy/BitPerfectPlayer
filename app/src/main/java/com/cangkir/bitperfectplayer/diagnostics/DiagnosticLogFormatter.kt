package com.cangkir.bitperfectplayer.diagnostics

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Pure-Kotlin agent-readable structured `.txt` formatter with defense-in-depth PII / URI / path
 * redaction (zero `android.*` / `androidx.*` imports).
 *
 * Line format:
 * `YYYY-MM-DDTHH:mm:ss.SSSZ <LEVEL> <CHANNEL> <EVENT> key1=val1 key2=val2`
 */
object DiagnosticLogFormatter {
    const val MAX_VALUE_LENGTH = 240
    const val MAX_CRASH_TRACE_CHARS = 12_000

    private val UTC_TIMESTAMP_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).withZone(ZoneOffset.UTC)

    private val KEY_INVALID_CHARS = Regex("[^A-Za-z0-9_.-]")
    private val EVENT_INVALID_CHARS = Regex("[^A-Za-z0-9_.-]")
    private val WHITESPACE_RUN = Regex("[\\r\\n\\t]+")
    private val URI_PATTERN = Regex("(?i)(?:content|file)://[^\\s\"'\\])}>,]+")
    private val STORAGE_PATH_PATTERN = Regex("(?i)/(?:storage|sdcard|mnt|data/(?:media|user/\\d+|data)|emulated)/[^\\s\"'\\])}>,]+")
    private val SAF_DOC_ID_PATTERN = Regex("(?i)\\b(?:primary|[0-9a-f]{4}-[0-9a-f]{4}):[^\\s\"'\\])}>,]+")
    private val HEX_CHARS = Regex("^[0-9a-fA-F]{8,}$")

    private val FORBIDDEN_PII_KEYS: Set<String> = setOf(
        "title",
        "tracktitle",
        "track_title",
        "artist",
        "artistname",
        "artist_name",
        "album",
        "albumname",
        "album_name",
        "filename",
        "file_name",
        "displayname",
        "display_name",
        "folder",
        "foldername",
        "folder_name",
        "uri",
        "trackuri",
        "track_uri",
        "treeuri",
        "tree_uri",
        "filepath",
        "file_path",
    )

    private val ALLOWED_EXTENSIONS: Set<String> = setOf(
        "flac",
        "wav",
        "mp3",
        "m4a",
        "ogg",
        "opus",
        "aac",
    )

    fun formatUtcTimestamp(epochMs: Long): String = UTC_TIMESTAMP_FORMATTER.format(Instant.ofEpochMilli(epochMs.coerceAtLeast(0L)))

    fun formatEntry(
        epochMs: Long,
        level: DiagnosticLogLevel,
        channel: DiagnosticLogChannel,
        event: String,
        fields: Map<String, Any?> = emptyMap(),
    ): String {
        val safeEvent = sanitizeEventName(event)
        val sb = StringBuilder(160)
        sb.append(formatUtcTimestamp(epochMs))
            .append(' ')
            .append(level.tag)
            .append(' ')
            .append(channel.tag)
            .append(' ')
            .append(safeEvent)

        for ((rawKey, rawValue) in fields) {
            val safeKey = sanitizeKey(rawKey)
            if (safeKey.isEmpty()) continue
            val valueStr = if (safeKey.lowercase(Locale.US) in FORBIDDEN_PII_KEYS) {
                "<REDACTED_PII>"
            } else {
                sanitizeValue(rawValue)
            }
            sb.append(' ').append(safeKey).append('=').append(formatFieldValueToken(valueStr))
        }
        return sb.toString()
    }

    fun formatDeviceSummary(epochMs: Long, fields: Map<String, Any?>): String = buildString(512) {
        append("# BitPerfectPlayer Device & Audio Summary\n")
        append("updatedUtc=").append(formatUtcTimestamp(epochMs)).append('\n')
        for ((rawKey, rawValue) in fields) {
            val safeKey = sanitizeKey(rawKey)
            if (safeKey.isEmpty()) continue
            val valueStr = if (safeKey.lowercase(Locale.US) in FORBIDDEN_PII_KEYS) {
                "<REDACTED_PII>"
            } else {
                sanitizeValue(rawValue)
            }
            append(safeKey).append('=').append(formatFieldValueToken(valueStr)).append('\n')
        }
    }

    fun formatCrashBlock(
        epochMs: Long,
        appVersion: String,
        versionCode: Long,
        apiLevel: Int,
        threadName: String,
        stackTrace: String,
    ): String {
        val header = formatEntry(
            epochMs = epochMs,
            level = DiagnosticLogLevel.CRASH,
            channel = DiagnosticLogChannel.ERRORS_AND_CRASHES,
            event = "UNCAUGHT_EXCEPTION",
            fields = linkedMapOf(
                "appVersion" to appVersion,
                "versionCode" to versionCode,
                "api" to apiLevel,
                "thread" to threadName,
            ),
        )
        val sanitizedTrace = sanitizeMultilineTrace(stackTrace)
        return buildString(sanitizedTrace.length + header.length + 64) {
            append("--- CRASH_START ---\n")
            append(header).append('\n')
            append(sanitizedTrace)
            if (!sanitizedTrace.endsWith('\n')) append('\n')
            append("--- CRASH_END ---")
        }
    }

    /**
     * Returns strictly the first 8 lowercase hex characters of a SHA-256 content fingerprint, or
     * `"none"` if absent/malformed. Never exposes track names or paths.
     */
    fun shortFingerprintPrefix(fingerprint: String?): String {
        val cleaned = fingerprint?.trim().orEmpty()
        if (!HEX_CHARS.matches(cleaned)) return "none"
        return cleaned.substring(0, 8).lowercase(Locale.US)
    }

    /**
     * Extracts ONLY an allowlisted lowercase audio container extension (`flac`, `wav`, `mp3`,
     * `m4a`, `ogg`, `opus`, `aac`) or `"unknown"`, ensuring track filenames never leak into logs.
     */
    fun extractSafeFileExtension(fileName: String?, mimeType: String? = null): String {
        val dotExt = fileName?.trim()?.substringAfterLast('.', "")?.lowercase(Locale.US).orEmpty()
        if (dotExt in ALLOWED_EXTENSIONS) return dotExt
        val lowerMime = mimeType?.trim()?.lowercase(Locale.US).orEmpty()
        return when {
            "flac" in lowerMime -> "flac"
            "wav" in lowerMime || "wave" in lowerMime -> "wav"
            "mpeg" in lowerMime || "mp3" in lowerMime -> "mp3"
            "mp4" in lowerMime || "aac" in lowerMime || "m4a" in lowerMime -> "m4a"
            "opus" in lowerMime -> "opus"
            "ogg" in lowerMime || "vorbis" in lowerMime -> "ogg"
            else -> "unknown"
        }
    }

    fun buildExportReadme(appVersion: String, versionCode: Long, exportedAtEpochMs: Long): String = buildString {
        append("BitPerfectPlayer Local Diagnostic Log Export\n")
        append("============================================\n")
        append("App Version: ").append(appVersion).append(" (versionCode=").append(versionCode).append(")\n")
        append("Exported (UTC): ").append(formatUtcTimestamp(exportedAtEpochMs)).append('\n')
        append('\n')
        append("Privacy Guarantee:\n")
        append("- Opt-in local diagnostics only; 100% offline (zero network permissions).\n")
        append("- Never records song titles, artists, albums, filenames, folder names, or raw URIs.\n")
        append("- Tracks are identified strictly by an 8-character SHA-256 prefix plus technical audio metadata.\n")
        append('\n')
        append("Included Channels (active .txt + optional rotated .prev.txt):\n")
        append("1. device_summary.txt      - App build, device model, Android API, and audio/display settings snapshot.\n")
        append(
            "2. audio_pipeline.txt      - Discrete track open, decoder/AudioTrack config, USB mixer, gapless, turntable, & FGS events.\n",
        )
        append("3. performance.txt         - Coarse startup, track load, seek latencies, and AudioTrack underrun deltas.\n")
        append("4. errors_and_crashes.txt  - Decoder/AudioTrack/service errors and uncaught crash blocks (--- CRASH_START/END ---).\n")
    }

    fun redactSensitivePatterns(raw: String): String {
        val withoutUris = URI_PATTERN.replace(raw, "<REDACTED_URI>")
        val withoutPaths = STORAGE_PATH_PATTERN.replace(withoutUris, "<REDACTED_PATH>")
        return SAF_DOC_ID_PATTERN.replace(withoutPaths, "<REDACTED_PATH>")
    }

    /**
     * Redacts URIs, filesystem/SAF paths, and any occurrence of [knownFileName] (e.g. a bare audio
     * track filename embedded inside an exception message) before recording to diagnostic logs.
     */
    fun redactFilenameAndPaths(raw: String?, knownFileName: String? = null): String {
        if (raw.isNullOrBlank()) return "unknown"
        val strippedFile = knownFileName?.trim()?.takeIf { it.isNotEmpty() }?.let { name ->
            raw.replace(name, "<REDACTED_FILE>", ignoreCase = true)
        } ?: raw
        return redactSensitivePatterns(strippedFile)
    }

    private fun sanitizeKey(rawKey: String): String = KEY_INVALID_CHARS.replace(rawKey.trim(), "")

    private fun sanitizeEventName(rawEvent: String): String = EVENT_INVALID_CHARS.replace(rawEvent.trim(), "_").ifEmpty { "EVENT" }

    private fun sanitizeValue(rawValue: Any?): String {
        if (rawValue == null) return "null"
        val singleLine = WHITESPACE_RUN.replace(rawValue.toString(), " ").trim()
        val redacted = redactSensitivePatterns(singleLine)
        return if (redacted.length > MAX_VALUE_LENGTH) {
            redacted.substring(0, MAX_VALUE_LENGTH - 3) + "..."
        } else {
            redacted.ifEmpty { "empty" }
        }
    }

    private fun formatFieldValueToken(sanitized: String): String {
        val needsQuotes = sanitized.any { it == ' ' || it == '=' || it == '"' }
        return if (needsQuotes) {
            "\"${sanitized.replace("\"", "'")}\""
        } else {
            sanitized
        }
    }

    private fun sanitizeMultilineTrace(rawTrace: String): String {
        val normalized = rawTrace.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (normalized.isEmpty()) return "(empty stacktrace)"
        val redacted = redactSensitivePatterns(normalized)
        return if (redacted.length > MAX_CRASH_TRACE_CHARS) {
            redacted.substring(0, MAX_CRASH_TRACE_CHARS) + "\n... (truncated)"
        } else {
            redacted
        }
    }
}
