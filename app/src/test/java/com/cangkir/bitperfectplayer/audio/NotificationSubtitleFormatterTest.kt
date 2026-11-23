package com.cangkir.bitperfectplayer.audio

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationSubtitleFormatterTest {

    @Test
    fun format_flac16Bit44100_formatsCompactAudiophileSubtitle() {
        val subtitle = NotificationSubtitleFormatter.format(
            artist = "Green Day",
            container = "FLAC",
            codec = "FLAC",
            bitDepth = 16,
            sampleRateHz = 44_100,
        )
        assertEquals("Green Day · FLAC 16-bit · 44.1 kHz", subtitle)
    }

    @Test
    fun format_flac24Bit96000_formatsCompactAudiophileSubtitle() {
        val subtitle = NotificationSubtitleFormatter.format(
            artist = "The Beatles",
            container = "FLAC",
            codec = "FLAC",
            bitDepth = 24,
            sampleRateHz = 96_000,
        )
        assertEquals("The Beatles · FLAC 24-bit · 96 kHz", subtitle)
    }

    @Test
    fun format_wav24Bit48000_formatsCompactAudiophileSubtitle() {
        val subtitle = NotificationSubtitleFormatter.format(
            artist = "Miles Davis",
            container = "WAV",
            codec = "PCM",
            bitDepth = 24,
            sampleRateHz = 48_000,
        )
        assertEquals("Miles Davis · WAV 24-bit · 48 kHz", subtitle)
    }

    @Test
    fun format_lossyFormats_omitsBitDepthCleanly() {
        assertEquals(
            "Artist Name · MP3 · 44.1 kHz",
            NotificationSubtitleFormatter.format(
                artist = "Artist Name",
                container = "MP3",
                codec = "MP3",
                bitDepth = null,
                sampleRateHz = 44_100,
            ),
        )
        assertEquals(
            "Artist Name · AAC · 48 kHz",
            NotificationSubtitleFormatter.format(
                artist = "Artist Name",
                container = "M4A",
                codec = "AAC",
                bitDepth = null,
                sampleRateHz = 48_000,
            ),
        )
        assertEquals(
            "Artist Name · Opus · 48 kHz",
            NotificationSubtitleFormatter.format(
                artist = "Artist Name",
                container = "OGG",
                codec = "Opus",
                bitDepth = null,
                sampleRateHz = 48_000,
            ),
        )
        assertEquals(
            "Artist Name · Vorbis · 44.1 kHz",
            NotificationSubtitleFormatter.format(
                artist = "Artist Name",
                container = "OGG",
                codec = "Vorbis",
                bitDepth = null,
                sampleRateHz = 44_100,
            ),
        )
    }

    @Test
    fun format_missingMetadata_fallsBackGracefullyToArtistOrIdleSubtitle() {
        assertEquals(
            "Artist Name",
            NotificationSubtitleFormatter.format(
                artist = "Artist Name",
                container = "AUDIO",
                codec = "Unknown",
                bitDepth = null,
                sampleRateHz = 0,
            ),
        )
        assertEquals(
            "Unknown Artist",
            NotificationSubtitleFormatter.format(
                artist = "<unknown>",
                container = "",
                codec = "",
                bitDepth = null,
                sampleRateHz = 0,
            ),
        )
        assertEquals(
            "Unknown Artist · FLAC · 44.1 kHz",
            NotificationSubtitleFormatter.format(
                artist = "   ",
                container = "FLAC",
                codec = "FLAC",
                bitDepth = null,
                sampleRateHz = 44_100,
            ),
        )
        assertEquals(
            NotificationSubtitleFormatter.DEFAULT_IDLE_SUBTITLE,
            NotificationSubtitleFormatter.format(track = null),
        )
    }

    @Test
    fun formatCompactSampleRateKhz_isLocaleIndependentAcrossCommonRates() {
        val originalLocale = Locale.getDefault()
        try {
            // German locale uses commas for decimals; compact kHz must always use ASCII dot.
            Locale.setDefault(Locale.GERMANY)
            assertEquals("44.1 kHz", NotificationSubtitleFormatter.formatCompactSampleRateKhz(44_100))
            assertEquals("48 kHz", NotificationSubtitleFormatter.formatCompactSampleRateKhz(48_000))
            assertEquals("88.2 kHz", NotificationSubtitleFormatter.formatCompactSampleRateKhz(88_200))
            assertEquals("96 kHz", NotificationSubtitleFormatter.formatCompactSampleRateKhz(96_000))
            assertEquals("176.4 kHz", NotificationSubtitleFormatter.formatCompactSampleRateKhz(176_400))
            assertEquals("192 kHz", NotificationSubtitleFormatter.formatCompactSampleRateKhz(192_000))
            assertEquals("384 kHz", NotificationSubtitleFormatter.formatCompactSampleRateKhz(384_000))
            assertEquals("800 Hz", NotificationSubtitleFormatter.formatCompactSampleRateKhz(800))
            assertNull(NotificationSubtitleFormatter.formatCompactSampleRateKhz(0))
            assertNull(NotificationSubtitleFormatter.formatCompactSampleRateKhz(-44_100))
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun formatTrack_usesRuntimeFallbackWhenTrackScannedMetadataIsIncomplete() {
        val flacMissingDepthAndRate = PlaybackTrack(
            uriString = "content://media/external/audio/media/9",
            title = "Reckoner",
            artist = "Radiohead",
            album = "In Rainbows",
            fileName = "07_reckoner.flac",
            container = "FLAC",
            codec = "FLAC",
            bitDepth = null,
            sampleRateHz = 0,
        )
        assertEquals(
            "Radiohead · FLAC 24-bit · 96 kHz",
            NotificationSubtitleFormatter.format(
                track = flacMissingDepthAndRate,
                runtimeSampleRateHz = 96_000,
                runtimeBitDepth = 24,
            ),
        )

        val mp3Track = PlaybackTrack(
            uriString = "content://media/external/audio/media/10",
            title = "Track",
            artist = "Artist Name",
            album = "Album",
            fileName = "track.mp3",
            container = "MP3",
            codec = "MP3",
            bitDepth = null,
            sampleRateHz = 44_100,
        )
        assertEquals(
            "Artist Name · MP3 · 44.1 kHz",
            NotificationSubtitleFormatter.format(
                track = mp3Track,
                runtimeSampleRateHz = 44_100,
                runtimeBitDepth = null,
            ),
        )
    }

    @Test
    fun formatAudioSpec_formatsStandardAndHiResRatesWithAndWithoutBitDepth() {
        assertEquals(
            "FLAC 16-bit · 44.1 kHz",
            NotificationSubtitleFormatter.formatAudioSpec(
                container = "FLAC",
                codec = "FLAC",
                bitDepth = 16,
                sampleRateHz = 44_100,
            ),
        )
        assertEquals(
            "WAV 24-bit · 48 kHz",
            NotificationSubtitleFormatter.formatAudioSpec(
                container = "WAV",
                codec = "PCM",
                bitDepth = 24,
                sampleRateHz = 48_000,
            ),
        )
        assertEquals(
            "FLAC 24-bit · 88.2 kHz",
            NotificationSubtitleFormatter.formatAudioSpec(
                container = "FLAC",
                codec = "FLAC",
                bitDepth = 24,
                sampleRateHz = 88_200,
            ),
        )
        assertEquals(
            "FLAC 24-bit · 96 kHz",
            NotificationSubtitleFormatter.formatAudioSpec(
                container = "FLAC",
                codec = "FLAC",
                bitDepth = 24,
                sampleRateHz = 96_000,
            ),
        )
        assertEquals(
            "FLAC 24-bit · 176.4 kHz",
            NotificationSubtitleFormatter.formatAudioSpec(
                container = "FLAC",
                codec = "FLAC",
                bitDepth = 24,
                sampleRateHz = 176_400,
            ),
        )
        assertEquals(
            "FLAC 24-bit · 192 kHz",
            NotificationSubtitleFormatter.formatAudioSpec(
                container = "FLAC",
                codec = "FLAC",
                bitDepth = 24,
                sampleRateHz = 192_000,
            ),
        )
        assertEquals(
            "MP3 · 44.1 kHz",
            NotificationSubtitleFormatter.formatAudioSpec(
                container = "MP3",
                codec = "MP3",
                bitDepth = null,
                sampleRateHz = 44_100,
            ),
        )
        assertEquals(
            "MP3 · 44.1 kHz",
            NotificationSubtitleFormatter.formatAudioSpec(
                container = "MP3",
                codec = "MP3",
                bitDepth = 0,
                sampleRateHz = 44_100,
            ),
        )
        assertEquals(
            "AAC · 48 kHz",
            NotificationSubtitleFormatter.formatAudioSpec(
                container = "M4A",
                codec = "AAC",
                bitDepth = null,
                sampleRateHz = 48_000,
            ),
        )
        assertEquals(
            "Opus · 48 kHz",
            NotificationSubtitleFormatter.formatAudioSpec(
                container = "OGG",
                codec = "Opus",
                bitDepth = null,
                sampleRateHz = 48_000,
            ),
        )
        assertEquals(
            "Vorbis · 44.1 kHz",
            NotificationSubtitleFormatter.formatAudioSpec(
                container = "OGG",
                codec = "Vorbis",
                bitDepth = null,
                sampleRateHz = 44_100,
            ),
        )
    }

    @Test
    fun formatAudioSpec_handlesUnknownCodecMissingMetadataAndRuntimeFallback() {
        assertEquals(
            "M4A · 48 kHz",
            NotificationSubtitleFormatter.formatAudioSpec(
                container = "M4A",
                codec = "Unknown",
                bitDepth = null,
                sampleRateHz = 48_000,
            ),
        )
        assertEquals(
            "",
            NotificationSubtitleFormatter.formatAudioSpec(
                container = "AUDIO",
                codec = "Unknown",
                bitDepth = null,
                sampleRateHz = 0,
            ),
        )
        assertEquals(
            "FLAC",
            NotificationSubtitleFormatter.formatAudioSpec(
                container = "FLAC",
                codec = null,
                bitDepth = null,
                sampleRateHz = 0,
            ),
        )
        assertEquals(
            "44.1 kHz",
            NotificationSubtitleFormatter.formatAudioSpec(
                container = "AUDIO",
                codec = null,
                bitDepth = null,
                sampleRateHz = 44_100,
            ),
        )

        val partialTrack = PlaybackTrack(
            uriString = "content://media/external/audio/media/19",
            title = "Everything In Its Right Place",
            artist = "Radiohead",
            album = "Kid A",
            fileName = "01.flac",
            container = "FLAC",
            codec = "FLAC",
            bitDepth = null,
            sampleRateHz = 0,
        )
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals(
                "FLAC 24-bit · 96 kHz",
                NotificationSubtitleFormatter.formatAudioSpec(
                    track = partialTrack,
                    runtimeSampleRateHz = 96_000,
                    runtimeBitDepth = 24,
                ),
            )
            assertEquals(
                "FLAC 16-bit · 44.1 kHz",
                NotificationSubtitleFormatter.formatAudioSpec(
                    track = partialTrack,
                    runtimeSampleRateHz = 44_100,
                    runtimeBitDepth = 16,
                ),
            )
        } finally {
            Locale.setDefault(originalLocale)
        }
    }
}
