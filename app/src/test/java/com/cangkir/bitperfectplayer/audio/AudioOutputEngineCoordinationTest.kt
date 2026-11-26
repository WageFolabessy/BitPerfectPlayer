package com.cangkir.bitperfectplayer.audio

import android.app.Application
import android.net.Uri
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Deterministic JVM multi-component coordination tests exercising the real [AudioOutputEngine],
 * [TrackLoadCoordinator], [AudioSafetyReconfigureHandler], [DeferredPlayScheduler],
 * [GaplessPreloadController], [TurntableScratchRunner], and [PlaybackPositionTracker] via thin
 * fake [AudioOutputSink], [PcmDecoderFactory], and [AudioRouteControl] seams with zero Android
 * audio hardware dependencies.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AudioOutputEngineCoordinationTest {

    private var activeEngine: AudioOutputEngine? = null

    @After
    fun tearDown() {
        activeEngine?.release()
        activeEngine = null
    }

    @Test
    fun rapidBurstTrackSwitches_cancelStaleGenerationsWithZeroLeakedDecodersOrSinks() {
        val enteredFirstOpen = CountDownLatch(1)
        val allowFirstOpenToFinish = CountDownLatch(1)
        val track1 = testRequest("content://test/track1.flac", "01.flac")
        val track2 = testRequest("content://test/track2.flac", "02.flac")
        val track3 = testRequest("content://test/track3.flac", "03.flac")
        val track4 = testRequest("content://test/track4.flac", "04.flac")

        val harness = createHarness(
            decoderOpener = { req, isCancelled ->
                if (req.uri == track1.uri) {
                    enteredFirstOpen.countDown()
                    allowFirstOpenToFinish.await(5, TimeUnit.SECONDS)
                    if (isCancelled()) throw AudioDecodeException.Cancelled()
                }
                FakePcmDecoder(
                    format = pcm16Format(sampleRateHz = 48_000, durationMs = 60_000L),
                    totalChunks = 200,
                    chunkFrames = 480,
                )
            },
        )

        harness.engine.loadTrack(track1)
        assertTrue("track1 should enter decoder open", enteredFirstOpen.await(3, TimeUnit.SECONDS))

        // Rapid burst switches while track1 is still opening.
        harness.engine.loadTrack(track2)
        harness.engine.loadTrack(track3)
        harness.engine.loadTrack(track4)
        allowFirstOpenToFinish.countDown()

        awaitCondition("Final burst track4 should enter PLAYING") {
            val st = harness.engine.state.value
            st.status == PlaybackStatus.PLAYING && st.activeTrackUri == track4.uri
        }

        // Only the winning track4 decoder & sink may remain active; all stale ones must be closed/released.
        val openDecoders = harness.decoderFactory.openedDecoders.filterNot { it.closed.get() }
        assertEquals("Exactly one active decoder should remain open", 1, openDecoders.size)

        val liveSinks = harness.sinkFactory.createdSinks.filterNot { it.released.get() }
        assertEquals("Exactly one active sink should remain live", 1, liveSinks.size)
        assertTrue("Winning sink must be playing", liveSinks.single().isPlaying())

        harness.engine.release()
        awaitCondition("All decoders and sinks must close on release") {
            harness.decoderFactory.openedDecoders.all { it.closed.get() } &&
                harness.sinkFactory.createdSinks.all { it.released.get() }
        }
    }

    @Test
    fun playPauseRouteChangeInPlaceAndResume_preservesPositionContinuityWithRouteHeadRebase() {
        val track = testRequest("content://test/rebase.flac", "rebase.flac", expectedDurationMs = 60_000L)
        val allowMoreChunksAfterPause = AtomicBoolean(true)
        val reachedTenSeconds = CountDownLatch(1)

        val harness = createHarness(
            decoderOpener = { _, _ ->
                FakePcmDecoder(
                    format = pcm16Format(sampleRateHz = 48_000, durationMs = 60_000L),
                    totalChunks = 2_000,
                    chunkFrames = 2_400, // 9,600 bytes (<= 16,384-byte buffer) = exact 50 ms per chunk
                    onAfterRead = { emitted ->
                        if (emitted == 199) {
                            // Exceed the 50 ms UI position throttle so chunk 200 publishes exact 10,000 ms.
                            Thread.sleep(60L)
                        } else if (emitted == 200) {
                            reachedTenSeconds.countDown()
                        } else if (emitted == 201) {
                            while (!allowMoreChunksAfterPause.get() && !Thread.currentThread().isInterrupted) {
                                Thread.sleep(2L)
                            }
                        } else if (emitted == 240) {
                            // Exceed the 50 ms UI position throttle after resume so chunk 241 publishes >= 12,000 ms.
                            Thread.sleep(60L)
                        }
                    },
                )
            },
        )

        allowMoreChunksAfterPause.set(false)
        harness.engine.loadTrack(track)
        assertTrue("Stream should decode 10 seconds of PCM", reachedTenSeconds.await(5, TimeUnit.SECONDS))

        awaitCondition("Position should reach >= 9,500 ms before route change") {
            harness.engine.playbackPositionMs.value >= 9_500L
        }
        val posBeforeRouteChange = harness.engine.playbackPositionMs.value
        val activeSink = harness.sinkFactory.createdSinks.single()

        // Safe-Mode unpinned USB plug-in triggers PAUSE_IN_PLACE_KEEP_TRACK.
        val usbCmd = harness.safetyController.onAudioDeviceConnected(deviceName = "KZ EDX Pro 2", isUsbAudio = true)
        harness.routeControl.dispatchSafetyCommand(usbCmd)

        awaitCondition("Engine should pause in place keeping the healthy sink") {
            harness.engine.state.value.status == PlaybackStatus.PAUSED &&
                activeSink.pauseCount.get() >= 1 &&
                !activeSink.released.get()
        }
        assertEquals("PAUSE_IN_PLACE_KEEP_TRACK must reuse the existing sink", 1, harness.sinkFactory.createdSinks.size)

        // Simulate OEM HAL resetting AudioTrack.playbackHeadPosition to 0 across route switch.
        activeSink.headFrames.set(0L)
        allowMoreChunksAfterPause.set(true)

        // First Play tap resumes in place and triggers ROUTE_HEAD_REBASE.
        harness.engine.play()

        awaitCondition("Position must continue monotonically past pre-pause position without dropping to 0") {
            harness.engine.state.value.status == PlaybackStatus.PLAYING &&
                harness.engine.playbackPositionMs.value >= posBeforeRouteChange + 1_500L
        }

        assertEquals("Still the same reused sink after in-place resume", 1, harness.sinkFactory.createdSinks.size)
        assertTrue(
            "Rebased playback position (${harness.engine.playbackPositionMs.value}ms) must exceed pre-pause (${posBeforeRouteChange}ms)",
            harness.engine.playbackPositionMs.value >= posBeforeRouteChange + 1_500L,
        )
    }

    @Test
    fun autoPauseSupersedingInFlightResume_abortsPlayAndClearsPreferredMixerAttributes() {
        val enteredPlayCall = CountDownLatch(1)
        val allowPlayCallToReturn = CountDownLatch(1)
        val blockNextPlay = AtomicBoolean(false)
        val track = testRequest("content://test/bitperfect.flac", "hi_res.flac")

        val harness = createHarness(
            decoderOpener = { _, _ ->
                FakePcmDecoder(
                    format = pcm16Format(sampleRateHz = 96_000, durationMs = 60_000L),
                    totalChunks = 500,
                    chunkFrames = 960,
                )
            },
            sinkConfigurator = { sink ->
                sink.onPlayHook = {
                    if (blockNextPlay.compareAndSet(true, false)) {
                        enteredPlayCall.countDown()
                        allowPlayCallToReturn.await(5, TimeUnit.SECONDS)
                    }
                    true
                }
            },
        )

        // Connect USB DAC and explicitly opt into Bit-Perfect mode with active preferred mixer.
        harness.safetyController.onAudioDeviceConnected(deviceName = "KZ EDX Pro 2", isUsbAudio = true)
        harness.routeControl.hasActivePreferredMixer = true
        harness.routeControl.activeNegotiatedMixerBehavior = NegotiatedMixerBehavior.BIT_PERFECT
        harness.engine.requestBitPerfectMode()
        harness.engine.confirmBitPerfectWarning()
        assertEquals(AudioOutputMode.BIT_PERFECT_OPT_IN, harness.safetyController.state().mode)

        harness.engine.loadTrack(track.copy(autoPlay = false))
        awaitCondition("Track should load in PAUSED state") {
            harness.engine.state.value.status == PlaybackStatus.PAUSED &&
                harness.sinkFactory.createdSinks.isNotEmpty()
        }

        // Start a play() resume that blocks inside sink.play(), simulating a slow HAL Binder call.
        blockNextPlay.set(true)
        harness.engine.play()
        assertTrue("Resume must enter sink.play()", enteredPlayCall.await(3, TimeUnit.SECONDS))

        // While play() is mid-flight, USB disconnect fires AUTO_PAUSE.
        val disconnectCmd = harness.safetyController.onUsbDisconnected("Internal Speaker")
        harness.routeControl.dispatchSafetyCommand(disconnectCmd)
        allowPlayCallToReturn.countDown()

        awaitCondition("AUTO_PAUSE must supersede in-flight resume and clear preferred mixer") {
            harness.routeControl.clearMixerCount.get() >= 1 &&
                harness.engine.state.value.status == PlaybackStatus.PAUSED &&
                !harness.engine.state.value.isPreparingPlayback
        }

        assertEquals(AudioOutputMode.SAFE_VOLUME_MODE, harness.safetyController.state().mode)
        assertEquals(PlaybackStatus.PAUSED, harness.engine.state.value.status)
        assertFalse("Superseded resume must not leave sink playing", harness.sinkFactory.createdSinks.last().isPlaying())
        assertTrue(
            "Preferred mixer attributes must be cleared on USB disconnect AUTO_PAUSE",
            harness.routeControl.clearMixerCount.get() >= 1,
        )
    }

    @Test
    fun gaplessTransition_reusesSinkOnMatchingFormatAndDrainsOnFormatMismatch() {
        val track1 = testRequest("content://test/gapless_1.flac", "01.flac", expectedDurationMs = 1_000L)
        val track2 = testRequest("content://test/gapless_2.flac", "02.flac", expectedDurationMs = 1_000L)
        val track3Mismatch = testRequest("content://test/gapless_3_96k.flac", "03_96k.flac", expectedDurationMs = 1_000L)

        val track1Gate = CountDownLatch(1)
        val track2Gate = CountDownLatch(1)
        val gaplessEvents = CopyOnWriteArrayList<Uri>()
        val completedCount = AtomicInteger(0)

        val harness = createHarness(
            onTrackCompleted = { completedCount.incrementAndGet() },
            onGaplessTransition = { req -> gaplessEvents.add(req.uri) },
            decoderOpener = { req, _ ->
                when (req.uri) {
                    track1.uri -> FakePcmDecoder(
                        format = pcm16Format(sampleRateHz = 48_000, durationMs = 1_000L),
                        totalChunks = 10,
                        chunkFrames = 4_800,
                        onAfterRead = { emitted ->
                            if (emitted == 1) track1Gate.await(5, TimeUnit.SECONDS)
                        },
                    )
                    track2.uri -> FakePcmDecoder(
                        format = pcm16Format(sampleRateHz = 48_000, durationMs = 1_000L),
                        totalChunks = 10,
                        chunkFrames = 4_800,
                        onAfterRead = { emitted ->
                            if (emitted == 1) track2Gate.await(5, TimeUnit.SECONDS)
                        },
                    )
                    else -> FakePcmDecoder(
                        format = pcm16Format(sampleRateHz = 96_000, durationMs = 1_000L),
                        totalChunks = 10,
                        chunkFrames = 9_600,
                    )
                }
            },
        )

        harness.engine.setGaplessEnabled(true)
        harness.engine.loadTrack(track1)
        awaitCondition("track1 should start playing") {
            harness.engine.state.value.status == PlaybackStatus.PLAYING &&
                harness.engine.state.value.activeTrackUri == track1.uri
        }

        // Queue matching-format track2; wait until preload executor finishes storing it before EOS.
        harness.engine.setNextTrack(track2)
        awaitCondition("track2 should start preloading in background") {
            harness.decoderFactory.openedUris.contains(track2.uri)
        }
        harness.awaitPreloadFence()
        track1Gate.countDown()

        awaitCondition("track1 -> track2 should transition via SEAMLESS_REUSE") {
            gaplessEvents.contains(track2.uri) &&
                harness.engine.state.value.activeTrackUri == track2.uri &&
                harness.engine.state.value.status == PlaybackStatus.PLAYING
        }

        val sharedSink = harness.sinkFactory.createdSinks.single()
        assertEquals("SEAMLESS_REUSE must keep exact same sink", 1, harness.sinkFactory.createdSinks.size)
        assertEquals("SEAMLESS_REUSE must not drain or fire onTrackCompleted", 0, sharedSink.drainCleanlyCount.get())
        assertEquals(0, completedCount.get())
        assertTrue("track1 decoder must be closed after seamless swap", harness.decoderFactory.openedDecoders.first().closed.get())

        // Now queue format-mismatched 96 kHz track3; wait for preload completion, then let track2 reach EOS.
        harness.engine.setNextTrack(track3Mismatch)
        awaitCondition("track3Mismatch should preload before track2 EOS") {
            harness.decoderFactory.openedUris.contains(track3Mismatch.uri)
        }
        harness.awaitPreloadFence()
        track2Gate.countDown()

        awaitCondition("Format mismatch must drain sink cleanly and report track completion") {
            completedCount.get() == 1 && harness.engine.state.value.status == PlaybackStatus.COMPLETED
        }

        assertEquals("Format mismatch must trigger clean EOS drain", 1, sharedSink.drainCleanlyCount.get())
        assertFalse("Format mismatch must not trigger seamless gapless callback", gaplessEvents.contains(track3Mismatch.uri))
        val mismatchedDecoder = harness.decoderFactory.openedDecoders.last()
        assertTrue("Unused mismatched preloaded decoder must be closed cleanly", mismatchedDecoder.closed.get())
    }

    @Test
    fun replayGainScaling_appliesGainInSafeModeAndBypassesUnconditionallyInBitPerfectMode() {
        val track = testRequest("content://test/rg_track.flac", "rg_track.flac", expectedDurationMs = 60_000L)
        val rawSampleValue: Short = 16_000 // 0x3E80
        val rgMetadata = ReplayGainMetadata(trackGainDb = -6.0f, trackPeak = 1.0f)

        val harness = createHarness(
            replayGainByUri = mapOf(track.uri to rgMetadata),
            decoderOpener = { _, _ ->
                FakePcmDecoder(
                    format = pcm16Format(sampleRateHz = 48_000, durationMs = 60_000L),
                    totalChunks = 500,
                    chunkFrames = 480,
                    constantSample16 = rawSampleValue,
                )
            },
        )

        harness.engine.setReplayGainConfig(
            ReplayGainConfig(mode = ReplayGainMode.TRACK, preampDb = 0.0f, preventClipping = true),
        )

        // Phase 1: Safe Mode -> ReplayGain scales samples (~-6 dB => ~0.5012x => ~8,019).
        harness.engine.loadTrack(track)
        awaitCondition("Safe Mode sink should receive ReplayGain-scaled PCM chunks") {
            harness.sinkFactory.createdSinks.firstOrNull()?.writtenChunks?.isNotEmpty() == true
        }

        val safeSink = harness.sinkFactory.createdSinks.first()
        val safeChunk = safeSink.writtenChunks.first()
        assertTrue("Safe Mode gain should be attenuated (~0.5012)", safeChunk.linearGain in 0.49f..0.52f)
        val scaledSample = readFirstPcm16LeSample(safeChunk.outputBytes)
        assertTrue(
            "Safe Mode sample ($scaledSample) should be attenuated near 8,019",
            abs(scaledSample - 8_019) <= 25,
        )
        val safeStage3 = harness.engine.state.value.diagnostics.stages
            .first { it.stage == AudioPathStage.APP_TO_FRAMEWORK }
        assertTrue(
            "Diagnostics Stage 3 should report active Safe-Mode ReplayGain (${safeStage3.detail})",
            safeStage3.detail.contains("Safe Mode") && safeStage3.detail.contains("-6.0 dB"),
        )

        // Phase 2: Switch to BIT_PERFECT_OPT_IN on USB DAC -> unconditional 1.0f bypass & bit-identical PCM.
        harness.safetyController.onAudioDeviceConnected(deviceName = "KZ EDX Pro 2", isUsbAudio = true)
        harness.engine.requestBitPerfectMode()
        harness.engine.confirmBitPerfectWarning()

        awaitCondition("Bit-Perfect mode should rebuild sink and write bit-identical PCM chunks") {
            harness.safetyController.state().mode == AudioOutputMode.BIT_PERFECT_OPT_IN &&
                harness.sinkFactory.createdSinks.size >= 2 &&
                harness.sinkFactory.createdSinks.last().writtenChunks.isNotEmpty()
        }

        val bitPerfectSink = harness.sinkFactory.createdSinks.last()
        val bitPerfectChunk = bitPerfectSink.writtenChunks.first()
        assertEquals("Bit-Perfect mode must unconditionally bypass ReplayGain to 1.0f", 1.0f, bitPerfectChunk.linearGain, 0.0f)
        assertArrayEquals(
            "Bit-Perfect PCM output must be 100% bit-identical to decoded source bytes",
            bitPerfectChunk.inputBytes,
            bitPerfectChunk.outputBytes,
        )
        assertEquals(rawSampleValue.toInt(), readFirstPcm16LeSample(bitPerfectChunk.outputBytes))
        val bpStage3 = harness.engine.state.value.diagnostics.stages
            .first { it.stage == AudioPathStage.APP_TO_FRAMEWORK }
        assertTrue(
            "Diagnostics Stage 3 must report ReplayGain: Bypassed (Bit-Perfect Opt-In) (${bpStage3.detail})",
            bpStage3.detail.contains("ReplayGain: Bypassed (Bit-Perfect Opt-In)"),
        )
    }

    private data class CoordinationHarness(
        val engine: AudioOutputEngine,
        val safetyController: AudioSafetyController,
        val routeControl: FakeAudioRouteControl,
        val sinkFactory: FakeAudioOutputSinkFactory,
        val decoderFactory: FakePcmDecoderFactory,
        val preloadExecutor: ExecutorService,
    ) {
        fun awaitPreloadFence() {
            preloadExecutor.submit {}.get(3, TimeUnit.SECONDS)
        }
    }

    private fun createHarness(
        replayGainByUri: Map<Uri, ReplayGainMetadata> = emptyMap(),
        onTrackCompleted: (() -> Unit)? = null,
        onGaplessTransition: ((EngineTrackRequest) -> Unit)? = null,
        sinkConfigurator: ((FakeAudioOutputSink) -> Unit)? = null,
        decoderOpener: (EngineTrackRequest, () -> Boolean) -> FakePcmDecoder,
    ): CoordinationHarness {
        val safetyController = AudioSafetyController()
        val routeRef = AtomicReference<FakeAudioRouteControl>()
        val sinkFactoryRef = AtomicReference<FakeAudioOutputSinkFactory>()
        val decoderFactory = FakePcmDecoderFactory(replayGainByUri, decoderOpener)
        val clockMs = AtomicLong(1_000L)
        val preloadExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "TestGaplessPreload").apply { isDaemon = true }
        }

        val deps = AudioEngineDependencies(
            routeControlFactory = { callbacks ->
                FakeAudioRouteControl(callbacks).also { routeRef.set(it) }
            },
            sinkBackendFactory = { flags, _ ->
                val factory = FakeAudioOutputSinkFactory(flags, sinkConfigurator).also { sinkFactoryRef.set(it) }
                AudioSinkBackend(sinkFactory = factory, spectrumTap = NoOpAudioSpectrumTap)
            },
            decoderFactory = decoderFactory,
            callbackDispatcher = ImmediateCallbackDispatcher(),
            clock = EngineClock { clockMs.addAndGet(5L) },
            controlExecutor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "TestEngineControl").apply { isDaemon = true }
            },
            preloadExecutor = preloadExecutor,
            isDebugBuild = false,
            slowStageDebugLogger = null,
        )
        val engine = AudioOutputEngine(
            safetyController = safetyController,
            onTrackCompleted = onTrackCompleted,
            onGaplessTransition = onGaplessTransition,
            diagnosticLogger = null,
            deps = deps,
        )
        activeEngine = engine
        return CoordinationHarness(
            engine = engine,
            safetyController = safetyController,
            routeControl = routeRef.get(),
            sinkFactory = sinkFactoryRef.get(),
            decoderFactory = decoderFactory,
            preloadExecutor = preloadExecutor,
        )
    }

    private fun testRequest(uriString: String, fileName: String, expectedDurationMs: Long = 60_000L): EngineTrackRequest =
        EngineTrackRequest(
            uri = Uri.parse(uriString),
            fileName = fileName,
            mimeType = "audio/flac",
            autoPlay = true,
            expectedDurationMs = expectedDurationMs,
            expectedContainer = "FLAC",
            expectedCodec = "FLAC",
            expectedSampleRateHz = 48_000,
            expectedBitDepth = 16,
        )

    private fun pcm16Format(sampleRateHz: Int, durationMs: Long): DecodedPcmFormat = DecodedPcmFormat(
        sampleRateHz = sampleRateHz,
        channelCount = 2,
        encoding = PcmEncodingType.PCM_16BIT,
        effectiveBitDepth = 16,
        decoderName = "FakePcmDecoder",
        durationMs = durationMs,
        sourceContainer = "FLAC",
        sourceCodec = "FLAC",
        sourceSampleRateHz = sampleRateHz,
        sourceBitDepth = 16,
        sourceHeaderVerified = true,
    )

    private fun readFirstPcm16LeSample(bytes: ByteArray): Int {
        val low = bytes[0].toInt() and 0xFF
        val high = bytes[1].toInt()
        return (high shl 8) or low
    }

    private fun awaitCondition(description: String, timeoutMs: Long = 5_000L, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(5L)
        }
        assertTrue("Timed out waiting for: $description", condition())
    }

    private data class WrittenPcmChunk(val linearGain: Float, val inputBytes: ByteArray, val outputBytes: ByteArray)

    private class FakePcmDecoder(
        override val format: DecodedPcmFormat,
        private val totalChunks: Int,
        private val chunkFrames: Int,
        private val constantSample16: Short = 1_000,
        private val onAfterRead: ((emittedChunks: Int) -> Unit)? = null,
    ) : PcmAudioDecoder {
        val closed = AtomicBoolean(false)
        private var chunksEmitted = 0

        @Volatile
        private var posMs: Long = 0L

        override val currentPositionMs: Long
            get() = posMs

        override fun readPcm(destination: ByteArray): Int {
            if (closed.get() || chunksEmitted >= totalChunks) return -1
            val byteCount = minOf(destination.size, chunkFrames * format.bytesPerFrame)
            val aligned = PcmStreamRules.alignByteCountToFrame(byteCount, format.bytesPerFrame)
            val lo = (constantSample16.toInt() and 0xFF).toByte()
            val hi = ((constantSample16.toInt() ushr 8) and 0xFF).toByte()
            var idx = 0
            while (idx + 1 < aligned) {
                destination[idx] = lo
                destination[idx + 1] = hi
                idx += 2
            }
            chunksEmitted++
            val frames = aligned / format.bytesPerFrame
            posMs += (frames.toLong() * 1000L) / format.sampleRateHz.toLong()
            onAfterRead?.invoke(chunksEmitted)
            return aligned
        }

        override fun seekToMs(positionMs: Long): Long {
            posMs = positionMs.coerceAtLeast(0L)
            return posMs
        }

        override fun close() {
            closed.set(true)
        }
    }

    private class FakePcmDecoderFactory(
        private val replayGainByUri: Map<Uri, ReplayGainMetadata>,
        private val opener: (EngineTrackRequest, () -> Boolean) -> FakePcmDecoder,
    ) : PcmDecoderFactory {
        val openedDecoders = CopyOnWriteArrayList<FakePcmDecoder>()
        val openedUris = CopyOnWriteArrayList<Uri>()

        override fun open(request: EngineTrackRequest, isCancelled: () -> Boolean): PcmAudioDecoder {
            val decoder = opener(request, isCancelled)
            openedDecoders.add(decoder)
            openedUris.add(request.uri)
            return decoder
        }

        override fun readReplayGainMetadata(uri: Uri, fileName: String, mimeType: String?): ReplayGainMetadata? = replayGainByUri[uri]
    }

    private class FakeAudioOutputSink(
        override val actualSampleRateHz: Int,
        override val actualEncodingLabel: String,
        override val isPinnedToPreferredUsbDevice: Boolean = false,
        private val isGenerationActive: (Long) -> Boolean,
    ) : AudioOutputSink {
        val released = AtomicBoolean(false)
        val playing = AtomicBoolean(false)
        val headFrames = AtomicLong(0L)
        val pauseCount = AtomicInteger(0)
        val drainCleanlyCount = AtomicInteger(0)
        val writtenChunks = CopyOnWriteArrayList<WrittenPcmChunk>()

        @Volatile
        var onPlayHook: (() -> Boolean)? = null

        override fun isHealthy(): Boolean = !released.get()

        override fun isPlaying(): Boolean = !released.get() && playing.get()

        override fun play(): Boolean {
            val ok = onPlayHook?.invoke() ?: true
            if (ok && !released.get()) {
                playing.set(true)
            }
            return ok && !released.get()
        }

        override fun pause() {
            playing.set(false)
            pauseCount.incrementAndGet()
        }

        override fun flush() = Unit

        override fun unblockForShutdown() {
            playing.set(false)
        }

        override fun stopAndRelease() {
            playing.set(false)
            released.set(true)
        }

        override fun setVolume(volume: Float) = Unit

        override fun readPlaybackHeadFrames(): Long = headFrames.get()

        override fun readUnderrunCount(): Int = 0

        override fun configureScratchStartThreshold(desiredHeadroomFrames: Int): Int = desiredHeadroomFrames

        override fun restoreDefaultStartThreshold() = Unit

        override fun drainCleanly(totalFramesWritten: Long, sampleRateHz: Int, expectedGeneration: Long): Long {
            drainCleanlyCount.incrementAndGet()
            headFrames.set(totalFramesWritten)
            playing.set(false)
            return totalFramesWritten
        }

        override fun writeAlignedPcm(
            encoding: PcmEncodingType,
            bytes: ByteArray,
            alignedByteCount: Int,
            directBuffer: ByteBuffer,
            floatBuffer: FloatArray,
            linearGain: Float,
            expectedGeneration: Long,
        ): Int {
            if (!isGenerationActive(expectedGeneration) || released.get()) return 0
            val inputCopy = bytes.copyOf(alignedByteCount)
            val outputCopy = inputCopy.copyOf()
            if (ReplayGainProcessor.needsSampleScaling(linearGain) && encoding == PcmEncodingType.PCM_16BIT) {
                ReplayGainProcessor.applyGainToPcm16LittleEndian(outputCopy, 0, alignedByteCount, linearGain)
            }
            writtenChunks.add(WrittenPcmChunk(linearGain, inputCopy, outputCopy))
            val bytesPerFrame = 2 * encoding.bytesPerSample
            headFrames.addAndGet((alignedByteCount / bytesPerFrame).toLong())
            return alignedByteCount
        }

        override fun writeNormalizedFloats(
            encoding: PcmEncodingType,
            srcFloats: FloatArray,
            frameCount: Int,
            channels: Int,
            scratchBytes: ByteArray,
            directBuffer: ByteBuffer,
            expectedGeneration: Long,
        ): Int = frameCount
    }

    private class FakeAudioOutputSinkFactory(
        private val flags: EngineRuntimeFlags,
        private val configurator: ((FakeAudioOutputSink) -> Unit)?,
    ) : AudioOutputSinkFactory {
        val createdSinks = CopyOnWriteArrayList<FakeAudioOutputSink>()

        override fun tryCreateSinkLocked(pcmFormat: DecodedPcmFormat): Result<AudioOutputSink> {
            val sink = FakeAudioOutputSink(
                actualSampleRateHz = pcmFormat.sampleRateHz,
                actualEncodingLabel = pcmFormat.encoding.displayLabel,
                isGenerationActive = ::isGenerationActive,
            )
            configurator?.invoke(sink)
            createdSinks.add(sink)
            return Result.success(sink)
        }

        override fun isGenerationActive(expectedGeneration: Long): Boolean =
            !flags.stopWorkerFlag.get() && !flags.releasedFlag.get() && flags.loadGeneration.get() == expectedGeneration

        override fun applyPlaybackThreadPriority() = Unit

        override fun sleepUninterrupted(durationMs: Long): Boolean = try {
            Thread.sleep(minOf(durationMs, 5L))
            true
        } catch (_: InterruptedException) {
            false
        }
    }

    private class FakeAudioRouteControl(private val callbacks: AudioRouteCallbacks) : AudioRouteControl {
        @Volatile
        override var activeNegotiatedMixerBehavior: NegotiatedMixerBehavior = NegotiatedMixerBehavior.NOT_APPLICABLE_SAFE_MODE

        @Volatile
        override var hasAudioFocus: Boolean = false

        @Volatile
        override var resumeAfterTransientFocusLoss: Boolean = false

        @Volatile
        override var hasActivePreferredMixer: Boolean = false

        val clearMixerCount = AtomicInteger(0)

        fun dispatchSafetyCommand(command: SafetyCommand) {
            callbacks.onSafetyCommand(command)
        }

        override fun initializeRouteMonitoring() = Unit

        override fun unregisterAudioMonitors() = Unit

        override fun requestAudioFocusLocked(forceRequest: Boolean): Boolean {
            hasAudioFocus = true
            return true
        }

        override fun abandonAudioFocusLocked(): Long {
            hasAudioFocus = false
            return 0L
        }

        override fun clearPreferredMixerAttributes(async: Boolean) {
            clearMixerCount.incrementAndGet()
            hasActivePreferredMixer = false
            activeNegotiatedMixerBehavior = NegotiatedMixerBehavior.NOT_APPLICABLE_SAFE_MODE
        }

        override fun evaluatePlayResume(nowElapsedMs: Long, firstPlayRequestElapsedMs: Long): PlayResumeRouteGuardResult =
            PlayResumeRouteGuardResult(PlayResumeRouteDecision.PROCEED_NOW, deferDelayMs = 0L)

        override fun isUsbRouteActivelyFlapping(nowElapsedMs: Long): Boolean = false

        override fun buildPendingTrackDiagnosticsSnapshot(
            safety: AudioSafetyState,
            container: String,
            codec: String,
            sampleRateHz: Int,
            bitDepth: Int?,
            replayGainResolution: ReplayGainResolution,
        ): AudioDiagnosticSnapshot = TrackTransitionStatePolicy.buildPendingTrackDiagnostics(
            safetyState = safety,
            apiLevel = 34,
            outputDeviceName = safety.activeDeviceName,
            outputConnectionType = if (safety.isUsbAudioConnected) {
                OutputConnectionType.USB_DEVICE
            } else {
                OutputConnectionType.INTERNAL_SPEAKER
            },
            negotiatedMixerBehavior = activeNegotiatedMixerBehavior,
            negotiatedMixerSummary = activeNegotiatedMixerBehavior.name,
            replayGainSummary = replayGainResolution.diagnosticLabel,
            container = container,
            codec = codec,
            sampleRateHz = sampleRateHz,
            bitDepth = bitDepth,
        )

        override fun buildDiagnosticSnapshot(
            safety: AudioSafetyState,
            activeFormat: DecodedPcmFormat?,
            audioTrackSampleRateHz: Int,
            audioTrackEncodingLabel: String,
            replayGainResolution: ReplayGainResolution,
        ): AudioDiagnosticSnapshot = AudioDiagnosticMapper.map(
            AudioDiagnosticInput(
                isTrackLoaded = activeFormat != null,
                sourceContainer = activeFormat?.sourceContainer ?: "None",
                sourceCodec = activeFormat?.sourceCodec ?: "None",
                sourceSampleRateHz = activeFormat?.sourceSampleRateHz ?: 0,
                sourceBitDepth = activeFormat?.sourceBitDepth,
                sourceHeaderVerified = activeFormat?.sourceHeaderVerified ?: false,
                decodedPcmEncodingLabel = activeFormat?.encoding?.displayLabel ?: "None",
                decodedEffectiveBitDepth = activeFormat?.effectiveBitDepth ?: 0,
                decodedSampleRateHz = activeFormat?.sampleRateHz ?: 0,
                decodedChannelCount = activeFormat?.channelCount ?: 0,
                decoderName = activeFormat?.decoderName ?: "Idle",
                audioTrackSampleRateHz = audioTrackSampleRateHz,
                audioTrackEncodingLabel = audioTrackEncodingLabel,
                safetyMode = safety.mode,
                isHardwareVolumeVerified = safety.isHardwareVolumeVerified,
                apiLevel = 34,
                outputDeviceName = safety.activeDeviceName,
                outputConnectionType = if (safety.isUsbAudioConnected) {
                    OutputConnectionType.USB_DEVICE
                } else {
                    OutputConnectionType.INTERNAL_SPEAKER
                },
                negotiatedMixerBehavior = activeNegotiatedMixerBehavior,
                negotiatedMixerSummary = activeNegotiatedMixerBehavior.name,
                replayGainSummary = replayGainResolution.diagnosticLabel,
                isReplayGainModifyingSamples = replayGainResolution.isModifyingSamples,
            ),
        )
    }

    private class ImmediateCallbackDispatcher : EngineCallbackDispatcher {
        override fun post(runnable: Runnable) {
            runnable.run()
        }

        override fun postDelayed(runnable: Runnable, delayMs: Long) {
            runnable.run()
        }

        override fun removeCallbacks(runnable: Runnable) = Unit
    }
}
