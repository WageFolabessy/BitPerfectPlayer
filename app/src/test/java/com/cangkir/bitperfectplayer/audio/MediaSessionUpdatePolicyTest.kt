package com.cangkir.bitperfectplayer.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSessionUpdatePolicyTest {

    @Test
    fun steadyPlaybackAt20Hz_emitsZeroRedundantMetadataPlaybackStateOrNotificationUpdates() {
        val uri = "content://media/external/audio/media/42"
        val initialMeta = MediaMetadataKey(trackUri = uri, durationMs = 240_000L, artworkToken = 12345)
        val initialNotif = NotificationContentKey(
            trackUri = uri,
            isPlaying = true,
            isBitPerfectMixerActive = false,
            artworkToken = 12345,
        )

        var lastMeta: MediaMetadataKey? = null
        var lastNotif: NotificationContentKey? = null
        var lastState: PublishedPlaybackState? = null
        var isForeground = false

        var metadataCalls = 0
        var playbackStateCalls = 0
        var foregroundCalls = 0
        var notificationUpdateCalls = 0

        // Simulate 200 ticks at 50 ms (20 Hz = 10 seconds of smooth playback)
        val baseElapsedMs = 1_000_000L
        for (tick in 0 until 200) {
            val nowElapsedMs = baseElapsedMs + tick * 50L
            val positionMs = 10_000L + tick * 50L

            if (MediaSessionUpdatePolicy.shouldUpdateMetadata(lastMeta, initialMeta)) {
                metadataCalls++
                lastMeta = initialMeta
            }

            if (
                MediaSessionUpdatePolicy.shouldUpdatePlaybackState(
                    last = lastState,
                    status = PlaybackStatus.PLAYING,
                    trackUri = uri,
                    errorMessage = null,
                    positionMs = positionMs,
                    playbackSpeed = 1.0f,
                    nowElapsedMs = nowElapsedMs,
                )
            ) {
                playbackStateCalls++
                lastState = PublishedPlaybackState(
                    status = PlaybackStatus.PLAYING,
                    trackUri = uri,
                    errorMessage = null,
                    positionMs = positionMs,
                    playbackSpeed = 1.0f,
                    publishedAtElapsedMs = nowElapsedMs,
                )
            }

            when (
                MediaSessionUpdatePolicy.resolveNotificationAction(
                    isForegroundActive = isForeground,
                    isPlaying = true,
                    lastKey = lastNotif,
                    currentKey = initialNotif,
                )
            ) {
                NotificationDispatchAction.PROMOTE_FOREGROUND -> {
                    foregroundCalls++
                    isForeground = true
                    lastNotif = initialNotif
                }
                NotificationDispatchAction.UPDATE_NOTIFICATION -> {
                    notificationUpdateCalls++
                    lastNotif = initialNotif
                }
                NotificationDispatchAction.NONE -> Unit
            }
        }

        // Only the very first emission should trigger IPC; all 199 subsequent 50 ms ticks must be silent!
        assertEquals(1, metadataCalls)
        assertEquals(1, playbackStateCalls)
        assertEquals(1, foregroundCalls)
        assertEquals(0, notificationUpdateCalls)
    }

    @Test
    fun metadataUpdate_triggersOnlyWhenTrackDurationOrArtworkChanges() {
        val base = MediaMetadataKey("content://a", 180_000L, artworkToken = 0)
        assertTrue(MediaSessionUpdatePolicy.shouldUpdateMetadata(null, base))
        assertFalse(MediaSessionUpdatePolicy.shouldUpdateMetadata(base, base))
        assertTrue(MediaSessionUpdatePolicy.shouldUpdateMetadata(base, base.copy(trackUri = "content://b")))
        assertTrue(MediaSessionUpdatePolicy.shouldUpdateMetadata(base, base.copy(durationMs = 185_000L)))
        assertTrue(MediaSessionUpdatePolicy.shouldUpdateMetadata(base, base.copy(artworkToken = 999)))
    }

    @Test
    fun playbackStateUpdate_triggersOnSeekJumpStatusTrackOrErrorChange_ignoresMinorJitter() {
        val last = PublishedPlaybackState(
            status = PlaybackStatus.PLAYING,
            trackUri = "content://a",
            errorMessage = null,
            positionMs = 30_000L,
            playbackSpeed = 1.0f,
            publishedAtElapsedMs = 100_000L,
        )

        // 2 seconds later, expected pos = 32,000 ms; 32,400 ms (+400 ms jitter) must NOT update
        assertFalse(
            MediaSessionUpdatePolicy.shouldUpdatePlaybackState(
                last = last,
                status = PlaybackStatus.PLAYING,
                trackUri = "content://a",
                errorMessage = null,
                positionMs = 32_400L,
                playbackSpeed = 1.0f,
                nowElapsedMs = 102_000L,
            ),
        )

        // Forward seek jump > 1500 ms triggers immediate update
        assertTrue(
            MediaSessionUpdatePolicy.shouldUpdatePlaybackState(
                last = last,
                status = PlaybackStatus.PLAYING,
                trackUri = "content://a",
                errorMessage = null,
                positionMs = 45_000L,
                playbackSpeed = 1.0f,
                nowElapsedMs = 102_000L,
            ),
        )

        // Backward seek jump > 1500 ms triggers immediate update
        assertTrue(
            MediaSessionUpdatePolicy.shouldUpdatePlaybackState(
                last = last,
                status = PlaybackStatus.PLAYING,
                trackUri = "content://a",
                errorMessage = null,
                positionMs = 10_000L,
                playbackSpeed = 1.0f,
                nowElapsedMs = 102_000L,
            ),
        )

        // Pause transition triggers immediate update
        assertTrue(
            MediaSessionUpdatePolicy.shouldUpdatePlaybackState(
                last = last,
                status = PlaybackStatus.PAUSED,
                trackUri = "content://a",
                errorMessage = null,
                positionMs = 32_000L,
                playbackSpeed = 0.0f,
                nowElapsedMs = 102_000L,
            ),
        )

        // Error message triggers immediate update
        assertTrue(
            MediaSessionUpdatePolicy.shouldUpdatePlaybackState(
                last = last,
                status = PlaybackStatus.PLAYING,
                trackUri = "content://a",
                errorMessage = "Codec error",
                positionMs = 32_000L,
                playbackSpeed = 1.0f,
                nowElapsedMs = 102_000L,
            ),
        )
    }

    @Test
    fun notificationDispatch_transitionsCleanlyBetweenForegroundPlayPauseAndModeChanges() {
        val playingSafe = NotificationContentKey("content://a", isPlaying = true, isBitPerfectMixerActive = false, artworkToken = 1)
        val playingBitPerfect = playingSafe.copy(isBitPerfectMixerActive = true)
        val pausedSafe = playingSafe.copy(isPlaying = false)

        // Initial play enters foreground
        assertEquals(
            NotificationDispatchAction.PROMOTE_FOREGROUND,
            MediaSessionUpdatePolicy.resolveNotificationAction(false, true, null, playingSafe),
        )
        // Steady playing does nothing
        assertEquals(
            NotificationDispatchAction.NONE,
            MediaSessionUpdatePolicy.resolveNotificationAction(true, true, playingSafe, playingSafe),
        )
        // Engaging Bit-Perfect updates foreground notification
        assertEquals(
            NotificationDispatchAction.PROMOTE_FOREGROUND,
            MediaSessionUpdatePolicy.resolveNotificationAction(true, true, playingSafe, playingBitPerfect),
        )
        // Pausing while foreground updates notification once
        assertEquals(
            NotificationDispatchAction.UPDATE_NOTIFICATION,
            MediaSessionUpdatePolicy.resolveNotificationAction(true, false, playingSafe, pausedSafe),
        )
        // Repeated paused ticks do nothing
        assertEquals(
            NotificationDispatchAction.NONE,
            MediaSessionUpdatePolicy.resolveNotificationAction(true, false, pausedSafe, pausedSafe),
        )
        // Paused while NOT foreground does nothing
        assertEquals(
            NotificationDispatchAction.NONE,
            MediaSessionUpdatePolicy.resolveNotificationAction(false, false, null, pausedSafe),
        )
    }
}
