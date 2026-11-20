package com.cangkir.bitperfectplayer.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        LyricsEntity::class,
        TrackHistoryEntity::class,
        FavoriteTrackEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class BitPerfectDatabase : RoomDatabase() {
    abstract fun lyricsDao(): LyricsDao
    abstract fun trackHistoryDao(): TrackHistoryDao
    abstract fun favoriteTrackDao(): FavoriteTrackDao

    companion object {
        private const val DATABASE_NAME = "bitperfect_player.db"

        @Volatile
        private var instance: BitPerfectDatabase? = null

        fun getInstance(context: Context): BitPerfectDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    BitPerfectDatabase::class.java,
                    DATABASE_NAME,
                ).build().also { instance = it }
            }
    }
}
