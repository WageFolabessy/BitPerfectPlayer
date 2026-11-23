package com.cangkir.bitperfectplayer.ui.theme

import com.cangkir.bitperfectplayer.audio.PlaybackTrack
import com.cangkir.bitperfectplayer.library.AlbumArtworkHelper
import java.util.Locale

/**
 * Pure-Kotlin deterministic cover-artwork candidate selector for contextual **Dynamic Theme**
 * (zero `android.*` / `androidx.*` imports).
 *
 * Orders tracks independently of the user's active list sort (preferring valid positive disc and
 * track numbers, then filename and URI) so changing sort order never causes album or playlist
 * theme colors to jump, and coverless Track #1 falls back cleanly to another covered track.
 */
object ThemeArtworkCandidateSelector {

    private const val DEFAULT_MAX_CANDIDATES = 5

    private val STABLE_COMPARATOR: Comparator<PlaybackTrack> =
        compareBy<PlaybackTrack> { it.discNumber <= 0 }
            .thenBy { if (it.discNumber > 0) it.discNumber else Int.MAX_VALUE }
            .thenBy { it.trackNumber <= 0 }
            .thenBy { if (it.trackNumber > 0) it.trackNumber else Int.MAX_VALUE }
            .thenBy { it.fileName.lowercase(Locale.ROOT) }
            .thenBy { it.uriString }

    fun selectStableCandidates(tracks: List<PlaybackTrack>, maxCandidates: Int = DEFAULT_MAX_CANDIDATES): List<PlaybackTrack> {
        if (tracks.isEmpty() || maxCandidates <= 0) return emptyList()
        val sorted = tracks.sortedWith(STABLE_COMPARATOR)
        val result = ArrayList<PlaybackTrack>(minOf(maxCandidates, sorted.size))
        val seenKeys = HashSet<String>(sorted.size)
        for (track in sorted) {
            val dedupKey = if (AlbumArtworkHelper.isDistinctAlbumName(track.album)) {
                val cleanAlbum = track.album.trim().lowercase(Locale.ROOT)
                val cleanArtist = track.artist.trim().lowercase(Locale.ROOT)
                "album:$cleanArtist|$cleanAlbum"
            } else {
                "uri:${track.uriString}"
            }
            if (seenKeys.add(dedupKey)) {
                result.add(track)
                if (result.size >= maxCandidates) break
            }
        }
        return result
    }
}
