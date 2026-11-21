package com.cangkir.bitperfectplayer.ui.settings

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
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cangkir.bitperfectplayer.audio.ReplayGainConfig
import com.cangkir.bitperfectplayer.audio.ReplayGainMode
import com.cangkir.bitperfectplayer.audio.ReplayGainProcessor
import com.cangkir.bitperfectplayer.audio.SleepTimerState
import com.cangkir.bitperfectplayer.ui.SleepTimerSettingsValueLeaf
import com.cangkir.bitperfectplayer.ui.theme.IcePrimary
import com.cangkir.bitperfectplayer.ui.theme.IcePrimaryContainer
import com.cangkir.bitperfectplayer.ui.theme.OnIcePrimaryContainer
import com.cangkir.bitperfectplayer.ui.theme.TextMuted
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.StateFlow

@Composable
internal fun SettingsAudioEngineSection(
    gaplessEnabled: Boolean,
    isBitPerfectOptIn: Boolean,
    isBitPerfectMixerActive: Boolean,
    rgConfig: ReplayGainConfig,
    modeLabel: String,
    modeDotColor: Color,
    activeDeviceName: String,
    sleepTimerStateFlow: StateFlow<SleepTimerState>,
    onSetGaplessEnabled: (Boolean) -> Unit,
    onSetReplayGainMode: (ReplayGainMode) -> Unit,
    onSetReplayGainPreampDb: (Float) -> Unit,
    onSetReplayGainPreventClipping: (Boolean) -> Unit,
    onOpenSleepTimer: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
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
                    checked = gaplessEnabled,
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
                    .clickable(onClick = onOpenSleepTimer)
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
                    tint = if (isBitPerfectMixerActive) IcePrimary else MaterialTheme.colorScheme.onSurfaceVariant,
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
                            text = "$modeLabel · $activeDeviceName",
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
