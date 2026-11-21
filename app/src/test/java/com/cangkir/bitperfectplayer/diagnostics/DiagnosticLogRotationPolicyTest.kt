package com.cangkir.bitperfectplayer.diagnostics

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticLogRotationPolicyTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun shouldRotate_respectsThresholdBoundary() {
        assertFalse(DiagnosticLogRotationPolicy.shouldRotate(0L, 100, maxChannelBytes = 50L))
        assertFalse(DiagnosticLogRotationPolicy.shouldRotate(100L, 156, maxChannelBytes = 256L))
        assertTrue(DiagnosticLogRotationPolicy.shouldRotate(100L, 157, maxChannelBytes = 256L))
    }

    @Test
    fun writeChannelPayload_rotatesAtLimitAndKeepsAtMostOnePrevBackup() {
        val logDir = tempFolder.newFolder("diag_logs")
        val maxBytes = 120L

        // Write 3 payloads that force multiple rotations on AUDIO_PIPELINE
        DiagnosticLogRotationPolicy.writeChannelPayload(
            logDir = logDir,
            channel = DiagnosticLogChannel.AUDIO_PIPELINE,
            payload = "FIRST_" + "x".repeat(70),
            maxChannelBytes = maxBytes,
        )
        DiagnosticLogRotationPolicy.writeChannelPayload(
            logDir = logDir,
            channel = DiagnosticLogChannel.AUDIO_PIPELINE,
            payload = "SECOND_" + "y".repeat(70),
            maxChannelBytes = maxBytes,
        )
        DiagnosticLogRotationPolicy.writeChannelPayload(
            logDir = logDir,
            channel = DiagnosticLogChannel.AUDIO_PIPELINE,
            payload = "THIRD_" + "z".repeat(70),
            maxChannelBytes = maxBytes,
        )

        val active = File(logDir, DiagnosticLogChannel.AUDIO_PIPELINE.fileName)
        val backup = File(logDir, DiagnosticLogChannel.AUDIO_PIPELINE.backupFileName!!)

        assertTrue(active.isFile)
        assertTrue(backup.isFile)
        assertTrue("Active file should contain latest rotated entry", active.readText().contains("THIRD_"))
        assertTrue("Backup file should contain previous active entry", backup.readText().contains("SECOND_"))
        assertFalse("Oldest entry before previous backup must be evicted", backup.readText().contains("FIRST_"))

        val exportable = DiagnosticLogRotationPolicy.listExportableFiles(logDir)
        assertEquals(listOf("audio_pipeline.txt", "audio_pipeline.prev.txt"), exportable.map { it.name })
    }

    @Test
    fun writeChannelPayload_overwritesDeviceSummarySnapshotAtomicallyWithoutPrevBackup() {
        val logDir = tempFolder.newFolder("diag_summary")

        DiagnosticLogRotationPolicy.writeChannelPayload(
            logDir = logDir,
            channel = DiagnosticLogChannel.DEVICE_SUMMARY,
            payload = "audioMode=SAFE_VOLUME_MODE\n",
        )
        DiagnosticLogRotationPolicy.writeChannelPayload(
            logDir = logDir,
            channel = DiagnosticLogChannel.DEVICE_SUMMARY,
            payload = "audioMode=BIT_PERFECT_OPT_IN\n",
        )

        val summaryFile = File(logDir, DiagnosticLogChannel.DEVICE_SUMMARY.fileName)
        assertTrue(summaryFile.isFile)
        assertEquals("audioMode=BIT_PERFECT_OPT_IN\n", summaryFile.readText())
        assertEquals(1, logDir.listFiles()?.size ?: 0)
    }

    @Test
    fun computeStorageStats_andClearAll_accountForAllChannelsAndCachedExportZips() {
        val logDir = tempFolder.newFolder("diag_clear_logs")
        val exportCacheDir = tempFolder.newFolder("diag_clear_cache")

        DiagnosticLogRotationPolicy.writeChannelPayload(logDir, DiagnosticLogChannel.DEVICE_SUMMARY, "v=1.2")
        DiagnosticLogRotationPolicy.writeChannelPayload(logDir, DiagnosticLogChannel.PERFORMANCE, "COLD_START ms=42")
        val cachedZip = File(exportCacheDir, "bitperfect-logs-v1.2-20261121-1000.zip").apply {
            writeText("fake-zip")
        }

        val before = DiagnosticLogRotationPolicy.computeStorageStats(logDir)
        assertTrue(before.hasLogs)
        assertEquals(2, before.fileCount)
        assertTrue(before.totalBytes > 0L)

        DiagnosticLogRotationPolicy.clearAll(logDir, exportCacheDir)

        val after = DiagnosticLogRotationPolicy.computeStorageStats(logDir)
        assertFalse(after.hasLogs)
        assertEquals(0, after.fileCount)
        assertEquals(0L, after.totalBytes)
        assertFalse("Cached share zip should be deleted on clearAll", cachedZip.exists())
    }
}
