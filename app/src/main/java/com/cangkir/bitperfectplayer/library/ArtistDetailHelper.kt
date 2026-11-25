package com.cangkir.bitperfectplayer.library

import com.cangkir.bitperfectplayer.audio.PlaybackTrack
import java.util.Locale

/**
 * Grouped album entry rendered inside the horizontal Albums carousel on `ArtistDetailView` and
 * reused as the single source of truth for `Artists` tab & Hero fallback artwork.
 */
data class ArtistAlbumItem(
    val albumName: String,
    val year: Int,
    val tracks: List<PlaybackTrack>,
    val representativeTrack: PlaybackTrack?,
) {
    val trackCount: Int get() = tracks.size

    val metadataLine: String
        get() {
            val trackWord = if (trackCount == 1) "track" else "tracks"
            return if (year > 0) "$year · $trackCount $trackWord" else "$trackCount $trackWord"
        }
}

/**
 * Shared pure-Kotlin artist artwork resolution plan consumed identically by both the `Artists`
 * tab and `ArtistDetailView` so their 4-priority fallback chains are 100% consistent.
 */
data class ArtistArtworkPlan(
    val artistName: String,
    val sortedAlbums: List<ArtistAlbumItem>,
    val embeddedArtistCandidates: List<PlaybackTrack>,
    val orderedAlbumCoverCandidates: List<PlaybackTrack>,
)

/**
 * Pure-Kotlin projected summary for `ArtistDetailView` (`Hero Banner` + `Albums` + `TRACKS`).
 */
data class ArtistDetailSummary(
    val artistName: String,
    val albums: List<ArtistAlbumItem>,
    val allTracks: List<PlaybackTrack>,
    val totalDurationMs: Long,
    val summaryLine: String,
    val orderedAlbumArtworkCandidates: List<PlaybackTrack>,
)

/**
 * Pure-Kotlin helper computing deterministic chronological (oldest-first) Artist Detail album
 * grouping, shared artist artwork resolution candidates, studio duration/summary strings, and
 * fallback monograms (zero `android.*` / `androidx.*` imports).
 */
object ArtistDetailHelper {
    private const val DEFAULT_MAX_EMBEDDED_ARTIST_CANDIDATES = 12

    /**
     * Deterministic chronological album comparator:
     * 1. Dated albums (`year in 1900..2100`) ALWAYS precede undated albums (`year <= 0`).
     * 2. Dated albums sorted **oldest-first (`year` ascending)** (e.g., `2003 -> 2005 -> 2007`).
     * 3. Tie-breaker: case-insensitive album title **A-Z**, then exact title, then stable URI key.
     */
    val ARTIST_ALBUM_COMPARATOR: Comparator<ArtistAlbumItem> =
        compareBy<ArtistAlbumItem> { ReleaseYearParser.sanitizeYear(it.year) == 0 }
            .thenBy { ReleaseYearParser.sanitizeYear(it.year).takeIf { y -> y > 0 } ?: Int.MAX_VALUE }
            .thenBy { it.albumName.lowercase(Locale.ROOT) }
            .thenBy { it.albumName }
            .thenBy { it.representativeTrack?.uriString.orEmpty() }

    fun parseReleaseYear(raw: String?): Int = ReleaseYearParser.parse(raw)

    fun sanitizeYear(year: Int): Int = ReleaseYearParser.sanitizeYear(year)

    fun resolveAlbumReleaseYear(tracks: List<PlaybackTrack>): Int = ReleaseYearParser.resolveAlbumReleaseYear(tracks.map { it.year })

