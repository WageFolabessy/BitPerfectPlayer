package com.cangkir.bitperfectplayer.ui

import android.net.Uri
import com.cangkir.bitperfectplayer.audio.PlaybackTrack
import com.cangkir.bitperfectplayer.data.PlaylistTrackEntity
import com.cangkir.bitperfectplayer.library.AddTrackToPlaylistOutcome
import com.cangkir.bitperfectplayer.library.PlaylistStore
import com.cangkir.bitperfectplayer.library.PlaylistsUiSnapshot
import com.cangkir.bitperfectplayer.library.ResolvedPlaylist
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Coordinates playlist CRUD, duplicate-safe membership insertion, entry removal with undo,
 * drag-reorder commits, and `.m3u8` import/export against [PlaylistStore].
 */
class PlaylistUiCoordinator(
    private val scope: CoroutineScope,
    private val playlistStore: PlaylistStore,
    private val messageEmitter: UiMessageEmitter,
) {
    val playlistsState: StateFlow<PlaylistsUiSnapshot> = playlistStore.playlistsSnapshot

    fun createPlaylist(name: String) {
        scope.launch {
            val clean = name.trim()
            playlistStore.createPlaylist(clean)
                .onSuccess {
                    messageEmitter.emitSnackbar("Created playlist \"$clean\"")
                }
                .onFailure { err ->
                    messageEmitter.emitSnackbar(err.message ?: "Unable to create playlist")
                }
        }
    }

    fun createPlaylistAndAddTrack(name: String, track: PlaybackTrack) {
        scope.launch {
            val clean = name.trim()
            playlistStore.createPlaylist(clean)
                .onSuccess { newId ->
                    playlistStore.addTracksToPlaylist(newId, listOf(track))
                        .onSuccess {
                            messageEmitter.emitSnackbar("Added \"${track.title}\" to \"$clean\"")
                        }
                        .onFailure { err ->
                            messageEmitter.emitSnackbar(err.message ?: "Created \"$clean\" but failed to add track")
                        }
                }
                .onFailure { err ->
                    messageEmitter.emitSnackbar(err.message ?: "Unable to create playlist")
                }
        }
    }

    fun addTrackToPlaylist(playlistId: Long, playlistName: String, track: PlaybackTrack) {
        scope.launch {
            playlistStore.addTrackToPlaylistUnique(playlistId, track)
                .onSuccess { outcome ->
                    val message = when (outcome) {
                        AddTrackToPlaylistOutcome.ADDED -> "Added \"${track.title}\" to \"$playlistName\""
                        AddTrackToPlaylistOutcome.ALREADY_EXISTS -> "\"${track.title}\" is already in \"$playlistName\""
                    }
                    messageEmitter.emitSnackbar(message)
                }
                .onFailure { err ->
                    messageEmitter.emitSnackbar(err.message ?: "Unable to add track to playlist")
                }
        }
    }

    fun renamePlaylist(playlistId: Long, newName: String) {
        scope.launch {
            val clean = newName.trim()
            playlistStore.renamePlaylist(playlistId, clean)
                .onSuccess {
                    messageEmitter.emitSnackbar("Renamed playlist to \"$clean\"")
                }
                .onFailure { err ->
                    messageEmitter.emitSnackbar(err.message ?: "Unable to rename playlist")
                }
        }
    }

    fun deletePlaylist(playlistId: Long, playlistName: String) {
        scope.launch {
            playlistStore.deletePlaylist(playlistId)
                .onSuccess {
                    messageEmitter.emitSnackbar("Deleted playlist \"$playlistName\"")
                }
                .onFailure { err ->
                    messageEmitter.emitSnackbar(err.message ?: "Unable to delete playlist")
                }
        }
    }

    fun removePlaylistEntry(playlistId: Long, entryId: Long, playlistName: String = "", trackTitle: String = "") {
        scope.launch {
            val resolvedPlaylistName = playlistName.ifBlank {
                playlistsState.value.playlists.firstOrNull { it.id == playlistId }?.name ?: "playlist"
            }
            playlistStore.removeEntryFromPlaylist(playlistId, entryId)
                .onSuccess { removedEntity ->
                    val displayTitle = trackTitle.ifBlank { removedEntity?.fileName ?: "track" }
                    messageEmitter.emitSnackbar(
                        message = "Removed \"$displayTitle\" from \"$resolvedPlaylistName\"",
                        actionLabel = if (removedEntity != null) "Undo" else null,
                        onAction = removedEntity?.let { entity -> { restoreRemovedPlaylistEntry(entity) } },
                    )
                }
                .onFailure { err ->
                    messageEmitter.emitSnackbar(err.message ?: "Unable to remove track from playlist")
                }
        }
    }

    fun commitPlaylistOrder(playlistId: Long, orderedEntryIds: List<Long>) {
        scope.launch {
            playlistStore.commitPlaylistOrder(playlistId, orderedEntryIds)
                .onFailure { err ->
                    messageEmitter.emitSnackbar(err.message ?: "Unable to reorder playlist")
                }
        }
    }

    fun exportPlaylistToM3u8(playlist: ResolvedPlaylist, destinationUri: Uri) {
        scope.launch {
            playlistStore.exportPlaylistToM3u8(playlist, destinationUri)
                .onSuccess { exportedCount ->
                    val trackWord = if (exportedCount == 1) "track" else "tracks"
                    messageEmitter.emitSnackbar("Exported \"${playlist.name}\" ($exportedCount $trackWord)")
                }
                .onFailure { err ->
                    messageEmitter.emitSnackbar(err.message ?: "Unable to export .m3u8 playlist")
                }
        }
    }

    fun importPlaylistFromM3u8(sourceUri: Uri, libraryTracks: List<PlaybackTrack>) {
        scope.launch {
            playlistStore.importPlaylistFromM3u8(sourceUri, libraryTracks)
                .onSuccess { summary ->
                    val message = if (summary.unmatchedCount > 0) {
                        "Imported \"${summary.playlistName}\": " +
                            "${summary.matchedCount} matched, ${summary.unmatchedCount} missing"
                    } else {
                        val trackWord = if (summary.matchedCount == 1) "track" else "tracks"
                        "Imported \"${summary.playlistName}\" (${summary.matchedCount} $trackWord)"
                    }
                    messageEmitter.emitSnackbar(message)
                }
                .onFailure { err ->
                    messageEmitter.emitSnackbar(err.message ?: "Unable to import .m3u8 playlist")
                }
        }
    }

    private fun restoreRemovedPlaylistEntry(entity: PlaylistTrackEntity) {
        scope.launch {
            playlistStore.restoreRemovedEntry(entity)
                .onFailure { err ->
                    messageEmitter.emitSnackbar(err.message ?: "Unable to restore track to playlist")
                }
        }
    }
}
