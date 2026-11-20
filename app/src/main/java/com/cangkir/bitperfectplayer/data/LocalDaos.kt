package com.cangkir.bitperfectplayer.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

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
