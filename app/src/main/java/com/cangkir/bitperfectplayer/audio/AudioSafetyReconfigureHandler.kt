package com.cangkir.bitperfectplayer.audio

/**
 * Narrow callbacks invoked by [AudioSafetyReconfigureHandler] while keeping [AudioOutputEngine]
 * as the sole owner of `engineLock` and active session references.
 */
internal interface SafetyReconfigureHost {
    fun currentStatus(): PlaybackStatus
    fun currentTrackRequest(): EngineTrackRequest?
    fun currentActiveDecoder(): PcmAudioDecoder?
    fun currentActiveSink(): AudioOutputSink?
    fun hasPendingRouteReconfigure(): Boolean
    fun setPendingRouteReconfigure(pending: Boolean)
    fun hasBitPerfectOrPreferredRoute(): Boolean
    fun actualSampleRateHz(): Int
    fun recomputeReplayGain()
    fun cancelPendingStartOnExternalPause()
    fun applyImmediatePauseState(notice: String?)
    fun publishSafePausedState()
    fun publishSafetyAndDiagnostics()
    fun enqueueLockedControlTask(block: () -> Unit)
    fun logUnderrunDeltaIfAny(sink: AudioOutputSink?, trigger: String)
    fun stopWorkerAndReleaseTrackLocked(clearMixer: Boolean, asyncMixerClear: Boolean)
    fun loadReconfiguredTrackLocked(autoResume: Boolean, preserveAutoPauseNotice: Boolean, resumeAttemptSeq: Long)
}

/**
 * Internal collaborator coordinating hearing-safety commands (`AUTO_PAUSE`, `MODE_CHANGED_TO_SAFE`,
 * `MODE_CHANGED_TO_BIT_PERFECT`) and paused Safe-Mode route refreshes under [AudioOutputEngine]'s
 * single `engineLock` discipline.
 */
