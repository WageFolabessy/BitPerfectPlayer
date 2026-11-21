package com.cangkir.bitperfectplayer.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

data class TrackPositionBackfill(val uriString: String, val discNumber: Int, val trackNumber: Int)

@Dao
interface CachedTrackDao {
    @Query("SELECT * FROM cached_tracks ORDER BY title COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<CachedTrackEntity>>

    @Query("SELECT * FROM cached_tracks")
    suspend fun getAll(): List<CachedTrackEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<CachedTrackEntity>)

    @Query("UPDATE cached_tracks SET content_fingerprint = :fingerprint WHERE uri_string = :uriString")
    suspend fun updateFingerprint(uriString: String, fingerprint: String)

    @Query("UPDATE cached_tracks SET disc_number = :discNumber, track_number = :trackNumber WHERE uri_string = :uriString")
    suspend fun updateTrackPosition(uriString: String, discNumber: Int, trackNumber: Int)

    @Transaction
    suspend fun backfillTrackPositions(updates: List<TrackPositionBackfill>) {
        for (item in updates) {
            updateTrackPosition(
                uriString = item.uriString,
                discNumber = item.discNumber,
                trackNumber = item.trackNumber,
            )
        }
    }

    @Query("DELETE FROM cached_tracks WHERE uri_string IN (:uris)")
    suspend fun deleteByUris(uris: List<String>): Int
}

@Dao
interface LyricsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LyricsEntity)

    @Query("SELECT * FROM lyrics WHERE content_fingerprint = :fingerprint LIMIT 1")
    suspend fun getByFingerprint(fingerprint: String): LyricsEntity?

    @Query("SELECT * FROM lyrics WHERE content_fingerprint = :fingerprint LIMIT 1")
    fun observeByFingerprint(fingerprint: String): Flow<LyricsEntity?>

    @Query("DELETE FROM lyrics WHERE content_fingerprint = :fingerprint")
    suspend fun deleteByFingerprint(fingerprint: String): Int

    @Query("SELECT * FROM lyrics ORDER BY updated_at_epoch_ms DESC")
    suspend fun getAll(): List<LyricsEntity>

    @Query("SELECT COUNT(*) FROM lyrics")
    suspend fun countAll(): Int
}

@Dao
interface TrackHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TrackHistoryEntity)

    @Query("SELECT * FROM track_history ORDER BY played_at_epoch_ms DESC LIMIT :limit")
    fun observeRecentlyPlayed(limit: Int = 50): Flow<List<TrackHistoryEntity>>

    @Query("SELECT * FROM track_history ORDER BY played_at_epoch_ms DESC LIMIT :limit")
    suspend fun getRecentlyPlayed(limit: Int = 50): List<TrackHistoryEntity>

    @Query("DELETE FROM track_history WHERE content_fingerprint = :fingerprint")
    suspend fun deleteByFingerprint(fingerprint: String): Int

    @Query("DELETE FROM track_history")
    suspend fun clearAll()
}

