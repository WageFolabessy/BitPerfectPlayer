package com.cangkir.bitperfectplayer.library

import com.cangkir.bitperfectplayer.audio.PlaybackTrack
import com.cangkir.bitperfectplayer.data.CachedTrackSourceKind
import com.cangkir.bitperfectplayer.data.FavoriteTrackRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryPersistenceAndFavoritesTest {

    private val track1 = PlaybackTrack(
        uriString = "content://media/external/audio/media/1",
        title = "Track One",
        artist = "Artist A",
        album = "Album A",
        fileName = "01_one.flac",
        fileSizeBytes = 30_000_000L,
        dateModifiedSeconds = 1_700_000_100L,
        durationMs = 240_000L,
        sampleRateHz = 96_000,
        bitDepth = 24,
        container = "FLAC",
        codec = "FLAC",
        contentFingerprint = null, // Intentionally uncomputed to verify first-tap favorite reactivity!
    )

    private val track2 = PlaybackTrack(
        uriString = "content://media/external/audio/media/2",
        title = "Track Two",
        artist = "Artist B",
        album = "Album B",
        fileName = "02_two.mp3",
        fileSizeBytes = 8_000_000L,
        dateModifiedSeconds = 1_700_000_200L,
        durationMs = 200_000L,
        sampleRateHz = 44_100,
        bitDepth = null,
        container = "MP3",
        codec = "MP3",
        contentFingerprint = "b".repeat(64),
    )

    private val safTrack = PlaybackTrack(
        uriString = "content://com.android.externalstorage.documents/document/primary%3AHiRes%2Fmaster.wav",
        title = "Master WAV",
        artist = "Studio",
        album = "Sessions",
        fileName = "master.wav",
        fileSizeBytes = 50_000_000L,
        dateModifiedSeconds = 1_700_000_300L,
        durationMs = 180_000L,
        sampleRateHz = 96_000,
        bitDepth = 24,
        container = "WAV",
        codec = "PCM",
        parentTreeUriString = "content://com.android.externalstorage.documents/tree/primary%3AHiRes",
    )

    @Test
    fun incrementalSyncDiffer_skipsUnchanged_parsesAddedAndModified_prunesDeleted() {
        val cached = listOf(
            CachedTrackSignature(track1, CachedTrackSourceKind.MEDIA_STORE),
            CachedTrackSignature(track2, CachedTrackSourceKind.MEDIA_STORE),
            CachedTrackSignature(safTrack, CachedTrackSourceKind.SAF_TREE),
        )

        val discovered = listOf(
            // track1 unchanged (same uri, size, dateModified)
            DiscoveredAudioSignature(track1.uriString, track1.fileSizeBytes, track1.dateModifiedSeconds, CachedTrackSourceKind.MEDIA_STORE),
            // track2 modified (dateModified changed!) -> must re-parse
            DiscoveredAudioSignature(track2.uriString, track2.fileSizeBytes, track2.dateModifiedSeconds + 60L, CachedTrackSourceKind.MEDIA_STORE),
            // new track3 added -> must parse
            DiscoveredAudioSignature("content://media/external/audio/media/3", 12_000_000L, 1_700_000_999L, CachedTrackSourceKind.MEDIA_STORE),
            // safTrack deleted from folder -> must prune
        )

        val plan = LibrarySyncDiffer.computePlan(
            cached = cached,
            discovered = discovered,
            scannedSourceKinds = setOf(CachedTrackSourceKind.MEDIA_STORE, CachedTrackSourceKind.SAF_TREE),
        )

        assertEquals(setOf(track1.uriString), plan.unchangedUris)
        assertEquals(setOf(track2.uriString, "content://media/external/audio/media/3"), plan.toParseUris)
        assertEquals(listOf(safTrack.uriString), plan.toPruneUris)
    }

    @Test
    fun incrementalSyncDiffer_doesNotPruneMediaStoreCacheWhenMediaStoreNotScanned() {
        val cached = listOf(
            CachedTrackSignature(track1, CachedTrackSourceKind.MEDIA_STORE),
            CachedTrackSignature(safTrack, CachedTrackSourceKind.SAF_TREE),
        )

        // Only SAF_TREE was scanned (e.g., MediaStore permission temporarily absent)
        val plan = LibrarySyncDiffer.computePlan(
            cached = cached,
            discovered = listOf(
                DiscoveredAudioSignature(safTrack.uriString, safTrack.fileSizeBytes, safTrack.dateModifiedSeconds, CachedTrackSourceKind.SAF_TREE),
            ),
            scannedSourceKinds = setOf(CachedTrackSourceKind.SAF_TREE),
        )

        assertTrue(plan.toPruneUris.isEmpty())
        assertEquals(setOf(safTrack.uriString), plan.unchangedUris)
    }

    @Test
    fun optimisticFavoriteToggle_propagatesImmediatelyAcrossListNowPlayingAndFavoritesTab_evenWithoutFingerprint() {
        val allTracks = listOf(track1, track2)

        // Initially not favorited anywhere
        val before = LibraryProjector.buildSnapshot(
            allTracks = allTracks,
            historyRecords = emptyList(),
            favoriteRecords = emptyList(),
            optimisticFavorites = emptyMap(),
        )
        assertFalse(track1.uriString in before.favoriteUris)
        assertTrue(before.favorites.isEmpty())

        // User taps heart on track1 (which has contentFingerprint == null!) -> instant optimistic true
        val optimisticOn = LibraryProjector.buildSnapshot(
            allTracks = allTracks,
            historyRecords = emptyList(),
            favoriteRecords = emptyList(),
            optimisticFavorites = mapOf(track1.uriString to true),
        )
        assertTrue(track1.uriString in optimisticOn.favoriteUris)
        assertTrue(FavoriteStateReducer.isFavorited(track1, optimisticOn.favoriteUris, optimisticOn.favoriteFingerprints))
        assertEquals(listOf(track1.uriString), optimisticOn.favorites.map { it.uriString })

        // Room finishes persisting favorite under computed SHA-256 fingerprint, optimistic override cleared
        val fp1 = "a".repeat(64)
        val persistedRecord = FavoriteTrackRecord(
            contentFingerprint = fp1,
            trackUri = track1.uriString,
            title = track1.title,
            artist = track1.artist,
            album = track1.album,
            fileName = track1.fileName,
            durationMs = track1.durationMs,
            addedAtEpochMs = 1_700_000_500L,
        )
        val persisted = LibraryProjector.buildSnapshot(
            allTracks = allTracks,
            historyRecords = emptyList(),
            favoriteRecords = listOf(persistedRecord),
            optimisticFavorites = emptyMap(),
        )
        assertTrue(track1.uriString in persisted.favoriteUris)
        assertEquals(listOf(track1.uriString), persisted.favorites.map { it.uriString })

        // User taps heart again to unfavorite -> immediately removed across all views
        val optimisticOff = LibraryProjector.buildSnapshot(
            allTracks = allTracks,
            historyRecords = emptyList(),
            favoriteRecords = listOf(persistedRecord),
            optimisticFavorites = mapOf(track1.uriString to false),
        )
        assertFalse(track1.uriString in optimisticOff.favoriteUris)
        assertFalse(FavoriteStateReducer.isFavorited(track1, optimisticOff.favoriteUris, optimisticOff.favoriteFingerprints))
        assertTrue(optimisticOff.favorites.isEmpty())
    }
}
