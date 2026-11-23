package com.cangkir.bitperfectplayer.ui.nowplaying

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cangkir.bitperfectplayer.audio.AudioOutputMode
import com.cangkir.bitperfectplayer.audio.AudioSafetyController
import com.cangkir.bitperfectplayer.audio.PlaybackTrack
import com.cangkir.bitperfectplayer.data.NowPlayingArtworkStyle
import com.cangkir.bitperfectplayer.data.SpectrumColorStyle
import com.cangkir.bitperfectplayer.ui.AudioModeSegmentedSwitch
import com.cangkir.bitperfectplayer.ui.theme.AmberIndicated
import com.cangkir.bitperfectplayer.ui.theme.AmberIndicatedContainer
import com.cangkir.bitperfectplayer.ui.theme.CoralWarning
import com.cangkir.bitperfectplayer.ui.theme.CoralWarningContainer
import com.cangkir.bitperfectplayer.ui.theme.EmeraldSafe
import com.cangkir.bitperfectplayer.ui.theme.EmeraldSafeContainer
import com.cangkir.bitperfectplayer.ui.theme.OnAmberIndicatedContainer
import com.cangkir.bitperfectplayer.ui.theme.OnCoralWarningContainer
import com.cangkir.bitperfectplayer.ui.theme.OnEmeraldSafeContainer

