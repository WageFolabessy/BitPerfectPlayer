package com.cangkir.bitperfectplayer.library

import com.cangkir.bitperfectplayer.audio.PlaybackTrack
import java.util.Locale

/**
 * Pure-Kotlin deterministic representative Front Cover candidate selector for `Folders` tab rows
 * (zero `android.*` / `androidx.*` imports).
 *
 * Sorts folder tracks deterministically (disc # -> track # -> title A-Z -> filename A-Z -> URI)
 * regardless of input/scan order and yields a bounded deduplicated list of artwork candidates so
 * `AlbumArtworkLoader` picks the first track with an actual Front Cover thumbnail without scanning
 * hundreds of identical tracks in a coverless folder.
 */
object FolderArtworkSelector {
    const val DEFAULT_MAX_CANDIDATES: Int = 8

    val DETERMINISTIC_TRACK_COMPARATOR: Comparator<PlaybackTrack> =
        compareBy<PlaybackTrack> { if (it.discNumber > 0) it.discNumber else Int.MAX_VALUE }
            .thenBy { if (it.trackNumber > 0) it.trackNumber else Int.MAX_VALUE }
            .thenBy { it.title.lowercase(Locale.ROOT) }
            .thenBy { it.fileName.lowercase(Locale.ROOT) }
            .thenBy { it.uriString }

    /**
     * Returns the deterministically first playable track with a valid non-blank URI in [tracks],
     * or `null` when none exists.
     */
    fun selectRepresentativeTrack(tracks: List<PlaybackTrack>): PlaybackTrack? =
        orderArtworkCandidates(tracks = tracks, maxCandidates = 1).firstOrNull()

    /**
     * Returns a deterministically ordered, bounded list of Front Cover artwork candidates for a
     * folder. Tracks sharing a known non-blank `(album, artist)` pair are deduplicated so probing
     * falls through quickly to other albums inside a mixed folder if the first album has no cover.
     */
    fun orderArtworkCandidates(tracks: List<PlaybackTrack>, maxCandidates: Int = DEFAULT_MAX_CANDIDATES): List<PlaybackTrack> {
        if (tracks.isEmpty() || maxCandidates <= 0) return emptyList()
        val ordered = tracks
            .filter { it.uriString.isNotBlank() }
            .sortedWith(DETERMINISTIC_TRACK_COMPARATOR)
        if (ordered.isEmpty()) return emptyList()

        val result = ArrayList<PlaybackTrack>(minOf(maxCandidates, ordered.size))
        val seenKeys = HashSet<String>(minOf(maxCandidates * 2, ordered.size * 2))
        for (track in ordered) {
            val dedupeKey = artworkDedupeKey(track)
            if (seenKeys.add(dedupeKey)) {
                result.add(track)
                if (result.size >= maxCandidates) break
            }
        }
        return result
    }

    private fun artworkDedupeKey(track: PlaybackTrack): String {
        val album = track.album.trim().lowercase(Locale.ROOT)
        val hasNamedAlbum = album.isNotEmpty() && album != "unknown album" && album != "<unknown>"
        return if (hasNamedAlbum) {
            val artist = track.artist.trim().lowercase(Locale.ROOT)
            "album:$artist|$album"
        } else {
            "uri:${track.uriString}"
        }
    }
}
