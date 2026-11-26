package com.cangkir.bitperfectplayer.ui

import android.content.Intent
import android.net.Uri
import com.cangkir.bitperfectplayer.audio.PlaybackAudioControl
import com.cangkir.bitperfectplayer.audio.SleepTimerPreset
import com.cangkir.bitperfectplayer.data.DefaultHomepage
import com.cangkir.bitperfectplayer.data.LocalBackupStore
import com.cangkir.bitperfectplayer.data.NowPlayingArtworkStyle
import com.cangkir.bitperfectplayer.data.SpectrumColorStyle
import com.cangkir.bitperfectplayer.data.UserPreferences
import com.cangkir.bitperfectplayer.data.UserPreferencesStore
import com.cangkir.bitperfectplayer.diagnostics.DiagnosticLogExporterContract
import com.cangkir.bitperfectplayer.diagnostics.DiagnosticLogStats
import com.cangkir.bitperfectplayer.ui.theme.DynamicThemeIntensity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Narrow diagnostics bridge required by [SettingsAndBackupCoordinator] so device summary
 * construction stays outside the presentation layer and cleanly testable on JVM.
 */
interface DiagnosticCoordinatorControls {
    val isEnabled: Boolean
    val storageStats: StateFlow<DiagnosticLogStats>

    fun setEnabled(enabled: Boolean)

    fun writeDeviceSummary(prefs: UserPreferences)

    fun buildExportSummaryFieldsIfEnabled(prefs: UserPreferences): Map<String, Any?>?

    fun refreshStorageStatsSync(): DiagnosticLogStats

    fun clearAllLogs(): DiagnosticLogStats
}

/**
 * Coordinates user preferences, single-latch startup homepage resolution, hearing-safety mode
 * transitions, sleep timer selection, local JSON backups, and diagnostic log management.
 */
