package com.cangkir.bitperfectplayer.ui.theme

import com.cangkir.bitperfectplayer.audio.PlaybackTrack

interface CoverThemeResolver {
    fun cacheKeyFor(track: PlaybackTrack): String

    suspend fun resolveTokens(track: PlaybackTrack?): DynamicThemeTokens?

    fun clear()
}
