package com.cangkir.bitperfectplayer.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticLogFormatterTest {

    @Test
    fun formatUtcTimestamp_formatsIso8601WithMillisUtc() {
        assertEquals("1970-01-01T00:00:00.000Z", DiagnosticLogFormatter.formatUtcTimestamp(0L))
        // 2026-01-02T03:04:05.678Z
        val epoch = 1_767_323_045_678L
        assertEquals("2026-01-02T03:04:05.678Z", DiagnosticLogFormatter.formatUtcTimestamp(epoch))
    }

    @Test
    fun formatEntry_producesSingleLineKeyValueAndSanitizesWhitespace() {
        val line = DiagnosticLogFormatter.formatEntry(
            epochMs = 1_767_323_045_678L,
            level = DiagnosticLogLevel.INFO,
            channel = DiagnosticLogChannel.AUDIO_PIPELINE,
            event = "DECODER_CONFIG",
            fields = linkedMapOf(
                "codec" to "c2.android.flac.decoder",
                "sr" to 96_000,
                "note" to "line1\nline2\twith spaces",
            ),
        )
        assertFalse("Log entry must be single-line", line.contains('\n'))
        assertFalse("Log entry must not contain CR", line.contains('\r'))
        assertTrue(
            line.startsWith("2026-01-02T03:04:05.678Z INFO AUDIO DECODER_CONFIG "),
        )
        assertTrue(line.contains("codec=c2.android.flac.decoder"))
        assertTrue(line.contains("sr=96000"))
        assertTrue(line.contains("note=\"line1 line2 with spaces\""))
    }

    @Test
    fun formatEntry_redactsForbiddenPiiKeysAndRawUrisAndStoragePaths() {
        val line = DiagnosticLogFormatter.formatEntry(
            epochMs = 1_000L,
            level = DiagnosticLogLevel.ERROR,
            channel = DiagnosticLogChannel.ERRORS_AND_CRASHES,
            event = "DECODER_OPEN_ERROR",
            fields = linkedMapOf(
                "title" to "Secret Song Name",
                "artist" to "Private Artist",
                "fileName" to "01_Private_Track.flac",
                "uri" to "content://media/external/audio/media/99",
                "msg" to "Failed reading content://com.android.externalstorage/document/primary:Music/track.flac " +
                    "at /storage/emulated/0/Music/MyAlbum/01.flac",
            ),
        )
        assertFalse(line.contains("Secret Song Name"))
        assertFalse(line.contains("Private Artist"))
        assertFalse(line.contains("01_Private_Track.flac"))
        assertFalse(line.contains("content://"))
        assertFalse(line.contains("/storage/emulated/"))
        assertTrue(line.contains("title=<REDACTED_PII>"))
        assertTrue(line.contains("artist=<REDACTED_PII>"))
        assertTrue(line.contains("fileName=<REDACTED_PII>"))
        assertTrue(line.contains("uri=<REDACTED_PII>"))
        assertTrue(line.contains("<REDACTED_URI>"))
        assertTrue(line.contains("<REDACTED_PATH>"))
    }

    @Test
    fun formatEntry_truncatesOverlongValues() {
        val longMsg = "A".repeat(DiagnosticLogFormatter.MAX_VALUE_LENGTH + 100)
        val line = DiagnosticLogFormatter.formatEntry(
            epochMs = 1_000L,
            level = DiagnosticLogLevel.WARN,
            channel = DiagnosticLogChannel.PERFORMANCE,
            event = "LONG_VALUE",
            fields = mapOf("msg" to longMsg),
        )
        assertTrue(line.endsWith("..."))
        assertTrue(line.length < DiagnosticLogFormatter.MAX_VALUE_LENGTH + 100)
    }

    @Test
    fun shortFingerprintPrefix_returns8LowercaseHexCharsOrNone() {
        val validFp = "ABCDEF0123456789".repeat(4)
        assertEquals("abcdef01", DiagnosticLogFormatter.shortFingerprintPrefix(validFp))
        assertEquals("none", DiagnosticLogFormatter.shortFingerprintPrefix(null))
        assertEquals("none", DiagnosticLogFormatter.shortFingerprintPrefix(""))
        assertEquals("none", DiagnosticLogFormatter.shortFingerprintPrefix("short"))
        assertEquals("none", DiagnosticLogFormatter.shortFingerprintPrefix("not_hex_string_1234"))
    }

    @Test
    fun extractSafeFileExtension_onlyEmitsAllowlistedAudioExtensions() {
        assertEquals("flac", DiagnosticLogFormatter.extractSafeFileExtension("01 - My Song.FLAC", null))
        assertEquals("wav", DiagnosticLogFormatter.extractSafeFileExtension("Secret Track.wav", null))
        assertEquals("mp3", DiagnosticLogFormatter.extractSafeFileExtension("noext", "audio/mpeg"))
        assertEquals("m4a", DiagnosticLogFormatter.extractSafeFileExtension("noext", "audio/mp4"))
        assertEquals("opus", DiagnosticLogFormatter.extractSafeFileExtension("noext", "audio/opus"))
        assertEquals("ogg", DiagnosticLogFormatter.extractSafeFileExtension("noext", "audio/ogg"))
        assertEquals("unknown", DiagnosticLogFormatter.extractSafeFileExtension("PersonalDiary.txt", "text/plain"))
        assertEquals("unknown", DiagnosticLogFormatter.extractSafeFileExtension(null, null))
    }

    @Test
    fun formatCrashBlock_wrapsMarkersAndRedactsPathsInStackTrace() {
        val block = DiagnosticLogFormatter.formatCrashBlock(
            epochMs = 1_767_323_045_678L,
            appVersion = "1.2",
            versionCode = 3L,
            apiLevel = 36,
            threadName = "BitPerfectAudioThread",
            stackTrace = "java.lang.IllegalStateException: Failed at content://media/external/audio/1\n" +
                "\tat com.cangkir.bitperfectplayer.audio.AudioOutputEngine.play(AudioOutputEngine.kt:180)",
        )
        assertTrue(block.startsWith("--- CRASH_START ---\n"))
        assertTrue(block.endsWith("--- CRASH_END ---"))
        assertTrue(block.contains("CRASH ERROR UNCAUGHT_EXCEPTION"))
        assertTrue(block.contains("appVersion=1.2"))
        assertTrue(block.contains("versionCode=3"))
        assertTrue(block.contains("api=36"))
        assertTrue(block.contains("<REDACTED_URI>"))
        assertFalse(block.contains("content://media/external/audio/1"))
        assertTrue(block.contains("AudioOutputEngine.kt:180"))
    }

    @Test
    fun formatDeviceSummary_andExportReadme_documentChannelsAndPrivacy() {
        val summary = DiagnosticLogFormatter.formatDeviceSummary(
            epochMs = 1_767_323_045_678L,
            fields = linkedMapOf(
                "appVersion" to "1.2",
                "audioMode" to "SAFE_VOLUME_MODE",
                "title" to "ShouldNeverAppear",
            ),
        )
        assertTrue(summary.contains("updatedUtc=2026-01-02T03:04:05.678Z"))
        assertTrue(summary.contains("appVersion=1.2"))
        assertTrue(summary.contains("audioMode=SAFE_VOLUME_MODE"))
        assertTrue(summary.contains("title=<REDACTED_PII>"))
        assertFalse(summary.contains("ShouldNeverAppear"))

        val readme = DiagnosticLogFormatter.buildExportReadme("1.2", 3L, 1_767_323_045_678L)
        assertTrue(readme.contains("App Version: 1.2 (versionCode=3)"))
        assertTrue(readme.contains("device_summary.txt"))
        assertTrue(readme.contains("audio_pipeline.txt"))
        assertTrue(readme.contains("performance.txt"))
        assertTrue(readme.contains("errors_and_crashes.txt"))
    }

    @Test
    fun diagnosticLogStats_formatsSummaryAndByteSizesCleanly() {
        assertEquals("No logs stored", DiagnosticLogStats().formatSummary())
        assertEquals("1 file · 512 B", DiagnosticLogStats(fileCount = 1, totalBytes = 512L).formatSummary())
        assertEquals("3 files · 14.2 KB", DiagnosticLogStats(fileCount = 3, totalBytes = 14_541L).formatSummary())
        assertEquals("4 files · 1.50 MB", DiagnosticLogStats(fileCount = 4, totalBytes = 1_572_864L).formatSummary())
    }

    @Test
    fun redactFilenameAndPaths_stripsBareFilenamesDataPathsAndSafDocIds() {
        val raw = "Format 'audio/dsf' (01 - Secret Private Song.dsf) failed at " +
            "primary:Music/SecretAlbum/01.dsf and /data/user/0/com.cangkir.bitperfectplayer/cache/tmp"
        val sanitized = DiagnosticLogFormatter.redactFilenameAndPaths(raw, "01 - Secret Private Song.dsf")

        assertFalse("Bare filename must never leak", sanitized.contains("Secret Private Song"))
        assertFalse("SAF volume-colon doc ID must never leak", sanitized.contains("primary:Music"))
        assertFalse("Internal /data/user path must never leak", sanitized.contains("/data/user/0"))
        assertTrue(sanitized.contains("<REDACTED_FILE>"))
        assertTrue(sanitized.contains("<REDACTED_PATH>"))
    }

    @Test
    fun formatEntry_preservesAudioResumePathEnumWhileRedactingFilePathAndFilesystemValues() {
        val line = DiagnosticLogFormatter.formatEntry(
            epochMs = 2_000L,
            level = DiagnosticLogLevel.INFO,
            channel = DiagnosticLogChannel.PERFORMANCE,
            event = "PLAY_RESUME_LATENCY",
            fields = linkedMapOf(
                "path" to "IN_PLACE_RESUME",
                "filePath" to "/storage/emulated/0/Music/PrivateSong.flac",
                "totalMs" to 14L,
            ),
        )
        assertTrue(line.contains("path=IN_PLACE_RESUME"))
        assertTrue(line.contains("filePath=<REDACTED_PII>"))
        assertFalse(line.contains("PrivateSong"))
    }
}
