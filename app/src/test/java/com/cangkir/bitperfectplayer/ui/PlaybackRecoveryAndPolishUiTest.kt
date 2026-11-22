package com.cangkir.bitperfectplayer.ui

import android.Manifest
import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import com.cangkir.bitperfectplayer.audio.AudioOutputMode
import com.cangkir.bitperfectplayer.audio.AudioSafetyController
import com.cangkir.bitperfectplayer.audio.AudioSafetyState
import com.cangkir.bitperfectplayer.audio.AutoPauseReason
import com.cangkir.bitperfectplayer.audio.EnginePlaybackState
import com.cangkir.bitperfectplayer.audio.PlaybackControllerState
import com.cangkir.bitperfectplayer.audio.PlaybackStatus
import com.cangkir.bitperfectplayer.audio.PlaybackTrack
import com.cangkir.bitperfectplayer.audio.QueueContext
import com.cangkir.bitperfectplayer.audio.QueueSnapshot
import com.cangkir.bitperfectplayer.audio.SleepTimerState
import com.cangkir.bitperfectplayer.library.AlbumArtworkLoader
import com.cangkir.bitperfectplayer.library.LibraryGroup
import com.cangkir.bitperfectplayer.library.SortDirection
import com.cangkir.bitperfectplayer.library.TrackSortOption
import com.cangkir.bitperfectplayer.library.TrackSortSpec
import com.cangkir.bitperfectplayer.ui.library.ArtistDetailView
import com.cangkir.bitperfectplayer.ui.nowplaying.NowPlayingScreen
import com.cangkir.bitperfectplayer.ui.theme.BitPerfectPlayerTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Headless JVM Compose behavioral tests verifying v1.3 USB hot-plug first-tap Play recovery,
 * immediate `00:00` elapsed position reset on track switch, flicker-free `ArtistDetailView` hero
 * placeholder & oldest-first fallback, and non-truncating compact `kHz` specs in `QueueScreen`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlaybackRecoveryAndPolishUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var artworkLoader: AlbumArtworkLoader

    private val trackCdFlac = PlaybackTrack(
        uriString = "content://test/audio/afterlife_cd.flac",
        title = "Afterlife",
        artist = "Avenged Sevenfold Featuring Symphony Orchestra",
        album = "Avenged Sevenfold",
        fileName = "04 - Afterlife.flac",
        durationMs = 352_000L, // 05:52
        sampleRateHz = 44_100,
        bitDepth = 16,
        container = "FLAC",
        codec = "FLAC",
        year = 2007,
        trackNumber = 4,
    )

    private val trackHiResFlac = PlaybackTrack(
        uriString = "content://test/audio/drive_my_car_96k.flac",
        title = "Drive My Car",
        artist = "Avenged Sevenfold Featuring Symphony Orchestra",
        album = "Rubber Soul Hi-Res",
        fileName = "01 - Drive My Car.flac",
        durationMs = 148_000L, // 02:28
        sampleRateHz = 96_000,
        bitDepth = 24,
        container = "FLAC",
        codec = "FLAC",
        year = 1965,
        trackNumber = 1,
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

    @Test
    @Config(qualifiers = "w411dp-h915dp")
    fun nowPlayingScreen_firstTapPlayImmediatelyDismissesAutoPausedBannerAndResumesAtPreservedPosition() {
        composeRule.mainClock.autoAdvance = false
        var playToggleClicks = 0
        val autoPauseBanner = AudioSafetyController.formatAutoPauseNotice(AutoPauseReason.BECOMING_NOISY)
        val posFlow = MutableStateFlow(67_000L) // 01:07 preserved position after USB unplug
        var playbackState by mutableStateOf(
            PlaybackControllerState(
                queue = QueueSnapshot(items = listOf(trackCdFlac), currentIndex = 0),
                queueContext = QueueContext.album(trackCdFlac.album),
                activeTrack = trackCdFlac,
                engineState = EnginePlaybackState(
                    status = PlaybackStatus.PAUSED,
                    activeTrackUri = Uri.parse(trackCdFlac.uriString),
                    durationMs = trackCdFlac.durationMs,
                    safetyState = AudioSafetyState(
                        mode = AudioOutputMode.SAFE_VOLUME_MODE,
                        isUsbAudioConnected = false,
                        activeDeviceName = "Internal Speaker",
                        lastAutoPauseReason = AutoPauseReason.BECOMING_NOISY,
                    ),
                    lastAutoPauseNotice = autoPauseBanner,
                ),
            ),
        )

        composeRule.setContent {
            BitPerfectPlayerTheme {
                NowPlayingScreen(
                    playbackState = playbackState,
                    playbackPositionMsFlow = posFlow,
                    sleepTimerStateFlow = MutableStateFlow(SleepTimerState()),
                    albumArtworkLoader = artworkLoader,
                    isFavorite = false,
                    spectrumVisualizerEnabled = false,
                    onBack = {},
                    onToggleFavorite = {},
                    onAddToPlaylist = {},
                    onShowTrackInfo = {},
                    onSelectSleepTimerPreset = {},
                    onTogglePlayPause = {
                        playToggleClicks++
                        playbackState = playbackState.copy(
                            engineState = playbackState.engineState.copy(
                                status = PlaybackStatus.PLAYING,
                                lastAutoPauseNotice = null,
                            ),
                        )
                    },
                    onPrevious = {},
                    onNext = {},
                    onSeekTo = {},
                    onToggleShuffle = {},
                    onCycleRepeatMode = {},
                    onRequestBitPerfectMode = {},
                    onConfirmBitPerfectWarning = {},
                    onDismissSafetyWarning = {},
                    onSwitchToSafeMode = {},
                    onClearAutoPauseNotice = {
                        playbackState = playbackState.copy(
                            engineState = playbackState.engineState.copy(lastAutoPauseNotice = null),
                        )
                    },
                    onOpenQueue = {},
                    onOpenLyrics = {},
                    onOpenDiagnostics = {},
                )
            }
        }
        composeRule.mainClock.advanceTimeBy(32L)

        // Immediately after USB unplug: fallback output pill is synced, Auto-paused banner is shown, 01:07 preserved
        composeRule.onNodeWithText("Safe Mode · Internal Speaker").assertIsDisplayed()
        composeRule.onNodeWithText(autoPauseBanner).assertIsDisplayed()
        composeRule.onNodeWithText("01:07").assertIsDisplayed()

        // First tap on Play immediately dismisses the Auto-paused banner and resumes at preserved 01:07
        composeRule.onNodeWithContentDescription("Play audio")
            .assertIsDisplayed()
            .performClick()
        composeRule.mainClock.advanceTimeBy(32L)

        assertEquals(1, playToggleClicks)
        assertEquals(0, composeRule.onAllNodesWithText(autoPauseBanner).fetchSemanticsNodes().size)
        composeRule.onNodeWithContentDescription("Pause playback").assertIsDisplayed()
        composeRule.onNodeWithText("01:07").assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithText("00:00").fetchSemanticsNodes().size)
    }

    @Test
    @Config(qualifiers = "w411dp-h915dp")
    fun nowPlayingScreen_trackSwitchImmediatelyResetsElapsedPositionToZeroEvenBeforePositionFlowClears() {
        composeRule.mainClock.autoAdvance = false
        val posFlow = MutableStateFlow(1_500L) // 00:01 on Track A
        var playbackState by mutableStateOf(
            PlaybackControllerState(
                queue = QueueSnapshot(items = listOf(trackCdFlac, trackHiResFlac), currentIndex = 0),
                queueContext = QueueContext.allSongs(),
                activeTrack = trackCdFlac,
                engineState = EnginePlaybackState(
                    status = PlaybackStatus.PLAYING,
                    activeTrackUri = Uri.parse(trackCdFlac.uriString),
                    durationMs = trackCdFlac.durationMs,
                ),
            ),
        )

        composeRule.setContent {
            BitPerfectPlayerTheme {
                NowPlayingScreen(
                    playbackState = playbackState,
                    playbackPositionMsFlow = posFlow,
                    sleepTimerStateFlow = MutableStateFlow(SleepTimerState()),
                    albumArtworkLoader = artworkLoader,
                    isFavorite = false,
                    spectrumVisualizerEnabled = false,
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
                )
            }
        }
        composeRule.mainClock.advanceTimeBy(32L)
        composeRule.onNodeWithText("Afterlife").assertIsDisplayed()
        composeRule.onNodeWithText("00:01").assertIsDisplayed()

        // Switch activeTrack to Track B while posFlow STILL briefly carries 1,500 ms and engineTrackUri is still Track A
        composeRule.runOnIdle {
            playbackState = playbackState.copy(
                queue = QueueSnapshot(items = listOf(trackCdFlac, trackHiResFlac), currentIndex = 1),
                activeTrack = trackHiResFlac,
                engineState = playbackState.engineState.copy(status = PlaybackStatus.PREPARING),
            )
        }
        composeRule.mainClock.advanceTimeBy(32L)

        // Elapsed display MUST reset to 00:00 immediately; stale 00:01 must never linger under Drive My Car
        composeRule.onNodeWithText("Drive My Car").assertIsDisplayed()
        composeRule.onNodeWithText("00:00").assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithText("00:01").fetchSemanticsNodes().size)

        // Once engine binds to Drive My Car and advances to 00:03, live position renders normally
        composeRule.runOnIdle {
            posFlow.value = 3_000L
            playbackState = playbackState.copy(
                engineState = playbackState.engineState.copy(
                    status = PlaybackStatus.PLAYING,
                    activeTrackUri = Uri.parse(trackHiResFlac.uriString),
                    durationMs = trackHiResFlac.durationMs,
                ),
            )
        }
        composeRule.mainClock.advanceTimeBy(32L)
        composeRule.onNodeWithText("00:03").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w411dp-h915dp")
    fun artistDetailView_doesNotFlashCachedAlbumCoverWhileEmbeddedLookupRuns_thenFallsBackToOldestAlbum() {
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val newerAlbumTrack = trackCdFlac.copy(
            uriString = "content://test/artist_flash/newer_2013.flac",
            title = "Hail to the King",
            artist = "Avenged Sevenfold",
            album = "LP 2013",
            year = 2013,
        )
        val oldestAlbumTrack = trackCdFlac.copy(
            uriString = "content://test/artist_flash/oldest_2003.flac",
            title = "Unholy Confessions",
            artist = "Avenged Sevenfold",
            album = "LP 2003",
            year = 2003,
        )
        // Both album covers are already warm in memoryCache at HERO_SIZE_PX, while embedded ARTIST lookup is uncached
        artworkLoader.seedArtworkForTesting(newerAlbumTrack, bmp, AlbumArtworkLoader.HERO_SIZE_PX)
        artworkLoader.seedArtworkForTesting(oldestAlbumTrack, bmp, AlbumArtworkLoader.HERO_SIZE_PX)

        val artistGroup = LibraryGroup(
            name = "Avenged Sevenfold",
            subtitle = "2 songs · 2 albums",
            tracks = listOf(newerAlbumTrack, oldestAlbumTrack), // Deliberately newer-first input
        )

        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            BitPerfectPlayerTheme {
                ArtistDetailView(
                    artistGroup = artistGroup,
                    albumTrackSortSpec = TrackSortSpec(TrackSortOption.TRACK_NUMBER, SortDirection.ASC),
                    artistTrackSortLabel = "Title",
                    scrollResetKey = 0,
                    activeTrackUri = null,
                    favoriteTrackUris = emptySet(),
                    favoriteFingerprints = emptySet(),
                    albumArtworkLoader = artworkLoader,
                    artistPhotoStore = null,
                    artistPhotoRevision = 0,
                    dynamicCoverThemeEnabled = false,
                    coverThemeColorCache = null,
                    onBack = {},
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
                )
            }
        }

        // Initial composition frame BEFORE Dispatchers.IO finishes embedded ARTIST check:
        // Must show matte-obsidian loading placeholder — NEVER flashing LP 2013 or LP 2003 album art!
        composeRule.onNodeWithContentDescription("Loading artist artwork for Avenged Sevenfold").assertIsDisplayed()
        assertEquals(
            0,
            composeRule.onAllNodesWithContentDescription("Artist hero cover from LP 2013 for Avenged Sevenfold")
                .fetchSemanticsNodes()
                .size,
        )
        assertEquals(
            0,
            composeRule.onAllNodesWithContentDescription("Artist hero cover from LP 2003 for Avenged Sevenfold")
                .fetchSemanticsNodes()
                .size,
        )

        // After async embedded ARTIST lookup completes and returns null, fall back strictly to oldest-first LP 2003
        composeRule.mainClock.autoAdvance = true
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithContentDescription("Artist hero cover from LP 2003 for Avenged Sevenfold")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("Artist hero cover from LP 2003 for Avenged Sevenfold").assertIsDisplayed()
        assertEquals(
            0,
            composeRule.onAllNodesWithContentDescription("Artist hero cover from LP 2013 for Avenged Sevenfold")
                .fetchSemanticsNodes()
                .size,
        )
    }

    @Test
    @Config(qualifiers = "w411dp-h915dp")
    fun artistDetailView_rendersCachedEmbeddedArtistPhotoImmediatelyWithoutPlaceholderOrAlbumFlash() {
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val albumTrack = trackCdFlac.copy(
            uriString = "content://test/artist_cached/muse_01.flac",
            artist = "Muse",
            album = "Origin of Symmetry",
            year = 2001,
        )
        artworkLoader.seedArtworkForTesting(albumTrack, bmp, AlbumArtworkLoader.HERO_SIZE_PX)
        artworkLoader.seedArtistEmbeddedBitmapForTesting("Muse", bmp, AlbumArtworkLoader.HERO_SIZE_PX)

        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            BitPerfectPlayerTheme {
                ArtistDetailView(
                    artistGroup = LibraryGroup("Muse", "1 song · 1 album", listOf(albumTrack)),
                    albumTrackSortSpec = TrackSortSpec(TrackSortOption.TRACK_NUMBER, SortDirection.ASC),
                    artistTrackSortLabel = "Title",
                    scrollResetKey = 0,
                    activeTrackUri = null,
                    favoriteTrackUris = emptySet(),
                    favoriteFingerprints = emptySet(),
                    albumArtworkLoader = artworkLoader,
                    artistPhotoStore = null,
                    artistPhotoRevision = 0,
                    dynamicCoverThemeEnabled = false,
                    coverThemeColorCache = null,
                    onBack = {},
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
                )
            }
        }

        // Cached embedded ARTIST photo renders synchronously on Frame 1 with zero album flash
        composeRule.onNodeWithContentDescription("Artist photo for Muse").assertIsDisplayed()
        assertEquals(
            0,
            composeRule.onAllNodesWithContentDescription("Artist hero cover from Origin of Symmetry for Muse")
                .fetchSemanticsNodes()
                .size,
        )
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w360dp-h740dp")
    fun queueScreen_displaysCompactKhzSpecsWithoutTruncationEvenForLongArtistNames() {
        val playbackState = PlaybackControllerState(
            queue = QueueSnapshot(items = listOf(trackCdFlac, trackHiResFlac), currentIndex = 0),
            queueContext = QueueContext.allSongs(),
            activeTrack = trackCdFlac,
            engineState = EnginePlaybackState(status = PlaybackStatus.PLAYING, durationMs = trackCdFlac.durationMs),
        )

        composeRule.setContent {
            BitPerfectPlayerTheme {
                QueueScreen(
                    playbackState = playbackState,
                    libraryTracks = listOf(trackCdFlac, trackHiResFlac),
                    activeContextTracks = listOf(trackCdFlac, trackHiResFlac),
                    albumArtworkLoader = artworkLoader,
                    onBack = {},
                    onSelectQueueIndex = {},
                    onAddTrackToQueue = {},
                    onRemoveQueueItem = {},
                    onMoveQueueItem = { _, _ -> },
                    onToggleShuffle = {},
                    onCycleRepeatMode = {},
                )
            }
        }
        composeRule.waitForIdle()

        // Verbose comma-formatted Hz must never appear in Queue rows
        assertEquals(0, composeRule.onAllNodesWithText("44,100 Hz", substring = true).fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("96,000 Hz", substring = true).fetchSemanticsNodes().size)

        val expectedSpecs = listOf(
            " · FLAC 16-bit · 44.1 kHz" to "Remove Afterlife from queue",
            " · FLAC 24-bit · 96 kHz" to "Remove Drive My Car from queue",
        )
        val artistNodes = composeRule.onAllNodesWithText(trackCdFlac.artist, useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(2, artistNodes.size)

        expectedSpecs.forEachIndexed { index, (expectedSpecText, removeDesc) ->
            val specNode = composeRule.onNodeWithText(expectedSpecText, useUnmergedTree = true)
                .assertIsDisplayed()
                .fetchSemanticsNode()
            val removeBounds = composeRule.onNodeWithContentDescription(removeDesc)
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
            val artistNode = artistNodes[index]

            val artistLayouts = mutableListOf<TextLayoutResult>()
            val specLayouts = mutableListOf<TextLayoutResult>()
            artistNode.config[SemanticsActions.GetTextLayoutResult].action?.invoke(artistLayouts)
            specNode.config[SemanticsActions.GetTextLayoutResult].action?.invoke(specLayouts)
            assertEquals(1, artistLayouts.size)
            assertEquals(1, specLayouts.size)

            val artistLayout = artistLayouts.single()
            val specLayout = specLayouts.single()
            assertTrue("Expected long artist to ellipsize on 360dp phone width", artistLayout.isLineEllipsized(0))
            assertFalse("Compact kHz spec in Queue row must never ellipsize: $expectedSpecText", specLayout.isLineEllipsized(0))
            assertEquals(1, specLayout.lineCount)
            assertEquals(expectedSpecText.length, specLayout.getLineEnd(0))
            assertTrue(
                "Compact kHz spec glyphs must fit inside allocated box without clipping " +
                    "(lineRight=${specLayout.getLineRight(0)}, boxW=${specLayout.size.width})",
                specLayout.getLineRight(0) <= specLayout.size.width + 1f,
            )
            assertTrue("Pinned spec should sit to the right of artist", specNode.boundsInRoot.left >= artistNode.boundsInRoot.right - 1f)
            assertTrue("Pinned spec must not overlap Remove button", specNode.boundsInRoot.right <= removeBounds.left + 1f)
        }
    }

    @Test
    fun queueScreen_addTrackDialogAlsoDisplaysCompactKhzSpecs() {
        val playbackState = PlaybackControllerState(
            queue = QueueSnapshot(items = listOf(trackCdFlac), currentIndex = 0),
            queueContext = QueueContext.allSongs(),
            activeTrack = trackCdFlac,
        )

        composeRule.setContent {
            BitPerfectPlayerTheme {
                QueueScreen(
                    playbackState = playbackState,
                    libraryTracks = listOf(trackCdFlac, trackHiResFlac),
                    activeContextTracks = listOf(trackCdFlac, trackHiResFlac),
                    albumArtworkLoader = artworkLoader,
                    onBack = {},
                    onSelectQueueIndex = {},
                    onAddTrackToQueue = {},
                    onRemoveQueueItem = {},
                    onMoveQueueItem = { _, _ -> },
                    onToggleShuffle = {},
                    onCycleRepeatMode = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("Add track to queue").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Add Track to Queue").assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithText("44,100 Hz", substring = true).fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("96,000 Hz", substring = true).fetchSemanticsNodes().size)
        assertTrue(
            composeRule.onAllNodesWithText(" · FLAC 16-bit · 44.1 kHz", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .size >= 2,
        )
        composeRule.onNodeWithText(" · FLAC 24-bit · 96 kHz", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Close").performClick()
    }
}
