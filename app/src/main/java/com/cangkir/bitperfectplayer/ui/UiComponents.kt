package com.cangkir.bitperfectplayer.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cangkir.bitperfectplayer.audio.AudioOutputMode
import com.cangkir.bitperfectplayer.audio.PlaybackControllerState
import com.cangkir.bitperfectplayer.audio.PlaybackTrack
import com.cangkir.bitperfectplayer.ui.theme.AmberIndicated
import com.cangkir.bitperfectplayer.ui.theme.AmberIndicatedContainer
import com.cangkir.bitperfectplayer.ui.theme.EmeraldSafe
import com.cangkir.bitperfectplayer.ui.theme.EmeraldSafeContainer
import com.cangkir.bitperfectplayer.ui.theme.HiResGold
import com.cangkir.bitperfectplayer.ui.theme.HiResGoldContainer
import com.cangkir.bitperfectplayer.ui.theme.IcePrimary
import com.cangkir.bitperfectplayer.ui.theme.IcePrimaryContainer
import com.cangkir.bitperfectplayer.ui.theme.OnAmberIndicatedContainer
import com.cangkir.bitperfectplayer.ui.theme.OnEmeraldSafeContainer
import com.cangkir.bitperfectplayer.ui.theme.OnHiResGoldContainer
import com.cangkir.bitperfectplayer.ui.theme.OnIcePrimaryContainer
import java.util.Locale

fun formatDurationMs(durationMs: Long): String {
    val totalSeconds = (durationMs.coerceAtLeast(0L) / 1000L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

fun PlaybackTrack.isLosslessFormat(): Boolean =
    container.equals("FLAC", ignoreCase = true) || container.equals("WAV", ignoreCase = true)

fun PlaybackTrack.isHiResLossless(): Boolean =
    isLosslessFormat() && ((bitDepth ?: 0) >= 24 || sampleRateHz > 48_000)

@Composable
fun FormatBadgeChip(
    text: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    contentColor: Color = MaterialTheme.colorScheme.primary,
    borderColor: Color = contentColor.copy(alpha = 0.25f),
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = containerColor,
        border = BorderStroke(0.75.dp, borderColor),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
fun TrackFormatBadge(
    track: PlaybackTrack,
    modifier: Modifier = Modifier,
) {
    val (bg, fg, border) = when {
        track.isHiResLossless() -> Triple(
            HiResGoldContainer,
            OnHiResGoldContainer,
            HiResGold.copy(alpha = 0.45f),
        )
        track.isLosslessFormat() -> Triple(
            IcePrimaryContainer,
            OnIcePrimaryContainer,
            IcePrimary.copy(alpha = 0.4f),
        )
        else -> Triple(
            MaterialTheme.colorScheme.surfaceContainerHighest,
            MaterialTheme.colorScheme.onSurfaceVariant,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
        )
    }
    FormatBadgeChip(
        text = track.formatBadge,
        modifier = modifier,
        containerColor = bg,
        contentColor = fg,
        borderColor = border,
    )
}

/**
 * Unified segmented Safe Mode vs Bit-Perfect Opt-In switch shared across Now Playing and Diagnostics.
 */
@Composable
fun AudioModeSegmentedSwitch(
    mode: AudioOutputMode,
    isBitPerfectMixerActive: Boolean,
    onSwitchToSafeMode: () -> Unit,
    onRequestBitPerfectMode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isSafe = mode == AudioOutputMode.SAFE_VOLUME_MODE
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Button(
                onClick = onSwitchToSafeMode,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                modifier = Modifier
                    .weight(1f)
                    .sizeIn(minHeight = 48.dp),
                colors = if (isSafe) {
                    ButtonDefaults.buttonColors(
                        containerColor = EmeraldSafeContainer,
                        contentColor = OnEmeraldSafeContainer,
                    )
                } else {
                    ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            ) {
                Text(
                    text = "Safe Mode",
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = if (isSafe) FontWeight.SemiBold else FontWeight.Medium,
                )
            }

            Button(
                onClick = onRequestBitPerfectMode,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                modifier = Modifier
                    .weight(1.15f)
                    .sizeIn(minHeight = 48.dp),
                colors = if (!isSafe) {
                    ButtonDefaults.buttonColors(
                        containerColor = if (isBitPerfectMixerActive) IcePrimaryContainer else AmberIndicatedContainer,
                        contentColor = if (isBitPerfectMixerActive) OnIcePrimaryContainer else OnAmberIndicatedContainer,
                    )
                } else {
                    ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            ) {
                Text(
                    text = "Bit-Perfect Opt-In",
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = if (!isSafe) FontWeight.SemiBold else FontWeight.Medium,
                )
            }
        }
    }
}

/**
 * Persistent studio-dock mini-player bar with quick Play/Pause and Next controls (>= 48 dp touch targets).
 */
@Composable
fun MiniPlayerBar(
    playbackState: PlaybackControllerState,
    onOpenNowPlaying: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val track = playbackState.activeTrack ?: return
    val progress = if (playbackState.durationMs > 0L) {
        (playbackState.currentPositionMs.toFloat() / playbackState.durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val isBitPerfectOptIn = playbackState.engineState.safetyState.mode == AudioOutputMode.BIT_PERFECT_OPT_IN
    val statusDotColor = when {
        playbackState.engineState.diagnostics.isBitPerfectMixerActive -> IcePrimary
        isBitPerfectOptIn -> AmberIndicated
        else -> EmeraldSafe
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 6.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.5.dp),
                color = statusDotColor,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenNowPlaying)
                    .semantics { contentDescription = "Open Now Playing for ${track.title}" }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        modifier = Modifier.size(42.dp),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, statusDotColor.copy(alpha = 0.45f)),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Filled.GraphicEq,
                                contentDescription = null,
                                tint = statusDotColor,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = track.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(statusDotColor),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "${track.artist} • ${track.formatBadge}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(4.dp))

                FilledTonalIconButton(
                    onClick = onTogglePlayPause,
                    modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                ) {
                    Icon(
                        imageVector = if (playbackState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playbackState.isPlaying) "Pause playback" else "Resume playback",
                    )
                }

                IconButton(
                    onClick = onNext,
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.SkipNext,
                        contentDescription = "Next track",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}
