package com.cangkir.bitperfectplayer.ui

import com.cangkir.bitperfectplayer.data.DefaultHomepage

enum class LibraryTab(val label: String) {
    ALL_SONGS("All Songs"),
    FOLDERS("Folders"),
    ARTISTS("Artists"),
    ALBUMS("Albums"),
    RECENT("Recent"),
    FAVORITES("Favorites"),
    ;

    companion object {
        fun fromPageIndex(pageIndex: Int): LibraryTab = entries[pageIndex.coerceIn(0, entries.lastIndex)]
    }
}

/**
 * Resolves the initial [LibraryTab] corresponding to a [DefaultHomepage] preference.
 * When [DefaultHomepage.PLAYLISTS] is selected, the app starts on the Playlists route while
 * Library defaults cleanly to [LibraryTab.ALL_SONGS] if the user later navigates there.
 */
fun DefaultHomepage.toInitialLibraryTab(): LibraryTab = when (this) {
    DefaultHomepage.ALL_SONGS -> LibraryTab.ALL_SONGS
    DefaultHomepage.FOLDERS -> LibraryTab.FOLDERS
    DefaultHomepage.ARTISTS -> LibraryTab.ARTISTS
    DefaultHomepage.ALBUMS -> LibraryTab.ALBUMS
    DefaultHomepage.RECENT -> LibraryTab.RECENT
    DefaultHomepage.FAVORITES -> LibraryTab.FAVORITES
    DefaultHomepage.PLAYLISTS -> LibraryTab.ALL_SONGS
}
