package com.cangkir.bitperfectplayer.ui

import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cangkir.bitperfectplayer.audio.AudioOutputMode
import com.cangkir.bitperfectplayer.audio.PlaybackControllerState
import com.cangkir.bitperfectplayer.audio.ReplayGainConfig
import com.cangkir.bitperfectplayer.audio.ReplayGainMode
import com.cangkir.bitperfectplayer.audio.ReplayGainProcessor
import com.cangkir.bitperfectplayer.audio.SleepTimerPreset
import com.cangkir.bitperfectplayer.audio.SleepTimerState
import com.cangkir.bitperfectplayer.data.LocalBackupRepository
import com.cangkir.bitperfectplayer.data.NowPlayingArtworkStyle
import com.cangkir.bitperfectplayer.data.SpectrumColorStyle
import com.cangkir.bitperfectplayer.library.LibrarySnapshot
import com.cangkir.bitperfectplayer.ui.theme.AmberIndicated
import com.cangkir.bitperfectplayer.ui.theme.EmeraldSafe
import com.cangkir.bitperfectplayer.ui.theme.IcePrimary
import com.cangkir.bitperfectplayer.ui.theme.IcePrimaryContainer
import com.cangkir.bitperfectplayer.ui.theme.OnIcePrimaryContainer
import com.cangkir.bitperfectplayer.ui.theme.TextMuted
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.StateFlow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    libraryState: LibrarySnapshot,
    playbackState: PlaybackControllerState,
    sleepTimerStateFlow: StateFlow<SleepTimerState>,
    artworkStyle: NowPlayingArtworkStyle = NowPlayingArtworkStyle.VINYL_DISC,
    onSetArtworkStyle: (NowPlayingArtworkStyle) -> Unit = {},
    spectrumVisualizerEnabled: Boolean = true,
    onSetSpectrumVisualizerEnabled: (Boolean) -> Unit = {},
    spectrumColorStyle: SpectrumColorStyle = SpectrumColorStyle.PRISM,
    onSetSpectrumColorStyle: (SpectrumColorStyle) -> Unit = {},
    spectrumSparksEnabled: Boolean = true,
    onSetSpectrumSparksEnabled: (Boolean) -> Unit = {},
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
) {
    val context = LocalContext.current
    var showSleepTimerSheet by remember { mutableStateOf(false) }

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
            pkgInfo.versionName ?: "1.0"
        }.getOrDefault("1.0")
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
        diagnostics.isBitPerfectMixerActive -> IcePrimary
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
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // =================================================================
            // 1. AUDIO ENGINE
            // =================================================================
            item(key = "section_audio_engine", contentType = "settings_group") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsSectionHeader("AUDIO ENGINE")
                    SettingsGroupCard {
                        // Gapless Playback toggle
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .sizeIn(minHeight = 60.dp)
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Gapless Playback",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Seamless zero-gap transitions when consecutive tracks share format and sample rate.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Switch(
                                checked = playbackState.gaplessEnabled,
                                onCheckedChange = onSetGaplessEnabled,
                            )
                        }

                        SettingsHairline()

                        // ReplayGain controls
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "ReplayGain",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                if (isBitPerfectOptIn && rgConfig.mode != ReplayGainMode.OFF) {
                                    Text(
                                        text = "Bypassed",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = IcePrimary,
                                        maxLines = 1,
                                        softWrap = false,
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = "Automatically bypassed in Bit-Perfect Opt-In mode.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(10.dp))

                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.65f)),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(3.dp),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    ReplayGainMode.entries.forEach { option ->
                                        val selected = rgConfig.mode == option
                                        Button(
                                            onClick = { onSetReplayGainMode(option) },
                                            shape = RoundedCornerShape(8.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                            modifier = Modifier
                                                .weight(1f)
                                                .sizeIn(minHeight = 48.dp),
                                            colors = if (selected) {
                                                ButtonDefaults.buttonColors(
                                                    containerColor = IcePrimaryContainer,
                                                    contentColor = OnIcePrimaryContainer,
                                                )
                                            } else {
                                                ButtonDefaults.textButtonColors(
                                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            },
                                        ) {
                                            Text(
                                                text = option.displayName,
                                                maxLines = 1,
                                                softWrap = false,
                                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                            )
                                        }
                                    }
                                }
                            }

                            if (rgConfig.mode != ReplayGainMode.OFF) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "Pre-amp",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        text = ReplayGainProcessor.formatSignedDb(rgConfig.clampedPreampDb),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        softWrap = false,
                                    )
                                }
                                Slider(
                                    value = rgConfig.clampedPreampDb,
                                    onValueChange = { raw ->
                                        val snapped = (raw * 2f).roundToInt() / 2f
                                        onSetReplayGainPreampDb(snapped)
                                    },
                                    valueRange = ReplayGainConfig.MIN_PREAMP_DB..ReplayGainConfig.MAX_PREAMP_DB,
                                    steps = 23,
                                    colors = SliderDefaults.colors(
                                        thumbColor = MaterialTheme.colorScheme.onSurface,
                                        activeTrackColor = MaterialTheme.colorScheme.primary,
                                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    ),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .sizeIn(minHeight = 48.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Prevent Clipping",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        Text(
                                            text = "Clamps gain against track/album peak tag",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextMuted,
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Switch(
                                        checked = rgConfig.preventClipping,
                                        onCheckedChange = onSetReplayGainPreventClipping,
                                    )
                                }
                            }
                        }

                        SettingsHairline()

                        // Sleep Timer shortcut (isolated leaf collects 1s countdown ticks)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .sizeIn(minHeight = 56.dp)
                                .clickable { showSleepTimerSheet = true }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Bedtime,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Sleep Timer",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                SleepTimerSettingsValueLeaf(sleepTimerStateFlow = sleepTimerStateFlow)
                            }
                            Icon(
                                imageVector = Icons.Filled.ChevronRight,
                                contentDescription = "Configure sleep timer",
                                tint = TextMuted,
                            )
                        }

                        SettingsHairline()

                        // 6-Stage Audio Diagnostics shortcut
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .sizeIn(minHeight = 60.dp)
                                .clickable(onClick = onOpenDiagnostics)
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.GraphicEq,
                                contentDescription = null,
                                tint = if (diagnostics.isBitPerfectMixerActive) IcePrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "6-Stage Audio Diagnostics",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(modeDotColor),
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "$modeLabel · ${safetyState.activeDeviceName}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Icon(
                                imageVector = Icons.Filled.ChevronRight,
                                contentDescription = "Open 6-Stage Audio Diagnostics",
                                tint = TextMuted,
                            )
                        }
                    }
                }
            }

            // =================================================================
            // 2. NOW PLAYING DISPLAY
            // =================================================================
            item(key = "section_display", contentType = "settings_group") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsSectionHeader("DISPLAY")
                    SettingsGroupCard {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                        ) {
                            Text(
                                text = "Now Playing Artwork",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = "Choose spinning turntable disc with rotational scrub seeking, " +
                                    "or static square cover with swipe-to-skip.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(10.dp))

                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.65f)),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(3.dp),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    NowPlayingArtworkStyle.entries.forEach { option ->
                                        val selected = artworkStyle == option
                                        Button(
                                            onClick = { onSetArtworkStyle(option) },
                                            shape = RoundedCornerShape(8.dp),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                            modifier = Modifier
                                                .weight(1f)
                                                .sizeIn(minHeight = 48.dp),
                                            colors = if (selected) {
                                                ButtonDefaults.buttonColors(
                                                    containerColor = IcePrimaryContainer,
                                                    contentColor = OnIcePrimaryContainer,
                                                )
                                            } else {
                                                ButtonDefaults.textButtonColors(
                                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            },
                                        ) {
                                            Text(
                                                text = option.displayName,
                                                maxLines = 1,
                                                softWrap = false,
                                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        SettingsHairline()

                        // Spectrum Visualizer toggle
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .sizeIn(minHeight = 60.dp)
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Spectrum Visualizer",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Read-only local FFT ring around Now Playing artwork. " +
                                        "Zero microphone permissions; safe in Bit-Perfect mode.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Switch(
                                checked = spectrumVisualizerEnabled,
                                onCheckedChange = onSetSpectrumVisualizerEnabled,
                                modifier = Modifier.semantics {
                                    contentDescription = "Toggle spectrum visualizer"
                                },
                            )
                        }

                        if (spectrumVisualizerEnabled) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                            ) {
                                Text(
                                    text = "Spectrum Style",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Spacer(modifier = Modifier.height(8.dp))

                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.65f)),
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(3.dp),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        SpectrumColorStyle.entries.forEach { option ->
                                            val selected = spectrumColorStyle == option
                                            Button(
                                                onClick = { onSetSpectrumColorStyle(option) },
                                                shape = RoundedCornerShape(8.dp),
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .sizeIn(minHeight = 48.dp)
                                                    .semantics {
                                                        contentDescription = "Spectrum style ${option.displayName}"
                                                    },
                                                colors = if (selected) {
                                                    ButtonDefaults.buttonColors(
                                                        containerColor = IcePrimaryContainer,
                                                        contentColor = OnIcePrimaryContainer,
                                                    )
                                                } else {
                                                    ButtonDefaults.textButtonColors(
                                                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                },
                                            ) {
                                                Text(
                                                    text = option.displayName,
                                                    maxLines = 1,
                                                    softWrap = false,
                                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                                )
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .sizeIn(minHeight = 48.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Kinetic Sparks",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        Text(
                                            text = "Transient particle bursts launched from beat peaks",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextMuted,
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Switch(
                                        checked = spectrumSparksEnabled,
                                        onCheckedChange = onSetSpectrumSparksEnabled,
                                        modifier = Modifier.semantics {
                                            contentDescription = "Toggle kinetic sparks"
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // =================================================================
            // 3. LIBRARY & STORAGE (100% OFFLINE)
            // =================================================================
            item(key = "section_library_storage", contentType = "settings_group") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsSectionHeader("LIBRARY & STORAGE (100% OFFLINE)")
                    SettingsGroupCard {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            OutlinedButton(
                                onClick = { folderPickerLauncher.launch(null) },
                                modifier = Modifier
                                    .weight(1f)
                                    .sizeIn(minHeight = 48.dp),
                            ) {
                                Icon(Icons.Filled.CreateNewFolder, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Add Folder (SAF)", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                            }
                            OutlinedButton(
                                onClick = onRefreshLibrary,
                                modifier = Modifier
                                    .weight(0.95f)
                                    .sizeIn(minHeight = 48.dp),
                            ) {
                                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Refresh Library", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                            }
                        }

                        SettingsHairline()

                        if (sortedFolders.isEmpty()) {
                            Text(
                                text = "No SAF folders linked. MediaStore tracks are scanned automatically; " +
                                    "link additional local folders anytime without broad storage permissions.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            )
                        } else {
                            sortedFolders.forEachIndexed { idx, uriString ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Folder,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = formatReadableSafLabel(uriString),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = uriString,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextMuted,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    IconButton(
                                        onClick = { onRemoveSafFolder(uriString) },
                                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.DeleteOutline,
                                            contentDescription = "Remove SAF folder ${formatReadableSafLabel(uriString)}",
                                            tint = TextMuted,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                }
                                if (idx < sortedFolders.lastIndex) {
                                    SettingsHairline()
                                }
                            }
                        }
                    }
                }
            }

            // =================================================================
            // 3. LOCAL BACKUP & RESTORE (100% OFFLINE VIA SAF)
            // =================================================================
            item(key = "section_local_backup", contentType = "settings_group") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsSectionHeader("LOCAL BACKUP & RESTORE")
                    SettingsGroupCard {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                        ) {
                            Text(
                                text = "Playlists, Saved Lyrics (SHA-256), Favorites & Play Stats",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = "Export a portable offline .json backup via Android Storage Access Framework, " +
                                    "or restore with non-destructive merge.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        backupExportLauncher.launch(LocalBackupRepository.suggestBackupFileName())
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .sizeIn(minHeight = 48.dp),
                                ) {
                                    Icon(Icons.Filled.SaveAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Export .json", maxLines = 1, softWrap = false)
                                }
                                OutlinedButton(
                                    onClick = {
                                        backupImportLauncher.launch(arrayOf("application/json", "*/*"))
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .sizeIn(minHeight = 48.dp),
                                ) {
                                    Icon(Icons.Filled.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Restore .json", maxLines = 1, softWrap = false)
                                }
                            }
                        }
                    }
                }
            }

            // =================================================================
            // 4. ABOUT & PRIVACY
            // =================================================================
            item(key = "section_about_privacy", contentType = "settings_group") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsSectionHeader("ABOUT & PRIVACY")
                    SettingsGroupCard {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Filled.VerifiedUser,
                                        contentDescription = null,
                                        tint = EmeraldSafe,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = "100% Offline · Zero Network Permissions",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "BitPerfectPlayer declares no INTERNET permission, collects zero telemetry, " +
                                    "and keeps all audio indexing, playlists, lyrics, and listening statistics strictly on-device.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            HorizontalDivider(
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            SettingsKeyValueRow("App Version", "BitPerfectPlayer v$appVersionName")
                            Spacer(modifier = Modifier.height(4.dp))
                            SettingsKeyValueRow("Indexed Local Tracks", "${libraryState.allSongs.size}")
                            Spacer(modifier = Modifier.height(4.dp))
                            SettingsKeyValueRow("Supported Formats", "FLAC · WAV · MP3 · M4A · OGG")
                        }
                    }
                }
            }

            item(key = "settings_bottom_spacer", contentType = "spacer") {
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun SettingsSectionHeader(text: String) {
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
private fun SettingsGroupCard(content: @Composable () -> Unit) {
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
private fun SettingsHairline() {
    HorizontalDivider(
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
        modifier = Modifier.padding(horizontal = 14.dp),
    )
}

@Composable
private fun SettingsKeyValueRow(label: String, value: String) {
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

private fun formatReadableSafLabel(uriString: String): String {
    val decoded = runCatching { Uri.decode(uriString) }.getOrDefault(uriString)
    val treeMarker = "/tree/"
    val afterTree = decoded.substringAfter(treeMarker, decoded)
    val cleaned = afterTree.substringAfter(':', afterTree).trim('/')
    return cleaned.ifBlank { decoded }
}
