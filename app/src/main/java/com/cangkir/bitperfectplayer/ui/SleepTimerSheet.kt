package com.cangkir.bitperfectplayer.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cangkir.bitperfectplayer.audio.SleepTimerPreset
import com.cangkir.bitperfectplayer.audio.SleepTimerState
import com.cangkir.bitperfectplayer.ui.theme.IcePrimary
import com.cangkir.bitperfectplayer.ui.theme.IcePrimaryContainer
import com.cangkir.bitperfectplayer.ui.theme.OnIcePrimaryContainer
import com.cangkir.bitperfectplayer.ui.theme.TextMuted
import kotlinx.coroutines.flow.StateFlow

/**
 * Narrow leaf composable collecting Sleep Timer 1-second ticks ONLY for the compact Now Playing
 * TopAppBar action so neither NowPlayingScreen's hero art nor LibraryScreen ever recomposes.
 */
@Composable
fun SleepTimerTopBarButton(sleepTimerStateFlow: StateFlow<SleepTimerState>, onClick: () -> Unit) {
    val timerState by sleepTimerStateFlow.collectAsStateWithLifecycle()
    if (timerState.isActive) {
        Surface(
            modifier = Modifier
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .clickable(onClick = onClick),
            shape = RoundedCornerShape(20.dp),
            color = IcePrimaryContainer,
            border = BorderStroke(0.5.dp, IcePrimary.copy(alpha = 0.55f)),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Bedtime,
                    contentDescription = "Sleep timer active: ${timerState.compactBadgeLabel}",
                    tint = OnIcePrimaryContainer,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = timerState.compactBadgeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = OnIcePrimaryContainer,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    } else {
        IconButton(
            onClick = onClick,
            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Bedtime,
                contentDescription = "Open sleep timer",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Narrow leaf composable rendering live Sleep Timer status inside SettingsScreen without
 * recomposing the surrounding Settings LazyColumn on 1-second countdown ticks.
 */
@Composable
fun SleepTimerSettingsValueLeaf(sleepTimerStateFlow: StateFlow<SleepTimerState>) {
    val timerState by sleepTimerStateFlow.collectAsStateWithLifecycle()
    val summary = when {
        !timerState.isActive -> "Off"
        timerState.isEndOfTrackArmed -> "End of current track"
        else -> "${timerState.compactBadgeLabel} remaining"
    }
    Text(
        text = summary,
        style = MaterialTheme.typography.bodySmall,
        color = if (timerState.isActive) IcePrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        softWrap = false,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerBottomSheet(
    sleepTimerStateFlow: StateFlow<SleepTimerState>,
    isBitPerfectOptIn: Boolean,
    onSelectPreset: (SleepTimerPreset) -> Unit,
    onDismiss: () -> Unit,
) {
    val timerState by sleepTimerStateFlow.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Sleep Timer",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (timerState.isActive) {
                    Text(
                        text = if (timerState.isEndOfTrackArmed) {
                            "After current track"
                        } else {
                            timerState.compactBadgeLabel
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = IcePrimary,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (isBitPerfectOptIn) {
                    "Bit-Perfect Mode active: pauses cleanly at 00:00 without digital volume scaling."
                } else {
                    "Safe Mode: smoothly fades volume out over the final 3 seconds before pausing and restoring 100%."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(14.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    SleepTimerPreset.entries.forEachIndexed { index, preset ->
                        val selected = timerState.preset == preset
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .sizeIn(minHeight = 52.dp)
                                .clickable {
                                    onSelectPreset(preset)
                                    onDismiss()
                                }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = preset.label,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selected) IcePrimary else MaterialTheme.colorScheme.onSurface,
                            )
                            if (selected) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = "Selected",
                                    tint = IcePrimary,
                                    modifier = Modifier.size(20.dp),
                                )
                            } else if (preset == SleepTimerPreset.END_OF_TRACK) {
                                Text(
                                    text = "No fade",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextMuted,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        }
                        if (index < SleepTimerPreset.entries.lastIndex) {
                            HorizontalDivider(
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                                modifier = Modifier.padding(horizontal = 14.dp),
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
