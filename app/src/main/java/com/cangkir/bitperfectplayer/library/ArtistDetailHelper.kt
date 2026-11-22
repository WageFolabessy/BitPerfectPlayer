package com.cangkir.bitperfectplayer.library

import com.cangkir.bitperfectplayer.audio.PlaybackTrack
import java.util.Locale

/**
 * Grouped album entry rendered inside the horizontal Albums carousel on `ArtistDetailView`.
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
 * Pure-Kotlin projected summary for `ArtistDetailView` (`Hero Banner` + `Albums` + `TRACKS`).
 */
data class ArtistDetailSummary(
    val artistName: String,
    val albums: List<ArtistAlbumItem>,
    val allTracks: List<PlaybackTrack>,
    val totalDurationMs: Long,
    val summaryLine: String,
    val newestAlbumArtworkCandidates: List<PlaybackTrack>,
)

/**
 * Pure-Kotlin helper computing Artist Detail album grouping (sorted newest release year first),
 * studio duration/summary strings, fallback monograms, and bounded embedded artist-art scan
 * candidates (zero `android.*` / `androidx.*` imports).
 */
object ArtistDetailHelper {
    private const val MIN_VALID_YEAR = 1000
    private const val MAX_VALID_YEAR = 2999
    private const val DEFAULT_MAX_EMBEDDED_ARTIST_CANDIDATES = 12

    /**
     * Parses a 4-digit release year from tag strings such as `"2023"`, `"2023-05-12"`, or
     * `"1997/06/16"`. Returns `0` when missing or outside `1000..2999`.
     */
    fun parseReleaseYear(raw: String?): Int {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.length < 4) return 0
        var runStart = -1
        for (i in trimmed.indices) {
            if (trimmed[i].isDigit()) {
                if (runStart < 0) runStart = i
                if (i - runStart + 1 == 4) {
                    val candidate = trimmed.substring(runStart, i + 1).toIntOrNull() ?: 0
                    if (candidate in MIN_VALID_YEAR..MAX_VALID_YEAR) return candidate
                    runStart = -1
                }
            } else {
                runStart = -1
            }
        }
        return 0
    }

    fun sanitizeYear(year: Int): Int = if (year in MIN_VALID_YEAR..MAX_VALID_YEAR) year else 0

    /**
     * Builds the complete [ArtistDetailSummary] for [artistName] from [artistTracks]:
     * - Groups tracks by album name and sorts tracks inside each album using [albumTrackSortSpec].
     * - Sorts albums newest release year first (`year > 0` descending, alphabetical tie-breaker;
     *   albums without a release year follow alphabetically).
     * - Formats the total studio summary line (e.g., `8 albums · 105 tracks · 9h 51 min`).
     */
    fun buildArtistDetail(
        artistName: String,
        artistTracks: List<PlaybackTrack>,
        albumTrackSortSpec: TrackSortSpec = TrackSortSpec(TrackSortOption.TRACK_NUMBER, SortDirection.ASC),
        playCountsByKey: Map<String, Int> = emptyMap(),
    ): ArtistDetailSummary {
        val albums = artistTracks
            .groupBy { it.album.ifBlank { "Unknown Album" } }
            .entries
            .map { (albumName, tracks) ->
                val ordered = LibrarySortPolicy.sortTracks(
                    tracks = tracks,
                    spec = albumTrackSortSpec,
                    playCountsByKey = playCountsByKey,
                )
                val albumYear = ordered.maxOfOrNull { sanitizeYear(it.year) } ?: 0
                ArtistAlbumItem(
                    albumName = albumName,
                    year = albumYear,
                    tracks = ordered,
                    representativeTrack = ordered.firstOrNull(),
                )
            }
            .sortedWith(
                compareByDescending<ArtistAlbumItem> { it.year > 0 }
                    .thenByDescending { it.year }
                    .thenBy { it.albumName.lowercase(Locale.ROOT) },
            )

        val totalDurationMs = artistTracks.sumOf { it.durationMs.coerceAtLeast(0L) }
        val summaryLine = formatArtistSummaryLine(
            albumCount = albums.size,
            trackCount = artistTracks.size,
            totalDurationMs = totalDurationMs,
        )
        val newestAlbumArtworkCandidates = albums.mapNotNull { it.representativeTrack }

        return ArtistDetailSummary(
            artistName = artistName,
            albums = albums,
            allTracks = artistTracks,
            totalDurationMs = totalDurationMs,
            summaryLine = summaryLine,
            newestAlbumArtworkCandidates = newestAlbumArtworkCandidates,
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
     * Computes a crisp 1–2 character studio monogram for artists without custom, embedded, or
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
     * block—checking 1 track per distinct album (newest first) before additional tracks so even
     * 200-track discographies stay fast.
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
