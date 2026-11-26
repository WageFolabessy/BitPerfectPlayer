package com.cangkir.bitperfectplayer.audio

import com.cangkir.bitperfectplayer.diagnostics.DiagnosticLogFormatter
import com.cangkir.bitperfectplayer.diagnostics.LocalDiagnosticLogger

internal data class TrackLoadTimings(
    val loadStartMs: Long,
    val audioTrackBuildStartMs: Long,
    val stopOldMs: Long,
    val decoderOpenMs: Long,
    val rgScanMs: Long,
    val trackCreateMs: Long,
    val wasPreloaded: Boolean,
)

internal data class AutoPauseMetrics(val pauseIpcMs: Long = 0L, val abandonFocusMs: Long = 0L, val clearMixerMs: Long = 0L)

internal data class EngineLifecycleSnapshot(
    val statusName: String,
    val posMs: Long,
    val trackHealthy: Boolean,
    val workerAlive: Boolean,
    val pendingReconfig: Boolean,
    val hasFocus: Boolean,
)

internal data class PlayResumeLatencyReport(
    val path: String,
    val queueWaitMs: Long,
    val focusMs: Long,
    val trackPlayMs: Long,
    val requestStartMs: Long,
    val sampleRateHz: Int,
    val encodingLabel: String,
    val pinnedUsb: Boolean,
)

/**
 * Pure metric calculation helpers shared across [EnginePlaybackMetrics] and JVM unit tests.
 */
internal object EngineMetricMath {
    fun elapsedDurationMs(startMs: Long, nowMs: Long): Long = (nowMs - startMs).coerceAtLeast(0L)

    fun computeUnderrunDelta(previousReported: Int, currentTotal: Int): Int =
        if (currentTotal > previousReported) currentTotal - previousReported else 0
}

/**
 * Internal telemetry and latency metrics helper for [AudioOutputEngine], centralizing underrun
 * delta tracking, slow-stage thresholds, and structured diagnostic log emission.
 */
