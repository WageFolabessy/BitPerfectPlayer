package com.cangkir.bitperfectplayer.diagnostics

import java.io.File
import java.io.FileOutputStream

/**
 * Pure-Kotlin bounded rotation and storage policy for local diagnostic log files
 * (zero `android.*` / `androidx.*` imports).
 *
 * Guarantees:
 * - Each append-only channel caps its active file at [MAX_CHANNEL_BYTES] (256 KB) and keeps at
 *   most 1 rotated backup (`<base>.prev.txt`).
 * - [DiagnosticLogChannel.DEVICE_SUMMARY] overwrites a single snapshot file atomically.
 * - Total local disk footprint stays strictly bounded well below 2 MB.
 */
object DiagnosticLogRotationPolicy {
    const val MAX_CHANNEL_BYTES: Long = 256L * 1024L
    const val MAX_BACKUP_FILES_PER_CHANNEL: Int = 1

    fun shouldRotate(currentFileSizeBytes: Long, incomingBytes: Int, maxChannelBytes: Long = MAX_CHANNEL_BYTES): Boolean {
        if (currentFileSizeBytes <= 0L || maxChannelBytes <= 0L) return false
        return (currentFileSizeBytes + incomingBytes.coerceAtLeast(0).toLong()) > maxChannelBytes
    }

    fun writeChannelPayload(logDir: File, channel: DiagnosticLogChannel, payload: String, maxChannelBytes: Long = MAX_CHANNEL_BYTES) {
        if (payload.isEmpty()) return
        if (!logDir.exists() && !logDir.mkdirs()) return

        val activeFile = File(logDir, channel.fileName)
        if (channel.overwritesSnapshot) {
            writeSnapshotAtomically(logDir, activeFile, payload)
            return
        }

        val lineWithNewline = if (payload.endsWith('\n')) payload else "$payload\n"
        val bytes = lineWithNewline.toByteArray(Charsets.UTF_8)

        if (activeFile.isFile && shouldRotate(activeFile.length(), bytes.size, maxChannelBytes)) {
            rotateActiveToBackup(logDir, channel, activeFile)
        }

        FileOutputStream(activeFile, true).use { stream ->
            stream.write(bytes)
            stream.flush()
        }
    }

    fun computeStorageStats(logDir: File): DiagnosticLogStats {
        if (!logDir.isDirectory) return DiagnosticLogStats()
        var count = 0
        var totalBytes = 0L
        var latestModified = 0L
        for (file in listExportableFiles(logDir)) {
            count++
            totalBytes += file.length()
            if (file.lastModified() > latestModified) {
                latestModified = file.lastModified()
            }
        }
        return DiagnosticLogStats(
            fileCount = count,
            totalBytes = totalBytes,
            lastUpdatedEpochMs = latestModified,
        )
    }

    fun listExportableFiles(logDir: File): List<File> {
        if (!logDir.isDirectory) return emptyList()
        val ordered = ArrayList<File>(8)
        for (channel in DiagnosticLogChannel.entries) {
            val active = File(logDir, channel.fileName)
            if (active.isFile && active.length() > 0L) {
                ordered.add(active)
            }
            val backupName = channel.backupFileName
            if (backupName != null) {
                val backup = File(logDir, backupName)
                if (backup.isFile && backup.length() > 0L) {
                    ordered.add(backup)
                }
            }
        }
        return ordered
    }

    fun clearAll(logDir: File, exportCacheDir: File? = null) {
        if (logDir.isDirectory) {
            logDir.listFiles()?.forEach { file ->
                if (file.isFile && (file.name in DiagnosticLogChannel.ALL_VALID_FILE_NAMES || file.name.endsWith(".tmp"))) {
                    file.delete()
                }
            }
        }
        if (exportCacheDir != null && exportCacheDir.isDirectory) {
            exportCacheDir.listFiles()?.forEach { file ->
                if (file.isFile && file.name.endsWith(".zip")) {
                    file.delete()
                }
            }
        }
    }

    private fun writeSnapshotAtomically(logDir: File, targetFile: File, payload: String) {
        val bytes = (if (payload.endsWith('\n')) payload else "$payload\n").toByteArray(Charsets.UTF_8)
        val tempFile = File(logDir, "${targetFile.name}.tmp")
        FileOutputStream(tempFile, false).use { stream ->
            stream.write(bytes)
            stream.flush()
        }
        if (!tempFile.renameTo(targetFile)) {
            if (targetFile.exists()) targetFile.delete()
            if (!tempFile.renameTo(targetFile)) {
                FileOutputStream(targetFile, false).use { stream ->
                    stream.write(bytes)
                    stream.flush()
                }
                tempFile.delete()
            }
        }
    }

    private fun rotateActiveToBackup(logDir: File, channel: DiagnosticLogChannel, activeFile: File) {
        val backupName = channel.backupFileName ?: return
        val backupFile = File(logDir, backupName)
        if (backupFile.exists()) {
            backupFile.delete()
        }
        if (!activeFile.renameTo(backupFile)) {
            // Fallback when rename fails across filesystem boundaries: copy then truncate active.
            runCatching {
                activeFile.copyTo(backupFile, overwrite = true)
                activeFile.delete()
            }
        }
    }
}
