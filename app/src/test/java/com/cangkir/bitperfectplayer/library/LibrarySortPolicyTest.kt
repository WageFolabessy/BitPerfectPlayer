package com.cangkir.bitperfectplayer.library

import com.cangkir.bitperfectplayer.audio.PlaybackTrack
import org.junit.Assert.assertEquals
import org.junit.Test

class LibrarySortPolicyTest {

    private fun track(
        id: Int,
        title: String,
        artist: String = "Green Day",
        album: String = "21st Century Breakdown",
        folder: String = "Rock",
        fileName: String = "$title.flac",
        disc: Int = 0,
        trackNum: Int = 0,
        durationMs: Long = 200_000L,
        sampleRateHz: Int = 44_100,
        bitDepth: Int? = 16,
        dateMod: Long = 1000L,
        fp: String? = null,
    ): PlaybackTrack = PlaybackTrack(
        uriString = "content://media/$id",
        title = title,
        artist = artist,
        album = album,
        folderName = folder,
        fileName = fileName,
        durationMs = durationMs,
        sampleRateHz = sampleRateHz,
        bitDepth = bitDepth,
        dateModifiedSeconds = dateMod,
        contentFingerprint = fp,
        discNumber = disc,
        trackNumber = trackNum,
    )

    @Test
    fun albumDetailDefault_ordersByDiscThenTrackThenNaturalFilename_neverAlphabetically() {
        val d2t1 = track(1, "Zebra Encore", disc = 2, trackNum = 1)
        val d1t10 = track(2, "Alpha Tenth", disc = 1, trackNum = 10)
        val d1t2 = track(3, "Know Your Enemy", disc = 1, trackNum = 2)
        val d1t1 = track(4, "Song of the Century", disc = 1, trackNum = 1)
        val untaggedB = track(5, "Bonus B", disc = 0, trackNum = 0, fileName = "10 - Bonus.flac")
        val untaggedA = track(6, "Bonus A", disc = 0, trackNum = 0, fileName = "2 - Bonus.flac")

        val sorted = LibrarySortPolicy.sortTracks(
            tracks = listOf(d2t1, untaggedB, d1t10, d1t2, untaggedA, d1t1),
            spec = TrackSortScope.ALBUM_DETAIL.defaultSpec,
        )

        assertEquals(
            listOf("Song of the Century", "Know Your Enemy", "Alpha Tenth", "Zebra Encore", "Bonus A", "Bonus B"),
            sorted.map { it.title },
        )

        // Descending direction reverses cleanly
        val reversed = LibrarySortPolicy.sortTracks(
            tracks = sorted,
            spec = TrackSortSpec(TrackSortOption.TRACK_NUMBER, SortDirection.DESC),
        )
        assertEquals(sorted.reversed().map { it.title }, reversed.map { it.title })
    }

    @Test
    fun artistDetailDefault_groupsByAlbumThenDiscAndTrackNumber() {
        val a2t2 = track(1, "Basket Case", album = "Dookie", disc = 1, trackNum = 7)
        val a2t1 = track(2, "Burnout", album = "Dookie", disc = 1, trackNum = 1)
        val a1t2 = track(3, "Jesus of Suburbia", album = "American Idiot", disc = 1, trackNum = 2)
        val a1t1 = track(4, "American Idiot", album = "American Idiot", disc = 1, trackNum = 1)

        val sorted = LibrarySortPolicy.sortTracks(
            tracks = listOf(a2t2, a1t2, a2t1, a1t1),
            spec = TrackSortScope.ARTIST_DETAIL.defaultSpec,
        )

        assertEquals(
            listOf("American Idiot", "Jesus of Suburbia", "Burnout", "Basket Case"),
            sorted.map { it.title },
        )
    }

