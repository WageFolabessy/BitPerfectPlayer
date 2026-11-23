package com.cangkir.bitperfectplayer.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun buildMemoryCacheKey_deduplicatesValidAlbumsWithoutMainThreadSha256() {
        val track1 = AlbumArtworkHelper.buildMemoryCacheKey(
            trackUri = "content://media/external/audio/media/101",
            artist = "Miles Davis",
            album = "Kind of Blue",
            parentTreeUriString = null,
            folderName = "Jazz",
            dateModifiedSeconds = 1_700_000L,
            fileSizeBytes = 80_000_000L,
            targetSizePx = 160,
        )
        val track2 = AlbumArtworkHelper.buildMemoryCacheKey(
            trackUri = "content://media/external/audio/media/102",
            artist = "  miles davis ",
            album = "KIND OF BLUE",
            parentTreeUriString = null,
            folderName = "Jazz",
            dateModifiedSeconds = 1_700_099L,
            fileSizeBytes = 95_000_000L,
            targetSizePx = 144,
        )
        val heroKey = AlbumArtworkHelper.buildMemoryCacheKey(
            trackUri = "content://media/external/audio/media/101",
            artist = "Miles Davis",
            album = "Kind of Blue",
            parentTreeUriString = null,
            folderName = "Jazz",
            dateModifiedSeconds = 1_700_000L,
            fileSizeBytes = 80_000_000L,
            targetSizePx = 600,
        )

        // Same album + artist + thumbnail bucket -> identical lightweight plain-string key
        assertEquals(track1, track2)
        assertTrue(track1.startsWith("alb|160|"))
        // Distinct size bucket -> distinct key
        assertNotEquals(track1, heroKey)
        assertTrue(heroKey.startsWith("alb|600|"))
    }

    @Test
    fun buildMemoryCacheKey_fallsBackToTrackIdentityWhenAlbumUnknownOrBlank() {
        val unknown1 = AlbumArtworkHelper.buildMemoryCacheKey(
            trackUri = "content://media/external/audio/media/201",
            artist = "Artist A",
            album = "Unknown Album",
            parentTreeUriString = null,
            folderName = "Downloads",
            dateModifiedSeconds = 1_700_000L,
            fileSizeBytes = 42_000_000L,
            targetSizePx = 160,
        )
        val unknown2 = AlbumArtworkHelper.buildMemoryCacheKey(
            trackUri = "content://media/external/audio/media/202",
            artist = "Artist A",
            album = "Unknown Album",
            parentTreeUriString = null,
            folderName = "Downloads",
            dateModifiedSeconds = 1_700_000L,
            fileSizeBytes = 42_000_000L,
            targetSizePx = 160,
        )
        val blankAlbum = AlbumArtworkHelper.buildMemoryCacheKey(
            trackUri = "content://media/external/audio/media/203",
            artist = "Artist A",
            album = "   ",
            parentTreeUriString = null,
            folderName = "Downloads",
            dateModifiedSeconds = 1_700_000L,
            fileSizeBytes = 42_000_000L,
            targetSizePx = 160,
        )

        assertTrue(unknown1.startsWith("trk|160|"))
        assertTrue(blankAlbum.startsWith("trk|160|"))
        assertNotEquals(unknown1, unknown2)
        assertNotEquals(unknown1, blankAlbum)
    }

    @Test
    fun hashKeyForDiskFilename_andLegacyBuildCacheKey_produceDeterministicSha256Hex() {
        val thumbA = AlbumArtworkHelper.buildCacheKey("content://media/external/audio/media/42", 1_700_000L, 42_000_000L, 144)
        val thumbB = AlbumArtworkHelper.buildCacheKey("content://media/external/audio/media/42", 1_700_000L, 42_000_000L, 160)
        val hero = AlbumArtworkHelper.buildCacheKey("content://media/external/audio/media/42", 1_700_000L, 42_000_000L, 600)
        val modified = AlbumArtworkHelper.buildCacheKey("content://media/external/audio/media/42", 1_700_001L, 42_000_000L, 160)

        assertEquals(64, thumbA.length)
        assertTrue(thumbA.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(thumbA, thumbB)
        assertNotEquals(thumbA, hero)
        assertNotEquals(thumbA, modified)

        val memHash = AlbumArtworkHelper.hashKeyForDiskFilename("alb|160|ms|artist|album")
        assertEquals(64, memHash.length)
        assertEquals(memHash, AlbumArtworkHelper.hashKeyForDiskFilename("alb|160|ms|artist|album"))
    }

    @Test
    fun thumbnailExtractionOrderAndDiskPruneThrottling_behaveAsConfigured() {
        // 160px MediaStore on API 29+ prefers fast loadThumbnail first
        assertTrue(
            AlbumArtworkHelper.shouldPreferMediaStoreThumbnailFirst(
                targetSizePx = 160,
                isMediaStoreUri = true,
                supportsApi29LoadThumbnail = true,
            ),
        )
        // 600px Hero artwork prefers embedded full-res tags first
        assertFalse(
            AlbumArtworkHelper.shouldPreferMediaStoreThumbnailFirst(
                targetSizePx = 600,
                isMediaStoreUri = true,
                supportsApi29LoadThumbnail = true,
            ),
        )
        // Non-MediaStore SAF URIs or API < 29 do not prefer MediaStore thumbnail first
        assertFalse(
            AlbumArtworkHelper.shouldPreferMediaStoreThumbnailFirst(
                targetSizePx = 160,
                isMediaStoreUri = false,
                supportsApi29LoadThumbnail = true,
            ),
        )
        assertFalse(
            AlbumArtworkHelper.shouldPreferMediaStoreThumbnailFirst(
                targetSizePx = 160,
                isMediaStoreUri = true,
                supportsApi29LoadThumbnail = false,
            ),
        )

        // Disk prune triggers strictly every 50 writes
        assertFalse(AlbumArtworkHelper.shouldPruneDiskCache(0))
        assertFalse(AlbumArtworkHelper.shouldPruneDiskCache(1))
        assertFalse(AlbumArtworkHelper.shouldPruneDiskCache(49))
        assertTrue(AlbumArtworkHelper.shouldPruneDiskCache(50))
        assertFalse(AlbumArtworkHelper.shouldPruneDiskCache(51))
        assertTrue(AlbumArtworkHelper.shouldPruneDiskCache(100))
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

    @Test
    fun computeProportionalTargetDimensions_preservesAspectRatioForSquareLandscapeAndPortraitWithoutCropping() {
        // Square 1:1 (1000x1000 -> 512x512)
        assertEquals(
            ArtworkDimensions(512, 512),
            AlbumArtworkHelper.computeProportionalTargetDimensions(1000, 1000, maxDimensionPx = 512),
        )

        // Landscape 16:9 (1600x900 -> 512x288, exact 16:9 ratio preserved)
        val landscape = AlbumArtworkHelper.computeProportionalTargetDimensions(1600, 900, maxDimensionPx = 512)
        assertEquals(ArtworkDimensions(512, 288), landscape)
        assertEquals(16.0 / 9.0, landscape.width.toDouble() / landscape.height.toDouble(), 0.001)

        // Portrait 9:16 (900x1600 -> 288x512, exact 9:16 ratio preserved)
        val portrait = AlbumArtworkHelper.computeProportionalTargetDimensions(900, 1600, maxDimensionPx = 512)
        assertEquals(ArtworkDimensions(288, 512), portrait)
        assertEquals(9.0 / 16.0, portrait.width.toDouble() / portrait.height.toDouble(), 0.001)

        // Landscape 4:3 (1200x900 -> 600x450)
        assertEquals(
            ArtworkDimensions(600, 450),
            AlbumArtworkHelper.computeProportionalTargetDimensions(1200, 900, maxDimensionPx = 600),
        )

        // Already within max bound -> returned intact without upscaling
        assertEquals(
            ArtworkDimensions(400, 225),
            AlbumArtworkHelper.computeProportionalTargetDimensions(400, 225, maxDimensionPx = 512),
        )

        // Tolerance band (1.25f): 700 <= 750 stays untouched; 800 > 750 scales down to 600x338
        assertEquals(
            ArtworkDimensions(700, 394),
            AlbumArtworkHelper.computeProportionalTargetDimensions(700, 394, maxDimensionPx = 600, toleranceScale = 1.25f),
        )
        assertEquals(
            ArtworkDimensions(600, 338),
            AlbumArtworkHelper.computeProportionalTargetDimensions(800, 450, maxDimensionPx = 600, toleranceScale = 1.25f),
        )

        // Extreme banner ratio never collapses shortest side to 0px
        assertEquals(
            ArtworkDimensions(160, 1),
            AlbumArtworkHelper.computeProportionalTargetDimensions(5000, 10, maxDimensionPx = 160),
        )

        // Invalid dimensions handled safely
        assertEquals(
            ArtworkDimensions(0, 500),
            AlbumArtworkHelper.computeProportionalTargetDimensions(0, 500, maxDimensionPx = 512),
        )
    }

    @Test
    fun slotTargetSizeResolution_boundsDiscAndLargeSlotsCleanly() {
        assertEquals(
            AlbumArtworkHelper.DISC_BUCKET_PX,
            AlbumArtworkHelper.resolveSlotTargetSizePx(EmbeddedArtworkSlot.DISC, 600),
        )
        assertEquals(
            AlbumArtworkHelper.DISC_BUCKET_PX,
            AlbumArtworkHelper.resolveSlotTargetSizePx(EmbeddedArtworkSlot.DISC, 1400),
        )
        assertEquals(
            AlbumArtworkHelper.THUMBNAIL_BUCKET_PX,
            AlbumArtworkHelper.resolveSlotTargetSizePx(EmbeddedArtworkSlot.DISC, 160),
        )
        assertEquals(
            AlbumArtworkHelper.HERO_BUCKET_PX,
            AlbumArtworkHelper.resolveSlotTargetSizePx(EmbeddedArtworkSlot.BACK_COVER, 600),
        )
        assertEquals(
            AlbumArtworkHelper.SLOT_LARGE_BUCKET_PX,
            AlbumArtworkHelper.resolveSlotTargetSizePx(EmbeddedArtworkSlot.ARTIST, 1400),
        )
    }

    @Test
    fun discSlotCacheKey_sharesKeyWithinAlbumDiscAndDistinctSafFolder() {
        val disc1Track1 = AlbumArtworkHelper.buildSlotCacheKey(
            slot = EmbeddedArtworkSlot.DISC,
            trackUri = "content://saf/doc/01.flac",
            artist = "Green Day",
            album = "21st Century Breakdown",
            discNumber = 1,
            parentTreeUriString = "content://tree/abc",
            folderName = "21CB",
            dateModifiedSeconds = 100L,
            fileSizeBytes = 10_000L,
            targetSizePx = 600,
        )
        val disc1Track2 = AlbumArtworkHelper.buildSlotCacheKey(
            slot = EmbeddedArtworkSlot.DISC,
            trackUri = "content://saf/doc/02.flac",
            artist = "Green Day",
            album = "21st Century Breakdown",
            discNumber = 1,
            parentTreeUriString = "content://tree/abc",
            folderName = "21CB",
            dateModifiedSeconds = 200L,
            fileSizeBytes = 20_000L,
            targetSizePx = 512,
        )
        val disc2Track1 = AlbumArtworkHelper.buildSlotCacheKey(
            slot = EmbeddedArtworkSlot.DISC,
            trackUri = "content://saf/doc/03.flac",
            artist = "Green Day",
            album = "21st Century Breakdown",
            discNumber = 2,
            parentTreeUriString = "content://tree/abc",
            folderName = "21CB",
            dateModifiedSeconds = 200L,
            fileSizeBytes = 20_000L,
            targetSizePx = 512,
        )
        assertEquals(disc1Track1, disc1Track2)
        assertFalse(disc1Track1 == disc2Track1)

        // Distinct SAF folder fallback without album tag shares DISC slot across sibling tracks.
        val unknownAlbumFolder1 = AlbumArtworkHelper.buildSlotCacheKey(
            slot = EmbeddedArtworkSlot.DISC,
            trackUri = "content://saf/doc/a.flac",
            artist = "Artist",
            album = "Unknown Album",
            discNumber = null,
            parentTreeUriString = "content://tree/xyz",
            folderName = "Bootleg 2009",
            dateModifiedSeconds = 1L,
            fileSizeBytes = 100L,
            targetSizePx = 512,
        )
        val unknownAlbumFolder2 = AlbumArtworkHelper.buildSlotCacheKey(
            slot = EmbeddedArtworkSlot.DISC,
            trackUri = "content://saf/doc/b.flac",
            artist = "Artist",
            album = "",
            discNumber = null,
            parentTreeUriString = "content://tree/xyz",
            folderName = "Bootleg 2009",
            dateModifiedSeconds = 2L,
            fileSizeBytes = 200L,
            targetSizePx = 512,
        )
        assertEquals(unknownAlbumFolder1, unknownAlbumFolder2)

        // Generic folder without album keeps track isolation; BACK_COVER also stays track-scoped.
        val generic1 = AlbumArtworkHelper.buildSlotCacheKey(
            slot = EmbeddedArtworkSlot.DISC,
            trackUri = "content://saf/doc/g1.flac",
            artist = "Artist",
            album = "Unknown Album",
            discNumber = null,
            parentTreeUriString = "content://tree/xyz",
            folderName = "Downloads",
            dateModifiedSeconds = 1L,
            fileSizeBytes = 100L,
            targetSizePx = 512,
        )
        val generic2 = AlbumArtworkHelper.buildSlotCacheKey(
            slot = EmbeddedArtworkSlot.DISC,
            trackUri = "content://saf/doc/g2.flac",
            artist = "Artist",
            album = "Unknown Album",
            discNumber = null,
            parentTreeUriString = "content://tree/xyz",
            folderName = "Downloads",
            dateModifiedSeconds = 2L,
            fileSizeBytes = 200L,
            targetSizePx = 512,
        )
        assertFalse(generic1 == generic2)
    }
}
