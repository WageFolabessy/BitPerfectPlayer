package com.cangkir.bitperfectplayer.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cangkir.bitperfectplayer.audio.PlaybackControllerState
import com.cangkir.bitperfectplayer.data.LyricsSourceType
import com.cangkir.bitperfectplayer.lyrics.LrcLine
import com.cangkir.bitperfectplayer.lyrics.LrcParser
import com.cangkir.bitperfectplayer.ui.theme.CoralWarning
import com.cangkir.bitperfectplayer.ui.theme.IcePrimary
import com.cangkir.bitperfectplayer.ui.theme.IcePrimaryContainer
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsScreen(
    playbackState: PlaybackControllerState,
    playbackPositionMsFlow: StateFlow<Long>,
    lyricsUiState: LyricsUiState,
    onBack: () -> Unit,
    onImportLrcUri: (android.net.Uri) -> Unit,
    onSaveManualLyrics: (String) -> Unit,
    onDeleteLyrics: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
) {
    val activeTrack = playbackState.activeTrack
    val lyricsState = lyricsUiState.lyricsState
    val parsed = lyricsState?.parsedLyrics

    var showManualEditor by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val lrcPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            onImportLrcUri(uri)
        }
    }

    if (showManualEditor && activeTrack != null) {
        ManualLyricsEditorDialog(
            trackTitle = activeTrack.title,
            initialText = parsed?.rawText.orEmpty(),
            onSave = { updated ->
                showManualEditor = false
                onSaveManualLyrics(updated)
            },
            onDismiss = { showManualEditor = false },
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Saved Lyrics?") },
            text = { Text("This removes the locally stored lyrics bound to this track's SHA-256 fingerprint.") },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteConfirm = false },
                    modifier = Modifier.sizeIn(minHeight = 48.dp),
                ) {
                    Text("Cancel")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDeleteLyrics()
                    },
                    modifier = Modifier.sizeIn(minHeight = 48.dp),
                ) {
                    Text("Delete", color = CoralWarning)
                }
            },
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = activeTrack?.title ?: "Lyrics",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val subtitle = when {
                            activeTrack == null -> "No active track"
                            lyricsState?.hasPersistedLyrics == true -> {
                                val src = if (lyricsState.sourceType == LyricsSourceType.LRC_FILE) ".lrc file" else "Manual"
                                val syncLabel = if (parsed?.isSynced == true) "Synced" else "Static"
                                "$syncLabel · $src · SHA-256 ${lyricsState.contentFingerprint.take(10)}…"
                            }
                            else -> "100% Offline Lyrics"
                        }
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
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
                actions = {
                    if (activeTrack != null) {
                        IconButton(
                            onClick = { lrcPickerLauncher.launch(arrayOf("*/*")) },
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.FileOpen,
                                contentDescription = "Import .lrc file via SAF",
                            )
                        }
                        IconButton(
                            onClick = { showManualEditor = true },
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Edit,
                                contentDescription = "Edit or paste lyrics manually",
                            )
                        }
                        if (lyricsState?.hasPersistedLyrics == true) {
                            IconButton(
                                onClick = { showDeleteConfirm = true },
                                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Delete,
                                    contentDescription = "Delete saved lyrics",
                                    tint = CoralWarning,
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        bottomBar = {
            if (activeTrack != null) {
                LyricsCompactSeekBar(
                    isPlaying = playbackState.isPlaying,
                    durationMs = playbackState.durationMs,
                    playbackPositionMsFlow = playbackPositionMsFlow,
                    onTogglePlayPause = onTogglePlayPause,
                    onSeekTo = onSeekTo,
                )
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when {
                activeTrack == null -> {
                    EmptyLyricsPlaceholder(
                        title = "No active track",
                        subtitle = "Play a track from your library to view or attach offline lyrics.",
                        fingerprint = null,
                        onImportLrc = null,
                        onOpenEditor = null,
                    )
                }
                lyricsUiState.isLoading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                parsed != null && parsed.isSynced && parsed.lines.isNotEmpty() -> {
                    SyncedLyricsList(
                        lines = parsed.lines,
                        playbackPositionMsFlow = playbackPositionMsFlow,
                        onSeekTo = onSeekTo,
                    )
                }
                parsed != null && parsed.plainLines.isNotEmpty() -> {
                    // Static scrollable view for untimestamped manual or plain-text lyrics
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(
                            items = parsed.plainLines,
                            contentType = { _, _ -> "plain_lyric_line" },
                        ) { _, line ->
                            if (line.isBlank()) {
                                Spacer(modifier = Modifier.height(10.dp))
                            } else {
                                Text(
                                    text = line,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
                else -> {
                    EmptyLyricsPlaceholder(
                        title = "No lyrics attached yet",
                        subtitle = "Import a local .lrc file via SAF or paste/write lyrics manually. Saved lyrics bind persistently to this track's SHA-256 content fingerprint.",
                        fingerprint = lyricsState?.contentFingerprint,
                        onImportLrc = { lrcPickerLauncher.launch(arrayOf("*/*")) },
                        onOpenEditor = { showManualEditor = true },
                    )
                }
            }
        }
    }
}

/**
 * Isolated synced lyrics list deriving active line strictly from the audio engine's playback
 * clock via [distinctUntilChanged], so the list recomposes only when the active line changes.
 */
@Composable
private fun SyncedLyricsList(
    lines: List<LrcLine>,
    playbackPositionMsFlow: StateFlow<Long>,
    onSeekTo: (Long) -> Unit,
) {
    val activeIndex by remember(lines, playbackPositionMsFlow) {
        playbackPositionMsFlow
            .map { posMs -> LrcParser.findActiveLineIndex(lines = lines, positionMs = posMs) }
            .distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = -1)

    val listState = rememberLazyListState()
    LaunchedEffect(activeIndex) {
        if (activeIndex >= 0) {
            val target = (activeIndex - 2).coerceAtLeast(0)
            listState.animateScrollToItem(target)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        itemsIndexed(
            items = lines,
            contentType = { _, _ -> "lrc_line" },
        ) { idx, line ->
            val isActive = idx == activeIndex
            val timestampLabel = remember(line.timestampMs) { formatDurationMs(line.timestampMs) }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (isActive) IcePrimaryContainer.copy(alpha = 0.65f) else MaterialTheme.colorScheme.background,
                    )
                    .clickable { onSeekTo(line.timestampMs) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Column {
                    Text(
                        text = timestampLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isActive) IcePrimary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1,
                        softWrap = false,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = line.text.ifBlank { "♪" },
                        style = if (isActive) {
                            MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                        } else {
                            MaterialTheme.typography.bodyLarge
                        },
                        color = if (isActive) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun LyricsCompactSeekBar(
    isPlaying: Boolean,
    durationMs: Long,
    playbackPositionMsFlow: StateFlow<Long>,
    onTogglePlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
) {
    val livePosMs by playbackPositionMsFlow.collectAsStateWithLifecycle()
    val safeDurationMs = durationMs.coerceAtLeast(1L)
    var scrubbing by remember { mutableStateOf(false) }
    var scrubMs by remember { mutableFloatStateOf(0f) }
    val displayMs = if (scrubbing) scrubMs.toLong() else livePosMs.coerceIn(0L, safeDurationMs)
    val totalLabel = remember(durationMs) { formatDurationMs(durationMs) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onTogglePlayPause,
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                )
            }
            Text(
                text = formatDurationMs(displayMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
            )
            Slider(
                value = displayMs.toFloat().coerceIn(0f, safeDurationMs.toFloat()),
                onValueChange = {
                    scrubbing = true
                    scrubMs = it
                },
                onValueChangeFinished = {
                    val target = scrubMs.toLong()
                    scrubbing = false
                    onSeekTo(target)
                },
                valueRange = 0f..safeDurationMs.toFloat(),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            )
            Text(
                text = totalLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun EmptyLyricsPlaceholder(
    title: String,
    subtitle: String,
    fingerprint: String?,
    onImportLrc: (() -> Unit)?,
    onOpenEditor: (() -> Unit)?,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Filled.Lyrics,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (!fingerprint.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            FormatBadgeChip(text = "SHA-256: ${fingerprint.take(16)}…")
        }
        if (onImportLrc != null && onOpenEditor != null) {
            Spacer(modifier = Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onImportLrc,
                    modifier = Modifier.sizeIn(minHeight = 48.dp),
                ) {
                    Icon(Icons.Filled.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Import .lrc")
                }
                OutlinedButton(
                    onClick = onOpenEditor,
                    modifier = Modifier.sizeIn(minHeight = 48.dp),
                ) {
                    Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Manual Editor")
                }
            }
        }
    }
}

@Composable
private fun ManualLyricsEditorDialog(
    trackTitle: String,
    initialText: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var draftText by remember(initialText) { mutableStateOf(initialText) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Manual Lyrics Editor",
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Paste plain lyrics or timestamped [mm:ss.xx] .lrc text for \"$trackTitle\". Stored 100% locally by SHA-256 fingerprint.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = draftText,
                    onValueChange = { draftText = it },
                    placeholder = { Text("[00:12.50]Paste or type lyrics here...") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 180.dp, max = 320.dp),
                    maxLines = 16,
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.sizeIn(minHeight = 48.dp),
            ) {
                Text("Cancel")
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(draftText) },
                modifier = Modifier.sizeIn(minHeight = 48.dp),
            ) {
                Text("Save Locally")
            }
        },
    )
}
