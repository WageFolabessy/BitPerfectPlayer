package com.cangkir.bitperfectplayer.audio

import android.content.Context
import androidx.core.net.toUri
import com.cangkir.bitperfectplayer.data.AudioSafetyModePreference
import com.cangkir.bitperfectplayer.data.LocalPersistenceRepository
import com.cangkir.bitperfectplayer.data.RepeatModePreference
import com.cangkir.bitperfectplayer.data.ReplayGainModePreference
import com.cangkir.bitperfectplayer.data.TrackHistoryRecord
import com.cangkir.bitperfectplayer.data.UserPreferencesRepository
import com.cangkir.bitperfectplayer.library.LibrarySnapshot
import com.cangkir.bitperfectplayer.library.PlaylistsUiSnapshot
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
import java.util.concurrent.ConcurrentHashMap

/**
 * Combined UI-observable low-frequency playback state unifying queue state, active queue context,
 * active track, resolved content fingerprint, gapless & ReplayGain preferences, playback status,
 * safety state, and 6-stage diagnostics. High-frequency playback position (~20 Hz) is exposed
 * separately via [PlaybackController.playbackPositionMs].
 */
data class PlaybackControllerState(
    val queue: QueueSnapshot<PlaybackTrack> = QueueSnapshot(),
    val queueContext: QueueContext = QueueContext.allSongs(),
    val activeTrack: PlaybackTrack? = null,
    val activeFingerprint: String? = null,
    val gaplessEnabled: Boolean = true,
    val replayGainConfig: ReplayGainConfig = ReplayGainConfig(),
    val engineState: EnginePlaybackState = EnginePlaybackState(),
) {
    val isPlaying: Boolean
        get() = engineState.isPlaying

    val durationMs: Long
        get() = engineState.durationMs.takeIf { it > 0L } ?: activeTrack?.durationMs ?: 0L
}

