package com.cangkir.bitperfectplayer.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class PersistenceAndPreferencesTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun createTestRepository(scope: TestScope): UserPreferencesRepository {
        val file = File(tempFolder.newFolder(), "test_prefs.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope.backgroundScope,
            produceFile = { file },
        )
        return UserPreferencesRepository(dataStore)
    }

    @Test
    fun freshInstallPreferences_enforcesSafeVolumeModeAsDefault() = runTest(UnconfinedTestDispatcher()) {
        val repo = createTestRepository(this)
        val snapshot = repo.getSnapshot()

        assertEquals(AudioSafetyModePreference.SAFE_VOLUME_MODE, snapshot.audioSafetyMode)
        assertFalse(snapshot.shuffleEnabled)
        assertEquals(RepeatModePreference.OFF, snapshot.repeatMode)
        assertTrue(snapshot.gaplessEnabled)
        assertEquals(ReplayGainModePreference.OFF, snapshot.replayGainMode)
        assertEquals(0.0f, snapshot.replayGainPreampDb, 1e-6f)
        assertTrue(snapshot.replayGainPreventClipping)
        assertEquals(NowPlayingArtworkStyle.VINYL_DISC, snapshot.nowPlayingArtworkStyle)
        assertTrue(snapshot.spectrumVisualizerEnabled)
        assertTrue(snapshot.persistedSafTreeUris.isEmpty())
        assertTrue(snapshot.persistedSafFileUris.isEmpty())
        assertNull(snapshot.lastPlaybackState.trackUri)
        assertNull(snapshot.lastPlaybackState.contentFingerprint)
        assertEquals(0L, snapshot.lastPlaybackState.positionMs)
        assertEquals(com.cangkir.bitperfectplayer.audio.QueueSourceType.ALL_SONGS, snapshot.lastPlaybackState.queueSourceType)
        assertEquals(com.cangkir.bitperfectplayer.library.TrackSortScope.ALBUM_DETAIL.defaultSpec, snapshot.sortPreferences.albumDetail)
        assertEquals(com.cangkir.bitperfectplayer.library.TrackSortScope.ALL_SONGS.defaultSpec, snapshot.sortPreferences.allSongs)
    }

    @Test
    fun userPreferencesRepository_persistsAndSanitizesAllSettings() = runTest(UnconfinedTestDispatcher()) {
        val repo = createTestRepository(this)
        val fpUpper = "A".repeat(64)

        repo.setAudioSafetyMode(AudioSafetyModePreference.BIT_PERFECT_OPT_IN)
        repo.setShuffleEnabled(true)
        repo.setRepeatMode(RepeatModePreference.ONE)
        repo.setGaplessEnabled(false)
        repo.setReplayGainMode(ReplayGainModePreference.ALBUM)
        repo.setReplayGainPreampDb(15.0f) // Out-of-bounds clamped to +6.0 dB
        repo.setReplayGainPreventClipping(false)
        repo.setNowPlayingArtworkStyle(NowPlayingArtworkStyle.SQUARE_COVER)
        repo.setSpectrumVisualizerEnabled(false)
        repo.addPersistedSafTreeUri("  content://com.android.externalstorage.documents/tree/primary%3AMusic  ")
        repo.addPersistedSafTreeUri("   ") // Blank URI ignored
        repo.addPersistedSafFileUri("content://com.android.externalstorage.documents/document/primary%3Asong.flac")
        repo.saveLastPlaybackState(
            trackUri = "  content://media/external/audio/media/42  ",
            contentFingerprint = fpUpper,
            positionMs = -500L, // Negative clamped to 0
            queueSourceType = com.cangkir.bitperfectplayer.audio.QueueSourceType.ALBUM,
            queueSourceKey = "21st Century Breakdown",
            queueSourceLabel = "21st Century Breakdown",
        )
        repo.setTrackSort(
            com.cangkir.bitperfectplayer.library.TrackSortScope.ALL_SONGS,
            com.cangkir.bitperfectplayer.library.TrackSortSpec(
                com.cangkir.bitperfectplayer.library.TrackSortOption.SAMPLE_RATE,
                com.cangkir.bitperfectplayer.library.SortDirection.DESC,
            ),
        )
        repo.setGroupSort(
            com.cangkir.bitperfectplayer.library.GroupSortScope.ALBUMS_LIST,
            com.cangkir.bitperfectplayer.library.GroupSortSpec(
                com.cangkir.bitperfectplayer.library.GroupSortOption.TRACK_COUNT,
                com.cangkir.bitperfectplayer.library.SortDirection.DESC,
            ),
        )

        val snapshot = repo.getSnapshot()
        assertEquals(AudioSafetyModePreference.BIT_PERFECT_OPT_IN, snapshot.audioSafetyMode)
        assertTrue(snapshot.shuffleEnabled)
        assertEquals(RepeatModePreference.ONE, snapshot.repeatMode)
        assertFalse(snapshot.gaplessEnabled)
        assertEquals(ReplayGainModePreference.ALBUM, snapshot.replayGainMode)
        assertEquals(6.0f, snapshot.replayGainPreampDb, 1e-6f)
        assertFalse(snapshot.replayGainPreventClipping)
        assertEquals(NowPlayingArtworkStyle.SQUARE_COVER, snapshot.nowPlayingArtworkStyle)
        assertFalse(snapshot.spectrumVisualizerEnabled)
        assertEquals(
            setOf("content://com.android.externalstorage.documents/tree/primary%3AMusic"),
            snapshot.persistedSafTreeUris,
        )
        assertEquals(
            setOf("content://com.android.externalstorage.documents/document/primary%3Asong.flac"),
            snapshot.persistedSafFileUris,
        )
        assertEquals("content://media/external/audio/media/42", snapshot.lastPlaybackState.trackUri)
        assertEquals("a".repeat(64), snapshot.lastPlaybackState.contentFingerprint)
        assertEquals(0L, snapshot.lastPlaybackState.positionMs)
        assertEquals(com.cangkir.bitperfectplayer.audio.QueueSourceType.ALBUM, snapshot.lastPlaybackState.queueSourceType)
        assertEquals("21st Century Breakdown", snapshot.lastPlaybackState.queueSourceKey)
        assertEquals("21st Century Breakdown", snapshot.lastPlaybackState.queueSourceLabel)
        // Sorting All Songs does NOT affect Album Detail default!
        assertEquals(
            com.cangkir.bitperfectplayer.library.TrackSortOption.SAMPLE_RATE,
            snapshot.sortPreferences.allSongs.option,
        )
        assertEquals(
            com.cangkir.bitperfectplayer.library.TrackSortOption.TRACK_NUMBER,
            snapshot.sortPreferences.albumDetail.option,
        )
        assertEquals(
            com.cangkir.bitperfectplayer.library.GroupSortOption.TRACK_COUNT,
            snapshot.sortPreferences.albumsList.option,
        )

        // Removal & reset to Safe Mode
        repo.removePersistedSafTreeUri("content://com.android.externalstorage.documents/tree/primary%3AMusic")
        repo.setAudioSafetyMode(AudioSafetyModePreference.SAFE_VOLUME_MODE)
        repo.setReplayGainPreampDb(-99.0f) // Clamped to -6.0 dB
        repo.setNowPlayingArtworkStyle(NowPlayingArtworkStyle.VINYL_DISC)
        repo.setSpectrumVisualizerEnabled(true)
        repo.clearLastPlaybackState()

        val updated = repo.getSnapshot()
        assertEquals(AudioSafetyModePreference.SAFE_VOLUME_MODE, updated.audioSafetyMode)
        assertEquals(-6.0f, updated.replayGainPreampDb, 1e-6f)
        assertEquals(NowPlayingArtworkStyle.VINYL_DISC, updated.nowPlayingArtworkStyle)
        assertTrue(updated.spectrumVisualizerEnabled)
        assertTrue(updated.persistedSafTreeUris.isEmpty())
        assertNull(updated.lastPlaybackState.trackUri)
        assertNull(updated.lastPlaybackState.contentFingerprint)
        assertEquals(0L, updated.lastPlaybackState.positionMs)
        assertEquals(com.cangkir.bitperfectplayer.audio.QueueSourceType.ALL_SONGS, updated.lastPlaybackState.queueSourceType)
        assertNull(updated.lastPlaybackState.queueSourceKey)
    }

    @Test
    fun entityDomainMappers_sanitizeInputsAndPreserveFields() {
        val validFp = "0123456789abcdef".repeat(4)
        val rawLyrics = LyricsRecord(
            contentFingerprint = "  ${validFp.uppercase()}  ",
            rawLyricsText = "[00:01.00]Hello\r\n[00:02.00]World\rLine3",
            isSyncedLrc = true,
            sourceType = LyricsSourceType.LRC_FILE,
            fileName = "   ",
            fileSizeBytes = -10L,
            durationMs = -20L,
            updatedAtEpochMs = 1_700_000_000_000L,
        )

        val roundTripLyrics = rawLyrics.toEntity().toDomain()
        assertEquals(validFp, roundTripLyrics.contentFingerprint)
        assertEquals("[00:01.00]Hello\n[00:02.00]World\nLine3", roundTripLyrics.rawLyricsText)
        assertTrue(roundTripLyrics.isSyncedLrc)
        assertEquals(LyricsSourceType.LRC_FILE, roundTripLyrics.sourceType)
        assertEquals("Unknown", roundTripLyrics.fileName)
        assertEquals(0L, roundTripLyrics.fileSizeBytes)
        assertEquals(0L, roundTripLyrics.durationMs)
        assertEquals(1_700_000_000_000L, roundTripLyrics.updatedAtEpochMs)

        val history = TrackHistoryRecord(
            contentFingerprint = validFp.uppercase(),
            trackUri = " content://media/1 ",
            title = "",
            artist = "   ",
            album = "",
            fileName = "01_song.flac",
            durationMs = 240_000L,
            playedAtEpochMs = 12345L,
        ).toEntity().toDomain()
        assertEquals(validFp, history.contentFingerprint)
        assertEquals("content://media/1", history.trackUri)
        assertEquals("01_song.flac", history.title)
        assertEquals("Unknown Artist", history.artist)
        assertEquals("Unknown Album", history.album)

        val favorite = FavoriteTrackRecord(
            contentFingerprint = validFp,
            trackUri = "content://media/2",
            title = "Track Two",
            artist = "Artist",
            album = "Album",
            fileName = "track2.wav",
            durationMs = -5L,
            addedAtEpochMs = 54321L,
        ).toEntity().toDomain()
        assertEquals(0L, favorite.durationMs)
        assertEquals("Track Two", favorite.title)
    }

    @Test
    fun persistenceSanitizer_validatesSha256AndEnumFallbacks() {
        assertTrue(PersistenceSanitizer.isValidSha256Fingerprint("a".repeat(64)))
        assertTrue(PersistenceSanitizer.isValidSha256Fingerprint("  " + "F".repeat(64) + " "))
        assertFalse(PersistenceSanitizer.isValidSha256Fingerprint("a".repeat(63)))
        assertFalse(PersistenceSanitizer.isValidSha256Fingerprint("g".repeat(64)))

        assertEquals(AudioSafetyModePreference.SAFE_VOLUME_MODE, AudioSafetyModePreference.fromStoredName("CORRUPTED"))
        assertEquals(AudioSafetyModePreference.SAFE_VOLUME_MODE, AudioSafetyModePreference.fromStoredName(null))
        assertEquals(RepeatModePreference.OFF, RepeatModePreference.fromStoredName("INVALID"))
        assertEquals(LyricsSourceType.MANUAL, LyricsSourceType.fromStoredName("UNKNOWN"))
        assertEquals(NowPlayingArtworkStyle.VINYL_DISC, NowPlayingArtworkStyle.fromStoredName(null))
        assertEquals(NowPlayingArtworkStyle.VINYL_DISC, NowPlayingArtworkStyle.fromStoredName("CORRUPTED"))
        assertEquals(NowPlayingArtworkStyle.SQUARE_COVER, NowPlayingArtworkStyle.fromStoredName("  square_cover "))
    }
}
