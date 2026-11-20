package com.cangkir.bitperfectplayer.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cangkir.bitperfectplayer.AppContainer
import com.cangkir.bitperfectplayer.audio.PlaybackControllerState
import com.cangkir.bitperfectplayer.audio.PlaybackTrack
import com.cangkir.bitperfectplayer.audio.ReplayGainMode
import com.cangkir.bitperfectplayer.library.AlbumArtworkLoader
import com.cangkir.bitperfectplayer.library.LibrarySnapshot
import com.cangkir.bitperfectplayer.library.PlaylistsUiSnapshot
import com.cangkir.bitperfectplayer.library.ResolvedPlaylist
import com.cangkir.bitperfectplayer.lyrics.TrackLyricsState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
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
 * Shared presentation ViewModel connecting Compose UI screens to [AppContainer] repositories and
 * [com.cangkir.bitperfectplayer.audio.PlaybackController].
 */
class MainViewModel(
    private val container: AppContainer,
) : ViewModel() {

    val libraryState: StateFlow<LibrarySnapshot> = container.libraryRepository.librarySnapshot
    val playlistsState: StateFlow<PlaylistsUiSnapshot> = container.playlistRepository.playlistsSnapshot
    val playbackState: StateFlow<PlaybackControllerState> = container.playbackController.state
    val playbackPositionMs: StateFlow<Long> = container.playbackController.playbackPositionMs
    val albumArtworkLoader: AlbumArtworkLoader = container.albumArtworkLoader

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

    private val _lyricsUiState = MutableStateFlow(LyricsUiState())
    val lyricsUiState: StateFlow<LyricsUiState> = _lyricsUiState.asStateFlow()

    private val _uiMessage = MutableStateFlow<String?>(null)
    val uiMessage: StateFlow<String?> = _uiMessage.asStateFlow()

    init {
        // Trigger incremental background sync while cached tracks from Room render immediately.
        refreshLibrary()

        // Restore last playback session as soon as cached or synced tracks are available.
        viewModelScope.launch {
            libraryState.collect { snapshot ->
                if (snapshot.allSongs.isNotEmpty()) {
                    container.playbackController.restoreLastSessionIfNeeded(snapshot.allSongs)
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
    }

    fun refreshLibrary() {
        container.albumArtworkLoader.clearNegativeCache()
        viewModelScope.launch {
            container.libraryRepository.refreshLibrary()
        }
    }

    fun importSafFolder(treeUri: Uri) {
        viewModelScope.launch {
            val count = container.libraryRepository.importSafFolder(treeUri)
            _uiMessage.value = if (count > 0) {
                "Imported folder ($count audio ${if (count == 1) "file" else "files"})"
            } else {
                "Folder linked (no supported FLAC/WAV/MP3/M4A/OGG files found)"
            }
        }
    }

    fun importSafAudioFile(fileUri: Uri) {
        viewModelScope.launch {
            val track = container.libraryRepository.importSafAudioFile(fileUri)
            if (track != null) {
                _uiMessage.value = "Imported ${track.title}"
            } else {
                _uiMessage.value = "Unsupported file format (MVP supports FLAC, WAV, MP3, M4A, OGG/Opus)"
            }
        }
    }

    fun removeSafFolder(treeUriString: String) {
        viewModelScope.launch {
            container.libraryRepository.removeSafFolder(treeUriString)
            _uiMessage.value = "Removed linked SAF folder"
        }
    }

    fun playTracks(tracks: List<PlaybackTrack>, startIndex: Int = 0) {
        container.playbackController.playQueue(tracks, startIndex = startIndex, autoPlay = true)
    }

    fun playTrackAtQueueIndex(index: Int) {
        container.playbackController.playTrackAt(index)
    }

    fun enqueueTrack(track: PlaybackTrack) {
        container.playbackController.enqueue(track)
        _uiMessage.value = "Added \"${track.title}\" to queue"
    }

    fun enqueueTrackNext(track: PlaybackTrack) {
        container.playbackController.enqueueNext(track)
        _uiMessage.value = "Queued \"${track.title}\" to play next"
    }

    fun removeQueueItem(index: Int) {
        container.playbackController.removeQueueItem(index)
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        container.playbackController.moveQueueItem(fromIndex, toIndex)
    }

    fun togglePlayPause() = container.playbackController.togglePlayPause()
    fun pause() = container.playbackController.pause()
    fun next() = container.playbackController.next(userInitiated = true)
    fun previous() = container.playbackController.previous()
    fun seekTo(positionMs: Long) = container.playbackController.seekTo(positionMs)
    fun toggleShuffle() = container.playbackController.toggleShuffle()
    fun cycleRepeatMode() = container.playbackController.cycleRepeatMode()
    fun setGaplessEnabled(enabled: Boolean) = container.playbackController.setGaplessEnabled(enabled)
    fun setReplayGainMode(mode: ReplayGainMode) = container.playbackController.setReplayGainMode(mode)
    fun setReplayGainPreampDb(preampDb: Float) = container.playbackController.setReplayGainPreampDb(preampDb)
    fun setReplayGainPreventClipping(preventClipping: Boolean) =
        container.playbackController.setReplayGainPreventClipping(preventClipping)

    fun requestBitPerfectMode() {
        container.playbackController.requestBitPerfectMode()
    }

    fun confirmBitPerfectWarning() {
        container.playbackController.confirmBitPerfectWarning()
    }

    fun dismissSafetyWarning() {
        container.playbackController.dismissSafetyWarning()
    }

    fun switchToSafeMode() {
        container.playbackController.switchToSafeMode()
        _uiMessage.value = "Switched to Safe Mode (System Volume Active)"
    }

    fun clearAutoPauseNotice() {
        container.playbackController.clearAutoPauseNotice()
    }

    fun toggleFavorite(track: PlaybackTrack) {
        viewModelScope.launch {
            val result = container.libraryRepository.toggleFavorite(track)
            result.onFailure {
                _uiMessage.value = "Unable to update favorite status"
            }
        }
    }

    fun createPlaylist(name: String) {
        viewModelScope.launch {
            val clean = name.trim()
            container.playlistRepository.createPlaylist(clean)
                .onSuccess {
                    _uiMessage.value = "Created playlist \"$clean\""
                }
                .onFailure { err ->
                    _uiMessage.value = err.message ?: "Unable to create playlist"
                }
        }
    }

    fun createPlaylistAndAddTrack(name: String, track: PlaybackTrack) {
        viewModelScope.launch {
            val clean = name.trim()
            container.playlistRepository.createPlaylist(clean)
                .onSuccess { newId ->
                    container.playlistRepository.addTracksToPlaylist(newId, listOf(track))
                        .onSuccess {
                            _uiMessage.value = "Added \"${track.title}\" to \"$clean\""
                        }
                        .onFailure { err ->
                            _uiMessage.value = err.message ?: "Created \"$clean\" but failed to add track"
                        }
                }
                .onFailure { err ->
                    _uiMessage.value = err.message ?: "Unable to create playlist"
                }
        }
    }

    fun renamePlaylist(playlistId: Long, newName: String) {
        viewModelScope.launch {
            val clean = newName.trim()
            container.playlistRepository.renamePlaylist(playlistId, clean)
                .onSuccess {
                    _uiMessage.value = "Renamed playlist to \"$clean\""
                }
                .onFailure { err ->
                    _uiMessage.value = err.message ?: "Unable to rename playlist"
                }
        }
    }

    fun deletePlaylist(playlistId: Long, playlistName: String) {
        viewModelScope.launch {
            container.playlistRepository.deletePlaylist(playlistId)
                .onSuccess {
                    _uiMessage.value = "Deleted playlist \"$playlistName\""
                }
                .onFailure { err ->
                    _uiMessage.value = err.message ?: "Unable to delete playlist"
                }
        }
    }

    fun addTrackToPlaylist(playlistId: Long, playlistName: String, track: PlaybackTrack) {
        viewModelScope.launch {
            container.playlistRepository.addTracksToPlaylist(playlistId, listOf(track))
                .onSuccess {
                    _uiMessage.value = "Added \"${track.title}\" to \"$playlistName\""
                }
                .onFailure { err ->
                    _uiMessage.value = err.message ?: "Unable to add track to playlist"
                }
        }
    }

    fun removePlaylistEntry(playlistId: Long, entryId: Long) {
        viewModelScope.launch {
            container.playlistRepository.removeEntryFromPlaylist(playlistId, entryId)
                .onFailure { err ->
                    _uiMessage.value = err.message ?: "Unable to remove track from playlist"
                }
        }
    }

    fun movePlaylistEntry(playlistId: Long, fromIndex: Int, toIndex: Int) {
        viewModelScope.launch {
            container.playlistRepository.moveEntryInPlaylist(playlistId, fromIndex, toIndex)
                .onFailure { err ->
                    _uiMessage.value = err.message ?: "Unable to reorder playlist"
                }
        }
    }

    fun exportPlaylistToM3u8(playlist: ResolvedPlaylist, destinationUri: Uri) {
        viewModelScope.launch {
            container.playlistRepository.exportPlaylistToM3u8(playlist, destinationUri)
                .onSuccess { exportedCount ->
                    _uiMessage.value = "Exported \"${playlist.name}\" ($exportedCount ${if (exportedCount == 1) "track" else "tracks"})"
                }
                .onFailure { err ->
                    _uiMessage.value = err.message ?: "Unable to export .m3u8 playlist"
                }
        }
    }

    fun importPlaylistFromM3u8(sourceUri: Uri) {
        viewModelScope.launch {
            val allTracks = libraryState.value.allSongs
            container.playlistRepository.importPlaylistFromM3u8(sourceUri, allTracks)
                .onSuccess { summary ->
                    _uiMessage.value = if (summary.unmatchedCount > 0) {
                        "Imported \"${summary.playlistName}\": ${summary.matchedCount} matched, ${summary.unmatchedCount} missing"
                    } else {
                        "Imported \"${summary.playlistName}\" (${summary.matchedCount} ${if (summary.matchedCount == 1) "track" else "tracks"})"
                    }
                }
                .onFailure { err ->
                    _uiMessage.value = err.message ?: "Unable to import .m3u8 playlist"
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
                _uiMessage.value = state.statusMessage ?: "Imported .lrc lyrics"
            }.onFailure { err ->
                _lyricsUiState.update {
                    it.copy(isLoading = false, errorMessage = err.message ?: "Failed to import .lrc")
                }
                _uiMessage.value = err.message ?: "Failed to import .lrc file"
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
                _uiMessage.value = state.statusMessage ?: "Lyrics saved locally"
            }.onFailure { err ->
                _lyricsUiState.update {
                    it.copy(isLoading = false, errorMessage = err.message ?: "Failed to save lyrics")
                }
                _uiMessage.value = err.message ?: "Failed to save lyrics"
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
                _uiMessage.value = "Lyrics deleted"
            }.onFailure { err ->
                _lyricsUiState.update {
                    it.copy(isLoading = false, errorMessage = err.message ?: "Failed to delete lyrics")
                }
            }
        }
    }

    fun clearUiMessage() {
        _uiMessage.value = null
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
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return MainViewModel(container) as T
                }
            }
    }
}