/**
 * Application-scoped playback coordinator bridging [PlaybackQueueManager], [AudioOutputEngine],
 * [PlayCountTracker], [PlaybackForegroundService], [UserPreferencesRepository],
 * [LocalPersistenceRepository], and [AndroidContentFingerprintProvider].
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
    private val playCountTracker = PlayCountTracker()
    private val trackedTracksByUri = ConcurrentHashMap<String, PlaybackTrack>()

    private val engine = AudioOutputEngine(
        context = appContext,
        safetyController = safetyController,
        onTrackCompleted = { handleTrackCompleted() },
        onGaplessTransition = { transitioned -> handleGaplessTransition(transitioned) },
        onActivePlaybackElapsedMs = { deltaMs -> handleActivePlaybackElapsed(deltaMs) },
    )

    private val sleepTimerController = SleepTimerController(
        scope = scope,
        isBitPerfectOptIn = { safetyController.state().mode == AudioOutputMode.BIT_PERFECT_OPT_IN },
        setSafeModeVolume = { vol -> engine.setSafeModeOutputVolume(vol) },
        pausePlayback = { pause() },
        onEndOfTrackArmedChanged = { syncNextTrackToEngine() },
    )

    private val _state = MutableStateFlow(
        PlaybackControllerState(engineState = engine.state.value),
    )
    val state: StateFlow<PlaybackControllerState> = _state.asStateFlow()

    val playbackPositionMs: StateFlow<Long> = engine.playbackPositionMs

    /**
     * Decoupled Sleep Timer state collected ONLY by narrow leaf composables in NowPlayingScreen
     * and SettingsScreen so 1-second countdown ticks never recompose Library, Playlists, or BottomNav.
     */
    val sleepTimerState: StateFlow<SleepTimerState> = sleepTimerController.state

    private var positionPersistenceJob: Job? = null
    private var fingerprintJob: Job? = null
    private var hasRestoredInitialSession = false
    private var isRestoreInFlight = false
    private var hasRecordedHistoryForActiveTrack = false

    init {
        // Restore persisted shuffle/repeat/gapless/ReplayGain preferences
        // (hearing safety mode ALWAYS boots in Safe Mode!).
        scope.launch {
            val prefs = runCatching { preferencesRepository.getSnapshot() }.getOrNull() ?: return@launch
            queueManager.setShuffle(prefs.shuffleEnabled)
            queueManager.setRepeatMode(prefs.repeatMode.toQueueRepeatMode())
            val rgConfig = ReplayGainConfig(
                mode = prefs.replayGainMode.toAudioReplayGainMode(),
                preampDb = prefs.replayGainPreampDb,
                preventClipping = prefs.replayGainPreventClipping,
            )
            engine.setGaplessEnabled(prefs.gaplessEnabled)
            engine.setReplayGainConfig(rgConfig)
            _state.update {
                it.copy(
                    queue = queueManager.snapshot(),
                    gaplessEnabled = prefs.gaplessEnabled,
                    replayGainConfig = rgConfig,
                )
            }
            syncNextTrackToEngine()
        }

        // Mirror engine state updates and persist playback position periodically while playing.
        scope.launch {
            engine.state.collect { engineState ->
                if (engineState.durationMs > 0L) {
                    playCountTracker.updateDuration(engineState.durationMs)
                }
                _state.update { it.copy(engineState = engineState) }
                if (engineState.isPlaying) {
                    ensurePositionPersistenceRunning()
                } else {
                    flushPendingPlayCountTime()
                    stopPositionPersistenceAndSaveNow()
                }
            }
        }
    }

    fun playQueue(
        tracks: List<PlaybackTrack>,
        startIndex: Int = 0,
        startPositionMs: Long = 0L,
        autoPlay: Boolean = true,
        context: QueueContext = QueueContext.allSongs(),
    ) {
        if (tracks.isEmpty()) return
        val snapshot = queueManager.setQueue(tracks, startIndex)
        val active = snapshot.currentItem ?: return
        _state.update {
            it.copy(
                queue = snapshot,
                queueContext = context,
                activeTrack = active,
            )
        }
        loadActiveTrack(active, startPositionMs = startPositionMs, autoPlay = autoPlay)
    }

    /**
     * Switches the active queue immediately to [context]'s ordered tracks (prepared paused at index
     * 0 by default for hearing safety unless [autoPlay] is requested).
     */
    fun loadContextQueue(
        tracks: List<PlaybackTrack>,
        context: QueueContext,
        autoPlay: Boolean = false,
    ) {
        if (tracks.isEmpty()) return
        playQueue(
            tracks = tracks,
            startIndex = 0,
            startPositionMs = 0L,
            autoPlay = autoPlay,
            context = context,
        )
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
                queueContext = if (wasEmpty) QueueContext.custom("Queue") else it.queueContext,
                activeTrack = snapshot.currentItem,
            )
        }
        if (wasEmpty && snapshot.currentItem != null) {
            loadActiveTrack(snapshot.currentItem!!, startPositionMs = 0L, autoPlay = false)
        } else {
            syncNextTrackToEngine()
        }
    }

    fun enqueueNext(track: PlaybackTrack) {
        val wasEmpty = queueManager.isEmpty()
        val snapshot = queueManager.enqueueNext(track)
        _state.update {
            it.copy(
                queue = snapshot,
                queueContext = if (wasEmpty) QueueContext.custom("Queue") else it.queueContext,
                activeTrack = snapshot.currentItem,
            )
        }
        if (wasEmpty && snapshot.currentItem != null) {
            loadActiveTrack(snapshot.currentItem!!, startPositionMs = 0L, autoPlay = false)
        } else {
            syncNextTrackToEngine()
        }
    }

    fun enqueueAll(tracks: List<PlaybackTrack>, fallbackContext: QueueContext? = null) {
        if (tracks.isEmpty()) return
        val wasEmpty = queueManager.isEmpty()
        val snapshot = queueManager.enqueueAll(tracks)
        _state.update {
            it.copy(
                queue = snapshot,
                queueContext = if (wasEmpty) (fallbackContext ?: QueueContext.custom("Queue")) else it.queueContext,
                activeTrack = snapshot.currentItem,
            )
        }
        if (wasEmpty && snapshot.currentItem != null) {
            loadActiveTrack(snapshot.currentItem!!, startPositionMs = 0L, autoPlay = false)
        } else {
            syncNextTrackToEngine()
        }
    }

    fun enqueueAllNext(tracks: List<PlaybackTrack>, fallbackContext: QueueContext? = null) {
        if (tracks.isEmpty()) return
        val wasEmpty = queueManager.isEmpty()
        val snapshot = queueManager.enqueueAllNext(tracks)
        _state.update {
            it.copy(
                queue = snapshot,
                queueContext = if (wasEmpty) (fallbackContext ?: QueueContext.custom("Queue")) else it.queueContext,
                activeTrack = snapshot.currentItem,
            )
        }
        if (wasEmpty && snapshot.currentItem != null) {
            loadActiveTrack(snapshot.currentItem!!, startPositionMs = 0L, autoPlay = false)
        } else {
            syncNextTrackToEngine()
        }
    }

    fun removeQueueItem(activeIndex: Int) {
        val previousActiveIndex = queueManager.snapshot().currentIndex
        queueManager.removeAt(activeIndex) ?: return
        val snapshot = queueManager.snapshot()
        val newActive = snapshot.currentItem
        _state.update { it.copy(queue = snapshot, activeTrack = newActive) }

        if (snapshot.isEmpty) {
            hasRecordedHistoryForActiveTrack = false
            playCountTracker.clearSession()?.let { persistPlayCountFlush(it) }
            engine.setNextTrack(null)
            engine.stop()
            _state.update { it.copy(activeFingerprint = null) }
            PlaybackForegroundService.stopService(appContext)
        } else if (activeIndex == previousActiveIndex && newActive != null) {
            loadActiveTrack(newActive, startPositionMs = 0L, autoPlay = engine.state.value.isPlaying)
        } else {
            syncNextTrackToEngine()
        }
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        if (queueManager.moveItem(fromIndex, toIndex)) {
            val snapshot = queueManager.snapshot()
            _state.update { it.copy(queue = snapshot, activeTrack = snapshot.currentItem) }
            syncNextTrackToEngine()
        }
    }

    fun play() {
        val active = _state.value.activeTrack ?: queueManager.currentItem ?: return
        if (engine.state.value.activeTrackUri == null) {
            loadActiveTrack(active, startPositionMs = playbackPositionMs.value, autoPlay = true)
            return
        }
        val wasCompleted = engine.state.value.status == PlaybackStatus.COMPLETED
        if (wasCompleted) {
            playCountTracker.resetSessionForReplay()?.let { persistPlayCountFlush(it) }
        }
        if (!hasRecordedHistoryForActiveTrack || wasCompleted) {
            hasRecordedHistoryForActiveTrack = true
            resolveFingerprintAndRecordHistory(active, recordHistory = true)
        }
        syncNextTrackToEngine()
        PlaybackForegroundService.startService(appContext)
        engine.play()
    }

    fun pause() {
        flushPendingPlayCountTime()
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
            engine.setNextTrack(null)
            if (!userInitiated) {
                engine.pause()
                persistCurrentPositionAsync()
            }
        }
    }

    fun previous() {
        // Standard UX: if > 3 seconds into track, restart current track; otherwise go to previous track.
        if (playbackPositionMs.value > 3_000L) {
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
        playCountTracker.onSeek()
        engine.seekTo(positionMs)
        persistCurrentPositionAsync()
    }

    fun stop() {
        playCountTracker.clearSession()?.let { persistPlayCountFlush(it) }
        persistCurrentPositionAsync()
        engine.setNextTrack(null)
        engine.stop()
        PlaybackForegroundService.stopService(appContext)
    }

    fun toggleShuffle() {
        val nextEnabled = !queueManager.snapshot().shuffleEnabled
        val snapshot = queueManager.setShuffle(nextEnabled)
        _state.update { it.copy(queue = snapshot, activeTrack = snapshot.currentItem) }
        syncNextTrackToEngine()
        scope.launch(Dispatchers.IO) {
            runCatching { preferencesRepository.setShuffleEnabled(nextEnabled) }
        }
    }

    fun cycleRepeatMode() {
        val nextMode = queueManager.cycleRepeatMode()
        val snapshot = queueManager.snapshot()
        _state.update { it.copy(queue = snapshot) }
        syncNextTrackToEngine()
        scope.launch(Dispatchers.IO) {
            runCatching { preferencesRepository.setRepeatMode(nextMode.toPreference()) }
        }
    }

    fun setGaplessEnabled(enabled: Boolean) {
        engine.setGaplessEnabled(enabled)
        _state.update { it.copy(gaplessEnabled = enabled) }
        syncNextTrackToEngine()
        scope.launch(Dispatchers.IO) {
            runCatching { preferencesRepository.setGaplessEnabled(enabled) }
        }
    }

    fun setReplayGainMode(mode: ReplayGainMode) {
        val updated = _state.value.replayGainConfig.copy(mode = mode)
        engine.setReplayGainConfig(updated)
        _state.update { it.copy(replayGainConfig = updated) }
        scope.launch(Dispatchers.IO) {
            runCatching { preferencesRepository.setReplayGainMode(mode.toPreference()) }
        }
    }

    fun setReplayGainPreampDb(preampDb: Float) {
        val clamped = preampDb.coerceIn(ReplayGainConfig.MIN_PREAMP_DB, ReplayGainConfig.MAX_PREAMP_DB)
        val updated = _state.value.replayGainConfig.copy(preampDb = clamped)
        engine.setReplayGainConfig(updated)
        _state.update { it.copy(replayGainConfig = updated) }
        scope.launch(Dispatchers.IO) {
            runCatching { preferencesRepository.setReplayGainPreampDb(clamped) }
        }
    }

    fun setReplayGainPreventClipping(preventClipping: Boolean) {
        val updated = _state.value.replayGainConfig.copy(preventClipping = preventClipping)
        engine.setReplayGainConfig(updated)
        _state.update { it.copy(replayGainConfig = updated) }
        scope.launch(Dispatchers.IO) {
            runCatching { preferencesRepository.setReplayGainPreventClipping(preventClipping) }
        }
    }

    fun setSleepTimerPreset(preset: SleepTimerPreset) {
        sleepTimerController.setPreset(preset)
    }

    fun cancelSleepTimer() {
        sleepTimerController.cancel()
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
     * Restores the last active track, exact scoped queue context (Album, Artist, Folder, Playlist,
     * Recent, Favorites, or All Songs in its sorted order), and paused playback position on app
     * relaunch without auto-playing.
     */
    fun restoreLastSessionIfNeeded(
        library: LibrarySnapshot,
        playlists: PlaylistsUiSnapshot,
    ) {
        if (hasRestoredInitialSession || isRestoreInFlight || library.allSongs.isEmpty() || !queueManager.isEmpty()) {
            return
        }
        isRestoreInFlight = true

        scope.launch {
            try {
                val prefs = runCatching { preferencesRepository.getSnapshot() }.getOrNull() ?: return@launch
                val lastUri = prefs.lastPlaybackState.trackUri
                if (lastUri == null) {
                    hasRestoredInitialSession = true
                    return@launch
                }

                val savedContext = prefs.lastPlaybackState.toQueueContext()
                // If restoring a Playlist or Most Played session, wait briefly for PlaylistRepository's
                // first Room emission before attempting resolution so we don't fall back prematurely.
                val needsPlaylistSnapshot = savedContext.sourceType == QueueSourceType.PLAYLIST ||
                    (savedContext.sourceType == QueueSourceType.CUSTOM && savedContext.sourceKey == QueueContext.MOST_PLAYED_KEY)
                if (needsPlaylistSnapshot && !playlists.isLoaded) {
                    return@launch
                }

                if (!queueManager.isEmpty()) {
                    hasRestoredInitialSession = true
                    return@launch
                }

                val selection = QueueContextResolver.resolveSessionRestore(
                    lastTrackUri = lastUri,
                    savedContext = savedContext,
                    allSongs = library.allSongs,
                    albumsByName = library.albums.associate { it.name to it.tracks },
                    artistsByName = library.artists.associate { it.name to it.tracks },
                    foldersByName = library.folders.associate { it.name to it.tracks },
                    playlistsById = playlists.playlists.associate { it.id.toString() to it.tracks },
                    recentlyPlayed = library.recentlyPlayed,
                    favorites = library.favorites,
                    mostPlayed = playlists.mostPlayed.map { it.track },
                )
                hasRestoredInitialSession = true
                if (selection == null) return@launch

                val rawLastPos = prefs.lastPlaybackState.positionMs
                val snapshot = queueManager.setQueue(selection.tracks, startIndex = selection.startIndex)
                val active = snapshot.currentItem ?: return@launch
                val restoredPos = if (active.durationMs > 0L && rawLastPos >= (active.durationMs - 1_500L).coerceAtLeast(1L)) {
                    0L
                } else {
                    rawLastPos.coerceAtLeast(0L)
                }
                _state.update {
                    it.copy(
                        queue = snapshot,
                        queueContext = selection.context,
                        activeTrack = active,
                        activeFingerprint = prefs.lastPlaybackState.contentFingerprint ?: active.contentFingerprint,
                    )
                }
                // Prepare paused at restored position (never blast audio automatically on app relaunch).
                loadActiveTrack(active, startPositionMs = restoredPos, autoPlay = false)
            } finally {
                isRestoreInFlight = false
            }
        }
    }

    fun persistCurrentPositionNow() {
        flushPendingPlayCountTime()
        persistCurrentPositionAsync()
    }

    fun release() {
        sleepTimerController.release()
        playCountTracker.clearSession()?.let { persistPlayCountFlush(it) }
        persistCurrentPositionAsync()
        positionPersistenceJob?.cancel()
        fingerprintJob?.cancel()
        engine.release()
    }

    private fun handleTrackCompleted() {
        playCountTracker.onNaturalCompletion()?.let { persistPlayCountFlush(it) }
        if (sleepTimerController.consumeEndOfTrackTrigger()) {
            persistCurrentPositionAsync()
            return
        }
        next(userInitiated = false)
    }

    /**
     * Handles a seamless same-format gapless transition performed directly inside [AudioOutputEngine]
     * without tearing down or rebuilding `AudioTrack`. Advances queue state, flushes the finishing
     * track's play-count session, starts a fresh session for the newly active track, resolves
     * fingerprint/history, and primes the subsequent upcoming track.
     */
    private fun handleGaplessTransition(transitionedRequest: EngineTrackRequest) {
        playCountTracker.onNaturalCompletion()?.let { persistPlayCountFlush(it) }
        val pauseAfterTransition = sleepTimerController.consumeEndOfTrackTrigger()
        val nextTrack = queueManager.next(userInitiated = false)
        val snapshot = queueManager.snapshot()
        val directive = GaplessTransitionPolicy.resolvePostTransitionDirective(
            hasNextTrack = nextTrack != null,
            nextTrackMatchesTransitioned = nextTrack?.uriString == transitionedRequest.uri.toString(),
            pauseAfterTransition = pauseAfterTransition,
        )

        when (directive) {
            PostGaplessTransitionDirective.STOP_END_OF_QUEUE -> {
                _state.update { it.copy(queue = snapshot) }
                engine.setNextTrack(null)
                engine.pause()
                persistCurrentPositionAsync()
            }
            PostGaplessTransitionDirective.RESYNC_VIA_RELOAD -> {
                val target = nextTrack ?: return
                _state.update { it.copy(queue = snapshot, activeTrack = target) }
                loadActiveTrack(target, startPositionMs = 0L, autoPlay = !pauseAfterTransition)
            }
            PostGaplessTransitionDirective.PAUSE_AND_PERSIST -> {
                val target = nextTrack ?: return
                hasRecordedHistoryForActiveTrack = false
                trackedTracksByUri[target.uriString] = target
                playCountTracker.startSession(target.uriString, target.durationMs)?.let { persistPlayCountFlush(it) }
                _state.update {
                    it.copy(
                        queue = snapshot,
                        activeTrack = target,
                        activeFingerprint = target.contentFingerprint,
                    )
                }
                engine.setNextTrack(null)
                engine.pause()
                persistCurrentPositionAsync()
                resolveFingerprintAndRecordHistory(target, recordHistory = false)
            }
            PostGaplessTransitionDirective.CONTINUE_AND_SYNC_NEXT -> {
                val target = nextTrack ?: return
                hasRecordedHistoryForActiveTrack = true
                trackedTracksByUri[target.uriString] = target
                playCountTracker.startSession(target.uriString, target.durationMs)?.let { persistPlayCountFlush(it) }
                _state.update {
                    it.copy(
                        queue = snapshot,
                        activeTrack = target,
                        activeFingerprint = target.contentFingerprint,
                    )
                }
                syncNextTrackToEngine()
                resolveFingerprintAndRecordHistory(target, recordHistory = true)
            }
        }
    }

    private fun syncNextTrackToEngine() {
        // When End-of-Track sleep timer is armed, do NOT pre-load or gaplessly chain into the next track.
        if (sleepTimerController.state.value.isEndOfTrackArmed) {
            engine.setNextTrack(null)
            return
        }
        val nextTrack = queueManager.peekNext(userInitiated = false)
        if (nextTrack == null) {
            engine.setNextTrack(null)
            return
        }
        engine.setNextTrack(
            EngineTrackRequest(
                uri = nextTrack.uriString.toUri(),
                fileName = nextTrack.fileName,
                mimeType = nextTrack.mimeType,
                startPositionMs = 0L,
                autoPlay = true,
            ),
        )
    }

    private fun handleActivePlaybackElapsed(deltaMs: Long) {
        playCountTracker.onActivePlaybackElapsed(deltaMs)?.let { persistPlayCountFlush(it) }
    }

    private fun flushPendingPlayCountTime() {
        playCountTracker.flushPendingPlayTime()?.let { persistPlayCountFlush(it) }
    }

    private fun persistPlayCountFlush(event: PlayCountFlushEvent) {
        if (!event.incrementPlayCount && event.playTimeDeltaMs <= 0L) return
        val track = trackedTracksByUri[event.trackUri]
            ?: _state.value.activeTrack?.takeIf { it.uriString == event.trackUri }
            ?: return
        val cachedActiveFp = if (_state.value.activeTrack?.uriString == event.trackUri) {
            _state.value.activeFingerprint
        } else {
            null
        }
        scope.launch(Dispatchers.IO) {
            val resolvedFp = track.contentFingerprint?.takeIf { it.isNotBlank() }
                ?: cachedActiveFp?.takeIf { it.isNotBlank() }
                ?: fingerprintProvider.computeFingerprint(track.uriString.toUri()).getOrNull()
                ?: return@launch

            if (track.contentFingerprint != resolvedFp) {
                trackedTracksByUri[track.uriString] = track.copy(contentFingerprint = resolvedFp)
                runCatching {
                    persistenceRepository.updateCachedTrackFingerprint(
                        uriString = track.uriString,
                        fingerprint = resolvedFp,
                    )
                }
            }
            runCatching {
                persistenceRepository.recordTrackListeningDelta(
                    contentFingerprint = resolvedFp,
                    track = track,
                    playCountDelta = if (event.incrementPlayCount) 1 else 0,
                    playTimeDeltaMs = event.playTimeDeltaMs,
                    playedAtEpochMs = System.currentTimeMillis(),
                )
            }
        }
    }

    private fun loadActiveTrack(
        track: PlaybackTrack,
        startPositionMs: Long,
        autoPlay: Boolean,
    ) {
        hasRecordedHistoryForActiveTrack = autoPlay
        trackedTracksByUri[track.uriString] = track
        playCountTracker.startSession(track.uriString, track.durationMs)?.let { persistPlayCountFlush(it) }
        if (autoPlay) {
            PlaybackForegroundService.startService(appContext)
        }
        val uri = track.uriString.toUri()
        engine.loadTrack(
            EngineTrackRequest(
                uri = uri,
                fileName = track.fileName,
                mimeType = track.mimeType,
                startPositionMs = startPositionMs,
                autoPlay = autoPlay,
            ),
        )
        syncNextTrackToEngine()
        resolveFingerprintAndRecordHistory(track, autoPlay)
    }

    private fun resolveFingerprintAndRecordHistory(track: PlaybackTrack, recordHistory: Boolean) {
        fingerprintJob?.cancel()
        fingerprintJob = scope.launch(Dispatchers.IO) {
            val resolvedFp = track.contentFingerprint?.takeIf { it.isNotBlank() }
                ?: fingerprintProvider.computeFingerprint(track.uriString.toUri()).getOrNull()

            if (resolvedFp != null && track.contentFingerprint != resolvedFp) {
                trackedTracksByUri[track.uriString] = track.copy(contentFingerprint = resolvedFp)
                runCatching {
                    persistenceRepository.updateCachedTrackFingerprint(
                        uriString = track.uriString,
                        fingerprint = resolvedFp,
                    )
                }
            }

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

            val qCtx = _state.value.queueContext
            runCatching {
                preferencesRepository.saveLastPlaybackState(
                    trackUri = track.uriString,
                    contentFingerprint = resolvedFp,
                    positionMs = playbackPositionMs.value,
                    queueSourceType = qCtx.sourceType,
                    queueSourceKey = qCtx.sourceKey,
                    queueSourceLabel = qCtx.sourceLabel,
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
                val qCtx = snap.queueContext
                runCatching {
                    preferencesRepository.saveLastPlaybackState(
                        trackUri = track.uriString,
                        contentFingerprint = snap.activeFingerprint ?: track.contentFingerprint,
                        positionMs = playbackPositionMs.value,
                        queueSourceType = qCtx.sourceType,
                        queueSourceKey = qCtx.sourceKey,
                        queueSourceLabel = qCtx.sourceLabel,
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
        val currentPos = playbackPositionMs.value
        val qCtx = snap.queueContext
        scope.launch(Dispatchers.IO) {
            runCatching {
                preferencesRepository.saveLastPlaybackState(
                    trackUri = track.uriString,
                    contentFingerprint = snap.activeFingerprint ?: track.contentFingerprint,
                    positionMs = currentPos,
                    queueSourceType = qCtx.sourceType,
                    queueSourceKey = qCtx.sourceKey,
                    queueSourceLabel = qCtx.sourceLabel,
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

    private fun ReplayGainModePreference.toAudioReplayGainMode(): ReplayGainMode = when (this) {
        ReplayGainModePreference.OFF -> ReplayGainMode.OFF
        ReplayGainModePreference.TRACK -> ReplayGainMode.TRACK
        ReplayGainModePreference.ALBUM -> ReplayGainMode.ALBUM
    }

    private fun ReplayGainMode.toPreference(): ReplayGainModePreference = when (this) {
        ReplayGainMode.OFF -> ReplayGainModePreference.OFF
        ReplayGainMode.TRACK -> ReplayGainModePreference.TRACK
        ReplayGainMode.ALBUM -> ReplayGainModePreference.ALBUM
    }
}
