package com.cangkir.bitperfectplayer.data

import com.cangkir.bitperfectplayer.audio.PlaybackTrack
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Facade over local Room DAOs exposing domain models and clean cached-library, favorite,
 * history, and lyrics CRUD.
 */
class LocalPersistenceRepository(
    private val cachedTrackDao: CachedTrackDao,
    private val lyricsDao: LyricsDao,
    private val trackHistoryDao: TrackHistoryDao,
    private val favoriteTrackDao: FavoriteTrackDao,
    private val playlistDao: PlaylistDao,
    private val trackStatsDao: TrackStatsDao,
) {
    constructor(database: BitPerfectDatabase) : this(
        cachedTrackDao = database.cachedTrackDao(),
        lyricsDao = database.lyricsDao(),
        trackHistoryDao = database.trackHistoryDao(),
        favoriteTrackDao = database.favoriteTrackDao(),
        playlistDao = database.playlistDao(),
        trackStatsDao = database.trackStatsDao(),
    )

    fun observeCachedTracks(): Flow<List<CachedTrackEntity>> = cachedTrackDao.observeAll()

    suspend fun getAllCachedTracks(): List<CachedTrackEntity> = cachedTrackDao.getAll()

    suspend fun upsertCachedTracks(entities: List<CachedTrackEntity>) {
        if (entities.isEmpty()) return
        entities.chunked(250).forEach { batch ->
            cachedTrackDao.upsertAll(batch)
        }
    }

    suspend fun updateCachedTrackFingerprint(uriString: String, fingerprint: String) {
        val normalized = PersistenceSanitizer.normalizeFingerprint(fingerprint)
        val trimmedUri = uriString.trim()
        if (trimmedUri.isNotEmpty() && normalized.isNotEmpty()) {
            cachedTrackDao.updateFingerprint(trimmedUri, normalized)
            playlistDao.backfillFingerprintByUri(trimmedUri, normalized)
        }
    }

    suspend fun deleteCachedTracksByUris(uris: List<String>) {
        if (uris.isEmpty()) return
        // Stay safely below SQLite's 999 bind parameter limit.
        uris.chunked(450).forEach { batch ->
            cachedTrackDao.deleteByUris(batch)
        }
    }

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

    /**
     * Atomically records anti-skip play count delta and accumulated active listening time into
     * `track_stats` bound to the track's SHA-256 content fingerprint.
     */
    suspend fun recordTrackListeningDelta(
        contentFingerprint: String,
        track: PlaybackTrack,
        playCountDelta: Int,
        playTimeDeltaMs: Long,
        playedAtEpochMs: Long = System.currentTimeMillis(),
    ) {
        val normalized = PersistenceSanitizer.normalizeFingerprint(contentFingerprint)
        if (normalized.isEmpty()) return
        if (playCountDelta <= 0 && playTimeDeltaMs <= 0L) return
        trackStatsDao.recordListeningDelta(
            contentFingerprint = normalized,
            trackUri = track.uriString.trim(),
            title = PersistenceSanitizer.sanitizeDisplayText(track.title, track.fileName.ifBlank { "Unknown Track" }),
            artist = PersistenceSanitizer.sanitizeDisplayText(track.artist, "Unknown Artist"),
            album = PersistenceSanitizer.sanitizeDisplayText(track.album, "Unknown Album"),
            fileName = PersistenceSanitizer.sanitizeDisplayText(track.fileName, "Unknown"),
            playCountDelta = playCountDelta.coerceAtLeast(0),
            playTimeDeltaMs = playTimeDeltaMs.coerceAtLeast(0L),
            playedAtEpochMs = PersistenceSanitizer.sanitizeNonNegative(playedAtEpochMs),
        )
    }

    fun observeMostPlayedStats(limit: Int = 50): Flow<List<TrackStatsRecord>> =
        trackStatsDao.observeMostPlayed(limit).map { list -> list.map { it.toDomain() } }

    fun observeTotalPlayCount(): Flow<Int> = trackStatsDao.observeTotalPlayCount()

    /**
     * Idempotently persists the desired favorite state [targetFavorite] for [record].
     * Preferred over blind toggle so rapid optimistic UI taps always converge accurately.
     */
    suspend fun setFavorite(record: FavoriteTrackRecord, targetFavorite: Boolean): Boolean {
        val normalized = PersistenceSanitizer.normalizeFingerprint(record.contentFingerprint)
        if (normalized.isEmpty()) return false
        return if (targetFavorite) {
            favoriteTrackDao.upsert(record.copy(contentFingerprint = normalized).toEntity())
            true
        } else {
            favoriteTrackDao.deleteByFingerprint(normalized)
            if (record.trackUri.isNotBlank()) {
                favoriteTrackDao.deleteByTrackUri(record.trackUri.trim())
            }
            false
        }
    }

    suspend fun toggleFavorite(record: FavoriteTrackRecord): Boolean {
        val normalized = PersistenceSanitizer.normalizeFingerprint(record.contentFingerprint)
        if (normalized.isEmpty()) return false
        val currentlyFav = favoriteTrackDao.isFavorite(normalized)
        return setFavorite(record, targetFavorite = !currentlyFav)
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

    fun observeCachedPlaybackTracks(): Flow<List<PlaybackTrack>> =
        cachedTrackDao.observeAll().map { entities -> entities.map { it.toPlaybackTrack() } }
}