internal class AudioSafetyReconfigureHandler(
    private val routeControl: AudioRouteControl,
    private val preloadController: GaplessPreloadController,
    private val scratchRunner: TurntableScratchRunner,
    private val deferredPlayScheduler: DeferredPlayScheduler,
    private val runtimeFlags: EngineRuntimeFlags,
    private val metrics: EnginePlaybackMetrics,
    private val clock: EngineClock,
    private val host: SafetyReconfigureHost,
) {
    fun handleSafetyCommand(command: SafetyCommand) {
        val safetyStartMs = clock.elapsedRealtimeMs()
        host.recomputeReplayGain()
        val statusBeforeCommand = host.currentStatus()
        val isActivelyPlayingOrPreparing =
            statusBeforeCommand == PlaybackStatus.PLAYING || statusBeforeCommand == PlaybackStatus.PREPARING
        val hasLoadedTrack = host.currentTrackRequest() != null &&
            (
                host.currentActiveDecoder() != null ||
                    statusBeforeCommand == PlaybackStatus.PREPARING ||
                    host.hasPendingRouteReconfigure()
                )
        val canPauseInPlace = RouteChangeReconfigurePolicy.canKeepSafeModeTrackOnRouteChange(
            wasBitPerfectMode = command.wasBitPerfectMode,
            hasActivePreferredMixerOrPinnedRoute = host.hasBitPerfectOrPreferredRoute() || routeControl.hasActivePreferredMixer,
            isPreparing = statusBeforeCommand == PlaybackStatus.PREPARING,
            hasActiveDecoder = host.currentActiveDecoder() != null,
            hasHealthyAudioTrack = host.currentActiveSink()?.isHealthy() == true,
        )
        val reconfigureAction = RouteChangeReconfigurePolicy.decideAction(
            commandType = command.type,
            isActivelyPlayingOrPreparing = isActivelyPlayingOrPreparing,
            hasLoadedTrack = hasLoadedTrack,
            canPauseInPlaceOnSafeRoute = canPauseInPlace,
        )
        when (command.type) {
            SafetyEventType.AUTO_PAUSE -> dispatchAutoPauseCommand(command, reconfigureAction, safetyStartMs)
            SafetyEventType.MODE_CHANGED_TO_SAFE -> dispatchModeChangeCommand(
                mode = AudioOutputMode.SAFE_VOLUME_MODE,
                action = reconfigureAction,
                clearMixer = command.requiresClearPreferredMixerAttributes,
                releaseRing = false,
                wasPlaying = statusBeforeCommand == PlaybackStatus.PLAYING,
            )
            SafetyEventType.MODE_CHANGED_TO_BIT_PERFECT -> {
                scratchRunner.abortScratch()
                dispatchModeChangeCommand(
                    mode = AudioOutputMode.BIT_PERFECT_OPT_IN,
                    action = reconfigureAction,
                    clearMixer = false,
                    releaseRing = true,
                    wasPlaying = statusBeforeCommand == PlaybackStatus.PLAYING,
                )
            }
            SafetyEventType.REQUIRES_SAFETY_WARNING, SafetyEventType.NONE -> host.publishSafetyAndDiagnostics()
        }
    }

    fun handlePausedSafeModeRouteRefresh(coalesceDiagnostics: Boolean) {
        preloadController.clearPreloadedNextAsync()
        if (host.currentActiveSink() != null) {
            scratchRunner.markRouteChangedWhilePaused()
        }
        val needsReconfigure = host.currentTrackRequest() != null &&
            (
                host.hasBitPerfectOrPreferredRoute() ||
                    routeControl.hasActivePreferredMixer ||
                    host.currentActiveSink()?.isHealthy() != true
                )
        if (needsReconfigure) {
            host.setPendingRouteReconfigure(true)
        }
        if (coalesceDiagnostics) {
            deferredPlayScheduler.scheduleCoalescedDiagnostics()
        } else {
            deferredPlayScheduler.cancelCoalescedDiagnostics()
            host.publishSafetyAndDiagnostics()
        }
    }

    private fun dispatchAutoPauseCommand(command: SafetyCommand, action: SafetyReconfigureAction, safetyStartMs: Long) {
        deferredPlayScheduler.cancelDeferredPlay()
        deferredPlayScheduler.cancelCoalescedDiagnostics()
        deferredPlayScheduler.cancelInFlightResumes()
        metrics.logAutoPauseEvent(command, action)
        scratchRunner.abortScratch()
        routeControl.resumeAfterTransientFocusLoss = false
        host.cancelPendingStartOnExternalPause()
        if (action == SafetyReconfigureAction.TEARDOWN_AND_REBIND_ON_PLAY) {
            host.setPendingRouteReconfigure(true)
            runtimeFlags.stopWorkerFlag.set(true)
        }
        host.applyImmediatePauseState(notice = AudioSafetyController.formatAutoPauseNotice(command.autoPauseReason))
        val uiUpdateMs = metrics.elapsedSince(safetyStartMs)
        enqueueSafetyReconfigure(
            action = action,
            clearPreferredMixer = command.requiresClearPreferredMixerAttributes,
            releaseRing = false,
            autoResume = false,
            preserveAutoPauseNotice = true,
            autoPauseReason = command.autoPauseReason,
            safetyStartMs = safetyStartMs,
            uiUpdateMs = uiUpdateMs,
        )
    }

    private fun dispatchModeChangeCommand(
        mode: AudioOutputMode,
        action: SafetyReconfigureAction,
        clearMixer: Boolean,
        releaseRing: Boolean,
        wasPlaying: Boolean,
    ) {
        val modeResumeSeq = if (wasPlaying) deferredPlayScheduler.beginResumeAttempt() else 0L
        metrics.logAudioModeChanged(mode, clearMixer)
        enqueueSafetyReconfigure(
            action = action,
            clearPreferredMixer = clearMixer,
            releaseRing = releaseRing,
            autoResume = wasPlaying,
            preserveAutoPauseNotice = false,
            resumeAttemptSeq = modeResumeSeq,
        )
    }

    private fun enqueueSafetyReconfigure(
        action: SafetyReconfigureAction,
        clearPreferredMixer: Boolean,
        releaseRing: Boolean,
        autoResume: Boolean,
        preserveAutoPauseNotice: Boolean,
        autoPauseReason: AutoPauseReason? = null,
        safetyStartMs: Long = 0L,
        uiUpdateMs: Long = 0L,
        resumeAttemptSeq: Long = 0L,
    ) {
        host.enqueueLockedControlTask {
            if (releaseRing) scratchRunner.releaseRingBuffer()
            val pauseMetrics = executeSafetyActionLocked(
                action = action,
                clearPreferredMixer = clearPreferredMixer,
                autoResume = autoResume,
                preserveAutoPauseNotice = preserveAutoPauseNotice,
                resumeAttemptSeq = resumeAttemptSeq,
            )
            if (autoPauseReason != null && safetyStartMs > 0L) {
                val sr = host.currentActiveDecoder()?.format?.sampleRateHz ?: host.actualSampleRateHz()
                metrics.logAutoPauseLatency(autoPauseReason, action, uiUpdateMs, pauseMetrics, safetyStartMs, sr)
            }
        }
    }

    private fun executeSafetyActionLocked(
        action: SafetyReconfigureAction,
        clearPreferredMixer: Boolean,
        autoResume: Boolean,
        preserveAutoPauseNotice: Boolean,
        resumeAttemptSeq: Long,
    ): AutoPauseMetrics = when (action) {
        SafetyReconfigureAction.RECONFIGURE_NOW -> {
            val clearMixerMs = clearMixerTimedIfRequested(clearPreferredMixer)
            reconfigureTrackIfLoadedLocked(autoResume, preserveAutoPauseNotice, resumeAttemptSeq)
            AutoPauseMetrics(clearMixerMs = clearMixerMs)
        }
        SafetyReconfigureAction.PAUSE_IN_PLACE_KEEP_TRACK -> pauseInPlaceKeepTrackLocked(clearPreferredMixer)
        SafetyReconfigureAction.TEARDOWN_AND_REBIND_ON_PLAY -> teardownRouteInvalidatedSessionLocked(clearPreferredMixer)
        SafetyReconfigureAction.DEFER_RECONFIGURE_UNTIL_PLAY -> {
            val clearMixerMs = clearMixerTimedIfRequested(clearPreferredMixer)
            val pauseIpcMs = deferRouteReconfigureWhilePausedLocked()
            AutoPauseMetrics(pauseIpcMs = pauseIpcMs, clearMixerMs = clearMixerMs)
        }
        SafetyReconfigureAction.DIAGNOSTICS_ONLY -> {
            val clearMixerMs = clearMixerTimedIfRequested(clearPreferredMixer)
            preloadController.clearPreloadedNextLocked()
            host.publishSafetyAndDiagnostics()
            AutoPauseMetrics(clearMixerMs = clearMixerMs)
        }
    }

    private fun clearMixerTimedIfRequested(clearPreferredMixer: Boolean): Long {
        if (!clearPreferredMixer) return 0L
        val clearStart = clock.elapsedRealtimeMs()
        routeControl.clearPreferredMixerAttributes(async = true)
        return metrics.elapsedSince(clearStart)
    }

    private fun pauseInPlaceKeepTrackLocked(clearPreferredMixer: Boolean): AutoPauseMetrics {
        val clearMixerMs = clearMixerTimedIfRequested(clearPreferredMixer && routeControl.hasActivePreferredMixer)
        preloadController.clearPreloadedNextLocked()
        runtimeFlags.isPausedFlag.set(true)
        val pauseStart = clock.elapsedRealtimeMs()
        host.currentActiveSink()?.let { sink ->
            host.logUnderrunDeltaIfAny(sink, trigger = "auto_pause_in_place")
            sink.pause()
        }
        val pauseIpcMs = metrics.elapsedSince(pauseStart)
        val focusStart = clock.elapsedRealtimeMs()
        routeControl.abandonAudioFocusLocked()
        val abandonFocusMs = metrics.elapsedSince(focusStart)
        scratchRunner.markRouteChangedWhilePaused()
        host.publishSafePausedState()
        return AutoPauseMetrics(pauseIpcMs = pauseIpcMs, abandonFocusMs = abandonFocusMs, clearMixerMs = clearMixerMs)
    }

    private fun teardownRouteInvalidatedSessionLocked(clearPreferredMixer: Boolean): AutoPauseMetrics {
        preloadController.clearPreloadedNextLocked()
        val teardownStart = clock.elapsedRealtimeMs()
        host.stopWorkerAndReleaseTrackLocked(clearMixer = clearPreferredMixer, asyncMixerClear = true)
        val pauseIpcMs = metrics.elapsedSince(teardownStart)
        val focusStart = clock.elapsedRealtimeMs()
        routeControl.abandonAudioFocusLocked()
        val abandonFocusMs = metrics.elapsedSince(focusStart)
        if (host.currentTrackRequest() != null) {
            host.setPendingRouteReconfigure(true)
        }
        host.publishSafePausedState()
        return AutoPauseMetrics(pauseIpcMs = pauseIpcMs, abandonFocusMs = abandonFocusMs, clearMixerMs = 0L)
    }

    private fun deferRouteReconfigureWhilePausedLocked(): Long {
        preloadController.clearPreloadedNextLocked()
        runtimeFlags.isPausedFlag.set(true)
        val pauseStart = clock.elapsedRealtimeMs()
        host.currentActiveSink()?.pause()
        val pauseIpcMs = metrics.elapsedSince(pauseStart)
        if (host.currentTrackRequest() != null) {
            host.setPendingRouteReconfigure(true)
        }
        host.publishSafePausedState()
        return pauseIpcMs
    }

    private fun reconfigureTrackIfLoadedLocked(autoResume: Boolean, preserveAutoPauseNotice: Boolean, resumeAttemptSeq: Long) {
        preloadController.clearPreloadedNextLocked()
        val hasTrack = host.currentTrackRequest() != null &&
            (host.currentActiveDecoder() != null || host.hasPendingRouteReconfigure())
        if (!hasTrack) {
            host.publishSafetyAndDiagnostics()
            return
        }
        val canResume = autoResume && (resumeAttemptSeq <= 0L || deferredPlayScheduler.isResumeAttemptValid(resumeAttemptSeq))
        host.loadReconfiguredTrackLocked(
            autoResume = canResume,
            preserveAutoPauseNotice = preserveAutoPauseNotice || (autoResume && !canResume),
            resumeAttemptSeq = if (canResume) resumeAttemptSeq else 0L,
        )
    }
}
