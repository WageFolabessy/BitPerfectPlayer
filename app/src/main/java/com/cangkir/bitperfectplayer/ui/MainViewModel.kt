package com.cangkir.bitperfectplayer.ui

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cangkir.bitperfectplayer.AppContainer
import com.cangkir.bitperfectplayer.audio.PlaybackControllerState
import com.cangkir.bitperfectplayer.audio.PlaybackStatus
import com.cangkir.bitperfectplayer.audio.PlaybackTrack
import com.cangkir.bitperfectplayer.audio.QueueContext
import com.cangkir.bitperfectplayer.audio.RepeatMode
import com.cangkir.bitperfectplayer.audio.ReplayGainMode
import com.cangkir.bitperfectplayer.audio.SleepTimerPreset
import com.cangkir.bitperfectplayer.audio.SleepTimerState
import com.cangkir.bitperfectplayer.data.DefaultHomepage
import com.cangkir.bitperfectplayer.data.NowPlayingArtworkStyle
import com.cangkir.bitperfectplayer.data.PlaylistTrackEntity
import com.cangkir.bitperfectplayer.data.SpectrumColorStyle
import com.cangkir.bitperfectplayer.diagnostics.DiagnosticLogStats
import com.cangkir.bitperfectplayer.library.AddTrackToPlaylistOutcome
import com.cangkir.bitperfectplayer.library.AlbumArtworkLoader
import com.cangkir.bitperfectplayer.library.ArtistPhotoStore
import com.cangkir.bitperfectplayer.library.FavoriteStateReducer
import com.cangkir.bitperfectplayer.library.GroupSortScope
import com.cangkir.bitperfectplayer.library.GroupSortSpec
import com.cangkir.bitperfectplayer.library.LibrarySnapshot
import com.cangkir.bitperfectplayer.library.PlaylistsUiSnapshot
import com.cangkir.bitperfectplayer.library.ResolvedPlaylist
import com.cangkir.bitperfectplayer.library.TrackSortScope
import com.cangkir.bitperfectplayer.library.TrackSortSpec
import com.cangkir.bitperfectplayer.library.TrackTechnicalInfo
import com.cangkir.bitperfectplayer.lyrics.TrackLyricsState
import com.cangkir.bitperfectplayer.ui.theme.CoverThemeColorCache
import com.cangkir.bitperfectplayer.ui.theme.DynamicThemeTokens
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LyricsUiState(
    val trackUri: String? = null,
    val isLoading: Boolean = false,
    val lyricsState: TrackLyricsState? = null,
    val errorMessage: String? = null,
)

data class UiSnackbarMessage(val id: Long, val message: String, val actionLabel: String? = null, val onAction: (() -> Unit)? = null)

/**
 * Shared presentation ViewModel connecting Compose UI screens to [AppContainer] repositories and
 * [com.cangkir.bitperfectplayer.audio.PlaybackController].
 */
class MainViewModel(internal val container: AppContainer) : ViewModel() {

    val libraryState: StateFlow<LibrarySnapshot> = container.libraryRepository.librarySnapshot
    val playlistsState: StateFlow<PlaylistsUiSnapshot> = container.playlistRepository.playlistsSnapshot
    val playbackState: StateFlow<PlaybackControllerState> = container.playbackController.state
    val playbackPositionMs: StateFlow<Long> = container.playbackController.playbackPositionMs
    val sleepTimerState: StateFlow<SleepTimerState> = container.playbackController.sleepTimerState
    val albumArtworkLoader: AlbumArtworkLoader = container.albumArtworkLoader
    val artistPhotoStore: ArtistPhotoStore = container.artistPhotoStore
    val artistPhotoRevision: StateFlow<Int> = container.artistPhotoStore.photoRevision
    val coverThemeColorCache: CoverThemeColorCache = container.coverThemeColorCache
    val diagnosticLogStats: StateFlow<DiagnosticLogStats> = container.diagnosticLogger.storageStats

