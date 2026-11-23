package com.cangkir.bitperfectplayer.library

import kotlin.math.roundToInt

/**
 * Pure-Kotlin placement geometry for square-letterboxed media notification cover art
 * (zero `android.*` / `androidx.*` imports).
 */
data class SquareLetterboxLayout(
    val canvasSizePx: Int,
    val drawLeft: Int,
    val drawTop: Int,
    val drawWidth: Int,
    val drawHeight: Int,
    val requiresLetterbox: Boolean,
)

/**
 * Pure-Kotlin square-letterboxing math for `MediaMetadata` / lock-screen notification artwork:
 * - Square (`1:1`) covers pass through with zero dark borders (`requiresLetterbox = false`),
 *   capped at [MAX_NOTIFICATION_COVER_PX] (`512 px`) to avoid `TransactionTooLargeException`.
 * - Non-square (`width != height`) covers are centered proportionally inside a square
 *   matte-obsidian (`#111114`) canvas capped at `512x512` so SystemUI never crops landscape or
 *   portrait artwork.
 */
object NotificationArtworkHelper {
    const val MAX_NOTIFICATION_COVER_PX: Int = 512
    const val LETTERBOX_BG_ARGB: Int = 0xFF111114.toInt()

    fun computeSquareLetterboxLayout(srcWidth: Int, srcHeight: Int, maxSizePx: Int = MAX_NOTIFICATION_COVER_PX): SquareLetterboxLayout? {
        if (srcWidth <= 0 || srcHeight <= 0 || maxSizePx <= 0) return null

        val longest = maxOf(srcWidth, srcHeight)
        val canvasSize = minOf(longest, maxSizePx)
        if (srcWidth == srcHeight) {
            return SquareLetterboxLayout(
                canvasSizePx = canvasSize,
                drawLeft = 0,
                drawTop = 0,
                drawWidth = canvasSize,
                drawHeight = canvasSize,
                requiresLetterbox = false,
            )
        }

        val scale = canvasSize.toDouble() / longest.toDouble()
        val drawWidth = (srcWidth.toDouble() * scale).roundToInt().coerceIn(1, canvasSize)
        val drawHeight = (srcHeight.toDouble() * scale).roundToInt().coerceIn(1, canvasSize)
        val drawLeft = ((canvasSize - drawWidth) / 2).coerceAtLeast(0)
        val drawTop = ((canvasSize - drawHeight) / 2).coerceAtLeast(0)
        return SquareLetterboxLayout(
            canvasSizePx = canvasSize,
            drawLeft = drawLeft,
            drawTop = drawTop,
            drawWidth = drawWidth,
            drawHeight = drawHeight,
            requiresLetterbox = true,
        )
    }
}
