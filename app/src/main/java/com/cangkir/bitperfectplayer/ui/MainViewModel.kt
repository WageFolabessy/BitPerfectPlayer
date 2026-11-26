package com.cangkir.bitperfectplayer.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cangkir.bitperfectplayer.audio.PlaybackAudioControl
import com.cangkir.bitperfectplayer.audio.PlaybackControllerState
import com.cangkir.bitperfectplayer.audio.PlaybackStatus
import com.cangkir.bitperfectplayer.audio.PlaybackTrack
import com.cangkir.bitperfectplayer.audio.PlaybackTransportControl
import com.cangkir.bitperfectplayer.audio.QueueContext
import com.cangkir.bitperfectplayer.audio.RepeatMode
import com.cangkir.bitperfectplayer.audio.ReplayGainMode
import com.cangkir.bitperfectplayer.audio.SleepTimerState
import com.cangkir.bitperfectplayer.data.DefaultHomepage
import com.cangkir.bitperfectplayer.data.LocalBackupStore
import com.cangkir.bitperfectplayer.data.NowPlayingArtworkStyle
import com.cangkir.bitperfectplayer.data.SpectrumColorStyle
import com.cangkir.bitperfectplayer.data.UserPreferencesStore
import com.cangkir.bitperfectplayer.diagnostics.DiagnosticLogExporterContract
import com.cangkir.bitperfectplayer.diagnostics.DiagnosticLogStats
import com.cangkir.bitperfectplayer.library.ArtistPhotoStorage
import com.cangkir.bitperfectplayer.library.LibraryRepository
import com.cangkir.bitperfectplayer.library.LibrarySnapshot
import com.cangkir.bitperfectplayer.library.PlaylistStore
import com.cangkir.bitperfectplayer.library.PlaylistsUiSnapshot
import com.cangkir.bitperfectplayer.library.TrackDetailsLoader
import com.cangkir.bitperfectplayer.library.TrackTechnicalInfo
import com.cangkir.bitperfectplayer.lyrics.LyricsStore
import com.cangkir.bitperfectplayer.ui.theme.ArtworkColorPalette
import com.cangkir.bitperfectplayer.ui.theme.CoverThemeResolver
import com.cangkir.bitperfectplayer.ui.theme.DynamicThemeCoordinator
import com.cangkir.bitperfectplayer.ui.theme.DynamicThemeTokens
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun interface PlaybackArtworkPreloader {
    suspend fun preloadDiscArtwork(track: PlaybackTrack)
}

data class DiagnosticServices(val controls: DiagnosticCoordinatorControls, val exporter: DiagnosticLogExporterContract)

data class MainViewModelMediaServices(
    val coverThemeResolver: CoverThemeResolver,
    val artworkPreloader: PlaybackArtworkPreloader = PlaybackArtworkPreloader { },
    val onClearArtworkCaches: () -> Unit = {},
    val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
)

/**
 * Shared presentation ViewModel composing playback transport/DSP controls with cohesive domain
 * coordinators ([library], [playlists], [settings], [dynamicTheme]). Depends strictly on narrow
 * interfaces so all state transitions and coordinator behaviors run under pure JVM unit tests
 * without an application container.
 */