    /**
     * Single shared pure-Kotlin builder grouping an artist's tracks into deterministically sorted
     * (oldest-first / chronological) [ArtistAlbumItem]s with consensus album release years across
     * all tracks in each album.
     */
    fun buildSortedArtistAlbums(
        artistTracks: List<PlaybackTrack>,
        albumTrackSortSpec: TrackSortSpec = TrackSortSpec(TrackSortOption.TRACK_NUMBER, SortDirection.ASC),
        playCountsByKey: Map<String, Int> = emptyMap(),
    ): List<ArtistAlbumItem> = artistTracks
        .groupBy { it.album.trim().ifBlank { "Unknown Album" } }
        .entries
        .map { (albumName, tracks) ->
            val ordered = LibrarySortPolicy.sortTracks(
                tracks = tracks,
                spec = albumTrackSortSpec,
                playCountsByKey = playCountsByKey,
            )
            val albumYear = resolveAlbumReleaseYear(ordered)
            ArtistAlbumItem(
                albumName = albumName,
                year = albumYear,
                tracks = ordered,
                representativeTrack = ordered.firstOrNull(),
            )
        }
        .sortedWith(ARTIST_ALBUM_COMPARATOR)

    /**
     * Builds the complete [ArtistDetailSummary] for [artistName] from [artistTracks]:
     * - Groups tracks by album and sorts albums strictly **oldest-first** via [buildSortedArtistAlbums].
     * - Produces [ArtistDetailSummary.orderedAlbumArtworkCandidates] starting with the first album
     *   in the oldest-first sorted album list.
     * - Formats the total studio summary line (e.g., `8 albums · 105 tracks · 9h 51 min`).
     */
    fun buildArtistDetail(
        artistName: String,
        artistTracks: List<PlaybackTrack>,
        albumTrackSortSpec: TrackSortSpec = TrackSortSpec(TrackSortOption.TRACK_NUMBER, SortDirection.ASC),
        playCountsByKey: Map<String, Int> = emptyMap(),
    ): ArtistDetailSummary {
        val albums = buildSortedArtistAlbums(
            artistTracks = artistTracks,
            albumTrackSortSpec = albumTrackSortSpec,
            playCountsByKey = playCountsByKey,
        )
        val totalDurationMs = artistTracks.sumOf { it.durationMs.coerceAtLeast(0L) }
        val summaryLine = formatArtistSummaryLine(
            albumCount = albums.size,
            trackCount = artistTracks.size,
            totalDurationMs = totalDurationMs,
        )
        val orderedAlbumArtworkCandidates = albums.mapNotNull { it.representativeTrack }

        return ArtistDetailSummary(
            artistName = artistName,
            albums = albums,
            allTracks = artistTracks,
            totalDurationMs = totalDurationMs,
            summaryLine = summaryLine,
            orderedAlbumArtworkCandidates = orderedAlbumArtworkCandidates,
        )
    }

    /**
     * Builds the unified [ArtistArtworkPlan] shared by the `Artists` tab and `ArtistDetailView`
     * so both screens resolve Priority 2 (embedded `ARTIST`) and Priority 3 (`Front Cover` of
     * `sortedAlbums.firstOrNull()`, oldest-first) identically.
     */
    fun buildArtistArtworkPlan(
        artistName: String,
        artistTracks: List<PlaybackTrack>,
        albumTrackSortSpec: TrackSortSpec = TrackSortSpec(TrackSortOption.TRACK_NUMBER, SortDirection.ASC),
        maxEmbeddedCandidates: Int = DEFAULT_MAX_EMBEDDED_ARTIST_CANDIDATES,
    ): ArtistArtworkPlan {
        val sortedAlbums = buildSortedArtistAlbums(
            artistTracks = artistTracks,
            albumTrackSortSpec = albumTrackSortSpec,
        )
        val embeddedCandidates = selectEmbeddedArtistArtworkCandidates(
            albums = sortedAlbums,
            allTracks = artistTracks,
            maxCandidates = maxEmbeddedCandidates,
        )
        val coverCandidates = sortedAlbums.mapNotNull { it.representativeTrack }
        return ArtistArtworkPlan(
            artistName = artistName,
            sortedAlbums = sortedAlbums,
            embeddedArtistCandidates = embeddedCandidates,
            orderedAlbumCoverCandidates = coverCandidates,
        )
    }