    /**
     * Single reactive source of truth for favorited track URIs (Room Flow + instant optimistic
     * toggle overrides), observed directly by LibraryScreen, NowPlayingScreen, and Favorites tab.
     */
    val favoriteTrackUris: StateFlow<Set<String>> = libraryState
        .map { it.favoriteUris }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = libraryState.value.favoriteUris,
        )

    /**
     * Persisted `NowPlayingScreen` artwork presentation mode (`Vinyl Disc` default vs `Square Cover`),
     * deduplicated with `distinctUntilChanged()` so unrelated preference writes never recompose UI.
     */
    val nowPlayingArtworkStyle: StateFlow<NowPlayingArtworkStyle> = container.preferencesRepository.preferencesFlow
        .map { it.nowPlayingArtworkStyle }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = NowPlayingArtworkStyle.VINYL_DISC,
        )

    /**
     * Persisted toggle for the read-only radial FFT spectrum visualizer (`true` by default),
     * deduplicated with `distinctUntilChanged()` so unrelated preference writes never recompose UI.
     */
    val spectrumVisualizerEnabled: StateFlow<Boolean> = container.preferencesRepository.preferencesFlow
        .map { it.spectrumVisualizerEnabled }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = true,
        )

    /** Persisted color palette mode (`Studio`, `Prism`, `Album`) for the spectrum visualizer. */
    val spectrumColorStyle: StateFlow<SpectrumColorStyle> = container.preferencesRepository.preferencesFlow
        .map { it.spectrumColorStyle }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = SpectrumColorStyle.PRISM,
        )

    /** Persisted toggle for transient-driven Kinetic Sparks particles (`true` by default). */
    val spectrumSparksEnabled: StateFlow<Boolean> = container.preferencesRepository.preferencesFlow
        .map { it.spectrumSparksEnabled }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = true,
        )

    /** Persisted startup homepage preference shown and updated in Settings. */
    val defaultHomepage: StateFlow<DefaultHomepage> = container.preferencesRepository.preferencesFlow
        .map { it.defaultHomepage }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = DefaultHomepage.ALL_SONGS,
        )

    /** Persisted opt-in local diagnostic logging flag (`false` by default). */
    val diagnosticLoggingEnabled: StateFlow<Boolean> = container.preferencesRepository.preferencesFlow
        .map { it.diagnosticLoggingEnabled }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = false,
        )

    /** Persisted opt-in Dynamic Cover Theme toggle (`false` by default -> Monochrome Studio). */
    val dynamicCoverThemeEnabled: StateFlow<Boolean> = container.preferencesRepository.preferencesFlow
        .map { it.dynamicCoverThemeEnabled }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = false,
        )

    private val playbackDynamicThemeTokensState = MutableStateFlow<DynamicThemeTokens?>(null)
    private val contextualDynamicThemeBySourceState = MutableStateFlow<Map<String, DynamicThemeTokens>>(emptyMap())

    /**
     * Effective root **Dynamic Theme** tokens applied globally (`Album / Artist / Playlist Detail`
     * contextual theme takes precedence while active, falling back to the playing track's theme,
     * or `null` for Monochrome Studio).
     */
    val globalDynamicThemeTokens: StateFlow<DynamicThemeTokens?> = combine(
        dynamicCoverThemeEnabled,
        playbackDynamicThemeTokensState,
        contextualDynamicThemeBySourceState,
    ) { enabled, playbackTokens, contextualMap ->
        if (!enabled) null else contextualMap.values.lastOrNull() ?: playbackTokens
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = null,
    )

    /**
     * Latched strictly ONCE per ViewModel lifetime when DataStore emits its initial snapshot so
     * cold startup opens the configured homepage cleanly without ever hijacking subsequent user
     * tab switches or Settings changes.
     */
    private val _initialStartupHomepage = MutableStateFlow<DefaultHomepage?>(null)
    val initialStartupHomepage: StateFlow<DefaultHomepage?> = _initialStartupHomepage.asStateFlow()

    private val _lyricsUiState = MutableStateFlow(LyricsUiState())
    val lyricsUiState: StateFlow<LyricsUiState> = _lyricsUiState.asStateFlow()

    private val _trackInfoState = MutableStateFlow<TrackTechnicalInfo?>(null)
    val trackInfoState: StateFlow<TrackTechnicalInfo?> = _trackInfoState.asStateFlow()
    private var trackInfoJob: Job? = null

    private val nextSnackbarId = AtomicLong(1L)
    private val _uiSnackbarMessage = MutableStateFlow<UiSnackbarMessage?>(null)
    val uiSnackbarMessage: StateFlow<UiSnackbarMessage?> = _uiSnackbarMessage.asStateFlow()

    init {
        viewModelScope.launch {
            val loadedHomepage = container.preferencesRepository.getSnapshot().defaultHomepage
            _initialStartupHomepage.compareAndSet(null, loadedHomepage)
        }

        // Trigger incremental background sync while cached tracks from Room render immediately.
        viewModelScope.launch {
            container.libraryRepository.refreshLibrary(forceYearBackfill = false)
        }

        // Restore last playback session with its exact saved QueueContext (Album/Artist/Folder/Playlist/etc.)
        // as soon as cached or synced tracks + playlists are available.
        viewModelScope.launch {
            combine(libraryState, playlistsState) { lib, pl -> lib to pl }
                .collect { (library, playlists) ->
                    if (library.allSongs.isNotEmpty()) {
                        container.playbackController.restoreLastSessionIfNeeded(
                            library = library,
                            playlists = playlists,
                        )
                    }
                }
        }

        // Automatically resolve fingerprint-bound lyrics whenever the active track changes.
        viewModelScope.launch {
            playbackState
                .distinctUntilChangedBy { it.activeTrack?.uriString }
                .collect { state ->
                    val track = state.activeTrack
                    if (track == null) {
                        _lyricsUiState.value = LyricsUiState()
                    } else {
                        loadLyricsForTrack(track)
                    }
                }
        }

        // Global Dynamic Theme layer: resolve active track cover palette off-main when opted in.
        val activeTrackForThemeFlow = playbackState
            .map { it.activeTrack }
            .distinctUntilChanged { old, new ->
                val oldKey = old?.let { container.coverThemeColorCache.cacheKeyFor(it) }
                val newKey = new?.let { container.coverThemeColorCache.cacheKeyFor(it) }
                oldKey == newKey
            }
        viewModelScope.launch {
            combine(dynamicCoverThemeEnabled, activeTrackForThemeFlow) { enabled, track ->
                enabled to track
            }.collectLatest { (enabled, track) ->
                playbackDynamicThemeTokensState.value = if (!enabled || track == null) {
                    null
                } else {
                    container.coverThemeColorCache.resolveTokens(track)
                }
            }
        }

        // Preload adjacent Next/Previous DISC artwork strictly once playback reaches PLAYING.
        viewModelScope.launch {
            playbackState
                .map { state ->
                    if (state.engineState.status == PlaybackStatus.PLAYING) {
                        val items = state.queue.items
                        val idx = state.queue.currentIndex
                        val repeat = state.queue.repeatMode
                        if (idx in items.indices && items.size > 1) {
                            val nextIdx = when {
                                idx + 1 < items.size -> idx + 1
                                repeat == RepeatMode.ALL -> 0
                                else -> -1
                            }
                            val prevIdx = when {
                                idx - 1 >= 0 -> idx - 1
                                repeat == RepeatMode.ALL -> items.lastIndex
                                else -> -1
                            }
                            Pair(items.getOrNull(nextIdx), items.getOrNull(prevIdx))
                        } else {
                            null
                        }
                    } else {
                        null
                    }
                }
                .distinctUntilChanged { old, new ->
                    old?.first?.uriString == new?.first?.uriString &&
                        old?.second?.uriString == new?.second?.uriString
                }
                .collectLatest { pair ->
                    val (nextCandidate, prevCandidate) = pair ?: return@collectLatest
                    withContext(Dispatchers.IO) {
                        if (nextCandidate != null) {
                            container.albumArtworkLoader.preloadDiscArtwork(nextCandidate)
                        }
                        if (prevCandidate != null && prevCandidate.uriString != nextCandidate?.uriString) {
                            container.albumArtworkLoader.preloadDiscArtwork(prevCandidate)
                        }
                    }
                }
        }
    }

    fun refreshLibrary() {
        container.albumArtworkLoader.clearNegativeCache()
        container.coverThemeColorCache.clear()
        viewModelScope.launch {
            container.libraryRepository.refreshLibrary(forceYearBackfill = true)
        }
    }

    fun importSafFolder(treeUri: Uri) {
        viewModelScope.launch {
            val count = container.libraryRepository.importSafFolder(treeUri)
            emitSnackbar(
                if (count > 0) {
                    "Imported folder ($count audio ${if (count == 1) "file" else "files"})"
                } else {
                    "Folder linked (no supported FLAC/WAV/MP3/M4A/OGG files found)"
                },
            )
        }
    }

    fun removeSafFolder(treeUriString: String) {
        viewModelScope.launch {
            container.libraryRepository.removeSafFolder(treeUriString)
            emitSnackbar("Removed linked SAF folder")
        }
    }

    fun playTracks(
        tracks: List<PlaybackTrack>,
        startIndex: Int = 0,
        context: QueueContext = QueueContext.allSongs(),
        shuffled: Boolean = false,
    ) {
        if (shuffled) {
            container.playbackController.playQueueShuffled(tracks = tracks, context = context)
        } else {
            container.playbackController.playQueue(
                tracks = tracks,
                startIndex = startIndex,
                autoPlay = true,
                context = context,
            )
        }
    }

    fun updateCustomArtistPhoto(artistName: String, sourceUri: Uri?) {
        viewModelScope.launch {
            if (sourceUri == null) {
                container.artistPhotoStore.clearArtistPhoto(artistName)
                emitSnackbar("Reset artist photo for \"$artistName\"")
            } else {
                val result = container.artistPhotoStore.saveArtistPhoto(artistName, sourceUri)
                emitSnackbar(
                    if (result.isSuccess) {
                        "Updated artist photo for \"$artistName\""
                    } else {
                        result.exceptionOrNull()?.message ?: "Could not save artist photo"
                    },
                )
            }
        }
    }

    fun playTrackAtQueueIndex(index: Int) {
        container.playbackController.playTrackAt(index)
    }

    fun enqueueTrack(track: PlaybackTrack) {
        container.playbackController.enqueue(track)
        emitSnackbar("Added \"${track.title}\" to queue")
    }

    fun enqueueTrackNext(track: PlaybackTrack) {
        container.playbackController.enqueueNext(track)
        emitSnackbar("Queued \"${track.title}\" to play next")
    }

    fun enqueueTracks(tracks: List<PlaybackTrack>, context: QueueContext) {
        if (tracks.isEmpty()) return
        container.playbackController.enqueueAll(tracks, fallbackContext = context)
        emitSnackbar("Added ${tracks.size} ${if (tracks.size == 1) "track" else "tracks"} from \"${context.sourceLabel}\" to queue")
    }

    fun enqueueTracksNext(tracks: List<PlaybackTrack>, context: QueueContext) {
        if (tracks.isEmpty()) return
        container.playbackController.enqueueAllNext(tracks, fallbackContext = context)
        emitSnackbar(
            "Queued ${tracks.size} ${if (tracks.size == 1) "track" else "tracks"} from \"${context.sourceLabel}\" to play next",
        )
    }

    fun setTrackSort(scope: TrackSortScope, spec: TrackSortSpec) {
        viewModelScope.launch {
            container.preferencesRepository.setTrackSort(scope, spec)
        }
    }

    fun setGroupSort(scope: GroupSortScope, spec: GroupSortSpec) {
        viewModelScope.launch {
            container.preferencesRepository.setGroupSort(scope, spec)
        }
    }

    fun removeQueueItem(index: Int) {
        container.playbackController.removeQueueItem(index)
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        container.playbackController.moveQueueItem(fromIndex, toIndex)
    }

    fun togglePlayPause() = container.playbackController.togglePlayPause()
    fun next() = container.playbackController.next(userInitiated = true)
    fun previous() = container.playbackController.previous()
    fun seekTo(positionMs: Long) = container.playbackController.seekTo(positionMs)
    fun beginTurntableScratch(): Boolean = container.playbackController.beginTurntableScratch()
    fun updateTurntableScratchRate(playbackRate: Float) = container.playbackController.updateTurntableScratchRate(playbackRate)
    fun endTurntableScratch(fallbackTargetMs: Long? = null) = container.playbackController.endTurntableScratch(fallbackTargetMs)
    fun toggleShuffle() = container.playbackController.toggleShuffle()
    fun cycleRepeatMode() = container.playbackController.cycleRepeatMode()
    fun setGaplessEnabled(enabled: Boolean) = container.playbackController.setGaplessEnabled(enabled)
    fun setReplayGainMode(mode: ReplayGainMode) = container.playbackController.setReplayGainMode(mode)
    fun setReplayGainPreampDb(preampDb: Float) = container.playbackController.setReplayGainPreampDb(preampDb)
    fun setReplayGainPreventClipping(preventClipping: Boolean) = container.playbackController.setReplayGainPreventClipping(preventClipping)

    fun setNowPlayingArtworkStyle(style: NowPlayingArtworkStyle) {
        viewModelScope.launch {
            container.preferencesRepository.setNowPlayingArtworkStyle(style)
        }
    }

    fun setSpectrumVisualizerEnabled(enabled: Boolean) {
        if (!enabled) {
            container.playbackController.setSpectrumTapActive(false)
        }
        viewModelScope.launch {
            container.preferencesRepository.setSpectrumVisualizerEnabled(enabled)
        }
    }

    fun setSpectrumColorStyle(style: SpectrumColorStyle) {
        viewModelScope.launch {
            container.preferencesRepository.setSpectrumColorStyle(style)
        }
    }

    fun setSpectrumSparksEnabled(enabled: Boolean) {
        viewModelScope.launch {
            container.preferencesRepository.setSpectrumSparksEnabled(enabled)
        }
    }

    fun setDefaultHomepage(homepage: DefaultHomepage) {
        viewModelScope.launch {
            container.preferencesRepository.setDefaultHomepage(homepage)
        }
    }

    fun setDynamicCoverThemeEnabled(enabled: Boolean) {
        viewModelScope.launch {
            container.preferencesRepository.setDynamicCoverThemeEnabled(enabled)
        }
    }

    fun setContextualDynamicTheme(sourceKey: String, tokens: DynamicThemeTokens?) {
        contextualDynamicThemeBySourceState.update { current ->
            if (tokens == null) {
                current - sourceKey
            } else {
                current + (sourceKey to tokens)
            }
        }
    }

    fun setDiagnosticLoggingEnabled(enabled: Boolean) {
        container.diagnosticLogger.setEnabled(enabled)
        viewModelScope.launch {
            container.preferencesRepository.setDiagnosticLoggingEnabled(enabled)
            if (enabled) {
                val prefs = container.preferencesRepository.getSnapshot()
                container.diagnosticLogger.writeDeviceSummary(container.buildDeviceSummaryFields(prefs))
            }
            emitSnackbar(
                if (enabled) {
                    "Local diagnostic logging enabled (stored privately on-device)"
                } else {
                    "Local diagnostic logging paused"
                },
            )
        }
    }

    fun refreshDiagnosticLogStats() {
        viewModelScope.launch(Dispatchers.IO) {
            container.diagnosticLogger.refreshStorageStatsSync()
        }
    }

    fun exportDiagnosticLogs(destinationUri: Uri) {
        viewModelScope.launch {
            val summaryFields = if (container.diagnosticLogger.isEnabled) {
                container.buildDeviceSummaryFields(container.preferencesRepository.getSnapshot())
            } else {
                null
            }
            container.diagnosticLogExporter.exportToUri(destinationUri, summaryFields)
                .onSuccess { res ->
                    val label = if (res.fileCount == 1) "file" else "files"
                    emitSnackbar("Exported ${res.fileCount} diagnostic log $label (.zip)")
                }
                .onFailure { err ->
                    emitSnackbar(err.message ?: "Unable to export diagnostic logs")
                }
        }
    }

    fun shareDiagnosticLogs(onShareReady: (Intent) -> Unit) {
        viewModelScope.launch {
            val summaryFields = if (container.diagnosticLogger.isEnabled) {
                container.buildDeviceSummaryFields(container.preferencesRepository.getSnapshot())
            } else {
                null
            }
            container.diagnosticLogExporter.prepareShareIntent(summaryFields)
                .onSuccess { res ->
                    onShareReady(res.shareIntent)
                }
                .onFailure { err ->
                    emitSnackbar(err.message ?: "Unable to prepare diagnostic logs for sharing")
                }
        }
    }

    fun clearDiagnosticLogs() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                container.diagnosticLogger.clearAllLogs()
            }
            emitSnackbar("Cleared local diagnostic logs")
        }
    }

    fun setSpectrumVisualizerActive(active: Boolean) {
        container.playbackController.setSpectrumTapActive(active)
    }

    fun sampleCurrentSpectrumWindow(outMonoSamples: FloatArray): Int =
        container.playbackController.sampleCurrentSpectrumWindow(outMonoSamples)

    fun setSleepTimerPreset(preset: SleepTimerPreset) {
        container.playbackController.setSleepTimerPreset(preset)
        emitSnackbar(
            when (preset) {
                SleepTimerPreset.OFF -> "Sleep timer off"
                SleepTimerPreset.END_OF_TRACK -> "Sleep timer: pause after current track"
                else -> "Sleep timer set for ${preset.label}"
            },
        )
    }

    fun exportLocalBackup(destinationUri: Uri) {
        viewModelScope.launch {
            container.backupRepository.exportBackupToUri(destinationUri)
                .onSuccess { summary ->
                    emitSnackbar(
                        "Backup saved (" +
                            "${summary.playlistsCount} playlists, " +
                            "${summary.lyricsCount} lyrics, " +
                            "${summary.favoritesCount} favorites, " +
                            "${summary.statsCount} stats)",
                    )
                }
                .onFailure { err ->
                    emitSnackbar(err.message ?: "Unable to export local backup")
                }
        }
    }

    fun importLocalBackup(sourceUri: Uri) {
        viewModelScope.launch {
            container.backupRepository.importBackupFromUri(sourceUri)
                .onSuccess { summary ->
                    emitSnackbar(
                        "Backup restored (" +
                            "${summary.playlistsMerged} playlists, " +
                            "${summary.lyricsUpserted} lyrics, " +
                            "${summary.favoritesMerged} favorites, " +
                            "${summary.statsMerged} stats)",
                    )
                }
                .onFailure { err ->
                    emitSnackbar(err.message ?: "Invalid or unreadable backup file")
                }
        }
    }

    fun openTrackInfo(track: PlaybackTrack) {
        trackInfoJob?.cancel()
        _trackInfoState.value = TrackTechnicalInfo(
            track = track,
            isLoadingDetails = true,
        )
        trackInfoJob = viewModelScope.launch {
            val loaded = container.trackInfoLoader.loadFullDetails(track)
            if (_trackInfoState.value?.track?.uriString == track.uriString) {
                _trackInfoState.value = loaded
            }
        }
    }

    fun dismissTrackInfo() {
        trackInfoJob?.cancel()
        trackInfoJob = null
        _trackInfoState.value = null
    }

    fun showUiMessage(message: String) {
        emitSnackbar(message)
    }

    fun requestBitPerfectMode() {
        container.playbackController.requestBitPerfectMode()
    }

    fun confirmBitPerfectWarning() {
        container.playbackController.confirmBitPerfectWarning()
        if (container.diagnosticLogger.isEnabled) {
            viewModelScope.launch {
                val prefs = container.preferencesRepository.getSnapshot()
                container.diagnosticLogger.writeDeviceSummary(container.buildDeviceSummaryFields(prefs))
            }
        }
    }

    fun dismissSafetyWarning() {
        container.playbackController.dismissSafetyWarning()
    }

    fun switchToSafeMode() {
        container.playbackController.switchToSafeMode()
        if (container.diagnosticLogger.isEnabled) {
            viewModelScope.launch {
                val prefs = container.preferencesRepository.getSnapshot()
                container.diagnosticLogger.writeDeviceSummary(container.buildDeviceSummaryFields(prefs))
            }
        }
        emitSnackbar("Switched to Safe Mode (System Volume Active)")
    }

    fun clearAutoPauseNotice() {
        container.playbackController.clearAutoPauseNotice()
    }

    fun toggleFavorite(track: PlaybackTrack, showRemovalUndoSnackbar: Boolean = false) {
        viewModelScope.launch {
            val result = container.libraryRepository.toggleFavorite(track)
            result.onSuccess { isNowFavorite ->
                if (showRemovalUndoSnackbar && !isNowFavorite) {
                    emitSnackbar(
                        message = "Removed \"${track.title}\" from Favorites",
                        actionLabel = "Undo",
                        onAction = { restoreFavorite(track) },
                    )
                }
            }.onFailure {
                emitSnackbar("Unable to update favorite status")
            }
        }
    }

    private fun restoreFavorite(track: PlaybackTrack) {
        viewModelScope.launch {
            val snap = libraryState.value
            val alreadyFav = FavoriteStateReducer.isFavorited(
                track = track,
                favoriteUris = snap.favoriteUris,
                favoriteFingerprints = snap.favoriteFingerprints,
            )
            if (!alreadyFav) {
                container.libraryRepository.toggleFavorite(track)
            }
        }
    }

    fun createPlaylist(name: String) {
        viewModelScope.launch {
            val clean = name.trim()
            container.playlistRepository.createPlaylist(clean)
                .onSuccess {
                    emitSnackbar("Created playlist \"$clean\"")
                }
                .onFailure { err ->
                    emitSnackbar(err.message ?: "Unable to create playlist")
                }
        }
    }

    fun renamePlaylist(playlistId: Long, newName: String) {
        viewModelScope.launch {
            val clean = newName.trim()
            container.playlistRepository.renamePlaylist(playlistId, clean)
                .onSuccess {
                    emitSnackbar("Renamed playlist to \"$clean\"")
                }
                .onFailure { err ->
                    emitSnackbar(err.message ?: "Unable to rename playlist")
                }
        }
    }

    fun deletePlaylist(playlistId: Long, playlistName: String) {
        viewModelScope.launch {
            container.playlistRepository.deletePlaylist(playlistId)
                .onSuccess {
                    emitSnackbar("Deleted playlist \"$playlistName\"")
                }
                .onFailure { err ->
                    emitSnackbar(err.message ?: "Unable to delete playlist")
                }
        }
    }

    fun commitPlaylistOrder(playlistId: Long, orderedEntryIds: List<Long>) {
        viewModelScope.launch {
            container.playlistRepository.commitPlaylistOrder(playlistId, orderedEntryIds)
                .onFailure { err ->
                    emitSnackbar(err.message ?: "Unable to reorder playlist")
                }
        }
    }

    fun exportPlaylistToM3u8(playlist: ResolvedPlaylist, destinationUri: Uri) {
        viewModelScope.launch {
            container.playlistRepository.exportPlaylistToM3u8(playlist, destinationUri)
                .onSuccess { exportedCount ->
                    emitSnackbar("Exported \"${playlist.name}\" ($exportedCount ${if (exportedCount == 1) "track" else "tracks"})")
                }
                .onFailure { err ->
                    emitSnackbar(err.message ?: "Unable to export .m3u8 playlist")
                }
        }
    }

    fun importPlaylistFromM3u8(sourceUri: Uri) {
        viewModelScope.launch {
            val allTracks = libraryState.value.allSongs
            container.playlistRepository.importPlaylistFromM3u8(sourceUri, allTracks)
                .onSuccess { summary ->
                    emitSnackbar(
                        if (summary.unmatchedCount > 0) {
                            "Imported \"${summary.playlistName}\": ${summary.matchedCount} matched, ${summary.unmatchedCount} missing"
                        } else {
                            "Imported \"${summary.playlistName}\" (${summary.matchedCount} ${if (summary.matchedCount == 1) "track" else "tracks"})"
                        },
                    )
                }
                .onFailure { err ->
                    emitSnackbar(err.message ?: "Unable to import .m3u8 playlist")
                }
        }
    }

    fun importLrcForActiveTrack(lrcUri: Uri) {
        val track = playbackState.value.activeTrack ?: return
        viewModelScope.launch {
            _lyricsUiState.update { it.copy(isLoading = true, errorMessage = null) }
            val result = container.lyricsRepository.importLrcFromSaf(track, lrcUri)
            result.onSuccess { state ->
                _lyricsUiState.value = LyricsUiState(
                    trackUri = track.uriString,
                    isLoading = false,
                    lyricsState = state,
                )
                emitSnackbar(state.statusMessage ?: "Imported .lrc lyrics")
            }.onFailure { err ->
                _lyricsUiState.update {
                    it.copy(isLoading = false, errorMessage = err.message ?: "Failed to import .lrc")
                }
                emitSnackbar(err.message ?: "Failed to import .lrc file")
            }
        }
    }

    fun saveManualLyricsForActiveTrack(rawText: String) {
        val track = playbackState.value.activeTrack ?: return
        viewModelScope.launch {
            _lyricsUiState.update { it.copy(isLoading = true, errorMessage = null) }
            val result = container.lyricsRepository.saveManualLyrics(track, rawText)
            result.onSuccess { state ->
                _lyricsUiState.value = LyricsUiState(
                    trackUri = track.uriString,
                    isLoading = false,
                    lyricsState = state,
                )
                emitSnackbar(state.statusMessage ?: "Lyrics saved locally")
            }.onFailure { err ->
                _lyricsUiState.update {
                    it.copy(isLoading = false, errorMessage = err.message ?: "Failed to save lyrics")
                }
                emitSnackbar(err.message ?: "Failed to save lyrics")
            }
        }
    }

    fun deleteLyricsForActiveTrack() {
        val track = playbackState.value.activeTrack ?: return
        viewModelScope.launch {
            _lyricsUiState.update { it.copy(isLoading = true, errorMessage = null) }
            val result = container.lyricsRepository.deleteLyrics(track)
            result.onSuccess { state ->
                _lyricsUiState.value = LyricsUiState(
                    trackUri = track.uriString,
                    isLoading = false,
                    lyricsState = state,
                )
                emitSnackbar("Lyrics deleted")
            }.onFailure { err ->
                _lyricsUiState.update {
                    it.copy(isLoading = false, errorMessage = err.message ?: "Failed to delete lyrics")
                }
            }
        }
    }

    internal fun emitSnackbar(message: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
        _uiSnackbarMessage.value = UiSnackbarMessage(
            id = nextSnackbarId.getAndIncrement(),
            message = message,
            actionLabel = actionLabel,
            onAction = onAction,
        )
    }

    fun clearUiMessage(expectedId: Long? = null) {
        if (expectedId == null || _uiSnackbarMessage.value?.id == expectedId) {
            _uiSnackbarMessage.value = null
        }
    }

    private fun loadLyricsForTrack(track: PlaybackTrack) {
        viewModelScope.launch {
            _lyricsUiState.value = LyricsUiState(trackUri = track.uriString, isLoading = true)
            val state = container.lyricsRepository.loadLyricsForTrack(track)
            if (playbackState.value.activeTrack?.uriString == track.uriString) {
                _lyricsUiState.value = LyricsUiState(
                    trackUri = track.uriString,
                    isLoading = false,
                    lyricsState = state,
                )
            }
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(container) as T
        }
    }
}

