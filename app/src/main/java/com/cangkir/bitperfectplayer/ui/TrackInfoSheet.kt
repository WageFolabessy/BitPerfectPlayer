package com.cangkir.bitperfectplayer.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cangkir.bitperfectplayer.audio.AudioFormatFormatter
import com.cangkir.bitperfectplayer.audio.ReplayGainProcessor
import com.cangkir.bitperfectplayer.library.TrackTechnicalInfo
import com.cangkir.bitperfectplayer.ui.theme.IcePrimary
import com.cangkir.bitperfectplayer.ui.theme.TextMuted
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackInfoBottomSheet(
    info: TrackTechnicalInfo,
    onCopiedMessage: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val track = info.track

    val formatLabel = remember(track.container, track.codec) {
        AudioFormatFormatter.formatContainerAndCodec(track.container, track.codec)
    }
    val sampleRateLabel = remember(track.sampleRateHz) {
        if (track.sampleRateHz > 0) AudioFormatFormatter.formatSampleRateHz(track.sampleRateHz) else "Unknown"
    }
    val bitDepthLabel = remember(track.bitDepth) {
        track.bitDepth?.takeIf { it > 0 }?.let { "$it-bit" } ?: "Lossy / Variable"
    }
    val fileSizeLabel = remember(info.fileSizeBytes) {
        formatFileSizeBytes(info.fileSizeBytes)
    }
    val durationLabel = remember(track.durationMs) {
        if (track.durationMs > 0L) AudioFormatFormatter.formatDurationMs(track.durationMs) else "Unknown"
    }
    val folderPathLabel = remember(track.relativePath, track.folderName) {
        track.relativePath?.trim()?.takeIf { it.isNotEmpty() } ?: track.folderName
    }
    val lastPlayedLabel = remember(info.lastPlayedAtEpochMs) {
        val ts = info.lastPlayedAtEpochMs
        if (ts == null || ts <= 0L) {
            "Never"
        } else {
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault())
                .format(Date(ts))
        }
    }
    val totalListeningLabel = remember(info.totalPlayTimeMs) {
        formatListeningDuration(info.totalPlayTimeMs)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column {
                Text(
                    text = "Track Info",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (info.isLoadingDetails) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
            }

            // Section 1: File & Metadata
            TrackInfoGroupHeader("METADATA & FILE")
            TrackInfoGroupCard {
                InfoRow("Title", track.title)
                InfoHairline()
                InfoRow("Artist", track.artist)
                InfoHairline()
                InfoRow("Album", track.album)
                InfoHairline()
                InfoRow("Folder / Path", folderPathLabel)
                InfoHairline()
                InfoRow("Filename", track.fileName)
                InfoHairline()
                InfoRow("File Size", fileSizeLabel)
                InfoHairline()
                InfoRow("Duration", durationLabel)
            }

            // Section 2: Audio Stream & ReplayGain
            TrackInfoGroupHeader("AUDIO STREAM")
            TrackInfoGroupCard {
                InfoRow("Container / Codec", formatLabel)
                InfoHairline()
                InfoRow("Sample Rate", sampleRateLabel)
                InfoHairline()
                InfoRow("Bit Depth", bitDepthLabel)
                InfoHairline()
                val rg = info.replayGainMetadata
                InfoRow(
                    label = "ReplayGain Track",
                    value = formatReplayGainTagLine(rg?.trackGainDb, rg?.trackPeak, info.isLoadingDetails),
                )
                InfoHairline()
                InfoRow(
                    label = "ReplayGain Album",
                    value = formatReplayGainTagLine(rg?.albumGainDb, rg?.albumPeak, info.isLoadingDetails),
                )
            }

            // Section 3: Local Identity & Listening Stats
            TrackInfoGroupHeader("LOCAL IDENTITY & STATS")
            TrackInfoGroupCard {
                val fp = info.contentFingerprint
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .sizeIn(minHeight = 48.dp)
                        .clickable(enabled = !fp.isNullOrBlank()) {
                            if (!fp.isNullOrBlank()) {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                clipboard?.setPrimaryClip(ClipData.newPlainText("SHA-256 Fingerprint", fp))
                                onCopiedMessage("Copied SHA-256 fingerprint")
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "SHA-256 Fingerprint",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = when {
                                !fp.isNullOrBlank() -> abbreviateFingerprint(fp)
                                info.isLoadingDetails -> "Computing..."
                                else -> "Unavailable"
                            },
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = if (!fp.isNullOrBlank()) IcePrimary else TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (!fp.isNullOrBlank()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.Filled.ContentCopy,
                            contentDescription = "Copy full SHA-256 fingerprint",
                            tint = IcePrimary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                InfoHairline()
                InfoRow(
                    label = "Play Count",
                    value = if (info.isLoadingDetails) "Loading..." else "${info.playCount} ${if (info.playCount == 1) "play" else "plays"}",
                )
                InfoHairline()
                InfoRow(
                    label = "Total Listening Time",
                    value = if (info.isLoadingDetails) "Loading..." else totalListeningLabel,
                )
                InfoHairline()
                InfoRow(
                    label = "Last Played",
                    value = if (info.isLoadingDetails) "Loading..." else lastPlayedLabel,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun TrackInfoGroupHeader(text: String) {
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
private fun TrackInfoGroupCard(content: @Composable () -> Unit) {
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
private fun InfoRow(label: String, value: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
            maxLines = 1,
            softWrap = false,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun InfoHairline() {
    HorizontalDivider(
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
        modifier = Modifier.padding(horizontal = 14.dp),
    )
}

private fun abbreviateFingerprint(fp: String): String =
    if (fp.length >= 24) "${fp.take(12)}…${fp.takeLast(10)}  (tap to copy)" else fp

private fun formatReplayGainTagLine(gainDb: Float?, peak: Float?, isLoading: Boolean): String {
    if (isLoading && gainDb == null && peak == null) return "Inspecting header..."
    if (gainDb == null && peak == null) return "None detected"
    val parts = mutableListOf<String>()
    gainDb?.let { parts.add(ReplayGainProcessor.formatSignedDb(it)) }
    peak?.let {
        val scaled = kotlin.math.round(it * 10000.0).toInt()
        val whole = scaled / 10000
        val frac = (scaled % 10000).toString().padStart(4, '0')
        parts.add("Peak $whole.$frac")
    }
    return parts.joinToString(" · ")
}

private fun formatFileSizeBytes(bytes: Long): String {
    if (bytes <= 0L) return "Unknown"
    val kb = bytes / 1024.0
    if (kb < 1024.0) {
        val tenths = kotlin.math.round(kb * 10.0).toLong()
        return "${tenths / 10}.${tenths % 10} KB"
    }
    val mb = kb / 1024.0
    if (mb < 1024.0) {
        val hundredths = kotlin.math.round(mb * 100.0).toLong()
        return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')} MB"
    }
    val gb = mb / 1024.0
    val hundredths = kotlin.math.round(gb * 100.0).toLong()
    return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')} GB"
}

private fun formatListeningDuration(totalMs: Long): String {
    val totalSeconds = (totalMs / 1000L).coerceAtLeast(0L)
    if (totalSeconds == 0L) return "0s"
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return when {
        hours > 0L -> "${hours}h ${minutes}m"
        minutes > 0L -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}
