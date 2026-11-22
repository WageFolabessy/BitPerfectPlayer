package com.cangkir.bitperfectplayer

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import com.cangkir.bitperfectplayer.audio.AudioMetadataReader
import com.cangkir.bitperfectplayer.audio.ForegroundPlaybackGate
import com.cangkir.bitperfectplayer.audio.PlaybackController
import com.cangkir.bitperfectplayer.data.BitPerfectDatabase
import com.cangkir.bitperfectplayer.data.LocalBackupRepository
import com.cangkir.bitperfectplayer.data.LocalPersistenceRepository
import com.cangkir.bitperfectplayer.data.UserPreferences
import com.cangkir.bitperfectplayer.data.UserPreferencesRepository
import com.cangkir.bitperfectplayer.diagnostics.DiagnosticCrashHandler
import com.cangkir.bitperfectplayer.diagnostics.DiagnosticLogExporter
import com.cangkir.bitperfectplayer.diagnostics.LocalDiagnosticLogger
import com.cangkir.bitperfectplayer.library.AlbumArtworkLoader
import com.cangkir.bitperfectplayer.library.ArtistPhotoStore
import com.cangkir.bitperfectplayer.library.LocalLibraryRepository
import com.cangkir.bitperfectplayer.library.PlaylistRepository
import com.cangkir.bitperfectplayer.library.TrackInfoLoader
import com.cangkir.bitperfectplayer.lyrics.AndroidContentFingerprintProvider
import com.cangkir.bitperfectplayer.lyrics.LyricsRepository
import com.cangkir.bitperfectplayer.ui.theme.CoverThemeColorCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Simple manual dependency container scoped to [BitPerfectApplication] (zero reflection/network).
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val foregroundPlaybackGate: ForegroundPlaybackGate = ForegroundPlaybackGate()
    val diagnosticLogger: LocalDiagnosticLogger by lazy { LocalDiagnosticLogger(appContext) }
    val diagnosticLogExporter: DiagnosticLogExporter by lazy {
        DiagnosticLogExporter(appContext, diagnosticLogger)
    }
    val database: BitPerfectDatabase by lazy { BitPerfectDatabase.getInstance(appContext) }
    val persistenceRepository: LocalPersistenceRepository by lazy { LocalPersistenceRepository(database) }
    val preferencesRepository: UserPreferencesRepository by lazy { UserPreferencesRepository.fromContext(appContext) }
    val backupRepository: LocalBackupRepository by lazy {
        LocalBackupRepository(appContext, persistenceRepository, preferencesRepository)
    }
    val fingerprintProvider: AndroidContentFingerprintProvider by lazy {
        AndroidContentFingerprintProvider(appContext.contentResolver)
    }
    val metadataReader: AudioMetadataReader by lazy { AudioMetadataReader(appContext) }
    val albumArtworkLoader: AlbumArtworkLoader by lazy { AlbumArtworkLoader(appContext) }
    val artistPhotoStore: ArtistPhotoStore by lazy { ArtistPhotoStore(appContext) }
    val coverThemeColorCache: CoverThemeColorCache by lazy { CoverThemeColorCache(albumArtworkLoader) }
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
    private val playbackControllerLazy: Lazy<PlaybackController> = lazy {
        PlaybackController(
            context = appContext,
            preferencesRepository = preferencesRepository,
            persistenceRepository = persistenceRepository,
            fingerprintProvider = fingerprintProvider,
            foregroundPlaybackGate = foregroundPlaybackGate,
            diagnosticLogger = diagnosticLogger,
        )
    }
    val playbackController: PlaybackController
        get() = playbackControllerLazy.value

    fun buildDeviceSummaryFields(prefs: UserPreferences): Map<String, Any?> {
        val currentAudioMode = if (playbackControllerLazy.isInitialized()) {
            playbackControllerLazy.value.state.value.engineState.safetyState.mode.name
        } else {
            com.cangkir.bitperfectplayer.audio.AudioOutputMode.SAFE_VOLUME_MODE.name
        }
        return linkedMapOf(
            "audioMode" to currentAudioMode,
            "gaplessEnabled" to prefs.gaplessEnabled,
            "replayGainMode" to prefs.replayGainMode.name,
            "replayGainPreampDb" to prefs.replayGainPreampDb,
            "replayGainPreventClipping" to prefs.replayGainPreventClipping,
            "defaultHomepage" to prefs.defaultHomepage.name,
            "artworkStyle" to prefs.nowPlayingArtworkStyle.name,
            "spectrumEnabled" to prefs.spectrumVisualizerEnabled,
            "spectrumStyle" to prefs.spectrumColorStyle.name,
            "spectrumSparks" to prefs.spectrumSparksEnabled,
            "dynamicCoverTheme" to prefs.dynamicCoverThemeEnabled,
        )
    }
}

class BitPerfectApplication : Application() {
    lateinit var container: AppContainer
        private set

    val processCreateElapsedMs: Long = SystemClock.elapsedRealtime()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var startedActivityCount: Int = 0

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        DiagnosticCrashHandler.install(container.diagnosticLogger)

        // Keep opt-in diagnostic logging flag and device_summary.txt synced with DataStore off-main.
        // Zero map allocations and zero PlaybackController touch while disabled (default OFF).
        appScope.launch {
            container.diagnosticLogger.refreshStorageStatsSync()
            container.preferencesRepository.preferencesFlow
                .map { prefs ->
                    if (!prefs.diagnosticLoggingEnabled) {
                        false to emptyMap()
                    } else {
                        true to container.buildDeviceSummaryFields(prefs)
                    }
                }
                .distinctUntilChanged()
                .collect { (enabled, summaryFields) ->
                    container.diagnosticLogger.setEnabled(enabled)
                    if (enabled) {
                        container.diagnosticLogger.writeDeviceSummary(summaryFields)
                    }
                }
        }

        registerActivityLifecycleCallbacks(
            object : ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) {
                    startedActivityCount += 1
                    container.foregroundPlaybackGate.setAppInForeground(startedActivityCount > 0)
                }

                override fun onActivityStopped(activity: Activity) {
                    startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
                    container.foregroundPlaybackGate.setAppInForeground(startedActivityCount > 0)
                    if (startedActivityCount == 0) {
                        container.diagnosticLogger.requestFlush()
                    }
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
