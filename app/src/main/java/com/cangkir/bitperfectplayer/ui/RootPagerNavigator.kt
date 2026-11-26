package com.cangkir.bitperfectplayer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

@Stable
internal class RootPagerNavigator {
    var openNowPlaying: () -> Unit = {}
    var openSettingsFromNowPlaying: () -> Unit = {}
    var backToContent: () -> Unit = {}
    var isLibraryDetailOpen: Boolean by mutableStateOf(false)
    var isPlaylistsDetailOpen: Boolean by mutableStateOf(false)
    var isLibraryPageForeground: Boolean by mutableStateOf(true)
    var isPlaylistsPageForeground: Boolean by mutableStateOf(false)
}

@Composable
internal fun rememberRootPagerNavigator(): RootPagerNavigator = remember { RootPagerNavigator() }
