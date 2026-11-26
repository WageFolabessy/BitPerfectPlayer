package com.cangkir.bitperfectplayer

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cangkir.bitperfectplayer.data.BitPerfectDatabase
import com.cangkir.bitperfectplayer.data.CachedTrackEntity
import com.cangkir.bitperfectplayer.data.CachedTrackSourceKind
import com.cangkir.bitperfectplayer.data.PlaylistEntity
import com.cangkir.bitperfectplayer.data.PlaylistTrackEntity
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device Android SQLite Room migration smoke test verifying real v1 -> v2 -> v3 -> v4 -> v5
 * migrations and post-migration track/playlist CRUD + cascade behavior on physical Android SQLite.
 */
@RunWith(AndroidJUnit4::class)
class RoomDatabaseDeviceMigrationTest {

    private lateinit var targetContext: Context
    private lateinit var dbFile: File
    private var roomDb: BitPerfectDatabase? = null

    private val sampleFingerprint = "a1b2c3d4e5f60718".repeat(4)

    @Before
    fun setUp() {
        targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        dbFile = targetContext.getDatabasePath("instrumented_migration_smoke.db")
        dbFile.parentFile?.mkdirs()
        deleteDbFiles()
    }

    @After
    fun tearDown() {
        runCatching { roomDb?.close() }
        roomDb = null
        deleteDbFiles()
    }

    @Test
    fun migrateV1ToV5_onDeviceSqlitePreservesDataAndSupportsTrackAndPlaylistCrud() = runBlocking {
        seedV1Database()

        val db = Room.databaseBuilder(targetContext, BitPerfectDatabase::class.java, dbFile.name)
            .addMigrations(
                BitPerfectDatabase.MIGRATION_1_2,
                BitPerfectDatabase.MIGRATION_2_3,
                BitPerfectDatabase.MIGRATION_3_4,
                BitPerfectDatabase.MIGRATION_4_5,
            )
            .build()
        roomDb = db

        // Verify v1 rows survived untouched across all four migrations.
        val lyrics = db.lyricsDao().getByFingerprint(sampleFingerprint)
        assertNotNull(lyrics)
        assertEquals("[00:01.00]BitPerfect Smoke", lyrics?.rawLyricsText)
        assertTrue(db.favoriteTrackDao().isFavorite(sampleFingerprint))
        assertEquals(1, db.trackHistoryDao().getRecentlyPlayed(10).size)

        // Verify v5 cached_tracks CRUD (including disc_number, track_number, and year).
        val trackUri = "content://media/external/audio/media/901"
        db.cachedTrackDao().upsertAll(
            listOf(
                CachedTrackEntity(
                    uriString = trackUri,
                    title = "Smoke Track",
                    artist = "Studio Artist",
                    album = "Studio Album",
                    folderName = "Music",
                    fileName = "01 - Smoke Track.flac",
                    mimeType = "audio/flac",
                    fileSizeBytes = 12_345_678L,
                    dateModifiedSeconds = 1_700_000_000L,
                    durationMs = 180_000L,
                    sampleRateHz = 96_000,
                    bitDepth = 24,
                    container = "FLAC",
                    codec = "FLAC",
                    contentFingerprint = sampleFingerprint,
                    parentTreeUriString = null,
                    relativePath = "Music/Studio Album/",
                    sourceKind = CachedTrackSourceKind.MEDIA_STORE.name,
                    lastSyncedEpochMs = 1_700_000_010_000L,
                    discNumber = 1,
                    trackNumber = 1,
                    year = 2025,
                ),
            ),
        )
        val cachedTracks = db.cachedTrackDao().getAll()
        assertEquals(1, cachedTracks.size)
        assertEquals(2025, cachedTracks.single().year)

        // Verify playlists + playlist_tracks CRUD and ON DELETE CASCADE foreign key.
        val playlistId = db.playlistDao().insertPlaylist(
            PlaylistEntity(
                name = "Device Smoke Playlist",
                createdAtEpochMs = 1_700_000_020_000L,
                updatedAtEpochMs = 1_700_000_020_000L,
            ),
        )
        db.playlistDao().insertPlaylistTrack(
            PlaylistTrackEntity(
                playlistId = playlistId,
                contentFingerprint = sampleFingerprint,
                trackUri = trackUri,
                fileName = "01 - Smoke Track.flac",
                positionIndex = 0,
                addedAtEpochMs = 1_700_000_025_000L,
            ),
        )
        assertEquals(1, db.playlistDao().getTracksForPlaylist(playlistId).size)

        db.playlistDao().deletePlaylistById(playlistId)
        assertTrue(db.playlistDao().getAllPlaylists().isEmpty())
        assertTrue(db.playlistDao().getAllPlaylistTracks().isEmpty())
    }

    private fun seedV1Database() {
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
                "INSERT INTO `lyrics` VALUES ('$sampleFingerprint', '[00:01.00]BitPerfect Smoke', " +
                    "1, 'LRC_FILE', '01 - Smoke.flac', 12345678, 180000, 1700000000000)",
            )
            sqlite.execSQL(
                "INSERT INTO `track_history` VALUES ('$sampleFingerprint', 'content://media/external/audio/media/901', " +
                    "'Smoke Track', 'Studio Artist', 'Studio Album', '01 - Smoke.flac', 180000, 1700000005000)",
            )
            sqlite.execSQL(
                "INSERT INTO `favorite_tracks` VALUES ('$sampleFingerprint', 'content://media/external/audio/media/901', " +
                    "'Smoke Track', 'Studio Artist', 'Studio Album', '01 - Smoke.flac', 180000, 1700000009000)",
            )
            sqlite.version = 1
        }
    }

    private fun deleteDbFiles() {
        listOf("", "-wal", "-shm", "-journal").forEach { suffix ->
            File(dbFile.absolutePath + suffix).delete()
        }
    }
}
