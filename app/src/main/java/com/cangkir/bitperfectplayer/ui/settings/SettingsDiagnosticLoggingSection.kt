package com.cangkir.bitperfectplayer.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cangkir.bitperfectplayer.diagnostics.DiagnosticLogStats
import com.cangkir.bitperfectplayer.ui.theme.CoralWarning
import com.cangkir.bitperfectplayer.ui.theme.studioSwitchColors

@Composable
internal fun SettingsDiagnosticLoggingSection(
    enabled: Boolean,
    logStats: DiagnosticLogStats,
    onSetEnabled: (Boolean) -> Unit,
    onExportClick: () -> Unit,
    onShareClick: () -> Unit,
    onClearClick: () -> Unit,
) {
    val canExportOrShare = logStats.hasLogs || enabled
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsSectionHeader("DIAGNOSTIC LOGGING")
        SettingsGroupCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .sizeIn(minHeight = 60.dp)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Enable Local Diagnostic Logging",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Opt-in only (OFF by default). Records bounded local audio pipeline, " +
                            "performance, and crash logs privately on-device. Never logs song titles, " +
                            "artists, filenames, or storage paths.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Switch(
                    checked = enabled,
                    onCheckedChange = onSetEnabled,
                    colors = studioSwitchColors(),
                    modifier = Modifier.semantics {
                        contentDescription = "Toggle local diagnostic logging"
                    },
                )
            }

            SettingsHairline()

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                SettingsKeyValueRow("Stored Logs", logStats.formatSummary())
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = onExportClick,
                        enabled = canExportOrShare,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                        modifier = Modifier
                            .weight(1.28f)
                            .sizeIn(minHeight = 48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.SaveAlt,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Export Logs (.zip)",
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    OutlinedButton(
                        onClick = onShareClick,
                        enabled = canExportOrShare,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .sizeIn(minHeight = 48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Share,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Share Logs",
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onClearClick,
                    enabled = logStats.hasLogs,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = CoralWarning,
                    ),
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (logStats.hasLogs) {
                            CoralWarning.copy(alpha = 0.65f)
                        } else {
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                        },
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .sizeIn(minHeight = 48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.DeleteOutline,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Clear Logs", maxLines = 1, softWrap = false)
                }
            }
        }
    }
}
