package com.cangkir.bitperfectplayer.diagnostics

import android.net.Uri
import kotlinx.coroutines.flow.StateFlow

interface DiagnosticLogControl {
    val isEnabled: Boolean
    val storageStats: StateFlow<DiagnosticLogStats>

    fun setEnabled(enabled: Boolean)

    fun writeDeviceSummary(fields: Map<String, Any?>)

    fun refreshStorageStatsSync(): DiagnosticLogStats

    fun clearAllLogs(): DiagnosticLogStats
}

interface DiagnosticLogExporterContract {
    suspend fun exportToUri(destinationUri: Uri, summaryFields: Map<String, Any?>? = null): Result<DiagnosticExportResult>

    suspend fun prepareShareIntent(summaryFields: Map<String, Any?>? = null): Result<DiagnosticShareResult>
}
