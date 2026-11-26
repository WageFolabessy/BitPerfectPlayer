package com.cangkir.bitperfectplayer.ui.theme

import android.graphics.Bitmap
import android.util.LruCache
import androidx.core.graphics.scale
import com.cangkir.bitperfectplayer.audio.PlaybackTrack
import com.cangkir.bitperfectplayer.data.NowPlayingArtworkStyle
import com.cangkir.bitperfectplayer.library.AlbumArtworkLoader
import com.cangkir.bitperfectplayer.library.EmbeddedArtworkSlot
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
 * Composite LRU cache key isolating all 4 embedded/custom artwork metadata slots
 * (`FRONT_COVER`, `BACK_COVER`, `DISC`, `ARTIST`) per stable source key.
 */
data class ArtworkThemeCacheKey(val sourceKey: String, val slot: EmbeddedArtworkSlot)

/**
 * Memory-cached, off-main-thread bridge converting local cover/disc/back/artist artwork loaded by
 * [AlbumArtworkLoader] into perceptual [ArtworkColorPalette] instances and contrast-verified
 * [DynamicThemeTokens].
 *
 * Performance & safety invariants:
 * - Keyed by `(stableSourceKey, EmbeddedArtworkSlot)` inside a bounded [LruCache] (`160` entries),
 *   storing perceptual [ArtworkColorPalette] so `SUBTLE`/`VIBRANT` mapping and spectrum triads
 *   never re-decode or re-cluster bitmaps.
 * - Downsamples artwork strictly on `Dispatchers.Default` to at most `48x48 px` (`<= 9.2 KB` pixel
 *   buffer) and never scans pixels on the main or audio threads.
 * - Strictly lazy single-slot loading: requesting `DISC` never decodes `BACK_COVER` or `ARTIST`,
 *   and `FRONT_COVER` fallback is requested only when `primarySlot` is absent or monochrome.
 * - Handles `Bitmap.Config.HARDWARE` copies safely without ever recycling bitmaps owned by
 *   [AlbumArtworkLoader].
 */
class CoverThemeColorCache(private val albumArtworkLoader: AlbumArtworkLoader) : CoverThemeResolver {
    private class CachedPaletteEntry(val palette: ArtworkColorPalette?, val seededTokens: DynamicThemeTokens? = null) {
        fun toTokens(intensity: DynamicThemeIntensity): DynamicThemeTokens? =
            seededTokens ?: palette?.let { CoverThemePaletteGenerator.generateTokensFromPalette(it, intensity) }
    }

    private val cache = LruCache<ArtworkThemeCacheKey, CachedPaletteEntry>(MAX_CACHE_ENTRIES)

    override fun supportsMultiSlotPalettes(): Boolean = true

    override fun cacheKeyFor(track: PlaybackTrack): String = cacheKeyFor(track, EmbeddedArtworkSlot.FRONT_COVER)

    override fun cacheKeyFor(track: PlaybackTrack, slot: EmbeddedArtworkSlot): String = when (slot) {
        EmbeddedArtworkSlot.FRONT_COVER -> albumArtworkLoader.memoryKeyFor(track, AlbumArtworkLoader.THUMBNAIL_SIZE_PX)
        EmbeddedArtworkSlot.DISC -> albumArtworkLoader.slotCacheKeyFor(
            track = track,
            slot = EmbeddedArtworkSlot.DISC,
            targetSizePx = AlbumArtworkLoader.DISC_SIZE_PX,
        )
        EmbeddedArtworkSlot.BACK_COVER -> albumArtworkLoader.slotCacheKeyFor(
            track = track,
            slot = EmbeddedArtworkSlot.BACK_COVER,
            targetSizePx = AlbumArtworkLoader.HERO_SIZE_PX,
        )
        EmbeddedArtworkSlot.ARTIST -> albumArtworkLoader.slotCacheKeyFor(
            track = track,
            slot = EmbeddedArtworkSlot.ARTIST,
            targetSizePx = AlbumArtworkLoader.HERO_SIZE_PX,
        )
    }

    fun peekCached(
        track: PlaybackTrack,
        slot: EmbeddedArtworkSlot = EmbeddedArtworkSlot.FRONT_COVER,
        intensity: DynamicThemeIntensity = DynamicThemeIntensity.SUBTLE,
    ): CoverThemeLookup = peekCachedByKey(cacheKeyFor(track, slot), slot = slot, intensity = intensity)

