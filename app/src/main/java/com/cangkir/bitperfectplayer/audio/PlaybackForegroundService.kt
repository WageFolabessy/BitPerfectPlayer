package com.cangkir.bitperfectplayer.audio

import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.cangkir.bitperfectplayer.BitPerfectApplication
import com.cangkir.bitperfectplayer.MainActivity
import com.cangkir.bitperfectplayer.R
import com.cangkir.bitperfectplayer.library.AlbumArtworkLoader
import com.cangkir.bitperfectplayer.library.FavoriteStateReducer
import com.cangkir.bitperfectplayer.library.LocalLibraryRepository
import com.cangkir.bitperfectplayer.library.NotificationArtworkFramer
import com.cangkir.bitperfectplayer.library.NotificationArtworkHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground media playback service (`foregroundServiceType="mediaPlayback"`) exposing system
 * `Notification.MediaStyle` and platform `android.media.session.MediaSession` controls
 * (`Play`, `Pause`, `Next`, `Previous`, `Seek`, `Favorite`, `Shuffle`) with uncropped square-framed
 * local offline artwork, concise audiophile format subtitle, and custom monochrome status icon.
 */
class PlaybackForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var playbackController: PlaybackController
    private lateinit var libraryRepository: LocalLibraryRepository
    private lateinit var foregroundPlaybackGate: ForegroundPlaybackGate
    private lateinit var albumArtworkLoader: AlbumArtworkLoader
    private lateinit var notificationManager: NotificationManager
    private lateinit var mediaSession: MediaSession

    private var isForegroundActive = false
    private var artworkTrackUri: String? = null
    private var artworkBitmap: Bitmap? = null
    private var artworkLoadJob: Job? = null

    private var favoriteUris: Set<String> = emptySet()
    private var favoriteFingerprints: Set<String> = emptySet()

    private var lastMetadataKey: MediaMetadataKey? = null
    private var lastNotificationKey: NotificationContentKey? = null
    private var lastPublishedPlaybackState: PublishedPlaybackState? = null

    override fun onCreate() {
        super.onCreate()
        val app = application as BitPerfectApplication
        playbackController = app.container.playbackController
        libraryRepository = app.container.libraryRepository
        foregroundPlaybackGate = app.container.foregroundPlaybackGate
        albumArtworkLoader = app.container.albumArtworkLoader
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val initialLib = libraryRepository.librarySnapshot.value
        favoriteUris = initialLib.favoriteUris
        favoriteFingerprints = initialLib.favoriteFingerprints

        createNotificationChannel()
        initMediaSession()
        observePlaybackState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (val action = intent?.action) {
            ACTION_PLAY_PAUSE -> {
                if (playbackController.state.value.isPlaying) {
                    playbackController.togglePlayPause()
                } else if (ensurePromotedToForeground()) {
                    playbackController.togglePlayPause()
                }
            }
            ACTION_NEXT -> {
                if (ensurePromotedToForeground()) {
                    playbackController.next(userInitiated = true)
                }
            }
            ACTION_PREVIOUS -> {
                if (ensurePromotedToForeground()) {
                    playbackController.previous()
                }
            }
            MediaSessionCustomActions.ACTION_TOGGLE_FAVORITE,
            MediaSessionCustomActions.ACTION_TOGGLE_SHUFFLE,
            -> {
                handleCustomAction(action)
            }
            ACTION_STOP -> {
                playbackController.pause()
                playbackController.persistCurrentPositionNow()
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                ensurePromotedToForeground()
                syncMediaSessionOnly(playbackController.state.value)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        playbackController.persistCurrentPositionNow()
        if (!playbackController.state.value.isPlaying) {
            stopForegroundCompat()
            stopSelf()
        }
    }

    override fun onDestroy() {
        isForegroundActive = false
        foregroundPlaybackGate.markServiceStopped()
        playbackController.persistCurrentPositionNow()
        artworkLoadJob?.cancel()
        serviceScope.cancel()
        runCatching {
            mediaSession.isActive = false
            mediaSession.release()
        }
        super.onDestroy()
    }

    private fun initMediaSession() {
        mediaSession = MediaSession(this, "BitPerfectPlayerMediaSession").apply {
            setCallback(
                object : MediaSession.Callback() {
                    override fun onPlay() {
                        playbackController.play()
                    }

                    override fun onPause() {
                        playbackController.pause()
                    }

                    override fun onSkipToNext() {
                        playbackController.next(userInitiated = true)
                    }

                    override fun onSkipToPrevious() {
                        playbackController.previous()
                    }

                    override fun onSeekTo(pos: Long) {
                        playbackController.seekTo(pos)
                    }

                    override fun onCustomAction(action: String, extras: Bundle?) {
                        handleCustomAction(action)
                    }

                    override fun onStop() {
                        playbackController.pause()
                        stopForegroundCompat()
                        stopSelf()
                    }
                },
            )
            isActive = true
        }
    }

    private fun handleCustomAction(action: String?) {
        when (MediaSessionCustomActions.resolveCommand(action)) {
            MediaCustomActionCommand.TOGGLE_FAVORITE -> {
                val state = playbackController.state.value
                val track = state.activeTrack ?: return
                val trackWithFp = if (track.contentFingerprint.isNullOrBlank() && !state.activeFingerprint.isNullOrBlank()) {
                    track.copy(contentFingerprint = state.activeFingerprint)
                } else {
                    track
                }
                serviceScope.launch {
                    libraryRepository.toggleFavorite(trackWithFp)
                }
            }
            MediaCustomActionCommand.TOGGLE_SHUFFLE -> {
                playbackController.toggleShuffle()
            }
            MediaCustomActionCommand.UNKNOWN -> Unit
        }
    }

    private fun observePlaybackState() {
        serviceScope.launch {
            playbackController.state.collect { state ->
                syncArtworkForTrack(state.activeTrack)
                syncMediaSessionAndNotification(state, playbackController.playbackPositionMs.value)
            }
        }
        serviceScope.launch {
            playbackController.playbackPositionMs.collect { positionMs ->
                syncMediaSessionPlaybackStateOnly(playbackController.state.value, positionMs)
            }
        }
        serviceScope.launch {
            libraryRepository.librarySnapshot
                .map { it.favoriteUris to it.favoriteFingerprints }
                .distinctUntilChanged()
                .collect { (uris, fps) ->
                    favoriteUris = uris
                    favoriteFingerprints = fps
                    syncMediaSessionAndNotification(
                        playbackController.state.value,
                        playbackController.playbackPositionMs.value,
                    )
                }
        }
    }

    private fun syncArtworkForTrack(track: PlaybackTrack?) {
        val nextUri = track?.uriString
        if (nextUri == artworkTrackUri) return

        artworkTrackUri = nextUri
        artworkLoadJob?.cancel()

        if (track == null) {
            artworkBitmap = null
            return
        }

        val cached = albumArtworkLoader.getCachedFromMemory(track, AlbumArtworkLoader.HERO_SIZE_PX)
        if (cached != null && isAlreadyReadyForNotification(cached)) {
            artworkBitmap = cached
            return
        }

        artworkBitmap = null
        artworkLoadJob = serviceScope.launch {
            val raw = cached ?: albumArtworkLoader.loadArtwork(track, AlbumArtworkLoader.HERO_SIZE_PX)
            val framed = withContext(Dispatchers.Default) {
                NotificationArtworkFramer.frameForMediaNotification(raw)
            }
            if (artworkTrackUri == track.uriString && framed !== artworkBitmap) {
                artworkBitmap = framed
                syncMediaSessionAndNotification(
                    playbackController.state.value,
                    playbackController.playbackPositionMs.value,
                )
            }
        }
    }

    private fun isAlreadyReadyForNotification(bitmap: Bitmap): Boolean = !bitmap.isRecycled &&
        bitmap.width > 0 &&
        bitmap.width == bitmap.height &&
        bitmap.width <= NotificationArtworkHelper.MAX_NOTIFICATION_COVER_PX &&
        bitmap.config != Bitmap.Config.HARDWARE

    private fun syncMediaSessionAndNotification(state: PlaybackControllerState, positionMs: Long) {
        val artworkToken = resolveArtworkToken(state.activeTrack)
        syncMediaSessionOnly(state, positionMs, artworkToken)

        val currentNotificationKey = buildNotificationKey(state, artworkToken)
        when (
            MediaSessionUpdatePolicy.resolveNotificationAction(
                isForegroundActive = isForegroundActive,
                isPlaying = state.isPlaying,
                lastKey = lastNotificationKey,
                currentKey = currentNotificationKey,
            )
        ) {
            NotificationDispatchAction.PROMOTE_FOREGROUND -> {
                promoteToForeground(state, currentNotificationKey)
            }
            NotificationDispatchAction.UPDATE_NOTIFICATION -> {
                updateNotification(state, currentNotificationKey)
            }
            NotificationDispatchAction.NONE -> Unit
        }
    }

    private fun syncMediaSessionOnly(
        state: PlaybackControllerState,
        positionMs: Long = playbackController.playbackPositionMs.value,
        artworkToken: Int = resolveArtworkToken(state.activeTrack),
    ) {
        val track = state.activeTrack
        val subtitle = resolveNotificationSubtitle(state)
        val metadataKey = MediaMetadataKey(
            trackUri = track?.uriString,
            durationMs = state.durationMs,
            artworkToken = artworkToken,
            subtitle = subtitle,
        )
        if (MediaSessionUpdatePolicy.shouldUpdateMetadata(lastMetadataKey, metadataKey)) {
            val metadataBuilder = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, track?.title ?: getString(R.string.app_name))
                .putString(MediaMetadata.METADATA_KEY_ARTIST, subtitle)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, subtitle)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, track?.album ?: "")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, state.durationMs)
            val currentArtwork = resolveActiveArtworkBitmap(track)
            if (currentArtwork != null) {
                metadataBuilder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, currentArtwork)
            }
            mediaSession.setMetadata(metadataBuilder.build())
            lastMetadataKey = metadataKey
        }

        syncMediaSessionPlaybackStateOnly(state, positionMs)
    }

    private fun syncMediaSessionPlaybackStateOnly(state: PlaybackControllerState, positionMs: Long) {
        val track = state.activeTrack
        val nowElapsedMs = SystemClock.elapsedRealtime()
        val playbackSpeed = if (state.isPlaying) 1.0f else 0.0f
        val isFavorite = isActiveTrackFavorite(state)
        val shuffleEnabled = state.queue.shuffleEnabled
        if (
            MediaSessionUpdatePolicy.shouldUpdatePlaybackState(
                last = lastPublishedPlaybackState,
                status = state.engineState.status,
                trackUri = track?.uriString,
                errorMessage = state.engineState.errorMessage,
                positionMs = positionMs,
                playbackSpeed = playbackSpeed,
                nowElapsedMs = nowElapsedMs,
                isFavorite = isFavorite,
                shuffleEnabled = shuffleEnabled,
            )
        ) {
            val sessionState = when (state.engineState.status) {
                PlaybackStatus.PLAYING -> PlaybackState.STATE_PLAYING
                PlaybackStatus.PREPARING -> PlaybackState.STATE_BUFFERING
                PlaybackStatus.PAUSED, PlaybackStatus.COMPLETED -> PlaybackState.STATE_PAUSED
                PlaybackStatus.ERROR -> PlaybackState.STATE_ERROR
                PlaybackStatus.IDLE -> PlaybackState.STATE_STOPPED
            }

            val actions = PlaybackState.ACTION_PLAY or
                PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                PlaybackState.ACTION_SEEK_TO or
                PlaybackState.ACTION_STOP

            val playbackStateBuilder = PlaybackState.Builder()
                .setActions(actions)
                .setState(
                    sessionState,
                    positionMs,
                    playbackSpeed,
                    nowElapsedMs,
                )
            val customSpecs = MediaSessionCustomActions.buildCustomActionSpecs(
                isFavorite = isFavorite,
                shuffleEnabled = shuffleEnabled,
                hasActiveTrack = track != null,
            )
            for (spec in customSpecs) {
                playbackStateBuilder.addCustomAction(
                    PlaybackState.CustomAction.Builder(
                        spec.action,
                        spec.label,
                        spec.icon.toDrawableRes(),
                    ).build(),
                )
            }
            state.engineState.errorMessage?.let { err ->
                playbackStateBuilder.setErrorMessage(err)
            }
            mediaSession.setPlaybackState(playbackStateBuilder.build())
            lastPublishedPlaybackState = PublishedPlaybackState(
                status = state.engineState.status,
                trackUri = track?.uriString,
                errorMessage = state.engineState.errorMessage,
                positionMs = positionMs,
                playbackSpeed = playbackSpeed,
                publishedAtElapsedMs = nowElapsedMs,
                isFavorite = isFavorite,
                shuffleEnabled = shuffleEnabled,
            )
        }
    }

    private fun isActiveTrackFavorite(state: PlaybackControllerState): Boolean {
        val track = state.activeTrack ?: return false
        val effectiveTrack = if (track.contentFingerprint.isNullOrBlank() && !state.activeFingerprint.isNullOrBlank()) {
            track.copy(contentFingerprint = state.activeFingerprint)
        } else {
            track
        }
        return FavoriteStateReducer.isFavorited(
            track = effectiveTrack,
            favoriteUris = favoriteUris,
            favoriteFingerprints = favoriteFingerprints,
        )
    }

    private fun resolveNotificationSubtitle(state: PlaybackControllerState): String {
        val track = state.activeTrack
        val diagnosticsMatchTrack = track != null &&
            state.engineState.activeTrackUri?.toString() == track.uriString
        val diag = state.engineState.diagnostics
        return NotificationSubtitleFormatter.format(
            track = track,
            runtimeSampleRateHz = if (diagnosticsMatchTrack) diag.sourceSampleRateHz else 0,
            runtimeBitDepth = if (diagnosticsMatchTrack) diag.sourceBitDepth else null,
        )
    }

    private fun MediaCustomActionIcon.toDrawableRes(): Int = when (this) {
        MediaCustomActionIcon.FAVORITE_FILLED -> R.drawable.ic_notif_favorite_filled
        MediaCustomActionIcon.FAVORITE_OUTLINE -> R.drawable.ic_notif_favorite_outline
        MediaCustomActionIcon.SHUFFLE_ON -> R.drawable.ic_notif_shuffle_on
        MediaCustomActionIcon.SHUFFLE_OFF -> R.drawable.ic_notif_shuffle_off
    }

    private fun resolveActiveArtworkBitmap(track: PlaybackTrack?): Bitmap? {
        val candidate = if (track != null && track.uriString == artworkTrackUri) artworkBitmap else null
        return candidate?.takeUnless { it.isRecycled }
    }

    private fun resolveArtworkToken(track: PlaybackTrack?): Int {
        val bmp = resolveActiveArtworkBitmap(track) ?: return 0
        return System.identityHashCode(bmp)
    }

    private fun buildNotificationKey(state: PlaybackControllerState, artworkToken: Int): NotificationContentKey = NotificationContentKey(
        trackUri = state.activeTrack?.uriString,
        isPlaying = state.isPlaying,
        isBitPerfectMixerActive = state.engineState.diagnostics.isBitPerfectMixerActive,
        artworkToken = artworkToken,
        subtitle = resolveNotificationSubtitle(state),
        isFavorite = isActiveTrackFavorite(state),
        shuffleEnabled = state.queue.shuffleEnabled,
    )

    private fun ensurePromotedToForeground(): Boolean {
        val state = playbackController.state.value
        syncArtworkForTrack(state.activeTrack)
        val key = buildNotificationKey(state, resolveArtworkToken(state.activeTrack))
        return if (!isForegroundActive || lastNotificationKey != key) {
            promoteToForeground(state, key)
        } else {
            foregroundPlaybackGate.markServicePromoted()
            true
        }
    }

    private fun promoteToForeground(state: PlaybackControllerState, notificationKey: NotificationContentKey): Boolean {
        val notification = buildNotification(state)
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            0
        }
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType)
            isForegroundActive = true
            lastNotificationKey = notificationKey
            foregroundPlaybackGate.markServicePromoted()
            true
        } catch (e: SecurityException) {
            handleForegroundStartFailure("SecurityException: ${e.message}", e)
            false
        } catch (e: IllegalStateException) {
            handleForegroundStartFailure(formatIllegalStateStartFailure(e), e)
            false
        }
    }

    private fun handleForegroundStartFailure(reason: String, throwable: Throwable) {
        Log.w(TAG, "Foreground service promotion blocked ($reason)", throwable)
        (application as? BitPerfectApplication)?.container?.diagnosticLogger?.logError("FGS_START_BLOCKED") {
            linkedMapOf("stage" to "startForeground", "reason" to reason, "api" to Build.VERSION.SDK_INT)
        }
        isForegroundActive = false
        foregroundPlaybackGate.markServiceStartFailed(reason)
    }

    private fun updateNotification(state: PlaybackControllerState, notificationKey: NotificationContentKey) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }
        runCatching {
            notificationManager.notify(NOTIFICATION_ID, buildNotification(state))
            lastNotificationKey = notificationKey
        }
    }

    private fun stopForegroundCompat() {
        isForegroundActive = false
        lastNotificationKey = null
        foregroundPlaybackGate.markServiceStopped()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun buildNotification(state: PlaybackControllerState): Notification {
        val track = state.activeTrack
        val title = track?.title ?: getString(R.string.app_name)
        val subtitle = resolveNotificationSubtitle(state)
        val isFavorite = isActiveTrackFavorite(state)
        val shuffleEnabled = state.queue.shuffleEnabled

        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            REQ_CONTENT,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val prevAction = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_notif_prev),
            "Previous",
            createServicePendingIntent(ACTION_PREVIOUS, REQ_PREV),
        ).build()

        val playPauseAction = Notification.Action.Builder(
            Icon.createWithResource(
                this,
                if (state.isPlaying) R.drawable.ic_notif_pause else R.drawable.ic_notif_play,
            ),
            if (state.isPlaying) "Pause" else "Play",
            createServicePendingIntent(ACTION_PLAY_PAUSE, REQ_PLAY_PAUSE),
        ).build()

        val nextAction = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_notif_next),
            "Next",
            createServicePendingIntent(ACTION_NEXT, REQ_NEXT),
        ).build()

        val shuffleIconRes = if (shuffleEnabled) R.drawable.ic_notif_shuffle_on else R.drawable.ic_notif_shuffle_off
        val shuffleAction = Notification.Action.Builder(
            Icon.createWithResource(this, shuffleIconRes),
            if (shuffleEnabled) "Shuffle On" else "Shuffle Off",
            createServicePendingIntent(MediaSessionCustomActions.ACTION_TOGGLE_SHUFFLE, REQ_SHUFFLE),
        ).build()

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bitperfect)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setContentIntent(contentPendingIntent)
            .setDeleteIntent(createServicePendingIntent(ACTION_STOP, REQ_STOP))
            .setOngoing(state.isPlaying)
            .setVisibility(Notification.VISIBILITY_PUBLIC)

        val style = Notification.MediaStyle().setMediaSession(mediaSession.sessionToken)
        if (track != null) {
            val favIconRes = if (isFavorite) R.drawable.ic_notif_favorite_filled else R.drawable.ic_notif_favorite_outline
            val favoriteAction = Notification.Action.Builder(
                Icon.createWithResource(this, favIconRes),
                if (isFavorite) "Remove from Favorites" else "Add to Favorites",
                createServicePendingIntent(MediaSessionCustomActions.ACTION_TOGGLE_FAVORITE, REQ_FAVORITE),
            ).build()
            builder
                .addAction(favoriteAction)
                .addAction(prevAction)
                .addAction(playPauseAction)
                .addAction(nextAction)
                .addAction(shuffleAction)
            style.setShowActionsInCompactView(1, 2, 3)
        } else {
            builder
                .addAction(prevAction)
                .addAction(playPauseAction)
                .addAction(nextAction)
                .addAction(shuffleAction)
            style.setShowActionsInCompactView(0, 1, 2)
        }
        builder.setStyle(style)

        val currentArtwork = resolveActiveArtworkBitmap(track)
        if (currentArtwork != null) {
            builder.setLargeIcon(currentArtwork)
        }

        return builder.build()
    }

    private fun createServicePendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, PlaybackForegroundService::class.java).setAction(action)
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Audio Playback",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Persistent media playback controls for BitPerfectPlayer"
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "PlaybackForegroundSvc"
        private const val CHANNEL_ID = "bitperfect_playback_channel"
        private const val NOTIFICATION_ID = 1001

        private const val ACTION_START = "com.cangkir.bitperfectplayer.action.START"
        private const val ACTION_PLAY_PAUSE = "com.cangkir.bitperfectplayer.action.PLAY_PAUSE"
        private const val ACTION_NEXT = "com.cangkir.bitperfectplayer.action.NEXT"
        private const val ACTION_PREVIOUS = "com.cangkir.bitperfectplayer.action.PREVIOUS"
        private const val ACTION_STOP = "com.cangkir.bitperfectplayer.action.STOP"

        private const val REQ_CONTENT = 10
        private const val REQ_PREV = 11
        private const val REQ_PLAY_PAUSE = 12
        private const val REQ_NEXT = 13
        private const val REQ_STOP = 14
        private const val REQ_FAVORITE = 15
        private const val REQ_SHUFFLE = 16

        fun startService(context: Context, foregroundPlaybackGate: ForegroundPlaybackGate? = null): Boolean {
            val appContext = context.applicationContext
            val appContainer = (appContext as? BitPerfectApplication)?.container
            val gate = foregroundPlaybackGate ?: appContainer?.foregroundPlaybackGate
            val intent = Intent(appContext, PlaybackForegroundService::class.java).setAction(ACTION_START)
            return try {
                ContextCompat.startForegroundService(appContext, intent)
                true
            } catch (e: SecurityException) {
                val reason = "SecurityException: ${e.message}"
                Log.w(TAG, "Blocked from starting PlaybackForegroundService ($reason)", e)
                appContainer?.diagnosticLogger?.logError("FGS_START_BLOCKED") {
                    linkedMapOf("stage" to "startForegroundService", "reason" to reason, "api" to Build.VERSION.SDK_INT)
                }
                gate?.markServiceStartFailed(reason)
                false
            } catch (e: IllegalStateException) {
                val reason = formatIllegalStateStartFailure(e)
                Log.w(TAG, "Blocked from starting PlaybackForegroundService ($reason)", e)
                appContainer?.diagnosticLogger?.logError("FGS_START_BLOCKED") {
                    linkedMapOf("stage" to "startForegroundService", "reason" to reason, "api" to Build.VERSION.SDK_INT)
                }
                gate?.markServiceStartFailed(reason)
                false
            }
        }

        fun stopService(context: Context) {
            val appContext = context.applicationContext
            (appContext as? BitPerfectApplication)?.container?.foregroundPlaybackGate?.markServiceStopped()
            val intent = Intent(appContext, PlaybackForegroundService::class.java).setAction(ACTION_STOP)
            runCatching {
                appContext.stopService(intent)
            }
        }

        private fun formatIllegalStateStartFailure(e: IllegalStateException): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            e is ForegroundServiceStartNotAllowedException
        ) {
            "ForegroundServiceStartNotAllowedException: ${e.message}"
        } else {
            "IllegalStateException: ${e.message}"
        }
    }
}
