package com.cangkir.bitperfectplayer.library

import com.cangkir.bitperfectplayer.audio.PlaybackTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistDetailHelperTest {
    @Test
    fun parseReleaseYearHandlesIsoDatesPlainYearsAndInvalidValues() {
        assertEquals(2023, ArtistDetailHelper.parseReleaseYear("2023"))
        assertEquals(1997, ArtistDetailHelper.parseReleaseYear("1997-06-16"))
        assertEquals(2015, ArtistDetailHelper.parseReleaseYear("  2015/03/21  "))
        assertEquals(1994, ArtistDetailHelper.parseReleaseYear("1994; 2009"))
        assertEquals(0, ArtistDetailHelper.parseReleaseYear(""))
        assertEquals(0, ArtistDetailHelper.parseReleaseYear(null))
        assertEquals(0, ArtistDetailHelper.parseReleaseYear("0000"))
        assertEquals(0, ArtistDetailHelper.parseReleaseYear("1899"))
        assertEquals(0, ArtistDetailHelper.parseReleaseYear("2101"))
        assertEquals(0, ArtistDetailHelper.parseReleaseYear("99"))
        assertEquals(0, ArtistDetailHelper.parseReleaseYear("unknown"))
    }

    @Test
    fun buildArtistDetailSortsAlbumsChronologicallyOldestFirstThenAlphabeticalAndFormatsStats() {
        val tracks = listOf(
            sampleTrack("u3", album = "Latest LP", year = 2024, trackNumber = 1, durationMs = 3_600_000L),
            sampleTrack("u5", album = "Beta 2020", year = 2020, trackNumber = 1, durationMs = 300_000L),
            sampleTrack("u6", album = "No Date Album", year = 0, trackNumber = 1, durationMs = 120_000L),
            sampleTrack("u1", album = "Debut", year = 1995, trackNumber = 2, durationMs = 240_000L),
            sampleTrack("u2", album = "Debut", year = 1995, trackNumber = 1, durationMs = 180_000L),
            sampleTrack("u4", album = "Alpha 2020", year = 2020, trackNumber = 1, durationMs = 300_000L),
        )

        val summary = ArtistDetailHelper.buildArtistDetail("Studio Artist", tracks)

        assertEquals(
            listOf("Debut", "Alpha 2020", "Beta 2020", "Latest LP", "No Date Album"),
            summary.albums.map { it.albumName },
        )
        assertEquals("1995 · 2 tracks", summary.albums[0].metadataLine)
        assertEquals("2024 · 1 track", summary.albums[3].metadataLine)
        assertEquals("1 track", summary.albums[4].metadataLine)
        // Tracks inside album sorted by track number ascending
        assertEquals(listOf("u2", "u1"), summary.albums[0].tracks.map { it.uriString })
        // Total duration: 240 + 180 + 3600 + 300 + 300 + 120 = 4740s = 79 min = 1h 19 min
        assertEquals("5 albums · 6 tracks · 1h 19 min", summary.summaryLine)
        // First album artwork candidate must be the oldest album's representative track (u2)
        assertEquals("u2", summary.orderedAlbumArtworkCandidates.first().uriString)
    }

    @Test
    fun buildSortedArtistAlbums_shuffledMultiYearTracksAlwaysSortOldestFirstWithConsensusYear() {
        val shuffled = listOf(
            sampleTrack("u_2013", album = "LP 2013", year = 2013),
            sampleTrack("u_undated_z", album = "Zebra Demo", year = 0),
            sampleTrack("u_2007_a", album = "LP 2007", year = 2007, trackNumber = 1),
            sampleTrack("u_2007_b", album = "LP 2007", year = 0, trackNumber = 2), // untagged track must NOT zero out album
            sampleTrack("u_2007_c", album = "LP 2007", year = 2007, trackNumber = 3),
            sampleTrack("u_2005_b", album = "Beta 2005", year = 2005),
            sampleTrack("u_2010", album = "LP 2010", year = 2010),
            sampleTrack("u_2003", album = "LP 2003", year = 2003),
            sampleTrack("u_undated_a", album = "Aardvark Session", year = 0),
            sampleTrack("u_2005_a", album = "Alpha 2005", year = 2005),
        )

        val albums = ArtistDetailHelper.buildSortedArtistAlbums(shuffled)
        val plan = ArtistDetailHelper.buildArtistArtworkPlan("Studio Artist", shuffled)

        val expectedAlbums = listOf(
            "LP 2003",
            "Alpha 2005",
            "Beta 2005",
            "LP 2007",
            "LP 2010",
            "LP 2013",
            "Aardvark Session",
            "Zebra Demo",
        )
        assertEquals(expectedAlbums, albums.map { it.albumName })
        assertEquals(listOf(2003, 2005, 2005, 2007, 2010, 2013, 0, 0), albums.map { it.year })
        assertEquals(expectedAlbums, plan.sortedAlbums.map { it.albumName })
        assertEquals("u_2003", plan.orderedAlbumCoverCandidates.first().uriString)
    }

    @Test
    fun formatArtistSummaryLineHandlesSingularPluralAndDurationEdgeCases() {
        val nineHours51MinMs = (9L * 60L + 51L) * 60_000L
        assertEquals(
            "8 albums · 105 tracks · 9h 51 min",
            ArtistDetailHelper.formatArtistSummaryLine(8, 105, nineHours51MinMs),
        )
        assertEquals(
            "1 album · 1 track · 4 min",
            ArtistDetailHelper.formatArtistSummaryLine(1, 1, 240_000L),
        )
        assertEquals(
            "2 albums · 5 tracks · 2h",
            ArtistDetailHelper.formatArtistSummaryLine(2, 5, 2L * 3_600_000L),
        )
        assertEquals(
            "1 album · 1 track · 1 min",
            ArtistDetailHelper.formatArtistSummaryLine(1, 1, 15_000L),
        )
        assertEquals(
            "2 albums · 3 tracks",
            ArtistDetailHelper.formatArtistSummaryLine(2, 3, 0L),
        )
    }

    @Test
    fun computeMonogramHandlesMultiWordSingleWordBlankAndSymbolNames() {
        assertEquals("PF", ArtistDetailHelper.computeMonogram("Pink Floyd"))
        assertEquals("R", ArtistDetailHelper.computeMonogram("Radiohead"))
        assertEquals("MD", ArtistDetailHelper.computeMonogram("Miles Davis Quintet"))
        assertEquals("?", ArtistDetailHelper.computeMonogram("   "))
        assertEquals("?", ArtistDetailHelper.computeMonogram("--- / ***"))
    }

    @Test
    fun selectEmbeddedArtistArtworkCandidatesBoundsAndPrioritizesOnePerAlbum() {
        val tracks = (1..20).map { i ->
            sampleTrack(
                uri = "content://track/$i",
                album = "Album ${(i - 1) / 4}",
                year = 2020 + (i - 1) / 4,
                container = if (i % 5 == 0) "WAV" else "FLAC",
                fileName = if (i % 5 == 0) "t$i.wav" else "t$i.flac",
            )
        }
        val summary = ArtistDetailHelper.buildArtistDetail("Band", tracks)
        val candidates = ArtistDetailHelper.selectEmbeddedArtistArtworkCandidates(
            albums = summary.albums,
            allTracks = tracks,
            maxCandidates = 8,
        )

        assertEquals(8, candidates.size)
        assertTrue(candidates.all { it.container == "FLAC" })
        // First 5 candidates come from all 5 distinct albums (chronological oldest-first)
        assertEquals(5, candidates.take(5).map { it.album }.toSet().size)
        assertEquals("Album 0", candidates.first().album)
    }

    private fun sampleTrack(
        uri: String,
        album: String,
        year: Int = 0,
        trackNumber: Int = 1,
        durationMs: Long = 180_000L,
        container: String = "FLAC",
        fileName: String = "track.flac",
    ) = PlaybackTrack(
        uriString = uri,
        title = "Title $uri",
        artist = "Studio Artist",
        album = album,
        fileName = fileName,
        durationMs = durationMs,
        container = container,
        trackNumber = trackNumber,
        year = year,
    )
}
