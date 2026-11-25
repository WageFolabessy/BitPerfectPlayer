package com.cangkir.bitperfectplayer.ui

import android.net.Uri
import com.cangkir.bitperfectplayer.audio.PlaybackTrack
import com.cangkir.bitperfectplayer.data.UserPreferencesStore
import com.cangkir.bitperfectplayer.library.ArtistPhotoStorage
import com.cangkir.bitperfectplayer.library.FavoriteStateReducer
import com.cangkir.bitperfectplayer.library.GroupSortScope
import com.cangkir.bitperfectplayer.library.GroupSortSpec
import com.cangkir.bitperfectplayer.library.LibraryRepository
import com.cangkir.bitperfectplayer.library.LibrarySnapshot
import com.cangkir.bitperfectplayer.library.TrackDetailsLoader
import com.cangkir.bitperfectplayer.library.TrackSortScope
import com.cangkir.bitperfectplayer.library.TrackSortSpec
import com.cangkir.bitperfectplayer.library.TrackTechnicalInfo
import com.cangkir.bitperfectplayer.lyrics.LyricsStore
import com.cangkir.bitperfectplayer.lyrics.TrackLyricsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LyricsUiState(
    val trackUri: String? = null,
    val isLoading: Boolean = false,
    val lyricsState: TrackLyricsState? = null,
    val errorMessage: String? = null,
)

/**
 * Coordinates local music library scanning, search filtering, sort scope persistence,
 * SAF folder imports, custom artist photos, favorites (with undo), Track Info sheets, and lyrics.
 */
