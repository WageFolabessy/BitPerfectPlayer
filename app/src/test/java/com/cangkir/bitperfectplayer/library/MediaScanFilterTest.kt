package com.cangkir.bitperfectplayer.library

import com.cangkir.bitperfectplayer.audio.SupportedAudioFormat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaScanFilterTest {

    @Test
    fun acceptsStandardMusicLibraryFiles() {
        val flacTrack = MediaScanFilter.MediaStoreScanCandidate(
            displayName = "21 Guns.flac",
            relativePath = "Music/Green Day/",
            bucketName = "Green Day",
            durationMs = 351_000L,
            sizeBytes = 128_000_000L,
            format = SupportedAudioFormat.FLAC,
            isMusic = true,
        )
        val mp3Download = MediaScanFilter.MediaStoreScanCandidate(
            displayName = "04 - Track.mp3",
            relativePath = "Download/Albums/",
            bucketName = "Albums",
            durationMs = 215_000L,
            sizeBytes = 8_200_000L,
            format = SupportedAudioFormat.MP3,
            isMusic = true,
        )
        val wavWithZeroIndexedDuration = MediaScanFilter.MediaStoreScanCandidate(
            displayName = "studio_master_24bit.wav",
            relativePath = "Music/HiRes/",
            bucketName = "HiRes",
            durationMs = 0L,
            sizeBytes = 64_000_000L,
            format = SupportedAudioFormat.WAV,
            isMusic = false,
        )

        assertTrue(MediaScanFilter.shouldIncludeInAutoScan(flacTrack))
        assertTrue(MediaScanFilter.shouldIncludeInAutoScan(mp3Download))
        assertTrue(MediaScanFilter.shouldIncludeInAutoScan(wavWithZeroIndexedDuration))
    }

    @Test
    fun excludesWhatsAppAudioAndVoiceNotes() {
        val waAudio = MediaScanFilter.MediaStoreScanCandidate(
            displayName = "AUD-20260602-WA0004.mp3",
            relativePath = "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Audio/",
            bucketName = "WhatsApp Audio",
            durationMs = 180_000L,
            sizeBytes = 3_000_000L,
            format = SupportedAudioFormat.MP3,
            isMusic = true,
        )
        val waVoiceNote = MediaScanFilter.MediaStoreScanCandidate(
            displayName = "PTT-20260602-WA0012.opus",
            relativePath = "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes/202622/",
            bucketName = "202622",
            durationMs = 45_000L,
            sizeBytes = 120_000L,
            format = SupportedAudioFormat.OGG,
            isMusic = true,
        )
        val movedWaAudio = MediaScanFilter.MediaStoreScanCandidate(
            displayName = "AUD-20260602-WA0099.m4a",
            relativePath = "Download/",
            bucketName = "Download",
            durationMs = 90_000L,
            sizeBytes = 1_500_000L,
            format = SupportedAudioFormat.M4A,
            isMusic = true,
        )

        assertFalse(MediaScanFilter.shouldIncludeInAutoScan(waAudio))
        assertFalse(MediaScanFilter.shouldIncludeInAutoScan(waVoiceNote))
        assertFalse(MediaScanFilter.shouldIncludeInAutoScan(movedWaAudio))
    }

    @Test
    fun excludesRecordingsCallRecordingsAndMessagingApps() {
        val miuiCallRec = MediaScanFilter.MediaStoreScanCandidate(
            displayName = "Call@08123456789(20260602).mp3",
            relativePath = "MIUI/sound_recorder/call_rec/",
            bucketName = "call_rec",
            durationMs = 240_000L,
            sizeBytes = 2_400_000L,
            format = SupportedAudioFormat.MP3,
            isMusic = true,
        )
        val voiceRecorder = MediaScanFilter.MediaStoreScanCandidate(
            displayName = "Lecture.m4a",
            relativePath = "Recordings/Voice Recorder/",
            bucketName = "Voice Recorder",
            durationMs = 600_000L,
            sizeBytes = 9_000_000L,
            format = SupportedAudioFormat.M4A,
            isMusic = true,
        )
        val androidRecordingFlag = MediaScanFilter.MediaStoreScanCandidate(
            displayName = "Memo.m4a",
            relativePath = "Music/",
            bucketName = "Music",
            durationMs = 120_000L,
            sizeBytes = 1_200_000L,
            format = SupportedAudioFormat.M4A,
            isMusic = true,
            isRecording = true,
        )
        val telegramAudio = MediaScanFilter.MediaStoreScanCandidate(
            displayName = "audio_clip.ogg",
            relativePath = "Telegram/Telegram Audio/",
            bucketName = "Telegram Audio",
            durationMs = 60_000L,
            sizeBytes = 500_000L,
            format = SupportedAudioFormat.OGG,
            isMusic = true,
        )

        assertFalse(MediaScanFilter.shouldIncludeInAutoScan(miuiCallRec))
        assertFalse(MediaScanFilter.shouldIncludeInAutoScan(voiceRecorder))
        assertFalse(MediaScanFilter.shouldIncludeInAutoScan(androidRecordingFlag))
        assertFalse(MediaScanFilter.shouldIncludeInAutoScan(telegramAudio))
    }

    @Test
    fun excludesRingtonesNotificationsShortClipsAndHiddenFiles() {
        val ringtone = MediaScanFilter.MediaStoreScanCandidate(
            displayName = "Classic.ogg",
            relativePath = "Ringtones/",
            durationMs = 30_000L,
            sizeBytes = 250_000L,
            format = SupportedAudioFormat.OGG,
            isMusic = false,
            isRingtone = true,
        )
        val shortSfx = MediaScanFilter.MediaStoreScanCandidate(
            displayName = "ding.mp3",
            relativePath = "Music/",
            durationMs = 4_000L,
            sizeBytes = 40_000L,
            format = SupportedAudioFormat.MP3,
            isMusic = true,
        )
        val hiddenFile = MediaScanFilter.MediaStoreScanCandidate(
            displayName = ".hidden_track.flac",
            relativePath = "Music/",
            durationMs = 200_000L,
            sizeBytes = 25_000_000L,
            format = SupportedAudioFormat.FLAC,
            isMusic = true,
        )

        assertFalse(MediaScanFilter.shouldIncludeInAutoScan(ringtone))
        assertFalse(MediaScanFilter.shouldIncludeInAutoScan(shortSfx))
        assertFalse(MediaScanFilter.shouldIncludeInAutoScan(hiddenFile))
    }
}
