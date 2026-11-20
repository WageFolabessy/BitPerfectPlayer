package com.cangkir.bitperfectplayer.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cangkir.bitperfectplayer.audio.AudioOutputMode
import com.cangkir.bitperfectplayer.audio.AudioSafetyController
import com.cangkir.bitperfectplayer.audio.PlaybackControllerState
import com.cangkir.bitperfectplayer.audio.PlaybackTrack
import com.cangkir.bitperfectplayer.audio.RepeatMode
import com.cangkir.bitperfectplayer.ui.theme.AmberIndicated
import com.cangkir.bitperfectplayer.ui.theme.AmberIndicatedContainer
import com.cangkir.bitperfectplayer.ui.theme.CoralWarning
import com.cangkir.bitperfectplayer.ui.theme.CoralWarningContainer
import com.cangkir.bitperfectplayer.ui.theme.EmeraldSafe
import com.cangkir.bitperfectplayer.ui.theme.EmeraldSafeContainer
import com.cangkir.bitperfectplayer.ui.theme.IcePrimary
import com.cangkir.bitperfectplayer.ui.theme.IcePrimaryContainer
import com.cangkir.bitperfectplayer.ui.theme.OnAmberIndicatedContainer
import com.cangkir.bitperfectplayer.ui.theme.OnCoralWarningContainer
import com.cangkir.bitperfectplayer.ui.theme.OnEmeraldSafeContainer
import com.cangkir.bitperfectplayer.ui.theme.OnIcePrimaryContainer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    playbackState: PlaybackControllerState,
    isFavorite: Boolean,
    onBack: () -> Unit,
    onToggleFavorite: (PlaybackTrack) -> Unit,
    onTogglePlayPause: () -> Unit,
    onQuickPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeatMode: () -> Unit,
    onRequestBitPerfectMode: () -> Unit,
    onConfirmBitPerfectWarning: () -> Unit,
    onDismissSafetyWarning: () -> Unit,
    onSwitchToSafeMode: () -> Unit,
    onClearAutoPauseNotice: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenLyrics: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    val track = playbackState.activeTrack
    val safetyState = playbackState.engineState.safetyState
    val diagnostics = playbackState.engineState.diagnostics
    val durationMs = playbackState.durationMs.coerceAtLeast(0L)
    val currentPosMs = playbackState.currentPositionMs.coerceIn(0L, durationMs.coerceAtLeast(1L))

    var isScrubbing by remember { mutableStateOf(false) }
    var scrubPositionMs by remember { mutableFloatStateOf(0f) }

    // Mandatory Hearing Safety Warning Dialog before activating potentially fixed-volume Bit-Perfect Mode.
    if (safetyState.isPendingSafetyWarning) {
        HearingSafetyWarningDialog(
            deviceName = safetyState.activeDeviceName,
            onConfirmBitPerfect = onConfirmBitPerfectWarning,
            onDismissStaySafe = onDismissSafetyWarning,
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Now Playing", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to library",
                        )
                    }
                },
                actions = {
                    if (playbackState.isPlaying) {
                        FilledTonalButton(
                            onClick = onQuickPause,
                            modifier = Modifier
                                .padding(end = 4.dp)
                                .sizeIn(minHeight = 48.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = CoralWarningContainer,
                                contentColor = OnCoralWarningContainer,
                            ),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Pause,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Pause")
                        }
                    }
                    if (track != null) {
                        IconButton(
                            onClick = { onToggleFavorite(track) },
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                        ) {
                            Icon(
                                imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
                                tint = if (isFavorite) IcePrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        if (track == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = Icons.Filled.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(56.dp),
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text("No track selected", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Select a local FLAC, WAV, MP3, M4A, or OGG track from your library to begin playback.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Auto-pause or decode error banner
            playbackState.engineState.lastAutoPauseNotice?.let { notice ->
                SafetyAlertBanner(
                    message = notice,
                    isError = false,
                    onDismiss = onClearAutoPauseNotice,
                )
                Spacer(modifier = Modifier.height(10.dp))
            }
            playbackState.engineState.errorMessage?.let { err ->
                SafetyAlertBanner(
                    message = err,
                    isError = true,
                    onDismiss = null,
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            // Minimal offline audiophile emblem + track format card
            Surface(
                modifier = Modifier.size(148.dp),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(
                    width = 1.dp,
                    color = if (diagnostics.isBitPerfectMixerActive) IcePrimary else MaterialTheme.colorScheme.outline,
                ),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Filled.GraphicEq,
                            contentDescription = null,
                            tint = if (diagnostics.isBitPerfectMixerActive) IcePrimary else EmeraldSafe,
                            modifier = Modifier.size(56.dp),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        FormatBadgeChip(
                            text = track.container,
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Track Metadata
            Text(
                text = track.title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${track.artist} • ${track.album}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(8.dp))

            FormatBadgeChip(
                text = diagnostics.sourceFormatSummary.takeIf { it != "No active track loaded" }
                    ?: track.formatBadge,
                modifier = Modifier.clickable(onClick = onOpenDiagnostics),
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Seekable Progress Bar
            val displayedPosMs = if (isScrubbing) scrubPositionMs.toLong() else currentPosMs
            val sliderMax = durationMs.coerceAtLeast(1L).toFloat()
            Slider(
                value = displayedPosMs.toFloat().coerceIn(0f, sliderMax),
                onValueChange = { newVal ->
                    isScrubbing = true
                    scrubPositionMs = newVal
                },
                onValueChangeFinished = {
                    val target = scrubPositionMs.toLong()
                    isScrubbing = false
                    onSeekTo(target)
                },
                valueRange = 0f..sliderMax,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = formatDurationMs(displayedPosMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = formatDurationMs(durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Primary Transport Controls (all >= 48 dp touch targets)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val shuffleOn = playbackState.queue.shuffleEnabled
                IconButton(
                    onClick = onToggleShuffle,
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Shuffle,
                        contentDescription = if (shuffleOn) "Disable shuffle" else "Enable shuffle",
                        tint = if (shuffleOn) IcePrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                IconButton(
                    onClick = onPrevious,
                    modifier = Modifier.sizeIn(minWidth = 52.dp, minHeight = 52.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.SkipPrevious,
                        contentDescription = "Previous track",
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }

                FilledIconButton(
                    onClick = onTogglePlayPause,
                    modifier = Modifier.size(68.dp),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Icon(
                        imageVector = if (playbackState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playbackState.isPlaying) "Pause playback" else "Play audio",
                        modifier = Modifier.size(36.dp),
                    )
                }

                IconButton(
                    onClick = onNext,
                    modifier = Modifier.sizeIn(minWidth = 52.dp, minHeight = 52.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.SkipNext,
                        contentDescription = "Next track",
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }

                val repeatMode = playbackState.queue.repeatMode
                IconButton(
                    onClick = onCycleRepeatMode,
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                ) {
                    Icon(
                        imageVector = if (repeatMode == RepeatMode.ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                        contentDescription = "Repeat mode: ${repeatMode.name}",
                        tint = if (repeatMode != RepeatMode.OFF) IcePrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Safe Mode vs Bit-Perfect Opt-In Safety Control Card
            AudioSafetyModeCard(
                mode = safetyState.mode,
                isBitPerfectMixerActive = diagnostics.isBitPerfectMixerActive,
                isFixedVolumeRisk = safetyState.isFixedVolumeRisk,
                headline = diagnostics.pathStatusHeadline,
                outputDeviceSummary = diagnostics.activeOutputDeviceSummary,
                onSwitchToSafeMode = onSwitchToSafeMode,
                onRequestBitPerfectMode = onRequestBitPerfectMode,
                onOpenDiagnostics = onOpenDiagnostics,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Fast Access Strip: Queue, Lyrics, Diagnostics (>= 48 dp touch targets)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onOpenQueue,
                    modifier = Modifier
                        .weight(1f)
                        .sizeIn(minHeight = 48.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Queue (${playbackState.queue.size})", maxLines = 1)
                }

                OutlinedButton(
                    onClick = onOpenLyrics,
                    modifier = Modifier
                        .weight(1f)
                        .sizeIn(minHeight = 48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Lyrics,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Lyrics", maxLines = 1)
                }

                OutlinedButton(
                    onClick = onOpenDiagnostics,
                    modifier = Modifier
                        .weight(1f)
                        .sizeIn(minHeight = 48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.GraphicEq,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Path", maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun AudioSafetyModeCard(
    mode: AudioOutputMode,
    isBitPerfectMixerActive: Boolean,
    isFixedVolumeRisk: Boolean,
    headline: String,
    outputDeviceSummary: String,
    onSwitchToSafeMode: () -> Unit,
    onRequestBitPerfectMode: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    val isSafe = mode == AudioOutputMode.SAFE_VOLUME_MODE
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (isSafe) Icons.Filled.Shield else Icons.Filled.GraphicEq,
                        contentDescription = null,
                        tint = if (isSafe) EmeraldSafe else IcePrimary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Audio Output Mode",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                TextButton(
                    onClick = onOpenDiagnostics,
                    modifier = Modifier.sizeIn(minHeight = 48.dp),
                ) {
                    Text("Details")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onSwitchToSafeMode,
                    modifier = Modifier
                        .weight(1f)
                        .sizeIn(minHeight = 48.dp),
                    colors = if (isSafe) {
                        ButtonDefaults.buttonColors(
                            containerColor = EmeraldSafeContainer,
                            contentColor = OnEmeraldSafeContainer,
                        )
                    } else {
                        ButtonDefaults.outlinedButtonColors()
                    },
                ) {
                    Text("Safe Mode", maxLines = 1)
                }

                Button(
                    onClick = onRequestBitPerfectMode,
                    modifier = Modifier
                        .weight(1f)
                        .sizeIn(minHeight = 48.dp),
                    colors = if (!isSafe) {
                        ButtonDefaults.buttonColors(
                            containerColor = if (isBitPerfectMixerActive) IcePrimaryContainer else AmberIndicatedContainer,
                            contentColor = if (isBitPerfectMixerActive) OnIcePrimaryContainer else OnAmberIndicatedContainer,
                        )
                    } else {
                        ButtonDefaults.outlinedButtonColors()
                    },
                ) {
                    Text("Bit-Perfect Opt-In", maxLines = 1)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = headline,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = when {
                    isSafe -> EmeraldSafe
                    isBitPerfectMixerActive -> IcePrimary
                    else -> AmberIndicated
                },
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Output: $outputDeviceSummary",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (isFixedVolumeRisk) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Caution: Hardware volume unverified — output may be fixed at 100% (0 dBFS).",
                    style = MaterialTheme.typography.labelSmall,
                    color = CoralWarning,
                )
            }
        }
    }
}

@Composable
private fun SafetyAlertBanner(
    message: String,
    isError: Boolean,
    onDismiss: (() -> Unit)?,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = if (isError) CoralWarningContainer else AmberIndicatedContainer,
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
                modifier = Modifier.size(20.dp),
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
fun HearingSafetyWarningDialog(
    deviceName: String,
    onConfirmBitPerfect: () -> Unit,
    onDismissStaySafe: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissStaySafe,
        icon = {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = "Hearing safety warning",
                tint = CoralWarning,
                modifier = Modifier.size(32.dp),
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
