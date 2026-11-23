package com.cangkir.bitperfectplayer.ui.theme

import com.cangkir.bitperfectplayer.audio.PlaybackTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeArtworkCandidateSelectorTest {

    private fun sample(
        uri: String,
        artist: String = "Artist",
        album: String = "Album",
        fileName: String = "track.flac",
        discNumber: Int = 0,
        trackNumber: Int = 0,
    ) = PlaybackTrack(
        uriString = uri,
        title = fileName,
        artist = artist,
        album = album,
        folderName = "Music",
        fileName = fileName,
        discNumber = discNumber,
        trackNumber = trackNumber,
    )

    @Test
    fun selectStableCandidates_isInvariantToListSortOrderAndPrioritizesPositiveDiscAndTrack() {
        val t1 = sample("u1", fileName = "01.flac", discNumber = 1, trackNumber = 1)
        val t2 = sample("u2", album = "Unknown Album", fileName = "02.wav", discNumber = 0, trackNumber = 2)
        val t3 = sample("u3", album = "Second LP", fileName = "01_b.flac", discNumber = 1, trackNumber = 3)
        val untaggedZ = sample("uz", album = "", fileName = "z_rip.wav", discNumber = 0, trackNumber = 0)
        val untaggedA = sample("ua", album = "", fileName = "a_rip.wav", discNumber = 0, trackNumber = 0)

        val forward = ThemeArtworkCandidateSelector.selectStableCandidates(listOf(t1, t2, t3, untaggedZ, untaggedA))
        val reversed = ThemeArtworkCandidateSelector.selectStableCandidates(listOf(untaggedA, untaggedZ, t3, t2, t1))

        assertEquals(forward.map { it.uriString }, reversed.map { it.uriString })
        assertEquals(listOf("u1", "u3", "u2", "ua", "uz"), forward.map { it.uriString })
    }

    @Test
    fun selectStableCandidates_handlesEmptyAndCapsMaxCandidates() {
        assertTrue(ThemeArtworkCandidateSelector.selectStableCandidates(emptyList()).isEmpty())
        val many = (1..10).map { i -> sample("u$i", album = "Album $i", fileName = "$i.flac", trackNumber = i) }
        val selected = ThemeArtworkCandidateSelector.selectStableCandidates(many, maxCandidates = 3)
        assertEquals(listOf("u1", "u2", "u3"), selected.map { it.uriString })
    }
}
