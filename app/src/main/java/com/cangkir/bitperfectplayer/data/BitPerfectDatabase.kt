package com.cangkir.bitperfectplayer.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        CachedTrackEntity::class,
        LyricsEntity::class,
        TrackHistoryEntity::class,
        FavoriteTrackEntity::class,
        PlaylistEntity::class,
        PlaylistTrackEntity::class,
        TrackStatsEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class BitPerfectDatabase : RoomDatabase() {
    abstract fun cachedTrackDao(): CachedTrackDao
    abstract fun lyricsDao(): LyricsDao
    abstract fun trackHistoryDao(): TrackHistoryDao
    abstract fun favoriteTrackDao(): FavoriteTrackDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun trackStatsDao(): TrackStatsDao

    companion object {
        private const val DATABASE_NAME = "bitperfect_player.db"

        /**
         * Non-destructive migration from v1 to v2 adding `cached_tracks` while preserving all
         * existing user `lyrics`, `track_history`, and `favorite_tracks` data intact.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
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
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_cached_tracks_title` ON `cached_tracks` (`title`)",
                )
            }
        }

        /**
         * Non-destructive migration from v2 to v3 adding `playlists`, `playlist_tracks`, and
         * `track_stats` while preserving `cached_tracks`, `lyrics`, `track_history`, and
         * `favorite_tracks` completely untouched.
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `playlists` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `created_at_epoch_ms` INTEGER NOT NULL,
                        `updated_at_epoch_ms` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `playlist_tracks` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `playlist_id` INTEGER NOT NULL,
                        `content_fingerprint` TEXT,
                        `track_uri` TEXT NOT NULL,
                        `file_name` TEXT NOT NULL,
                        `position_index` INTEGER NOT NULL,
                        `added_at_epoch_ms` INTEGER NOT NULL,
                        FOREIGN KEY(`playlist_id`) REFERENCES `playlists`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_playlist_tracks_playlist_id_position_index` " +
                        "ON `playlist_tracks` (`playlist_id`, `position_index`)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `track_stats` (
                        `content_fingerprint` TEXT NOT NULL,
                        `track_uri` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `artist` TEXT NOT NULL,
                        `album` TEXT NOT NULL,
                        `file_name` TEXT NOT NULL,
                        `play_count` INTEGER NOT NULL,
                        `total_play_time_ms` INTEGER NOT NULL,
                        `last_played_at_epoch_ms` INTEGER NOT NULL,
                        PRIMARY KEY(`content_fingerprint`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_track_stats_play_count` ON `track_stats` (`play_count`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_track_stats_last_played_at_epoch_ms` ON `track_stats` (`last_played_at_epoch_ms`)",
                )
            }
        }

        /**
         * Non-destructive migration from v3 to v4 adding `disc_number` and `track_number` columns
         * to `cached_tracks` while preserving ALL existing user tables (`cached_tracks`, `lyrics`,
         * `track_history`, `favorite_tracks`, `playlists`, `playlist_tracks`, `track_stats`).
         */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `cached_tracks` ADD COLUMN `disc_number` INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE `cached_tracks` ADD COLUMN `track_number` INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        @Volatile
        private var instance: BitPerfectDatabase? = null

        fun getInstance(context: Context): BitPerfectDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                BitPerfectDatabase::class.java,
                DATABASE_NAME,
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
                .also { instance = it }
        }
    }
}
