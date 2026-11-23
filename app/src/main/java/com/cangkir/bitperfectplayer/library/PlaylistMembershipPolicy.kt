package com.cangkir.bitperfectplayer.library

import com.cangkir.bitperfectplayer.audio.PlaybackTrack

/**
 * Pure-Kotlin duplicate-membership detector for user playlists (zero `android.*` / `androidx.*`
 * imports), used by both `AddToPlaylistDialog` UI badges and `PlaylistRepository` inserts.
 */
object PlaylistMembershipPolicy {

    /**
     * Returns `true` when [track] is already present inside [playlist], matching either a
     * non-blank normalized SHA-256 `contentFingerprint` or the exact trimmed `uriString`.
     */
    fun isTrackInPlaylist(track: PlaybackTrack, playlist: ResolvedPlaylist): Boolean = isTrackDuplicateOfPersisted(
        trackFingerprint = track.contentFingerprint,
        trackUri = track.uriString,
        persistedFingerprints = playlist.entries.asSequence().map { it.track.contentFingerprint }.asIterable(),
        persistedUris = playlist.entries.asSequence().map { it.track.uriString }.asIterable(),
    )

    /**
     * Checks whether a candidate track (`trackFingerprint`, `trackUri`) already matches any existing
     * persisted row via non-blank lowercase fingerprint or exact trimmed URI. Intentionally does
     * not match on bare `fileName` so distinct untagged files in different folders (`01.wav`) are
     * never falsely rejected as duplicates.
     */
    fun isTrackDuplicateOfPersisted(
        trackFingerprint: String?,
        trackUri: String,
        persistedFingerprints: Iterable<String?>,
        persistedUris: Iterable<String>,
    ): Boolean {
        val normFp = trackFingerprint?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val normUri = trackUri.trim()

        if (normFp != null) {
            for (existingFp in persistedFingerprints) {
                val cleanExisting = existingFp?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
                if (cleanExisting == normFp) return true
            }
        }
        if (normUri.isNotEmpty()) {
            for (existingUri in persistedUris) {
                if (existingUri.trim() == normUri) return true
            }
        }
        return false
    }
}
