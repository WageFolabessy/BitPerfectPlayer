package com.cangkir.bitperfectplayer.library

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.net.toUri
import com.cangkir.bitperfectplayer.audio.AudioFormatClassifier
import com.cangkir.bitperfectplayer.audio.PlaybackTrack
import com.cangkir.bitperfectplayer.data.PersistenceSanitizer
import com.cangkir.bitperfectplayer.data.PlaylistDao
import com.cangkir.bitperfectplayer.data.PlaylistEntity
import com.cangkir.bitperfectplayer.data.PlaylistTrackEntity
import com.cangkir.bitperfectplayer.data.TrackStatsDao
import com.cangkir.bitperfectplayer.lyrics.AndroidContentFingerprintProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter

data class ResolvedPlaylistEntry(
    val entryId: Long,
    val playlistId: Long,
    val positionIndex: Int,
    val track: PlaybackTrack,
)

data class ResolvedPlaylist(
    val id: Long,
    val name: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val entries: List<ResolvedPlaylistEntry>,
) {
    val tracks: List<PlaybackTrack> = entries.map { it.track }
    val trackCount: Int = entries.size
}

data class MostPlayedTrackItem(
    val track: PlaybackTrack,
    val playCount: Int,
    val totalPlayTimeMs: Long,
    val lastPlayedAtEpochMs: Long,
)

data class PlaylistsUiSnapshot(
    val playlists: List<ResolvedPlaylist> = emptyList(),
    val mostPlayed: List<MostPlayedTrackItem> = emptyList(),
    val totalPlays: Int = 0,
)

data class M3u8ImportSummary(
    val playlistId: Long,
    val playlistName: String,
    val matchedCount: Int,
    val unmatchedCount: Int,
)

/**
 * Local-only playlists and listening statistics repository backed by Room (`playlists`,
 * `playlist_tracks`, `track_stats`) with 100% offline `.m3u8` SAF import/export.
 */
