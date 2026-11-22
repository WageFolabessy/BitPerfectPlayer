package com.cangkir.bitperfectplayer.ui.theme

import android.graphics.Bitmap
import android.util.LruCache
import com.cangkir.bitperfectplayer.audio.PlaybackTrack
import com.cangkir.bitperfectplayer.library.AlbumArtworkLoader
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Synchronous lookup state returned by [CoverThemeColorCache.peekCached] so Compose UI can bind
 * cached palettes on the first frame or fall back smoothly while extracting off-thread.
 */
sealed interface CoverThemeLookup {
    data object Pending : CoverThemeLookup
    data class Resolved(val tokens: DynamicThemeTokens?) : CoverThemeLookup
}

/**
 * Memory-cached, off-main-thread bridge converting local cover artwork loaded by
 * [AlbumArtworkLoader] into contrast-verified [DynamicThemeTokens].
 *
 * Performance & safety invariants:
 * - Downsamples artwork strictly on `Dispatchers.Default` to at most `32x32 px` (`<= 4 KB` pixel
 *   buffer) and never scans pixels on the main or audio threads.
 * - Handles `Bitmap.Config.HARDWARE` copies safely without ever recycling bitmaps owned by
 *   [AlbumArtworkLoader].
 */
class CoverThemeColorCache(private val albumArtworkLoader: AlbumArtworkLoader) {
    private class CachedEntry(val tokens: DynamicThemeTokens?)

    private val cache = LruCache<String, CachedEntry>(MAX_CACHE_ENTRIES)

    fun cacheKeyFor(track: PlaybackTrack): String = albumArtworkLoader.memoryKeyFor(track, AlbumArtworkLoader.THUMBNAIL_SIZE_PX)

    fun peekCached(track: PlaybackTrack): CoverThemeLookup {
        val entry = cache.get(cacheKeyFor(track)) ?: return CoverThemeLookup.Pending
        return CoverThemeLookup.Resolved(entry.tokens)
    }

    internal fun seedCacheForTesting(track: PlaybackTrack, tokens: DynamicThemeTokens?) {
        cache.put(cacheKeyFor(track), CachedEntry(tokens))
    }

    fun clear() {
        cache.evictAll()
    }

    suspend fun resolveTokens(track: PlaybackTrack?): DynamicThemeTokens? {
        if (track == null) return null
        val key = cacheKeyFor(track)
        cache.get(key)?.let { return it.tokens }

        val bitmap = albumArtworkLoader.loadArtwork(track, AlbumArtworkLoader.THUMBNAIL_SIZE_PX)
        if (bitmap == null) {
            cache.put(key, CachedEntry(null))
            return null
        }

        val extracted = withContext(Dispatchers.Default) {
            extractFromBitmapSafely(bitmap)
        }
        cache.put(key, CachedEntry(extracted))
        return extracted
    }

    private fun extractFromBitmapSafely(bitmap: Bitmap): DynamicThemeTokens? {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return null
        return runCatching {
            val softwareBitmap = if (bitmap.config == Bitmap.Config.HARDWARE) {
                bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return@runCatching null
            } else {
                bitmap
            }
            try {
                extractScaledPixels(softwareBitmap)
            } finally {
                if (softwareBitmap !== bitmap && !softwareBitmap.isRecycled) {
                    softwareBitmap.recycle()
                }
            }
        }.getOrNull()
    }

    private fun extractScaledPixels(softwareBitmap: Bitmap): DynamicThemeTokens? {
        val srcWidth = softwareBitmap.width
        val srcHeight = softwareBitmap.height
        val maxDim = maxOf(srcWidth, srcHeight)
        val scale = if (maxDim > MAX_SAMPLE_DIM_PX) MAX_SAMPLE_DIM_PX.toFloat() / maxDim.toFloat() else 1f
        val targetWidth = (srcWidth * scale).roundToInt().coerceAtLeast(1)
        val targetHeight = (srcHeight * scale).roundToInt().coerceAtLeast(1)

        val sampled = if (targetWidth != srcWidth || targetHeight != srcHeight) {
            Bitmap.createScaledBitmap(softwareBitmap, targetWidth, targetHeight, true)
        } else {
            softwareBitmap
        }
        try {
            val pixels = IntArray(targetWidth * targetHeight)
            sampled.getPixels(pixels, 0, targetWidth, 0, 0, targetWidth, targetHeight)
            return CoverThemePaletteGenerator.extractThemeTokens(pixels, targetWidth, targetHeight)
        } finally {
            if (sampled !== softwareBitmap && !sampled.isRecycled) {
                sampled.recycle()
            }
        }
    }

    companion object {
        private const val MAX_CACHE_ENTRIES = 128
        private const val MAX_SAMPLE_DIM_PX = 32
    }
}