@Dao
interface FavoriteTrackDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FavoriteTrackEntity)

    @Query("DELETE FROM favorite_tracks WHERE content_fingerprint = :fingerprint")
    suspend fun deleteByFingerprint(fingerprint: String): Int

    @Query("DELETE FROM favorite_tracks WHERE track_uri = :trackUri")
    suspend fun deleteByTrackUri(trackUri: String): Int

    @Query("SELECT EXISTS(SELECT 1 FROM favorite_tracks WHERE content_fingerprint = :fingerprint)")
    suspend fun isFavorite(fingerprint: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM favorite_tracks WHERE content_fingerprint = :fingerprint)")
    fun observeIsFavorite(fingerprint: String): Flow<Boolean>

    @Query("SELECT * FROM favorite_tracks ORDER BY added_at_epoch_ms DESC")
    fun observeAllFavorites(): Flow<List<FavoriteTrackEntity>>

    @Query("SELECT content_fingerprint FROM favorite_tracks")
    fun observeFavoriteFingerprints(): Flow<List<String>>

    @Query("SELECT * FROM favorite_tracks ORDER BY added_at_epoch_ms DESC")
    suspend fun getAllFavorites(): List<FavoriteTrackEntity>
}

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY updated_at_epoch_ms DESC, id DESC")
    fun observeAllPlaylists(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists ORDER BY updated_at_epoch_ms DESC, id DESC")
    suspend fun getAllPlaylists(): List<PlaylistEntity>

    @Query("SELECT * FROM playlists WHERE id = :playlistId LIMIT 1")
    suspend fun getPlaylistById(playlistId: Long): PlaylistEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPlaylist(entity: PlaylistEntity): Long

    @Query("UPDATE playlists SET name = :name, updated_at_epoch_ms = :updatedAtEpochMs WHERE id = :playlistId")
    suspend fun renamePlaylist(playlistId: Long, name: String, updatedAtEpochMs: Long): Int

    @Query("UPDATE playlists SET updated_at_epoch_ms = :updatedAtEpochMs WHERE id = :playlistId")
    suspend fun touchPlaylist(playlistId: Long, updatedAtEpochMs: Long)

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deletePlaylistById(playlistId: Long): Int

    @Query("SELECT * FROM playlist_tracks ORDER BY playlist_id ASC, position_index ASC, id ASC")
    fun observeAllPlaylistTracks(): Flow<List<PlaylistTrackEntity>>

    @Query("SELECT * FROM playlist_tracks ORDER BY playlist_id ASC, position_index ASC, id ASC")
    suspend fun getAllPlaylistTracks(): List<PlaylistTrackEntity>

    @Query("SELECT * FROM playlist_tracks WHERE playlist_id = :playlistId ORDER BY position_index ASC, id ASC")
    fun observeTracksForPlaylist(playlistId: Long): Flow<List<PlaylistTrackEntity>>

    @Query("SELECT * FROM playlist_tracks WHERE playlist_id = :playlistId ORDER BY position_index ASC, id ASC")
    suspend fun getTracksForPlaylist(playlistId: Long): List<PlaylistTrackEntity>

    @Query("SELECT MAX(position_index) FROM playlist_tracks WHERE playlist_id = :playlistId")
    suspend fun getMaxPositionIndex(playlistId: Long): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistTrack(entity: PlaylistTrackEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistTracks(entities: List<PlaylistTrackEntity>)

    @Query("UPDATE playlist_tracks SET position_index = :positionIndex WHERE id = :entryId")
    suspend fun updatePlaylistTrackPosition(entryId: Long, positionIndex: Int)

    @Query(
        "UPDATE playlist_tracks SET content_fingerprint = :fingerprint " +
            "WHERE track_uri = :trackUri AND (content_fingerprint IS NULL OR content_fingerprint = '')",
    )
    suspend fun backfillFingerprintByUri(trackUri: String, fingerprint: String)

    @Query("DELETE FROM playlist_tracks WHERE id = :entryId")
    suspend fun deletePlaylistTrackById(entryId: Long): Int

    @Transaction
    suspend fun replacePlaylistTrackOrder(playlistId: Long, orderedEntryIds: List<Long>, updatedAtEpochMs: Long) {
        orderedEntryIds.forEachIndexed { index, entryId ->
            updatePlaylistTrackPosition(entryId = entryId, positionIndex = index)
        }
        touchPlaylist(playlistId = playlistId, updatedAtEpochMs = updatedAtEpochMs)
    }

    @Transaction
    suspend fun removeEntryAndCompact(playlistId: Long, entryId: Long, updatedAtEpochMs: Long) {
        deletePlaylistTrackById(entryId)
        val remaining = getTracksForPlaylist(playlistId)
        remaining.forEachIndexed { index, entity ->
            if (entity.positionIndex != index) {
                updatePlaylistTrackPosition(entryId = entity.id, positionIndex = index)
            }
        }
        touchPlaylist(playlistId = playlistId, updatedAtEpochMs = updatedAtEpochMs)
    }
}

@Dao
interface TrackStatsDao {
    @Query("SELECT * FROM track_stats ORDER BY play_count DESC, last_played_at_epoch_ms DESC")
    fun observeAllStats(): Flow<List<TrackStatsEntity>>

    @Query("SELECT * FROM track_stats ORDER BY play_count DESC, last_played_at_epoch_ms DESC")
    suspend fun getAllStats(): List<TrackStatsEntity>

    @Query("SELECT * FROM track_stats WHERE play_count > 0 ORDER BY play_count DESC, last_played_at_epoch_ms DESC LIMIT :limit")
    fun observeMostPlayed(limit: Int = 50): Flow<List<TrackStatsEntity>>

    @Query("SELECT COALESCE(SUM(play_count), 0) FROM track_stats")
    fun observeTotalPlayCount(): Flow<Int>

    @Query("SELECT * FROM track_stats WHERE content_fingerprint = :fingerprint LIMIT 1")
    suspend fun getByFingerprint(fingerprint: String): TrackStatsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TrackStatsEntity)

    @Transaction
    suspend fun recordListeningDelta(
        contentFingerprint: String,
        trackUri: String,
        title: String,
        artist: String,
        album: String,
        fileName: String,
        playCountDelta: Int,
        playTimeDeltaMs: Long,
        playedAtEpochMs: Long,
    ) {
        val existing = getByFingerprint(contentFingerprint)
        val nextCount = ((existing?.playCount ?: 0) + playCountDelta).coerceAtLeast(0)
        val nextTime = ((existing?.totalPlayTimeMs ?: 0L) + playTimeDeltaMs).coerceAtLeast(0L)
        val nextLastPlayed = if (playCountDelta > 0) {
            maxOf(existing?.lastPlayedAtEpochMs ?: 0L, playedAtEpochMs)
        } else {
            existing?.lastPlayedAtEpochMs ?: playedAtEpochMs
        }
        upsert(
            TrackStatsEntity(
                contentFingerprint = contentFingerprint,
                trackUri = trackUri,
                title = title,
                artist = artist,
                album = album,
                fileName = fileName,
                playCount = nextCount,
                totalPlayTimeMs = nextTime,
                lastPlayedAtEpochMs = nextLastPlayed,
            ),
        )
    }
}