class MainViewModel(
    private val playbackTransport: PlaybackTransportControl,
    private val playbackAudio: PlaybackAudioControl,
    libraryRepository: LibraryRepository,
    playlistStore: PlaylistStore,
    artistPhotoStorage: ArtistPhotoStorage,
    trackDetailsLoader: TrackDetailsLoader,
    lyricsStore: LyricsStore,
    preferencesStore: UserPreferencesStore,
    backupStore: LocalBackupStore,
    diagnosticServices: DiagnosticServices,
    private val mediaServices: MainViewModelMediaServices,
) : ViewModel() {

    private val snackbarController = UiSnackbarController()

    val settings = SettingsAndBackupCoordinator(
        scope = viewModelScope,
        preferencesStore = preferencesStore,
        backupStore = backupStore,
        diagnosticControls = diagnosticServices.controls,
        diagnosticLogExporter = diagnosticServices.exporter,
        audioControl = playbackAudio,
        messageEmitter = snackbarController,
        ioDispatcher = mediaServices.ioDispatcher,
    )

    val library = LibraryAndLyricsCoordinator(
        scope = viewModelScope,
        libraryRepository = libraryRepository,
        artistPhotoStorage = artistPhotoStorage,
        trackDetailsLoader = trackDetailsLoader,
        lyricsStore = lyricsStore,
        preferencesStore = preferencesStore,
        messageEmitter = snackbarController,
        onClearArtworkCaches = mediaServices.onClearArtworkCaches,
    )

    val playlists = PlaylistUiCoordinator(
        scope = viewModelScope,
        playlistStore = playlistStore,
        messageEmitter = snackbarController,
    )

    val libraryState: StateFlow<LibrarySnapshot> = library.libraryState
    val searchQuery: StateFlow<String> = library.searchQuery
    val filteredAllSongs: StateFlow<List<PlaybackTrack>> = library.filteredAllSongs
    val playlistsState: StateFlow<PlaylistsUiSnapshot> = playlists.playlistsState
    val favoriteTrackUris: StateFlow<Set<String>> = library.favoriteTrackUris
    val playbackState: StateFlow<PlaybackControllerState> = playbackTransport.state
    val playbackPositionMs: StateFlow<Long> = playbackTransport.playbackPositionMs
    val sleepTimerState: StateFlow<SleepTimerState> = playbackTransport.sleepTimerState
    val artistPhotoRevision: StateFlow<Int> = library.artistPhotoRevision
    val diagnosticLogStats: StateFlow<DiagnosticLogStats> = settings.diagnosticLogStats

    val nowPlayingArtworkStyle: StateFlow<NowPlayingArtworkStyle> = settings.nowPlayingArtworkStyle
    val spectrumVisualizerEnabled: StateFlow<Boolean> = settings.spectrumVisualizerEnabled
    val spectrumColorStyle: StateFlow<SpectrumColorStyle> = settings.spectrumColorStyle
    val spectrumSparksEnabled: StateFlow<Boolean> = settings.spectrumSparksEnabled
    val defaultHomepage: StateFlow<DefaultHomepage> = settings.defaultHomepage
    val diagnosticLoggingEnabled: StateFlow<Boolean> = settings.diagnosticLoggingEnabled
    val dynamicCoverThemeEnabled: StateFlow<Boolean> = settings.dynamicCoverThemeEnabled
    val initialStartupHomepage: StateFlow<DefaultHomepage?> = settings.initialStartupHomepage

    val dynamicTheme = DynamicThemeCoordinator(
        scope = viewModelScope,
        coverThemeResolver = mediaServices.coverThemeResolver,
        dynamicThemeEnabled = dynamicCoverThemeEnabled,
        activeTrackFlow = playbackState
            .map { it.activeTrack }
            .stateIn(scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = playbackState.value.activeTrack),
        artworkStyleFlow = nowPlayingArtworkStyle,
    )

    val globalDynamicThemeTokens: StateFlow<DynamicThemeTokens?> = dynamicTheme.globalDynamicThemeTokens
    val activeTrackPalette: StateFlow<ArtworkColorPalette?> = dynamicTheme.activeTrackPalette

    val lyricsUiState: StateFlow<LyricsUiState> = library.lyricsUiState
    val trackInfoState: StateFlow<TrackTechnicalInfo?> = library.trackInfoState
    val uiSnackbarMessage: StateFlow<UiSnackbarMessage?> = snackbarController.messages

    init {
        library.triggerInitialSync()
        observeSessionRestore()
        observeActiveTrackLyrics()
        observeAdjacentArtworkPreload()
    }

    private fun observeSessionRestore() {
        viewModelScope.launch {
            combine(libraryState, playlistsState) { lib, pl -> lib to pl }
                .collect { (snapshot, resolvedPlaylists) ->
                    if (snapshot.allSongs.isNotEmpty()) {
                        playbackTransport.restoreLastSessionIfNeeded(
                            library = snapshot,
                            playlists = resolvedPlaylists,
                        )
                    }
                }
        }
    }

    private fun observeActiveTrackLyrics() {
        viewModelScope.launch {
            playbackState
                .distinctUntilChangedBy { it.activeTrack?.uriString }
                .collect { state ->
                    library.loadLyricsForActiveTrack(state.activeTrack) { targetUri ->
                        playbackState.value.activeTrack?.uriString == targetUri
                    }
                }
        }
    }

    private fun observeAdjacentArtworkPreload() {
        viewModelScope.launch {
            playbackState
                .map { state -> resolveAdjacentDiscCandidates(state) }
                .distinctUntilChangedBy { pair -> pair?.first?.uriString to pair?.second?.uriString }
                .collectLatest { pair ->
                    val (nextCandidate, prevCandidate) = pair ?: return@collectLatest
                    withContext(mediaServices.ioDispatcher) {
                        if (nextCandidate != null) {
                            mediaServices.artworkPreloader.preloadDiscArtwork(nextCandidate)
                        }
                        if (prevCandidate != null && prevCandidate.uriString != nextCandidate?.uriString) {
                            mediaServices.artworkPreloader.preloadDiscArtwork(prevCandidate)
                        }
                    }
                }
        }
    }

    private fun resolveAdjacentDiscCandidates(state: PlaybackControllerState): Pair<PlaybackTrack?, PlaybackTrack?>? {
        if (state.engineState.status != PlaybackStatus.PLAYING) return null
        val items = state.queue.items
        val idx = state.queue.currentIndex
        if (idx !in items.indices || items.size <= 1) return null
        val repeat = state.queue.repeatMode
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
        return Pair(items.getOrNull(nextIdx), items.getOrNull(prevIdx))
    }

    fun playTracks(
        tracks: List<PlaybackTrack>,
        startIndex: Int = 0,
        context: QueueContext = QueueContext.allSongs(),
        shuffled: Boolean = false,
    ) {
        if (shuffled) {
            playbackTransport.playQueueShuffled(tracks = tracks, context = context)
        } else {
            playbackTransport.playQueue(
                tracks = tracks,
                startIndex = startIndex,
                autoPlay = true,
                context = context,
            )
        }
    }

    fun playTrackAtQueueIndex(index: Int) {
        playbackTransport.playTrackAt(index)
    }

    fun enqueueTrack(track: PlaybackTrack) {
        playbackTransport.enqueue(track)
        snackbarController.emitSnackbar("Added \"${track.title}\" to queue")
    }

    fun enqueueTrackNext(track: PlaybackTrack) {
        playbackTransport.enqueueNext(track)
        snackbarController.emitSnackbar("Queued \"${track.title}\" to play next")
    }

    fun enqueueTracks(tracks: List<PlaybackTrack>, context: QueueContext) {
        if (tracks.isEmpty()) return
        playbackTransport.enqueueAll(tracks, fallbackContext = context)
        val trackWord = if (tracks.size == 1) "track" else "tracks"
        snackbarController.emitSnackbar("Added ${tracks.size} $trackWord from \"${context.sourceLabel}\" to queue")
    }

    fun enqueueTracksNext(tracks: List<PlaybackTrack>, context: QueueContext) {
        if (tracks.isEmpty()) return
        playbackTransport.enqueueAllNext(tracks, fallbackContext = context)
        val trackWord = if (tracks.size == 1) "track" else "tracks"
        snackbarController.emitSnackbar("Queued ${tracks.size} $trackWord from \"${context.sourceLabel}\" to play next")
    }

    fun removeQueueItem(index: Int) {
        playbackTransport.removeQueueItem(index)
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        playbackTransport.moveQueueItem(fromIndex, toIndex)
    }

    fun togglePlayPause() = playbackTransport.togglePlayPause()

    fun next() = playbackTransport.next(userInitiated = true)

    fun previous() = playbackTransport.previous()

    fun seekTo(positionMs: Long) = playbackTransport.seekTo(positionMs)

    fun dismissActiveSession() = playbackTransport.dismissActiveSession()

    fun beginTurntableScratch(): Boolean = playbackAudio.beginTurntableScratch()

    fun updateTurntableScratchRate(playbackRate: Float) = playbackAudio.updateTurntableScratchRate(playbackRate)

    fun endTurntableScratch(fallbackTargetMs: Long? = null) = playbackAudio.endTurntableScratch(fallbackTargetMs)

    fun toggleShuffle() = playbackTransport.toggleShuffle()

    fun cycleRepeatMode() = playbackTransport.cycleRepeatMode()

    fun setGaplessEnabled(enabled: Boolean) = playbackAudio.setGaplessEnabled(enabled)

    fun setReplayGainMode(mode: ReplayGainMode) = playbackAudio.setReplayGainMode(mode)

    fun setReplayGainPreampDb(preampDb: Float) = playbackAudio.setReplayGainPreampDb(preampDb)

    fun setReplayGainPreventClipping(preventClipping: Boolean) = playbackAudio.setReplayGainPreventClipping(preventClipping)

    fun setContextualDynamicTheme(sourceKey: String, tokens: DynamicThemeTokens?) {
        dynamicTheme.setContextualDynamicTheme(sourceKey, tokens)
    }

    fun setSpectrumVisualizerActive(active: Boolean) {
        playbackAudio.setSpectrumTapActive(active)
    }

    fun sampleCurrentSpectrumWindow(outMonoSamples: FloatArray): Int = playbackAudio.sampleCurrentSpectrumWindow(outMonoSamples)

    fun showUiMessage(message: String) {
        snackbarController.emitSnackbar(message)
    }

    fun clearUiMessage(expectedId: Long? = null) {
        snackbarController.clear(expectedId)
    }

    fun importPlaylistFromM3u8(sourceUri: Uri) {
        playlists.importPlaylistFromM3u8(sourceUri, libraryState.value.allSongs)
    }

    fun importLrcForActiveTrack(lrcUri: Uri) {
        val track = playbackState.value.activeTrack ?: return
        library.importLrcForTrack(track, lrcUri)
    }

    fun saveManualLyricsForActiveTrack(rawText: String) {
        val track = playbackState.value.activeTrack ?: return
        library.saveManualLyricsForTrack(track, rawText)
    }

    fun deleteLyricsForActiveTrack() {
        val track = playbackState.value.activeTrack ?: return
        library.deleteLyricsForTrack(track)
    }

    class Factory(
        private val playbackTransport: PlaybackTransportControl,
        private val playbackAudio: PlaybackAudioControl,
        private val libraryRepository: LibraryRepository,
        private val playlistStore: PlaylistStore,
        private val artistPhotoStorage: ArtistPhotoStorage,
        private val trackDetailsLoader: TrackDetailsLoader,
        private val lyricsStore: LyricsStore,
        private val preferencesStore: UserPreferencesStore,
        private val backupStore: LocalBackupStore,
        private val diagnosticServices: DiagnosticServices,
        private val mediaServices: MainViewModelMediaServices,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(MainViewModel::class.java)) {
                "Unknown ViewModel class: ${modelClass.name}"
            }
            val instance = MainViewModel(
                playbackTransport = playbackTransport,
                playbackAudio = playbackAudio,
                libraryRepository = libraryRepository,
                playlistStore = playlistStore,
                artistPhotoStorage = artistPhotoStorage,
                trackDetailsLoader = trackDetailsLoader,
                lyricsStore = lyricsStore,
                preferencesStore = preferencesStore,
                backupStore = backupStore,
                diagnosticServices = diagnosticServices,
                mediaServices = mediaServices,
            )
            return modelClass.cast(instance)!!
        }
    }
}
