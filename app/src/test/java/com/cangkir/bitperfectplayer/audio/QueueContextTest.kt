package com.cangkir.bitperfectplayer.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueContextTest {

    private fun track(id: Int, title: String, album: String = "Album A"): PlaybackTrack =
        PlaybackTrack(
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
    fun shouldSwitchToBrowsingContext_switchesForDifferentScopedContextAndPreservesSameOrNull() {
        val albumATracks = listOf(track(1, "A1", album = "Album A"), track(2, "A2", album = "Album A"))
        val albumBTracks = listOf(track(3, "B1", album = "Album B"), track(4, "B2", album = "Album B"))

        // Opening Queue while viewing Album B while Album A is active -> switch immediately
        assertTrue(
            QueueContextResolver.shouldSwitchToBrowsingContext(
                activeContext = QueueContext.album("Album A"),
                isActiveQueueEmpty = false,
                browsingContext = BrowsingQueueContext(QueueContext.album("Album B"), albumBTracks),
            ),
        )

        // Opening Queue while viewing Album A while All Songs is active -> switch immediately
        assertTrue(
            QueueContextResolver.shouldSwitchToBrowsingContext(
                activeContext = QueueContext.allSongs(),
                isActiveQueueEmpty = false,
                browsingContext = BrowsingQueueContext(QueueContext.album("Album A"), albumATracks),
            ),
        )

        // Opening Queue while viewing the SAME active Album (case-insensitive) -> preserve active playback
        assertFalse(
            QueueContextResolver.shouldSwitchToBrowsingContext(
                activeContext = QueueContext.album("Album A"),
                isActiveQueueEmpty = false,
                browsingContext = BrowsingQueueContext(QueueContext.album("album a"), albumATracks),
            ),
        )

        // Opening Queue from NowPlaying or All Songs (null browsingContext) -> preserve active playback
        assertFalse(
            QueueContextResolver.shouldSwitchToBrowsingContext(
                activeContext = QueueContext.album("Album A"),
                isActiveQueueEmpty = false,
                browsingContext = null,
            ),
        )

        // Never auto-switch for ALL_SONGS or empty browsing track lists
        assertFalse(
            QueueContextResolver.shouldSwitchToBrowsingContext(
                activeContext = QueueContext.album("Album A"),
                isActiveQueueEmpty = false,
                browsingContext = BrowsingQueueContext(QueueContext.allSongs(), albumBTracks),
            ),
        )
        assertFalse(
            QueueContextResolver.shouldSwitchToBrowsingContext(
                activeContext = QueueContext.album("Album A"),
                isActiveQueueEmpty = false,
                browsingContext = BrowsingQueueContext(QueueContext.album("Album B"), emptyList()),
            ),
        )

        // If active queue was emptied, opening Queue from the same album repopulates it paused
        assertTrue(
            QueueContextResolver.shouldSwitchToBrowsingContext(
                activeContext = QueueContext.album("Album A"),
                isActiveQueueEmpty = true,
                browsingContext = BrowsingQueueContext(QueueContext.album("Album A"), albumATracks),
            ),
        )
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
