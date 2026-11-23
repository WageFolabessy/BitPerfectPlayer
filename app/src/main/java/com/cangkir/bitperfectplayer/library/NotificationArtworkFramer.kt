package com.cangkir.bitperfectplayer.library

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale

/**
 * Android `Bitmap` framer applying [NotificationArtworkHelper.computeSquareLetterboxLayout] so
 * non-square album artwork is rendered 100% uncropped on Android notification shade and lock-screen
 * media cards without ever exceeding `512x512` across Binder IPC.
 *
 * Never mutates or recycles [source], which remains owned by [AlbumArtworkLoader].
 */
object NotificationArtworkFramer {
    fun frameForMediaNotification(source: Bitmap?, maxSizePx: Int = NotificationArtworkHelper.MAX_NOTIFICATION_COVER_PX): Bitmap? {
        val valid = source?.takeIf { !it.isRecycled && it.width > 0 && it.height > 0 } ?: return null
        val layout = NotificationArtworkHelper.computeSquareLetterboxLayout(
            srcWidth = valid.width,
            srcHeight = valid.height,
            maxSizePx = maxSizePx,
        ) ?: return null

        val readable = if (valid.config == Bitmap.Config.HARDWARE) {
            valid.copy(Bitmap.Config.ARGB_8888, false) ?: return null
        } else {
            valid
        }

        var result: Bitmap? = null
        return try {
            result = if (!layout.requiresLetterbox) {
                if (readable.width == layout.canvasSizePx && readable.height == layout.canvasSizePx) {
                    readable
                } else {
                    readable.scale(layout.canvasSizePx, layout.canvasSizePx)
                }
            } else {
                val framed = createBitmap(layout.canvasSizePx, layout.canvasSizePx, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(framed)
                canvas.drawColor(NotificationArtworkHelper.LETTERBOX_BG_ARGB)
                val destRect = Rect(
                    layout.drawLeft,
                    layout.drawTop,
                    layout.drawLeft + layout.drawWidth,
                    layout.drawTop + layout.drawHeight,
                )
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                canvas.drawBitmap(readable, null, destRect, paint)
                framed
            }
            result
        } finally {
            if (readable !== valid && readable !== result && !readable.isRecycled) {
                readable.recycle()
            }
        }
    }
}
