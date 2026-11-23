package com.cangkir.bitperfectplayer.ui.nowplaying

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cangkir.bitperfectplayer.audio.AlbumPaletteExtractor
import com.cangkir.bitperfectplayer.audio.AudioOutputMode
import com.cangkir.bitperfectplayer.audio.PlaybackControllerState
import com.cangkir.bitperfectplayer.audio.PlaybackTrack
import com.cangkir.bitperfectplayer.audio.SleepTimerPreset
import com.cangkir.bitperfectplayer.audio.SleepTimerState
import com.cangkir.bitperfectplayer.data.NowPlayingArtworkStyle
import com.cangkir.bitperfectplayer.data.SpectrumColorStyle
import com.cangkir.bitperfectplayer.library.AlbumArtworkLoader
import com.cangkir.bitperfectplayer.ui.CompactSpectrumBarStrip
import com.cangkir.bitperfectplayer.ui.InteractiveVinylDisc
import com.cangkir.bitperfectplayer.ui.RadialSpectrumRing
import com.cangkir.bitperfectplayer.ui.SleepTimerBottomSheet
import com.cangkir.bitperfectplayer.ui.SleepTimerTopBarButton
import com.cangkir.bitperfectplayer.ui.WindowLayoutMetrics
import com.cangkir.bitperfectplayer.ui.theme.AmberIndicated
import com.cangkir.bitperfectplayer.ui.theme.EmeraldSafe
import com.cangkir.bitperfectplayer.ui.theme.TextMuted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    playbackState: PlaybackControllerState,
    playbackPositionMsFlow: StateFlow<Long>,
    sleepTimerStateFlow: StateFlow<SleepTimerState>,
    albumArtworkLoader: AlbumArtworkLoader,
    isFavorite: Boolean,
    artworkStyle: NowPlayingArtworkStyle = NowPlayingArtworkStyle.VINYL_DISC,
    onSetArtworkStyle: (NowPlayingArtworkStyle) -> Unit = {},
    spectrumVisualizerEnabled: Boolean = true,
    onSetSpectrumVisualizerEnabled: (Boolean) -> Unit = {},
    spectrumColorStyle: SpectrumColorStyle = SpectrumColorStyle.PRISM,
    onCycleSpectrumColorStyle: () -> Unit = {},
    spectrumSparksEnabled: Boolean = true,
    onSetSpectrumSparksEnabled: (Boolean) -> Unit = {},
    onSetSpectrumVisualizerActive: (Boolean) -> Unit = {},
    onSampleSpectrumWindow: (FloatArray) -> Int = { 0 },
    onBack: () -> Unit,
    onToggleFavorite: (PlaybackTrack) -> Unit,
    onAddToPlaylist: (PlaybackTrack) -> Unit,
    onShowTrackInfo: (PlaybackTrack) -> Unit,
    onSelectSleepTimerPreset: (SleepTimerPreset) -> Unit,
    onTogglePlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onBeginTurntableScratch: () -> Boolean = { false },
    onUpdateTurntableScratchRate: (Float) -> Unit = {},
    onEndTurntableScratch: (Long?) -> Unit = {},
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
    isPageActive: Boolean = true,
) {
    val track = playbackState.activeTrack
    val safetyState = playbackState.engineState.safetyState
    val diagnostics = playbackState.engineState.diagnostics
    val durationMs = playbackState.durationMs.coerceAtLeast(0L)

    // Strict lifecycle gate: activate read-only PCM spectrum tap ONLY while NowPlayingScreen
    // is the visible foreground page (isPageActive && ON_START..ON_STOP) AND enabled.
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestSetSpectrumActive by rememberUpdatedState(onSetSpectrumVisualizerActive)
    DisposableEffect(lifecycleOwner, spectrumVisualizerEnabled, isPageActive) {
        val lifecycle = lifecycleOwner.lifecycle
        latestSetSpectrumActive(
            isPageActive && spectrumVisualizerEnabled && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
        )
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> latestSetSpectrumActive(isPageActive && spectrumVisualizerEnabled)
                Lifecycle.Event.ON_STOP -> latestSetSpectrumActive(false)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            latestSetSpectrumActive(false)
        }
    }

    // Async local cover-art accent extraction strictly on Dispatchers.Default when Album style is selected.
    var albumSpectrumPalette by remember(track?.uriString) {
        mutableStateOf(AlbumPaletteExtractor.FALLBACK_STUDIO_PALETTE)
    }
    LaunchedEffect(track?.uriString, spectrumVisualizerEnabled, spectrumColorStyle) {
        if (track == null || !spectrumVisualizerEnabled || spectrumColorStyle != SpectrumColorStyle.ALBUM_ADAPTIVE) {
            return@LaunchedEffect
        }
        val bitmap = albumArtworkLoader.loadArtwork(track, AlbumArtworkLoader.THUMBNAIL_SIZE_PX)
        albumSpectrumPalette = withContext(Dispatchers.Default) { extractAlbumPaletteFromBitmap(bitmap) }
    }

    var showAudioModeSheet by remember { mutableStateOf(false) }
    var showSleepTimerSheet by remember { mutableStateOf(false) }

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
                    IconButton(onClick = onBack, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to library")
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
                        NowPlayingOverflowMenuButton(
                            track = track,
                            artworkStyle = artworkStyle,
                            spectrumVisualizerEnabled = spectrumVisualizerEnabled,
                            spectrumColorStyle = spectrumColorStyle,
                            spectrumSparksEnabled = spectrumSparksEnabled,
                            onAddToPlaylist = onAddToPlaylist,
                            onShowTrackInfo = onShowTrackInfo,
                            onOpenSleepTimer = { showSleepTimerSheet = true },
                            onSetArtworkStyle = onSetArtworkStyle,
                            onSetSpectrumVisualizerEnabled = onSetSpectrumVisualizerEnabled,
                            onCycleSpectrumColorStyle = onCycleSpectrumColorStyle,
                            onSetSpectrumSparksEnabled = onSetSpectrumSparksEnabled,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { innerPadding ->
        val swipeExclusionBounds = LocalSwipeExclusionBounds.current ?: remember { NowPlayingSwipeExclusionBounds() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (track == null) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Filled.MusicNote, contentDescription = null, tint = TextMuted, modifier = Modifier.size(48.dp))
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
                return@Box
            }

            val isSafe = safetyState.mode == AudioOutputMode.SAFE_VOLUME_MODE
            val modeDotColor = when {
                diagnostics.isBitPerfectMixerActive -> MaterialTheme.colorScheme.primary
                isSafe -> EmeraldSafe
                else -> AmberIndicated
            }
            val modeShortLabel = when {
                diagnostics.isBitPerfectMixerActive -> "Bit-Perfect"
                isSafe -> "Safe Mode"
                else -> "Clean Native"
            }
            val formatSpecText = playbackState.displayFormatSpec

            // Decoupled scrub preview state holder: NEVER read `.value` inside NowPlayingScreen root
            // so rotational vinyl scrubbing/coasting updates ONLY the pill leaf and NowPlayingSeekSection.
            val vinylScrubPreviewMs = remember(track.uriString) { mutableStateOf<Long?>(null) }

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding(),
            ) {
                val widthDp = maxWidth.value.toInt()
                val heightDp = maxHeight.value.toInt()
                val useTwoPane = remember(widthDp, heightDp) {
                    WindowLayoutMetrics.useTwoPaneNowPlaying(widthDp, heightDp)
                }
                val discContainerDp = remember(widthDp, heightDp, useTwoPane) {
                    WindowLayoutMetrics.computeNowPlayingDiscContainerDp(widthDp, heightDp, isTwoPane = useTwoPane)
                }
                val vinylDiameterDp = remember(discContainerDp, spectrumVisualizerEnabled) {
                    WindowLayoutMetrics.computeVinylDiscDiameterDp(discContainerDp, spectrumVisualizerEnabled)
                }
                val squareArtworkDp = remember(discContainerDp) {
                    WindowLayoutMetrics.computeSquareArtworkSizeDp(discContainerDp)
                }

                if (useTwoPane) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .widthIn(max = 1120.dp)
                                .padding(horizontal = 24.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(24.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(0.46f)
                                    .fillMaxHeight(),
                                contentAlignment = Alignment.Center,
                            ) {
                                NowPlayingHeroCenterpiece(
                                    track = track,
                                    artworkStyle = artworkStyle,
                                    isPlaying = playbackState.isPlaying && isPageActive,
                                    isSafeMode = isSafe,
                                    durationMs = durationMs,
                                    discContainerDiameter = discContainerDp.dp,
                                    vinylDiscDiameter = vinylDiameterDp.dp,
                                    squareArtworkSize = squareArtworkDp.dp,
                                    spectrumVisualizerEnabled = spectrumVisualizerEnabled,
                                    spectrumColorStyle = spectrumColorStyle,
                                    spectrumSparksEnabled = spectrumSparksEnabled,
                                    albumSpectrumPalette = albumSpectrumPalette,
                                    playbackPositionMsFlow = playbackPositionMsFlow,
                                    vinylScrubPreviewMs = vinylScrubPreviewMs,
                                    albumArtworkLoader = albumArtworkLoader,
                                    swipeExclusionBounds = swipeExclusionBounds,
                                    onSampleSpectrumWindow = onSampleSpectrumWindow,
                                    onSeekTo = onSeekTo,
                                    onPrevious = onPrevious,
                                    onNext = onNext,
                                    onBeginTurntableScratch = onBeginTurntableScratch,
                                    onUpdateTurntableScratchRate = onUpdateTurntableScratchRate,
                                    onEndTurntableScratch = onEndTurntableScratch,
                                )
                            }
                            Column(
                                modifier = Modifier
                                    .weight(0.54f)
                                    .fillMaxHeight()
                                    .verticalScroll(rememberScrollState()),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                NowPlayingTrackControlsSection(
                                    track = track,
                                    formatSpecText = formatSpecText,
                                    durationMs = durationMs,
                                    playbackState = playbackState,
                                    playbackPositionMsFlow = playbackPositionMsFlow,
                                    vinylScrubPreviewMs = vinylScrubPreviewMs,
                                    swipeExclusionBounds = swipeExclusionBounds,
                                    modeDotColor = modeDotColor,
                                    modeShortLabel = modeShortLabel,
                                    compactVerticalSpacing = true,
                                    onSeekTo = onSeekTo,
                                    onToggleShuffle = onToggleShuffle,
                                    onPrevious = onPrevious,
                                    onTogglePlayPause = onTogglePlayPause,
                                    onNext = onNext,
                                    onCycleRepeatMode = onCycleRepeatMode,
                                    onOpenAudioModeSheet = { showAudioModeSheet = true },
                                    onOpenQueue = onOpenQueue,
                                    onOpenLyrics = onOpenLyrics,
                                    onOpenDiagnostics = onOpenDiagnostics,
                                )
                            }
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp, vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        NowPlayingHeroCenterpiece(
                            track = track,
                            artworkStyle = artworkStyle,
                            isPlaying = playbackState.isPlaying && isPageActive,
                            isSafeMode = isSafe,
                            durationMs = durationMs,
                            discContainerDiameter = discContainerDp.dp,
                            vinylDiscDiameter = vinylDiameterDp.dp,
                            squareArtworkSize = squareArtworkDp.dp,
                            spectrumVisualizerEnabled = spectrumVisualizerEnabled,
                            spectrumColorStyle = spectrumColorStyle,
                            spectrumSparksEnabled = spectrumSparksEnabled,
                            albumSpectrumPalette = albumSpectrumPalette,
                            playbackPositionMsFlow = playbackPositionMsFlow,
                            vinylScrubPreviewMs = vinylScrubPreviewMs,
                            albumArtworkLoader = albumArtworkLoader,
                            swipeExclusionBounds = swipeExclusionBounds,
                            onSampleSpectrumWindow = onSampleSpectrumWindow,
                            onSeekTo = onSeekTo,
                            onPrevious = onPrevious,
                            onNext = onNext,
                            onBeginTurntableScratch = onBeginTurntableScratch,
                            onUpdateTurntableScratchRate = onUpdateTurntableScratchRate,
                            onEndTurntableScratch = onEndTurntableScratch,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        NowPlayingTrackControlsSection(
                            track = track,
                            formatSpecText = formatSpecText,
                            durationMs = durationMs,
                            playbackState = playbackState,
                            playbackPositionMsFlow = playbackPositionMsFlow,
                            vinylScrubPreviewMs = vinylScrubPreviewMs,
                            swipeExclusionBounds = swipeExclusionBounds,
                            modeDotColor = modeDotColor,
                            modeShortLabel = modeShortLabel,
                            compactVerticalSpacing = false,
                            onSeekTo = onSeekTo,
                            onToggleShuffle = onToggleShuffle,
                            onPrevious = onPrevious,
                            onTogglePlayPause = onTogglePlayPause,
                            onNext = onNext,
                            onCycleRepeatMode = onCycleRepeatMode,
                            onOpenAudioModeSheet = { showAudioModeSheet = true },
                            onOpenQueue = onOpenQueue,
                            onOpenLyrics = onOpenLyrics,
                            onOpenDiagnostics = onOpenDiagnostics,
                        )
                    }
                }

                // Floating top alert overlay prevents auto-pause/error notices from shifting the vinyl disc or transport buttons.
                val alertError = playbackState.engineState.errorMessage
                val alertNotice = playbackState.engineState.lastAutoPauseNotice
                val bannerMessage = alertError ?: alertNotice
                AnimatedVisibility(
                    visible = bannerMessage != null,
                    enter = fadeIn(animationSpec = tween(durationMillis = 120)) +
                        slideInVertically(animationSpec = tween(durationMillis = 120)) { -it / 3 },
                    exit = ExitTransition.None,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp),
                ) {
                    bannerMessage?.let { msg ->
                        SafetyAlertBanner(
                            message = msg,
                            isError = alertError != null,
                            onDismiss = if (alertError != null) null else onClearAutoPauseNotice,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NowPlayingHeroCenterpiece(
    track: PlaybackTrack,
    artworkStyle: NowPlayingArtworkStyle,
    isPlaying: Boolean,
    isSafeMode: Boolean,
    durationMs: Long,
    discContainerDiameter: Dp,
    vinylDiscDiameter: Dp,
    squareArtworkSize: Dp,
    spectrumVisualizerEnabled: Boolean,
    spectrumColorStyle: SpectrumColorStyle,
    spectrumSparksEnabled: Boolean,
    albumSpectrumPalette: IntArray,
    playbackPositionMsFlow: StateFlow<Long>,
    vinylScrubPreviewMs: MutableState<Long?>,
    albumArtworkLoader: AlbumArtworkLoader,
    swipeExclusionBounds: NowPlayingSwipeExclusionBounds,
    onSampleSpectrumWindow: (FloatArray) -> Int,
    onSeekTo: (Long) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onBeginTurntableScratch: () -> Boolean,
    onUpdateTurntableScratchRate: (Float) -> Unit,
    onEndTurntableScratch: (Long?) -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        when (artworkStyle) {
            NowPlayingArtworkStyle.VINYL_DISC -> {
                DisposableEffect(Unit) {
                    onDispose { swipeExclusionBounds.vinylDiscRadiusPx = 0f }
                }
                val vinylBoundsModifier = Modifier.onGloballyPositioned { coords ->
                    val rect = coords.boundsInRoot()
                    swipeExclusionBounds.vinylDiscCenterInRoot = rect.center
                    swipeExclusionBounds.vinylDiscRadiusPx = minOf(rect.width, rect.height) / 2f
                }
                if (spectrumVisualizerEnabled) {
                    RadialSpectrumRing(
                        isEnabled = true,
                        isPlaying = isPlaying,
                        sampleSpectrumWindow = onSampleSpectrumWindow,
                        containerDiameter = discContainerDiameter,
                        vinylDiameter = vinylDiscDiameter,
                        colorStyle = spectrumColorStyle,
                        sparksEnabled = spectrumSparksEnabled,
                        albumPalette = albumSpectrumPalette,
                        trackKey = track.uriString,
                    ) {
                        InteractiveVinylDisc(
                            track = track,
                            isPlaying = isPlaying,
                            isSafeMode = isSafeMode,
                            durationMs = durationMs,
                            playbackPositionMsFlow = playbackPositionMsFlow,
                            scrubPreviewMsState = vinylScrubPreviewMs,
                            albumArtworkLoader = albumArtworkLoader,
                            onSeekTo = onSeekTo,
                            modifier = vinylBoundsModifier,
                            discDiameter = vinylDiscDiameter,
                            onBeginTurntableScratch = onBeginTurntableScratch,
                            onUpdateTurntableScratchRate = onUpdateTurntableScratchRate,
                            onEndTurntableScratch = onEndTurntableScratch,
                        )
                    }
                } else {
                    InteractiveVinylDisc(
                        track = track,
                        isPlaying = isPlaying,
                        isSafeMode = isSafeMode,
                        durationMs = durationMs,
                        playbackPositionMsFlow = playbackPositionMsFlow,
                        scrubPreviewMsState = vinylScrubPreviewMs,
                        albumArtworkLoader = albumArtworkLoader,
                        onSeekTo = onSeekTo,
                        modifier = vinylBoundsModifier,
                        discDiameter = vinylDiscDiameter,
                        onBeginTurntableScratch = onBeginTurntableScratch,
                        onUpdateTurntableScratchRate = onUpdateTurntableScratchRate,
                        onEndTurntableScratch = onEndTurntableScratch,
                    )
                }
            }
            NowPlayingArtworkStyle.SQUARE_COVER -> {
                SwipeableSquareArtwork(
                    track = track,
                    albumArtworkLoader = albumArtworkLoader,
                    onPrevious = onPrevious,
                    onNext = onNext,
                    artworkSize = squareArtworkSize,
                    onBoundsInRootChanged = { swipeExclusionBounds.squareArtworkRectInRoot = it },
                )
                if (spectrumVisualizerEnabled) {
                    Spacer(modifier = Modifier.height(8.dp))
                    CompactSpectrumBarStrip(
                        isEnabled = true,
                        isPlaying = isPlaying,
                        sampleSpectrumWindow = onSampleSpectrumWindow,
                        barWidth = squareArtworkSize,
                        colorStyle = spectrumColorStyle,
                        sparksEnabled = spectrumSparksEnabled,
                        albumPalette = albumSpectrumPalette,
                        trackKey = track.uriString,
                    )
                }
            }
        }
    }
}

@Composable
private fun NowPlayingTrackControlsSection(
    track: PlaybackTrack,
    formatSpecText: String,
    durationMs: Long,
    playbackState: PlaybackControllerState,
    playbackPositionMsFlow: StateFlow<Long>,
    vinylScrubPreviewMs: MutableState<Long?>,
    swipeExclusionBounds: NowPlayingSwipeExclusionBounds,
    modeDotColor: Color,
    modeShortLabel: String,
    compactVerticalSpacing: Boolean,
    onSeekTo: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onPrevious: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onCycleRepeatMode: () -> Unit,
    onOpenAudioModeSheet: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenLyrics: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    val titleStyle = MaterialTheme.typography.headlineSmall
    val fixedTitleSlotHeight = with(LocalDensity.current) { (titleStyle.lineHeight * 2).toDp() }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Reserve a constant 2-line height so rapid Next/Previous taps keep transport buttons stationary.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(fixedTitleSlotHeight),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = track.title,
                style = titleStyle,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(modifier = Modifier.height(if (compactVerticalSpacing) 2.dp else 4.dp))
        Text(
            text = track.artistAlbumLine,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(if (compactVerticalSpacing) 4.dp else 6.dp))
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

        Spacer(modifier = Modifier.height(if (compactVerticalSpacing) 6.dp else 14.dp))

        // Isolated 20 Hz Seekable Progress Leaf (hero art & controls do NOT recompose on ticks)
        NowPlayingSeekSection(
            activeTrack = track,
            engineTrackUri = playbackState.engineState.activeTrackUri?.toString(),
            durationMs = durationMs,
            playbackPositionMsFlow = playbackPositionMsFlow,
            externalScrubPreviewMsState = vinylScrubPreviewMs,
            onSeekTo = onSeekTo,
            onSliderBoundsInRootChanged = { swipeExclusionBounds.seekSliderRectInRoot = it },
        )

        Spacer(modifier = Modifier.height(if (compactVerticalSpacing) 6.dp else 12.dp))

        NowPlayingTransportRow(
            isPlaying = playbackState.isPlaying,
            isPreparingPlayback = playbackState.isPreparingPlayback,
            shuffleEnabled = playbackState.queue.shuffleEnabled,
            repeatMode = playbackState.queue.repeatMode,
            onToggleShuffle = onToggleShuffle,
            onPrevious = onPrevious,
            onTogglePlayPause = onTogglePlayPause,
            onNext = onNext,
            onCycleRepeatMode = onCycleRepeatMode,
        )

        Spacer(modifier = Modifier.height(if (compactVerticalSpacing) 10.dp else 18.dp))

        NowPlayingAudioModeStrip(
            modeDotColor = modeDotColor,
            modeShortLabel = modeShortLabel,
            activeDeviceName = playbackState.engineState.safetyState.activeDeviceName,
            onClick = onOpenAudioModeSheet,
        )

        Spacer(modifier = Modifier.height(if (compactVerticalSpacing) 6.dp else 12.dp))

        NowPlayingUtilityStrip(
            onOpenQueue = onOpenQueue,
            onOpenLyrics = onOpenLyrics,
            onOpenDiagnostics = onOpenDiagnostics,
            isBitPerfectActive = playbackState.engineState.diagnostics.isBitPerfectMixerActive,
        )
    }
}
