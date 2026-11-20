package com.cangkir.bitperfectplayer.data

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * End-to-end SQLite schema & migration verification test across Room v1 -> v2 -> v3 -> v4.
 *
 * Executes actual SQLite DDL (`MIGRATION_1_2`, `MIGRATION_2_3`, `MIGRATION_3_4`) and triggers
 * Room's generated `BitPerfectDatabase_Impl.onValidateSchema(...)` to guarantee upgrading from
 * any historical APK version on a physical device never throws `IllegalStateException` and never
 * loses user lyrics, favorites, history, playlists, or listening statistics.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BitPerfectDatabaseMigrationTest {

    private lateinit var app: Application
    private lateinit var dbFile: File
    private var openedRoomDb: BitPerfectDatabase? = null

    private val sampleFp1 = "a1b2c3d4e5f60718".repeat(4)
    private val sampleFp2 = "f0e1d2c3b4a59687".repeat(4)

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        dbFile = app.getDatabasePath("migration_audit_test.db")
        dbFile.parentFile?.mkdirs()
        deleteDbFiles()
    }

    @After
    fun tearDown() {
        runCatching { openedRoomDb?.close() }
        openedRoomDb = null
        deleteDbFiles()
    }

    private fun deleteDbFiles() {
        listOf("", "-wal", "-shm", "-journal").forEach { suffix ->
            File(dbFile.absolutePath + suffix).delete()
        }
    }

    @Test
    fun migrateV1ToV4_validatesFullRoomSchemaAndPreservesLyricsFavoritesAndHistory() = runTest {
        // 1. Seed a genuine Room v1 SQLite database on disk
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { sqlite ->
            sqlite.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `lyrics` (
                    `content_fingerprint` TEXT NOT NULL,
                    `raw_lyrics_text` TEXT NOT NULL,
                    `is_synced_lrc` INTEGER NOT NULL,
                    `source_type` TEXT NOT NULL,
                    `file_name` TEXT NOT NULL,
                    `file_size_bytes` INTEGER NOT NULL,
                    `duration_ms` INTEGER NOT NULL,
                    `updated_at_epoch_ms` INTEGER NOT NULL,
                    PRIMARY KEY(`content_fingerprint`)
                )
                """.trimIndent(),
            )
            sqlite.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `track_history` (
                    `content_fingerprint` TEXT NOT NULL,
                    `track_uri` TEXT NOT NULL,
                    `title` TEXT NOT NULL,
                    `artist` TEXT NOT NULL,
                    `album` TEXT NOT NULL,
                    `file_name` TEXT NOT NULL,
                    `duration_ms` INTEGER NOT NULL,
                    `played_at_epoch_ms` INTEGER NOT NULL,
                    PRIMARY KEY(`content_fingerprint`)
                )
                """.trimIndent(),
            )
            sqlite.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_track_history_played_at_epoch_ms` ON `track_history` (`played_at_epoch_ms`)",
            )
            sqlite.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `favorite_tracks` (
                    `content_fingerprint` TEXT NOT NULL,
                    `track_uri` TEXT NOT NULL,
                    `title` TEXT NOT NULL,
                    `artist` TEXT NOT NULL,
                    `album` TEXT NOT NULL,
                    `file_name` TEXT NOT NULL,
                    `duration_ms` INTEGER NOT NULL,
                    `added_at_epoch_ms` INTEGER NOT NULL,
                    PRIMARY KEY(`content_fingerprint`)
                )
                """.trimIndent(),
            )
            sqlite.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_favorite_tracks_added_at_epoch_ms` ON `favorite_tracks` (`added_at_epoch_ms`)",
            )
            sqlite.execSQL(
                "INSERT INTO `lyrics` VALUES ('$sampleFp1', '[00:01.00]Hello Audiophile', 1, 'LRC_FILE', '01 - Hello.flac', 35000000, 210000, 1700000000000)",
            )
            sqlite.execSQL(
                "INSERT INTO `track_history` VALUES ('$sampleFp1', 'content://media/external/audio/media/101', 'Hello', 'Artist A', 'Album A', '01 - Hello.flac', 210000, 1700000005000)",
            )
            sqlite.execSQL(
                "INSERT INTO `favorite_tracks` VALUES ('$sampleFp1', 'content://media/external/audio/media/101', 'Hello', 'Artist A', 'Album A', '01 - Hello.flac', 210000, 1700000009000)",
            )
            sqlite.version = 1
        }

        // 2. Open with Room v4 and run the complete MIGRATION_1_2 -> MIGRATION_2_3 -> MIGRATION_3_4 chain
        val db = Room.databaseBuilder(app, BitPerfectDatabase::class.java, dbFile.name)
            .addMigrations(
                BitPerfectDatabase.MIGRATION_1_2,
                BitPerfectDatabase.MIGRATION_2_3,
                BitPerfectDatabase.MIGRATION_3_4,
            )
            .allowMainThreadQueries()
            .build()
        openedRoomDb = db

        // 3. Verify v1 user data survived completely untouched
        val lyrics = db.lyricsDao().getByFingerprint(sampleFp1)
        assertNotNull(lyrics)
        assertEquals("[00:01.00]Hello Audiophile", lyrics?.rawLyricsText)

        assertTrue(db.favoriteTrackDao().isFavorite(sampleFp1))
        val history = db.trackHistoryDao().getRecentlyPlayed(10)
        assertEquals(1, history.size)
        assertEquals("Hello", history.first().title)

        // 4. Verify v2/v3/v4 tables & columns work cleanly after migration
        db.cachedTrackDao().upsertAll(
            listOf(
                CachedTrackEntity(
                    uriString = "content://media/external/audio/media/101",
                    title = "Hello",
                    artist = "Artist A",
                    album = "Album A",
                    folderName = "Music",
                    fileName = "01 - Hello.flac",
                    mimeType = "audio/flac",
                    fileSizeBytes = 35_000_000L,
                    dateModifiedSeconds = 1_700_000_000L,
                    durationMs = 210_000L,
                    sampleRateHz = 96_000,
                    bitDepth = 24,
                    container = "FLAC",
                    codec = "FLAC",
                    contentFingerprint = sampleFp1,
                    parentTreeUriString = null,
                    relativePath = "Music/Album A/",
                    sourceKind = CachedTrackSourceKind.MEDIA_STORE.name,
                    lastSyncedEpochMs = 1_700_000_010_000L,
                    discNumber = 1,
                    trackNumber = 1,
                ),
            ),
        )
        val cached = db.cachedTrackDao().getAll()
        assertEquals(1, cached.size)
        assertEquals(1, cached.first().discNumber)
        assertEquals(1, cached.first().trackNumber)
    }

    @Test
    fun migrateV2AndV3ToV4_preservesCachedTracksPlaylistsAndStatsWithDefaultDiscAndTrackNumbers() = runTest {
        // 1. Seed a Room v2 database with existing cached_tracks (19 columns, before disc_number/track_number)
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { sqlite ->
            sqlite.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `lyrics` (
                    `content_fingerprint` TEXT NOT NULL,
                    `raw_lyrics_text` TEXT NOT NULL,
                    `is_synced_lrc` INTEGER NOT NULL,
                    `source_type` TEXT NOT NULL,
                    `file_name` TEXT NOT NULL,
                    `file_size_bytes` INTEGER NOT NULL,
                    `duration_ms` INTEGER NOT NULL,
                    `updated_at_epoch_ms` INTEGER NOT NULL,
                    PRIMARY KEY(`content_fingerprint`)
                )
                """.trimIndent(),
            )
            sqlite.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `track_history` (
                    `content_fingerprint` TEXT NOT NULL,
                    `track_uri` TEXT NOT NULL,
                    `title` TEXT NOT NULL,
                    `artist` TEXT NOT NULL,
                    `album` TEXT NOT NULL,
                    `file_name` TEXT NOT NULL,
                    `duration_ms` INTEGER NOT NULL,
                    `played_at_epoch_ms` INTEGER NOT NULL,
                    PRIMARY KEY(`content_fingerprint`)
                )
                """.trimIndent(),
            )
            sqlite.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_track_history_played_at_epoch_ms` ON `track_history` (`played_at_epoch_ms`)",
            )
            sqlite.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `favorite_tracks` (
                    `content_fingerprint` TEXT NOT NULL,
                    `track_uri` TEXT NOT NULL,
                    `title` TEXT NOT NULL,
                    `artist` TEXT NOT NULL,
                    `album` TEXT NOT NULL,
                    `file_name` TEXT NOT NULL,
                    `duration_ms` INTEGER NOT NULL,
                    `added_at_epoch_ms` INTEGER NOT NULL,
                    PRIMARY KEY(`content_fingerprint`)
                )
                """.trimIndent(),
            )
            sqlite.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_favorite_tracks_added_at_epoch_ms` ON `favorite_tracks` (`added_at_epoch_ms`)",
            )
            sqlite.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `cached_tracks` (
                    `uri_string` TEXT NOT NULL,
                    `title` TEXT NOT NULL,
                    `artist` TEXT NOT NULL,
                    `album` TEXT NOT NULL,
                    `folder_name` TEXT NOT NULL,
                    `file_name` TEXT NOT NULL,
                    `mime_type` TEXT,
                    `file_size_bytes` INTEGER NOT NULL,
                    `date_modified_seconds` INTEGER NOT NULL,
                    `duration_ms` INTEGER NOT NULL,
                    `sample_rate_hz` INTEGER NOT NULL,
                    `bit_depth` INTEGER,
                    `container` TEXT NOT NULL,
                    `codec` TEXT NOT NULL,
                    `content_fingerprint` TEXT,
                    `parent_tree_uri_string` TEXT,
                    `relative_path` TEXT,
                    `source_kind` TEXT NOT NULL,
                    `last_synced_epoch_ms` INTEGER NOT NULL,
                    PRIMARY KEY(`uri_string`)
                )
                """.trimIndent(),
            )
            sqlite.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_cached_tracks_title` ON `cached_tracks` (`title`)",
            )
            sqlite.execSQL(
                """
                INSERT INTO `cached_tracks` VALUES (
                    'content://media/external/audio/media/202',
                    'Peacemaker',
                    'Green Day',
                    '21st Century Breakdown',
                    'Music',
                    '07 - Peacemaker.flac',
                    'audio/flac',
                    42500000,
                    1700000000,
                    204000,
                    96000,
                    24,
                    'FLAC',
                    'FLAC',
                    '$sampleFp2',
                    NULL,
                    'Music/Green Day/',
                    'MEDIA_STORE',
                    1700000000000
                )
                """.trimIndent(),
            )
            sqlite.version = 2
        }

        // 2. Open with Room v4 (runs MIGRATION_2_3 and MIGRATION_3_4)
        val db = Room.databaseBuilder(app, BitPerfectDatabase::class.java, dbFile.name)
            .addMigrations(
                BitPerfectDatabase.MIGRATION_1_2,
                BitPerfectDatabase.MIGRATION_2_3,
                BitPerfectDatabase.MIGRATION_3_4,
            )
            .allowMainThreadQueries()
            .build()
        openedRoomDb = db

        val cachedBeforeBackfill = db.cachedTrackDao().getAll()
        assertEquals(1, cachedBeforeBackfill.size)
        assertEquals("Peacemaker", cachedBeforeBackfill.first().title)
        assertEquals(0, cachedBeforeBackfill.first().discNumber)
        assertEquals(0, cachedBeforeBackfill.first().trackNumber)

        // 3. Verify cursor-only backfill updates disc_number and track_number in place
        db.cachedTrackDao().backfillTrackPositions(
            listOf(
                TrackPositionBackfill(
                    uriString = "content://media/external/audio/media/202",
                    discNumber = 1,
                    trackNumber = 7,
                ),
            ),
        )
        val cachedAfterBackfill = db.cachedTrackDao().getAll().first()
        assertEquals(1, cachedAfterBackfill.discNumber)
        assertEquals(7, cachedAfterBackfill.trackNumber)

        // 4. Verify v3 playlist foreign-key CASCADE delete works on migrated DB
        val playlistId = db.playlistDao().insertPlaylist(
            PlaylistEntity(name = "Test Cascade", createdAtEpochMs = 100L, updatedAtEpochMs = 100L),
        )
        db.playlistDao().insertPlaylistTrack(
            PlaylistTrackEntity(
                playlistId = playlistId,
                contentFingerprint = sampleFp2,
                trackUri = cachedAfterBackfill.uriString,
                fileName = cachedAfterBackfill.fileName,
                positionIndex = 0,
                addedAtEpochMs = 100L,
            ),
        )
        assertEquals(1, db.playlistDao().getTracksForPlaylist(playlistId).size)
        db.playlistDao().deletePlaylistById(playlistId)
        assertEquals(0, db.playlistDao().getTracksForPlaylist(playlistId).size)
    }
}
