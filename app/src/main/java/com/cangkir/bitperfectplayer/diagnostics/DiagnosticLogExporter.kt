package com.cangkir.bitperfectplayer.diagnostics

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class DiagnosticExportResult(val fileCount: Int, val totalLogBytes: Long)

data class DiagnosticShareResult(val shareIntent: Intent, val fileCount: Int, val zipBytes: Long)

/**
 * Zero-permission local `.zip` packager using standard JDK [ZipOutputStream] for:
 * 1. Direct SAF export via `ActivityResultContracts.CreateDocument("application/zip")`.
 * 2. Scoped `FileProvider` sharing via `cacheDir/diagnostic_exports/` and `Intent.ACTION_SEND`.
 */
class DiagnosticLogExporter(context: Context, private val logger: LocalDiagnosticLogger) {
    private val appContext = context.applicationContext

    suspend fun exportToUri(destinationUri: Uri, summaryFields: Map<String, Any?>? = null): Result<DiagnosticExportResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val files = prepareFlushAndCollectFiles(summaryFields)
                if (files.isEmpty()) {
                    throw IllegalStateException(EMPTY_LOGS_MESSAGE)
                }
                val outputStream = appContext.contentResolver.openOutputStream(destinationUri, "wt")
                    ?: throw IllegalStateException("Unable to open destination file for diagnostic logs")
                val totalBytes = outputStream.use { stream ->
                    writeZipArchive(stream, files)
                }
                DiagnosticExportResult(
                    fileCount = files.size,
                    totalLogBytes = totalBytes,
                )
            }
        }

    suspend fun prepareShareIntent(summaryFields: Map<String, Any?>? = null): Result<DiagnosticShareResult> = withContext(Dispatchers.IO) {
        runCatching {
            val files = prepareFlushAndCollectFiles(summaryFields)
            if (files.isEmpty()) {
                throw IllegalStateException(EMPTY_LOGS_MESSAGE)
            }
            val cacheDir = logger.exportCacheDir
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }
            // Remove older cached export archives before writing the fresh zip.
            cacheDir.listFiles()?.forEach { existing ->
                if (existing.isFile && existing.name.endsWith(".zip")) {
                    existing.delete()
                }
            }
            val zipName = suggestZipFileName(logger.appVersion.versionName)
            val zipFile = File(cacheDir, zipName)
            FileOutputStream(zipFile, false).use { out ->
                writeZipArchive(out, files)
            }
            val authority = resolveFileProviderAuthority(appContext.packageName)
            val contentUri = FileProvider.getUriForFile(appContext, authority, zipFile)
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, contentUri)
                putExtra(
                    Intent.EXTRA_SUBJECT,
                    "BitPerfectPlayer v${logger.appVersion.versionName} Diagnostic Logs",
                )
                clipData = ClipData.newRawUri(zipName, contentUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            DiagnosticShareResult(
                shareIntent = Intent.createChooser(sendIntent, "Share Diagnostic Logs"),
                fileCount = files.size,
                zipBytes = zipFile.length(),
            )
        }
    }

    private fun prepareFlushAndCollectFiles(summaryFields: Map<String, Any?>?): List<File> {
        if (logger.isEnabled && summaryFields != null) {
            logger.writeDeviceSummarySync(summaryFields)
        }
        logger.flushSync()
        return DiagnosticLogRotationPolicy.listExportableFiles(logger.logDir)
    }

    private fun writeZipArchive(targetStream: OutputStream, files: List<File>): Long {
        var totalSourceBytes = 0L
        val buffer = ByteArray(ZIP_COPY_BUFFER_BYTES)
        ZipOutputStream(BufferedOutputStream(targetStream)).use { zip ->
            val readmeBytes = DiagnosticLogFormatter.buildExportReadme(
                appVersion = logger.appVersion.versionName,
                versionCode = logger.appVersion.versionCode,
                exportedAtEpochMs = System.currentTimeMillis(),
            ).toByteArray(Charsets.UTF_8)
            zip.putNextEntry(ZipEntry(README_ENTRY_NAME))
            zip.write(readmeBytes)
            zip.closeEntry()

            for (file in files) {
                if (!file.isFile || file.length() <= 0L) continue
                zip.putNextEntry(ZipEntry(file.name))
                FileInputStream(file).use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        zip.write(buffer, 0, read)
                        totalSourceBytes += read.toLong()
                    }
                }
                zip.closeEntry()
            }
            zip.finish()
        }
        return totalSourceBytes
    }

    companion object {
        private const val README_ENTRY_NAME = "README_LOGS.txt"
        private const val ZIP_COPY_BUFFER_BYTES = 8_192
        private const val EMPTY_LOGS_MESSAGE =
            "No diagnostic logs stored yet. Enable Local Diagnostic Logging to record a session."

        fun resolveFileProviderAuthority(packageName: String): String = "$packageName.fileprovider"

        fun suggestZipFileName(appVersionName: String, nowEpochMs: Long = System.currentTimeMillis()): String {
            val safeVer = appVersionName.trim().replace(Regex("[^A-Za-z0-9._-]"), "").ifEmpty { "1.2" }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(nowEpochMs))
            return "bitperfect-logs-v$safeVer-$stamp.zip"
        }
    }
}
