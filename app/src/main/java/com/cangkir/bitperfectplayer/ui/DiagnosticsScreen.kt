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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cangkir.bitperfectplayer.audio.AudioOutputMode
import com.cangkir.bitperfectplayer.audio.DiagnosticStageEntry
import com.cangkir.bitperfectplayer.audio.PlaybackControllerState
import com.cangkir.bitperfectplayer.audio.VerificationLevel
import com.cangkir.bitperfectplayer.ui.nowplaying.HearingSafetyWarningDialog
import com.cangkir.bitperfectplayer.ui.theme.AmberIndicated
import com.cangkir.bitperfectplayer.ui.theme.AmberIndicatedContainer
import com.cangkir.bitperfectplayer.ui.theme.CoralWarning
import com.cangkir.bitperfectplayer.ui.theme.CoralWarningContainer
import com.cangkir.bitperfectplayer.ui.theme.EmeraldSafe
import com.cangkir.bitperfectplayer.ui.theme.EmeraldSafeContainer
import com.cangkir.bitperfectplayer.ui.theme.OnAmberIndicatedContainer
import com.cangkir.bitperfectplayer.ui.theme.OnCoralWarningContainer
import com.cangkir.bitperfectplayer.ui.theme.OnEmeraldSafeContainer
import com.cangkir.bitperfectplayer.ui.theme.TextMuted

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
        diagnostics.isBitPerfectMixerActive -> MaterialTheme.colorScheme.primary
        isSafe -> EmeraldSafe
        else -> AmberIndicated
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Audio Diagnostics", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = "6-Stage Signal Path & Verification Boundaries",
                            style = MaterialTheme.typography.labelMedium,
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
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = WindowLayoutMetrics.EXPANDED_CONTENT_MAX_WIDTH_DP.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // 1. Live Output Status & Mode Switcher (Flat Utilitarian Panel)
                item(key = "headline", contentType = "diag_headline") {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline),
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(statusAccent),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = when {
                                        diagnostics.isBitPerfectMixerActive -> "BIT-PERFECT HAL ACTIVE"
                                        diagnostics.isNativeSampleRateMixerActive && isSafe -> "SAFE MODE · NATIVE RATE USB"
                                        diagnostics.isNativeSampleRateMixerActive -> "NATIVE RATE USB MIXER"
                                        isSafe -> "SAFE MODE ACTIVE"
                                        else -> "CLEAN NATIVE FALLBACK"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = statusAccent,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = diagnostics.pathStatusHeadline,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            AudioModeSegmentedSwitch(
                                mode = safetyState.mode,
                                isBitPerfectMixerActive = diagnostics.isBitPerfectMixerActive,
                                onSwitchToSafeMode = onSwitchToSafeMode,
                                onRequestBitPerfectMode = onRequestBitPerfectMode,
                            )
                        }
                    }
                }

                // 2. Real-Time Signal Summary (SPEC Section 3.5 — Flat Hairline Grouped Table)
                item(key = "realtime_summary", contentType = "diag_summary") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "SIGNAL SUMMARY",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextMuted,
                            modifier = Modifier.padding(horizontal = 2.dp),
                        )
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline),
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                DiagnosticKeyValueRow(
                                    label = "Source Format",
                                    value = diagnostics.sourceFormatSummary,
                                )
                                HairlineInsetDivider()
                                DiagnosticKeyValueRow(
                                    label = "Decoded PCM",
                                    value = diagnostics.decodedPcmSummary,
                                )
                                HairlineInsetDivider()
                                DiagnosticKeyValueRow(
                                    label = "Output Device",
                                    value = diagnostics.activeOutputDeviceSummary,
                                )
                                HairlineInsetDivider()
                                DiagnosticKeyValueRow(
                                    label = "Engine & Mixer",
                                    value = diagnostics.activeEngineAndMixerSummary,
                                )
                                HairlineInsetDivider()
                                DiagnosticKeyValueRow(
                                    label = "DSP / EQ / Volume",
                                    value = diagnostics.dspEqVolumeStatusSummary,
                                )
                            }
                        }
                    }
                }

                // 3. 6-Stage Header & Confidence Legend
                item(key = "legend", contentType = "diag_legend") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "6-STAGE SIGNAL CHAIN",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextMuted,
                            modifier = Modifier.padding(horizontal = 2.dp),
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

                // 4. All 6 Separated Audio Stages (Flattened — Zero Card-in-Card Nesting)
                itemsIndexed(
                    items = diagnostics.stages,
                    key = { _, entry -> entry.stage.stageNumber },
                    contentType = { _, _ -> "diag_stage_row" },
                ) { index, entry ->
                    DiagnosticStageRow(entry = entry)
                    if (index < diagnostics.stages.lastIndex) {
                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }

                // 5. Mandatory Verbatim Maximum Verification Claim Notice (SPEC Section 3.5)
                item(key = "claim_notice", contentType = "diag_claim") {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = CoralWarningContainer,
                        border = BorderStroke(0.5.dp, CoralWarning.copy(alpha = 0.45f)),
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                                    contentDescription = null,
                                    tint = CoralWarning,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Maximum Verification Claim Boundary",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = OnCoralWarningContainer,
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
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
}

@Composable
private fun HairlineInsetDivider() {
    HorizontalDivider(
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
        modifier = Modifier.padding(horizontal = 14.dp),
    )
}

@Composable
private fun DiagnosticKeyValueRow(label: String, value: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = TextMuted,
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
private fun DiagnosticStageRow(entry: DiagnosticStageEntry) {
    val tierAccent: Color = when (entry.verificationLevel) {
        VerificationLevel.VERIFIED -> EmeraldSafe
        VerificationLevel.INDICATED -> AmberIndicated
        VerificationLevel.UNVERIFIABLE -> CoralWarning
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f),
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 3.dp, height = 14.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(tierAccent),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "STAGE ${entry.stage.stageNumber} · ${entry.stage.title}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            VerificationBadge(level = entry.verificationLevel)
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = entry.summary,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f),
            modifier = Modifier.padding(start = 11.dp),
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = entry.detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 11.dp),
        )
    }
}

@Composable
fun VerificationBadge(level: VerificationLevel) {
    val badgeStyle = when (level) {
        VerificationLevel.VERIFIED -> VerificationBadgeStyle(
            label = "VERIFIED",
            bg = EmeraldSafeContainer,
            fg = OnEmeraldSafeContainer,
            dot = EmeraldSafe,
        )
        VerificationLevel.INDICATED -> VerificationBadgeStyle(
            label = "INDICATED",
            bg = AmberIndicatedContainer,
            fg = OnAmberIndicatedContainer,
            dot = AmberIndicated,
        )
        VerificationLevel.UNVERIFIABLE -> VerificationBadgeStyle(
            label = "UNVERIFIABLE",
            bg = CoralWarningContainer,
            fg = OnCoralWarningContainer,
            dot = CoralWarning,
        )
    }
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = badgeStyle.bg,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(badgeStyle.dot),
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = badgeStyle.label,
                style = MaterialTheme.typography.labelSmall,
                color = badgeStyle.fg,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

private data class VerificationBadgeStyle(val label: String, val bg: Color, val fg: Color, val dot: Color)