fun MainViewModel.dismissActiveSession() = container.playbackController.dismissActiveSession()

fun MainViewModel.createPlaylistAndAddTrack(name: String, track: PlaybackTrack) {
    viewModelScope.launch {
        val clean = name.trim()
        container.playlistRepository.createPlaylist(clean)
            .onSuccess { newId ->
                container.playlistRepository.addTracksToPlaylist(newId, listOf(track))
                    .onSuccess {
                        emitSnackbar("Added \"${track.title}\" to \"$clean\"")
                    }
                    .onFailure { err ->
                        emitSnackbar(err.message ?: "Created \"$clean\" but failed to add track")
                    }
            }
            .onFailure { err ->
                emitSnackbar(err.message ?: "Unable to create playlist")
            }
    }
}

fun MainViewModel.addTrackToPlaylist(playlistId: Long, playlistName: String, track: PlaybackTrack) {
    viewModelScope.launch {
        container.playlistRepository.addTrackToPlaylistUnique(playlistId, track)
            .onSuccess { outcome ->
                when (outcome) {
                    AddTrackToPlaylistOutcome.ADDED ->
                        emitSnackbar("Added \"${track.title}\" to \"$playlistName\"")
                    AddTrackToPlaylistOutcome.ALREADY_EXISTS ->
                        emitSnackbar("\"${track.title}\" is already in \"$playlistName\"")
                }
            }
            .onFailure { err ->
                emitSnackbar(err.message ?: "Unable to add track to playlist")
            }
    }
}

