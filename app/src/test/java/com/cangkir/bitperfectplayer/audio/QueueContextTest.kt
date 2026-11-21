package com.cangkir.bitperfectplayer.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueContextTest {

    private fun track(id: Int, title: String, album: String = "Album A"): PlaybackTrack = PlaybackTrack(
        uriString = "content://media/$id",
        title = title,
        artist = "Artist",
        album = album,
        folderName = "Folder",
        fileName = "$id.flac",
        trackNumber = id,
    )

    @Test
    fun queueContextFormattingAndEquality_matchExpectedLabels() {
        val albumCtx = QueueContext.album("21st Century Breakdown")
        assertEquals("Album · 21st Century Breakdown · 18 tracks", albumCtx.formatQueueSubtitle(18))
        assertEquals("Album · 21st Century Breakdown", albumCtx.formatCompactLabel())

        assertTrue(albumCtx.matches(QueueContext.album("21st century breakdown")))
        assertFalse(albumCtx.matches(QueueContext.folder("21st Century Breakdown")))
        assertEquals("All Songs · 268 tracks", QueueContext.allSongs().formatQueueSubtitle(268))
        assertEquals("Playlist · Late Night · 1 track", QueueContext.playlist(7L, "Late Night").formatQueueSubtitle(1))
    }

    @Test
    fun resolveFullListPlaybackSelection_locatesSearchedTrackInFullSortedListAndFallsBackSafely() {
        val t1 = track(1, "Alpha")
        val t2 = track(2, "Beta")
        val t3 = track(3, "Peacemaker")
        val t4 = track(4, "Zulu")
        val fullAllSongs = listOf(t1, t2, t3, t4)
        val filteredSingle = listOf(t3)

        // Searching "Peacemaker" in All Songs returns a 1-item filtered list, but playback loads fullAllSongs @ index 2
        val (resolvedTracks, resolvedIndex) = QueueContextResolver.resolveFullListPlaybackSelection(
            fullTrackList = fullAllSongs,
            tappedTrack = t3,
            fallbackTracks = filteredSingle,
            fallbackIndex = 0,
        )
        assertEquals(fullAllSongs, resolvedTracks)
        assertEquals(2, resolvedIndex)

        // Safe fallback when tapped track is unexpectedly absent from fullTrackList
        val orphan = track(99, "Orphan")
        val (fallbackTracks, fallbackIdx) = QueueContextResolver.resolveFullListPlaybackSelection(
            fullTrackList = fullAllSongs,
            tappedTrack = orphan,
            fallbackTracks = listOf(orphan),
            fallbackIndex = 0,
        )
        assertEquals(listOf(orphan), fallbackTracks)
        assertEquals(0, fallbackIdx)
    }

    @Test
    fun queueDragReorderMath_swapsAcrossNeighborMidpointsWithHysteresisAndComputesEdgeScroll() {
        val visible = listOf(
            VisibleQueueItemMetric(index = 0, offsetPx = 0, sizePx = 100),
            VisibleQueueItemMetric(index = 1, offsetPx = 100, sizePx = 100),
            VisibleQueueItemMetric(index = 2, offsetPx = 200, sizePx = 100),
        )

        // Dragging row 0 down by 40 px does not yet cross row 1 midpoint (150 px)
        assertNull(QueueDragReorderMath.computeDragSwap(visible[0], draggedDistanceY = 40f, visibleItems = visible))

        // Dragging row 0 down by 60 px (bottom = 160 > 152) swaps 0 -> 1 with exact -100 px offset adjustment
        val downSwap = QueueDragReorderMath.computeDragSwap(visible[0], draggedDistanceY = 60f, visibleItems = visible)
        assertNotNull(downSwap)
        assertEquals(0, downSwap!!.fromIndex)
        assertEquals(1, downSwap.toIndex)
        assertEquals(-100f, downSwap.offsetAdjustmentPx, 0.001f)

        // After applying -100 px adjustment (remaining distance = -40 px at index 1), row does NOT bounce back up
        assertNull(QueueDragReorderMath.computeDragSwap(visible[1], draggedDistanceY = -40f, visibleItems = visible))

        // Dragging row 1 up by -60 px (top = 40 < 48) swaps 1 -> 0 with exact +100 px offset adjustment
        val upSwap = QueueDragReorderMath.computeDragSwap(visible[1], draggedDistanceY = -60f, visibleItems = visible)
        assertNotNull(upSwap)
        assertEquals(1, upSwap!!.fromIndex)
        assertEquals(0, upSwap.toIndex)
        assertEquals(100f, upSwap.offsetAdjustmentPx, 0.001f)

        // Edge auto-scroll triggers gently near viewport edges only when list can scroll in that direction
        val scrollDown = QueueDragReorderMath.computeEdgeAutoScrollPx(
            draggedTop = 520f,
            draggedBottom = 600f,
            viewportStart = 0,
            viewportEnd = 600,
            edgeZonePx = 80f,
            maxSpeedPx = 12f,
            canScrollBackward = true,
            canScrollForward = true,
        )
        assertTrue(scrollDown > 0f && scrollDown <= 12f)

        val scrollUpBlocked = QueueDragReorderMath.computeEdgeAutoScrollPx(
            draggedTop = 0f,
            draggedBottom = 80f,
            viewportStart = 0,
            viewportEnd = 600,
            edgeZonePx = 80f,
            maxSpeedPx = 12f,
            canScrollBackward = false,
            canScrollForward = true,
        )
        assertEquals(0f, scrollUpBlocked, 0.001f)
    }

    @Test
    fun resolveSessionRestore_restoresExactScopedAlbumOrPlaylistInsteadOfAllSongs() {
        val a1 = track(1, "Intro", album = "21st Century Breakdown")
        val a2 = track(2, "Track Two", album = "21st Century Breakdown")
        val a3 = track(3, "Track Three", album = "21st Century Breakdown")
        val other1 = track(4, "Other Song 1", album = "Other LP")
        val other2 = track(5, "Other Song 2", album = "Other LP")

        val allSongs = listOf(other1, a1, other2, a3, a2)
        val albumOrdered = listOf(a1, a2, a3)
        val playlistOrdered = listOf(other2, a2)

        // 1. Restore saved Album context -> ONLY the 3 album tracks in track-number order!
        val restoredAlbum = QueueContextResolver.resolveSessionRestore(
            lastTrackUri = a2.uriString,
            savedContext = QueueContext.album("21st Century Breakdown"),
            allSongs = allSongs,
            albumsByName = mapOf("21st Century Breakdown" to albumOrdered),
            artistsByName = emptyMap(),
            foldersByName = emptyMap(),
            playlistsById = mapOf("42" to playlistOrdered),
            recentlyPlayed = emptyList(),
            favorites = emptyList(),
            mostPlayed = emptyList(),
        )
        assertNotNull(restoredAlbum)
        assertEquals(3, restoredAlbum!!.tracks.size)
        assertEquals(listOf("Intro", "Track Two", "Track Three"), restoredAlbum.tracks.map { it.title })
        assertEquals(1, restoredAlbum.startIndex)
        assertEquals(QueueSourceType.ALBUM, restoredAlbum.context.sourceType)

        // 2. Restore saved Playlist context -> ONLY the 2 playlist tracks!
        val restoredPlaylist = QueueContextResolver.resolveSessionRestore(
            lastTrackUri = a2.uriString,
            savedContext = QueueContext.playlist(42L, "Focus"),
            allSongs = allSongs,
            albumsByName = mapOf("21st Century Breakdown" to albumOrdered),
            artistsByName = emptyMap(),
            foldersByName = emptyMap(),
            playlistsById = mapOf("42" to playlistOrdered),
            recentlyPlayed = emptyList(),
            favorites = emptyList(),
            mostPlayed = emptyList(),
        )
        assertNotNull(restoredPlaylist)
        assertEquals(2, restoredPlaylist!!.tracks.size)
        assertEquals(1, restoredPlaylist.startIndex)
        assertEquals(QueueSourceType.PLAYLIST, restoredPlaylist.context.sourceType)

        // 3. Deleted playlist gracefully falls back to All Songs
        val fallback = QueueContextResolver.resolveSessionRestore(
            lastTrackUri = a2.uriString,
            savedContext = QueueContext.playlist(999L, "Deleted"),
            allSongs = allSongs,
            albumsByName = emptyMap(),
            artistsByName = emptyMap(),
            foldersByName = emptyMap(),
            playlistsById = emptyMap(),
            recentlyPlayed = emptyList(),
            favorites = emptyList(),
            mostPlayed = emptyList(),
        )
        assertNotNull(fallback)
        assertEquals(allSongs.size, fallback!!.tracks.size)
        assertEquals(QueueSourceType.ALL_SONGS, fallback.context.sourceType)
        assertEquals(4, fallback.startIndex)

        // 4. Track deleted from entire library returns null cleanly
        val missing = QueueContextResolver.resolveSessionRestore(
            lastTrackUri = "content://media/9999",
            savedContext = QueueContext.allSongs(),
            allSongs = allSongs,
            albumsByName = emptyMap(),
            artistsByName = emptyMap(),
            foldersByName = emptyMap(),
            playlistsById = emptyMap(),
            recentlyPlayed = emptyList(),
            favorites = emptyList(),
            mostPlayed = emptyList(),
        )
        assertNull(missing)
    }

    @Test
    fun playbackQueueManager_enqueueAllAndEnqueueAllNext_preserveExactGroupOrder() {
        val manager = PlaybackQueueManager<String>()
        manager.setQueue(listOf("A", "B", "C"), startIndex = 0)

        // Insert group [X, Y] right after active track "A" -> [A, X, Y, B, C]
        val afterNext = manager.enqueueAllNext(listOf("X", "Y"))
        assertEquals(listOf("A", "X", "Y", "B", "C"), afterNext.items)
        assertEquals(0, afterNext.currentIndex)

        // Append group [Z1, Z2] to end -> [A, X, Y, B, C, Z1, Z2]
        val afterAppend = manager.enqueueAll(listOf("Z1", "Z2"))
        assertEquals(listOf("A", "X", "Y", "B", "C", "Z1", "Z2"), afterAppend.items)
        assertEquals(0, afterAppend.currentIndex)
    }
}
