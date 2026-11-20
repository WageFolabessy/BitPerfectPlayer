package com.cangkir.bitperfectplayer

import android.app.Application
import android.content.Context
import com.cangkir.bitperfectplayer.audio.AudioMetadataReader
import com.cangkir.bitperfectplayer.audio.PlaybackController
import com.cangkir.bitperfectplayer.data.BitPerfectDatabase
import com.cangkir.bitperfectplayer.data.LocalBackupRepository
import com.cangkir.bitperfectplayer.data.LocalPersistenceRepository
import com.cangkir.bitperfectplayer.data.UserPreferencesRepository
import com.cangkir.bitperfectplayer.library.AlbumArtworkLoader
import com.cangkir.bitperfectplayer.library.LocalLibraryRepository
import com.cangkir.bitperfectplayer.library.PlaylistRepository
import com.cangkir.bitperfectplayer.library.TrackInfoLoader
import com.cangkir.bitperfectplayer.lyrics.AndroidContentFingerprintProvider
import com.cangkir.bitperfectplayer.lyrics.LyricsRepository

/**
 * Simple manual dependency container scoped to [BitPerfectApplication] (zero reflection/network).
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val database: BitPerfectDatabase by lazy { BitPerfectDatabase.getInstance(appContext) }
    val persistenceRepository: LocalPersistenceRepository by lazy { LocalPersistenceRepository(database) }
    val preferencesRepository: UserPreferencesRepository by lazy { UserPreferencesRepository.fromContext(appContext) }
    val backupRepository: LocalBackupRepository by lazy {
        LocalBackupRepository(appContext, persistenceRepository)
    }
    val fingerprintProvider: AndroidContentFingerprintProvider by lazy {
        AndroidContentFingerprintProvider(appContext.contentResolver)
    }
    val metadataReader: AudioMetadataReader by lazy { AudioMetadataReader(appContext) }
    val albumArtworkLoader: AlbumArtworkLoader by lazy { AlbumArtworkLoader(appContext) }
    val trackInfoLoader: TrackInfoLoader by lazy {
        TrackInfoLoader(
            context = appContext,
            persistenceRepository = persistenceRepository,
            fingerprintProvider = fingerprintProvider,
        )
    }
    val libraryRepository: LocalLibraryRepository by lazy {
        LocalLibraryRepository(
            context = appContext,
            metadataReader = metadataReader,
            preferencesRepository = preferencesRepository,
            persistenceRepository = persistenceRepository,
            fingerprintProvider = fingerprintProvider,
        )
    }
    val playlistRepository: PlaylistRepository by lazy {
        PlaylistRepository(
            context = appContext,
            playlistDao = database.playlistDao(),
            trackStatsDao = database.trackStatsDao(),
            libraryRepository = libraryRepository,
            preferencesRepository = preferencesRepository,
            fingerprintProvider = fingerprintProvider,
        )
    }
    val lyricsRepository: LyricsRepository by lazy {
        LyricsRepository(
            context = appContext,
            persistenceRepository = persistenceRepository,
            preferencesRepository = preferencesRepository,
            fingerprintProvider = fingerprintProvider,
        )
    }
    val playbackController: PlaybackController by lazy {
        PlaybackController(
            context = appContext,
            preferencesRepository = preferencesRepository,
            persistenceRepository = persistenceRepository,
            fingerprintProvider = fingerprintProvider,
        )
    }
}

class BitPerfectApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