internal class EnginePlaybackMetrics(
    private val safetyController: AudioSafetyController,
    private val diagnosticLogger: LocalDiagnosticLogger?,
    private val clock: EngineClock,
    private val isDebugBuild: Boolean,
    private val slowStageDebugLogger: ((String) -> Unit)? = null,
) {
    @Volatile
    var lastReportedUnderrunCount: Int = 0
        private set

    @Volatile
    var lastWorkerStartFocusMs: Long = 0L

    @Volatile
    var lastWorkerStartTrackPlayMs: Long = 0L

    fun elapsedSince(startMs: Long): Long = EngineMetricMath.elapsedDurationMs(startMs, clock.elapsedRealtimeMs())

    fun resetUnderrunBaseline() {
        lastReportedUnderrunCount = 0
    }

    fun resetWorkerStartTimings() {
        lastWorkerStartFocusMs = 0L
        lastWorkerStartTrackPlayMs = 0L
    }

    fun logUnderrunDeltaIfAny(sink: AudioOutputSink?, trigger: String, sampleRateHz: Int, encodingLabel: String) {
        val logger = diagnosticLogger ?: return
        if (!logger.isEnabled || sink == null) return
        val current = sink.readUnderrunCount()
        val delta = EngineMetricMath.computeUnderrunDelta(lastReportedUnderrunCount, current)
        if (delta > 0) {
            lastReportedUnderrunCount = current
            logger.logPerf("TRACK_UNDERRUNS") {
                linkedMapOf(
                    "delta" to delta,
                    "total" to current,
                    "trigger" to trigger,
                    "sr" to sampleRateHz,
                    "enc" to encodingLabel,
                    "mode" to safetyController.state().mode.name,
                )
            }
        }
    }

    fun logTrackOpen(request: EngineTrackRequest) {
        diagnosticLogger?.logAudio("TRACK_OPEN") {
            linkedMapOf(
                "fp" to request.fingerprintPrefix,
                "ext" to DiagnosticLogFormatter.extractSafeFileExtension(request.fileName, request.mimeType),
                "sizeBytes" to request.fileSizeBytes,
                "startPosMs" to request.startPositionMs,
                "autoPlay" to request.autoPlay,
                "mode" to safetyController.state().mode.name,
            )
        }
    }

    fun logTrackLoadLatency(request: EngineTrackRequest, format: DecodedPcmFormat, timings: TrackLoadTimings, startedPlaying: Boolean) {
        val loadMs = elapsedSince(timings.loadStartMs)
        diagnosticLogger?.logPerf("TRACK_LOAD_LATENCY") {
            linkedMapOf(
                "stopOldMs" to timings.stopOldMs,
                "decoderOpenMs" to timings.decoderOpenMs,
                "rgScanMs" to timings.rgScanMs,
                "trackCreateMs" to timings.trackCreateMs,
                "focusMs" to lastWorkerStartFocusMs,
                "trackPlayMs" to lastWorkerStartTrackPlayMs,
                "loadMs" to loadMs,
                "preloaded" to timings.wasPreloaded,
                "autoPlay" to request.autoPlay,
                "startedPlaying" to startedPlaying,
                "sr" to format.sampleRateHz,
                "enc" to format.encoding.name,
                "ch" to format.channelCount,
                "mode" to safetyController.state().mode.name,
            )
        }
    }

    fun logPlayResumeLatency(report: PlayResumeLatencyReport) {
        val safety = safetyController.state()
        val totalMs = elapsedSince(report.requestStartMs)
        diagnosticLogger?.logPerf("PLAY_RESUME_LATENCY") {
            linkedMapOf(
                "path" to report.path,
                "queueWaitMs" to report.queueWaitMs,
                "focusMs" to report.focusMs,
                "trackPlayMs" to report.trackPlayMs,
                "totalMs" to totalMs,
                "sr" to report.sampleRateHz,
                "enc" to report.encodingLabel,
                "mode" to safety.mode.name,
                "usb" to safety.isUsbAudioConnected,
                "pinnedUsb" to report.pinnedUsb,
            )
        }
    }

    fun logSlowAudioTrackBuildAndStart(startMs: Long, sampleRateHz: Int) {
        val elapsedMs = elapsedSince(startMs)
        if (AudioStartPathPolicy.shouldLogSlowAudioStage(isDebugBuild, elapsedMs)) {
            slowStageDebugLogger?.invoke(
                "Slow audio stage [audioTrackBuildAndStart]: ${elapsedMs}ms " +
                    "(sr=${sampleRateHz}Hz, mode=${safetyController.state().mode})",
            )
        }
        if (AudioStartPathPolicy.shouldRecordSlowAudioStage(elapsedMs)) {
            val safety = safetyController.state()
            diagnosticLogger?.logPerf("SLOW_AUDIO_STAGE") {
                linkedMapOf(
                    "stage" to "audioTrackBuildAndStart",
                    "elapsedMs" to elapsedMs,
                    "sr" to sampleRateHz,
                    "mode" to safety.mode.name,
                    "usb" to safety.isUsbAudioConnected,
                )
            }
        }
    }

    fun logAutoPauseLatency(
        reason: AutoPauseReason,
        action: SafetyReconfigureAction,
        uiUpdateMs: Long,
        metrics: AutoPauseMetrics,
        safetyStartMs: Long,
        sampleRateHz: Int,
    ) {
        val totalMs = elapsedSince(safetyStartMs)
        diagnosticLogger?.logPerf("AUTO_PAUSE_LATENCY") {
            linkedMapOf(
                "reason" to reason.name,
                "action" to action.name,
                "uiUpdateMs" to uiUpdateMs,
                "pauseIpcMs" to metrics.pauseIpcMs,
                "abandonFocusMs" to metrics.abandonFocusMs,
                "clearMixerMs" to metrics.clearMixerMs,
                "totalMs" to totalMs,
                "sr" to sampleRateHz,
                "mode" to safetyController.state().mode.name,
            )
        }
        diagnosticLogger?.requestFlush()
    }

    fun logEngineLifecycle(event: String, source: String, snapshot: EngineLifecycleSnapshot) {
        val logger = diagnosticLogger ?: return
        if (!logger.isEnabled) return
        logger.logAudio("ENGINE_LIFECYCLE") {
            linkedMapOf(
                "event" to event,
                "source" to source,
                "status" to snapshot.statusName,
                "posMs" to snapshot.posMs,
                "trackHealthy" to snapshot.trackHealthy,
                "workerAlive" to snapshot.workerAlive,
                "pendingReconfig" to snapshot.pendingReconfig,
                "hasFocus" to snapshot.hasFocus,
            )
        }
    }

    fun logAudioTrackInitError(error: Throwable, format: DecodedPcmFormat) {
        diagnosticLogger?.logError("AUDIOTRACK_INIT_ERROR") {
            linkedMapOf(
                "errorType" to error.javaClass.simpleName,
                "msg" to (error.message ?: "unknown"),
                "sr" to format.sampleRateHz,
                "ch" to format.channelCount,
                "enc" to format.encoding.name,
                "mode" to safetyController.state().mode.name,
            )
        }
    }

    fun logAudioTrackStartRetry(sampleRateHz: Int) {
        diagnosticLogger?.logAudio("AUDIOTRACK_START_RETRY") {
            linkedMapOf("sr" to sampleRateHz, "mode" to safetyController.state().mode.name)
        }
    }

    fun logAutoPauseEvent(command: SafetyCommand, action: SafetyReconfigureAction) {
        diagnosticLogger?.logAudio("AUTO_PAUSE") {
            linkedMapOf(
                "reason" to (command.autoPauseReason?.name ?: "UNKNOWN"),
                "clearMixer" to command.requiresClearPreferredMixerAttributes,
                "mode" to safetyController.state().mode.name,
                "action" to action.name,
            )
        }
    }

    fun logAudioModeChanged(mode: AudioOutputMode, clearMixer: Boolean) {
        diagnosticLogger?.logAudio("AUDIO_MODE_CHANGED") {
            linkedMapOf("mode" to mode.name, "clearMixer" to clearMixer)
        }
    }

    fun logGaplessSwap(request: EngineTrackRequest, format: DecodedPcmFormat) {
        diagnosticLogger?.logAudio("GAPLESS_SWAP") {
            linkedMapOf(
                "fp" to request.fingerprintPrefix,
                "ext" to DiagnosticLogFormatter.extractSafeFileExtension(request.fileName, request.mimeType),
                "sr" to format.sampleRateHz,
                "enc" to format.encoding.name,
                "ch" to format.channelCount,
                "mode" to safetyController.state().mode.name,
            )
        }
    }

    fun logMidStreamFormatChange(newFormat: DecodedPcmFormat) {
        diagnosticLogger?.logAudio("MID_STREAM_FORMAT_CHANGE") {
            linkedMapOf(
                "sr" to newFormat.sampleRateHz,
                "enc" to newFormat.encoding.name,
                "ch" to newFormat.channelCount,
            )
        }
    }

    fun logPlaybackAutoRecover(attempt: Int, isDeadTrack: Boolean, error: Throwable, resumePosMs: Long) {
        diagnosticLogger?.logAudio("PLAYBACK_AUTO_RECOVER") {
            linkedMapOf(
                "attempt" to attempt,
                "deadTrack" to isDeadTrack,
                "errType" to error.javaClass.simpleName,
                "posMs" to resumePosMs,
                "mode" to safetyController.state().mode.name,
            )
        }
    }

    fun logWorkerError(message: String, fileName: String?) {
        diagnosticLogger?.logError("PLAYBACK_WORKER_ERROR") {
            linkedMapOf(
                "msg" to DiagnosticLogFormatter.redactFilenameAndPaths(message, fileName),
                "mode" to safetyController.state().mode.name,
            )
        }
    }

    fun logPlayResumeAborted(stage: String, pauseTrack: Boolean, trackPlayMs: Long, statusName: String) {
        diagnosticLogger?.logAudio("PLAY_RESUME_ABORTED") {
            linkedMapOf(
                "stage" to stage,
                "pauseTrack" to pauseTrack,
                "trackPlayMs" to trackPlayMs,
                "status" to statusName,
                "mode" to safetyController.state().mode.name,
            )
        }
    }

    fun logPausedSeekLatency(seekStartMs: Long, targetMs: Long, actualMs: Long) {
        diagnosticLogger?.logPerf("SEEK_LATENCY") {
            linkedMapOf(
                "seekMs" to elapsedSince(seekStartMs),
                "targetMs" to targetMs,
                "actualMs" to actualMs,
                "state" to "paused",
            )
        }
    }

    fun logPausedSeekDeferredRebuild(error: Throwable, targetMs: Long) {
        diagnosticLogger?.logError("PAUSED_SEEK_DEFER_REBUILD") {
            linkedMapOf("errType" to error.javaClass.simpleName, "targetMs" to targetMs)
        }
    }

    fun logPlayDeferredForRouteFlap(delayMs: Long) {
        diagnosticLogger?.logAudio("PLAY_DEFERRED_ROUTE_FLAP") {
            linkedMapOf("delayMs" to delayMs)
        }
    }

    fun logPlayAbortedForRouteFlap(waitedMs: Long) {
        diagnosticLogger?.logAudio("PLAY_ABORTED_ROUTE_FLAP") {
            linkedMapOf("waitedMs" to waitedMs)
        }
    }

    fun requestFlush() {
        diagnosticLogger?.requestFlush()
    }
}
