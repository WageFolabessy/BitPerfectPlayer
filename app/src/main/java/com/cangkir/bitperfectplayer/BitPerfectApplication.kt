package com.cangkir.bitperfectplayer

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import com.cangkir.bitperfectplayer.audio.AudioMetadataReader
import com.cangkir.bitperfectplayer.audio.ForegroundPlaybackGate
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

    val foregroundPlaybackGate: ForegroundPlaybackGate = ForegroundPlaybackGate()
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
            foregroundPlaybackGate = foregroundPlaybackGate,
        )
    }
}

class BitPerfectApplication : Application() {
    lateinit var container: AppContainer
        private set

    private var startedActivityCount: Int = 0

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        registerActivityLifecycleCallbacks(
            object : ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) {
                    startedActivityCount += 1
                    container.foregroundPlaybackGate.setAppInForeground(startedActivityCount > 0)
                }

                override fun onActivityStopped(activity: Activity) {
                    startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
                    container.foregroundPlaybackGate.setAppInForeground(startedActivityCount > 0)
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

                override fun onActivityResumed(activity: Activity) = Unit

                override fun onActivityPaused(activity: Activity) = Unit

                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
    }
}
