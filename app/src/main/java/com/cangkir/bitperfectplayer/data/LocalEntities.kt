package com.cangkir.bitperfectplayer.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.cangkir.bitperfectplayer.audio.PlaybackTrack

enum class CachedTrackSourceKind {
    MEDIA_STORE,
    SAF_TREE,
    SAF_FILE,
    ;

    companion object {
        fun fromStoredName(raw: String?): CachedTrackSourceKind =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: MEDIA_STORE
    }
}

@Entity(
    tableName = "cached_tracks",
    indices = [Index(value = ["title"])],
)
data class CachedTrackEntity(
    @PrimaryKey
    @ColumnInfo(name = "uri_string")
    val uriString: String,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "artist")
    val artist: String,
    @ColumnInfo(name = "album")
    val album: String,
    @ColumnInfo(name = "folder_name")
    val folderName: String,
    @ColumnInfo(name = "file_name")
    val fileName: String,
    @ColumnInfo(name = "mime_type")
    val mimeType: String?,
    @ColumnInfo(name = "file_size_bytes")
    val fileSizeBytes: Long,
    @ColumnInfo(name = "date_modified_seconds")
    val dateModifiedSeconds: Long,
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long,
    @ColumnInfo(name = "sample_rate_hz")
    val sampleRateHz: Int,
    @ColumnInfo(name = "bit_depth")
    val bitDepth: Int?,
    @ColumnInfo(name = "container")
    val container: String,
    @ColumnInfo(name = "codec")
    val codec: String,
    @ColumnInfo(name = "content_fingerprint")
    val contentFingerprint: String?,
    @ColumnInfo(name = "parent_tree_uri_string")
    val parentTreeUriString: String?,
    @ColumnInfo(name = "relative_path")
    val relativePath: String?,
    @ColumnInfo(name = "source_kind")
    val sourceKind: String,
    @ColumnInfo(name = "last_synced_epoch_ms")
    val lastSyncedEpochMs: Long,
)

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

fun CachedTrackEntity.toPlaybackTrack(): PlaybackTrack = PlaybackTrack(
    uriString = uriString,
    title = PersistenceSanitizer.sanitizeDisplayText(title, fileName.ifBlank { "Unknown Track" }),
    artist = PersistenceSanitizer.sanitizeDisplayText(artist, "Unknown Artist"),
    album = PersistenceSanitizer.sanitizeDisplayText(album, "Unknown Album"),
    folderName = PersistenceSanitizer.sanitizeDisplayText(folderName, "Internal Storage"),
    fileName = PersistenceSanitizer.sanitizeDisplayText(fileName, "Unknown"),
    mimeType = mimeType,
    fileSizeBytes = PersistenceSanitizer.sanitizeNonNegative(fileSizeBytes),
    dateModifiedSeconds = PersistenceSanitizer.sanitizeNonNegative(dateModifiedSeconds),
    durationMs = PersistenceSanitizer.sanitizeNonNegative(durationMs),
    sampleRateHz = sampleRateHz.coerceAtLeast(0),
    bitDepth = bitDepth?.takeIf { it > 0 },
    container = container.ifBlank { "AUDIO" },
    codec = codec.ifBlank { "Unknown" },
    contentFingerprint = contentFingerprint?.let { PersistenceSanitizer.normalizeFingerprint(it) }?.takeIf { it.isNotEmpty() },
    parentTreeUriString = parentTreeUriString,
    relativePath = relativePath,
)

fun PlaybackTrack.toCachedEntity(
    sourceKind: CachedTrackSourceKind,
    syncedEpochMs: Long = System.currentTimeMillis(),
): CachedTrackEntity = CachedTrackEntity(
    uriString = uriString.trim(),
    title = PersistenceSanitizer.sanitizeDisplayText(title, fileName.ifBlank { "Unknown Track" }),
    artist = PersistenceSanitizer.sanitizeDisplayText(artist, "Unknown Artist"),
    album = PersistenceSanitizer.sanitizeDisplayText(album, "Unknown Album"),
    folderName = PersistenceSanitizer.sanitizeDisplayText(folderName, "Internal Storage"),
    fileName = PersistenceSanitizer.sanitizeDisplayText(fileName, "Unknown"),
    mimeType = mimeType,
    fileSizeBytes = PersistenceSanitizer.sanitizeNonNegative(fileSizeBytes),
    dateModifiedSeconds = PersistenceSanitizer.sanitizeNonNegative(dateModifiedSeconds),
    durationMs = PersistenceSanitizer.sanitizeNonNegative(durationMs),
    sampleRateHz = sampleRateHz.coerceAtLeast(0),
    bitDepth = bitDepth?.takeIf { it > 0 },
    container = container.ifBlank { "AUDIO" },
    codec = codec.ifBlank { "Unknown" },
    contentFingerprint = contentFingerprint?.let { PersistenceSanitizer.normalizeFingerprint(it) }?.takeIf { it.isNotEmpty() },
    parentTreeUriString = parentTreeUriString,
    relativePath = relativePath,
    sourceKind = sourceKind.name,
    lastSyncedEpochMs = PersistenceSanitizer.sanitizeNonNegative(syncedEpochMs),
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
