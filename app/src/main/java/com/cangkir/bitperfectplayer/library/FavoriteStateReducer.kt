package com.cangkir.bitperfectplayer.library

import com.cangkir.bitperfectplayer.audio.PlaybackTrack
import com.cangkir.bitperfectplayer.data.FavoriteTrackRecord

/**
 * Pure-Kotlin optimistic + persisted favorite state reducer (zero `android.*` imports).
 *
 * Provides a single reactive source of truth for heart-icon state across `LibraryScreen`,
 * `NowPlayingScreen`, and the `Favorites` tab:
 * - Tapping a heart applies an immediate optimistic override keyed by `track.uriString`, even when
 *   a file's SHA-256 content fingerprint has not been lazily computed yet.
 * - Persisted Room `FavoriteTrackRecord` entries match by SHA-256 fingerprint (surviving renames/moves),
 *   exact `trackUri`, or fallback filename.
 */
object FavoriteStateReducer {

    data class ResolvedFavorites(
        val favoriteTracks: List<PlaybackTrack>,
        val favoriteUris: Set<String>,
        val favoriteFingerprints: Set<String>,
    )

    fun resolve(
        allTracks: List<PlaybackTrack>,
        persistedFavorites: List<FavoriteTrackRecord>,
        optimisticOverrides: Map<String, Boolean>,
    ): ResolvedFavorites {
        val trackByUri = allTracks.associateBy { it.uriString }
        val trackByFp = allTracks
            .filter { !it.contentFingerprint.isNullOrBlank() }
            .associateBy { it.contentFingerprint!!.lowercase() }
        val trackByFileName = allTracks.associateBy { it.fileName.lowercase() }

        val persistedFpSet = persistedFavorites.map { it.contentFingerprint.lowercase() }.toMutableSet()
        val effectiveUris = LinkedHashSet<String>()
        val orderedFavorites = LinkedHashMap<String, PlaybackTrack>()

        // 1. Add all persisted favorites that are not optimistically unfavorited.
        for (fav in persistedFavorites.sortedByDescending { it.addedAtEpochMs }) {
            val fp = fav.contentFingerprint.lowercase()
            val match = trackByFp[fp]
                ?: trackByUri[fav.trackUri]
                ?: trackByFileName[fav.fileName.lowercase()]

            val uriKey = match?.uriString ?: fav.trackUri
            val override = optimisticOverrides[uriKey] ?: optimisticOverrides[fav.trackUri]
            if (override == false) {
                persistedFpSet.remove(fp)
                continue
            }

            if (uriKey.isNotBlank()) {
                effectiveUris.add(uriKey)
            }
            if (fav.trackUri.isNotBlank()) {
                effectiveUris.add(fav.trackUri)
            }
            if (match != null) {
                orderedFavorites[match.uriString] = match.copy(
                    contentFingerprint = match.contentFingerprint ?: fp,
                )
            }
        }

        // 2. Apply optimistic additions immediately (even before SHA-256 or Room write completes).
        for ((uri, desired) in optimisticOverrides) {
            if (desired) {
                effectiveUris.add(uri)
                val track = trackByUri[uri]
                if (track != null) {
                    track.contentFingerprint?.lowercase()?.let { persistedFpSet.add(it) }
                    if (uri !in orderedFavorites) {
                        // Place newly favorited track at the top of Favorites immediately.
                        val rebuilt = LinkedHashMap<String, PlaybackTrack>()
                        rebuilt[uri] = track
                        rebuilt.putAll(orderedFavorites)
                        orderedFavorites.clear()
                        orderedFavorites.putAll(rebuilt)
                    }
                }
            } else {
                effectiveUris.remove(uri)
                orderedFavorites.remove(uri)?.contentFingerprint?.lowercase()?.let {
                    persistedFpSet.remove(it)
                }
            }
        }

        return ResolvedFavorites(
            favoriteTracks = orderedFavorites.values.toList(),
            favoriteUris = effectiveUris,
            favoriteFingerprints = persistedFpSet,
        )
    }

    fun isFavorited(track: PlaybackTrack, favoriteUris: Set<String>, favoriteFingerprints: Set<String>): Boolean {
        if (track.uriString in favoriteUris) return true
        val fp = track.contentFingerprint?.lowercase()
        return fp != null && fp in favoriteFingerprints
    }
}
