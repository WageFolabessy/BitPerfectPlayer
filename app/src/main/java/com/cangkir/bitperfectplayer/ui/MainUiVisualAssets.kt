package com.cangkir.bitperfectplayer.ui

import com.cangkir.bitperfectplayer.library.AlbumArtworkLoader
import com.cangkir.bitperfectplayer.library.ArtistPhotoStore
import com.cangkir.bitperfectplayer.ui.theme.CoverThemeColorCache

/**
 * UI-layer media rendering assets injected separately from [MainViewModel] so the ViewModel
 * remains decoupled from Android bitmap caches and `AppContainer`.
 */
data class MainUiVisualAssets(
    val albumArtworkLoader: AlbumArtworkLoader,
    val artistPhotoStore: ArtistPhotoStore,
    val coverThemeColorCache: CoverThemeColorCache,
)
