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
        year: Int = 0,
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
        year = year,
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
    fun artistDetailDefault_defaultsToReleaseYearAscMatchingAlbumsCarouselWithIntraAlbumTrackOrder() {
        assertEquals(
            TrackSortSpec(TrackSortOption.RELEASE_YEAR, SortDirection.ASC),
            TrackSortScope.ARTIST_DETAIL.defaultSpec,
        )
        assertEquals(
            listOf(TrackSortOption.RELEASE_YEAR, TrackSortOption.ALBUM),
            TrackSortScope.ARTIST_DETAIL.availableOptions.take(2),
        )
        assertEquals("Release Year", TrackSortOption.RELEASE_YEAR.fullLabel)
        assertEquals("Year ↑", TrackSortScope.ARTIST_DETAIL.defaultSpec.compactLabel)

        val shuffledTracks = listOf(
            track(1, "LP2013 T2", album = "LP 2013", disc = 1, trackNum = 2, year = 2013),
            track(2, "LP2013 T1", album = "LP 2013", disc = 1, trackNum = 1, year = 2013),
            track(3, "Undated T2", album = "Bonus EP", disc = 1, trackNum = 2, year = 0),
            track(4, "Undated T1", album = "Bonus EP", disc = 1, trackNum = 1, year = 0),
            track(5, "Beta 2005 T1", album = "Beta 2005", disc = 1, trackNum = 1, year = 2005),
            track(6, "LP2001 D2T1", album = "Debut LP", disc = 2, trackNum = 1, year = 2001),
            track(7, "LP2001 D1T10", album = "Debut LP", disc = 1, trackNum = 10, year = 0),
            track(8, "LP2001 D1T2", album = "Debut LP", disc = 1, trackNum = 2, year = 2001),
            track(9, "LP2001 D1T1", album = "Debut LP", disc = 1, trackNum = 1, year = 2001),
            track(10, "Alpha 2005 T1", album = "Alpha 2005", disc = 1, trackNum = 1, year = 2005),
        )

        val carouselTracks = ArtistDetailHelper.buildSortedArtistAlbums(shuffledTracks).flatMap { it.tracks }
        val sortedAsc = LibrarySortPolicy.sortTracks(
            tracks = shuffledTracks,
            spec = TrackSortScope.ARTIST_DETAIL.defaultSpec,
        )

        assertEquals(carouselTracks.map { it.uriString }, sortedAsc.map { it.uriString })
        assertEquals(
            listOf(
                "LP2001 D1T1",
                "LP2001 D1T2",
                "LP2001 D1T10",
                "LP2001 D2T1",
                "Alpha 2005 T1",
                "Beta 2005 T1",
                "LP2013 T1",
                "LP2013 T2",
                "Undated T1",
                "Undated T2",
            ),
            sortedAsc.map { it.title },
        )
    }

    @Test
    fun releaseYearDescending_ordersNewestAlbumFirstWhileKeepingIntraAlbumAscendingAndUndatedLast() {
        val tracks = listOf(
            track(1, "LP2001 T1", album = "Debut 2001", disc = 1, trackNum = 1, year = 2001),
            track(2, "LP2001 T10", album = "Debut 2001", disc = 1, trackNum = 10, year = 2001),
            track(3, "LP2001 T2", album = "Debut 2001", disc = 1, trackNum = 2, year = 0),
            track(4, "LP2013 T2", album = "Late 2013", disc = 1, trackNum = 2, year = 2013),
            track(5, "LP2013 T1", album = "Late 2013", disc = 1, trackNum = 1, year = 2013),
            track(6, "Mid B T1", album = "Zeta 2007", disc = 1, trackNum = 1, year = 2007),
            track(7, "Mid A T1", album = "Alpha 2007", disc = 1, trackNum = 1, year = 2007),
            track(8, "Undated T2", album = "Session Notes", disc = 1, trackNum = 2, year = 0),
            track(9, "Undated T1", album = "Session Notes", disc = 1, trackNum = 1, year = 0),
        )

        val sortedDesc = LibrarySortPolicy.sortTracks(
            tracks = tracks,
            spec = TrackSortSpec(TrackSortOption.RELEASE_YEAR, SortDirection.DESC),
        )

        assertEquals(
            listOf(
                "LP2013 T1",
                "LP2013 T2",
                "Mid A T1",
                "Mid B T1",
                "LP2001 T1",
                "LP2001 T2",
                "LP2001 T10",
                "Undated T1",
                "Undated T2",
            ),
            sortedDesc.map { it.title },
        )
    }

    @Test
    fun albumSortRemainsAlphabetical_andArtistDetailPreservesExplicitUserSelection() {
        val d2024 = track(1, "Saviors Opening", album = "Saviors", disc = 1, trackNum = 1, year = 2024)
        val a2004t2 = track(2, "Jesus of Suburbia", album = "American Idiot", disc = 1, trackNum = 2, year = 2004)
        val a2004t1 = track(3, "American Idiot", album = "American Idiot", disc = 1, trackNum = 1, year = 2004)
        val d1994t2 = track(4, "Basket Case", album = "Dookie", disc = 1, trackNum = 7, year = 1994)
        val d1994t1 = track(5, "Burnout", album = "Dookie", disc = 1, trackNum = 1, year = 1994)

        val byAlbumAsc = LibrarySortPolicy.sortTracks(
            tracks = listOf(d2024, d1994t2, a2004t2, d1994t1, a2004t1),
            spec = TrackSortSpec(TrackSortOption.ALBUM, SortDirection.ASC),
        )
        assertEquals(
            listOf("American Idiot", "Jesus of Suburbia", "Burnout", "Basket Case", "Saviors Opening"),
            byAlbumAsc.map { it.title },
        )

        assertEquals(
            TrackSortSpec(TrackSortOption.ALBUM, SortDirection.ASC),
            TrackSortSpec.deserialize(
                raw = "ALBUM:ASC",
                allowedOptions = TrackSortScope.ARTIST_DETAIL.availableOptions,
                fallback = TrackSortScope.ARTIST_DETAIL.defaultSpec,
            ),
        )
        assertEquals(
            TrackSortScope.ARTIST_DETAIL.defaultSpec,
            TrackSortSpec.deserialize(
                raw = null,
                allowedOptions = TrackSortScope.ARTIST_DETAIL.availableOptions,
                fallback = TrackSortScope.ARTIST_DETAIL.defaultSpec,
            ),
        )
    }

    @Test
    fun sampleRateMostPlayedAndDateModified_sortAccuratelyWithTieBreakers() {
        val fpA = "a".repeat(64)
        val fpB = "b".repeat(64)
        val fpC = "c".repeat(64)

        val cd = track(1, "CD Track", sampleRateHz = 44_100, bitDepth = 16, dateMod = 3000L, fp = fpA)
        val hires96Bit16 = track(2, "96k 16-bit", sampleRateHz = 96_000, bitDepth = 16, dateMod = 1000L, fp = fpB)
        val hires96Bit24 = track(3, "96k 24-bit", sampleRateHz = 96_000, bitDepth = 24, dateMod = 2000L, fp = fpC)
        val hires192Bit24 = track(4, "192k 24-bit", sampleRateHz = 192_000, bitDepth = 24, dateMod = 500L)

        val byHiRes = LibrarySortPolicy.sortTracks(
            tracks = listOf(cd, hires96Bit16, hires192Bit24, hires96Bit24),
            spec = TrackSortSpec(TrackSortOption.SAMPLE_RATE, SortDirection.DESC),
        )
        assertEquals(
            listOf("192k 24-bit", "96k 24-bit", "96k 16-bit", "CD Track"),
            byHiRes.map { it.title },
        )

        val byMostPlayed = LibrarySortPolicy.sortTracks(
            tracks = listOf(cd, hires96Bit16, hires96Bit24, hires192Bit24),
            spec = TrackSortSpec(TrackSortOption.MOST_PLAYED, SortDirection.DESC),
            playCountsByKey = mapOf(fpB to 12, fpA to 4, "content://media/4" to 25),
        )
        assertEquals(
            listOf("192k 24-bit", "96k 16-bit", "CD Track", "96k 24-bit"),
            byMostPlayed.map { it.title },
        )

        val byDateDesc = LibrarySortPolicy.sortTracks(
            tracks = listOf(cd, hires96Bit16, hires96Bit24, hires192Bit24),
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
