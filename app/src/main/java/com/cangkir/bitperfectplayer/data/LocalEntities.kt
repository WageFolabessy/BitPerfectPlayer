package com.cangkir.bitperfectplayer.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "lyrics")
data class LyricsEntity(
    @PrimaryKey
    @ColumnInfo(name = "content_fingerprint")
    val contentFingerprint: String,
    @ColumnInfo(name = "raw_lyrics_text")
    val rawLyricsText: String,
    @ColumnInfo(name = "is_synced_lrc")
    val isSyncedLrc: Boolean,
    @ColumnInfo(name = "source_type")
    val sourceType: String,
    @ColumnInfo(name = "file_name")
    val fileName: String,
    @ColumnInfo(name = "file_size_bytes")
    val fileSizeBytes: Long,
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long,
    @ColumnInfo(name = "updated_at_epoch_ms")
    val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "track_history",
    indices = [Index(value = ["played_at_epoch_ms"])],
)
data class TrackHistoryEntity(
    @PrimaryKey
    @ColumnInfo(name = "content_fingerprint")
    val contentFingerprint: String,
    @ColumnInfo(name = "track_uri")
    val trackUri: String,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "artist")
    val artist: String,
    @ColumnInfo(name = "album")
    val album: String,
    @ColumnInfo(name = "file_name")
    val fileName: String,
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long,
    @ColumnInfo(name = "played_at_epoch_ms")
    val playedAtEpochMs: Long,
)

@Entity(
    tableName = "favorite_tracks",
    indices = [Index(value = ["added_at_epoch_ms"])],
)
data class FavoriteTrackEntity(
    @PrimaryKey
    @ColumnInfo(name = "content_fingerprint")
    val contentFingerprint: String,
    @ColumnInfo(name = "track_uri")
    val trackUri: String,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "artist")
    val artist: String,
    @ColumnInfo(name = "album")
    val album: String,
    @ColumnInfo(name = "file_name")
    val fileName: String,
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long,
    @ColumnInfo(name = "added_at_epoch_ms")
    val addedAtEpochMs: Long,
)

fun LyricsEntity.toDomain(): LyricsRecord = LyricsRecord(
    contentFingerprint = PersistenceSanitizer.normalizeFingerprint(contentFingerprint),
    rawLyricsText = PersistenceSanitizer.sanitizeLyricsText(rawLyricsText),
    isSyncedLrc = isSyncedLrc,
    sourceType = LyricsSourceType.fromStoredName(sourceType),
    fileName = PersistenceSanitizer.sanitizeDisplayText(fileName, "Unknown"),
    fileSizeBytes = PersistenceSanitizer.sanitizeNonNegative(fileSizeBytes),
    durationMs = PersistenceSanitizer.sanitizeNonNegative(durationMs),
    updatedAtEpochMs = PersistenceSanitizer.sanitizeNonNegative(updatedAtEpochMs),
)

fun LyricsRecord.toEntity(): LyricsEntity = LyricsEntity(
    contentFingerprint = PersistenceSanitizer.normalizeFingerprint(contentFingerprint),
    rawLyricsText = PersistenceSanitizer.sanitizeLyricsText(rawLyricsText),
    isSyncedLrc = isSyncedLrc,
    sourceType = sourceType.name,
    fileName = PersistenceSanitizer.sanitizeDisplayText(fileName, "Unknown"),
    fileSizeBytes = PersistenceSanitizer.sanitizeNonNegative(fileSizeBytes),
    durationMs = PersistenceSanitizer.sanitizeNonNegative(durationMs),
    updatedAtEpochMs = PersistenceSanitizer.sanitizeNonNegative(updatedAtEpochMs),
)

fun TrackHistoryEntity.toDomain(): TrackHistoryRecord = TrackHistoryRecord(
    contentFingerprint = PersistenceSanitizer.normalizeFingerprint(contentFingerprint),
    trackUri = trackUri.trim(),
    title = PersistenceSanitizer.sanitizeDisplayText(title, fileName.ifBlank { "Unknown Track" }),
    artist = PersistenceSanitizer.sanitizeDisplayText(artist, "Unknown Artist"),
    album = PersistenceSanitizer.sanitizeDisplayText(album, "Unknown Album"),
    fileName = PersistenceSanitizer.sanitizeDisplayText(fileName, "Unknown"),
    durationMs = PersistenceSanitizer.sanitizeNonNegative(durationMs),
    playedAtEpochMs = PersistenceSanitizer.sanitizeNonNegative(playedAtEpochMs),
)

fun TrackHistoryRecord.toEntity(): TrackHistoryEntity = TrackHistoryEntity(
    contentFingerprint = PersistenceSanitizer.normalizeFingerprint(contentFingerprint),
    trackUri = trackUri.trim(),
    title = PersistenceSanitizer.sanitizeDisplayText(title, fileName.ifBlank { "Unknown Track" }),
    artist = PersistenceSanitizer.sanitizeDisplayText(artist, "Unknown Artist"),
    album = PersistenceSanitizer.sanitizeDisplayText(album, "Unknown Album"),
    fileName = PersistenceSanitizer.sanitizeDisplayText(fileName, "Unknown"),
    durationMs = PersistenceSanitizer.sanitizeNonNegative(durationMs),
    playedAtEpochMs = PersistenceSanitizer.sanitizeNonNegative(playedAtEpochMs),
)

fun FavoriteTrackEntity.toDomain(): FavoriteTrackRecord = FavoriteTrackRecord(
    contentFingerprint = PersistenceSanitizer.normalizeFingerprint(contentFingerprint),
    trackUri = trackUri.trim(),
    title = PersistenceSanitizer.sanitizeDisplayText(title, fileName.ifBlank { "Unknown Track" }),
    artist = PersistenceSanitizer.sanitizeDisplayText(artist, "Unknown Artist"),
    album = PersistenceSanitizer.sanitizeDisplayText(album, "Unknown Album"),
    fileName = PersistenceSanitizer.sanitizeDisplayText(fileName, "Unknown"),
    durationMs = PersistenceSanitizer.sanitizeNonNegative(durationMs),
    addedAtEpochMs = PersistenceSanitizer.sanitizeNonNegative(addedAtEpochMs),
)

fun FavoriteTrackRecord.toEntity(): FavoriteTrackEntity = FavoriteTrackEntity(
    contentFingerprint = PersistenceSanitizer.normalizeFingerprint(contentFingerprint),
    trackUri = trackUri.trim(),
    title = PersistenceSanitizer.sanitizeDisplayText(title, fileName.ifBlank { "Unknown Track" }),
    artist = PersistenceSanitizer.sanitizeDisplayText(artist, "Unknown Artist"),
    album = PersistenceSanitizer.sanitizeDisplayText(album, "Unknown Album"),
    fileName = PersistenceSanitizer.sanitizeDisplayText(fileName, "Unknown"),
    durationMs = PersistenceSanitizer.sanitizeNonNegative(durationMs),
    addedAtEpochMs = PersistenceSanitizer.sanitizeNonNegative(addedAtEpochMs),
)
