package com.cangkir.bitperfectplayer.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cangkir.bitperfectplayer.audio.AudioOutputMode
import com.cangkir.bitperfectplayer.audio.DiagnosticStageEntry
import com.cangkir.bitperfectplayer.audio.PlaybackControllerState
import com.cangkir.bitperfectplayer.audio.VerificationLevel
import com.cangkir.bitperfectplayer.ui.theme.AmberIndicated
import com.cangkir.bitperfectplayer.ui.theme.AmberIndicatedContainer
import com.cangkir.bitperfectplayer.ui.theme.CoralWarning
import com.cangkir.bitperfectplayer.ui.theme.CoralWarningContainer
import com.cangkir.bitperfectplayer.ui.theme.EmeraldSafe
import com.cangkir.bitperfectplayer.ui.theme.EmeraldSafeContainer
import com.cangkir.bitperfectplayer.ui.theme.IcePrimary
import com.cangkir.bitperfectplayer.ui.theme.OnAmberIndicatedContainer
import com.cangkir.bitperfectplayer.ui.theme.OnCoralWarningContainer
import com.cangkir.bitperfectplayer.ui.theme.OnEmeraldSafeContainer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    playbackState: PlaybackControllerState,
    onBack: () -> Unit,
    onSwitchToSafeMode: () -> Unit,
    onRequestBitPerfectMode: () -> Unit,
    onConfirmBitPerfectWarning: () -> Unit,
    onDismissSafetyWarning: () -> Unit,
) {
    val safetyState = playbackState.engineState.safetyState
    val diagnostics = playbackState.engineState.diagnostics
    val isSafe = safetyState.mode == AudioOutputMode.SAFE_VOLUME_MODE

    if (safetyState.isPendingSafetyWarning) {
        HearingSafetyWarningDialog(
            deviceName = safetyState.activeDeviceName,
            onConfirmBitPerfect = onConfirmBitPerfectWarning,
            onDismissStaySafe = onDismissSafetyWarning,
        )
    }

    val statusAccent = when {
        diagnostics.isBitPerfectMixerActive -> IcePrimary
        isSafe -> EmeraldSafe
        else -> AmberIndicated
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Audio Diagnostics", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = "6-Stage Signal Path & Verification Boundaries",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 1. Live Status Headline & Unified Segmented Mode Switcher
            item(key = "headline") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    border = BorderStroke(1.dp, statusAccent.copy(alpha = 0.6f)),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = statusAccent.copy(alpha = 0.16f),
                                modifier = Modifier.size(38.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = if (isSafe) Icons.Filled.Shield else Icons.Filled.GraphicEq,
                                        contentDescription = null,
                                        tint = statusAccent,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = when {
                                        diagnostics.isBitPerfectMixerActive -> "BIT-PERFECT HAL ACTIVE"
                                        isSafe -> "SAFE MODE ACTIVE"
                                        else -> "CLEAN NATIVE FALLBACK"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = statusAccent,
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = diagnostics.pathStatusHeadline,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                        AudioModeSegmentedSwitch(
                            mode = safetyState.mode,
                            isBitPerfectMixerActive = diagnostics.isBitPerfectMixerActive,
                            onSwitchToSafeMode = onSwitchToSafeMode,
                            onRequestBitPerfectMode = onRequestBitPerfectMode,
                        )
                    }
                }
            }

            // 2. Real-time SPEC Section 3.5 Summary Fields
            item(key = "realtime_summary") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = "Real-Time Signal Summary",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        DiagnosticKeyValueRow(
                            icon = Icons.Filled.AudioFile,
                            label = "Source Format",
                            value = diagnostics.sourceFormatSummary,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f))
                        DiagnosticKeyValueRow(
                            icon = Icons.Filled.Memory,
                            label = "Decoded PCM",
                            value = diagnostics.decodedPcmSummary,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f))
                        DiagnosticKeyValueRow(
                            icon = Icons.Filled.Headphones,
                            label = "Output Device",
                            value = diagnostics.activeOutputDeviceSummary,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f))
                        DiagnosticKeyValueRow(
                            icon = Icons.Filled.Tune,
                            label = "Engine & Mixer",
                            value = diagnostics.activeEngineAndMixerSummary,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f))
                        DiagnosticKeyValueRow(
                            icon = Icons.Filled.Equalizer,
                            label = "DSP / EQ / Volume",
                            value = diagnostics.dspEqVolumeStatusSummary,
                        )
                    }
                }
            }

            // 3. Confidence Legend & Section Header
            item(key = "legend") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "6-Stage Audio Signal Chain",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        VerificationBadge(VerificationLevel.VERIFIED)
                        VerificationBadge(VerificationLevel.INDICATED)
                        VerificationBadge(VerificationLevel.UNVERIFIABLE)
                    }
                }
            }

            // 4. All 6 Separated Audio Stages (SPEC Section 3.1)
            items(diagnostics.stages, key = { it.stage.stageNumber }) { entry ->
                DiagnosticStageCard(entry = entry)
            }

            // 5. Mandatory Maximum Verification Claim Notice (SPEC Section 3.5)
            item(key = "claim_notice") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = CoralWarningContainer),
                    border = BorderStroke(1.dp, CoralWarning.copy(alpha = 0.55f)),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                                contentDescription = null,
                                tint = CoralWarning,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Maximum Verification Claim Boundary",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = OnCoralWarningContainer,
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = diagnostics.maximumClaimNotice,
                            style = MaterialTheme.typography.bodySmall,
                            color = OnCoralWarningContainer,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticKeyValueRow(
    icon: ImageVector,
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
            modifier = Modifier
                .padding(top = 2.dp)
                .size(16.dp),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun DiagnosticStageCard(entry: DiagnosticStageEntry) {
    val tierAccent: Color = when (entry.verificationLevel) {
        VerificationLevel.VERIFIED -> EmeraldSafe
        VerificationLevel.INDICATED -> AmberIndicated
        VerificationLevel.UNVERIFIABLE -> CoralWarning
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Top bar: STAGE pill on left, confidence badge on right (zero title wrapping collisions)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(width = 3.dp, height = 14.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(tierAccent),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "STAGE ${entry.stage.stageNumber}",
                        style = MaterialTheme.typography.labelSmall,
                        color = tierAccent,
                        fontWeight = FontWeight.Bold,
                    )
                }
                VerificationBadge(level = entry.verificationLevel)
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = entry.stage.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = entry.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f),
            )

            Spacer(modifier = Modifier.height(8.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
            ) {
                Text(
                    text = entry.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
fun VerificationBadge(level: VerificationLevel) {
    val (label, bg, fg, icon) = when (level) {
        VerificationLevel.VERIFIED -> Quad(
            "VERIFIED",
            EmeraldSafeContainer,
            OnEmeraldSafeContainer,
            Icons.Filled.CheckCircle,
        )
        VerificationLevel.INDICATED -> Quad(
            "INDICATED",
            AmberIndicatedContainer,
            OnAmberIndicatedContainer,
            Icons.Filled.Info,
        )
        VerificationLevel.UNVERIFIABLE -> Quad(
            "UNVERIFIABLE",
            CoralWarningContainer,
            OnCoralWarningContainer,
            Icons.AutoMirrored.Filled.HelpOutline,
        )
    }
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = bg,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(13.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = fg,
            )
        }
    }
}

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
