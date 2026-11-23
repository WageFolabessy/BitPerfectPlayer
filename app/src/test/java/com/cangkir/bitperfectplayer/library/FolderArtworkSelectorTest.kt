package com.cangkir.bitperfectplayer.library

import com.cangkir.bitperfectplayer.audio.PlaybackTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderArtworkSelectorTest {

    @Test
    fun orderArtworkCandidates_isDeterministicRegardlessOfInputScanOrder() {
        val disc1Track1 = track(uri = "content://f/1", album = "Album A", disc = 1, track = 1, title = "Zeta")
        val disc1Track2 = track(uri = "content://f/2", album = "Album B", disc = 1, track = 2, title = "Alpha")
        val disc2Track1 = track(uri = "content://f/3", album = "Album C", disc = 2, track = 1, title = "Beta")
        val untaggedAlpha = track(uri = "content://f/4", album = "Album D", disc = 0, track = 0, title = "Alpha Untagged")
        val untaggedBeta = track(uri = "content://f/5", album = "Album E", disc = 0, track = 0, title = "Beta Untagged")

        val orderA = FolderArtworkSelector.orderArtworkCandidates(
            listOf(untaggedBeta, disc2Track1, disc1Track2, untaggedAlpha, disc1Track1),
        )
        val orderB = FolderArtworkSelector.orderArtworkCandidates(
            listOf(disc1Track2, untaggedAlpha, disc1Track1, untaggedBeta, disc2Track1),
        )

        val expectedUris = listOf("content://f/1", "content://f/2", "content://f/3", "content://f/4", "content://f/5")
        assertEquals(expectedUris, orderA.map { it.uriString })
        assertEquals(expectedUris, orderB.map { it.uriString })
        assertEquals("content://f/1", FolderArtworkSelector.selectRepresentativeTrack(listOf(untaggedBeta, disc1Track1))?.uriString)
    }

    @Test
    fun orderArtworkCandidates_deduplicatesSameAlbumAndRespectsMaxBound() {
        val albumATracks = (1..5).map { i ->
            track(uri = "content://f/a$i", album = "Album A", artist = "Band", track = i, title = "Song $i")
        }
        val albumBTrack = track(uri = "content://f/b1", album = "Album B", artist = "Band", track = 1, title = "Other")
        val unknownAlbumTrack1 = track(uri = "content://f/u1", album = "Unknown Album", track = 2, title = "Loose 1")
        val unknownAlbumTrack2 = track(uri = "content://f/u2", album = "Unknown Album", track = 3, title = "Loose 2")

        val candidates = FolderArtworkSelector.orderArtworkCandidates(
            tracks = albumATracks + listOf(albumBTrack, unknownAlbumTrack1, unknownAlbumTrack2),
            maxCandidates = 4,
        )

        // Album A deduplicates to its first deterministic track so Album B & loose tracks stay reachable
        assertEquals(4, candidates.size)
        assertEquals(listOf("content://f/b1", "content://f/a1", "content://f/u1", "content://f/u2"), candidates.map { it.uriString })
    }

    @Test
    fun selectRepresentativeTrack_handlesEmptyAndBlankUris() {
        assertNull(FolderArtworkSelector.selectRepresentativeTrack(emptyList()))
        assertTrue(
            FolderArtworkSelector.orderArtworkCandidates(
                listOf(track(uri = "   ", album = "Broken", title = "No Uri")),
            ).isEmpty(),
        )
    }

    private fun track(uri: String, album: String, artist: String = "Artist", disc: Int = 1, track: Int = 1, title: String) = PlaybackTrack(
        uriString = uri,
        title = title,
        artist = artist,
        album = album,
        folderName = "Music",
        fileName = "$title.flac",
        discNumber = disc,
        trackNumber = track,
    )
}
