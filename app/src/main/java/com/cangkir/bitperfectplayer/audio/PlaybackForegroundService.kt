package com.cangkir.bitperfectplayer.audio

import android.Manifest
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
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.cangkir.bitperfectplayer.BitPerfectApplication
import com.cangkir.bitperfectplayer.MainActivity
import com.cangkir.bitperfectplayer.R
import com.cangkir.bitperfectplayer.library.AlbumArtworkLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground media playback service (`foregroundServiceType="mediaPlayback"`) exposing system
 * `Notification.MediaStyle` and platform `android.media.session.MediaSession` controls
 * (`Play`, `Pause`, `Next`, `Previous`, `Seek`) with local offline artwork and zero network dependencies.
 */
class PlaybackForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var playbackController: PlaybackController
    private lateinit var albumArtworkLoader: AlbumArtworkLoader
    private lateinit var notificationManager: NotificationManager
    private lateinit var mediaSession: MediaSession

    private var isForegroundActive = false
    private var artworkTrackUri: String? = null
    private var artworkBitmap: Bitmap? = null
    private var artworkLoadJob: Job? = null

    private var lastMetadataKey: MediaMetadataKey? = null
    private var lastNotificationKey: NotificationContentKey? = null
    private var lastPublishedPlaybackState: PublishedPlaybackState? = null

    override fun onCreate() {
        super.onCreate()
        val app = application as BitPerfectApplication
        playbackController = app.container.playbackController
        albumArtworkLoader = app.container.albumArtworkLoader
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        createNotificationChannel()
        initMediaSession()
        observePlaybackState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> playbackController.togglePlayPause()
            ACTION_NEXT -> playbackController.next(userInitiated = true)
            ACTION_PREVIOUS -> playbackController.previous()
            ACTION_STOP -> {
                playbackController.pause()
                playbackController.persistCurrentPositionNow()
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                val state = playbackController.state.value
                syncArtworkForTrack(state.activeTrack)
                val key = buildNotificationKey(state, resolveArtworkToken(state.activeTrack))
                if (!isForegroundActive || lastNotificationKey != key) {
                    promoteToForeground(state, key)
                }
                syncMediaSessionOnly(state)
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

    private fun observePlaybackState() {
        serviceScope.launch {
            playbackController.state.collect { state ->
                syncArtworkForTrack(state.activeTrack)
                syncMediaSessionAndNotification(state)
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
        artworkBitmap = cached
        if (cached != null) return

        artworkLoadJob = serviceScope.launch {
            val loaded = albumArtworkLoader.loadArtwork(track, AlbumArtworkLoader.HERO_SIZE_PX)
            if (artworkTrackUri == track.uriString && loaded !== artworkBitmap) {
                artworkBitmap = loaded
                syncMediaSessionAndNotification(playbackController.state.value)
            }
        }
    }

    private fun syncMediaSessionAndNotification(state: PlaybackControllerState) {
        val artworkToken = resolveArtworkToken(state.activeTrack)
        syncMediaSessionOnly(state, artworkToken)

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
        artworkToken: Int = resolveArtworkToken(state.activeTrack),
    ) {
        val track = state.activeTrack
        val metadataKey = MediaMetadataKey(
            trackUri = track?.uriString,
            durationMs = state.durationMs,
            artworkToken = artworkToken,
        )
        if (MediaSessionUpdatePolicy.shouldUpdateMetadata(lastMetadataKey, metadataKey)) {
            val metadataBuilder = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, track?.title ?: getString(R.string.app_name))
                .putString(MediaMetadata.METADATA_KEY_ARTIST, track?.artist ?: "Offline Bit-Perfect Audio")
                .putString(MediaMetadata.METADATA_KEY_ALBUM, track?.album ?: "")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, state.durationMs)
            val currentArtwork = resolveActiveArtworkBitmap(track)
            if (currentArtwork != null) {
                metadataBuilder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, currentArtwork)
            }
            mediaSession.setMetadata(metadataBuilder.build())
            lastMetadataKey = metadataKey
        }

        val nowElapsedMs = SystemClock.elapsedRealtime()
        val playbackSpeed = if (state.isPlaying) 1.0f else 0.0f
        if (
            MediaSessionUpdatePolicy.shouldUpdatePlaybackState(
                last = lastPublishedPlaybackState,
                status = state.engineState.status,
                trackUri = track?.uriString,
                errorMessage = state.engineState.errorMessage,
                positionMs = state.currentPositionMs,
                playbackSpeed = playbackSpeed,
                nowElapsedMs = nowElapsedMs,
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
                    state.currentPositionMs,
                    playbackSpeed,
                    nowElapsedMs,
                )
            state.engineState.errorMessage?.let { err ->
                playbackStateBuilder.setErrorMessage(err)
            }
            mediaSession.setPlaybackState(playbackStateBuilder.build())
            lastPublishedPlaybackState = PublishedPlaybackState(
                status = state.engineState.status,
                trackUri = track?.uriString,
                errorMessage = state.engineState.errorMessage,
                positionMs = state.currentPositionMs,
                playbackSpeed = playbackSpeed,
                publishedAtElapsedMs = nowElapsedMs,
            )
        }
    }

    private fun resolveActiveArtworkBitmap(track: PlaybackTrack?): Bitmap? {
        val candidate = if (track != null && track.uriString == artworkTrackUri) artworkBitmap else null
        return candidate?.takeUnless { it.isRecycled }
    }

    private fun resolveArtworkToken(track: PlaybackTrack?): Int {
        val bmp = resolveActiveArtworkBitmap(track) ?: return 0
        return System.identityHashCode(bmp)
    }

    private fun buildNotificationKey(
        state: PlaybackControllerState,
        artworkToken: Int,
    ): NotificationContentKey = NotificationContentKey(
        trackUri = state.activeTrack?.uriString,
        isPlaying = state.isPlaying,
        isBitPerfectMixerActive = state.engineState.diagnostics.isBitPerfectMixerActive,
        artworkToken = artworkToken,
    )

    private fun promoteToForeground(
        state: PlaybackControllerState,
        notificationKey: NotificationContentKey,
    ) {
        val notification = buildNotification(state)
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            0
        }
        runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType)
            isForegroundActive = true
            lastNotificationKey = notificationKey
        }
    }

    private fun updateNotification(
        state: PlaybackControllerState,
        notificationKey: NotificationContentKey,
    ) {
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
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun buildNotification(state: PlaybackControllerState): Notification {
        val track = state.activeTrack
        val title = track?.title ?: getString(R.string.app_name)
        val subtitle = buildString {
            append(track?.artist ?: "Ready")
            val modeLabel = if (state.engineState.diagnostics.isBitPerfectMixerActive) {
                " • Bit-Perfect USB"
            } else {
                " • Safe/Clean Native"
            }
            append(modeLabel)
        }

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

        val style = Notification.MediaStyle()
            .setMediaSession(mediaSession.sessionToken)
            .setShowActionsInCompactView(0, 1, 2)

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif_music)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setContentIntent(contentPendingIntent)
            .setDeleteIntent(createServicePendingIntent(ACTION_STOP, REQ_STOP))
            .setOngoing(state.isPlaying)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(prevAction)
            .addAction(playPauseAction)
            .addAction(nextAction)
            .setStyle(style)

        val currentArtwork = if (track != null && track.uriString == artworkTrackUri) artworkBitmap else null
        if (currentArtwork != null && !currentArtwork.isRecycled) {
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

        fun startService(context: Context) {
            val intent = Intent(context, PlaybackForegroundService::class.java).setAction(ACTION_START)
            runCatching {
                ContextCompat.startForegroundService(context.applicationContext, intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, PlaybackForegroundService::class.java).setAction(ACTION_STOP)
            runCatching {
                context.applicationContext.stopService(intent)
            }
        }
    }
}
