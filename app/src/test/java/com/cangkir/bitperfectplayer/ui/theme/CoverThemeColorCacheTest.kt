package com.cangkir.bitperfectplayer.ui.theme

import android.app.Application
import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import androidx.test.core.app.ApplicationProvider
import com.cangkir.bitperfectplayer.audio.PlaybackTrack
import com.cangkir.bitperfectplayer.data.NowPlayingArtworkStyle
import com.cangkir.bitperfectplayer.library.AlbumArtworkLoader
import com.cangkir.bitperfectplayer.library.EmbeddedArtworkSlot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class CoverThemeColorCacheTest {

    private val track = PlaybackTrack(
        uriString = "content://test/audio/slot_test.flac",
        title = "Multi-Slot FLAC",
        artist = "Analog Collective",
        album = "Chromatic Press",
        fileName = "slot_test.flac",
        durationMs = 200_000L,
        container = "FLAC",
        codec = "FLAC",
    )

    private fun solidBitmap(colorArgb: Int, size: Int = 96): Bitmap = createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
        eraseColor(colorArgb)
    }

    @Test
    fun cacheKeysIsolateAllFourArtworkSlotsWithoutRecyclingCallerBitmaps() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val loader = AlbumArtworkLoader(context)
        val cache = CoverThemeColorCache(loader)

        val frontBitmap = solidBitmap(0xFF1D4ED8.toInt()) // Cobalt blue
        val backBitmap = solidBitmap(0xFFD9363E.toInt()) // Crimson red
        val discBitmap = solidBitmap(0xFFE5A93B.toInt()) // Warm gold
        val artistBitmap = solidBitmap(0xFF10B981.toInt()) // Emerald green

        val sharedKey = "shared_album_key"
        val front = cache.resolvePaletteFromBitmap(sharedKey, EmbeddedArtworkSlot.FRONT_COVER, frontBitmap)
        val back = cache.resolvePaletteFromBitmap(sharedKey, EmbeddedArtworkSlot.BACK_COVER, backBitmap)
        val disc = cache.resolvePaletteFromBitmap(sharedKey, EmbeddedArtworkSlot.DISC, discBitmap)
        val artist = cache.resolvePaletteFromBitmap(sharedKey, EmbeddedArtworkSlot.ARTIST, artistBitmap)

        assertNotNull(front)
        assertNotNull(back)
        assertNotNull(disc)
        assertNotNull(artist)
        assertFalse(frontBitmap.isRecycled)
        assertFalse(backBitmap.isRecycled)
        assertFalse(discBitmap.isRecycled)
        assertFalse(artistBitmap.isRecycled)

        val hues = listOf(front!!, back!!, disc!!, artist!!).map { it.primaryOklch.hDeg }
        assertEquals(4, hues.map { (it / 25f).toInt() }.toSet().size)

        // Switching SUBTLE vs VIBRANT on the cached palette requires zero re-decoding.
        val subtleLookup = cache.peekCachedByKey(sharedKey, EmbeddedArtworkSlot.DISC, DynamicThemeIntensity.SUBTLE)
        val vibrantLookup = cache.peekCachedByKey(sharedKey, EmbeddedArtworkSlot.DISC, DynamicThemeIntensity.VIBRANT)
        assertTrue(subtleLookup is CoverThemeLookup.Resolved && subtleLookup.tokens != null)
        assertTrue(vibrantLookup is CoverThemeLookup.Resolved && vibrantLookup.tokens != null)
        assertNotEquals(
            (subtleLookup as CoverThemeLookup.Resolved).tokens!!.primaryArgb,
            (vibrantLookup as CoverThemeLookup.Resolved).tokens!!.primaryArgb,
        )

        // Single-slot invalidation removes only DISC while preserving FRONT/BACK/ARTIST.
        cache.invalidateKey(sharedKey, EmbeddedArtworkSlot.DISC)
        assertTrue(cache.peekCachedByKey(sharedKey, EmbeddedArtworkSlot.DISC) is CoverThemeLookup.Pending)
        assertTrue(cache.peekCachedByKey(sharedKey, EmbeddedArtworkSlot.FRONT_COVER) is CoverThemeLookup.Resolved)
        assertTrue(cache.peekCachedByKey(sharedKey, EmbeddedArtworkSlot.BACK_COVER) is CoverThemeLookup.Resolved)
        assertTrue(cache.peekCachedByKey(sharedKey, EmbeddedArtworkSlot.ARTIST) is CoverThemeLookup.Resolved)
    }

    @Test
    fun resolveSpectrumTriad_usesDisplayedSlotAndBorrowsFrontCoverWhenDiscIsMonochrome() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val loader = AlbumArtworkLoader(context)
        val cache = CoverThemeColorCache(loader)

        val blueFront = solidBitmap(0xFF1D4ED8.toInt())
        val crimsonBack = solidBitmap(0xFFD9363E.toInt())
        val monoDisc = solidBitmap(0xFF7A7A7A.toInt())

        loader.seedArtworkForTesting(track, blueFront, AlbumArtworkLoader.THUMBNAIL_SIZE_PX)
        loader.seedSlotBitmapForTesting(track, EmbeddedArtworkSlot.DISC, monoDisc, AlbumArtworkLoader.DISC_SIZE_PX)
        loader.seedSlotBitmapForTesting(track, EmbeddedArtworkSlot.BACK_COVER, crimsonBack, AlbumArtworkLoader.HERO_SIZE_PX)

        // Vinyl Disc mode with monochrome DISC -> borrows chromatic FRONT_COVER (blue).
        val vinylTriad = cache.resolveSpectrumTriad(track, NowPlayingArtworkStyle.VINYL_DISC, isBackCoverVisible = false)
        val frontTriad = cache.resolveSpectrumTriad(track, NowPlayingArtworkStyle.SQUARE_COVER, isBackCoverVisible = false)
        assertTrue(vinylTriad.contentEquals(frontTriad))

        // Square Cover flipped to chromatic BACK_COVER -> yields distinct crimson triad!
        val backTriad = cache.resolveSpectrumTriad(track, NowPlayingArtworkStyle.SQUARE_COVER, isBackCoverVisible = true)
        assertFalse(backTriad.contentEquals(frontTriad))
    }

    @Test
    fun resolveArtistHeroTokens_borrowsOldestAlbumFrontCoverWhenArtistPhotoIsMonochrome() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val loader = AlbumArtworkLoader(context)
        val cache = CoverThemeColorCache(loader)

        val monoArtistBitmap = solidBitmap(0xFF5A5A5A.toInt())
        val goldAlbumBitmap = solidBitmap(0xFFE5A93B.toInt())
        loader.seedArtworkForTesting(track, goldAlbumBitmap, AlbumArtworkLoader.THUMBNAIL_SIZE_PX)

        val tokens = cache.resolveArtistHeroTokens(
            heroKey = "artist_custom:analog_collective:1",
            heroSlot = EmbeddedArtworkSlot.ARTIST,
            heroBitmap = monoArtistBitmap,
            fallbackAlbumCandidates = listOf(track),
        )
        assertNotNull("Monochrome ARTIST photo should borrow chromatic oldest album FRONT_COVER", tokens)

        // When both ARTIST photo and album fallback are monochrome -> falls back to Monochrome Studio (null).
        val monoTrack = track.copy(uriString = "content://test/audio/mono_album.flac", album = "Mono Album")
        loader.seedArtworkForTesting(monoTrack, solidBitmap(0xFF202020.toInt()), AlbumArtworkLoader.THUMBNAIL_SIZE_PX)
        val monoTokens = cache.resolveArtistHeroTokens(
            heroKey = "artist_custom:mono_artist:1",
            heroSlot = EmbeddedArtworkSlot.ARTIST,
            heroBitmap = monoArtistBitmap,
            fallbackAlbumCandidates = listOf(monoTrack),
        )
        assertNull(monoTokens)
    }
}
