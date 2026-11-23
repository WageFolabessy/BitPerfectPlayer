package com.cangkir.bitperfectplayer.library

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ArtistPhotoStoreTest {
    private lateinit var app: Application
    private lateinit var store: ArtistPhotoStore
    private lateinit var sourceFile: File

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        store = ArtistPhotoStore(app)
        sourceFile = File(app.cacheDir, "test_artist_photo_src.jpg")
    }

    @After
    fun tearDown() {
        sourceFile.delete()
        File(app.filesDir, "artist_photos").deleteRecursively()
    }

    @Test
    fun saveLoadAndClearCustomArtistPhotoPersistsDownscaledBitmapAndIncrementsRevision() = runTest {
        val raw = Bitmap.createBitmap(1600, 1200, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(180, 40, 60))
        }
        FileOutputStream(sourceFile).use { out ->
            raw.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        raw.recycle()

        val initialRev = store.photoRevision.value
        assertFalse(store.hasCustomArtistPhoto("Pink Floyd"))
        assertNull(store.loadArtistPhotoBitmap("Pink Floyd"))

        val saveResult = store.saveArtistPhoto("Pink Floyd", Uri.fromFile(sourceFile))
        assertTrue(saveResult.isSuccess)
        val savedBitmap = saveResult.getOrThrow()
        assertTrue(maxOf(savedBitmap.width, savedBitmap.height) <= 1080)
        assertEquals(initialRev + 1, store.photoRevision.value)
        assertTrue(store.hasCustomArtistPhoto("Pink Floyd"))
        assertNotNull(store.getCachedBitmap("Pink Floyd"))

        // Reload with a fresh store instance (disk persistence verification)
        val reloadedStore = ArtistPhotoStore(app)
        val loadedFromDisk = reloadedStore.loadArtistPhotoBitmap("  pink floyd  ")
        assertNotNull(loadedFromDisk)

        // Reset removes disk file, evicts memory cache, and bumps photoRevision
        assertTrue(store.clearArtistPhoto("Pink Floyd"))
        assertEquals(initialRev + 2, store.photoRevision.value)
        assertFalse(store.hasCustomArtistPhoto("Pink Floyd"))
        assertNull(store.getCachedBitmap("Pink Floyd"))
        assertNull(store.loadArtistPhotoBitmap("Pink Floyd"))
    }

    @Test
    fun readJpegExifOrientationParsesLittleEndianAndBigEndianOrientationTags() {
        val leApp1 = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),
            0xFF.toByte(), 0xE1.toByte(), 0x00, 0x20,
            'E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0,
            'I'.code.toByte(), 'I'.code.toByte(), 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00,
            0x01, 0x00,
            0x12, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00, 0x06, 0x00, 0x00, 0x00,
            0xFF.toByte(), 0xD9.toByte(),
        )
        val beApp1 = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),
            0xFF.toByte(), 0xE1.toByte(), 0x00, 0x20,
            'E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0,
            'M'.code.toByte(), 'M'.code.toByte(), 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08,
            0x00, 0x01,
            0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01, 0x00, 0x08, 0x00, 0x00,
            0xFF.toByte(), 0xD9.toByte(),
        )
        assertEquals(6, ArtistPhotoStore.readJpegExifOrientation(leApp1))
        assertEquals(8, ArtistPhotoStore.readJpegExifOrientation(beApp1))
        assertEquals(1, ArtistPhotoStore.readJpegExifOrientation(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)))
    }
}
