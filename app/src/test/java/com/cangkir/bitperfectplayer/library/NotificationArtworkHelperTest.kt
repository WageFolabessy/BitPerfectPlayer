package com.cangkir.bitperfectplayer.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationArtworkHelperTest {

    @Test
    fun squareCovers_passThroughWithoutLetterboxAndCapAt512Px() {
        val exact = NotificationArtworkHelper.computeSquareLetterboxLayout(512, 512)
        assertNotNull(exact)
        assertEquals(512, exact!!.canvasSizePx)
        assertEquals(0, exact.drawLeft)
        assertEquals(0, exact.drawTop)
        assertEquals(512, exact.drawWidth)
        assertEquals(512, exact.drawHeight)
        assertFalse(exact.requiresLetterbox)

        val large = NotificationArtworkHelper.computeSquareLetterboxLayout(1200, 1200)
        assertNotNull(large)
        assertEquals(512, large!!.canvasSizePx)
        assertEquals(512, large.drawWidth)
        assertEquals(512, large.drawHeight)
        assertFalse(large.requiresLetterbox)

        val small = NotificationArtworkHelper.computeSquareLetterboxLayout(300, 300)
        assertNotNull(small)
        assertEquals(300, small!!.canvasSizePx)
        assertEquals(300, small.drawWidth)
        assertEquals(300, small.drawHeight)
        assertFalse(small.requiresLetterbox)
    }

    @Test
    fun landscapeCover_centersVerticallyInSquareCanvasWithLetterboxBars() {
        val hd = NotificationArtworkHelper.computeSquareLetterboxLayout(1600, 900)
        assertNotNull(hd)
        assertEquals(512, hd!!.canvasSizePx)
        assertEquals(512, hd.drawWidth)
        assertEquals(288, hd.drawHeight)
        assertEquals(0, hd.drawLeft)
        assertEquals(112, hd.drawTop)
        assertTrue(hd.requiresLetterbox)

        val compact = NotificationArtworkHelper.computeSquareLetterboxLayout(400, 200)
        assertNotNull(compact)
        assertEquals(400, compact!!.canvasSizePx)
        assertEquals(400, compact.drawWidth)
        assertEquals(200, compact.drawHeight)
        assertEquals(0, compact.drawLeft)
        assertEquals(100, compact.drawTop)
        assertTrue(compact.requiresLetterbox)
    }

    @Test
    fun portraitCover_centersHorizontallyInSquareCanvasWithPillarboxBars() {
        val portrait = NotificationArtworkHelper.computeSquareLetterboxLayout(900, 1600)
        assertNotNull(portrait)
        assertEquals(512, portrait!!.canvasSizePx)
        assertEquals(288, portrait.drawWidth)
        assertEquals(512, portrait.drawHeight)
        assertEquals(112, portrait.drawLeft)
        assertEquals(0, portrait.drawTop)
        assertTrue(portrait.requiresLetterbox)
    }

    @Test
    fun invalidDimensions_returnsNull() {
        assertNull(NotificationArtworkHelper.computeSquareLetterboxLayout(0, 512))
        assertNull(NotificationArtworkHelper.computeSquareLetterboxLayout(512, 0))
        assertNull(NotificationArtworkHelper.computeSquareLetterboxLayout(-10, 100))
        assertNull(NotificationArtworkHelper.computeSquareLetterboxLayout(512, 512, maxSizePx = 0))
    }
}
