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
 * Resolved queue track list, starting index, and effective context for cold-start session restoration.
 */
data class RestoredQueueSelection(val tracks: List<PlaybackTrack>, val startIndex: Int, val context: QueueContext)

/**
 * Pure-Kotlin queue context & session restoration resolver (zero `android.*` imports):
 * 1. Opening `QueueScreen` is strictly passive (read-only) and never replaces the active queue or
 *    interrupts playback.
 * 2. Tapping a searched track in `All Songs` resolves its exact index inside the full sorted
 *    `allSongs` list via [resolveFullListPlaybackSelection] so Next/Previous/Shuffle operate across
 *    the entire library.
 * 3. App relaunch restores ONLY the exact saved Album, Artist, Folder, Playlist, Recent, or
 *    Favorites track list (in its proper sorted order), falling back cleanly to `All Songs` only
 *    if the saved context was deleted or no longer holds the last active track.
 */
object QueueContextResolver {

    /**
     * Resolves the playback track list and starting index when tapping [tappedTrack] from a
     * filtered view over [fullTrackList] (e.g., searching inside the `All Songs` tab).
     *
     * Matches by stable `uriString` inside [fullTrackList] so Next, Previous, and Shuffle operate
     * across the full sorted list. Falls back safely to [fallbackTracks] at [fallbackIndex] only
     * when [tappedTrack] is absent from [fullTrackList].
     */
    fun resolveFullListPlaybackSelection(
        fullTrackList: List<PlaybackTrack>,
        tappedTrack: PlaybackTrack,
        fallbackTracks: List<PlaybackTrack>,
        fallbackIndex: Int,
    ): Pair<List<PlaybackTrack>, Int> {
        val fullIndex = fullTrackList.indexOfFirst { it.uriString == tappedTrack.uriString }
        return if (fullIndex >= 0) {
            fullTrackList to fullIndex
        } else {
            fallbackTracks to fallbackIndex.coerceIn(0, fallbackTracks.lastIndex.coerceAtLeast(0))
        }
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

/**
 * Pure-Kotlin layout snapshot of a single visible queue row for drag-and-drop midpoint math.
 */
data class VisibleQueueItemMetric(val index: Int, val offsetPx: Int, val sizePx: Int)

/**
 * Pure-Kotlin swap decision emitted when a dragged queue row crosses a neighbor's midpoint.
 */
data class DragSwapDecision(val fromIndex: Int, val toIndex: Int, val offsetAdjustmentPx: Float)

/**
 * Pure-Kotlin drag-and-drop swap hysteresis and edge auto-scroll math for `QueueScreen`
 * (zero `android.*` / `androidx.*` imports).
 */
object QueueDragReorderMath {
    private const val SWAP_HYSTERESIS_PX = 2f

    fun computeDragSwap(
        draggedItem: VisibleQueueItemMetric,
        draggedDistanceY: Float,
        visibleItems: List<VisibleQueueItemMetric>,
    ): DragSwapDecision? {
        if (draggedItem.sizePx <= 0 || !draggedDistanceY.isFinite() || draggedDistanceY == 0f) return null
        val draggedTop = draggedItem.offsetPx + draggedDistanceY
        val draggedBottom = draggedTop + draggedItem.sizePx

        return if (draggedDistanceY > 0f) {
            val next = visibleItems.firstOrNull { it.index == draggedItem.index + 1 } ?: return null
            val targetMidY = next.offsetPx + next.sizePx / 2f
            if (draggedBottom > targetMidY + SWAP_HYSTERESIS_PX) {
                DragSwapDecision(
                    fromIndex = draggedItem.index,
                    toIndex = next.index,
                    offsetAdjustmentPx = -(next.offsetPx - draggedItem.offsetPx).toFloat(),
                )
            } else {
                null
            }
        } else {
            val prev = visibleItems.firstOrNull { it.index == draggedItem.index - 1 } ?: return null
            val targetMidY = prev.offsetPx + prev.sizePx / 2f
            if (draggedTop < targetMidY - SWAP_HYSTERESIS_PX) {
                DragSwapDecision(
                    fromIndex = draggedItem.index,
                    toIndex = prev.index,
                    offsetAdjustmentPx = -(prev.offsetPx - draggedItem.offsetPx).toFloat(),
                )
            } else {
                null
            }
        }
    }

    fun computeEdgeAutoScrollPx(
        draggedTop: Float,
        draggedBottom: Float,
        viewportStart: Int,
        viewportEnd: Int,
        edgeZonePx: Float,
        maxSpeedPx: Float,
        canScrollBackward: Boolean,
        canScrollForward: Boolean,
    ): Float {
        val viewportHeight = (viewportEnd - viewportStart).coerceAtLeast(1)
        val effectiveZone = minOf(edgeZonePx, viewportHeight * 0.25f).coerceAtLeast(1f)
        val topOverlap = (viewportStart + effectiveZone) - draggedTop
        val bottomOverlap = draggedBottom - (viewportEnd - effectiveZone)
        return when {
            topOverlap > 0f && canScrollBackward ->
                -((topOverlap / effectiveZone).coerceIn(0f, 1f) * maxSpeedPx)
            bottomOverlap > 0f && canScrollForward ->
                ((bottomOverlap / effectiveZone).coerceIn(0f, 1f) * maxSpeedPx)
            else -> 0f
        }
    }
}
