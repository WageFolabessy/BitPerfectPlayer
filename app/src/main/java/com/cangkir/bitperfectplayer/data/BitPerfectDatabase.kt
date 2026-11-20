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
    ],
    version = 2,
    exportSchema = false,
)
abstract class BitPerfectDatabase : RoomDatabase() {
    abstract fun cachedTrackDao(): CachedTrackDao
    abstract fun lyricsDao(): LyricsDao
    abstract fun trackHistoryDao(): TrackHistoryDao
    abstract fun favoriteTrackDao(): FavoriteTrackDao

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

        @Volatile
        private var instance: BitPerfectDatabase? = null

        fun getInstance(context: Context): BitPerfectDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    BitPerfectDatabase::class.java,
                    DATABASE_NAME,
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
    }
}
