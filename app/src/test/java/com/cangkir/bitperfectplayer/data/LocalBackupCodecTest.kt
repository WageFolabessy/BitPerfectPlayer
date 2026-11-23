package com.cangkir.bitperfectplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalBackupCodecTest {

    private val fp1 = "1".repeat(64)
    private val fp2 = "a".repeat(64)

    @Test
    fun encodeAndDecodeRoundTrip_preservesPlaylistsLyricsFavoritesAndStatsWithSpecialCharacters() {
        val original = LocalBackupPayload(
            schemaVersion = LocalBackupCodec.CURRENT_SCHEMA_VERSION,
            appId = LocalBackupCodec.APP_ID,
            exportedAtEpochMs = 1_730_000_000_000L,
            playlists = listOf(
                BackupPlaylist(
                    name = "Late Night \"Hi-Res\" \\ Jazz",
                    createdAtEpochMs = 1_700_000_000_000L,
                    updatedAtEpochMs = 1_720_000_000_000L,
                    tracks = listOf(
                        BackupPlaylistEntry(
                            contentFingerprint = fp1,
                            trackUri = "content://media/external/audio/media/10",
                            fileName = "01 - So What.flac",
                            positionIndex = 0,
                            addedAtEpochMs = 1_700_000_001_000L,
                        ),
                        BackupPlaylistEntry(
                            contentFingerprint = null,
                            trackUri = "content://media/external/audio/media/11",
                            fileName = "02 - Blue in Green.wav",
                            positionIndex = 1,
                            addedAtEpochMs = 1_700_000_002_000L,
                        ),
                    ),
                ),
            ),
            lyrics = listOf(
                LyricsRecord(
                    contentFingerprint = fp1,
                    rawLyricsText = "[00:12.50]Line one \"quoted\"\n[00:24.00]Line two\tTabbed",
                    isSyncedLrc = true,
                    sourceType = LyricsSourceType.LRC_FILE,
                    fileName = "01 - So What.flac",
                    fileSizeBytes = 42_000_000L,
                    durationMs = 562_000L,
                    updatedAtEpochMs = 1_710_000_000_000L,
                ),
            ),
            favorites = listOf(
                FavoriteTrackRecord(
                    contentFingerprint = fp2,
                    trackUri = "content://media/external/audio/media/22",
                    title = "Take Five",
                    artist = "Dave Brubeck",
                    album = "Time Out",
                    fileName = "Take Five.flac",
                    durationMs = 324_000L,
                    addedAtEpochMs = 1_715_000_000_000L,
                ),
            ),
            trackStats = listOf(
                TrackStatsRecord(
                    contentFingerprint = fp1,
                    trackUri = "content://media/external/audio/media/10",
                    title = "So What",
                    artist = "Miles Davis",
                    album = "Kind of Blue",
                    fileName = "01 - So What.flac",
                    playCount = 14,
                    totalPlayTimeMs = 7_800_000L,
                    lastPlayedAtEpochMs = 1_729_000_000_000L,
                ),
            ),
        )

        val encoded = LocalBackupCodec.encode(original)
        val decoded = LocalBackupCodec.decode("\uFEFF$encoded").getOrThrow()

        assertEquals(original, decoded)
        assertNull(decoded.playlists[0].tracks[1].contentFingerprint)
        assertEquals(14, decoded.trackStats[0].playCount)
        assertEquals(7_800_000L, decoded.trackStats[0].totalPlayTimeMs)
    }

    @Test
    fun decodeCorruptMalformedOrUnsupportedSchemaJson_failsSafelyWithoutCrash() {
        assertTrue(LocalBackupCodec.decode("").isFailure)
        assertTrue(LocalBackupCodec.decode("{not-valid-json").isFailure)
        assertTrue(LocalBackupCodec.decode("[]").isFailure)

        // Unsupported future schemaVersion
        val futureSchema = """
            {"schemaVersion": 99, "playlists": [], "lyrics": [], "favorites": [], "trackStats": []}
        """.trimIndent()
        assertTrue(LocalBackupCodec.decode(futureSchema).isFailure)

        // Missing required section array
        val missingArrays = """{"schemaVersion": 1, "playlists": []}"""
        assertTrue(LocalBackupCodec.decode(missingArrays).isFailure)

        // Wrong type for array
        val badArrayType = """
            {"schemaVersion": 1, "playlists": "invalid", "lyrics": [], "favorites": [], "trackStats": []}
        """.trimIndent()
        assertTrue(LocalBackupCodec.decode(badArrayType).isFailure)
    }

    @Test
    fun decodeWithInvalidFingerprintsOrNegativeNumbers_sanitizesSafely() {
        val raw = """
            {
              "schemaVersion": 1,
              "exportedAtEpochMs": -100,
              "playlists": [],
              "lyrics": [
                {"contentFingerprint": "bad-fp", "rawLyricsText": "ignored", "isSyncedLrc": false}
              ],
              "favorites": [],
              "trackStats": [
                {
                  "contentFingerprint": "${fp2.uppercase()}",
                  "trackUri": "content://track/1",
                  "title": "Test",
                  "artist": "Artist",
                  "album": "Album",
                  "fileName": "test.flac",
                  "playCount": -5,
                  "totalPlayTimeMs": -999,
                  "lastPlayedAtEpochMs": 12345
                }
              ]
            }
        """.trimIndent()

        val decoded = LocalBackupCodec.decode(raw).getOrThrow()
        assertEquals(0L, decoded.exportedAtEpochMs)
        assertTrue(decoded.lyrics.isEmpty()) // invalid non-64-hex fingerprint dropped cleanly
        assertEquals(1, decoded.trackStats.size)
        assertEquals(fp2, decoded.trackStats[0].contentFingerprint)
        assertEquals(0, decoded.trackStats[0].playCount)
        assertEquals(0L, decoded.trackStats[0].totalPlayTimeMs)
        assertEquals(DefaultHomepage.ALL_SONGS, decoded.defaultHomepage)
    }

    @Test
    fun encodeAndDecode_preservesDefaultHomepageAndHandlesLegacyBackupsMissingField() {
        val withPlaylistsHome = LocalBackupPayload(
            schemaVersion = LocalBackupCodec.CURRENT_SCHEMA_VERSION,
            appId = LocalBackupCodec.APP_ID,
            exportedAtEpochMs = 1_735_000_000_000L,
            defaultHomepage = DefaultHomepage.PLAYLISTS,
        )
        val encoded = LocalBackupCodec.encode(withPlaylistsHome)
        assertTrue(encoded.contains("\"defaultHomepage\": \"PLAYLISTS\""))
        val decoded = LocalBackupCodec.decode(encoded).getOrThrow()
        assertEquals(DefaultHomepage.PLAYLISTS, decoded.defaultHomepage)

        // Legacy v1.0 / v1.1 JSON without defaultHomepage field must safely default to ALL_SONGS
        val legacyJson = """
            {
              "schemaVersion": 1,
              "appId": "com.cangkir.bitperfectplayer",
              "exportedAtEpochMs": 1700000000000,
              "playlists": [],
              "lyrics": [],
              "favorites": [],
              "trackStats": []
            }
        """.trimIndent()
        val legacyDecoded = LocalBackupCodec.decode(legacyJson).getOrThrow()
        assertEquals(DefaultHomepage.ALL_SONGS, legacyDecoded.defaultHomepage)

        // Unknown / corrupted defaultHomepage value also falls back safely to ALL_SONGS
        val unknownHomepageJson = """
            {
              "schemaVersion": 1,
              "defaultHomepage": "FUTURE_UNKNOWN_TAB",
              "playlists": [],
              "lyrics": [],
              "favorites": [],
              "trackStats": []
            }
        """.trimIndent()
        val unknownDecoded = LocalBackupCodec.decode(unknownHomepageJson).getOrThrow()
        assertEquals(DefaultHomepage.ALL_SONGS, unknownDecoded.defaultHomepage)
        assertFalse(unknownDecoded.dynamicCoverThemeEnabled)
    }

    @Test
    fun encodeAndDecode_preservesDynamicCoverThemeAndDefaultsLegacyBackupsToFalse() {
        val withDynamicTheme = LocalBackupPayload(
            schemaVersion = LocalBackupCodec.CURRENT_SCHEMA_VERSION,
            appId = LocalBackupCodec.APP_ID,
            exportedAtEpochMs = 1_740_000_000_000L,
            defaultHomepage = DefaultHomepage.ALBUMS,
            dynamicCoverThemeEnabled = true,
        )
        val encoded = LocalBackupCodec.encode(withDynamicTheme)
        assertTrue(encoded.contains("\"dynamicCoverThemeEnabled\": true"))
        val decoded = LocalBackupCodec.decode(encoded).getOrThrow()
        assertEquals(DefaultHomepage.ALBUMS, decoded.defaultHomepage)
        assertTrue(decoded.dynamicCoverThemeEnabled)

        // Legacy v1.0-v1.2 backup JSON missing dynamicCoverThemeEnabled must default to false (Monochrome Studio)
        val legacyWithoutDynamicTheme = """
            {
              "schemaVersion": 1,
              "appId": "com.cangkir.bitperfectplayer",
              "exportedAtEpochMs": 1735000000000,
              "defaultHomepage": "ALL_SONGS",
              "playlists": [],
              "lyrics": [],
              "favorites": [],
              "trackStats": []
            }
        """.trimIndent()
        val legacyDecoded = LocalBackupCodec.decode(legacyWithoutDynamicTheme).getOrThrow()
        assertFalse(legacyDecoded.dynamicCoverThemeEnabled)
    }
}
