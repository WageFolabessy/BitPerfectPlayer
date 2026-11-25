package com.cangkir.bitperfectplayer.library

import android.net.Uri
import com.cangkir.bitperfectplayer.audio.PlaybackTrack
import com.cangkir.bitperfectplayer.data.PlaylistTrackEntity
import kotlinx.coroutines.flow.StateFlow

interface LibraryRepository {
    val librarySnapshot: StateFlow<LibrarySnapshot>

    suspend fun refreshLibrary(forceYearBackfill: Boolean = false)

    suspend fun importSafFolder(treeUri: Uri): Int

    suspend fun removeSafFolder(treeUriString: String)

    suspend fun toggleFavorite(track: PlaybackTrack): Result<Boolean>
}

interface PlaylistStore {
    val playlistsSnapshot: StateFlow<PlaylistsUiSnapshot>

    suspend fun createPlaylist(rawName: String): Result<Long>

    suspend fun renamePlaylist(playlistId: Long, newRawName: String): Result<Unit>

    suspend fun deletePlaylist(playlistId: Long): Result<Unit>

    suspend fun addTracksToPlaylist(playlistId: Long, tracks: List<PlaybackTrack>): Result<Int>

    suspend fun addTrackToPlaylistUnique(playlistId: Long, track: PlaybackTrack): Result<AddTrackToPlaylistOutcome>

    suspend fun removeEntryFromPlaylist(playlistId: Long, entryId: Long): Result<PlaylistTrackEntity?>

    suspend fun restoreRemovedEntry(entity: PlaylistTrackEntity): Result<Unit>

    suspend fun commitPlaylistOrder(playlistId: Long, orderedVisibleEntryIds: List<Long>): Result<Unit>

    suspend fun exportPlaylistToM3u8(playlist: ResolvedPlaylist, destinationUri: Uri): Result<Int>

    suspend fun importPlaylistFromM3u8(sourceUri: Uri, libraryTracks: List<PlaybackTrack>): Result<M3u8ImportSummary>
}

interface ArtistPhotoStorage {
    val photoRevision: StateFlow<Int>

    suspend fun saveArtistPhotoResult(artistName: String, sourceUri: Uri): Result<Unit>

    suspend fun clearArtistPhoto(artistName: String): Boolean
}

interface TrackDetailsLoader {
    suspend fun loadFullDetails(track: PlaybackTrack): TrackTechnicalInfo
}
