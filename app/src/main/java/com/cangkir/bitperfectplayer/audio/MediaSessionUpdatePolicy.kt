package com.cangkir.bitperfectplayer.audio

import kotlin.math.abs

/**
 * Pure-Kotlin snapshot keys and throttling/diff policy (zero `android.*` imports) governing
 * [PlaybackForegroundService] `MediaSession` and `Notification` updates so 20 Hz engine position
 * ticks never spam Binder IPC or repeatedly serialize album-art bitmaps.
 */
data class MediaMetadataKey(val trackUri: String?, val durationMs: Long, val artworkToken: Int, val subtitle: String = "")

data class NotificationContentKey(
    val trackUri: String?,
    val isPlaying: Boolean,
    val isBitPerfectMixerActive: Boolean,
    val artworkToken: Int,
    val subtitle: String = "",
    val isFavorite: Boolean = false,
    val shuffleEnabled: Boolean = false,
)

data class PublishedPlaybackState(
    val status: PlaybackStatus,
    val trackUri: String?,
    val errorMessage: String?,
    val positionMs: Long,
    val playbackSpeed: Float,
    val publishedAtElapsedMs: Long,
    val isFavorite: Boolean = false,
    val shuffleEnabled: Boolean = false,
)

enum class NotificationDispatchAction {
    NONE,
    PROMOTE_FOREGROUND,
    UPDATE_NOTIFICATION,
}

object MediaSessionUpdatePolicy {

    const val DEFAULT_SEEK_JUMP_THRESHOLD_MS: Long = 1_500L

    /**
     * Metadata (including heavy album artwork bitmaps) is pushed over Binder ONLY when track URI,
     * duration, formatted audiophile subtitle, or resolved artwork identity genuinely changes.
     */
    fun shouldUpdateMetadata(last: MediaMetadataKey?, current: MediaMetadataKey): Boolean = last != current

    /**
     * Determines whether a foreground promotion or notification update is genuinely required.
     * Steady-state 20 Hz position ticks return [NotificationDispatchAction.NONE].
     */
    fun resolveNotificationAction(
        isForegroundActive: Boolean,
        isPlaying: Boolean,
        lastKey: NotificationContentKey?,
        currentKey: NotificationContentKey,
    ): NotificationDispatchAction = if (isPlaying) {
        if (!isForegroundActive || lastKey != currentKey) {
            NotificationDispatchAction.PROMOTE_FOREGROUND
        } else {
            NotificationDispatchAction.NONE
        }
    } else {
        if (isForegroundActive && lastKey != currentKey) {
            NotificationDispatchAction.UPDATE_NOTIFICATION
        } else {
            NotificationDispatchAction.NONE
        }
    }

    /**
     * Publishes `MediaSession.setPlaybackState(...)` ONLY when playback status, active track,
     * error message, playback speed, custom action state (`isFavorite` / `shuffleEnabled`), or
     * seek/drift position delta exceeds [seekJumpThresholdMs] from the expected elapsed position.
     */
    fun shouldUpdatePlaybackState(
        last: PublishedPlaybackState?,
        status: PlaybackStatus,
        trackUri: String?,
        errorMessage: String?,
        positionMs: Long,
        playbackSpeed: Float,
        nowElapsedMs: Long,
        isFavorite: Boolean = false,
        shuffleEnabled: Boolean = false,
        seekJumpThresholdMs: Long = DEFAULT_SEEK_JUMP_THRESHOLD_MS,
    ): Boolean {
        if (last == null) return true
        val statusOrTrackChanged = last.status != status || last.trackUri != trackUri
        val errorOrSpeedChanged = last.errorMessage != errorMessage || last.playbackSpeed != playbackSpeed
        val customActionChanged = last.isFavorite != isFavorite || last.shuffleEnabled != shuffleEnabled
        if (statusOrTrackChanged || errorOrSpeedChanged || customActionChanged) {
            return true
        }
        val elapsedSinceLastMs = (nowElapsedMs - last.publishedAtElapsedMs).coerceAtLeast(0L)
        val expectedPositionMs = last.positionMs + (elapsedSinceLastMs * last.playbackSpeed).toLong()
        val deltaMs = abs(positionMs - expectedPositionMs)
        return deltaMs > seekJumpThresholdMs
    }
}
