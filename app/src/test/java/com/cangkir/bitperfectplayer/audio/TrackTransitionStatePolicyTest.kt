package com.cangkir.bitperfectplayer.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackTransitionStatePolicyTest {

    private val trackA = PlaybackTrack(
        uriString = "content://test/audio/track_a.flac",
        title = "Track A",
        artist = "Artist A",
        album = "Album A",
        fileName = "01_a.flac",
        durationMs = 180_000L, // 03:00
        sampleRateHz = 44_100,
        bitDepth = 16,
        container = "FLAC",
        codec = "FLAC",
    )

    private val trackB = PlaybackTrack(
        uriString = "content://test/audio/track_b.flac",
        title = "Track B",
        artist = "Artist B",
        album = "Album B",
        fileName = "02_b.flac",
        durationMs = 300_000L, // 05:00
        sampleRateHz = 96_000,
        bitDepth = 24,
        container = "FLAC",
        codec = "FLAC",
    )

    @Test
    fun rapidTrackSwitch_immediatelyResolvesNewTrackDurationAndFormatBeforeDecoderSync() {
        // Case 1: activeTrack already switched to Track B while engineState still holds Track A URI
        assertFalse(
            TrackTransitionStatePolicy.isEngineSyncedToActiveTrack(
                activeTrack = trackB,
                engineTrackUri = trackA.uriString,
                engineStatus = PlaybackStatus.PLAYING,
            ),
        )
        assertEquals(
            300_000L,
            TrackTransitionStatePolicy.resolveDisplayDurationMs(
                activeTrack = trackB,
                engineTrackUri = trackA.uriString,
                engineStatus = PlaybackStatus.PLAYING,
                engineDurationMs = 180_000L,
            ),
        )
        assertEquals(
            "FLAC · 24-bit · 96,000 Hz",
            TrackTransitionStatePolicy.resolveDisplayFormatSpec(
                activeTrack = trackB,
                engineTrackUri = trackA.uriString,
                engineStatus = PlaybackStatus.PLAYING,
                diagnosticsSourceFormatSummary = "FLAC · 16-bit · 44,100 Hz",
            ),
        )

        // Case 2: activeTrack == Track B and engineStatus == PREPARING even with Track A duration still lingering
        assertFalse(
            TrackTransitionStatePolicy.isEngineSyncedToActiveTrack(
                activeTrack = trackB,
                engineTrackUri = trackB.uriString,
                engineStatus = PlaybackStatus.PREPARING,
            ),
        )
        assertEquals(
            300_000L,
            TrackTransitionStatePolicy.resolveDisplayDurationMs(
                activeTrack = trackB,
                engineTrackUri = trackB.uriString,
                engineStatus = PlaybackStatus.PREPARING,
                engineDurationMs = 180_000L,
            ),
        )
        assertEquals(
            "FLAC · 24-bit · 96,000 Hz",
            TrackTransitionStatePolicy.resolveDisplayFormatSpec(
                activeTrack = trackB,
                engineTrackUri = trackB.uriString,
                engineStatus = PlaybackStatus.PREPARING,
                diagnosticsSourceFormatSummary = "FLAC · 16-bit · 44,100 Hz",
            ),
        )
    }

    @Test
    fun syncedDecoderState_usesVerifiedDecoderDurationAndFormatSpecSeamlessly() {
        assertTrue(
            TrackTransitionStatePolicy.isEngineSyncedToActiveTrack(
                activeTrack = trackB,
                engineTrackUri = trackB.uriString,
                engineStatus = PlaybackStatus.PLAYING,
            ),
        )
        assertEquals(
            302_450L,
            TrackTransitionStatePolicy.resolveDisplayDurationMs(
                activeTrack = trackB,
                engineTrackUri = trackB.uriString,
                engineStatus = PlaybackStatus.PLAYING,
                engineDurationMs = 302_450L,
            ),
        )
        assertEquals(
            "FLAC · 24-bit · 96,000 Hz",
            TrackTransitionStatePolicy.resolveDisplayFormatSpec(
                activeTrack = trackB,
                engineTrackUri = trackB.uriString,
                engineStatus = PlaybackStatus.PLAYING,
                diagnosticsSourceFormatSummary = "FLAC · 24-bit · 96,000 Hz",
            ),
        )
    }

    @Test
    fun buildPendingTrackDiagnostics_seedsStage1FromNewTrackAndResetsStage2ToPreparing() {
        val pending = TrackTransitionStatePolicy.buildPendingTrackDiagnostics(
            safetyState = AudioSafetyState(
                mode = AudioOutputMode.SAFE_VOLUME_MODE,
                isUsbAudioConnected = true,
                activeDeviceName = "KZ EDX Pro 2 USB-C",
            ),
            apiLevel = 36,
            outputConnectionType = OutputConnectionType.USB_HEADSET,
            container = trackB.container,
            codec = trackB.codec,
            sampleRateHz = trackB.sampleRateHz,
            bitDepth = trackB.bitDepth,
        )

        assertEquals("FLAC · 24-bit · 96,000 Hz", pending.sourceFormatSummary)
        assertEquals(96_000, pending.sourceSampleRateHz)
        assertEquals(24, pending.sourceBitDepth)
        assertEquals("Idle (no active PCM stream)", pending.decodedPcmSummary)
        assertEquals(VerificationLevel.UNVERIFIABLE, pending.stages.last().verificationLevel)
    }

    @Test
    fun resolveDisplayPositionMs_immediatelyResetsToZeroWhenEngineStillPointsToPreviousTrack() {
        // Track switched to Track B while engineState.activeTrackUri still points to Track A at 00:01 (1,500 ms)
        assertEquals(
            0L,
            TrackTransitionStatePolicy.resolveDisplayPositionMs(
                activeTrack = trackB,
                engineTrackUri = trackA.uriString,
                livePositionMs = 1_500L,
            ),
        )
        // Null engine URI (e.g., cold-start restored session before first Play) preserves restored position
        assertEquals(
            2_400L,
            TrackTransitionStatePolicy.resolveDisplayPositionMs(
                activeTrack = trackB,
                engineTrackUri = null,
                livePositionMs = 2_400L,
            ),
        )
        // Null active track always displays 0L
        assertEquals(
            0L,
            TrackTransitionStatePolicy.resolveDisplayPositionMs(
                activeTrack = null,
                engineTrackUri = trackA.uriString,
                livePositionMs = 2_400L,
            ),
        )
        // Once engine URI matches Track B, live position is reported accurately (clamped >= 0L)
        assertEquals(
            42_750L,
            TrackTransitionStatePolicy.resolveDisplayPositionMs(
                activeTrack = trackB,
                engineTrackUri = trackB.uriString,
                livePositionMs = 42_750L,
            ),
        )
        assertEquals(
            0L,
            TrackTransitionStatePolicy.resolveDisplayPositionMs(
                activeTrack = trackB,
                engineTrackUri = trackB.uriString,
                livePositionMs = -100L,
            ),
        )
    }

    @Test
    fun gaplessTransition_syncingEngineUriRestoresSeekSliderAndDurationImmediately() {
        // Before PlaybackController syncs engineState on GAPLESS_SWAP, activeTrack == trackB while
        // engineState still holds trackA URI -> display position stays stuck at 00:00 despite seeks.
        assertFalse(
            TrackTransitionStatePolicy.shouldSyncEngineStateOnGaplessTransition(
                targetTrackUri = trackB.uriString,
                latestEngineTrackUri = trackA.uriString,
            ),
        )
        assertFalse(
            TrackTransitionStatePolicy.shouldSyncEngineStateOnGaplessTransition(
                targetTrackUri = trackB.uriString,
                latestEngineTrackUri = null,
            ),
        )
        assertEquals(
            0L,
            TrackTransitionStatePolicy.resolveDisplayPositionMs(
                activeTrack = trackB,
                engineTrackUri = trackA.uriString,
                livePositionMs = 120_000L,
            ),
        )

        // Once handleGaplessTransition adopts engine.state.value matching trackB, seeks & slider sync immediately.
        assertTrue(
            TrackTransitionStatePolicy.shouldSyncEngineStateOnGaplessTransition(
                targetTrackUri = trackB.uriString,
                latestEngineTrackUri = trackB.uriString,
            ),
        )
        assertEquals(
            120_000L,
            TrackTransitionStatePolicy.resolveDisplayPositionMs(
                activeTrack = trackB,
                engineTrackUri = trackB.uriString,
                livePositionMs = 120_000L,
            ),
        )
        assertEquals(
            302_450L,
            TrackTransitionStatePolicy.resolveDisplayDurationMs(
                activeTrack = trackB,
                engineTrackUri = trackB.uriString,
                engineStatus = PlaybackStatus.PLAYING,
                engineDurationMs = 302_450L,
            ),
        )
        assertEquals(
            "FLAC · 24-bit · 96,000 Hz",
            TrackTransitionStatePolicy.resolveDisplayFormatSpec(
                activeTrack = trackB,
                engineTrackUri = trackB.uriString,
                engineStatus = PlaybackStatus.PLAYING,
                diagnosticsSourceFormatSummary = "FLAC · 24-bit · 96,000 Hz",
            ),
        )
    }
}
