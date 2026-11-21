package com.cangkir.bitperfectplayer.audio

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TurntableAudioDspTest {

    @Test
    fun ringBuffer_enforcesHard8MbCeilingRoundedToChannelMultiple() {
        // Requesting far above 8 MB must clamp strictly to <= 2,097,152 floats (8 MB)
        val stereo = TurntablePcmRingBuffer(channelCount = 2, maxFloats = 10_000_000)
        assertTrue(stereo.capacityFloats <= TurntablePcmRingBuffer.MAX_RING_FLOATS)
        assertEquals(0, stereo.capacityFloats % 2)
        assertEquals(TurntablePcmRingBuffer.MAX_RING_FLOATS, stereo.capacityFloats)

        // 6-channel surround must round down to an exact multiple of 6
        val surround = TurntablePcmRingBuffer(channelCount = 6, maxFloats = TurntablePcmRingBuffer.MAX_RING_FLOATS)
        assertTrue(surround.capacityFloats <= TurntablePcmRingBuffer.MAX_RING_FLOATS)
        assertEquals(0, surround.capacityFloats % 6)
        assertEquals(TurntablePcmRingBuffer.MAX_RING_FLOATS / 6, surround.capacityFrames)
    }

    @Test
    fun ringBuffer_wraparoundPreservesRecentFramesAndEvictsOldestCleanly() {
        // Small 8-frame stereo buffer for deterministic wraparound testing
        val ring = TurntablePcmRingBuffer(channelCount = 2, maxFloats = 16)
        assertEquals(8, ring.capacityFrames)

        // Append 12 frames: frame k has L = k / 20f, R = -k / 20f
        val batch = FloatArray(12 * 2)
        for (f in 0 until 12) {
            batch[f * 2] = f / 20f
            batch[f * 2 + 1] = -f / 20f
        }
        ring.appendInterleavedFloats(batch, frameCount = 12)

        assertEquals(12L, ring.writeHeadFrame)
        assertEquals(4L, ring.oldestAvailableFrame)
        assertEquals(8, ring.bufferedFrameCount)

        val out = FloatArray(2)
        // Frame 3 was evicted -> must return false & zero
        assertFalse(ring.readInterpolatedFrame(3.0, out))
        assertEquals(0f, out[0], 1e-6f)
        assertEquals(0f, out[1], 1e-6f)

        // Frame 4 (oldest available) and Frame 11 (newest) read accurately
        assertTrue(ring.readInterpolatedFrame(4.0, out))
        assertEquals(4f / 20f, out[0], 1e-6f)
        assertEquals(-4f / 20f, out[1], 1e-6f)

        assertTrue(ring.readInterpolatedFrame(11.0, out))
        assertEquals(11f / 20f, out[0], 1e-6f)
        assertEquals(-11f / 20f, out[1], 1e-6f)

        // Fractional interpolation between frames 7 and 8 (t = 0.25 -> 7.25 / 20f)
        assertTrue(ring.readInterpolatedFrame(7.25, out))
        assertEquals(7.25f / 20f, out[0], 1e-6f)
        assertEquals(-7.25f / 20f, out[1], 1e-6f)

        // Frame 12 (at exclusive writeHead) -> out of bounds
        assertFalse(ring.readInterpolatedFrame(12.0, out))
    }

    @Test
    fun ringBuffer_copyFramesAt1xAcrossWrapSeamIsExact() {
        val ring = TurntablePcmRingBuffer(channelCount = 2, maxFloats = 16) // 8 frames
        val batch = FloatArray(11 * 2) { idx -> (idx + 1) / 100f }
        ring.appendInterleavedFloats(batch, frameCount = 11) // holds frames 3..10 (wrapped across slot 0)

        val dest = FloatArray(6 * 2)
        val copied = ring.copyFramesAt1x(startFrame = 5L, maxFrames = 6, destFloats = dest)
        assertEquals(6, copied) // frames 5..10
        for (f in 0 until 6) {
            val origIdx = (5 + f) * 2
            assertEquals(batch[origIdx], dest[f * 2], 1e-6f)
            assertEquals(batch[origIdx + 1], dest[f * 2 + 1], 1e-6f)
        }
    }

    @Test
    fun pcmCodec_roundTripsAllFourEncodingsAndSaturatesOutOfRangeFloats() {
        val testFloats = floatArrayOf(0.0f, 0.5f, -0.5f, 0.25f, -0.75f, 1.0f, -1.0f, 1.5f, -1.8f, Float.NaN)
        val expectedClamped = floatArrayOf(0.0f, 0.5f, -0.5f, 0.25f, -0.75f, 1.0f, -1.0f, 1.0f, -1.0f, 0.0f)

        for (encoding in PcmEncodingType.entries) {
            val encodedBytes = ByteArray(testFloats.size * encoding.bytesPerSample)
            val bytesWritten = TurntablePcmCodec.encodeFloatsToPcmBytes(
                encoding = encoding,
                srcFloats = testFloats,
                floatCount = testFloats.size,
                destBytes = encodedBytes,
            )
            assertEquals(testFloats.size * encoding.bytesPerSample, bytesWritten)

            val decoded = FloatArray(testFloats.size)
            val floatsRead = TurntablePcmCodec.decodePcmBytesToFloats(
                encoding = encoding,
                srcBytes = encodedBytes,
                byteOffset = 0,
                byteCount = bytesWritten,
                destFloats = decoded,
            )
            assertEquals(testFloats.size, floatsRead)

            val tolerance = when (encoding) {
                PcmEncodingType.PCM_16BIT -> 1.5e-4f
                PcmEncodingType.PCM_24BIT_PACKED -> 1e-6f
                PcmEncodingType.PCM_32BIT -> 1e-6f
                PcmEncodingType.PCM_FLOAT -> 1e-7f
            }
            for (i in expectedClamped.indices) {
                assertEquals(
                    "Mismatch for $encoding at index $i",
                    expectedClamped[i],
                    decoded[i],
                    tolerance,
                )
            }
        }
    }

    @Test
    fun resampler_forwardVarispeedAndReverseScratchInterpolateAccuratelyAndClampBoundaries() {
        val sampleRate = 48_000
        val ring = TurntablePcmRingBuffer(channelCount = 1, maxFloats = 4_096)
        // Ramp signal: frame f has amplitude f / 4000f (smooth linear ramp)
        val ramp = FloatArray(4_000) { f -> f / 4_000f }
        ring.appendInterleavedFloats(ramp, frameCount = 4_000)

        val resampler = TurntableResampler(sampleRateHz = sampleRate, channelCount = 1)
        val out = FloatArray(600)

        // 1. Forward +2.0x scratch (start at rate 2.0 to test steady-state interpolation directly)
        resampler.resetForScratchEntry(initialRate = 2.0f)
        val fwd = resampler.renderChunk(
            ringBuffer = ring,
            targetRate = 2.0f,
            startCursorFrame = 100.0,
            minVirtualFrame = 0.0,
            maxVirtualFrame = 3_999.0,
            outFrames = 600,
            destFloats = out,
        )
        assertEquals(600, fwd.framesRendered)
        assertEquals(1_300.0, fwd.nextCursorFrame, 1.0)
        // First sample must start near 0 (4 ms anti-click fade-in), while sample 300 (> 4 ms = 192 frames) is full scale
        assertTrue(out[0] < 0.01f)
        assertEquals((100.0 + 300.0 * 2.0) / 4_000.0, out[300].toDouble(), 1e-3)
        // Strictly increasing samples on forward ramp after fade-in
        assertTrue(out[500] > out[300])

        // 2. Reverse -1.5x scratch from frame 2,000
        resampler.resetForScratchEntry(initialRate = -1.5f)
        val rev = resampler.renderChunk(
            ringBuffer = ring,
            targetRate = -1.5f,
            startCursorFrame = 2_000.0,
            minVirtualFrame = 0.0,
            maxVirtualFrame = 3_999.0,
            outFrames = 600,
            destFloats = out,
        )
        assertEquals(600, rev.framesRendered)
        assertEquals(1_100.0, rev.nextCursorFrame, 1.0)
        // Sample 300 is at frame 2000 - 450 = 1550
        assertEquals(1_550.0 / 4_000.0, out[300].toDouble(), 1e-3)
        // Strictly decreasing samples on reversed ramp after fade-in!
        assertTrue(out[500] < out[300])

        // 3. Rewinding past oldestAvailableFrame fades cleanly to silence while virtual cursor keeps descending
        val pastEdge = resampler.renderChunk(
            ringBuffer = ring,
            targetRate = -2.0f,
            startCursorFrame = 10.0,
            minVirtualFrame = -5_000.0,
            maxVirtualFrame = 3_999.0,
            outFrames = 600,
            destFloats = out,
        )
        assertTrue(pastEdge.isBelowOldestFrame)
        assertTrue(pastEdge.nextCursorFrame < 0.0)
        // After 4 ms (192 frames) past the edge, output must be completely silent (0.0f)
        assertEquals(0.0f, out[599], 1e-6f)
    }

    @Test
    fun speedController_deadbandHoldCurveAndBitPerfectEligibilityPolicy() {
        // Deadband |deg/s| < 6 -> exact 0.0x hold
        assertEquals(0.0f, TurntableSpeedController.mapAngularVelocityToRate(0f), 1e-6f)
        assertEquals(0.0f, TurntableSpeedController.mapAngularVelocityToRate(5.9f), 1e-6f)
        assertEquals(0.0f, TurntableSpeedController.mapAngularVelocityToRate(-5.9f), 1e-6f)

        // Musical control points
        val at20 = TurntableSpeedController.mapAngularVelocityToRate(20f)
        assertTrue("Expected 20 deg/s in 0.6..1.0x, got $at20", at20 in 0.6f..1.0f)
        val at90 = TurntableSpeedController.mapAngularVelocityToRate(90f)
        assertTrue("Expected 90 deg/s in 1.6..2.0x, got $at90", at90 in 1.6f..2.0f)
        assertEquals(4.0f, TurntableSpeedController.mapAngularVelocityToRate(360f), 1e-6f)
        assertEquals(4.0f, TurntableSpeedController.mapAngularVelocityToRate(720f), 1e-6f)

        // Odd symmetry for reverse scratching
        assertEquals(-at20, TurntableSpeedController.mapAngularVelocityToRate(-20f), 1e-6f)
        assertEquals(-at90, TurntableSpeedController.mapAngularVelocityToRate(-90f), 1e-6f)
        assertEquals(-4.0f, TurntableSpeedController.mapAngularVelocityToRate(-360f), 1e-6f)

        // Monotonic non-decreasing curve across [0..400]
        var prev = 0f
        for (v in 1..400) {
            val cur = TurntableSpeedController.mapAngularVelocityToRate(v.toFloat())
            assertTrue("Expected monotonic speed curve at $v deg/s", cur >= prev)
            prev = cur
        }

        // Badge labels
        assertEquals("HOLD", TurntableSpeedController.formatSpeedBadge(0f))
        assertEquals("HOLD", TurntableSpeedController.formatSpeedBadge(0.04f))
        assertEquals("2.1×", TurntableSpeedController.formatSpeedBadge(2.12f))
        assertEquals("-1.4×", TurntableSpeedController.formatSpeedBadge(-1.41f))

        // Strict Bit-Perfect bypass eligibility policy
        assertTrue(
            TurntableScratchPolicy.isLiveTurntableEligible(
                safetyMode = AudioOutputMode.SAFE_VOLUME_MODE,
                isPlaying = true,
                hasActiveTrack = true,
            ),
        )
        assertFalse(
            TurntableScratchPolicy.isLiveTurntableEligible(
                safetyMode = AudioOutputMode.BIT_PERFECT_OPT_IN,
                isPlaying = true,
                hasActiveTrack = true,
            ),
        )
        assertFalse(
            TurntableScratchPolicy.isLiveTurntableEligible(
                safetyMode = AudioOutputMode.SAFE_VOLUME_MODE,
                isPlaying = false,
                hasActiveTrack = true,
            ),
        )
        assertFalse(
            TurntableScratchPolicy.isLiveTurntableEligible(
                safetyMode = AudioOutputMode.SAFE_VOLUME_MODE,
                isPlaying = true,
                hasActiveTrack = false,
            ),
        )
    }

    @Test
    fun resampler_holdAtZeroSmoothlyBrakesToExactSilence() {
        val ring = TurntablePcmRingBuffer(channelCount = 2, maxFloats = 4_096)
        val tone = FloatArray(2_000 * 2) { 0.5f }
        ring.appendInterleavedFloats(tone, frameCount = 2_000)

        val resampler = TurntableResampler(sampleRateHz = 48_000, channelCount = 2)
        resampler.resetForScratchEntry(initialRate = 1.0f)
        val buf = FloatArray(4_800 * 2) // 100 ms

        // Ramp into audible playback first
        val running = resampler.renderChunk(
            ringBuffer = ring,
            targetRate = 1.0f,
            startCursorFrame = 100.0,
            minVirtualFrame = 0.0,
            maxVirtualFrame = 1_999.0,
            outFrames = 480, // 10 ms
            destFloats = buf,
        )
        assertTrue(abs(buf[479 * 2]) > 0.45f)

        // Hold finger still (targetRate = 0.0f): rate decays & 4 ms fade brings output to exact 0.0f
        resampler.renderChunk(
            ringBuffer = ring,
            targetRate = 0.0f,
            startCursorFrame = running.nextCursorFrame,
            minVirtualFrame = 0.0,
            maxVirtualFrame = 1_999.0,
            outFrames = 4_800, // 100 ms
            destFloats = buf,
        )
        assertEquals(0.0f, resampler.currentRate, 1e-6f)
        assertEquals(0.0f, buf[(4_800 - 1) * 2], 1e-6f)
        assertEquals(0.0f, buf[(4_800 - 1) * 2 + 1], 1e-6f)
    }

    @Test
    fun scratchPolicy_computesEntryCursorVirtualBoundsPositionAndSeamlessDrainEligibility() {
        // 1. Entry cursor subtracts unplayed AudioTrack lookahead and clamps into ring buffer range
        val entryCursor = TurntableScratchPolicy.computeScratchEntryCursorFrame(
            currentStreamHeadFrame = 48_000L,
            totalFramesWrittenToTrack = 50_000L,
            hwPlaybackHeadFrames = 42_000L, // 8,000 frames unplayed in AudioTrack
            oldestAvailableFrame = 10_000L,
            writeHeadFrame = 48_000L,
        )
        assertEquals(40_000.0, entryCursor, 1e-6)

        // Clamps to oldestAvailableFrame when lookahead exceeds ring window
        val clampedLow = TurntableScratchPolicy.computeScratchEntryCursorFrame(
            currentStreamHeadFrame = 12_000L,
            totalFramesWrittenToTrack = 20_000L,
            hwPlaybackHeadFrames = 5_000L,
            oldestAvailableFrame = 10_000L,
            writeHeadFrame = 12_000L,
        )
        assertEquals(10_000.0, clampedLow, 1e-6)

        // 2. Virtual frame bounds respect streamOriginMs and track durationMs
        val (minFrame, maxFrame) = TurntableScratchPolicy.computeVirtualFrameBounds(
            streamOriginMs = 10_000L,
            sampleRateHz = 48_000,
            durationMs = 60_000L,
        )
        assertEquals(-480_000.0, minFrame, 1.0)
        assertEquals(2_400_000.0, maxFrame, 1.0)

        // 3. Clamped scratch position converts virtual cursor relative to streamOriginMs and clamps to [0..durationMs]
        assertEquals(
            12_000L,
            TurntableScratchPolicy.computeClampedScratchPositionMs(
                streamOriginMs = 10_000L,
                scratchCursorFrame = 96_000.0,
                sampleRateHz = 48_000,
                durationMs = 60_000L,
            ),
        )
        assertEquals(
            0L,
            TurntableScratchPolicy.computeClampedScratchPositionMs(
                streamOriginMs = 1_000L,
                scratchCursorFrame = -96_000.0,
                sampleRateHz = 48_000,
                durationMs = 60_000L,
            ),
        )

        // 4. Seamless ring drain requires buffered frames > 0, no explicit fallback seek, and cursor inside ring window
        assertTrue(
            TurntableScratchPolicy.canResumeViaSeamlessRingDrain(
                bufferedFrameCount = 48_000,
                cursorFrameLong = 24_000L,
                oldestAvailableFrame = 0L,
                writeHeadFrame = 48_000L,
                fallbackSeekMs = -1L,
            ),
        )
        assertFalse(
            TurntableScratchPolicy.canResumeViaSeamlessRingDrain(
                bufferedFrameCount = 48_000,
                cursorFrameLong = -500L,
                oldestAvailableFrame = 0L,
                writeHeadFrame = 48_000L,
                fallbackSeekMs = -1L,
            ),
        )
        assertFalse(
            TurntableScratchPolicy.canResumeViaSeamlessRingDrain(
                bufferedFrameCount = 48_000,
                cursorFrameLong = 24_000L,
                oldestAvailableFrame = 0L,
                writeHeadFrame = 48_000L,
                fallbackSeekMs = 15_000L,
            ),
        )
    }

    @Test
    fun computeEffectiveScratchMaxAheadFrames_alwaysExceedsTrackStartThreshold() {
        // When AudioTrack startThresholdInFrames is larger than sampleRate / 15 (e.g., 160 ms buffer
        // on 48 kHz = 7,680 frames vs 3,200 base headroom), maxAhead MUST exceed startThreshold!
        val effective = TurntableScratchPolicy.computeEffectiveScratchMaxAheadFrames(
            sampleRateHz = 48_000,
            trackStartThresholdFrames = 7_680,
            chunkFrames = 727,
        )
        assertTrue(effective > 7_680L)
        assertEquals(7_680L + 727L, effective)

        // When startThreshold is lowered to ~65 ms on API 31+, base headroom + chunk floor applies cleanly
        val lowLatency = TurntableScratchPolicy.computeEffectiveScratchMaxAheadFrames(
            sampleRateHz = 48_000,
            trackStartThresholdFrames = 2_000,
            chunkFrames = 727,
        )
        assertEquals(3_200L, lowLatency)
    }

    @Test
    fun flushHeadTracker_handlesImmediateResetAsyncStaleFlushResetAndMonotonicHalWithoutDeadlock() {
        val maxAhead = 3_200L

        // Case 1: Stale pre-flush head (192,000) observed right after flush(), then AudioFlinger resets hwHead -> 0
        val asyncTracker = TurntableFlushHeadTracker(initialObservedHead = 192_000L, maxAheadFrames = maxAhead)
        assertEquals(0L, asyncTracker.computeAheadFrames(192_000L))
        asyncTracker.onFramesWritten(727)
        // Suddenly AudioFlinger commits the async flush and drops playbackHeadPosition to 0:
        // Previously ahead became (192,727 - 0) >> 3,200 (permanent silence deadlock); now it rebases cleanly!
        val aheadAfterDrop = asyncTracker.computeAheadFrames(0L)
        assertTrue("Expected non-deadlocking ahead <= maxAhead after async flush reset, got $aheadAfterDrop", aheadAfterDrop <= maxAhead)
        assertEquals(0L, asyncTracker.headOrigin)
        asyncTracker.onFramesWritten(727)
        assertEquals(727L, asyncTracker.computeAheadFrames(0L))
        assertEquals(227L, asyncTracker.computeAheadFrames(500L))

        // Case 2: Immediate flush reset (head already 0)
        val zeroTracker = TurntableFlushHeadTracker(initialObservedHead = 0L, maxAheadFrames = maxAhead)
        zeroTracker.onFramesWritten(1_454)
        assertEquals(1_454L, zeroTracker.computeAheadFrames(0L))
        assertEquals(454L, zeroTracker.computeAheadFrames(1_000L))

        // Case 3: Monotonic HAL that does not reset head to 0 on flush()
        val monoTracker = TurntableFlushHeadTracker(initialObservedHead = 50_000L, maxAheadFrames = maxAhead)
        monoTracker.onFramesWritten(1_500)
        assertEquals(1_500L, monoTracker.computeAheadFrames(50_000L))
        assertEquals(500L, monoTracker.computeAheadFrames(51_000L))
        assertEquals(51_500L, monoTracker.currentTrackWrittenFrame())
    }

    @Test
    fun loadPausedThenPlay_shortPcmFeedThenImmediateForwardHoldAndReverseScratchProducesAudiblePcmWithoutSeek() {
        // Simulate loadTrack(startPositionMs = 30_000, autoPlay = false) -> play() -> brief PCM feed
        // (~500 ms = 24,000 frames @ 48 kHz) -> immediate vinyl touch without any prior seekTo().
        val sampleRate = 48_000
        val channels = 2
        val streamOriginMs = 30_000L
        val durationMs = 180_000L
        val chunkFrames = sampleRate / 66 // 727 frames (~15 ms)

        val ring = TurntablePcmRingBuffer(channelCount = channels)
        ring.reset(0L)

        // Short PCM feed after play(): 24,000 frames of 440 Hz stereo sine (plus forward decode-ahead source)
        val totalSourceFrames = 96_000
        val sourceTone = FloatArray(totalSourceFrames * channels) { idx ->
            val frame = idx / channels
            kotlin.math.sin(2.0 * kotlin.math.PI * 440.0 * frame / sampleRate).toFloat() * 0.75f
        }
        var decodedIntoRingFrames = 24_000
        ring.appendInterleavedFloats(sourceTone, frameCount = decodedIntoRingFrames, srcFloatOffset = 0)

        // Anchor entry cursor at hardware head (~16,000 played, 8,000 queued in AudioTrack)
        var cursor = TurntableScratchPolicy.computeScratchEntryCursorFrame(
            currentStreamHeadFrame = decodedIntoRingFrames.toLong(),
            totalFramesWrittenToTrack = 24_000L,
            hwPlaybackHeadFrames = 16_000L,
            oldestAvailableFrame = ring.oldestAvailableFrame,
            writeHeadFrame = ring.writeHeadFrame,
        )
        assertEquals(16_000.0, cursor, 1e-6)

        // Simulate post-flush stale head (16,000) dropping to 0 on play() + AudioTrack startThreshold
        val maxAhead = TurntableScratchPolicy.computeEffectiveScratchMaxAheadFrames(sampleRate, 3_200, chunkFrames)
        val tracker = TurntableFlushHeadTracker(initialObservedHead = 16_000L, maxAheadFrames = maxAhead)
        val (minVirtual, maxVirtual) = TurntableScratchPolicy.computeVirtualFrameBounds(streamOriginMs, sampleRate, durationMs)
        val resampler = TurntableResampler(sampleRateHz = sampleRate, channelCount = channels)
        resampler.resetForScratchEntry(initialRate = 0.0f)
        val outBuf = FloatArray(chunkFrames * channels)

        // 1. Immediate forward varispeed (2.0x then 4.0x) with decode-ahead + async flush drop to 0
        var simulatedHwHead = 0L
        var forwardPeak = 0.0f
        val cursorBeforeForward = cursor
        for (i in 0 until 12) {
            val rate = if (i < 6) 2.0f else 4.0f
            val ahead = tracker.computeAheadFrames(simulatedHwHead)
            assertTrue("Scratch loop must not deadlock after flush drop", ahead <= maxAhead)

            val neededEnd = (cursor + rate * chunkFrames + 4.0).toLong()
            while (ring.writeHeadFrame <= neededEnd && decodedIntoRingFrames < totalSourceFrames) {
                val add = minOf(4_096, totalSourceFrames - decodedIntoRingFrames)
                ring.appendInterleavedFloats(sourceTone, frameCount = add, srcFloatOffset = decodedIntoRingFrames * channels)
                decodedIntoRingFrames += add
            }

            val step = resampler.renderChunk(
                ringBuffer = ring,
                targetRate = rate,
                startCursorFrame = cursor,
                minVirtualFrame = minVirtual,
                maxVirtualFrame = maxVirtual,
                outFrames = chunkFrames,
                destFloats = outBuf,
            )
            cursor = step.nextCursorFrame
            tracker.onFramesWritten(step.framesRendered)
            simulatedHwHead += step.framesRendered.toLong()
            for (sample in outBuf) {
                if (abs(sample) > forwardPeak) forwardPeak = abs(sample)
            }
        }
        assertTrue("Forward scratch must advance cursor significantly", cursor > cursorBeforeForward + 15_000.0)
        assertTrue("Forward scratch must emit non-zero audible PCM", forwardPeak > 0.5f)
        val posAfterForwardMs = TurntableScratchPolicy.computeClampedScratchPositionMs(streamOriginMs, cursor, sampleRate, durationMs)
        assertTrue("Forward scratch position must advance beyond 30,000 ms", posAfterForwardMs > streamOriginMs + 300L)

        // 2. Hold at 0.0x: brakes smoothly to silence
        val cursorBeforeHold = cursor
        for (i in 0 until 8) {
            val step = resampler.renderChunk(
                ringBuffer = ring,
                targetRate = 0.0f,
                startCursorFrame = cursor,
                minVirtualFrame = minVirtual,
                maxVirtualFrame = maxVirtual,
                outFrames = chunkFrames,
                destFloats = outBuf,
            )
            cursor = step.nextCursorFrame
        }
        assertEquals(0.0f, resampler.currentRate, 1e-6f)
        assertEquals(0.0f, outBuf[outBuf.lastIndex], 1e-6f)
        assertTrue("Cursor should remain near hold point", abs(cursor - cursorBeforeHold) < 2_500.0)

        // 3. Immediate reverse scratch (-2.0x): emits non-zero audible PCM and rewinds cursor cleanly
        val cursorBeforeReverse = cursor
        var reversePeak = 0.0f
        for (i in 0 until 10) {
            val step = resampler.renderChunk(
                ringBuffer = ring,
                targetRate = -2.0f,
                startCursorFrame = cursor,
                minVirtualFrame = minVirtual,
                maxVirtualFrame = maxVirtual,
                outFrames = chunkFrames,
                destFloats = outBuf,
            )
            cursor = step.nextCursorFrame
            for (sample in outBuf) {
                if (abs(sample) > reversePeak) reversePeak = abs(sample)
            }
        }
        assertTrue("Reverse scratch must rewind cursor", cursor < cursorBeforeReverse - 8_000.0)
        assertTrue("Reverse scratch must emit non-zero audible PCM", reversePeak > 0.5f)
    }
}
