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

    suspend fun backfillCachedTrackPositions(updates: List<TrackPositionBackfill>) {
        if (updates.isEmpty()) return
        cachedTrackDao.backfillTrackPositions(updates)
    }

    suspend fun backfillCachedTrackYears(updates: List<TrackYearBackfill>) {
        if (updates.isEmpty()) return
        cachedTrackDao.backfillTrackYears(updates)
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

    fun observeAllTrackStats(): Flow<List<TrackStatsRecord>> = trackStatsDao.observeAllStats().map { list -> list.map { it.toDomain() } }

    fun observeMostPlayedStats(limit: Int = 50): Flow<List<TrackStatsRecord>> =
        trackStatsDao.observeMostPlayed(limit).map { list -> list.map { it.toDomain() } }

    fun observeTotalPlayCount(): Flow<Int> = trackStatsDao.observeTotalPlayCount()

    suspend fun getTrackStatsByFingerprint(fingerprint: String): TrackStatsRecord? {
        val normalized = PersistenceSanitizer.normalizeFingerprint(fingerprint)
        if (normalized.isEmpty()) return null
        return trackStatsDao.getByFingerprint(normalized)?.toDomain()
    }

    suspend fun exportLocalBackupPayload(
        nowEpochMs: Long = System.currentTimeMillis(),
        defaultHomepage: DefaultHomepage = DefaultHomepage.ALL_SONGS,
        dynamicCoverThemeEnabled: Boolean = false,
    ): LocalBackupPayload {
        val playlists = playlistDao.getAllPlaylists()
        val tracksByPlaylist = playlistDao.getAllPlaylistTracks().groupBy { it.playlistId }
        val backupPlaylists = playlists.map { pl ->
            BackupPlaylist(
                name = pl.name,
                createdAtEpochMs = pl.createdAtEpochMs,
                updatedAtEpochMs = pl.updatedAtEpochMs,
                tracks = tracksByPlaylist[pl.id].orEmpty().map { tr ->
                    BackupPlaylistEntry(
                        contentFingerprint = tr.contentFingerprint,
                        trackUri = tr.trackUri,
                        fileName = tr.fileName,
                        positionIndex = tr.positionIndex,
                        addedAtEpochMs = tr.addedAtEpochMs,
                    )
                },
            )
        }
        return LocalBackupPayload(
            schemaVersion = LocalBackupCodec.CURRENT_SCHEMA_VERSION,
            appId = LocalBackupCodec.APP_ID,
            exportedAtEpochMs = nowEpochMs,
            defaultHomepage = defaultHomepage,
            dynamicCoverThemeEnabled = dynamicCoverThemeEnabled,
            playlists = backupPlaylists,
            lyrics = lyricsDao.getAll().map { it.toDomain() },
            favorites = favoriteTrackDao.getAllFavorites().map { it.toDomain() },
            trackStats = trackStatsDao.getAllStats().map { it.toDomain() },
        )
    }

    /**
     * Non-destructive merge/upsert restore of a validated [LocalBackupPayload].
     * Never deletes existing local playlists, lyrics, favorites, or stats, and stays idempotent
     * if the exact same backup is restored more than once.
     */
    suspend fun importLocalBackupPayload(
        payload: LocalBackupPayload,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): LocalBackupRestoreSummary {
        val lyricsCount = mergeBackupLyrics(payload.lyrics)
        val favoritesCount = mergeBackupFavorites(payload.favorites)
        val statsCount = mergeBackupTrackStats(payload.trackStats)
        val (playlistsMerged, playlistTracksAdded) = mergeBackupPlaylists(payload.playlists, nowEpochMs)

        return LocalBackupRestoreSummary(
            playlistsMerged = playlistsMerged,
            playlistTracksAdded = playlistTracksAdded,
            lyricsUpserted = lyricsCount,
            favoritesMerged = favoritesCount,
            statsMerged = statsCount,
        )
    }

    private suspend fun mergeBackupLyrics(lyrics: List<LyricsRecord>): Int {
        var lyricsCount = 0
        for (lyric in lyrics) {
            val normalizedFp = PersistenceSanitizer.normalizeFingerprint(lyric.contentFingerprint)
            if (!PersistenceSanitizer.isValidSha256Fingerprint(normalizedFp)) continue
            val existing = lyricsDao.getByFingerprint(normalizedFp)
            if (existing == null || lyric.updatedAtEpochMs >= existing.updatedAtEpochMs) {
                lyricsDao.upsert(lyric.copy(contentFingerprint = normalizedFp).toEntity())
                lyricsCount++
            }
        }
        return lyricsCount
    }

    private suspend fun mergeBackupFavorites(favorites: List<FavoriteTrackRecord>): Int {
        var favoritesCount = 0
        for (fav in favorites) {
            val normalizedFp = PersistenceSanitizer.normalizeFingerprint(fav.contentFingerprint)
            if (!PersistenceSanitizer.isValidSha256Fingerprint(normalizedFp)) continue
            if (!favoriteTrackDao.isFavorite(normalizedFp)) {
                favoriteTrackDao.upsert(fav.copy(contentFingerprint = normalizedFp).toEntity())
                favoritesCount++
            }
        }
        return favoritesCount
    }

    private suspend fun mergeBackupTrackStats(trackStats: List<TrackStatsRecord>): Int {
        var statsCount = 0
        for (stat in trackStats) {
            val normalizedFp = PersistenceSanitizer.normalizeFingerprint(stat.contentFingerprint)
            if (!PersistenceSanitizer.isValidSha256Fingerprint(normalizedFp)) continue
            val existing = trackStatsDao.getByFingerprint(normalizedFp)
            val merged = if (existing == null) {
                stat.copy(contentFingerprint = normalizedFp).toEntity()
            } else {
                TrackStatsEntity(
                    contentFingerprint = normalizedFp,
                    trackUri = existing.trackUri.ifBlank { stat.trackUri.trim() },
                    title = existing.title.ifBlank { stat.title },
                    artist = existing.artist.ifBlank { stat.artist },
                    album = existing.album.ifBlank { stat.album },
                    fileName = existing.fileName.ifBlank { stat.fileName },
                    playCount = maxOf(existing.playCount, stat.playCount.coerceAtLeast(0)),
                    totalPlayTimeMs = maxOf(existing.totalPlayTimeMs, stat.totalPlayTimeMs.coerceAtLeast(0L)),
                    lastPlayedAtEpochMs = maxOf(existing.lastPlayedAtEpochMs, stat.lastPlayedAtEpochMs.coerceAtLeast(0L)),
                )
            }
            trackStatsDao.upsert(merged)
            statsCount++
        }
        return statsCount
    }

    private suspend fun mergeBackupPlaylists(playlists: List<BackupPlaylist>, nowEpochMs: Long): Pair<Int, Int> {
        val existingPlaylistsByName = playlistDao.getAllPlaylists()
            .associateBy { it.name.trim().lowercase() }
            .toMutableMap()
        var playlistsMerged = 0
        var playlistTracksAdded = 0

        for (backupPl in playlists) {
            val cleanName = backupPl.name.trim()
            if (cleanName.isEmpty()) continue
            val targetPlaylistId = resolveOrCreatePlaylistId(existingPlaylistsByName, cleanName, backupPl, nowEpochMs)
            playlistsMerged++
            playlistTracksAdded += appendNonDuplicatePlaylistTracks(targetPlaylistId, backupPl.tracks, nowEpochMs)
        }
        return playlistsMerged to playlistTracksAdded
    }

    private suspend fun resolveOrCreatePlaylistId(
        existingPlaylistsByName: MutableMap<String, PlaylistEntity>,
        cleanName: String,
        backupPl: BackupPlaylist,
        nowEpochMs: Long,
    ): Long {
        val key = cleanName.lowercase()
        val existing = existingPlaylistsByName[key]
        if (existing != null) return existing.id

        val createdAt = backupPl.createdAtEpochMs.takeIf { it > 0L } ?: nowEpochMs
        val updatedAt = backupPl.updatedAtEpochMs.takeIf { it > 0L } ?: nowEpochMs
        val createdId = playlistDao.insertPlaylist(
            PlaylistEntity(name = cleanName, createdAtEpochMs = createdAt, updatedAtEpochMs = updatedAt),
        )
        existingPlaylistsByName[key] = PlaylistEntity(
            id = createdId,
            name = cleanName,
            createdAtEpochMs = createdAt,
            updatedAtEpochMs = updatedAt,
        )
        return createdId
    }

    private suspend fun appendNonDuplicatePlaylistTracks(
        targetPlaylistId: Long,
        tracks: List<BackupPlaylistEntry>,
        nowEpochMs: Long,
    ): Int {
        val existingEntries = playlistDao.getTracksForPlaylist(targetPlaylistId)
        val existingKeys = existingEntries.map { entry ->
            entry.contentFingerprint?.lowercase()?.takeIf { it.isNotEmpty() } ?: entry.trackUri
        }.toMutableSet()
        var nextPos = (existingEntries.maxOfOrNull { it.positionIndex } ?: -1) + 1

        val toInsert = mutableListOf<PlaylistTrackEntity>()
        for (tr in tracks.sortedBy { it.positionIndex }) {
            val fp = tr.contentFingerprint?.let { PersistenceSanitizer.normalizeFingerprint(it) }
                ?.takeIf { PersistenceSanitizer.isValidSha256Fingerprint(it) }
            val dedupKey = fp ?: tr.trackUri.trim()
            if (dedupKey.isEmpty() || !existingKeys.add(dedupKey)) continue
            toInsert.add(
                PlaylistTrackEntity(
                    playlistId = targetPlaylistId,
                    contentFingerprint = fp,
                    trackUri = tr.trackUri.trim(),
                    fileName = PersistenceSanitizer.sanitizeDisplayText(tr.fileName, "Unknown"),
                    positionIndex = nextPos++,
                    addedAtEpochMs = tr.addedAtEpochMs.takeIf { it > 0L } ?: nowEpochMs,
                ),
            )
        }
        if (toInsert.isNotEmpty()) {
            playlistDao.insertPlaylistTracks(toInsert)
            playlistDao.touchPlaylist(targetPlaylistId, nowEpochMs)
        }
        return toInsert.size
    }

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

    fun observeFavoriteFingerprints(): Flow<Set<String>> = favoriteTrackDao.observeFavoriteFingerprints().map { it.toSet() }

    fun observeCachedPlaybackTracks(): Flow<List<PlaybackTrack>> =
        cachedTrackDao.observeAll().map { entities -> entities.map { it.toPlaybackTrack() } }
}
