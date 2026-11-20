package com.cangkir.bitperfectplayer.library

import com.cangkir.bitperfectplayer.audio.PlaybackTrack
import com.cangkir.bitperfectplayer.data.FavoriteTrackRecord
import com.cangkir.bitperfectplayer.data.TrackHistoryRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryProjectorTest {

    private val fp1 = "1".repeat(64)
    private val fp2 = "2".repeat(64)
    private val fpDeleted = "9".repeat(64)

    @Test
    fun mergeAndDeduplicate_combinesMediaStoreAndSafWithoutDuplicatesAndRetainsSafTree() {
        val mediaTrack = PlaybackTrack(
            uriString = "content://media/external/audio/media/10",
            title = "Midnight",
            artist = "Artist A",
            album = "Album One",
            folderName = "Music",
            fileName = "01_midnight.flac",
            fileSizeBytes = 25_000_000L,
            durationMs = 210_000L,
            container = "FLAC",
            codec = "FLAC",
        )
        val sameFileViaSaf = mediaTrack.copy(
            uriString = "content://com.android.externalstorage.documents/document/primary%3AMusic%2F01_midnight.flac",
            sampleRateHz = 96_000,
            bitDepth = 24,
            parentTreeUriString = "content://com.android.externalstorage.documents/tree/primary%3AMusic",
        )
        val safOnlyTrack = PlaybackTrack(
            uriString = "content://com.android.externalstorage.documents/document/primary%3AHires%2Fmaster.wav",
            title = "Acoustic Master",
            artist = "Artist B",
            album = "Album Two",
            folderName = "Hires",
            fileName = "master.wav",
            fileSizeBytes = 50_000_000L,
            durationMs = 180_000L,
            sampleRateHz = 96_000,
            bitDepth = 24,
            container = "WAV",
            codec = "PCM",
        )

        val merged = LibraryProjector.mergeAndDeduplicate(
            mediaStoreTracks = listOf(mediaTrack),
            safTracks = listOf(sameFileViaSaf, safOnlyTrack),
        )

        assertEquals(2, merged.size)
        assertEquals("Acoustic Master", merged[0].title)
        assertEquals("Midnight", merged[1].title)
        assertEquals(96_000, merged[1].sampleRateHz)
        assertEquals(24, merged[1].bitDepth)
        assertEquals(
            "content://com.android.externalstorage.documents/tree/primary%3AMusic",
            merged[1].parentTreeUriString,
        )
    }

    @Test
    fun buildSnapshot_groupsFoldersArtistsAlbums_resolvesFavoritesByFingerprint_andSkipsDeletedFiles() {
        val trackA = PlaybackTrack(
            uriString = "content://media/1",
            title = "Alpha",
            artist = "Miles",
            album = "Blue",
            folderName = "Jazz",
            fileName = "alpha.flac",
            contentFingerprint = fp1,
            container = "FLAC",
        )
        val trackB = PlaybackTrack(
            uriString = "content://media/2",
            title = "Beta",
            artist = "Miles",
            album = "Gold",
            folderName = "Acoustic",
            fileName = "beta.wav",
            contentFingerprint = fp2,
            container = "WAV",
        )

        val history = listOf(
            TrackHistoryRecord(fpDeleted, "content://media/999", "Deleted Song", "X", "Y", "gone.mp3", 1000L, 3000L),
            TrackHistoryRecord(fp2, "content://media/2", "Beta", "Miles", "Gold", "beta.wav", 2000L, 2000L),
            TrackHistoryRecord(fp1, "content://media/1", "Alpha", "Miles", "Blue", "alpha.flac", 2000L, 1000L),
        )

        // Favorite stored under an old URI, but matching contentFingerprint fp1 still resolves!
        val favorites = listOf(
            FavoriteTrackRecord(fp1, "content://old/moved/uri", "Alpha", "Miles", "Blue", "renamed_old.flac", 2000L, 5000L),
        )

        val snap = LibraryProjector.buildSnapshot(
            allTracks = listOf(trackB, trackA),
            historyRecords = history,
            favoriteRecords = favorites,
        )

        assertEquals(listOf("Alpha", "Beta"), snap.allSongs.map { it.title })
        assertEquals(listOf("Acoustic", "Jazz"), snap.folders.map { it.name })
        assertEquals(listOf("Miles"), snap.artists.map { it.name })
        assertEquals(listOf("Blue", "Gold"), snap.albums.map { it.name })

        // Deleted history item skipped cleanly; order preserved most-recent first
        assertEquals(listOf("Beta", "Alpha"), snap.recentlyPlayed.map { it.title })

        // Favorite resolved via SHA-256 fingerprint despite moved URI
        assertEquals(listOf("Alpha"), snap.favorites.map { it.title })
        assertTrue(fp1 in snap.favoriteFingerprints)
    }
}
