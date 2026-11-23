package com.cangkir.bitperfectplayer.ui

import android.Manifest
import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.core.app.ApplicationProvider
import com.cangkir.bitperfectplayer.audio.PlaybackControllerState
import com.cangkir.bitperfectplayer.audio.PlaybackTrack
import com.cangkir.bitperfectplayer.audio.SleepTimerState
import com.cangkir.bitperfectplayer.data.DefaultHomepage
import com.cangkir.bitperfectplayer.library.AlbumArtworkLoader
import com.cangkir.bitperfectplayer.library.LibraryGroup
import com.cangkir.bitperfectplayer.library.LibrarySnapshot
import com.cangkir.bitperfectplayer.library.PlaylistsUiSnapshot
import com.cangkir.bitperfectplayer.ui.library.LIBRARY_SUBTAB_PAGER_TEST_TAG
import com.cangkir.bitperfectplayer.ui.library.LibraryScreen
import com.cangkir.bitperfectplayer.ui.playlists.PlaylistsScreen
import com.cangkir.bitperfectplayer.ui.settings.SettingsScreen
import com.cangkir.bitperfectplayer.ui.theme.BitPerfectPlayerTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LibrarySubTabSwipeUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var artworkLoader: AlbumArtworkLoader

    private val baseTrack = PlaybackTrack(
        uriString = "content://test/audio/base.flac",
        title = "Base Song",
        artist = "Base Artist",
        album = "Base Album",
        folderName = "Base Folder",
        fileName = "01_base.flac",
        durationMs = 200_000L,
        sampleRateHz = 96_000,
        bitDepth = 24,
        container = "FLAC",
        codec = "FLAC",
    )

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(
            Manifest.permission.READ_MEDIA_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        artworkLoader = AlbumArtworkLoader(app)
    }

    private fun buildDistinctSnapshot(): LibrarySnapshot {
        val allSongsTrack = baseTrack.copy(
            uriString = "content://test/audio/song_all.flac",
            title = "All Songs Anthem",
            artist = "All Songs Singer",
            album = "All Songs LP",
            folderName = "All Songs Dir",
        )
        val folderTrack = baseTrack.copy(
            uriString = "content://test/audio/song_folder.flac",
            title = "Folder Track Item",
            artist = "Folder Creator",
            album = "Folder Disc",
            folderName = "Lossless Master Dir",
        )
        val artistTrack = baseTrack.copy(
            uriString = "content://test/audio/song_artist.flac",
            title = "Artist Track Item",
            artist = "Miles Coltrane Quartet",
            album = "Artist Disc",
            folderName = "Artist Dir",
        )
        val albumTrack = baseTrack.copy(
            uriString = "content://test/audio/song_album.flac",
            title = "Album Track Item",
            artist = "Album Band",
            album = "Midnight Masterpiece",
            folderName = "Album Dir",
        )
        val recentTrack = baseTrack.copy(
            uriString = "content://test/audio/song_recent.flac",
            title = "Recently Played Echo",
            artist = "Recent Artist",
            album = "Recent LP",
            folderName = "Recent Dir",
        )
        val favoriteTrack = baseTrack.copy(
            uriString = "content://test/audio/song_favorite.flac",
            title = "Beloved Favorite Ballad",
            artist = "Favorite Artist",
            album = "Favorite LP",
            folderName = "Favorite Dir",
        )
        return LibrarySnapshot(
            allSongs = listOf(allSongsTrack),
            folders = listOf(LibraryGroup("Lossless Master Dir", "1 track", listOf(folderTrack))),
            artists = listOf(LibraryGroup("Miles Coltrane Quartet", "1 song · 1 album", listOf(artistTrack))),
            albums = listOf(LibraryGroup("Midnight Masterpiece", "Album Band · 1 track", listOf(albumTrack))),
            recentlyPlayed = listOf(recentTrack),
            favorites = listOf(favoriteTrack),
        )
    }

    private fun buildScrollableSnapshot(): LibrarySnapshot = LibrarySnapshot(
        allSongs = (1..30).map { idx ->
            baseTrack.copy(
                uriString = "content://test/scroll/all_$idx.flac",
                title = "Track %02d".format(idx),
                fileName = "all_%02d.flac".format(idx),
            )
        },
        folders = (1..25).map { idx ->
            val t = baseTrack.copy(
                uriString = "content://test/scroll/folder_$idx.flac",
                title = "Folder Track %02d".format(idx),
                folderName = "Folder %02d".format(idx),
            )
            LibraryGroup("Folder %02d".format(idx), "1 track", listOf(t))
        },
        artists = (1..25).map { idx ->
            val t = baseTrack.copy(
                uriString = "content://test/scroll/artist_$idx.flac",
                title = "Artist Track %02d".format(idx),
                artist = "Artist %02d".format(idx),
            )
            LibraryGroup("Artist %02d".format(idx), "1 song · 1 album", listOf(t))
        },
        albums = (1..25).map { idx ->
            val t = baseTrack.copy(
                uriString = "content://test/scroll/album_$idx.flac",
                title = "Album Track %02d".format(idx),
                album = "Album %02d".format(idx),
            )
            LibraryGroup("Album %02d".format(idx), "Artist · 1 track", listOf(t))
        },
        recentlyPlayed = (1..25).map { idx ->
            baseTrack.copy(
                uriString = "content://test/scroll/recent_$idx.flac",
                title = "Recent %02d".format(idx),
            )
        },
        favorites = (1..25).map { idx ->
            baseTrack.copy(
                uriString = "content://test/scroll/fav_$idx.flac",
                title = "Fav %02d".format(idx),
            )
        },
    )

    private fun assertTabSelected(tab: LibraryTab) {
        composeRule.onNodeWithText(tab.label).performScrollTo().assertIsSelected()
    }

    @Test
    fun libraryTab_fromPageIndexClampsAndMapsAllSixTabs() {
        assertEquals(LibraryTab.ALL_SONGS, LibraryTab.fromPageIndex(-3))
        assertEquals(LibraryTab.ALL_SONGS, LibraryTab.fromPageIndex(0))
        assertEquals(LibraryTab.FOLDERS, LibraryTab.fromPageIndex(1))
        assertEquals(LibraryTab.ARTISTS, LibraryTab.fromPageIndex(2))
        assertEquals(LibraryTab.ALBUMS, LibraryTab.fromPageIndex(3))
        assertEquals(LibraryTab.RECENT, LibraryTab.fromPageIndex(4))
        assertEquals(LibraryTab.FAVORITES, LibraryTab.fromPageIndex(5))
        assertEquals(LibraryTab.FAVORITES, LibraryTab.fromPageIndex(99))

        assertEquals(LibraryTab.ALL_SONGS, DefaultHomepage.ALL_SONGS.toInitialLibraryTab())
        assertEquals(LibraryTab.FOLDERS, DefaultHomepage.FOLDERS.toInitialLibraryTab())
        assertEquals(LibraryTab.ARTISTS, DefaultHomepage.ARTISTS.toInitialLibraryTab())
        assertEquals(LibraryTab.ALBUMS, DefaultHomepage.ALBUMS.toInitialLibraryTab())
        assertEquals(LibraryTab.RECENT, DefaultHomepage.RECENT.toInitialLibraryTab())
        assertEquals(LibraryTab.FAVORITES, DefaultHomepage.FAVORITES.toInitialLibraryTab())
        assertEquals(LibraryTab.ALL_SONGS, DefaultHomepage.PLAYLISTS.toInitialLibraryTab())
    }

    @Test
    @Config(qualifiers = "w411dp-h915dp")
    fun libraryScreen_horizontalSwipeNavigatesAllSixSubTabsBidirectionallyAndUpdatesSelectedTab() {
        val snapshot = buildDistinctSnapshot()

        composeRule.setContent {
            BitPerfectPlayerTheme {
                LibraryScreen(
                    libraryState = snapshot,
                    favoriteTrackUris = emptySet(),
                    playbackState = PlaybackControllerState(),
                    albumArtworkLoader = artworkLoader,
                    onRefreshLibrary = {},
                    onImportFolder = {},
                    onPlayTracks = { _, _, _ -> },
                    onEnqueueTrack = {},
                    onEnqueueNext = {},
                    onEnqueueTracks = { _, _ -> },
                    onEnqueueTracksNext = { _, _ -> },
                    onSetTrackSort = { _, _ -> },
                    onSetGroupSort = { _, _ -> },
                    onAddToPlaylist = {},
                    onShowTrackInfo = {},
                    onToggleFavorite = {},
                    onOpenDiagnostics = {},
                )
            }
        }
        composeRule.waitForIdle()

        assertTabSelected(LibraryTab.ALL_SONGS)
        composeRule.onNodeWithText("All Songs Anthem").assertIsDisplayed()

        // Forward swipes: All Songs -> Folders -> Artists -> Albums -> Recent -> Favorites
        composeRule.onNodeWithTag(LIBRARY_SUBTAB_PAGER_TEST_TAG).performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        assertTabSelected(LibraryTab.FOLDERS)
        composeRule.onNodeWithText("All Songs").assertIsNotSelected()
        composeRule.onNodeWithText("Lossless Master Dir").assertIsDisplayed()

        composeRule.onNodeWithTag(LIBRARY_SUBTAB_PAGER_TEST_TAG).performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        assertTabSelected(LibraryTab.ARTISTS)
        composeRule.onNodeWithText("Miles Coltrane Quartet").assertIsDisplayed()

        composeRule.onNodeWithTag(LIBRARY_SUBTAB_PAGER_TEST_TAG).performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        assertTabSelected(LibraryTab.ALBUMS)
        composeRule.onNodeWithText("Midnight Masterpiece").assertIsDisplayed()

        composeRule.onNodeWithTag(LIBRARY_SUBTAB_PAGER_TEST_TAG).performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        assertTabSelected(LibraryTab.RECENT)
        composeRule.onNodeWithText("Recently Played Echo").assertIsDisplayed()

        composeRule.onNodeWithTag(LIBRARY_SUBTAB_PAGER_TEST_TAG).performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        assertTabSelected(LibraryTab.FAVORITES)
        composeRule.onNodeWithText("Beloved Favorite Ballad").assertIsDisplayed()

        // Reverse swipes: Favorites -> Recent -> Albums -> Artists -> Folders -> All Songs
        composeRule.onNodeWithTag(LIBRARY_SUBTAB_PAGER_TEST_TAG).performTouchInput { swipeRight() }
        composeRule.waitForIdle()
        assertTabSelected(LibraryTab.RECENT)
        composeRule.onNodeWithText("Recently Played Echo").assertIsDisplayed()

        composeRule.onNodeWithTag(LIBRARY_SUBTAB_PAGER_TEST_TAG).performTouchInput { swipeRight() }
        composeRule.waitForIdle()
        assertTabSelected(LibraryTab.ALBUMS)
        composeRule.onNodeWithText("Midnight Masterpiece").assertIsDisplayed()

        composeRule.onNodeWithTag(LIBRARY_SUBTAB_PAGER_TEST_TAG).performTouchInput { swipeRight() }
        composeRule.waitForIdle()
        assertTabSelected(LibraryTab.ARTISTS)
        composeRule.onNodeWithText("Miles Coltrane Quartet").assertIsDisplayed()

        composeRule.onNodeWithTag(LIBRARY_SUBTAB_PAGER_TEST_TAG).performTouchInput { swipeRight() }
        composeRule.waitForIdle()
        assertTabSelected(LibraryTab.FOLDERS)
        composeRule.onNodeWithText("Lossless Master Dir").assertIsDisplayed()

        composeRule.onNodeWithTag(LIBRARY_SUBTAB_PAGER_TEST_TAG).performTouchInput { swipeRight() }
        composeRule.waitForIdle()
        assertTabSelected(LibraryTab.ALL_SONGS)
        composeRule.onNodeWithText("All Songs Anthem").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w411dp-h915dp")
    fun libraryScreen_tappingSubTabHeadersAnimatesPagerCleanlyAcrossAdjacentAndNonAdjacentTabs() {
        val snapshot = buildDistinctSnapshot()

        composeRule.setContent {
            BitPerfectPlayerTheme {
                LibraryScreen(
                    libraryState = snapshot,
                    favoriteTrackUris = emptySet(),
                    playbackState = PlaybackControllerState(),
                    albumArtworkLoader = artworkLoader,
                    onRefreshLibrary = {},
                    onImportFolder = {},
                    onPlayTracks = { _, _, _ -> },
                    onEnqueueTrack = {},
                    onEnqueueNext = {},
                    onEnqueueTracks = { _, _ -> },
                    onEnqueueTracksNext = { _, _ -> },
                    onSetTrackSort = { _, _ -> },
                    onSetGroupSort = { _, _ -> },
                    onAddToPlaylist = {},
                    onShowTrackInfo = {},
                    onToggleFavorite = {},
                    onOpenDiagnostics = {},
                )
            }
        }
        composeRule.waitForIdle()

        // Non-adjacent jump: All Songs -> Albums
        composeRule.onNodeWithText("Albums").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertTabSelected(LibraryTab.ALBUMS)
        composeRule.onNodeWithText("Midnight Masterpiece").assertIsDisplayed()

        // Jump forward to Favorites
        composeRule.onNodeWithText("Favorites").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertTabSelected(LibraryTab.FAVORITES)
        composeRule.onNodeWithText("Beloved Favorite Ballad").assertIsDisplayed()

        // Jump backward to Folders, then swipe left to Artists without bouncing back
        composeRule.onNodeWithText("Folders").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertTabSelected(LibraryTab.FOLDERS)
        composeRule.onNodeWithText("Lossless Master Dir").assertIsDisplayed()

        composeRule.onNodeWithTag(LIBRARY_SUBTAB_PAGER_TEST_TAG).performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        assertTabSelected(LibraryTab.ARTISTS)
        composeRule.onNodeWithText("Miles Coltrane Quartet").assertIsDisplayed()

        // Jump to Recent, then back to All Songs
        composeRule.onNodeWithText("Recent").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertTabSelected(LibraryTab.RECENT)
        composeRule.onNodeWithText("Recently Played Echo").assertIsDisplayed()

        composeRule.onNodeWithText("All Songs").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertTabSelected(LibraryTab.ALL_SONGS)
        composeRule.onNodeWithText("All Songs Anthem").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w411dp-h915dp")
    fun libraryScreen_preservesIndependentScrollPositionsSearchAndDrillDownAcrossSwipes() {
        val snapshot = buildScrollableSnapshot()

        composeRule.setContent {
            BitPerfectPlayerTheme {
                LibraryScreen(
                    libraryState = snapshot,
                    favoriteTrackUris = emptySet(),
                    playbackState = PlaybackControllerState(),
                    albumArtworkLoader = artworkLoader,
                    onRefreshLibrary = {},
                    onImportFolder = {},
                    onPlayTracks = { _, _, _ -> },
                    onEnqueueTrack = {},
                    onEnqueueNext = {},
                    onEnqueueTracks = { _, _ -> },
                    onEnqueueTracksNext = { _, _ -> },
                    onSetTrackSort = { _, _ -> },
                    onSetGroupSort = { _, _ -> },
                    onAddToPlaylist = {},
                    onShowTrackInfo = {},
                    onToggleFavorite = {},
                    onOpenDiagnostics = {},
                )
            }
        }
        composeRule.waitForIdle()

        // Scroll All Songs down to index 18 (Track 19)
        composeRule.onNode(hasScrollToIndexAction() and !hasTestTag(LIBRARY_SUBTAB_PAGER_TEST_TAG))
            .performScrollToIndex(18)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Track 19").assertIsDisplayed()

        // Swipe to Folders, scroll down to index 15 (Folder 16), drill down and return
        composeRule.onNodeWithTag(LIBRARY_SUBTAB_PAGER_TEST_TAG).performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        composeRule.onNode(hasScrollToIndexAction() and !hasTestTag(LIBRARY_SUBTAB_PAGER_TEST_TAG))
            .performScrollToIndex(15)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Folder 16").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Folder Track 16").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Back to Folders").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertTabSelected(LibraryTab.FOLDERS)
        composeRule.onNodeWithText("Folder 16").assertIsDisplayed()

        // Swipe through Artists -> Albums -> Recent -> Favorites and scroll Favorites to index 14 (Fav 15)
        repeat(4) {
            composeRule.onNodeWithTag(LIBRARY_SUBTAB_PAGER_TEST_TAG).performTouchInput { swipeLeft() }
            composeRule.waitForIdle()
        }
        assertTabSelected(LibraryTab.FAVORITES)
        composeRule.onNode(hasScrollToIndexAction() and !hasTestTag(LIBRARY_SUBTAB_PAGER_TEST_TAG))
            .performScrollToIndex(14)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Fav 15").assertIsDisplayed()

        // Swipe back to Folders and All Songs -> independent scroll positions preserved!
        repeat(4) {
            composeRule.onNodeWithTag(LIBRARY_SUBTAB_PAGER_TEST_TAG).performTouchInput { swipeRight() }
            composeRule.waitForIdle()
        }
        assertTabSelected(LibraryTab.FOLDERS)
        composeRule.onNodeWithText("Folder 16").assertIsDisplayed()

        composeRule.onNodeWithTag(LIBRARY_SUBTAB_PAGER_TEST_TAG).performTouchInput { swipeRight() }
        composeRule.waitForIdle()
        assertTabSelected(LibraryTab.ALL_SONGS)
        composeRule.onNodeWithText("Track 19").assertIsDisplayed()

        // Search state in All Songs also survives swipes to another tab and back
        composeRule.onNode(hasSetTextAction()).performTextInput("Track 27")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("1 of 30 tracks").assertIsDisplayed()
        composeRule.onNode(hasText("Track 27") and !hasSetTextAction()).assertIsDisplayed()

        composeRule.onNodeWithTag(LIBRARY_SUBTAB_PAGER_TEST_TAG).performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        assertTabSelected(LibraryTab.FOLDERS)

        composeRule.onNodeWithTag(LIBRARY_SUBTAB_PAGER_TEST_TAG).performTouchInput { swipeRight() }
        composeRule.waitForIdle()
        assertTabSelected(LibraryTab.ALL_SONGS)
        composeRule.onNodeWithText("1 of 30 tracks").assertIsDisplayed()
        composeRule.onNode(hasText("Track 27") and !hasSetTextAction()).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w411dp-h915dp")
    fun startupNavHost_initialPagerPageHonorsDefaultHomepageAndRestoresOnReturnWithoutHijacking() {
        val snapshot = buildDistinctSnapshot()
        var homepagePref by mutableStateOf(DefaultHomepage.ARTISTS)

        composeRule.setContent {
            BitPerfectPlayerTheme {
                BitPerfectStartupNavHost(
                    startupHomepage = homepagePref,
                    libraryContent = { initialTab ->
                        LibraryScreen(
                            libraryState = snapshot,
                            favoriteTrackUris = emptySet(),
                            playbackState = PlaybackControllerState(),
                            albumArtworkLoader = artworkLoader,
                            onRefreshLibrary = {},
                            onImportFolder = {},
                            onPlayTracks = { _, _, _ -> },
                            onEnqueueTrack = {},
                            onEnqueueNext = {},
                            onEnqueueTracks = { _, _ -> },
                            onEnqueueTracksNext = { _, _ -> },
                            onSetTrackSort = { _, _ -> },
                            onSetGroupSort = { _, _ -> },
                            onAddToPlaylist = {},
                            onShowTrackInfo = {},
                            onToggleFavorite = {},
                            onOpenDiagnostics = {},
                            initialTab = initialTab,
                        )
                    },
                    playlistsContent = {
                        PlaylistsScreen(
                            playlistsState = PlaylistsUiSnapshot(isLoaded = true),
                            activeTrackUri = null,
                            albumArtworkLoader = artworkLoader,
                            onPlayTracks = { _, _, _ -> },
                            onEnqueueTracks = { _, _ -> },
                            onEnqueueTracksNext = { _, _ -> },
                            onSetPlaylistSort = { _, _ -> },
                            onCreatePlaylist = {},
                            onRenamePlaylist = { _, _ -> },
                            onDeletePlaylist = { _, _ -> },
                            onRemovePlaylistEntry = { _, _ -> },
                            onMovePlaylistEntry = { _, _, _ -> },
                            onExportPlaylist = { _, _ -> },
                            onImportPlaylist = {},
                        )
                    },
                    settingsContent = {
                        SettingsScreen(
                            libraryState = snapshot,
                            playbackState = PlaybackControllerState(),
                            sleepTimerStateFlow = MutableStateFlow(SleepTimerState()),
                            defaultHomepage = homepagePref,
                            onSetDefaultHomepage = { homepagePref = it },
                            onImportFolder = {},
                            onRemoveSafFolder = {},
                            onRefreshLibrary = {},
                            onSetGaplessEnabled = {},
                            onSetReplayGainMode = {},
                            onSetReplayGainPreampDb = {},
                            onSetReplayGainPreventClipping = {},
                            onSelectSleepTimerPreset = {},
                            onExportLocalBackup = {},
                            onImportLocalBackup = {},
                            onOpenDiagnostics = {},
                        )
                    },
                )
            }
        }
        composeRule.waitForIdle()

        // Cold startup lands directly on Artists tab inside HorizontalPager
        assertTabSelected(LibraryTab.ARTISTS)
        composeRule.onNodeWithText("Miles Coltrane Quartet").assertIsDisplayed()

        // Swipe left to Albums
        composeRule.onNodeWithTag(LIBRARY_SUBTAB_PAGER_TEST_TAG).performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        assertTabSelected(LibraryTab.ALBUMS)
        composeRule.onNodeWithText("Midnight Masterpiece").assertIsDisplayed()

        // Switch to Settings, change DefaultHomepage -> must NOT hijack Settings or reset Library tab
        composeRule.onNodeWithContentDescription("Settings").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("AUDIO ENGINE").assertIsDisplayed()

        homepagePref = DefaultHomepage.FAVORITES
        composeRule.waitForIdle()
        composeRule.onNodeWithText("AUDIO ENGINE").assertIsDisplayed()

        // Return to Library -> previously swiped Albums page stays preserved cleanly
        composeRule.onNodeWithContentDescription("Library").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertTabSelected(LibraryTab.ALBUMS)
        composeRule.onNodeWithText("Midnight Masterpiece").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w411dp-h915dp")
    fun libraryScreen_defaultHomepageRecentInitializesPagerOnRecentAndSwipesBothWays() {
        val snapshot = buildDistinctSnapshot()

        composeRule.setContent {
            BitPerfectPlayerTheme {
                LibraryScreen(
                    libraryState = snapshot,
                    favoriteTrackUris = emptySet(),
                    playbackState = PlaybackControllerState(),
                    albumArtworkLoader = artworkLoader,
                    onRefreshLibrary = {},
                    onImportFolder = {},
                    onPlayTracks = { _, _, _ -> },
                    onEnqueueTrack = {},
                    onEnqueueNext = {},
                    onEnqueueTracks = { _, _ -> },
                    onEnqueueTracksNext = { _, _ -> },
                    onSetTrackSort = { _, _ -> },
                    onSetGroupSort = { _, _ -> },
                    onAddToPlaylist = {},
                    onShowTrackInfo = {},
                    onToggleFavorite = {},
                    onOpenDiagnostics = {},
                    initialTab = DefaultHomepage.RECENT.toInitialLibraryTab(),
                )
            }
        }
        composeRule.waitForIdle()

        assertTabSelected(LibraryTab.RECENT)
        composeRule.onNodeWithText("Recently Played Echo").assertIsDisplayed()

        // Swipe left -> Favorites
        composeRule.onNodeWithTag(LIBRARY_SUBTAB_PAGER_TEST_TAG).performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        assertTabSelected(LibraryTab.FAVORITES)
        composeRule.onNodeWithText("Beloved Favorite Ballad").assertIsDisplayed()

        // Swipe right twice -> Recent -> Albums
        composeRule.onNodeWithTag(LIBRARY_SUBTAB_PAGER_TEST_TAG).performTouchInput { swipeRight() }
        composeRule.waitForIdle()
        assertTabSelected(LibraryTab.RECENT)
        composeRule.onNodeWithTag(LIBRARY_SUBTAB_PAGER_TEST_TAG).performTouchInput { swipeRight() }
        composeRule.waitForIdle()
        assertTabSelected(LibraryTab.ALBUMS)
        composeRule.onNodeWithText("Midnight Masterpiece").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w411dp-h915dp")
    fun bottomNavigationDestinations_remainStrictlyNonSwipeable() {
        val snapshot = buildDistinctSnapshot()

        composeRule.setContent {
            BitPerfectPlayerTheme {
                BitPerfectStartupNavHost(
                    startupHomepage = DefaultHomepage.ALL_SONGS,
                    libraryContent = { initialTab ->
                        LibraryScreen(
                            libraryState = snapshot,
                            favoriteTrackUris = emptySet(),
                            playbackState = PlaybackControllerState(),
                            albumArtworkLoader = artworkLoader,
                            onRefreshLibrary = {},
                            onImportFolder = {},
                            onPlayTracks = { _, _, _ -> },
                            onEnqueueTrack = {},
                            onEnqueueNext = {},
                            onEnqueueTracks = { _, _ -> },
                            onEnqueueTracksNext = { _, _ -> },
                            onSetTrackSort = { _, _ -> },
                            onSetGroupSort = { _, _ -> },
                            onAddToPlaylist = {},
                            onShowTrackInfo = {},
                            onToggleFavorite = {},
                            onOpenDiagnostics = {},
                            initialTab = initialTab,
                        )
                    },
                    playlistsContent = {
                        PlaylistsScreen(
                            playlistsState = PlaylistsUiSnapshot(isLoaded = true),
                            activeTrackUri = null,
                            albumArtworkLoader = artworkLoader,
                            onPlayTracks = { _, _, _ -> },
                            onEnqueueTracks = { _, _ -> },
                            onEnqueueTracksNext = { _, _ -> },
                            onSetPlaylistSort = { _, _ -> },
                            onCreatePlaylist = {},
                            onRenamePlaylist = { _, _ -> },
                            onDeletePlaylist = { _, _ -> },
                            onRemovePlaylistEntry = { _, _ -> },
                            onMovePlaylistEntry = { _, _, _ -> },
                            onExportPlaylist = { _, _ -> },
                            onImportPlaylist = {},
                        )
                    },
                    settingsContent = {
                        SettingsScreen(
                            libraryState = snapshot,
                            playbackState = PlaybackControllerState(),
                            sleepTimerStateFlow = MutableStateFlow(SleepTimerState()),
                            onImportFolder = {},
                            onRemoveSafFolder = {},
                            onRefreshLibrary = {},
                            onSetGaplessEnabled = {},
                            onSetReplayGainMode = {},
                            onSetReplayGainPreampDb = {},
                            onSetReplayGainPreventClipping = {},
                            onSelectSleepTimerPreset = {},
                            onExportLocalBackup = {},
                            onImportLocalBackup = {},
                            onOpenDiagnostics = {},
                        )
                    },
                )
            }
        }
        composeRule.waitForIdle()

        // Swiping past left boundary of All Songs stays in Library (never switches to Playlists/Settings)
        composeRule.onNodeWithTag(LIBRARY_SUBTAB_PAGER_TEST_TAG).performTouchInput { swipeRight() }
        composeRule.waitForIdle()
        assertTabSelected(LibraryTab.ALL_SONGS)
        composeRule.onNodeWithText("All Songs Anthem").assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithText("YOUR PLAYLISTS").fetchSemanticsNodes().size)

        // Swiping past right boundary of Favorites stays in Library (never switches to Playlists/Settings)
        composeRule.onNodeWithText("Favorites").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(LIBRARY_SUBTAB_PAGER_TEST_TAG).performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        assertTabSelected(LibraryTab.FAVORITES)
        composeRule.onNodeWithText("Beloved Favorite Ballad").assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithText("YOUR PLAYLISTS").fetchSemanticsNodes().size)

        // Bottom-nav Playlists destination is strictly non-swipeable
        composeRule.onNodeWithContentDescription("Playlists").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("YOUR PLAYLISTS").assertIsDisplayed()
        composeRule.onNodeWithText("YOUR PLAYLISTS").performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("YOUR PLAYLISTS").assertIsDisplayed()
        composeRule.onNodeWithText("YOUR PLAYLISTS").performTouchInput { swipeRight() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("YOUR PLAYLISTS").assertIsDisplayed()

        // Bottom-nav Settings destination is strictly non-swipeable
        composeRule.onNodeWithContentDescription("Settings").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("AUDIO ENGINE").assertIsDisplayed()
        composeRule.onNodeWithText("AUDIO ENGINE").performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("AUDIO ENGINE").assertIsDisplayed()
        composeRule.onNodeWithText("AUDIO ENGINE").performTouchInput { swipeRight() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("AUDIO ENGINE").assertIsDisplayed()
    }
}
