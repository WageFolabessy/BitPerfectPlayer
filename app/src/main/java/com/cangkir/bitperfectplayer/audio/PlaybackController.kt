package com.cangkir.bitperfectplayer.audio

import android.content.Context
import android.net.Uri
import com.cangkir.bitperfectplayer.data.AudioSafetyModePreference
import com.cangkir.bitperfectplayer.data.LocalPersistenceRepository
import com.cangkir.bitperfectplayer.data.RepeatModePreference
import com.cangkir.bitperfectplayer.data.TrackHistoryRecord
import com.cangkir.bitperfectplayer.data.UserPreferencesRepository
import com.cangkir.bitperfectplayer.lyrics.AndroidContentFingerprintProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Combined UI-observable playback state unifying queue state, active track, resolved content
 * fingerprint, hardware-clocked playback engine state, safety state, and 6-stage diagnostics.
 */
data class PlaybackControllerState(
    val queue: QueueSnapshot<PlaybackTrack> = QueueSnapshot(),
    val activeTrack: PlaybackTrack? = null,
    val activeFingerprint: String? = null,
    val engineState: EnginePlaybackState = EnginePlaybackState(),
) {
    val isPlaying: Boolean
        get() = engineState.isPlaying

    val currentPositionMs: Long
        get() = engineState.currentPositionMs

    val durationMs: Long
        get() = engineState.durationMs.takeIf { it > 0L } ?: activeTrack?.durationMs ?: 0L
}

/**
 * Application-scoped playback coordinator bridging [PlaybackQueueManager], [AudioOutputEngine],
 * [PlaybackForegroundService], [UserPreferencesRepository], [LocalPersistenceRepository], and
 * [AndroidContentFingerprintProvider].
 */