    @Test
    fun sampleRateMostPlayedAndDateModified_sortAccuratelyWithTieBreakers() {
        val fpA = "a".repeat(64)
        val fpB = "b".repeat(64)
        val fpC = "c".repeat(64)

        val cd = track(1, "CD Track", sampleRateHz = 44_100, bitDepth = 16, dateMod = 3000L, fp = fpA)
        val hires96_16 = track(2, "96k 16-bit", sampleRateHz = 96_000, bitDepth = 16, dateMod = 1000L, fp = fpB)
        val hires96_24 = track(3, "96k 24-bit", sampleRateHz = 96_000, bitDepth = 24, dateMod = 2000L, fp = fpC)
        val hires192_24 = track(4, "192k 24-bit", sampleRateHz = 192_000, bitDepth = 24, dateMod = 500L)

        val byHiRes = LibrarySortPolicy.sortTracks(
            tracks = listOf(cd, hires96_16, hires192_24, hires96_24),
            spec = TrackSortSpec(TrackSortOption.SAMPLE_RATE, SortDirection.DESC),
        )
        assertEquals(
            listOf("192k 24-bit", "96k 24-bit", "96k 16-bit", "CD Track"),
            byHiRes.map { it.title },
        )

        val byMostPlayed = LibrarySortPolicy.sortTracks(
            tracks = listOf(cd, hires96_16, hires96_24, hires192_24),
            spec = TrackSortSpec(TrackSortOption.MOST_PLAYED, SortDirection.DESC),
            playCountsByKey = mapOf(fpB to 12, fpA to 4, "content://media/4" to 25),
        )
        assertEquals(
            listOf("192k 24-bit", "96k 16-bit", "CD Track", "96k 24-bit"),
            byMostPlayed.map { it.title },
        )

        val byDateDesc = LibrarySortPolicy.sortTracks(
            tracks = listOf(cd, hires96_16, hires96_24, hires192_24),
            spec = TrackSortSpec(TrackSortOption.DATE_MODIFIED, SortDirection.DESC),
        )
        assertEquals(
            listOf("CD Track", "96k 24-bit", "96k 16-bit", "192k 24-bit"),
            byDateDesc.map { it.title },
        )
    }

    @Test
    fun groupSortingAndSpecDeserialization_workAndRecoverFromCorruptValues() {
        val groups = listOf(
            LibraryGroup("Zeppelin", "2 tracks", listOf(track(1, "A"), track(2, "B"))),
            LibraryGroup("Adele", "5 tracks", List(5) { track(10 + it, "T$it") }),
            LibraryGroup("Miles", "5 tracks", List(5) { track(20 + it, "M$it") }),
        )

        val byName = LibrarySortPolicy.sortGroups(groups, GroupSortSpec(GroupSortOption.NAME, SortDirection.ASC))
        assertEquals(listOf("Adele", "Miles", "Zeppelin"), byName.map { it.name })

        val byCountDesc = LibrarySortPolicy.sortGroups(groups, GroupSortSpec(GroupSortOption.TRACK_COUNT, SortDirection.DESC))
        // Ties break A->Z cleanly
        assertEquals(listOf("Adele", "Miles", "Zeppelin"), byCountDesc.map { it.name })

        val byCountAsc = LibrarySortPolicy.sortGroups(groups, GroupSortSpec(GroupSortOption.TRACK_COUNT, SortDirection.ASC))
        assertEquals(listOf("Zeppelin", "Adele", "Miles"), byCountAsc.map { it.name })

        // Spec round-trip & corrupt value recovery
        val serialized = TrackSortSpec(TrackSortOption.SAMPLE_RATE, SortDirection.DESC).serialize()
        assertEquals(
            TrackSortSpec(TrackSortOption.SAMPLE_RATE, SortDirection.DESC),
            TrackSortSpec.deserialize(
                raw = serialized,
                allowedOptions = TrackSortScope.ALL_SONGS.availableOptions,
                fallback = TrackSortScope.ALL_SONGS.defaultSpec,
            ),
        )
        // Disallowed option for ALBUM_DETAIL (e.g., CUSTOM_ORDER) safely falls back to TRACK_NUMBER ASC
        assertEquals(
            TrackSortScope.ALBUM_DETAIL.defaultSpec,
            TrackSortSpec.deserialize(
                raw = "CUSTOM_ORDER:ASC",
                allowedOptions = TrackSortScope.ALBUM_DETAIL.availableOptions,
                fallback = TrackSortScope.ALBUM_DETAIL.defaultSpec,
            ),
        )
    }

    @Test
    fun libraryProjector_keepsAllSongsAlphabeticalWhileOrderingAlbumsByTrackNumber() {
        val t1 = track(1, "Zebra Opening", album = "Concept LP", disc = 1, trackNum = 1)
        val t2 = track(2, "Alpha Closing", album = "Concept LP", disc = 1, trackNum = 2)

        val snapshot = LibraryProjector.buildSnapshot(
            allTracks = listOf(t1, t2),
            historyRecords = emptyList(),
            favoriteRecords = emptyList(),
        )

        // All Songs defaults to Title ASC
        assertEquals(listOf("Alpha Closing", "Zebra Opening"), snapshot.allSongs.map { it.title })
        // Album detail defaults to Track # ASC (NEVER alphabetical!)
        assertEquals(listOf("Zebra Opening", "Alpha Closing"), snapshot.albums.single().tracks.map { it.title })
    }
}
