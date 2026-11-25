package com.cangkir.bitperfectplayer.audio

import com.cangkir.bitperfectplayer.library.LibrarySnapshot
import com.cangkir.bitperfectplayer.library.PlaylistsUiSnapshot
import kotlinx.coroutines.flow.StateFlow

/**
 * Queue and transport control boundary consumed by UI view-models and coordinators.
 * Split from [PlaybackAudioControl] to keep interface surfaces narrow and testable without Android deps.
 */
interface PlaybackTransportControl {
    val state: StateFlow<PlaybackControllerState>
    val playbackPositionMs: StateFlow<Long>
    val sleepTimerState: StateFlow<SleepTimerState>

    fun restoreLastSessionIfNeeded(library: LibrarySnapshot, playlists: PlaylistsUiSnapshot)

    fun playQueue(
        tracks: List<PlaybackTrack>,
        startIndex: Int = 0,
        startPositionMs: Long = 0L,
        autoPlay: Boolean = true,
        context: QueueContext = QueueContext.allSongs(),
    )

    fun playQueueShuffled(tracks: List<PlaybackTrack>, context: QueueContext = QueueContext.allSongs())

    fun playTrackAt(queueIndex: Int)

    fun enqueue(track: PlaybackTrack)

    fun enqueueNext(track: PlaybackTrack)

    fun enqueueAll(tracks: List<PlaybackTrack>, fallbackContext: QueueContext? = null)

    fun enqueueAllNext(tracks: List<PlaybackTrack>, fallbackContext: QueueContext? = null)

    fun removeQueueItem(activeIndex: Int)

    fun moveQueueItem(fromIndex: Int, toIndex: Int)

    fun togglePlayPause()

    fun next(userInitiated: Boolean = true)

    fun previous()

    fun seekTo(positionMs: Long)

    fun toggleShuffle()

    fun cycleRepeatMode()

    fun dismissActiveSession(source: String = "MINI_PLAYER_SWIPE")
}

/**
 * DSP, hearing safety, sleep timer, scratch, and spectrum tap boundary for audio playback.
 */
interface PlaybackAudioControl {
    fun beginTurntableScratch(): Boolean

    fun updateTurntableScratchRate(playbackRate: Float)

    fun endTurntableScratch(fallbackTargetMs: Long? = null)

    fun setGaplessEnabled(enabled: Boolean)

    fun setReplayGainMode(mode: ReplayGainMode)

    fun setReplayGainPreampDb(preampDb: Float)

    fun setReplayGainPreventClipping(preventClipping: Boolean)

    fun setSpectrumTapActive(active: Boolean)

    fun sampleCurrentSpectrumWindow(outMonoSamples: FloatArray): Int

    fun setSleepTimerPreset(preset: SleepTimerPreset)

    fun requestBitPerfectMode(): SafetyCommand

    fun confirmBitPerfectWarning(): SafetyCommand

    fun dismissSafetyWarning(): SafetyCommand

    fun switchToSafeMode(): SafetyCommand

    fun clearAutoPauseNotice()
}
