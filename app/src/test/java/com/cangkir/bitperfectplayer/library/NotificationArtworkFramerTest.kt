package com.cangkir.bitperfectplayer.library

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.test.core.app.ApplicationProvider
import com.cangkir.bitperfectplayer.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class NotificationArtworkFramerTest {

    @Test
    fun landscapeBitmap_isFramedIntoSquare512WithDarkObsidianLetterboxAndUncroppedCenter() {
        val src = createBitmap(1600, 900, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.RED)
        }
        val framed = NotificationArtworkFramer.frameForMediaNotification(src)
        assertNotNull(framed)
        assertEquals(512, framed!!.width)
        assertEquals(512, framed.height)
        assertFalse(src.isRecycled)

        // Top and bottom letterbox margins (y in 0..111 and 400..511) must be matte obsidian #111114
        assertEquals(NotificationArtworkHelper.LETTERBOX_BG_ARGB, framed.getPixel(256, 10))
        assertEquals(NotificationArtworkHelper.LETTERBOX_BG_ARGB, framed.getPixel(256, 110))
        assertEquals(NotificationArtworkHelper.LETTERBOX_BG_ARGB, framed.getPixel(256, 402))
        assertEquals(NotificationArtworkHelper.LETTERBOX_BG_ARGB, framed.getPixel(256, 500))
        // Center artwork region (y in 112..399) must remain uncropped red
        assertEquals(Color.RED, framed.getPixel(256, 114))
        assertEquals(Color.RED, framed.getPixel(256, 256))
        assertEquals(Color.RED, framed.getPixel(256, 398))

        framed.recycle()
        src.recycle()
    }

    @Test
    fun portraitBitmap_isFramedIntoSquare512WithDarkObsidianPillarboxAndUncroppedCenter() {
        val src = createBitmap(900, 1600, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLUE)
        }
        val framed = NotificationArtworkFramer.frameForMediaNotification(src)
        assertNotNull(framed)
        assertEquals(512, framed!!.width)
        assertEquals(512, framed.height)
        assertFalse(src.isRecycled)

        // Left and right pillarbox margins (x in 0..111 and 400..511) must be matte obsidian #111114
        assertEquals(NotificationArtworkHelper.LETTERBOX_BG_ARGB, framed.getPixel(10, 256))
        assertEquals(NotificationArtworkHelper.LETTERBOX_BG_ARGB, framed.getPixel(110, 256))
        assertEquals(NotificationArtworkHelper.LETTERBOX_BG_ARGB, framed.getPixel(402, 256))
        assertEquals(NotificationArtworkHelper.LETTERBOX_BG_ARGB, framed.getPixel(500, 256))
        // Center artwork region (x in 112..399) must remain uncropped blue
        assertEquals(Color.BLUE, framed.getPixel(114, 256))
        assertEquals(Color.BLUE, framed.getPixel(256, 256))
        assertEquals(Color.BLUE, framed.getPixel(398, 256))

        framed.recycle()
        src.recycle()
    }

    @Test
    fun squareBitmapAtOrBelow512_passesThroughIdenticallyWithoutBorders() {
        val src = createBitmap(512, 512, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.GREEN)
        }
        val framed = NotificationArtworkFramer.frameForMediaNotification(src)
        assertSame(src, framed)
        assertEquals(Color.GREEN, framed!!.getPixel(0, 0))
        src.recycle()
    }

    @Test
    fun largeSquareBitmapAbove512_scalesDownTo512WithoutAddingDarkBorders() {
        val src = createBitmap(1024, 1024, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.YELLOW)
        }
        val framed = NotificationArtworkFramer.frameForMediaNotification(src)
        assertNotNull(framed)
        assertEquals(512, framed!!.width)
        assertEquals(512, framed.height)
        assertEquals(Color.YELLOW, framed.getPixel(0, 0))
        assertFalse(src.isRecycled)

        framed.recycle()
        src.recycle()
    }

    @Test
    fun nullOrRecycledBitmap_returnsNullSafely() {
        assertNull(NotificationArtworkFramer.frameForMediaNotification(null))
        val recycled = createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        recycled.recycle()
        assertNull(NotificationArtworkFramer.frameForMediaNotification(recycled))
    }

    @Test
    fun customMonochromeNotificationDrawables_resolveCleanly() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertNotNull(ContextCompat.getDrawable(app, R.drawable.ic_stat_bitperfect))
        assertNotNull(ContextCompat.getDrawable(app, R.drawable.ic_notif_favorite_filled))
        assertNotNull(ContextCompat.getDrawable(app, R.drawable.ic_notif_favorite_outline))
        assertNotNull(ContextCompat.getDrawable(app, R.drawable.ic_notif_shuffle_on))
        assertNotNull(ContextCompat.getDrawable(app, R.drawable.ic_notif_shuffle_off))
    }
}