    fun peekCachedByKey(
        key: String,
        slot: EmbeddedArtworkSlot = EmbeddedArtworkSlot.FRONT_COVER,
        intensity: DynamicThemeIntensity = DynamicThemeIntensity.SUBTLE,
    ): CoverThemeLookup {
        val entry = cache.get(ArtworkThemeCacheKey(key, slot)) ?: return CoverThemeLookup.Pending
        return CoverThemeLookup.Resolved(entry.toTokens(intensity))
    }

    fun peekCachedPalette(track: PlaybackTrack, slot: EmbeddedArtworkSlot = EmbeddedArtworkSlot.FRONT_COVER): ArtworkColorPalette? =
        cache.get(ArtworkThemeCacheKey(cacheKeyFor(track, slot), slot))?.palette

    fun invalidateKey(key: String, slot: EmbeddedArtworkSlot? = null) {
        if (slot != null) {
            cache.remove(ArtworkThemeCacheKey(key, slot))
        } else {
            EmbeddedArtworkSlot.entries.forEach { s ->
                cache.remove(ArtworkThemeCacheKey(key, s))
            }
        }
    }

    internal fun seedCacheForTesting(
        track: PlaybackTrack,
        tokens: DynamicThemeTokens?,
        slot: EmbeddedArtworkSlot = EmbeddedArtworkSlot.FRONT_COVER,
    ) {
        cache.put(ArtworkThemeCacheKey(cacheKeyFor(track, slot), slot), CachedPaletteEntry(palette = null, seededTokens = tokens))
    }

    internal fun seedCacheKeyForTesting(
        key: String,
        tokens: DynamicThemeTokens?,
        slot: EmbeddedArtworkSlot = EmbeddedArtworkSlot.FRONT_COVER,
    ) {
        cache.put(ArtworkThemeCacheKey(key, slot), CachedPaletteEntry(palette = null, seededTokens = tokens))
    }

    internal fun seedPaletteForTesting(track: PlaybackTrack, slot: EmbeddedArtworkSlot, palette: ArtworkColorPalette?) {
        cache.put(ArtworkThemeCacheKey(cacheKeyFor(track, slot), slot), CachedPaletteEntry(palette = palette))
    }

    override fun clear() {
        cache.evictAll()
    }

    /**
     * Lazily loads and clusters ONLY the single requested [slot] for [track], caching both
     * chromatic and monochrome/missing outcomes under `(sourceKey, slot)`.
     */
    override suspend fun resolvePalette(track: PlaybackTrack?, slot: EmbeddedArtworkSlot): ArtworkColorPalette? {
        if (track == null) return null
        val sourceKey = cacheKeyFor(track, slot)
        val cacheKey = ArtworkThemeCacheKey(sourceKey, slot)
        cache.get(cacheKey)?.let { return it.palette }

        val bitmap = loadSlotBitmap(track, slot)
        return resolvePaletteFromBitmap(key = sourceKey, slot = slot, bitmap = bitmap)
    }

    override suspend fun resolveTokens(track: PlaybackTrack?): DynamicThemeTokens? = resolveTokens(
        track = track,
        slot = EmbeddedArtworkSlot.FRONT_COVER,
        intensity = DynamicThemeIntensity.SUBTLE,
    )

    override suspend fun resolveTokens(
        track: PlaybackTrack?,
        slot: EmbeddedArtworkSlot,
        intensity: DynamicThemeIntensity,
    ): DynamicThemeTokens? {
        if (track == null) return null
        val sourceKey = cacheKeyFor(track, slot)
        val cacheKey = ArtworkThemeCacheKey(sourceKey, slot)
        cache.get(cacheKey)?.let { return it.toTokens(intensity) }

        val bitmap = loadSlotBitmap(track, slot)
        val palette = resolvePaletteFromBitmap(key = sourceKey, slot = slot, bitmap = bitmap)
        return DynamicThemeSlotPolicy.toDynamicTokens(palette, intensity)
    }

    /**
     * Evaluates ordered [candidates] (`FRONT_COVER` slot) until a track with non-monochrome
     * chromatic artwork yields non-null [DynamicThemeTokens], caching both positive and negative hits.
     */
    suspend fun resolveFirstChromaticTokens(
        candidates: List<PlaybackTrack>,
        intensity: DynamicThemeIntensity = DynamicThemeIntensity.SUBTLE,
    ): DynamicThemeTokens? {
        for (candidate in candidates) {
            val tokens = resolveTokens(candidate, EmbeddedArtworkSlot.FRONT_COVER, intensity)
            if (tokens != null) return tokens
        }
        return null
    }