class LibraryAndLyricsCoordinator(
    private val scope: CoroutineScope,
    private val libraryRepository: LibraryRepository,
    private val artistPhotoStorage: ArtistPhotoStorage,
    private val trackDetailsLoader: TrackDetailsLoader,
    private val lyricsStore: LyricsStore,
    private val preferencesStore: UserPreferencesStore,
    private val messageEmitter: UiMessageEmitter,
    private val onClearArtworkCaches: () -> Unit = {},
) {
    val libraryState: StateFlow<LibrarySnapshot> = libraryRepository.librarySnapshot
    val artistPhotoRevision: StateFlow<Int> = artistPhotoStorage.photoRevision

    val favoriteTrackUris: StateFlow<Set<String>> = libraryState
        .map { it.favoriteUris }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = libraryState.value.favoriteUris,
        )

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val filteredAllSongs: StateFlow<List<PlaybackTrack>> = combine(libraryState, _searchQuery) { library, query ->
        filterTracks(library.allSongs, query)
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = libraryState.value.allSongs,
    )

    private val _lyricsUiState = MutableStateFlow(LyricsUiState())
    val lyricsUiState: StateFlow<LyricsUiState> = _lyricsUiState.asStateFlow()

    private val _trackInfoState = MutableStateFlow<TrackTechnicalInfo?>(null)
    val trackInfoState: StateFlow<TrackTechnicalInfo?> = _trackInfoState.asStateFlow()
    private var trackInfoJob: Job? = null

    fun triggerInitialSync() {
        scope.launch {
            libraryRepository.refreshLibrary(forceYearBackfill = false)
        }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun refreshLibrary() {
        onClearArtworkCaches()
        scope.launch {
            libraryRepository.refreshLibrary(forceYearBackfill = true)
        }
    }

    fun importSafFolder(treeUri: Uri) {
        scope.launch {
            val count = libraryRepository.importSafFolder(treeUri)
            val fileWord = if (count == 1) "file" else "files"
            messageEmitter.emitSnackbar(
                if (count > 0) {
                    "Imported folder ($count audio $fileWord)"
                } else {
                    "Folder linked (no supported FLAC/WAV/MP3/M4A/OGG files found)"
                },
            )
        }
    }

    fun removeSafFolder(treeUriString: String) {
        scope.launch {
            libraryRepository.removeSafFolder(treeUriString)
            messageEmitter.emitSnackbar("Removed linked SAF folder")
        }
    }

    fun updateCustomArtistPhoto(artistName: String, sourceUri: Uri?) {
        scope.launch {
            if (sourceUri == null) {
                artistPhotoStorage.clearArtistPhoto(artistName)
                messageEmitter.emitSnackbar("Reset artist photo for \"$artistName\"")
            } else {
                val result = artistPhotoStorage.saveArtistPhotoResult(artistName, sourceUri)
                messageEmitter.emitSnackbar(
                    if (result.isSuccess) {
                        "Updated artist photo for \"$artistName\""
                    } else {
                        result.exceptionOrNull()?.message ?: "Could not save artist photo"
                    },
                )
            }
        }
    }

    fun toggleFavorite(track: PlaybackTrack, showRemovalUndoSnackbar: Boolean = false) {
        scope.launch {
            libraryRepository.toggleFavorite(track)
                .onSuccess { isNowFavorite ->
                    if (showRemovalUndoSnackbar && !isNowFavorite) {
                        messageEmitter.emitSnackbar(
                            message = "Removed \"${track.title}\" from Favorites",
                            actionLabel = "Undo",
                            onAction = { restoreFavorite(track) },
                        )
                    }
                }
                .onFailure {
                    messageEmitter.emitSnackbar("Unable to update favorite status")
                }
        }
    }

    fun setTrackSort(scopeKey: TrackSortScope, spec: TrackSortSpec) {
        scope.launch {
            preferencesStore.setTrackSort(scopeKey, spec)
        }
    }

    fun setGroupSort(scopeKey: GroupSortScope, spec: GroupSortSpec) {
        scope.launch {
            preferencesStore.setGroupSort(scopeKey, spec)
        }
    }

    fun openTrackInfo(track: PlaybackTrack) {
        trackInfoJob?.cancel()
        _trackInfoState.value = TrackTechnicalInfo(
            track = track,
            isLoadingDetails = true,
        )
        trackInfoJob = scope.launch {
            val loaded = trackDetailsLoader.loadFullDetails(track)
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

    fun loadLyricsForActiveTrack(track: PlaybackTrack?, isStillActiveTrack: (String) -> Boolean) {
        if (track == null) {
            _lyricsUiState.value = LyricsUiState()
            return
        }
        scope.launch {
            _lyricsUiState.value = LyricsUiState(trackUri = track.uriString, isLoading = true)
            val state = lyricsStore.loadLyricsForTrack(track)
            if (isStillActiveTrack(track.uriString)) {
                _lyricsUiState.value = LyricsUiState(
                    trackUri = track.uriString,
                    isLoading = false,
                    lyricsState = state,
                )
            }
        }
    }

    fun importLrcForTrack(track: PlaybackTrack, lrcUri: Uri) {
        runLyricsMutation(
            track = track,
            useStatusMessage = true,
            successFallbackMessage = "Imported .lrc lyrics",
            failureErrorFallback = "Failed to import .lrc",
            failureSnackbarFallback = "Failed to import .lrc file",
        ) { target ->
            lyricsStore.importLrcFromSaf(target, lrcUri)
        }
    }

    fun saveManualLyricsForTrack(track: PlaybackTrack, rawText: String) {
        runLyricsMutation(
            track = track,
            useStatusMessage = true,
            successFallbackMessage = "Lyrics saved locally",
            failureErrorFallback = "Failed to save lyrics",
            failureSnackbarFallback = "Failed to save lyrics",
        ) { target ->
            lyricsStore.saveManualLyrics(target, rawText)
        }
    }

    fun deleteLyricsForTrack(track: PlaybackTrack) {
        runLyricsMutation(
            track = track,
            useStatusMessage = false,
            successFallbackMessage = "Lyrics deleted",
            failureErrorFallback = "Failed to delete lyrics",
            failureSnackbarFallback = null,
        ) { target ->
            lyricsStore.deleteLyrics(target)
        }
    }

    private fun restoreFavorite(track: PlaybackTrack) {
        scope.launch {
            val snap = libraryState.value
            val alreadyFav = FavoriteStateReducer.isFavorited(
                track = track,
                favoriteUris = snap.favoriteUris,
                favoriteFingerprints = snap.favoriteFingerprints,
            )
            if (!alreadyFav) {
                libraryRepository.toggleFavorite(track)
            }
        }
    }

    private fun runLyricsMutation(
        track: PlaybackTrack,
        useStatusMessage: Boolean,
        successFallbackMessage: String,
        failureErrorFallback: String,
        failureSnackbarFallback: String?,
        mutation: suspend (PlaybackTrack) -> Result<TrackLyricsState>,
    ) {
        scope.launch {
            _lyricsUiState.update { it.copy(isLoading = true, errorMessage = null) }
            mutation(track)
                .onSuccess { state ->
                    _lyricsUiState.value = LyricsUiState(
                        trackUri = track.uriString,
                        isLoading = false,
                        lyricsState = state,
                    )
                    val message = if (useStatusMessage) {
                        state.statusMessage ?: successFallbackMessage
                    } else {
                        successFallbackMessage
                    }
                    messageEmitter.emitSnackbar(message)
                }
                .onFailure { err ->
                    _lyricsUiState.update {
                        it.copy(isLoading = false, errorMessage = err.message ?: failureErrorFallback)
                    }
                    if (failureSnackbarFallback != null) {
                        messageEmitter.emitSnackbar(err.message ?: failureSnackbarFallback)
                    }
                }
        }
    }

    companion object {
        fun filterTracks(tracks: List<PlaybackTrack>, query: String): List<PlaybackTrack> {
            val trimmed = query.trim()
            if (trimmed.isEmpty()) return tracks
            return tracks.filter { track ->
                track.title.contains(trimmed, ignoreCase = true) ||
                    track.artist.contains(trimmed, ignoreCase = true) ||
                    track.album.contains(trimmed, ignoreCase = true) ||
                    track.fileName.contains(trimmed, ignoreCase = true)
            }
        }
    }
}
