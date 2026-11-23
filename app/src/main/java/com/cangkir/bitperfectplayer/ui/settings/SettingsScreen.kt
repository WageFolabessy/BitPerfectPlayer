package com.cangkir.bitperfectplayer.ui.settings

import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cangkir.bitperfectplayer.audio.AudioOutputMode
import com.cangkir.bitperfectplayer.audio.PlaybackControllerState
import com.cangkir.bitperfectplayer.audio.ReplayGainMode
import com.cangkir.bitperfectplayer.audio.SleepTimerPreset
import com.cangkir.bitperfectplayer.audio.SleepTimerState
import com.cangkir.bitperfectplayer.data.DefaultHomepage
import com.cangkir.bitperfectplayer.data.LocalBackupRepository
import com.cangkir.bitperfectplayer.data.NowPlayingArtworkStyle
import com.cangkir.bitperfectplayer.data.SpectrumColorStyle
import com.cangkir.bitperfectplayer.diagnostics.DiagnosticLogExporter
import com.cangkir.bitperfectplayer.diagnostics.DiagnosticLogStats
import com.cangkir.bitperfectplayer.library.LibrarySnapshot
import com.cangkir.bitperfectplayer.ui.SleepTimerBottomSheet
import com.cangkir.bitperfectplayer.ui.WindowLayoutMetrics
import com.cangkir.bitperfectplayer.ui.nowplaying.NowPlayingSwipeExclusionBounds
import com.cangkir.bitperfectplayer.ui.nowplaying.SettingsTabSwipeContainer
import com.cangkir.bitperfectplayer.ui.theme.AmberIndicated
import com.cangkir.bitperfectplayer.ui.theme.EmeraldSafe
import kotlinx.coroutines.flow.StateFlow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    libraryState: LibrarySnapshot,
    playbackState: PlaybackControllerState,
    sleepTimerStateFlow: StateFlow<SleepTimerState>,
    defaultHomepage: DefaultHomepage = DefaultHomepage.ALL_SONGS,
    onSetDefaultHomepage: (DefaultHomepage) -> Unit = {},
    artworkStyle: NowPlayingArtworkStyle = NowPlayingArtworkStyle.VINYL_DISC,
    onSetArtworkStyle: (NowPlayingArtworkStyle) -> Unit = {},
    spectrumVisualizerEnabled: Boolean = true,
    onSetSpectrumVisualizerEnabled: (Boolean) -> Unit = {},
    spectrumColorStyle: SpectrumColorStyle = SpectrumColorStyle.PRISM,
    onSetSpectrumColorStyle: (SpectrumColorStyle) -> Unit = {},
    spectrumSparksEnabled: Boolean = true,
    onSetSpectrumSparksEnabled: (Boolean) -> Unit = {},
    dynamicCoverThemeEnabled: Boolean = false,
    onSetDynamicCoverThemeEnabled: (Boolean) -> Unit = {},
    diagnosticLoggingEnabled: Boolean = false,
    diagnosticLogStats: DiagnosticLogStats = DiagnosticLogStats(),
    onSetDiagnosticLoggingEnabled: (Boolean) -> Unit = {},
    onExportDiagnosticLogs: (Uri) -> Unit = {},
    onShareDiagnosticLogs: () -> Unit = {},
    onClearDiagnosticLogs: () -> Unit = {},
    onRefreshDiagnosticLogStats: () -> Unit = {},
    onImportFolder: (Uri) -> Unit,
    onRemoveSafFolder: (String) -> Unit,
    onRefreshLibrary: () -> Unit,
    onSetGaplessEnabled: (Boolean) -> Unit,
    onSetReplayGainMode: (ReplayGainMode) -> Unit,
    onSetReplayGainPreampDb: (Float) -> Unit,
    onSetReplayGainPreventClipping: (Boolean) -> Unit,
    onSelectSleepTimerPreset: (SleepTimerPreset) -> Unit,
    onExportLocalBackup: (Uri) -> Unit,
    onImportLocalBackup: (Uri) -> Unit,
    onOpenDiagnostics: () -> Unit,
    onBack: (() -> Unit)? = null,
    onSwipeToNowPlaying: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var showSleepTimerSheet by remember { mutableStateOf(false) }
    var showDefaultHomepageSheet by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        onRefreshDiagnosticLogStats()
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri ->
        if (treeUri != null) {
            onImportFolder(treeUri)
        }
    }

    val backupExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { destinationUri ->
        if (destinationUri != null) {
            onExportLocalBackup(destinationUri)
        }
    }

    // Wildcard MIME fallback ensures HyperOS / Android SAF always allows selecting .json backups.
    val backupImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { sourceUri ->
        if (sourceUri != null) {
            onImportLocalBackup(sourceUri)
        }
    }

    val diagnosticExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip"),
    ) { destinationUri ->
        if (destinationUri != null) {
            onExportDiagnosticLogs(destinationUri)
        }
    }

    val sortedFolders = remember(libraryState.persistedSafTreeUris) {
        libraryState.persistedSafTreeUris.sorted()
    }

    val appVersionName = remember(context) {
        runCatching {
            val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0L),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            pkgInfo.versionName ?: "1.3"
        }.getOrDefault("1.3")
    }

    val safetyState = playbackState.engineState.safetyState
    val diagnostics = playbackState.engineState.diagnostics
    val isBitPerfectOptIn = safetyState.mode == AudioOutputMode.BIT_PERFECT_OPT_IN
    val rgConfig = playbackState.replayGainConfig

    val modeLabel = when {
        diagnostics.isBitPerfectMixerActive -> "Bit-Perfect USB Active"
        isBitPerfectOptIn -> "Clean Native Fallback"
        else -> "Safe Mode (Volume Active)"
    }
    val modeDotColor = when {
        diagnostics.isBitPerfectMixerActive -> MaterialTheme.colorScheme.primary
        isBitPerfectOptIn -> AmberIndicated
        else -> EmeraldSafe
    }

    if (showSleepTimerSheet) {
        SleepTimerBottomSheet(
            sleepTimerStateFlow = sleepTimerStateFlow,
            isBitPerfectOptIn = isBitPerfectOptIn,
            onSelectPreset = onSelectSleepTimerPreset,
            onDismiss = { showSleepTimerSheet = false },
        )
    }

    if (showDefaultHomepageSheet) {
        DefaultHomepageBottomSheet(
            currentHomepage = defaultHomepage,
            onSelectHomepage = { selected ->
                onSetDefaultHomepage(selected)
                showDefaultHomepageSheet = false
            },
            onDismiss = { showDefaultHomepageSheet = false },
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets.only(
            WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
        ),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { innerPadding ->
        val swipeExclusionBounds = remember { NowPlayingSwipeExclusionBounds() }
        SettingsTabSwipeContainer(
            exclusionBounds = swipeExclusionBounds,
            onSwipeLeftToRight = onSwipeToNowPlaying ?: onBack,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopCenter,
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = WindowLayoutMetrics.EXPANDED_CONTENT_MAX_WIDTH_DP.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    item(key = "section_audio_engine", contentType = "settings_group") {
                        val audioEngineUiState = SettingsAudioEngineUiState(
                            gaplessEnabled = playbackState.gaplessEnabled,
                            isBitPerfectOptIn = isBitPerfectOptIn,
                            isBitPerfectMixerActive = diagnostics.isBitPerfectMixerActive,
                            rgConfig = rgConfig,
                            modeLabel = modeLabel,
                            modeDotColor = modeDotColor,
                            activeDeviceName = safetyState.activeDeviceName,
                        )
                        val audioEngineCallbacks = remember(
                            onSetGaplessEnabled,
                            onSetReplayGainMode,
                            onSetReplayGainPreampDb,
                            onSetReplayGainPreventClipping,
                            onOpenDiagnostics,
                            swipeExclusionBounds,
                        ) {
                            SettingsAudioEngineCallbacks(
                                onSetGaplessEnabled = onSetGaplessEnabled,
                                onSetReplayGainMode = onSetReplayGainMode,
                                onSetReplayGainPreampDb = onSetReplayGainPreampDb,
                                onSetReplayGainPreventClipping = onSetReplayGainPreventClipping,
                                onOpenSleepTimer = { showSleepTimerSheet = true },
                                onOpenDiagnostics = onOpenDiagnostics,
                                onPreampSliderBoundsInRootChanged = { rect ->
                                    swipeExclusionBounds.seekSliderRectInRoot = rect
                                },
                            )
                        }
                        SettingsAudioEngineSection(
                            uiState = audioEngineUiState,
                            sleepTimerStateFlow = sleepTimerStateFlow,
                            callbacks = audioEngineCallbacks,
                        )
                    }

                    item(key = "section_display", contentType = "settings_group") {
                        SettingsDisplaySection(
                            defaultHomepage = defaultHomepage,
                            onOpenDefaultHomepagePicker = { showDefaultHomepageSheet = true },
                            artworkStyle = artworkStyle,
                            onSetArtworkStyle = onSetArtworkStyle,
                            spectrumVisualizerEnabled = spectrumVisualizerEnabled,
                            onSetSpectrumVisualizerEnabled = onSetSpectrumVisualizerEnabled,
                            spectrumColorStyle = spectrumColorStyle,
                            onSetSpectrumColorStyle = onSetSpectrumColorStyle,
                            spectrumSparksEnabled = spectrumSparksEnabled,
                            onSetSpectrumSparksEnabled = onSetSpectrumSparksEnabled,
                            dynamicCoverThemeEnabled = dynamicCoverThemeEnabled,
                            onSetDynamicCoverThemeEnabled = onSetDynamicCoverThemeEnabled,
                        )
                    }

                    item(key = "section_library_storage", contentType = "settings_group") {
                        SettingsLibraryStorageSection(
                            sortedFolders = sortedFolders,
                            onAddFolderClick = { folderPickerLauncher.launch(null) },
                            onRefreshLibrary = onRefreshLibrary,
                            onRemoveSafFolder = onRemoveSafFolder,
                        )
                    }

                    item(key = "section_local_backup", contentType = "settings_group") {
                        SettingsLocalBackupSection(
                            onExportClick = {
                                backupExportLauncher.launch(LocalBackupRepository.suggestBackupFileName())
                            },
                            onImportClick = {
                                backupImportLauncher.launch(arrayOf("application/json", "*/*"))
                            },
                        )
                    }

                    item(key = "section_diagnostic_logging", contentType = "settings_group") {
                        SettingsDiagnosticLoggingSection(
                            enabled = diagnosticLoggingEnabled,
                            logStats = diagnosticLogStats,
                            onSetEnabled = onSetDiagnosticLoggingEnabled,
                            onExportClick = {
                                diagnosticExportLauncher.launch(DiagnosticLogExporter.suggestZipFileName(appVersionName))
                            },
                            onShareClick = onShareDiagnosticLogs,
                            onClearClick = onClearDiagnosticLogs,
                        )
                    }

                    item(key = "section_about_privacy", contentType = "settings_group") {
                        SettingsAboutPrivacySection(
                            appVersionName = appVersionName,
                            indexedLocalTrackCount = libraryState.allSongs.size,
                        )
                    }

                    item(key = "settings_bottom_spacer", contentType = "spacer") {
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
internal fun SettingsSectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier.padding(horizontal = 2.dp),
    )
}

@Composable
internal fun SettingsGroupCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            content()
        }
    }
}

@Composable
internal fun SettingsHairline() {
    HorizontalDivider(
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
        modifier = Modifier.padding(horizontal = 14.dp),
    )
}

@Composable
internal fun SettingsKeyValueRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false,
        )
    }
}
