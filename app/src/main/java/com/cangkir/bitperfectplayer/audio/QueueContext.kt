package com.cangkir.bitperfectplayer.audio

/**
 * Origin scope of the active playback queue so QueueScreen, NowPlayingScreen, and session
 * restoration remain strictly context-aware instead of reverting to all library songs.
 */
enum class QueueSourceType(val displayPrefix: String) {
    ALL_SONGS("All Songs"),
    ALBUM("Album"),
    ARTIST("Artist"),
    FOLDER("Folder"),
    PLAYLIST("Playlist"),
    RECENT("Recent"),
    FAVORITES("Favorites"),
    CUSTOM("Queue"),
    ;

    companion object {
        fun fromStoredName(raw: String?): QueueSourceType =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: ALL_SONGS
    }
}

/**
 * Pure-Kotlin descriptor identifying which library or playlist view populated the active queue
 * (zero `android.*` imports).
 */
data class QueueContext(
    val sourceType: QueueSourceType = QueueSourceType.ALL_SONGS,
    val sourceKey: String? = null,
    val sourceLabel: String = "All Songs",
) {
    /**
     * Full descriptive subtitle for `QueueScreen`'s TopAppBar
     * (e.g., `"Album · 21st Century Breakdown · 18 tracks"`).
     */
    fun formatQueueSubtitle(trackCount: Int): String {
        val countPart = "$trackCount ${if (trackCount == 1) "track" else "tracks"}"
        return when (sourceType) {
            QueueSourceType.ALL_SONGS -> "All Songs · $countPart"
            QueueSourceType.RECENT -> "Recent · $countPart"
            QueueSourceType.FAVORITES -> "Favorites · $countPart"
            QueueSourceType.ALBUM,
            QueueSourceType.ARTIST,
            QueueSourceType.FOLDER,
            QueueSourceType.PLAYLIST,
            -> {
                val cleanLabel = sourceLabel.trim().ifEmpty { sourceType.displayPrefix }
                "${sourceType.displayPrefix} · $cleanLabel · $countPart"
            }
            QueueSourceType.CUSTOM -> {
                val cleanLabel = sourceLabel.trim().ifEmpty { "Custom Queue" }
                "$cleanLabel · $countPart"
            }
        }
    }

    /**
     * Compact context badge for `NowPlayingScreen`'s TopAppBar subtitle
     * (e.g., `"Album · 21st Century Breakdown"`, `"Playlist · Late Night"`, `"All Songs"`).
     */
    fun formatCompactLabel(): String = when (sourceType) {
        QueueSourceType.ALL_SONGS -> "All Songs"
        QueueSourceType.RECENT -> "Recent"
        QueueSourceType.FAVORITES -> "Favorites"
        QueueSourceType.ALBUM,
        QueueSourceType.ARTIST,
        QueueSourceType.FOLDER,
        QueueSourceType.PLAYLIST,
        -> {
            val cleanLabel = sourceLabel.trim().ifEmpty { sourceType.displayPrefix }
            "${sourceType.displayPrefix} · $cleanLabel"
        }
        QueueSourceType.CUSTOM -> sourceLabel.trim().ifEmpty { "Queue" }
    }

    fun matches(other: QueueContext): Boolean {
        if (sourceType != other.sourceType) return false
        val k1 = (sourceKey ?: sourceLabel).trim().lowercase()
        val k2 = (other.sourceKey ?: other.sourceLabel).trim().lowercase()
        return k1 == k2
    }

    companion object {
        const val MOST_PLAYED_KEY = "most_played"

        fun allSongs(): QueueContext = QueueContext(
            sourceType = QueueSourceType.ALL_SONGS,
            sourceKey = null,
            sourceLabel = "All Songs",
        )

        fun album(albumName: String): QueueContext = QueueContext(
            sourceType = QueueSourceType.ALBUM,
            sourceKey = albumName,
            sourceLabel = albumName,
        )

        fun artist(artistName: String): QueueContext = QueueContext(
            sourceType = QueueSourceType.ARTIST,
            sourceKey = artistName,
            sourceLabel = artistName,
        )

        fun folder(folderName: String): QueueContext = QueueContext(
            sourceType = QueueSourceType.FOLDER,
            sourceKey = folderName,
            sourceLabel = folderName,
        )

        fun playlist(playlistId: Long, playlistName: String): QueueContext = QueueContext(
            sourceType = QueueSourceType.PLAYLIST,
            sourceKey = playlistId.toString(),
            sourceLabel = playlistName,
        )

        fun mostPlayed(): QueueContext = QueueContext(
            sourceType = QueueSourceType.CUSTOM,
            sourceKey = MOST_PLAYED_KEY,
            sourceLabel = "Most Played",
        )

        fun recent(): QueueContext = QueueContext(
            sourceType = QueueSourceType.RECENT,
            sourceKey = null,
            sourceLabel = "Recent",
        )

        fun favorites(): QueueContext = QueueContext(
            sourceType = QueueSourceType.FAVORITES,
            sourceKey = null,
            sourceLabel = "Favorites",
        )

        fun custom(label: String = "Queue", key: String? = null): QueueContext = QueueContext(
            sourceType = QueueSourceType.CUSTOM,
            sourceKey = key,
            sourceLabel = label.ifBlank { "Queue" },
        )
    }
}