    /**
     * Evaluates ordered [candidates] (`FRONT_COVER` slot) until a chromatic [ArtworkColorPalette]
     * is found, caching each inspected candidate lazily.
     */
    suspend fun resolveFirstChromaticPalette(candidates: List<PlaybackTrack>): ArtworkColorPalette? {
        for (candidate in candidates) {
            val palette = resolvePalette(candidate, EmbeddedArtworkSlot.FRONT_COVER)
            if (palette != null && !palette.isMonochrome) return palette
        }
        return null
    }

    /**
     * Resolves a perceptual [ArtworkColorPalette] from an already-loaded [bitmap] keyed under
     * `(key, slot)` on `Dispatchers.Default` (<= 48x48 pixels).
     */
    suspend fun resolvePaletteFromBitmap(
        key: String,
        slot: EmbeddedArtworkSlot = EmbeddedArtworkSlot.FRONT_COVER,
        bitmap: Bitmap?,
    ): ArtworkColorPalette? {
        if (key.isBlank()) return null
        val cacheKey = ArtworkThemeCacheKey(key, slot)
        cache.get(cacheKey)?.let { return it.palette }
        if (bitmap == null) {
            cache.put(cacheKey, CachedPaletteEntry(palette = null))
            return null
        }

        val extracted = withContext(Dispatchers.Default) {
            extractPaletteFromBitmapSafely(bitmap)
        }
        cache.put(cacheKey, CachedPaletteEntry(palette = extracted))
        return extracted
    }

    suspend fun resolveTokensFromBitmap(
        key: String,
        bitmap: Bitmap?,
        slot: EmbeddedArtworkSlot = EmbeddedArtworkSlot.FRONT_COVER,
        intensity: DynamicThemeIntensity = DynamicThemeIntensity.SUBTLE,
    ): DynamicThemeTokens? {
        if (key.isBlank()) return null
        val cacheKey = ArtworkThemeCacheKey(key, slot)
        cache.get(cacheKey)?.let { return it.toTokens(intensity) }

        val palette = resolvePaletteFromBitmap(key = key, slot = slot, bitmap = bitmap)
        return DynamicThemeSlotPolicy.toDynamicTokens(palette, intensity)
    }

    /**
     * Resolves Artist Detail dynamic tokens using the unified monochrome-borrow policy:
     * tries the displayed hero [heroSlot] (`ARTIST` for custom/embedded artist art, or `FRONT_COVER`)
     * first, and if monochrome/absent borrows chromatic `FRONT_COVER` from [fallbackAlbumCandidates]
     * (ordered oldest-release-first) before defaulting to Monochrome Studio (`null`).
     */
    suspend fun resolveArtistHeroTokens(
        heroKey: String?,
        heroSlot: EmbeddedArtworkSlot,
        heroBitmap: Bitmap?,
        fallbackAlbumCandidates: List<PlaybackTrack>,
        intensity: DynamicThemeIntensity = DynamicThemeIntensity.SUBTLE,
    ): DynamicThemeTokens? {
        if (!heroKey.isNullOrBlank()) {
            val cachedHero = cache.get(ArtworkThemeCacheKey(heroKey, heroSlot))
            if (cachedHero?.seededTokens != null) {
                return cachedHero.seededTokens
            }
        }
        val palette = DynamicThemeSlotPolicy.resolveArtistPaletteWithFallback(
            loadArtistPalette = {
                if (heroKey.isNullOrBlank() || heroBitmap == null) {
                    null
                } else {
                    resolvePaletteFromBitmap(key = heroKey, slot = heroSlot, bitmap = heroBitmap)
                }
            },
            loadOldestAlbumFrontPalette = {
                if (heroSlot == EmbeddedArtworkSlot.ARTIST) {
                    resolveFirstChromaticPalette(fallbackAlbumCandidates)
                } else {
                    null
                }
            },
        )
        return DynamicThemeSlotPolicy.toDynamicTokens(palette, intensity)
    }

