package com.cangkir.bitperfectplayer.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Facade over local Room DAOs exposing domain models and clean favorite/history/lyrics CRUD.
 */
class LocalPersistenceRepository(
    private val lyricsDao: LyricsDao,
    private val trackHistoryDao: TrackHistoryDao,
    private val favoriteTrackDao: FavoriteTrackDao,
) {
    constructor(database: BitPerfectDatabase) : this(
        lyricsDao = database.lyricsDao(),
        trackHistoryDao = database.trackHistoryDao(),
        favoriteTrackDao = database.favoriteTrackDao(),
    )

    suspend fun upsertLyrics(record: LyricsRecord) {
        lyricsDao.upsert(record.toEntity())
    }

    suspend fun getLyricsByFingerprint(fingerprint: String): LyricsRecord? {
        val normalized = PersistenceSanitizer.normalizeFingerprint(fingerprint)
        if (normalized.isEmpty()) return null
        return lyricsDao.getByFingerprint(normalized)?.toDomain()
    }

    fun observeLyricsByFingerprint(fingerprint: String): Flow<LyricsRecord?> {
        val normalized = PersistenceSanitizer.normalizeFingerprint(fingerprint)
        return lyricsDao.observeByFingerprint(normalized).map { it?.toDomain() }
    }

    suspend fun deleteLyricsByFingerprint(fingerprint: String): Boolean {
        val normalized = PersistenceSanitizer.normalizeFingerprint(fingerprint)
        if (normalized.isEmpty()) return false
        return lyricsDao.deleteByFingerprint(normalized) > 0
    }

    suspend fun recordTrackPlayed(record: TrackHistoryRecord) {
        if (record.contentFingerprint.isBlank()) return
        trackHistoryDao.upsert(record.toEntity())
    }

    fun observeRecentlyPlayed(limit: Int = 50): Flow<List<TrackHistoryRecord>> =
        trackHistoryDao.observeRecentlyPlayed(limit).map { list -> list.map { it.toDomain() } }

    suspend fun getRecentlyPlayed(limit: Int = 50): List<TrackHistoryRecord> =
        trackHistoryDao.getRecentlyPlayed(limit).map { it.toDomain() }

    suspend fun removeHistoryEntry(fingerprint: String) {
        val normalized = PersistenceSanitizer.normalizeFingerprint(fingerprint)
        if (normalized.isNotEmpty()) {
            trackHistoryDao.deleteByFingerprint(normalized)
        }
    }

    suspend fun toggleFavorite(record: FavoriteTrackRecord): Boolean {
        val normalized = PersistenceSanitizer.normalizeFingerprint(record.contentFingerprint)
        if (normalized.isEmpty()) return false
        return if (favoriteTrackDao.isFavorite(normalized)) {
            favoriteTrackDao.deleteByFingerprint(normalized)
            false
        } else {
            favoriteTrackDao.upsert(record.copy(contentFingerprint = normalized).toEntity())
            true
        }
    }

    suspend fun isFavorite(fingerprint: String): Boolean {
        val normalized = PersistenceSanitizer.normalizeFingerprint(fingerprint)
        if (normalized.isEmpty()) return false
        return favoriteTrackDao.isFavorite(normalized)
    }

    fun observeIsFavorite(fingerprint: String): Flow<Boolean> =
        favoriteTrackDao.observeIsFavorite(PersistenceSanitizer.normalizeFingerprint(fingerprint))

    fun observeAllFavorites(): Flow<List<FavoriteTrackRecord>> =
        favoriteTrackDao.observeAllFavorites().map { list -> list.map { it.toDomain() } }

    fun observeFavoriteFingerprints(): Flow<Set<String>> =
        favoriteTrackDao.observeFavoriteFingerprints().map { it.toSet() }
}
