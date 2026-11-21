package com.cangkir.bitperfectplayer.diagnostics

import java.util.Locale

/**
 * Severity levels for structured single-line local diagnostic log records.
 */
enum class DiagnosticLogLevel(val tag: String) {
    INFO("INFO"),
    WARN("WARN"),
    ERROR("ERROR"),
    CRASH("CRASH"),
}

/**
 * Four bounded local diagnostic log files separating device configuration, audio pipeline events,
 * coarse aggregated performance metrics, and errors/crashes so logs never pile into one giant file.
 *
 * Pure Kotlin (zero `android.*` / `androidx.*` imports).
 */
enum class DiagnosticLogChannel(val fileName: String, val tag: String, val overwritesSnapshot: Boolean) {
    DEVICE_SUMMARY(
        fileName = "device_summary.txt",
        tag = "DEVICE",
        overwritesSnapshot = true,
    ),
    AUDIO_PIPELINE(
        fileName = "audio_pipeline.txt",
        tag = "AUDIO",
        overwritesSnapshot = false,
    ),
    PERFORMANCE(
        fileName = "performance.txt",
        tag = "PERF",
        overwritesSnapshot = false,
    ),
    ERRORS_AND_CRASHES(
        fileName = "errors_and_crashes.txt",
        tag = "ERROR",
        overwritesSnapshot = false,
    ),
    ;

    /**
     * Rotated backup filename (`<base>.prev.txt`) for append-only channels, or `null` for
     * single-snapshot channels like [DEVICE_SUMMARY].
     */
    val backupFileName: String?
        get() = if (overwritesSnapshot) {
            null
        } else {
            "${fileName.removeSuffix(".txt")}.prev.txt"
        }

    companion object {
        val ALL_VALID_FILE_NAMES: Set<String> = buildSet {
            entries.forEach { channel ->
                add(channel.fileName)
                channel.backupFileName?.let { add(it) }
            }
        }
    }
}

/**
 * Pure-Kotlin snapshot of currently stored local diagnostic log files.
 */
data class DiagnosticLogStats(val fileCount: Int = 0, val totalBytes: Long = 0L, val lastUpdatedEpochMs: Long = 0L) {
    val hasLogs: Boolean
        get() = fileCount > 0 && totalBytes > 0L

    fun formatSummary(): String {
        if (!hasLogs) return "No logs stored"
        val fileLabel = if (fileCount == 1) "1 file" else "$fileCount files"
        return "$fileLabel · ${formatByteSize(totalBytes)}"
    }

    companion object {
        fun formatByteSize(bytes: Long): String {
            val safe = bytes.coerceAtLeast(0L)
            return when {
                safe < 1024L -> "$safe B"
                safe < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", safe / 1024.0)
                else -> String.format(Locale.US, "%.2f MB", safe / (1024.0 * 1024.0))
            }
        }
    }
}