/**
 * Snapshot of the Album, Artist, Folder, Playlist, Recent, Favorites, or Most Played view currently
 * open when the user navigates to `QueueScreen`, allowing real-time context-aware queue switching
 * (loaded paused for hearing safety).
 */
data class BrowsingQueueContext(val context: QueueContext, val tracks: List<PlaybackTrack>)

/**
 * Resolved queue track list, starting index, and effective context for cold-start session restoration.
 */
data class RestoredQueueSelection(val tracks: List<PlaybackTrack>, val startIndex: Int, val context: QueueContext)

/**
 * Pure-Kotlin queue context policy & session restoration resolver ensuring:
 * 1. Opening `QueueScreen` from a scoped view (Album, Artist, Folder, Playlist, Recent, Favorites,
 *    or Most Played) immediately aligns the active queue with that view (loaded paused) whenever
 *    it differs from the active queue context, while preserving active playback when opened from
 *    `NowPlayingScreen`, `All Songs`, or the same already-active view.
 * 2. App relaunch restores ONLY the exact saved Album, Artist, Folder, Playlist, Recent, or
 *    Favorites track list (in its proper sorted order), falling back cleanly to `All Songs` only
 *    if the saved context was deleted or no longer holds the last active track.
 */
object QueueContextResolver {

    /**
     * Returns `true` when opening `QueueScreen` from [browsingContext] should immediately replace
     * the active queue (loaded paused, never auto-playing) with [browsingContext]'s ordered tracks.
     */
    fun shouldSwitchToBrowsingContext(
        activeContext: QueueContext,
        isActiveQueueEmpty: Boolean,
        browsingContext: BrowsingQueueContext?,
    ): Boolean {
        if (browsingContext == null || browsingContext.tracks.isEmpty()) return false
        if (browsingContext.context.sourceType == QueueSourceType.ALL_SONGS) return false
        return isActiveQueueEmpty || !activeContext.matches(browsingContext.context)
    }

    fun resolveSessionRestore(
        lastTrackUri: String,
        savedContext: QueueContext?,
        allSongs: List<PlaybackTrack>,
        albumsByName: Map<String, List<PlaybackTrack>>,
        artistsByName: Map<String, List<PlaybackTrack>>,
        foldersByName: Map<String, List<PlaybackTrack>>,
        playlistsById: Map<String, List<PlaybackTrack>>,
        recentlyPlayed: List<PlaybackTrack>,
        favorites: List<PlaybackTrack>,
        mostPlayed: List<PlaybackTrack>,
    ): RestoredQueueSelection? {
        if (lastTrackUri.isBlank() || allSongs.isEmpty()) return null

        if (savedContext != null) {
            val lookupKey = savedContext.sourceKey?.takeIf { it.isNotBlank() } ?: savedContext.sourceLabel
            val candidateTracks: List<PlaybackTrack>? = when (savedContext.sourceType) {
                QueueSourceType.ALL_SONGS -> allSongs
                QueueSourceType.ALBUM -> albumsByName[lookupKey]
                QueueSourceType.ARTIST -> artistsByName[lookupKey]
                QueueSourceType.FOLDER -> foldersByName[lookupKey]
                QueueSourceType.PLAYLIST -> playlistsById[lookupKey]
                QueueSourceType.RECENT -> recentlyPlayed
                QueueSourceType.FAVORITES -> favorites
                QueueSourceType.CUSTOM -> {
                    if (savedContext.sourceKey == QueueContext.MOST_PLAYED_KEY) {
                        mostPlayed
                    } else {
                        null
                    }
                }
            }

            if (!candidateTracks.isNullOrEmpty()) {
                val idx = candidateTracks.indexOfFirst { it.uriString == lastTrackUri }
                if (idx >= 0) {
                    return RestoredQueueSelection(
                        tracks = candidateTracks,
                        startIndex = idx,
                        context = savedContext,
                    )
                }
            }
        }

        // Graceful fallback to All Songs if the original context was deleted or modified
        val fallbackIndex = allSongs.indexOfFirst { it.uriString == lastTrackUri }
        if (fallbackIndex < 0) return null
        return RestoredQueueSelection(
            tracks = allSongs,
            startIndex = fallbackIndex,
            context = QueueContext.allSongs(),
        )
    }
}