    /**
     * Formats total artist statistics: e.g., `"8 albums · 105 tracks · 9h 51 min"`,
     * `"1 album · 1 track · 4 min"`, or `"2 albums · 10 tracks"` when duration is unknown.
     */
    fun formatArtistSummaryLine(albumCount: Int, trackCount: Int, totalDurationMs: Long): String {
        val safeAlbums = albumCount.coerceAtLeast(0)
        val safeTracks = trackCount.coerceAtLeast(0)
        val albumWord = if (safeAlbums == 1) "album" else "albums"
        val trackWord = if (safeTracks == 1) "track" else "tracks"
        val durationPart = formatTotalDuration(totalDurationMs)
        return if (durationPart.isEmpty()) {
            "$safeAlbums $albumWord · $safeTracks $trackWord"
        } else {
            "$safeAlbums $albumWord · $safeTracks $trackWord · $durationPart"
        }
    }

    /**
     * Formats cumulative duration compactly for Artist Detail (`"9h 51 min"`, `"2h"`, `"42 min"`,
     * `"1 min"` for short non-zero tracks, or `""` when `totalDurationMs <= 0L`).
     */
    fun formatTotalDuration(totalDurationMs: Long): String {
        if (totalDurationMs <= 0L) return ""
        val totalMinutes = ((totalDurationMs + 30_000L) / 60_000L).coerceAtLeast(1L)
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return when {
            hours > 0L && minutes > 0L -> "${hours}h $minutes min"
            hours > 0L -> "${hours}h"
            else -> "$minutes min"
        }
    }

    /**
     * Computes a crisp 1-2 character studio monogram for artists without custom, embedded, or
     * album cover artwork.
     */
    fun computeMonogram(artistName: String): String {
        val tokens = artistName
            .trim()
            .split(Regex("\\s+"))
            .map { part -> part.firstOrNull { it.isLetterOrDigit() } }
            .filterNotNull()
        return when {
            tokens.isEmpty() -> "?"
            tokens.size == 1 -> tokens[0].uppercaseChar().toString()
            else -> "${tokens[0].uppercaseChar()}${tokens[1].uppercaseChar()}"
        }
    }

    /**
     * Selects a bounded list of FLAC/MP3 tracks to probe lazily for an embedded `ARTIST` picture
     * block—checking 1 track per distinct album (chronological order) before additional tracks so
     * even 200-track discographies stay fast.
     */
    fun selectEmbeddedArtistArtworkCandidates(
        albums: List<ArtistAlbumItem>,
        allTracks: List<PlaybackTrack>,
        maxCandidates: Int = DEFAULT_MAX_EMBEDDED_ARTIST_CANDIDATES,
    ): List<PlaybackTrack> {
        if (maxCandidates <= 0) return emptyList()
        val chosen = ArrayList<PlaybackTrack>(minOf(maxCandidates, allTracks.size))
        val seenUris = HashSet<String>()

        fun tryAdd(track: PlaybackTrack): Boolean {
            if (chosen.size >= maxCandidates) return false
            if (!supportsEmbeddedMultiArtwork(track)) return false
            if (seenUris.add(track.uriString)) {
                chosen.add(track)
                return true
            }
            return false
        }

        for (album in albums) {
            album.tracks.firstOrNull { supportsEmbeddedMultiArtwork(it) }?.let { tryAdd(it) }
            if (chosen.size >= maxCandidates) return chosen
        }
        for (track in allTracks) {
            tryAdd(track)
            if (chosen.size >= maxCandidates) break
        }
        return chosen
    }

    fun supportsEmbeddedMultiArtwork(track: PlaybackTrack): Boolean {
        val c = track.container.uppercase(Locale.ROOT)
        if (c == "FLAC" || c == "MP3") return true
        val lowerName = track.fileName.lowercase(Locale.ROOT)
        return lowerName.endsWith(".flac") || lowerName.endsWith(".mp3")
    }
}
