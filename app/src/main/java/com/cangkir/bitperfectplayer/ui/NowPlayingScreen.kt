package com.cangkir.bitperfectplayer.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cangkir.bitperfectplayer.audio.AudioOutputMode
import com.cangkir.bitperfectplayer.audio.AudioSafetyController
import com.cangkir.bitperfectplayer.audio.PlaybackControllerState
import com.cangkir.bitperfectplayer.audio.PlaybackTrack
import com.cangkir.bitperfectplayer.audio.RepeatMode
import com.cangkir.bitperfectplayer.audio.SleepTimerPreset
import com.cangkir.bitperfectplayer.audio.SleepTimerState
import com.cangkir.bitperfectplayer.library.AlbumArtworkLoader
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
import com.cangkir.bitperfectplayer.ui.theme.TextMuted
import kotlinx.coroutines.flow.StateFlow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    playbackState: PlaybackControllerState,
    playbackPositionMsFlow: StateFlow<Long>,
    sleepTimerStateFlow: StateFlow<SleepTimerState>,
    albumArtworkLoader: AlbumArtworkLoader,
    isFavorite: Boolean,
    onBack: () -> Unit,
    onToggleFavorite: (PlaybackTrack) -> Unit,
    onAddToPlaylist: (PlaybackTrack) -> Unit,
    onShowTrackInfo: (PlaybackTrack) -> Unit,
    onSelectSleepTimerPreset: (SleepTimerPreset) -> Unit,
    onTogglePlayPause: () -> Unit,
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

    var showAudioModeSheet by remember { mutableStateOf(false) }
    var showSleepTimerSheet by remember { mutableStateOf(false) }
    var overflowMenuExpanded by remember { mutableStateOf(false) }

    // Mandatory Hearing Safety Warning Dialog before activating potentially fixed-volume Bit-Perfect Mode.
    if (safetyState.isPendingSafetyWarning) {
        HearingSafetyWarningDialog(
            deviceName = safetyState.activeDeviceName,
            onConfirmBitPerfect = onConfirmBitPerfectWarning,
            onDismissStaySafe = onDismissSafetyWarning,
        )
    }

    if (showSleepTimerSheet) {
        SleepTimerBottomSheet(
            sleepTimerStateFlow = sleepTimerStateFlow,
            isBitPerfectOptIn = safetyState.mode == AudioOutputMode.BIT_PERFECT_OPT_IN,
            onSelectPreset = onSelectSleepTimerPreset,
            onDismiss = { showSleepTimerSheet = false },
        )
    }

    if (showAudioModeSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showAudioModeSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            AudioModeSheetContent(
                mode = safetyState.mode,
                isBitPerfectMixerActive = diagnostics.isBitPerfectMixerActive,
                isUsbOutputConnected = safetyState.isUsbAudioConnected,
                isFixedVolumeRisk = safetyState.isFixedVolumeRisk,
                headline = diagnostics.pathStatusHeadline,
                outputDeviceSummary = diagnostics.activeOutputDeviceSummary,
                onSwitchToSafeMode = onSwitchToSafeMode,
                onRequestBitPerfectMode = onRequestBitPerfectMode,
                onOpenDiagnostics = {
                    showAudioModeSheet = false
                    onOpenDiagnostics()
                },
            )
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Now Playing",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (track != null) {
                            Text(
                                text = playbackState.queueContext.formatCompactLabel(),
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
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
                    // Isolated 1-second tick leaf: only this button recomposes while sleep timer counts down.
                    SleepTimerTopBarButton(
                        sleepTimerStateFlow = sleepTimerStateFlow,
                        onClick = { showSleepTimerSheet = true },
                    )
                    if (track != null) {
                        IconButton(
                            onClick = { onToggleFavorite(track) },
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                        ) {
                            Icon(
                                imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
                                tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
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
                                            showSleepTimerSheet = true
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
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
                    tint = TextMuted,
                    modifier = Modifier.size(48.dp),
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text("No track selected", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Select a local FLAC, WAV, MP3, M4A, or OGG track from your library.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            return@Scaffold
        }

        val isSafe = safetyState.mode == AudioOutputMode.SAFE_VOLUME_MODE
        val modeDotColor = when {
            diagnostics.isBitPerfectMixerActive -> IcePrimary
            isSafe -> EmeraldSafe
            else -> AmberIndicated
        }
        val modeShortLabel = when {
            diagnostics.isBitPerfectMixerActive -> "Bit-Perfect"
            isSafe -> "Safe Mode"
            else -> "Clean Native"
        }
        val formatSpecText = remember(diagnostics.sourceFormatSummary, track.formatBadge) {
            diagnostics.sourceFormatSummary
                .takeIf { it != "No active track loaded" }
                ?.replace(" • ", " · ")
                ?: track.formatBadge
        }

        val swipeThresholdPx = with(LocalDensity.current) { 76.dp.toPx() }
        var artworkDragOffsetPx by remember(track.uriString) { mutableFloatStateOf(0f) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            playbackState.engineState.lastAutoPauseNotice?.let { notice ->
                SafetyAlertBanner(
                    message = notice,
                    isError = false,
                    onDismiss = onClearAutoPauseNotice,
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
            playbackState.engineState.errorMessage?.let { err ->
                SafetyAlertBanner(
                    message = err,
                    isError = true,
                    onDismiss = null,
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Hero Album Cover Art Centerpiece with horizontal swipe-to-skip gesture (100% offline)
            Box(
                modifier = Modifier
                    .pointerInput(track.uriString, swipeThresholdPx) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                val finalDrag = artworkDragOffsetPx
                                artworkDragOffsetPx = 0f
                                when {
                                    finalDrag <= -swipeThresholdPx -> onNext()
                                    finalDrag >= swipeThresholdPx -> onPrevious()
                                }
                            },
                            onDragCancel = {
                                artworkDragOffsetPx = 0f
                            },
                            onHorizontalDrag = { _, dragAmount ->
                                artworkDragOffsetPx = (artworkDragOffsetPx + dragAmount)
                                    .coerceIn(-swipeThresholdPx * 1.6f, swipeThresholdPx * 1.6f)
                            },
                        )
                    }
                    .graphicsLayer {
                        translationX = artworkDragOffsetPx * 0.32f
                    },
            ) {
                TrackArtworkThumbnail(
                    track = track,
                    artworkLoader = albumArtworkLoader,
                    size = 272.dp,
                    cornerRadius = 16.dp,
                    targetSizePx = AlbumArtworkLoader.HERO_SIZE_PX,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

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
                text = track.artistAlbumLine,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = formatSpecText,
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onOpenDiagnostics)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )

            Spacer(modifier = Modifier.height(18.dp))

            // Isolated 20 Hz Seekable Progress Leaf (hero art & controls do NOT recompose on ticks)
            NowPlayingSeekSection(
                durationMs = durationMs,
                playbackPositionMsFlow = playbackPositionMsFlow,
                onSeekTo = onSeekTo,
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Primary Transport Controls (all >= 48 dp touch targets)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
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
                        tint = if (shuffleOn) MaterialTheme.colorScheme.primary else TextMuted,
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
                    modifier = Modifier.size(64.dp),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.onSurface,
                        contentColor = MaterialTheme.colorScheme.background,
                    ),
                ) {
                    Icon(
                        imageVector = if (playbackState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playbackState.isPlaying) "Pause playback" else "Play audio",
                        modifier = Modifier.size(32.dp),
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
                        tint = if (repeatMode != RepeatMode.OFF) MaterialTheme.colorScheme.primary else TextMuted,
                    )
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            // Compact Clickable Audio-Mode Status Strip (opens bottom sheet for mode switching)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .sizeIn(minHeight = 48.dp)
                    .clickable { showAudioModeSheet = true },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(modeDotColor),
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "$modeShortLabel · ${safetyState.activeDeviceName}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.Filled.ChevronRight,
                        contentDescription = "Configure audio output mode",
                        tint = TextMuted,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Subtle Bottom Utility Strip: Queue, Lyrics, Audio Path (>= 48 dp targets)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    onClick = onOpenQueue,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    modifier = Modifier
                        .weight(1.1f)
                        .sizeIn(minHeight = 48.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Queue (${playbackState.queue.size})",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                TextButton(
                    onClick = onOpenLyrics,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    modifier = Modifier
                        .weight(0.95f)
                        .sizeIn(minHeight = 48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Lyrics,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Lyrics",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                    )
                }

                TextButton(
                    onClick = onOpenDiagnostics,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .sizeIn(minHeight = 48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.GraphicEq,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Audio Path",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

/**
 * Narrow leaf composable collecting 20 Hz playback position so Now Playing's hero artwork,
 * metadata, transport controls, and audio-mode strip never recompose during playback.
 */
@Composable
private fun NowPlayingSeekSection(
    durationMs: Long,
    playbackPositionMsFlow: StateFlow<Long>,
    onSeekTo: (Long) -> Unit,
) {
    val livePositionMs by playbackPositionMsFlow.collectAsStateWithLifecycle()
    val currentPosMs = livePositionMs.coerceIn(0L, durationMs.coerceAtLeast(1L))
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubPositionMs by remember { mutableFloatStateOf(0f) }

    val displayedPosMs = if (isScrubbing) scrubPositionMs.toLong() else currentPosMs
    val sliderMax = durationMs.coerceAtLeast(1L).toFloat()
    val durationLabel = remember(durationMs) { formatDurationMs(durationMs) }

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
            thumbColor = MaterialTheme.colorScheme.onSurface,
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
            color = TextMuted,
            maxLines = 1,
            softWrap = false,
        )
        Text(
            text = durationLabel,
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun AudioModeSheetContent(
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
        isBitPerfectMixerActive -> IcePrimary
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
private fun SafetyAlertBanner(
    message: String,
    isError: Boolean,
    onDismiss: (() -> Unit)?,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
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
fun HearingSafetyWarningDialog(
    deviceName: String,
    onConfirmBitPerfect: () -> Unit,
    onDismissStaySafe: () -> Unit,
) {
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
