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
    val sortPreferences: LibrarySortPreferences = LibrarySortPreferences(),
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
        sortPreferences: LibrarySortPreferences = LibrarySortPreferences(),
        playCountsByKey: Map<String, Int> = emptyMap(),
        isScanning: Boolean = false,
        scanWarning: String? = null,
    ): LibrarySnapshot {
        val sortedSongs = LibrarySortPolicy.sortTracks(
            tracks = allTracks,
            spec = sortPreferences.allSongs,
            playCountsByKey = playCountsByKey,
        )

        val foldersRaw = allTracks
            .groupBy { it.folderName.ifBlank { "Internal Storage" } }
            .entries
            .map { (name, items) ->
                val ordered = LibrarySortPolicy.sortTracks(
                    tracks = items,
                    spec = sortPreferences.folderDetail,
                    playCountsByKey = playCountsByKey,
                )
                LibraryGroup(
                    name = name,
                    subtitle = "${ordered.size} ${if (ordered.size == 1) "track" else "tracks"}",
                    tracks = ordered,
                )
            }
        val folders = LibrarySortPolicy.sortGroups(foldersRaw, sortPreferences.foldersList)

        val artistsRaw = allTracks
            .groupBy { it.artist.ifBlank { "Unknown Artist" } }
            .entries
            .map { (name, items) ->
                val ordered = LibrarySortPolicy.sortTracks(
                    tracks = items,
                    spec = sortPreferences.artistDetail,
                    playCountsByKey = playCountsByKey,
                )
                val albumCount = ordered.map { it.album }.distinct().size
                LibraryGroup(
                    name = name,
                    subtitle = "${ordered.size} ${if (ordered.size == 1) "song" else "songs"} · $albumCount ${if (albumCount == 1) "album" else "albums"}",
                    tracks = ordered,
                )
            }
        val artists = LibrarySortPolicy.sortGroups(artistsRaw, sortPreferences.artistsList)

        val albumsRaw = allTracks
            .groupBy { it.album.ifBlank { "Unknown Album" } }
            .entries
            .map { (name, items) ->
                val ordered = LibrarySortPolicy.sortTracks(
                    tracks = items,
                    spec = sortPreferences.albumDetail,
                    playCountsByKey = playCountsByKey,
                )
                val primaryArtist = ordered.firstOrNull()?.artist ?: "Unknown Artist"
                LibraryGroup(
                    name = name,
                    subtitle = "$primaryArtist · ${ordered.size} ${if (ordered.size == 1) "track" else "tracks"}",
                    tracks = ordered,
                )
            }
        val albums = LibrarySortPolicy.sortGroups(albumsRaw, sortPreferences.albumsList)

        val trackByUri = sortedSongs.associateBy { it.uriString }
        val trackByFp = sortedSongs
            .filter { !it.contentFingerprint.isNullOrBlank() }
            .associateBy { it.contentFingerprint!!.lowercase() }
        val trackByFileName = sortedSongs.associateBy { it.fileName.lowercase() }

        // Resolve Recently Played entries against currently accessible library tracks.
        val chronologicalRecent = historyRecords
            .sortedByDescending { it.playedAtEpochMs }
            .mapNotNull { rec ->
                val fp = rec.contentFingerprint.lowercase()
                val match = trackByFp[fp]
                    ?: trackByUri[rec.trackUri]
                    ?: trackByFileName[rec.fileName.lowercase()]
                match?.copy(contentFingerprint = match.contentFingerprint ?: fp)
            }
            .distinctBy { it.uriString }
        val recentlyPlayed = LibrarySortPolicy.sortTracks(
            tracks = chronologicalRecent,
            spec = sortPreferences.recent,
            playCountsByKey = playCountsByKey,
        )

        val resolvedFavs = FavoriteStateReducer.resolve(
            allTracks = sortedSongs,
            persistedFavorites = favoriteRecords,
            optimisticOverrides = optimisticFavorites,
        )
        val sortedFavorites = LibrarySortPolicy.sortTracks(
            tracks = resolvedFavs.favoriteTracks,
            spec = sortPreferences.favorites,
            playCountsByKey = playCountsByKey,
        )

        return LibrarySnapshot(
            allSongs = sortedSongs,
            folders = folders,
            artists = artists,
            albums = albums,
            recentlyPlayed = recentlyPlayed,
            favorites = sortedFavorites,
            favoriteFingerprints = resolvedFavs.favoriteFingerprints,
            favoriteUris = resolvedFavs.favoriteUris,
            persistedSafTreeUris = persistedSafTreeUris,
            sortPreferences = sortPreferences,
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
            discNumber = incoming.discNumber.takeIf { it > 0 } ?: base.discNumber,
            trackNumber = incoming.trackNumber.takeIf { it > 0 } ?: base.trackNumber,
        )
}
