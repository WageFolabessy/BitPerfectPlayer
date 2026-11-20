package com.cangkir.bitperfectplayer.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumArtworkHelperTest {

    @Test
    fun selectBestSiblingCoverName_matchesPriorityAndCaseInsensitively() {
        assertEquals(
            "COVER.JPG",
            AlbumArtworkHelper.selectBestSiblingCoverName(
                listOf("01.flac", "folder.jpg", "COVER.JPG", "album.jpg"),
            ),
        )
        assertEquals(
            "Cover.Png",
            AlbumArtworkHelper.selectBestSiblingCoverName(
                listOf("notes.txt", "album.jpg", "Cover.Png", "front.jpg"),
            ),
        )
        assertEquals(
            "Folder.JPG",
            AlbumArtworkHelper.selectBestSiblingCoverName(
                listOf("track.wav", "front.jpg", "Folder.JPG"),
            ),
        )
        assertEquals(
            "front.jpg",
            AlbumArtworkHelper.selectBestSiblingCoverName(
                listOf("album.jpg", "front.jpg"),
            ),
        )
        assertEquals(
            "album.jpg",
            AlbumArtworkHelper.selectBestSiblingCoverName(
                listOf("back.jpg", "album.jpg"),
            ),
        )
        assertNull(
            AlbumArtworkHelper.selectBestSiblingCoverName(
                listOf("01.flac", "booklet.pdf", "back.jpg"),
            ),
        )
    }

    @Test
    fun computeInSampleSize_returnsPowerOfTwoWithoutOverShrinking() {
        assertEquals(16, AlbumArtworkHelper.computeInSampleSize(3000, 3000, 160, 160))
        assertEquals(2, AlbumArtworkHelper.computeInSampleSize(1200, 1200, 600, 600))
        assertEquals(1, AlbumArtworkHelper.computeInSampleSize(120, 120, 160, 160))
        assertEquals(1, AlbumArtworkHelper.computeInSampleSize(0, 500, 160, 160))
    }

    @Test
    fun buildCacheKey_isDeterministicHexAndSensitiveToTrackVersionAndBucket() {
        val thumbA = AlbumArtworkHelper.buildCacheKey("content://media/external/audio/media/42", 1_700_000L, 42_000_000L, 144)
        val thumbB = AlbumArtworkHelper.buildCacheKey("content://media/external/audio/media/42", 1_700_000L, 42_000_000L, 160)
        val hero = AlbumArtworkHelper.buildCacheKey("content://media/external/audio/media/42", 1_700_000L, 42_000_000L, 600)
        val modified = AlbumArtworkHelper.buildCacheKey("content://media/external/audio/media/42", 1_700_001L, 42_000_000L, 160)

        assertEquals(64, thumbA.length)
        assertTrue(thumbA.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(thumbA, thumbB)
        assertNotEquals(thumbA, hero)
        assertNotEquals(thumbA, modified)
    }

    @Test
    fun deriveParentDocumentId_handlesNestedRootAndOpaqueIds() {
        assertEquals(
            "primary:Music/Jazz",
            AlbumArtworkHelper.deriveParentDocumentId("primary:Music/Jazz/01.flac"),
        )
        assertEquals(
            "primary:",
            AlbumArtworkHelper.deriveParentDocumentId("primary:01.flac"),
        )
        assertNull(AlbumArtworkHelper.deriveParentDocumentId("opaque_doc_12345"))
    }
}
