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
import com.cangkir.bitperfectplayer.data.UserPreferencesRepository
import com.cangkir.bitperfectplayer.lyrics.AndroidContentFingerprintProvider
import java.io.OutputStreamWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

data class ResolvedPlaylistEntry(val entryId: Long, val playlistId: Long, val positionIndex: Int, val track: PlaybackTrack)

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

data class MostPlayedTrackItem(val track: PlaybackTrack, val playCount: Int, val totalPlayTimeMs: Long, val lastPlayedAtEpochMs: Long)

data class PlaylistsUiSnapshot(
    val playlists: List<ResolvedPlaylist> = emptyList(),
    val mostPlayed: List<MostPlayedTrackItem> = emptyList(),
    val totalPlays: Int = 0,
    val playlistDetailSort: TrackSortSpec = TrackSortScope.PLAYLIST_DETAIL.defaultSpec,
    val isLoaded: Boolean = false,
)

data class M3u8ImportSummary(val playlistId: Long, val playlistName: String, val matchedCount: Int, val unmatchedCount: Int)

/**
 * Local-only playlists and listening statistics repository backed by Room (`playlists`,
 * `playlist_tracks`, `track_stats`) with 100% offline `.m3u8` SAF import/export.
 */
class PlaylistRepository(
    context: Context,
    private val playlistDao: PlaylistDao,
    private val trackStatsDao: TrackStatsDao,
    private val libraryRepository: LocalLibraryRepository,
    private val preferencesRepository: UserPreferencesRepository,
    private val fingerprintProvider: AndroidContentFingerprintProvider,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val playlistsSnapshot: StateFlow<PlaylistsUiSnapshot> = combine(
        playlistDao.observeAllPlaylists(),
        playlistDao.observeAllPlaylistTracks(),
        trackStatsDao.observeAllStats(),
        trackStatsDao.observeTotalPlayCount(),
        combine(libraryRepository.librarySnapshot, preferencesRepository.preferencesFlow) { lib, prefs ->
            lib to prefs.sortPreferences.playlistDetail
        },
    ) { playlistEntities, allEntries, allStats, totalPlays, (library, playlistSortSpec) ->
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

        val playCountsByKey = HashMap<String, Int>(allStats.size * 2).apply {
            for (stat in allStats) {
                if (stat.playCount > 0) {
                    put(stat.contentFingerprint.lowercase(), stat.playCount)
                    if (stat.trackUri.isNotBlank()) {
                        putIfAbsent(stat.trackUri, stat.playCount)
                    }
                }
            }
        }

        val entriesByPlaylist = allEntries.groupBy { it.playlistId }
        val resolvedPlaylists = playlistEntities.map { pl ->
            val rawEntries = entriesByPlaylist[pl.id].orEmpty()
            val customOrdered = rawEntries.mapNotNull { entry ->
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
            val displayedEntries = if (playlistSortSpec.option == TrackSortOption.CUSTOM_ORDER) {
                if (playlistSortSpec.direction == SortDirection.ASC) customOrdered else customOrdered.asReversed()
            } else {
                val sortedTracks = LibrarySortPolicy.sortTracks(
                    tracks = customOrdered.map { it.track },
                    spec = playlistSortSpec,
                    playCountsByKey = playCountsByKey,
                )
                val entriesByTrackUri = customOrdered.groupBy { it.track.uriString }
                    .mapValues { (_, list) -> ArrayDeque(list) }
                sortedTracks.mapNotNull { track ->
                    entriesByTrackUri[track.uriString]?.removeFirstOrNull()
                }
            }
            ResolvedPlaylist(
                id = pl.id,
                name = pl.name,
                createdAtEpochMs = pl.createdAtEpochMs,
                updatedAtEpochMs = pl.updatedAtEpochMs,
                entries = displayedEntries,
            )
        }

        val resolvedMostPlayed = allStats
            .asSequence()
            .filter { it.playCount > 0 }
            .take(50)
            .mapNotNull { stat ->
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
            .toList()

        PlaylistsUiSnapshot(
            playlists = resolvedPlaylists,
            mostPlayed = resolvedMostPlayed,
            totalPlays = totalPlays.coerceAtLeast(0),
            playlistDetailSort = playlistSortSpec,
            isLoaded = true,
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(
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

    suspend fun addTracksToPlaylist(playlistId: Long, tracks: List<PlaybackTrack>): Result<Int> = withContext(Dispatchers.IO) {
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

    suspend fun moveEntryInPlaylist(playlistId: Long, fromIndex: Int, toIndex: Int): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val currentIds = playlistDao.getTracksForPlaylist(playlistId).map { it.id }
            val resolvedIds = playlistsSnapshot.value.playlists
                .firstOrNull { it.id == playlistId }
                ?.entries
                ?.map { it.entryId }
            val isPartialVisibleSubset = resolvedIds != null && resolvedIds.size in 2 until currentIds.size
            val inResolvedBounds = resolvedIds != null && fromIndex in resolvedIds.indices && toIndex in resolvedIds.indices
            val reorderedIds = if (resolvedIds != null && isPartialVisibleSubset && inResolvedBounds) {
                // Preserve any temporarily unresolved rows in place while reordering visible entries.
                val reorderedVisible = ArrayDeque(
                    M3u8PlaylistCodec.reorderList(
                        items = resolvedIds,
                        fromIndex = fromIndex,
                        toIndex = toIndex,
                    ),
                )
                val visibleSet = resolvedIds.toHashSet()
                currentIds.map { id ->
                    if (id in visibleSet) reorderedVisible.removeFirst() else id
                }
            } else {
                M3u8PlaylistCodec.reorderList(
                    items = currentIds,
                    fromIndex = fromIndex,
                    toIndex = toIndex,
                )
            }
            if (reorderedIds != currentIds) {
                playlistDao.replacePlaylistTrackOrder(
                    playlistId = playlistId,
                    orderedEntryIds = reorderedIds,
                    updatedAtEpochMs = System.currentTimeMillis(),
                )
            }
        }
    }

    suspend fun exportPlaylistToM3u8(playlist: ResolvedPlaylist, destinationUri: Uri): Result<Int> = withContext(Dispatchers.IO) {
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

    suspend fun importPlaylistFromM3u8(sourceUri: Uri, libraryTracks: List<PlaybackTrack>): Result<M3u8ImportSummary> =
        withContext(Dispatchers.IO) {
            runCatching {
                val rawText = appContext.contentResolver.openInputStream(sourceUri)?.use { input ->
                    val buffer = ByteArray(8192)
                    val out = java.io.ByteArrayOutputStream()
                    var total = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_M3U8_FILE_BYTES) {
                            throw IllegalArgumentException(".m3u8 file exceeds 4 MB safety limit")
                        }
                        out.write(buffer, 0, read)
                    }
                    out.toString(Charsets.UTF_8.name())
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

    companion object {
        private const val MAX_M3U8_FILE_BYTES = 4 * 1024 * 1024 // 4 MB OOM safety cap
    }
}
