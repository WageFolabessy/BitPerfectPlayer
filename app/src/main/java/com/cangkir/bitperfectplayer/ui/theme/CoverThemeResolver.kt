package com.cangkir.bitperfectplayer.ui.theme

import com.cangkir.bitperfectplayer.audio.PlaybackTrack
import com.cangkir.bitperfectplayer.library.EmbeddedArtworkSlot

interface CoverThemeResolver {
    fun cacheKeyFor(track: PlaybackTrack): String

    fun cacheKeyFor(track: PlaybackTrack, slot: EmbeddedArtworkSlot): String = "${cacheKeyFor(track)}|slot:${slot.name}"

    fun supportsMultiSlotPalettes(): Boolean = false

    suspend fun resolveTokens(track: PlaybackTrack?): DynamicThemeTokens?

    suspend fun resolveTokens(
        track: PlaybackTrack?,
        slot: EmbeddedArtworkSlot,
        intensity: DynamicThemeIntensity = DynamicThemeIntensity.SUBTLE,
    ): DynamicThemeTokens? = if (slot == EmbeddedArtworkSlot.FRONT_COVER) {
        resolveTokens(track)
    } else {
        resolvePalette(track, slot)?.let { CoverThemePaletteGenerator.generateTokensFromPalette(it, intensity) }
    }

    suspend fun resolvePalette(track: PlaybackTrack?, slot: EmbeddedArtworkSlot): ArtworkColorPalette? = null

    fun clear()
}