@Composable
internal fun NowPlayingOverflowMenuButton(
    track: PlaybackTrack,
    artworkStyle: NowPlayingArtworkStyle,
    spectrumVisualizerEnabled: Boolean,
    spectrumColorStyle: SpectrumColorStyle,
    spectrumSparksEnabled: Boolean,
    onAddToPlaylist: (PlaybackTrack) -> Unit,
    onShowTrackInfo: (PlaybackTrack) -> Unit,
    onOpenSleepTimer: () -> Unit,
    onSetArtworkStyle: (NowPlayingArtworkStyle) -> Unit,
    onSetSpectrumVisualizerEnabled: (Boolean) -> Unit,
    onCycleSpectrumColorStyle: () -> Unit,
    onSetSpectrumSparksEnabled: (Boolean) -> Unit,
) {
    var overflowMenuExpanded by remember { mutableStateOf(false) }

    Box {
        IconButton(
            onClick = { overflowMenuExpanded = true },
            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = "More track actions",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (overflowMenuExpanded) {
            DropdownMenu(
                expanded = true,
                onDismissRequest = { overflowMenuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text("Add to Playlist") },
                    onClick = {
                        overflowMenuExpanded = false
                        onAddToPlaylist(track)
                    },
                )
                DropdownMenuItem(
                    text = { Text("Track Info") },
                    onClick = {
                        overflowMenuExpanded = false
                        onShowTrackInfo(track)
                    },
                )
                DropdownMenuItem(
                    text = { Text("Sleep Timer") },
                    onClick = {
                        overflowMenuExpanded = false
                        onOpenSleepTimer()
                    },
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            if (artworkStyle == NowPlayingArtworkStyle.VINYL_DISC) {
                                "Switch to Square Cover"
                            } else {
                                "Switch to Vinyl Disc"
                            },
                        )
                    },
                    onClick = {
                        overflowMenuExpanded = false
                        onSetArtworkStyle(
                            if (artworkStyle == NowPlayingArtworkStyle.VINYL_DISC) {
                                NowPlayingArtworkStyle.SQUARE_COVER
                            } else {
                                NowPlayingArtworkStyle.VINYL_DISC
                            },
                        )
                    },
                )
                DropdownMenuItem(
                    text = {
                        Text(if (spectrumVisualizerEnabled) "Hide Spectrum" else "Show Spectrum")
                    },
                    onClick = {
                        overflowMenuExpanded = false
                        onSetSpectrumVisualizerEnabled(!spectrumVisualizerEnabled)
                    },
                )
                if (spectrumVisualizerEnabled) {
                    DropdownMenuItem(
                        text = { Text("Spectrum Style: ${spectrumColorStyle.displayName}") },
                        onClick = {
                            overflowMenuExpanded = false
                            onCycleSpectrumColorStyle()
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(if (spectrumSparksEnabled) "Disable Sparks" else "Enable Sparks")
                        },
                        onClick = {
                            overflowMenuExpanded = false
                            onSetSpectrumSparksEnabled(!spectrumSparksEnabled)
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun AudioModeSheetContent(
    mode: AudioOutputMode,
    isBitPerfectMixerActive: Boolean,
    isUsbOutputConnected: Boolean,
    isFixedVolumeRisk: Boolean,
    headline: String,
    outputDeviceSummary: String,
    onSwitchToSafeMode: () -> Unit,
    onRequestBitPerfectMode: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    val isSafe = mode == AudioOutputMode.SAFE_VOLUME_MODE
    val accentColor = when {
        isSafe -> EmeraldSafe
        isBitPerfectMixerActive -> MaterialTheme.colorScheme.primary
        else -> AmberIndicated
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Text(
            text = "Audio Output Mode",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = outputDeviceSummary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(16.dp))

        AudioModeSegmentedSwitch(
            mode = mode,
            isBitPerfectMixerActive = isBitPerfectMixerActive,
            onSwitchToSafeMode = onSwitchToSafeMode,
            onRequestBitPerfectMode = onRequestBitPerfectMode,
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = headline,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = accentColor,
        )

        val detailNote = when {
            isBitPerfectMixerActive && isFixedVolumeRisk ->
                "Caution: Hardware volume is unverified — USB output may be fixed at 100% (0 dBFS)."
            !isSafe && isUsbOutputConnected && !isBitPerfectMixerActive ->
                "System volume remains active on Clean Native fallback because the USB HAL did not engage Bit-Perfect."
            !isSafe && !isUsbOutputConnected ->
                "System volume remains active on non-USB output. Connect a USB-C DAC/headset to negotiate Bit-Perfect."
            else ->
                "Safe Mode keeps Android system volume active and prevents full-scale blast on high-sensitivity IEMs."
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = detailNote,
            style = MaterialTheme.typography.bodySmall,
            color = if (isBitPerfectMixerActive && isFixedVolumeRisk) CoralWarning else MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider(
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
        )
        Spacer(modifier = Modifier.height(12.dp))

        OutlinedButton(
            onClick = onOpenDiagnostics,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .sizeIn(minHeight = 48.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.GraphicEq,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Open 6-Stage Audio Diagnostics")
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
internal fun SafetyAlertBanner(message: String, isError: Boolean, onDismiss: (() -> Unit)?) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        shadowElevation = 6.dp,
        color = if (isError) CoralWarningContainer else AmberIndicatedContainer,
        border = BorderStroke(
            0.5.dp,
            (if (isError) CoralWarning else AmberIndicated).copy(alpha = 0.5f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = if (isError) CoralWarning else AmberIndicated,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) OnCoralWarningContainer else OnAmberIndicatedContainer,
                modifier = Modifier.weight(1f),
            )
            if (onDismiss != null) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.sizeIn(minHeight = 48.dp),
                ) {
                    Text(
                        text = "OK",
                        color = if (isError) OnCoralWarningContainer else OnAmberIndicatedContainer,
                    )
                }
            }
        }
    }
}

/**
 * Mandatory Hearing Safety Warning Dialog displayed before enabling Bit-Perfect Mode whenever
 * USB hardware volume control is unverified (SPEC Section 3.3).
 */
@Composable
fun HearingSafetyWarningDialog(deviceName: String, onConfirmBitPerfect: () -> Unit, onDismissStaySafe: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismissStaySafe,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        icon = {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = "Hearing safety warning",
                tint = CoralWarning,
                modifier = Modifier.size(28.dp),
            )
        },
        title = {
            Text(
                text = "Hearing Safety Warning",
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Active Output: $deviceName",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = AudioSafetyController.FIXED_VOLUME_WARNING_MESSAGE,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "Playback auto-pauses automatically if the USB cable is disconnected or audio output changes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        dismissButton = {
            Button(
                onClick = onDismissStaySafe,
                modifier = Modifier.sizeIn(minHeight = 48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = EmeraldSafeContainer,
                    contentColor = OnEmeraldSafeContainer,
                ),
            ) {
                Text("Stay in Safe Mode")
            }
        },
        confirmButton = {
            OutlinedButton(
                onClick = onConfirmBitPerfect,
                modifier = Modifier.sizeIn(minHeight = 48.dp),
                border = BorderStroke(1.dp, CoralWarning),
            ) {
                Text("Enable Bit-Perfect", color = CoralWarning)
            }
        },
    )
}
