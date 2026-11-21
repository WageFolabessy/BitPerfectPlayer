package com.cangkir.bitperfectplayer.ui.settings

import android.net.Uri
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cangkir.bitperfectplayer.data.NowPlayingArtworkStyle
import com.cangkir.bitperfectplayer.data.SpectrumColorStyle
import com.cangkir.bitperfectplayer.ui.theme.IcePrimaryContainer
import com.cangkir.bitperfectplayer.ui.theme.OnIcePrimaryContainer
import com.cangkir.bitperfectplayer.ui.theme.TextMuted

@Composable
internal fun SettingsDisplaySection(
    artworkStyle: NowPlayingArtworkStyle,
    onSetArtworkStyle: (NowPlayingArtworkStyle) -> Unit,
    spectrumVisualizerEnabled: Boolean,
    onSetSpectrumVisualizerEnabled: (Boolean) -> Unit,
    spectrumColorStyle: SpectrumColorStyle,
    onSetSpectrumColorStyle: (SpectrumColorStyle) -> Unit,
    spectrumSparksEnabled: Boolean,
    onSetSpectrumSparksEnabled: (Boolean) -> Unit,
) {
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

@Composable
internal fun SettingsLibraryStorageSection(
    sortedFolders: List<String>,
    onAddFolderClick: () -> Unit,
    onRefreshLibrary: () -> Unit,
    onRemoveSafFolder: (String) -> Unit,
) {
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
                    onClick = onAddFolderClick,
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

private fun formatReadableSafLabel(uriString: String): String {
    val decoded = runCatching { Uri.decode(uriString) }.getOrDefault(uriString)
    val treeMarker = "/tree/"
    val afterTree = decoded.substringAfter(treeMarker, decoded)
    val cleaned = afterTree.substringAfter(':', afterTree).trim('/')
    return cleaned.ifBlank { decoded }
}
