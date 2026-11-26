package com.cangkir.bitperfectplayer.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.core.graphics.createBitmap
import androidx.test.core.app.ApplicationProvider
import com.cangkir.bitperfectplayer.audio.EnginePlaybackState
import com.cangkir.bitperfectplayer.audio.PlaybackControllerState
import com.cangkir.bitperfectplayer.audio.PlaybackStatus
import com.cangkir.bitperfectplayer.audio.PlaybackTrack
import com.cangkir.bitperfectplayer.audio.QueueSnapshot
import com.cangkir.bitperfectplayer.audio.SleepTimerState
import com.cangkir.bitperfectplayer.data.NowPlayingArtworkStyle
import com.cangkir.bitperfectplayer.data.SpectrumColorStyle
import com.cangkir.bitperfectplayer.library.AlbumArtworkLoader
import com.cangkir.bitperfectplayer.library.EmbeddedArtworkSlot
import com.cangkir.bitperfectplayer.library.LibraryGroup
import com.cangkir.bitperfectplayer.library.LibrarySnapshot
import com.cangkir.bitperfectplayer.library.SortDirection
import com.cangkir.bitperfectplayer.library.TrackSortOption
import com.cangkir.bitperfectplayer.library.TrackSortSpec
import com.cangkir.bitperfectplayer.ui.library.ArtistDetailView
import com.cangkir.bitperfectplayer.ui.nowplaying.NowPlayingScreen
import com.cangkir.bitperfectplayer.ui.settings.SettingsScreen
import com.cangkir.bitperfectplayer.ui.theme.BitPerfectExtendedColors
import com.cangkir.bitperfectplayer.ui.theme.BitPerfectPlayerTheme
import com.cangkir.bitperfectplayer.ui.theme.CoralWarning
import com.cangkir.bitperfectplayer.ui.theme.CoverThemeColorCache
import com.cangkir.bitperfectplayer.ui.theme.CoverThemePaletteGenerator
import com.cangkir.bitperfectplayer.ui.theme.DynamicThemeIntensity
import com.cangkir.bitperfectplayer.ui.theme.DynamicThemeTokens
import com.cangkir.bitperfectplayer.ui.theme.EmeraldSafe
import com.cangkir.bitperfectplayer.ui.theme.LocalBitPerfectExtendedColors
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h915dp")
class DynamicThemeAndSpectrumUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var artworkLoader: AlbumArtworkLoader
    private lateinit var themeCache: CoverThemeColorCache

    private val hiResTrack = PlaybackTrack(
        uriString = "content://test/audio/oklch_studio.flac",
        title = "Blue & Gold",
        artist = "Studio Ensemble",
        album = "Chromatic Sessions",
        fileName = "01 - Blue & Gold.flac",
        durationMs = 240_000L,
        sampleRateHz = 96_000,
        bitDepth = 24,
        container = "FLAC",
        codec = "FLAC",
        year = 2022,
    )

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        artworkLoader = AlbumArtworkLoader(app)
        themeCache = CoverThemeColorCache(artworkLoader)
    }

    private fun solidBitmap(argb: Int, size: Int = 64): Bitmap = createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
        eraseColor(argb)
    }

    @Test
    fun settingsScreen_themeIntensitySelectorVisibleOnlyWhenDynamicThemeEnabledAndPersistsSelection() {
        var dynamicEnabled by mutableStateOf(false)
        var selectedIntensity by mutableStateOf(DynamicThemeIntensity.VIBRANT)

        composeRule.setContent {
            BitPerfectPlayerTheme {
                SettingsScreen(
                    libraryState = LibrarySnapshot(),
                    playbackState = PlaybackControllerState(),
                    sleepTimerStateFlow = MutableStateFlow(SleepTimerState()),
                    dynamicCoverThemeEnabled = dynamicEnabled,
                    onSetDynamicCoverThemeEnabled = { dynamicEnabled = it },
                    dynamicThemeIntensity = selectedIntensity,
                    onSetDynamicThemeIntensity = { selectedIntensity = it },
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
            }
        }

        // Scroll to Dynamic Theme toggle inside Settings LazyColumn.
        composeRule.onNode(androidx.compose.ui.test.hasScrollAction())
            .performScrollToNode(hasContentDescription("Toggle dynamic theme"))
        composeRule.waitForIdle()

        // When Dynamic Theme is OFF, Theme Intensity selector must stay completely hidden.
        assertEquals(0, composeRule.onAllNodesWithContentDescription("Theme intensity Subtle").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithContentDescription("Theme intensity Vibrant").fetchSemanticsNodes().size)

        // Toggle Dynamic Theme ON -> Subtle / Vibrant 2-segment control appears cleanly underneath.
        composeRule.onNodeWithContentDescription("Toggle dynamic theme").performClick()
        composeRule.waitForIdle()
        assertTrue(dynamicEnabled)
        composeRule.onNode(androidx.compose.ui.test.hasScrollAction())
            .performScrollToNode(hasContentDescription("Theme intensity Subtle"))
        composeRule.onNodeWithText("Theme Intensity").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Theme intensity Subtle").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Theme intensity Vibrant").assertIsDisplayed()

        // Selecting Subtle updates callback immediately.
        composeRule.onNodeWithContentDescription("Theme intensity Subtle").performClick()
        composeRule.waitForIdle()
        assertEquals(DynamicThemeIntensity.SUBTLE, selectedIntensity)

        // Selecting Vibrant updates callback immediately.
        composeRule.onNodeWithContentDescription("Theme intensity Vibrant").performClick()
        composeRule.waitForIdle()
        assertEquals(DynamicThemeIntensity.VIBRANT, selectedIntensity)

        // Turning Dynamic Theme OFF hides Theme Intensity again.
        composeRule.onNodeWithContentDescription("Toggle dynamic theme").performClick()
        composeRule.waitForIdle()
        assertFalse(dynamicEnabled)
        assertEquals(0, composeRule.onAllNodesWithContentDescription("Theme intensity Subtle").fetchSemanticsNodes().size)
    }

    @Test
    fun bitPerfectPlayerTheme_exposesExtendedColorsAndPreservesSemanticDiagnosticColors() {
        val vibrantTokens = CoverThemePaletteGenerator.generateTokensFromHue(
            hueDeg = 225f,
            sourceSaturation = 0.90f,
            intensity = DynamicThemeIntensity.VIBRANT,
        )
        var activeTokens by mutableStateOf<DynamicThemeTokens?>(null)
        var observedExtended: BitPerfectExtendedColors? = null
        var observedTertiary: Color? = null
        var observedError: Color? = null

        composeRule.setContent {
            BitPerfectPlayerTheme(dynamicTokens = activeTokens) {
                observedExtended = LocalBitPerfectExtendedColors.current
                observedTertiary = MaterialTheme.colorScheme.tertiary
                observedError = MaterialTheme.colorScheme.error
            }
        }

        composeRule.waitForIdle()
        val inactiveExtended = checkNotNull(observedExtended)
        assertFalse(inactiveExtended.isDynamicActive)
        assertEquals(EmeraldSafe, observedTertiary)
        assertEquals(CoralWarning, observedError)

        activeTokens = vibrantTokens
        composeRule.mainClock.advanceTimeBy(350L)
        composeRule.waitForIdle()
        val activeExtended = checkNotNull(observedExtended)
        assertTrue(activeExtended.isDynamicActive)
        assertEquals(DynamicThemeIntensity.VIBRANT, activeExtended.intensity)
        assertEquals(Color(vibrantTokens.highlightPeakArgb), activeExtended.highlightPeak)
        assertEquals(Color(vibrantTokens.glowPrimaryArgb), activeExtended.glowPrimary)
        assertEquals(Color(vibrantTokens.glowSecondaryArgb), activeExtended.glowSecondary)
        // Diagnostic safety indicators MUST never drift under dynamic themes.
        assertEquals(EmeraldSafe, observedTertiary)
        assertEquals(CoralWarning, observedError)
    }

    @Test
    fun nowPlayingScreen_rendersAmbientGlowAndNotifiesBackCoverFlipAcrossVinylAndSquareModes() {
        val discBitmap = solidBitmap(0xFFE5A93B.toInt())
        val frontBitmap = solidBitmap(0xFF1D4ED8.toInt())
        val backBitmap = solidBitmap(0xFFD9363E.toInt())
        artworkLoader.seedArtworkForTesting(hiResTrack, frontBitmap, AlbumArtworkLoader.THUMBNAIL_SIZE_PX)
        artworkLoader.seedArtworkForTesting(hiResTrack, frontBitmap, AlbumArtworkLoader.HERO_SIZE_PX)
        artworkLoader.seedSlotBitmapForTesting(hiResTrack, EmbeddedArtworkSlot.DISC, discBitmap, AlbumArtworkLoader.DISC_SIZE_PX)
        artworkLoader.seedSlotBitmapForTesting(hiResTrack, EmbeddedArtworkSlot.BACK_COVER, backBitmap, AlbumArtworkLoader.HERO_SIZE_PX)

        val dynamicTokens = CoverThemePaletteGenerator.generateTokensFromHue(220f, 0.90f, DynamicThemeIntensity.VIBRANT)
        var style by mutableStateOf(NowPlayingArtworkStyle.VINYL_DISC)
        val backFlipEvents = mutableListOf<Boolean>()

        val playbackState = PlaybackControllerState(
            queue = QueueSnapshot(items = listOf(hiResTrack), currentIndex = 0),
            activeTrack = hiResTrack,
            engineState = EnginePlaybackState(status = PlaybackStatus.PAUSED),
        )

        composeRule.setContent {
            BitPerfectPlayerTheme(dynamicTokens = dynamicTokens) {
                NowPlayingScreen(
                    playbackState = playbackState,
                    playbackPositionMsFlow = MutableStateFlow(12_000L),
                    sleepTimerStateFlow = MutableStateFlow(SleepTimerState()),
                    albumArtworkLoader = artworkLoader,
                    isFavorite = false,
                    artworkStyle = style,
                    spectrumVisualizerEnabled = true,
                    spectrumColorStyle = SpectrumColorStyle.ALBUM_ADAPTIVE,
                    spectrumSparksEnabled = true,
                    onBack = {},
                    onToggleFavorite = {},
                    onAddToPlaylist = {},
                    onShowTrackInfo = {},
                    onSelectSleepTimerPreset = {},
                    onTogglePlayPause = {},
                    onPrevious = {},
                    onNext = {},
                    onSeekTo = {},
                    onToggleShuffle = {},
                    onCycleRepeatMode = {},
                    onRequestBitPerfectMode = {},
                    onConfirmBitPerfectWarning = {},
                    onDismissSafetyWarning = {},
                    onSwitchToSafeMode = {},
                    onClearAutoPauseNotice = {},
                    onOpenQueue = {},
                    onOpenLyrics = {},
                    onOpenDiagnostics = {},
                    coverThemeColorCache = themeCache,
                    onBackCoverVisibilityChanged = { backFlipEvents.add(it) },
                )
            }
        }

        composeRule.waitForIdle()
        // Vinyl Disc mode renders ambient glow + radial spectrum ring.
        composeRule.onNodeWithContentDescription("Ambient artwork glow").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Audio spectrum visualizer ring").assertIsDisplayed()

        // Switch to Square Cover mode -> renders ambient glow + linear 32-bar strip + flip button.
        style = NowPlayingArtworkStyle.SQUARE_COVER
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Ambient artwork glow").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Audio spectrum visualizer bar strip").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Show back cover").assertIsDisplayed()

        // Flip Front -> Back cover notifies DynamicThemeCoordinator callback cleanly!
        composeRule.onNodeWithContentDescription("Show back cover").performClick()
        composeRule.waitForIdle()
        assertTrue("Expected back-cover visibility callback true after flip", backFlipEvents.lastOrNull() == true)
        composeRule.onNodeWithContentDescription("Show front cover").assertIsDisplayed()

        // Flip Back -> Front cover resets visibility callback to false.
        composeRule.onNodeWithContentDescription("Show front cover").performClick()
        composeRule.waitForIdle()
        assertTrue("Expected back-cover visibility callback false after returning to front", backFlipEvents.lastOrNull() == false)
    }

    @Test
    fun artistDetailView_propagatesIsolatedArtistSlotThemeAndAmbientHeroWash() {
        val artistPortrait = solidBitmap(0xFF10B981.toInt()) // vivid emerald ARTIST art
        artworkLoader.seedArtistEmbeddedBitmapForTesting(
            artistName = hiResTrack.artist,
            bitmap = artistPortrait,
            targetSizePx = AlbumArtworkLoader.HERO_SIZE_PX,
        )
        val artistGroup = LibraryGroup(
            name = hiResTrack.artist,
            subtitle = "1 album · 1 track",
            tracks = listOf(hiResTrack),
        )

        var showArtistDetail by mutableStateOf(true)
        var pushedContextualTokens by mutableStateOf<DynamicThemeTokens?>(null)

        composeRule.setContent {
            // Host root uses pushed contextual Artist tokens when active (isolated from playback track).
            BitPerfectPlayerTheme(dynamicTokens = pushedContextualTokens) {
                if (showArtistDetail) {
                    ArtistDetailView(
                        artistGroup = artistGroup,
                        albumTrackSortSpec = TrackSortSpec(TrackSortOption.TRACK_NUMBER, SortDirection.ASC),
                        artistTrackSortLabel = "Year",
                        scrollResetKey = "test_artist",
                        activeTrackUri = null,
                        favoriteTrackUris = emptySet(),
                        favoriteFingerprints = emptySet(),
                        albumArtworkLoader = artworkLoader,
                        artistPhotoStore = null,
                        artistPhotoRevision = 0,
                        dynamicCoverThemeEnabled = true,
                        dynamicThemeIntensity = DynamicThemeIntensity.VIBRANT,
                        coverThemeColorCache = themeCache,
                        onBack = { showArtistDetail = false },
                        onOpenAlbum = {},
                        onOpenSort = {},
                        onPlayTracks = { _, _ -> },
                        onShuffleTracks = {},
                        onEnqueueAllNext = {},
                        onEnqueueAll = {},
                        onEnqueueTrack = {},
                        onEnqueueNext = {},
                        onAddToPlaylist = {},
                        onShowTrackInfo = {},
                        onToggleFavorite = {},
                        onSetCustomArtistPhoto = { _, _ -> },
                        onResetCustomArtistPhoto = {},
                        onContextualDynamicThemeChanged = { pushedContextualTokens = it },
                    )
                }
            }
        }

        composeRule.waitUntil(timeoutMillis = 3_000L) { pushedContextualTokens != null }
        composeRule.waitForIdle()

        assertNotNull("Chromatic ARTIST portrait should push isolated contextual tokens", pushedContextualTokens)
        assertEquals(DynamicThemeIntensity.VIBRANT, pushedContextualTokens!!.intensity)
        composeRule.onNodeWithContentDescription("Ambient artist hero glow").assertIsDisplayed()

        // Leaving ArtistDetailView cleanly clears contextual theme back to null (playback theme resumes).
        showArtistDetail = false
        composeRule.waitForIdle()
        assertNull(pushedContextualTokens)
    }
}