class PlaylistRepository(
    context: Context,
    private val playlistDao: PlaylistDao,
    private val trackStatsDao: TrackStatsDao,
    private val libraryRepository: LocalLibraryRepository,
    private val fingerprintProvider: AndroidContentFingerprintProvider,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val playlistsSnapshot: StateFlow<PlaylistsUiSnapshot> = combine(
        playlistDao.observeAllPlaylists(),
        playlistDao.observeAllPlaylistTracks(),
        trackStatsDao.observeMostPlayed(limit = 50),
        trackStatsDao.observeTotalPlayCount(),
        libraryRepository.librarySnapshot,
    ) { playlistEntities, allEntries, mostPlayedEntities, totalPlays, library ->
        val tracksByFp = HashMap<String, PlaybackTrack>(library.allSongs.size)
        val tracksByUri = HashMap<String, PlaybackTrack>(library.allSongs.size)
        val tracksByFileName = HashMap<String, PlaybackTrack>(library.allSongs.size)

        for (track in library.allSongs) {
            track.contentFingerprint?.lowercase()?.takeIf { it.isNotEmpty() }?.let { fp ->
                tracksByFp.putIfAbsent(fp, track)
            }
            tracksByUri.putIfAbsent(track.uriString, track)
            tracksByFileName.putIfAbsent(track.fileName.lowercase(), track)
        }

        val entriesByPlaylist = allEntries.groupBy { it.playlistId }
        val resolvedPlaylists = playlistEntities.map { pl ->
            val rawEntries = entriesByPlaylist[pl.id].orEmpty()
            val resolvedEntries = rawEntries.mapNotNull { entry ->
                val resolvedTrack = entry.contentFingerprint?.lowercase()?.let { tracksByFp[it] }
                    ?: tracksByUri[entry.trackUri]
                    ?: tracksByFileName[entry.fileName.lowercase()]
                    ?: return@mapNotNull null
                ResolvedPlaylistEntry(
                    entryId = entry.id,
                    playlistId = entry.playlistId,
                    positionIndex = entry.positionIndex,
                    track = resolvedTrack,
                )
            }
            ResolvedPlaylist(
                id = pl.id,
                name = pl.name,
                createdAtEpochMs = pl.createdAtEpochMs,
                updatedAtEpochMs = pl.updatedAtEpochMs,
                entries = resolvedEntries,
            )
        }

        val resolvedMostPlayed = mostPlayedEntities.mapNotNull { stat ->
            val resolvedTrack = tracksByFp[stat.contentFingerprint.lowercase()]
                ?: tracksByUri[stat.trackUri]
                ?: tracksByFileName[stat.fileName.lowercase()]
                ?: return@mapNotNull null
            MostPlayedTrackItem(
                track = resolvedTrack,
                playCount = stat.playCount,
                totalPlayTimeMs = stat.totalPlayTimeMs,
                lastPlayedAtEpochMs = stat.lastPlayedAtEpochMs,
            )
        }

        PlaylistsUiSnapshot(
            playlists = resolvedPlaylists,
            mostPlayed = resolvedMostPlayed,
            totalPlays = totalPlays.coerceAtLeast(0),
        )
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = PlaylistsUiSnapshot(),
    )

    suspend fun createPlaylist(rawName: String): Result<Long> = withContext(Dispatchers.IO) {
        val cleanName = rawName.trim()
        if (cleanName.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Playlist name cannot be empty"))
        }
        runCatching {
            val now = System.currentTimeMillis()
            playlistDao.insertPlaylist(
                PlaylistEntity(
                    name = cleanName,
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now,
                ),
            )
        }
    }

    suspend fun renamePlaylist(playlistId: Long, newRawName: String): Result<Unit> = withContext(Dispatchers.IO) {
        val cleanName = newRawName.trim()
        if (cleanName.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Playlist name cannot be empty"))
        }
        runCatching {
            playlistDao.renamePlaylist(
                playlistId = playlistId,
                name = cleanName,
                updatedAtEpochMs = System.currentTimeMillis(),
            )
            Unit
        }
    }

    suspend fun deletePlaylist(playlistId: Long): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            playlistDao.deletePlaylistById(playlistId)
            Unit
        }
    }

    suspend fun addTracksToPlaylist(
        playlistId: Long,
        tracks: List<PlaybackTrack>,
    ): Result<Int> = withContext(Dispatchers.IO) {
        if (tracks.isEmpty()) return@withContext Result.success(0)
        runCatching {
            val now = System.currentTimeMillis()
            var nextPos = (playlistDao.getMaxPositionIndex(playlistId) ?: -1) + 1
            val entities = tracks.map { track ->
                val fp = track.contentFingerprint?.takeIf { it.isNotBlank() }
                    ?: fingerprintProvider.computeFingerprint(track.uriString.toUri()).getOrNull()
                PlaylistTrackEntity(
                    playlistId = playlistId,
                    contentFingerprint = fp?.let { PersistenceSanitizer.normalizeFingerprint(it) },
                    trackUri = track.uriString.trim(),
                    fileName = PersistenceSanitizer.sanitizeDisplayText(track.fileName, "Unknown"),
                    positionIndex = nextPos++,
                    addedAtEpochMs = now,
                )
            }
            playlistDao.insertPlaylistTracks(entities)
            playlistDao.touchPlaylist(playlistId = playlistId, updatedAtEpochMs = now)
            entities.size
        }
    }

    suspend fun removeEntryFromPlaylist(playlistId: Long, entryId: Long): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            playlistDao.removeEntryAndCompact(
                playlistId = playlistId,
                entryId = entryId,
                updatedAtEpochMs = System.currentTimeMillis(),
            )
        }
    }

    suspend fun moveEntryInPlaylist(
        playlistId: Long,
        fromIndex: Int,
        toIndex: Int,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val current = playlistDao.getTracksForPlaylist(playlistId)
            val reorderedIds = M3u8PlaylistCodec.reorderList(
                items = current.map { it.id },
                fromIndex = fromIndex,
                toIndex = toIndex,
            )
            if (reorderedIds != current.map { it.id }) {
                playlistDao.replacePlaylistTrackOrder(
                    playlistId = playlistId,
                    orderedEntryIds = reorderedIds,
                    updatedAtEpochMs = System.currentTimeMillis(),
                )
            }
        }
    }

    suspend fun exportPlaylistToM3u8(
        playlist: ResolvedPlaylist,
        destinationUri: Uri,
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val content = M3u8PlaylistCodec.exportToM3u8(
                playlistName = playlist.name,
                tracks = playlist.tracks,
            )
            val stream = appContext.contentResolver.openOutputStream(destinationUri, "wt")
                ?: throw IllegalStateException("Unable to open destination file for writing")
            stream.use { out ->
                OutputStreamWriter(out, Charsets.UTF_8).use { writer ->
                    writer.write(content)
                    writer.flush()
                }
            }
            playlist.tracks.size
        }
    }

    suspend fun importPlaylistFromM3u8(
        sourceUri: Uri,
        libraryTracks: List<PlaybackTrack>,
    ): Result<M3u8ImportSummary> = withContext(Dispatchers.IO) {
        runCatching {
            val rawText = appContext.contentResolver.openInputStream(sourceUri)?.use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            } ?: throw IllegalStateException("Unable to read selected .m3u8 file")

            val parsed = M3u8PlaylistCodec.parseM3u8(rawText)
            val displayName = queryDisplayName(sourceUri)
            val fallbackName = displayName?.let { AudioFormatClassifier.baseNameWithoutExtension(it) }
                ?.takeIf { it.isNotBlank() }
                ?: "Imported Playlist"
            val playlistName = parsed.titleHint?.takeIf { it.isNotBlank() } ?: fallbackName

            val matchResult = M3u8PlaylistCodec.matchEntriesToLibrary(
                entries = parsed.entries,
                libraryTracks = libraryTracks,
            )
            val now = System.currentTimeMillis()
            val playlistId = playlistDao.insertPlaylist(
                PlaylistEntity(
                    name = playlistName,
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now,
                ),
            )
            if (matchResult.matchedTracks.isNotEmpty()) {
                val entities = matchResult.matchedTracks.mapIndexed { index, track ->
                    PlaylistTrackEntity(
                        playlistId = playlistId,
                        contentFingerprint = track.contentFingerprint?.let {
                            PersistenceSanitizer.normalizeFingerprint(it)
                        },
                        trackUri = track.uriString.trim(),
                        fileName = PersistenceSanitizer.sanitizeDisplayText(track.fileName, "Unknown"),
                        positionIndex = index,
                        addedAtEpochMs = now,
                    )
                }
                playlistDao.insertPlaylistTracks(entities)
            }

            M3u8ImportSummary(
                playlistId = playlistId,
                playlistName = playlistName,
                matchedCount = matchResult.matchedTracks.size,
                unmatchedCount = matchResult.unmatchedLocations.size,
            )
        }
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        appContext.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx) else null
            } else {
                null
            }
        }
    }.getOrNull()
}