class PlaybackController(
    context: Context,
    private val preferencesRepository: UserPreferencesRepository,
    private val persistenceRepository: LocalPersistenceRepository,
    private val fingerprintProvider: AndroidContentFingerprintProvider,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val queueManager = PlaybackQueueManager<PlaybackTrack>()
    private val safetyController = AudioSafetyController()

    private val engine = AudioOutputEngine(
        context = appContext,
        safetyController = safetyController,
        onTrackCompleted = { handleTrackCompleted() },
    )

    private val _state = MutableStateFlow(
        PlaybackControllerState(engineState = engine.state.value),
    )
    val state: StateFlow<PlaybackControllerState> = _state.asStateFlow()

    private var positionPersistenceJob: Job? = null
    private var fingerprintJob: Job? = null
    private var hasRestoredInitialSession = false

    init {
        // Restore persisted shuffle/repeat preferences (hearing safety mode always boots in Safe Mode!).
        scope.launch {
            val prefs = runCatching { preferencesRepository.getSnapshot() }.getOrNull() ?: return@launch
            queueManager.setShuffle(prefs.shuffleEnabled)
            queueManager.setRepeatMode(prefs.repeatMode.toQueueRepeatMode())
            _state.update { it.copy(queue = queueManager.snapshot()) }
        }

        // Mirror engine state updates and persist playback position periodically while playing.
        scope.launch {
            engine.state.collect { engineState ->
                _state.update { it.copy(engineState = engineState) }
                if (engineState.isPlaying) {
                    ensurePositionPersistenceRunning()
                } else {
                    stopPositionPersistenceAndSaveNow()
                }
            }
        }
    }

    fun playQueue(tracks: List<PlaybackTrack>, startIndex: Int = 0, startPositionMs: Long = 0L, autoPlay: Boolean = true) {
        if (tracks.isEmpty()) return
        val snapshot = queueManager.setQueue(tracks, startIndex)
        val active = snapshot.currentItem ?: return
        _state.update { it.copy(queue = snapshot, activeTrack = active) }
        loadActiveTrack(active, startPositionMs = startPositionMs, autoPlay = autoPlay)
    }

    fun playTrackAt(queueIndex: Int) {
        val selected = queueManager.selectIndex(queueIndex) ?: return
        _state.update { it.copy(queue = queueManager.snapshot(), activeTrack = selected) }
        loadActiveTrack(selected, startPositionMs = 0L, autoPlay = true)
    }

    fun enqueue(track: PlaybackTrack) {
        val wasEmpty = queueManager.isEmpty()
        val snapshot = queueManager.enqueue(track)
        _state.update {
            it.copy(
                queue = snapshot,
                activeTrack = snapshot.currentItem,
            )
        }
        if (wasEmpty && snapshot.currentItem != null) {
            loadActiveTrack(snapshot.currentItem!!, startPositionMs = 0L, autoPlay = false)
        }
    }

    fun enqueueNext(track: PlaybackTrack) {
        val wasEmpty = queueManager.isEmpty()
        val snapshot = queueManager.enqueueNext(track)
        _state.update {
            it.copy(
                queue = snapshot,
                activeTrack = snapshot.currentItem,
            )
        }
        if (wasEmpty && snapshot.currentItem != null) {
            loadActiveTrack(snapshot.currentItem!!, startPositionMs = 0L, autoPlay = false)
        }
    }

    fun removeQueueItem(activeIndex: Int) {
        val previousActiveIndex = queueManager.snapshot().currentIndex
        val removed = queueManager.removeAt(activeIndex) ?: return
        val snapshot = queueManager.snapshot()
        val newActive = snapshot.currentItem
        _state.update { it.copy(queue = snapshot, activeTrack = newActive) }

        if (snapshot.isEmpty) {
            engine.stop()
            _state.update { it.copy(activeFingerprint = null) }
            PlaybackForegroundService.stopService(appContext)
        } else if (activeIndex == previousActiveIndex && newActive != null && newActive.uriString != removed.uriString) {
            loadActiveTrack(newActive, startPositionMs = 0L, autoPlay = engine.state.value.isPlaying)
        }
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        if (queueManager.moveItem(fromIndex, toIndex)) {
            val snapshot = queueManager.snapshot()
            _state.update { it.copy(queue = snapshot, activeTrack = snapshot.currentItem) }
        }
    }

    fun play() {
        val active = _state.value.activeTrack ?: queueManager.currentItem ?: return
        if (engine.state.value.activeTrackUri == null) {
            loadActiveTrack(active, startPositionMs = _state.value.currentPositionMs, autoPlay = true)
            return
        }
        PlaybackForegroundService.startService(appContext)
        engine.play()
    }

    fun pause() {
        engine.pause()
        persistCurrentPositionAsync()
    }

    fun togglePlayPause() {
        if (_state.value.isPlaying) pause() else play()
    }

    fun next(userInitiated: Boolean = true) {
        val nextTrack = queueManager.next(userInitiated = userInitiated)
        val snapshot = queueManager.snapshot()
        if (nextTrack != null) {
            _state.update { it.copy(queue = snapshot, activeTrack = nextTrack) }
            loadActiveTrack(nextTrack, startPositionMs = 0L, autoPlay = true)
        } else {
            _state.update { it.copy(queue = snapshot) }
            if (!userInitiated) {
                engine.pause()
                persistCurrentPositionAsync()
            }
        }
    }

    fun previous() {
        // Standard UX: if > 3 seconds into track, restart current track; otherwise go to previous track.
        if (_state.value.currentPositionMs > 3_000L) {
            seekTo(0L)
            return
        }
        val prevTrack = queueManager.previous()
        val snapshot = queueManager.snapshot()
        if (prevTrack != null) {
            _state.update { it.copy(queue = snapshot, activeTrack = prevTrack) }
            loadActiveTrack(prevTrack, startPositionMs = 0L, autoPlay = true)
        } else {
            seekTo(0L)
        }
    }

    fun seekTo(positionMs: Long) {
        engine.seekTo(positionMs)
        persistCurrentPositionAsync()
    }

    fun stop() {
        persistCurrentPositionAsync()
        engine.stop()
        PlaybackForegroundService.stopService(appContext)
    }

    fun toggleShuffle() {
        val nextEnabled = !queueManager.snapshot().shuffleEnabled
        val snapshot = queueManager.setShuffle(nextEnabled)
        _state.update { it.copy(queue = snapshot, activeTrack = snapshot.currentItem) }
        scope.launch(Dispatchers.IO) {
            runCatching { preferencesRepository.setShuffleEnabled(nextEnabled) }
        }
    }

    fun cycleRepeatMode() {
        val nextMode = queueManager.cycleRepeatMode()
        val snapshot = queueManager.snapshot()
        _state.update { it.copy(queue = snapshot) }
        scope.launch(Dispatchers.IO) {
            runCatching { preferencesRepository.setRepeatMode(nextMode.toPreference()) }
        }
    }

    fun requestBitPerfectMode(): SafetyCommand = engine.requestBitPerfectMode()

    fun confirmBitPerfectWarning(): SafetyCommand {
        val cmd = engine.confirmBitPerfectWarning()
        if (cmd.type == SafetyEventType.MODE_CHANGED_TO_BIT_PERFECT) {
            scope.launch(Dispatchers.IO) {
                runCatching {
                    preferencesRepository.setAudioSafetyMode(AudioSafetyModePreference.BIT_PERFECT_OPT_IN)
                }
            }
        }
        return cmd
    }

    fun dismissSafetyWarning(): SafetyCommand = engine.dismissSafetyWarning()

    fun switchToSafeMode(): SafetyCommand {
        val cmd = engine.switchToSafeMode()
        scope.launch(Dispatchers.IO) {
            runCatching {
                preferencesRepository.setAudioSafetyMode(AudioSafetyModePreference.SAFE_VOLUME_MODE)
            }
        }
        return cmd
    }

    fun clearAutoPauseNotice() {
        engine.clearAutoPauseNotice()
    }

    /**
     * Restores the last active track and paused playback position on app relaunch without auto-playing.
     */
    fun restoreLastSessionIfNeeded(libraryTracks: List<PlaybackTrack>) {
        if (hasRestoredInitialSession || libraryTracks.isEmpty() || !queueManager.isEmpty()) return
        hasRestoredInitialSession = true

        scope.launch {
            val prefs = runCatching { preferencesRepository.getSnapshot() }.getOrNull() ?: return@launch
            val lastUri = prefs.lastPlaybackState.trackUri ?: return@launch
            val lastPos = prefs.lastPlaybackState.positionMs
            val index = libraryTracks.indexOfFirst { it.uriString == lastUri }
            if (index < 0) return@launch

            val snapshot = queueManager.setQueue(libraryTracks, startIndex = index)
            val active = snapshot.currentItem ?: return@launch
            _state.update {
                it.copy(
                    queue = snapshot,
                    activeTrack = active,
                    activeFingerprint = prefs.lastPlaybackState.contentFingerprint ?: active.contentFingerprint,
                )
            }
            // Prepare paused at restored position (never blast audio automatically on app relaunch).
            loadActiveTrack(active, startPositionMs = lastPos, autoPlay = false)
        }
    }

    fun persistCurrentPositionNow() {
        persistCurrentPositionAsync()
    }

    fun release() {
        persistCurrentPositionAsync()
        positionPersistenceJob?.cancel()
        fingerprintJob?.cancel()
        engine.release()
    }

    private fun handleTrackCompleted() {
        next(userInitiated = false)
    }

    private fun loadActiveTrack(
        track: PlaybackTrack,
        startPositionMs: Long,
        autoPlay: Boolean,
    ) {
        if (autoPlay) {
            PlaybackForegroundService.startService(appContext)
        }
        val uri = Uri.parse(track.uriString)
        engine.loadTrack(
            EngineTrackRequest(
                uri = uri,
                fileName = track.fileName,
                mimeType = track.mimeType,
                startPositionMs = startPositionMs,
                autoPlay = autoPlay,
            ),
        )
        resolveFingerprintAndRecordHistory(track, autoPlay)
    }

    private fun resolveFingerprintAndRecordHistory(track: PlaybackTrack, recordHistory: Boolean) {
        fingerprintJob?.cancel()
        fingerprintJob = scope.launch(Dispatchers.IO) {
            val resolvedFp = track.contentFingerprint?.takeIf { it.isNotBlank() }
                ?: fingerprintProvider.computeFingerprint(Uri.parse(track.uriString)).getOrNull()

            if (_state.value.activeTrack?.uriString == track.uriString) {
                val updatedTrack = if (resolvedFp != null) {
                    track.copy(contentFingerprint = resolvedFp)
                } else {
                    track
                }
                _state.update {
                    it.copy(
                        activeTrack = updatedTrack,
                        activeFingerprint = resolvedFp,
                    )
                }
            }

            if (recordHistory && resolvedFp != null) {
                runCatching {
                    persistenceRepository.recordTrackPlayed(
                        TrackHistoryRecord(
                            contentFingerprint = resolvedFp,
                            trackUri = track.uriString,
                            title = track.title,
                            artist = track.artist,
                            album = track.album,
                            fileName = track.fileName,
                            durationMs = _state.value.durationMs.takeIf { it > 0L } ?: track.durationMs,
                            playedAtEpochMs = System.currentTimeMillis(),
                        ),
                    )
                }
            }

            runCatching {
                preferencesRepository.saveLastPlaybackState(
                    trackUri = track.uriString,
                    contentFingerprint = resolvedFp,
                    positionMs = _state.value.currentPositionMs,
                )
            }
        }
    }

    private fun ensurePositionPersistenceRunning() {
        if (positionPersistenceJob?.isActive == true) return
        positionPersistenceJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(5_000L)
                val snap = _state.value
                val track = snap.activeTrack ?: continue
                runCatching {
                    preferencesRepository.saveLastPlaybackState(
                        trackUri = track.uriString,
                        contentFingerprint = snap.activeFingerprint ?: track.contentFingerprint,
                        positionMs = snap.currentPositionMs,
                    )
                }
            }
        }
    }

    private fun stopPositionPersistenceAndSaveNow() {
        positionPersistenceJob?.cancel()
        positionPersistenceJob = null
        persistCurrentPositionAsync()
    }

    private fun persistCurrentPositionAsync() {
        val snap = _state.value
        val track = snap.activeTrack ?: return
        scope.launch(Dispatchers.IO) {
            runCatching {
                preferencesRepository.saveLastPlaybackState(
                    trackUri = track.uriString,
                    contentFingerprint = snap.activeFingerprint ?: track.contentFingerprint,
                    positionMs = snap.currentPositionMs,
                )
            }
        }
    }

    private fun RepeatModePreference.toQueueRepeatMode(): RepeatMode = when (this) {
        RepeatModePreference.OFF -> RepeatMode.OFF
        RepeatModePreference.ALL -> RepeatMode.ALL
        RepeatModePreference.ONE -> RepeatMode.ONE
    }

    private fun RepeatMode.toPreference(): RepeatModePreference = when (this) {
        RepeatMode.OFF -> RepeatModePreference.OFF
        RepeatMode.ALL -> RepeatModePreference.ALL
        RepeatMode.ONE -> RepeatModePreference.ONE
    }
}