    /**
     * Resolves the 3-stop OLED spectrum triad for [SpectrumColorStyle.ALBUM_ADAPTIVE] directly from
     * the unified 4-slot OKLCH cache for the actively displayed Now Playing slot (`DISC` / `BACK_COVER`
     * / `FRONT_COVER` with monochrome borrow), eliminating duplicate bitmap scans.
     */
    suspend fun resolveSpectrumTriad(track: PlaybackTrack?, artworkStyle: NowPlayingArtworkStyle, isBackCoverVisible: Boolean): IntArray {
        val palette = DynamicThemeSlotPolicy.resolveTrackPaletteWithBorrow(
            track = track,
            artworkStyle = artworkStyle,
            isBackCoverVisible = isBackCoverVisible,
            loadSlotPalette = { t, slot -> resolvePalette(t, slot) },
        )
        return DynamicThemeSlotPolicy.toSpectrumTriad(palette)
    }

    /**
     * Resolves the 4-stop OLED spectrum palette `[spectrumLow, spectrumMid, spectrumHigh, highlightPeak]`
     * for [SpectrumColorStyle.ALBUM_ADAPTIVE] and multi-color Kinetic Sparks from the unified 4-slot
     * OKLCH cache for the actively displayed Now Playing slot.
     */
    suspend fun resolveSpectrumPalette(
        track: PlaybackTrack?,
        artworkStyle: NowPlayingArtworkStyle,
        isBackCoverVisible: Boolean,
    ): IntArray {
        val palette = DynamicThemeSlotPolicy.resolveTrackPaletteWithBorrow(
            track = track,
            artworkStyle = artworkStyle,
            isBackCoverVisible = isBackCoverVisible,
            loadSlotPalette = { t, slot -> resolvePalette(t, slot) },
        )
        return DynamicThemeSlotPolicy.toSpectrumPalette(palette)
    }

    private suspend fun loadSlotBitmap(track: PlaybackTrack, slot: EmbeddedArtworkSlot): Bitmap? = when (slot) {
        EmbeddedArtworkSlot.FRONT_COVER -> albumArtworkLoader.loadArtwork(track, AlbumArtworkLoader.THUMBNAIL_SIZE_PX)
        EmbeddedArtworkSlot.DISC -> albumArtworkLoader.loadTrackSlotBitmap(
            track = track,
            slot = EmbeddedArtworkSlot.DISC,
            targetSizePx = AlbumArtworkLoader.DISC_SIZE_PX,
        )
        EmbeddedArtworkSlot.BACK_COVER -> albumArtworkLoader.loadTrackSlotBitmap(
            track = track,
            slot = EmbeddedArtworkSlot.BACK_COVER,
            targetSizePx = AlbumArtworkLoader.HERO_SIZE_PX,
        )
        EmbeddedArtworkSlot.ARTIST -> albumArtworkLoader.loadTrackSlotBitmap(
            track = track,
            slot = EmbeddedArtworkSlot.ARTIST,
            targetSizePx = AlbumArtworkLoader.HERO_SIZE_PX,
        )
    }

    private fun extractPaletteFromBitmapSafely(bitmap: Bitmap): ArtworkColorPalette? {
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

    private fun extractScaledPixels(softwareBitmap: Bitmap): ArtworkColorPalette {
        val srcWidth = softwareBitmap.width
        val srcHeight = softwareBitmap.height
        val maxDim = maxOf(srcWidth, srcHeight)
        val scale = if (maxDim > MAX_SAMPLE_DIM_PX) MAX_SAMPLE_DIM_PX.toFloat() / maxDim.toFloat() else 1f
        val targetWidth = (srcWidth * scale).roundToInt().coerceAtLeast(1)
        val targetHeight = (srcHeight * scale).roundToInt().coerceAtLeast(1)

        val sampled = if (targetWidth != srcWidth || targetHeight != srcHeight) {
            softwareBitmap.scale(targetWidth, targetHeight)
        } else {
            softwareBitmap
        }
        try {
            val pixels = IntArray(targetWidth * targetHeight)
            sampled.getPixels(pixels, 0, targetWidth, 0, 0, targetWidth, targetHeight)
            return ArtworkPaletteExtractor.extractPalette(pixels, targetWidth, targetHeight)
        } finally {
            if (sampled !== softwareBitmap && !sampled.isRecycled) {
                sampled.recycle()
            }
        }
    }

    companion object {
        private const val MAX_CACHE_ENTRIES = 160
        private const val MAX_SAMPLE_DIM_PX = 48
    }
}
