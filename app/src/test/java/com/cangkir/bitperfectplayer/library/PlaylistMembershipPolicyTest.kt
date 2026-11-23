package com.cangkir.bitperfectplayer.library

import com.cangkir.bitperfectplayer.audio.PlaybackTrack
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistMembershipPolicyTest {

    private val trackA = PlaybackTrack(
        uriString = "content://media/external/audio/media/10",
        title = "Peacemaker",
        artist = "Green Day",
        album = "21st Century Breakdown",
        folderName = "FLAC",
        fileName = "07 - Peacemaker.flac",
        contentFingerprint = "a".repeat(64),
    )

    @Test
    fun isTrackDuplicateOfPersisted_matchesNormalizedFingerprintOrTrimmedUri() {
        // Exact lowercase/uppercase/whitespace fingerprint match
        assertTrue(
            PlaylistMembershipPolicy.isTrackDuplicateOfPersisted(
                trackFingerprint = "  ${"A".repeat(64)} ",
                trackUri = "content://moved/new_location.flac",
                persistedFingerprints = listOf("a".repeat(64)),
                persistedUris = listOf("content://media/external/audio/media/10"),
            ),
        )

        // Exact trimmed URI match when fingerprint is absent
        assertTrue(
            PlaylistMembershipPolicy.isTrackDuplicateOfPersisted(
                trackFingerprint = null,
                trackUri = " content://media/external/audio/media/10 ",
                persistedFingerprints = listOf(null),
                persistedUris = listOf("content://media/external/audio/media/10"),
            ),
        )
    }

    @Test
    fun isTrackDuplicateOfPersisted_doesNotFalsePositiveOnUntaggedDifferentFilesWithSameFilename() {
        assertFalse(
            PlaylistMembershipPolicy.isTrackDuplicateOfPersisted(
                trackFingerprint = null,
                trackUri = "content://folder_b/01.wav",
                persistedFingerprints = listOf("", null),
                persistedUris = listOf("content://folder_a/01.wav"),
            ),
        )
    }

    @Test
    fun isTrackInPlaylist_detectsExistingEntryAndAllowsDistinctTracks() {
        val playlist = ResolvedPlaylist(
            id = 1L,
            name = "Reference Mix",
            createdAtEpochMs = 1L,
            updatedAtEpochMs = 2L,
            entries = listOf(
                ResolvedPlaylistEntry(entryId = 100L, playlistId = 1L, positionIndex = 0, track = trackA),
            ),
        )
        val sameContentRelocated = trackA.copy(uriString = "content://saf/copy/peacemaker.flac")
        val differentTrack = trackA.copy(
            uriString = "content://media/external/audio/media/11",
            title = "Static Age",
            contentFingerprint = "b".repeat(64),
        )

        assertTrue(PlaylistMembershipPolicy.isTrackInPlaylist(trackA, playlist))
        assertTrue(PlaylistMembershipPolicy.isTrackInPlaylist(sameContentRelocated, playlist))
        assertFalse(PlaylistMembershipPolicy.isTrackInPlaylist(differentTrack, playlist))
    }
}
