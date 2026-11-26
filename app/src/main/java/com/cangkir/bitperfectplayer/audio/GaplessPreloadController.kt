package com.cangkir.bitperfectplayer.audio

import android.net.Uri
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal data class PreloadedTrack(
    val request: EngineTrackRequest,
    val decoder: PcmAudioDecoder,
    val replayGainMetadata: ReplayGainMetadata?,
    val generation: Long,
)

/**
 * Internal collaborator managing background gapless next-track decoder pre-loading on the
 * dedicated `"BitPerfectGaplessPreload"` single-thread executor, OOM-safe header-only ReplayGain
 * pre-scanning, generation/URI validation under `preloadLock`, and leak-free decoder disposal off
 * the main thread.
 */
internal class GaplessPreloadController(
    private val decoderFactory: PcmDecoderFactory,
    private val releasedFlag: AtomicBoolean,
    private val loadGeneration: AtomicLong,
    private val shouldReadReplayGain: () -> Boolean = { true },
    private val preloadExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "BitPerfectGaplessPreload").apply { isDaemon = true }
    },
) {
    private val preloadLock = Any()

    @Volatile
    var gaplessEnabled: Boolean = true
        private set

    @Volatile
    var queuedNextRequest: EngineTrackRequest? = null
        private set

    private var preloadedTrack: PreloadedTrack? = null
    private var preloadingUri: Uri? = null

    fun setGaplessEnabled(enabled: Boolean) {
        if (gaplessEnabled == enabled) return
        gaplessEnabled = enabled
        if (!enabled) {
            clearPreloadedNextAsync()
        }
    }

    /**
     * Supplies the upcoming queue track for background gapless decoder pre-loading (or `null` when
     * at end of queue). Automatically cancels/closes any stale pre-loaded decoder if the upcoming
     * track URI changed or was cleared.
     */
    fun setNextTrack(request: EngineTrackRequest?, isPlaying: Boolean, currentPositionMs: Long, durationMs: Long) {
        queuedNextRequest = request
        val staleDecoder: PcmAudioDecoder? = synchronized(preloadLock) {
            val existing = preloadedTrack
            if (existing != null && (request == null || existing.request.uri != request.uri)) {
                preloadedTrack = null
                preloadingUri = null
                existing.decoder
            } else {
                if (preloadingUri != null && (request == null || preloadingUri != request.uri)) {
                    preloadingUri = null
                }
                null
            }
        }
        if (staleDecoder != null) {
            closeDecoderOffMainThread(staleDecoder)
        }
        if (request != null &&
            isPlaying &&
            GaplessTransitionPolicy.shouldStartPreload(
                gaplessEnabled = gaplessEnabled,
                hasNextTrack = true,
                isAlreadyPreloadingOrReady = isNextPreloadedOrLoading(request.uri),
                currentPositionMs = currentPositionMs,
                durationMs = durationMs,
            )
        ) {
            triggerPreloadAsync(request, loadGeneration.get())
        }
    }

    fun maybeTriggerPreloadForPosition(currentPositionMs: Long, durationMs: Long, expectedGeneration: Long) {
        val nextReq = queuedNextRequest
        if (GaplessTransitionPolicy.shouldStartPreload(
                gaplessEnabled = gaplessEnabled,
                hasNextTrack = nextReq != null,
                isAlreadyPreloadingOrReady = isNextPreloadedOrLoading(nextReq?.uri),
                currentPositionMs = currentPositionMs,
                durationMs = durationMs,
            )
        ) {
            triggerPreloadAsync(nextReq, expectedGeneration)
        }
    }

    fun isNextPreloadedOrLoading(uri: Uri?): Boolean {
        if (uri == null) return true
        return synchronized(preloadLock) {
            preloadedTrack?.request?.uri == uri || preloadingUri == uri
        }
    }

    fun triggerPreloadAsync(request: EngineTrackRequest?, expectedGeneration: Long) {
        if (request == null || releasedFlag.get() || !gaplessEnabled) return
        val shouldLaunch = synchronized(preloadLock) {
            if (preloadedTrack?.request?.uri == request.uri || preloadingUri == request.uri) {
                false
            } else {
                preloadingUri = request.uri
                true
            }
        }
        if (!shouldLaunch) return

        runCatching {
            preloadExecutor.execute {
                executePreloadTask(request, expectedGeneration)
            }
        }.onFailure {
            clearPreloadingUri(request.uri)
        }
    }

    fun takePreloadedTrackIfReady(expectedGeneration: Long): PreloadedTrack? {
        var staleToClose: PcmAudioDecoder? = null
        val ready = synchronized(preloadLock) {
            val existing = preloadedTrack ?: return@synchronized null
            preloadedTrack = null
            preloadingUri = null
            val matches = existing.generation == expectedGeneration &&
                queuedNextRequest?.uri == existing.request.uri
            if (matches) {
                existing
            } else {
                staleToClose = existing.decoder
                null
            }
        }
        staleToClose?.let { runCatching { it.close() } }
        return ready
    }

    /**
     * Evaluates [GaplessTransitionPolicy.decide] at natural end-of-stream. When
     * [GaplessTransitionAction.SEAMLESS_REUSE] applies, returns the ready [PreloadedTrack];
     * otherwise closes any unused preloaded decoder and returns `null`.
     */
    fun pollSeamlessPreloadedTrack(currentFormat: DecodedPcmFormat, expectedGeneration: Long): PreloadedTrack? {
        val preloaded = takePreloadedTrackIfReady(expectedGeneration)
        val action = GaplessTransitionPolicy.decide(
            gaplessEnabled = gaplessEnabled,
            hasNextTrack = queuedNextRequest != null,
            currentFormat = currentFormat.toStreamSignature(),
            nextFormat = preloaded?.decoder?.format?.toStreamSignature(),
        )
        if (action == GaplessTransitionAction.SEAMLESS_REUSE && preloaded != null) {
            return preloaded
        }
        preloaded?.let { runCatching { it.decoder.close() } }
        return null
    }

    fun takePreloadedTrackMatchingUri(targetUri: Uri): PreloadedTrack? = synchronized(preloadLock) {
        val existing = preloadedTrack ?: return@synchronized null
        if (existing.request.uri != targetUri) return@synchronized null
        preloadedTrack = null
        preloadingUri = null
        existing
    }

    fun clearPreloadedUnlessMatchesAsync(targetUri: Uri, startPositionMs: Long) {
        if (startPositionMs == 0L) {
            val matches = synchronized(preloadLock) {
                preloadedTrack?.request?.uri == targetUri || preloadingUri == targetUri
            }
            if (matches) return
        }
        clearPreloadedNextAsync()
    }

    fun consumeQueuedNextRequestIfMatches(uri: Uri) {
        if (queuedNextRequest?.uri == uri) {
            queuedNextRequest = null
        }
    }

    fun clearQueuedAndPreloadedAsync() {
        queuedNextRequest = null
        clearPreloadedNextAsync()
    }

    fun clearPreloadedNextAsync() {
        val toClose = synchronized(preloadLock) {
            val prev = preloadedTrack?.decoder
            preloadedTrack = null
            preloadingUri = null
            prev
        }
        if (toClose != null) {
            closeDecoderOffMainThread(toClose)
        }
    }

    fun clearPreloadedNextLocked() {
        val toClose = synchronized(preloadLock) {
            val prev = preloadedTrack?.decoder
            preloadedTrack = null
            preloadingUri = null
            prev
        }
        toClose?.let { runCatching { it.close() } }
    }

    fun shutdown() {
        queuedNextRequest = null
        preloadExecutor.shutdownNow()
    }

    fun readReplayGainMetadata(uri: Uri, fileName: String, mimeType: String?): ReplayGainMetadata? =
        decoderFactory.readReplayGainMetadata(uri = uri, fileName = fileName, mimeType = mimeType)

    private fun isPreloadStillValid(request: EngineTrackRequest, expectedGeneration: Long): Boolean {
        val engineReady = !releasedFlag.get() && gaplessEnabled
        return engineReady && loadGeneration.get() == expectedGeneration && queuedNextRequest?.uri == request.uri
    }

    private fun executePreloadTask(request: EngineTrackRequest, expectedGeneration: Long) {
        if (!isPreloadStillValid(request, expectedGeneration)) {
            clearPreloadingUri(request.uri)
            return
        }

        val rgMeta = if (shouldReadReplayGain()) {
            readReplayGainMetadata(
                uri = request.uri,
                fileName = request.fileName,
                mimeType = request.mimeType,
            )
        } else {
            null
        }
        val openedDecoder = openDecoderForPreload(request, expectedGeneration)
        if (openedDecoder == null) {
            clearPreloadingUri(request.uri)
            return
        }

        var decoderToClose: PcmAudioDecoder? = null
        synchronized(preloadLock) {
            if (isPreloadStillValid(request, expectedGeneration)) {
                decoderToClose = preloadedTrack?.decoder
                preloadedTrack = PreloadedTrack(
                    request = request,
                    decoder = openedDecoder,
                    replayGainMetadata = rgMeta,
                    generation = expectedGeneration,
                )
            } else {
                decoderToClose = openedDecoder
            }
            if (preloadingUri == request.uri) preloadingUri = null
        }
        decoderToClose?.let { runCatching { it.close() } }
    }

    private fun openDecoderForPreload(request: EngineTrackRequest, expectedGeneration: Long): PcmAudioDecoder? = try {
        decoderFactory.open(
            request = request,
            isCancelled = { !isPreloadStillValid(request, expectedGeneration) },
        )
    } catch (_: AudioDecodeException.Cancelled) {
        null
    } catch (e: AudioDecodeException) {
        Log.w(TAG, "Gapless preload skipped for ${request.fileName}: ${e.message}")
        null
    } catch (e: SecurityException) {
        Log.w(TAG, "Gapless preload skipped for ${request.fileName}: ${e.message}")
        null
    } catch (e: IllegalStateException) {
        Log.w(TAG, "Gapless preload skipped for ${request.fileName}: ${e.message}")
        null
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "Gapless preload skipped for ${request.fileName}: ${e.message}")
        null
    }

    private fun clearPreloadingUri(uri: Uri) {
        synchronized(preloadLock) {
            if (preloadingUri == uri) preloadingUri = null
        }
    }

    internal fun closeDecoderOffMainThread(decoder: PcmAudioDecoder) {
        if (releasedFlag.get()) {
            runCatching { decoder.close() }
            return
        }
        runCatching {
            preloadExecutor.execute { runCatching { decoder.close() } }
        }.onFailure {
            runCatching { decoder.close() }
        }
    }

    companion object {
        private const val TAG = "BitPerfectAudio"
    }
}
