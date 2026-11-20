package com.cangkir.bitperfectplayer.library

import com.cangkir.bitperfectplayer.audio.PlaybackTrack
import com.cangkir.bitperfectplayer.data.FavoriteTrackRecord
import com.cangkir.bitperfectplayer.data.TrackHistoryRecord

/**
 * Grouped library projection used by Folders, Artists, and Albums tabs.
 */
data class LibraryGroup(
    val name: String,
    val subtitle: String,
    val tracks: List<PlaybackTrack>,
)

/**
 * Complete reactive library snapshot consumed by the UI.
 */
data class LibrarySnapshot(
    val allSongs: List<PlaybackTrack> = emptyList(),
    val folders: List<LibraryGroup> = emptyList(),
    val artists: List<LibraryGroup> = emptyList(),
    val albums: List<LibraryGroup> = emptyList(),
    val recentlyPlayed: List<PlaybackTrack> = emptyList(),
    val favorites: List<PlaybackTrack> = emptyList(),
    val favoriteFingerprints: Set<String> = emptySet(),
    val favoriteUris: Set<String> = emptySet(),
    val persistedSafTreeUris: Set<String> = emptySet(),
    val isScanning: Boolean = false,
    val scanWarning: String? = null,
)

/**
 * Pure-Kotlin library deduplication and grouped projection builder (zero `android.*` imports).
 */
object LibraryProjector {

    /**
     * Merges MediaStore and SAF tracks while deduplicating identical URIs or identical
     * `(fileName, fileSizeBytes)` entries (preferring entries with richer metadata / SAF tree URI).
     */
    fun mergeAndDeduplicate(
        mediaStoreTracks: List<PlaybackTrack>,
        safTracks: List<PlaybackTrack>,
    ): List<PlaybackTrack> {
        val byUri = LinkedHashMap<String, PlaybackTrack>()
        val seenFileKeyToUri = HashMap<String, String>()

        fun addTrack(track: PlaybackTrack, preferReplacement: Boolean) {
            val existingByUri = byUri[track.uriString]
            if (existingByUri != null) {
                if (preferReplacement) {
                    byUri[track.uriString] = mergeTrackFields(existingByUri, track)
                }
                return
            }
            val fileKey = if (track.fileSizeBytes > 0L) {
                "${track.fileName.lowercase()}|${track.fileSizeBytes}"
            } else {
                null
            }
            if (fileKey != null) {
                val duplicateUri = seenFileKeyToUri[fileKey]
                if (duplicateUri != null) {
                    val existing = byUri[duplicateUri]
                    if (existing != null && preferReplacement) {
                        byUri[duplicateUri] = mergeTrackFields(existing, track)
                    }
                    return
                }
                seenFileKeyToUri[fileKey] = track.uriString
            }
            byUri[track.uriString] = track
        }

        // Add MediaStore first, then enrich/append SAF tracks.
        mediaStoreTracks.forEach { addTrack(it, preferReplacement = false) }
        safTracks.forEach { addTrack(it, preferReplacement = true) }

        return byUri.values.sortedWith(
            compareBy<PlaybackTrack> { it.title.lowercase() }.thenBy { it.fileName.lowercase() },
        )
    }

    fun buildSnapshot(
        allTracks: List<PlaybackTrack>,
        historyRecords: List<TrackHistoryRecord>,
        favoriteRecords: List<FavoriteTrackRecord>,
        optimisticFavorites: Map<String, Boolean> = emptyMap(),
        persistedSafTreeUris: Set<String> = emptySet(),
        isScanning: Boolean = false,
        scanWarning: String? = null,
    ): LibrarySnapshot {
        val sortedSongs = allTracks.sortedWith(
            compareBy<PlaybackTrack> { it.title.lowercase() }.thenBy { it.fileName.lowercase() },
        )

        val folders = sortedSongs
            .groupBy { it.folderName.ifBlank { "Internal Storage" } }
            .entries
            .sortedBy { it.key.lowercase() }
            .map { (name, items) ->
                LibraryGroup(
                    name = name,
                    subtitle = "${items.size} ${if (items.size == 1) "track" else "tracks"}",
                    tracks = items,
                )
            }

        val artists = sortedSongs
            .groupBy { it.artist.ifBlank { "Unknown Artist" } }
            .entries
            .sortedBy { it.key.lowercase() }
            .map { (name, items) ->
                val albumCount = items.map { it.album }.distinct().size
                LibraryGroup(
                    name = name,
                    subtitle = "${items.size} ${if (items.size == 1) "song" else "songs"} · $albumCount ${if (albumCount == 1) "album" else "albums"}",
                    tracks = items,
                )
            }

        val albums = sortedSongs
            .groupBy { it.album.ifBlank { "Unknown Album" } }
            .entries
            .sortedBy { it.key.lowercase() }
            .map { (name, items) ->
                val primaryArtist = items.firstOrNull()?.artist ?: "Unknown Artist"
                LibraryGroup(
                    name = name,
                    subtitle = "$primaryArtist · ${items.size} ${if (items.size == 1) "track" else "tracks"}",
                    tracks = items,
                )
            }

        val trackByUri = sortedSongs.associateBy { it.uriString }
        val trackByFp = sortedSongs
            .filter { !it.contentFingerprint.isNullOrBlank() }
            .associateBy { it.contentFingerprint!!.lowercase() }
        val trackByFileName = sortedSongs.associateBy { it.fileName.lowercase() }

        // Resolve Recently Played entries against currently accessible library tracks.
        val recentlyPlayed = historyRecords
            .sortedByDescending { it.playedAtEpochMs }
            .mapNotNull { rec ->
                val fp = rec.contentFingerprint.lowercase()
                val match = trackByFp[fp]
                    ?: trackByUri[rec.trackUri]
                    ?: trackByFileName[rec.fileName.lowercase()]
                match?.copy(contentFingerprint = match.contentFingerprint ?: fp)
            }
            .distinctBy { it.uriString }

        val resolvedFavs = FavoriteStateReducer.resolve(
            allTracks = sortedSongs,
            persistedFavorites = favoriteRecords,
            optimisticOverrides = optimisticFavorites,
        )

        return LibrarySnapshot(
            allSongs = sortedSongs,
            folders = folders,
            artists = artists,
            albums = albums,
            recentlyPlayed = recentlyPlayed,
            favorites = resolvedFavs.favoriteTracks,
            favoriteFingerprints = resolvedFavs.favoriteFingerprints,
            favoriteUris = resolvedFavs.favoriteUris,
            persistedSafTreeUris = persistedSafTreeUris,
            isScanning = isScanning,
            scanWarning = scanWarning,
        )
    }

    private fun mergeTrackFields(base: PlaybackTrack, incoming: PlaybackTrack): PlaybackTrack =
        base.copy(
            parentTreeUriString = incoming.parentTreeUriString ?: base.parentTreeUriString,
            sampleRateHz = incoming.sampleRateHz.takeIf { it > 0 } ?: base.sampleRateHz,
            bitDepth = incoming.bitDepth ?: base.bitDepth,
            durationMs = incoming.durationMs.takeIf { it > 0L } ?: base.durationMs,
            dateModifiedSeconds = maxOf(base.dateModifiedSeconds, incoming.dateModifiedSeconds),
            contentFingerprint = incoming.contentFingerprint ?: base.contentFingerprint,
        )
}