fun MainViewModel.removePlaylistEntry(playlistId: Long, entryId: Long, playlistName: String = "", trackTitle: String = "") {
    viewModelScope.launch {
        val resolvedPlaylistName = playlistName.ifBlank {
            playlistsState.value.playlists.firstOrNull { it.id == playlistId }?.name ?: "playlist"
        }
        container.playlistRepository.removeEntryFromPlaylist(playlistId, entryId)
            .onSuccess { removedEntity ->
                val displayTitle = trackTitle.ifBlank { removedEntity?.fileName ?: "track" }
                emitSnackbar(
                    message = "Removed \"$displayTitle\" from \"$resolvedPlaylistName\"",
                    actionLabel = if (removedEntity != null) "Undo" else null,
                    onAction = removedEntity?.let { entity -> { restoreRemovedPlaylistEntry(entity) } },
                )
            }
            .onFailure { err ->
                emitSnackbar(err.message ?: "Unable to remove track from playlist")
            }
    }
}

private fun MainViewModel.restoreRemovedPlaylistEntry(entity: PlaylistTrackEntity) {
    viewModelScope.launch {
        container.playlistRepository.restoreRemovedEntry(entity)
            .onFailure { err ->
                emitSnackbar(err.message ?: "Unable to restore track to playlist")
            }
    }
}
