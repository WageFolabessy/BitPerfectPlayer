package com.cangkir.bitperfectplayer.diagnostics

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalDiagnosticLoggerTest {

    private lateinit var context: Context
    private lateinit var logger: LocalDiagnosticLogger

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        logger = LocalDiagnosticLogger(context)
        logger.clearAllLogs()
    }

    @Test
    fun disabledByDefault_writesZeroFilesAndEvaluatesZeroLogLambdas() {
        assertFalse(logger.isEnabled)
        var lambdaEvaluated = false

        logger.logAudio("TRACK_OPEN") {
            lambdaEvaluated = true
            mapOf("fp" to "12345678")
        }
        logger.logPerf("COLD_START") {
            lambdaEvaluated = true
            mapOf("ms" to 100L)
        }
        logger.writeDeviceSummary(mapOf("audioMode" to "SAFE_VOLUME_MODE"))
        logger.writeCrashSync("main", IllegalStateException("Simulated crash while disabled"))
        logger.flushSync()

        assertFalse("Log field lambda must never run when disabled", lambdaEvaluated)
        assertFalse(logger.getStorageStats().hasLogs)
        assertTrue(DiagnosticLogRotationPolicy.listExportableFiles(logger.logDir).isEmpty())
    }

    @Test
    fun enabledLogger_writesFourChannelsExportsValidZipAndClearsCleanly() = runTest {
        logger.setEnabled(true)
        logger.writeDeviceSummarySync(mapOf("audioMode" to "SAFE_VOLUME_MODE"))
        logger.logAudio("TRACK_OPEN") { mapOf("fp" to "abcdef01", "ext" to "flac") }
        logger.logPerf("TRACK_LOAD_LATENCY") { mapOf("loadMs" to 48L) }
        logger.logError("DECODER_OPEN_ERROR") { mapOf("errorType" to "IoError") }
        logger.flushSync()

        val stats = logger.getStorageStats()
        assertTrue(stats.hasLogs)
        assertEquals(4, stats.fileCount)

        val exporter = DiagnosticLogExporter(context, logger)
        val outputZip = File(context.cacheDir, "test-export.zip")
        val exportResult = exporter.exportToUri(Uri.fromFile(outputZip))
        assertTrue("Expected ZIP export success: $exportResult", exportResult.isSuccess)
        assertTrue(outputZip.isFile && outputZip.length() > 0L)

        val entryNames = mutableListOf<String>()
        var readmeContent = ""
        ZipInputStream(FileInputStream(outputZip)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                entryNames.add(entry.name)
                if (entry.name == "README_LOGS.txt") {
                    readmeContent = zis.readBytes().toString(Charsets.UTF_8)
                }
                zis.closeEntry()
            }
        }
        assertTrue(entryNames.contains("README_LOGS.txt"))
        assertTrue(entryNames.contains("device_summary.txt"))
        assertTrue(entryNames.contains("audio_pipeline.txt"))
        assertTrue(entryNames.contains("performance.txt"))
        assertTrue(entryNames.contains("errors_and_crashes.txt"))
        assertTrue(readmeContent.contains("Privacy Guarantee:"))

        val afterClear = logger.clearAllLogs()
        assertFalse(afterClear.hasLogs)
        assertEquals(0, afterClear.fileCount)
        outputZip.delete()
    }

    @Test
    fun crashHandler_recordsCrashOnlyWhenEnabledAndAlwaysDelegatesToPreviousHandler() {
        var delegatedCount = 0
        val delegate = Thread.UncaughtExceptionHandler { _, _ -> delegatedCount++ }
        val handler = DiagnosticCrashHandler(logger = logger, previousHandler = delegate)

        // While disabled -> delegates without writing any crash file
        handler.uncaughtException(Thread.currentThread(), RuntimeException("disabled-boom"))
        assertEquals(1, delegatedCount)
        assertFalse(File(logger.logDir, DiagnosticLogChannel.ERRORS_AND_CRASHES.fileName).exists())

        // While enabled -> writes structured CRASH block AND delegates
        logger.setEnabled(true)
        handler.uncaughtException(Thread.currentThread(), IllegalStateException("enabled-boom"))
        assertEquals(2, delegatedCount)

        val crashFile = File(logger.logDir, DiagnosticLogChannel.ERRORS_AND_CRASHES.fileName)
        assertTrue(crashFile.isFile)
        val content = crashFile.readText()
        assertTrue(content.contains("--- CRASH_START ---"))
        assertTrue(content.contains("enabled-boom"))
        assertTrue(content.contains("--- CRASH_END ---"))
    }

    @Test
    fun exporter_whenEmptyOrDisabled_returnsGracefulErrorWithoutCreatingZip() = runTest {
        assertFalse(logger.isEnabled)
        val exporter = DiagnosticLogExporter(context, logger)
        val outputZip = File(context.cacheDir, "should-not-exist.zip").apply { delete() }

        val exportRes = exporter.exportToUri(Uri.fromFile(outputZip))
        assertTrue(exportRes.isFailure)
        assertTrue(exportRes.exceptionOrNull()?.message?.contains("No diagnostic logs stored") == true)
        assertFalse("Empty export must not leave a corrupt zip file", outputZip.exists())

        val shareRes = exporter.prepareShareIntent()
        assertTrue(shareRes.isFailure)
        assertTrue(shareRes.exceptionOrNull()?.message?.contains("No diagnostic logs stored") == true)
    }
}