class SettingsAndBackupCoordinator(
    private val scope: CoroutineScope,
    private val preferencesStore: UserPreferencesStore,
    private val backupStore: LocalBackupStore,
    private val diagnosticControls: DiagnosticCoordinatorControls,
    private val diagnosticLogExporter: DiagnosticLogExporterContract,
    private val audioControl: PlaybackAudioControl,
    private val messageEmitter: UiMessageEmitter,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    val diagnosticLogStats: StateFlow<DiagnosticLogStats> = diagnosticControls.storageStats

    val nowPlayingArtworkStyle: StateFlow<NowPlayingArtworkStyle> =
        preferenceState(NowPlayingArtworkStyle.VINYL_DISC) { it.nowPlayingArtworkStyle }

    val spectrumVisualizerEnabled: StateFlow<Boolean> =
        preferenceState(true) { it.spectrumVisualizerEnabled }

    val spectrumColorStyle: StateFlow<SpectrumColorStyle> =
        preferenceState(SpectrumColorStyle.PRISM) { it.spectrumColorStyle }

    val spectrumSparksEnabled: StateFlow<Boolean> =
        preferenceState(true) { it.spectrumSparksEnabled }

    val defaultHomepage: StateFlow<DefaultHomepage> =
        preferenceState(DefaultHomepage.ALL_SONGS) { it.defaultHomepage }

    val diagnosticLoggingEnabled: StateFlow<Boolean> =
        preferenceState(false) { it.diagnosticLoggingEnabled }

    val dynamicCoverThemeEnabled: StateFlow<Boolean> =
        preferenceState(false) { it.dynamicCoverThemeEnabled }

    val dynamicThemeIntensity: StateFlow<DynamicThemeIntensity> =
        preferenceState(DynamicThemeIntensity.VIBRANT) { it.dynamicThemeIntensity }

    // Latched strictly once per ViewModel lifetime so subsequent Settings changes never hijack navigation.
    private val _initialStartupHomepage = MutableStateFlow<DefaultHomepage?>(null)
    val initialStartupHomepage: StateFlow<DefaultHomepage?> = _initialStartupHomepage.asStateFlow()

    init {
        scope.launch {
            val loadedHomepage = preferencesStore.getSnapshot().defaultHomepage
            _initialStartupHomepage.compareAndSet(null, loadedHomepage)
        }
    }

    fun setNowPlayingArtworkStyle(style: NowPlayingArtworkStyle) {
        scope.launch { preferencesStore.setNowPlayingArtworkStyle(style) }
    }

    fun setSpectrumVisualizerEnabled(enabled: Boolean) {
        if (!enabled) {
            audioControl.setSpectrumTapActive(false)
        }
        scope.launch { preferencesStore.setSpectrumVisualizerEnabled(enabled) }
    }

    fun setSpectrumColorStyle(style: SpectrumColorStyle) {
        scope.launch { preferencesStore.setSpectrumColorStyle(style) }
    }

    fun setSpectrumSparksEnabled(enabled: Boolean) {
        scope.launch { preferencesStore.setSpectrumSparksEnabled(enabled) }
    }

    fun setDefaultHomepage(homepage: DefaultHomepage) {
        scope.launch { preferencesStore.setDefaultHomepage(homepage) }
    }

    fun setDynamicCoverThemeEnabled(enabled: Boolean) {
        scope.launch { preferencesStore.setDynamicCoverThemeEnabled(enabled) }
    }

    fun setDynamicThemeIntensity(intensity: DynamicThemeIntensity) {
        scope.launch { preferencesStore.setDynamicThemeIntensity(intensity) }
    }

    fun setDiagnosticLoggingEnabled(enabled: Boolean) {
        diagnosticControls.setEnabled(enabled)
        scope.launch {
            preferencesStore.setDiagnosticLoggingEnabled(enabled)
            if (enabled) {
                diagnosticControls.writeDeviceSummary(preferencesStore.getSnapshot())
            }
            messageEmitter.emitSnackbar(
                if (enabled) {
                    "Local diagnostic logging enabled (stored privately on-device)"
                } else {
                    "Local diagnostic logging paused"
                },
            )
        }
    }

    fun refreshDiagnosticLogStats() {
        scope.launch(ioDispatcher) {
            diagnosticControls.refreshStorageStatsSync()
        }
    }

    fun exportDiagnosticLogs(destinationUri: Uri) {
        scope.launch {
            val summaryFields = resolveExportSummaryFieldsIfEnabled()
            diagnosticLogExporter.exportToUri(destinationUri, summaryFields)
                .onSuccess { res ->
                    val label = if (res.fileCount == 1) "file" else "files"
                    messageEmitter.emitSnackbar("Exported ${res.fileCount} diagnostic log $label (.zip)")
                }
                .onFailure { err ->
                    messageEmitter.emitSnackbar(err.message ?: "Unable to export diagnostic logs")
                }
        }
    }

    fun shareDiagnosticLogs(onShareReady: (Intent) -> Unit) {
        scope.launch {
            val summaryFields = resolveExportSummaryFieldsIfEnabled()
            diagnosticLogExporter.prepareShareIntent(summaryFields)
                .onSuccess { res ->
                    onShareReady(res.shareIntent)
                }
                .onFailure { err ->
                    messageEmitter.emitSnackbar(err.message ?: "Unable to prepare diagnostic logs for sharing")
                }
        }
    }

    fun clearDiagnosticLogs() {
        scope.launch {
            withContext(ioDispatcher) {
                diagnosticControls.clearAllLogs()
            }
            messageEmitter.emitSnackbar("Cleared local diagnostic logs")
        }
    }

    fun setSleepTimerPreset(preset: SleepTimerPreset) {
        audioControl.setSleepTimerPreset(preset)
        messageEmitter.emitSnackbar(
            when (preset) {
                SleepTimerPreset.OFF -> "Sleep timer off"
                SleepTimerPreset.END_OF_TRACK -> "Sleep timer: pause after current track"
                else -> "Sleep timer set for ${preset.label}"
            },
        )
    }

    fun requestBitPerfectMode() {
        audioControl.requestBitPerfectMode()
    }

    fun confirmBitPerfectWarning() {
        audioControl.confirmBitPerfectWarning()
        writeDeviceSummaryIfLoggingEnabledAsync()
    }

    fun dismissSafetyWarning() {
        audioControl.dismissSafetyWarning()
    }

    fun switchToSafeMode() {
        audioControl.switchToSafeMode()
        writeDeviceSummaryIfLoggingEnabledAsync()
        messageEmitter.emitSnackbar("Switched to Safe Mode (System Volume Active)")
    }

    fun clearAutoPauseNotice() {
        audioControl.clearAutoPauseNotice()
    }

    fun exportLocalBackup(destinationUri: Uri) {
        scope.launch {
            backupStore.exportBackupToUri(destinationUri)
                .onSuccess { summary ->
                    messageEmitter.emitSnackbar(
                        "Backup saved (" +
                            "${summary.playlistsCount} playlists, " +
                            "${summary.lyricsCount} lyrics, " +
                            "${summary.favoritesCount} favorites, " +
                            "${summary.statsCount} stats)",
                    )
                }
                .onFailure { err ->
                    messageEmitter.emitSnackbar(err.message ?: "Unable to export local backup")
                }
        }
    }

    fun importLocalBackup(sourceUri: Uri) {
        scope.launch {
            backupStore.importBackupFromUri(sourceUri)
                .onSuccess { summary ->
                    messageEmitter.emitSnackbar(
                        "Backup restored (" +
                            "${summary.playlistsMerged} playlists, " +
                            "${summary.lyricsUpserted} lyrics, " +
                            "${summary.favoritesMerged} favorites, " +
                            "${summary.statsMerged} stats)",
                    )
                }
                .onFailure { err ->
                    messageEmitter.emitSnackbar(err.message ?: "Invalid or unreadable backup file")
                }
        }
    }

    private fun <T> preferenceState(initialValue: T, selector: (UserPreferences) -> T): StateFlow<T> = preferencesStore.preferencesFlow
        .map(selector)
        .distinctUntilChanged()
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = initialValue,
        )

    private fun writeDeviceSummaryIfLoggingEnabledAsync() {
        if (!diagnosticControls.isEnabled) return
        scope.launch {
            diagnosticControls.writeDeviceSummary(preferencesStore.getSnapshot())
        }
    }

    private suspend fun resolveExportSummaryFieldsIfEnabled(): Map<String, Any?>? = if (diagnosticControls.isEnabled) {
        diagnosticControls.buildExportSummaryFieldsIfEnabled(preferencesStore.getSnapshot())
    } else {
        null
    }
}
