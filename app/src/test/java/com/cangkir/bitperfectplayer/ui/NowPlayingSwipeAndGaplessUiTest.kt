package com.cangkir.bitperfectplayer.ui

import android.Manifest
import android.app.Application
import android.net.Uri
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.cangkir.bitperfectplayer.audio.AudioOutputMode
import com.cangkir.bitperfectplayer.audio.AudioSafetyState
import com.cangkir.bitperfectplayer.audio.EnginePlaybackState
import com.cangkir.bitperfectplayer.audio.PlaybackControllerState
import com.cangkir.bitperfectplayer.audio.PlaybackStatus
import com.cangkir.bitperfectplayer.audio.PlaybackTrack
import com.cangkir.bitperfectplayer.audio.QueueContext
import com.cangkir.bitperfectplayer.audio.QueueSnapshot
import com.cangkir.bitperfectplayer.audio.ReplayGainConfig
import com.cangkir.bitperfectplayer.audio.ReplayGainMode
import com.cangkir.bitperfectplayer.audio.SleepTimerState
import com.cangkir.bitperfectplayer.data.DefaultHomepage
import com.cangkir.bitperfectplayer.data.NowPlayingArtworkStyle
import com.cangkir.bitperfectplayer.library.AlbumArtworkLoader
import com.cangkir.bitperfectplayer.library.LibrarySnapshot
import com.cangkir.bitperfectplayer.library.PlaylistsUiSnapshot
import com.cangkir.bitperfectplayer.ui.library.LibraryScreen
import com.cangkir.bitperfectplayer.ui.nowplaying.NOW_PLAYING_SQUARE_ARTWORK_TEST_TAG
import com.cangkir.bitperfectplayer.ui.nowplaying.NOW_PLAYING_SWIPE_CONTAINER_TEST_TAG
import com.cangkir.bitperfectplayer.ui.nowplaying.NowPlayingScreen
import com.cangkir.bitperfectplayer.ui.nowplaying.ROOT_HORIZONTAL_PAGER_TEST_TAG
import com.cangkir.bitperfectplayer.ui.nowplaying.SETTINGS_SWIPE_CONTAINER_TEST_TAG
import com.cangkir.bitperfectplayer.ui.playlists.PlaylistsScreen
import com.cangkir.bitperfectplayer.ui.settings.REPLAY_GAIN_PREAMP_SLIDER_TEST_TAG
import com.cangkir.bitperfectplayer.ui.settings.SettingsScreen
import com.cangkir.bitperfectplayer.ui.theme.BitPerfectPlayerTheme
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NowPlayingSwipeAndGaplessUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var artworkLoader: AlbumArtworkLoader

    private val trackA = PlaybackTrack(
        uriString = "content://test/audio/track_a.flac",
        title = "American Idiot",
        artist = "Green Day",
        album = "American Idiot",
        fileName = "01 - American Idiot.flac",
        durationMs = 174_000L, // 02:54
        sampleRateHz = 44_100,
        bitDepth = 16,
        container = "FLAC",
        codec = "FLAC",
    )

    private val trackB = PlaybackTrack(
        uriString = "content://test/audio/track_b.flac",
        title = "Jesus of Suburbia",
        artist = "Green Day",
        album = "American Idiot",
        fileName = "02 - Jesus of Suburbia.flac",
        durationMs = 300_000L, // 05:00
        sampleRateHz = 44_100,
        bitDepth = 16,
        container = "FLAC",
        codec = "FLAC",
    )

    private val trackLongTitle = trackB.copy(
        uriString = "content://test/audio/track_long.flac",
        title = "Boulevard of Broken Dreams (Extended 24-Bit Studio Remaster)",
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

    private fun buildState(active: PlaybackTrack, engineUri: String = active.uriString): PlaybackControllerState = PlaybackControllerState(
        queue = QueueSnapshot(items = listOf(trackA, trackB), currentIndex = if (active == trackA) 0 else 1),
        queueContext = QueueContext.album(active.album),
        activeTrack = active,
        engineState = EnginePlaybackState(
            status = PlaybackStatus.PLAYING,
            activeTrackUri = Uri.parse(engineUri),
            durationMs = active.durationMs,
            safetyState = AudioSafetyState(
                mode = AudioOutputMode.SAFE_VOLUME_MODE,
                activeDeviceName = "Internal Speaker",
            ),
        ),
    )

    private fun advanceSettle(ms: Long = 600L) {
        composeRule.mainClock.advanceTimeBy(ms)
    }

    @Composable
    private fun RenderTestNowPlaying(
        state: PlaybackControllerState,
        posFlow: MutableStateFlow<Long>,
        artworkStyle: NowPlayingArtworkStyle = NowPlayingArtworkStyle.VINYL_DISC,
        onBack: () -> Unit = {},
        onPrevious: () -> Unit = {},
        onNext: () -> Unit = {},
        onSeekTo: (Long) -> Unit = {},
    ) {
        NowPlayingScreen(
            playbackState = state,
            playbackPositionMsFlow = posFlow,
            sleepTimerStateFlow = MutableStateFlow(SleepTimerState()),
            albumArtworkLoader = artworkLoader,
            isFavorite = false,
            artworkStyle = artworkStyle,
            spectrumVisualizerEnabled = false,
            onBack = onBack,
            onToggleFavorite = {},
            onAddToPlaylist = {},
            onShowTrackInfo = {},
            onSelectSleepTimerPreset = {},
            onTogglePlayPause = {},
            onPrevious = onPrevious,
            onNext = onNext,
            onSeekTo = onSeekTo,
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

    @Composable
    private fun RenderRootPagerNavHost(
        state: PlaybackControllerState,
        posFlow: MutableStateFlow<Long>,
        showMiniPlayerButton: Boolean,
        artworkStyle: NowPlayingArtworkStyle = NowPlayingArtworkStyle.VINYL_DISC,
        onPrevious: () -> Unit = {},
        onNext: () -> Unit = {},
        onSeekTo: (Long) -> Unit = {},
        onSetReplayGainPreampDb: (Float) -> Unit = {},
    ) {
        val navigator = rememberRootPagerNavigator()
        BitPerfectStartupNavHost(
            startupHomepage = DefaultHomepage.ALL_SONGS,
            hasActiveTrack = state.activeTrack != null,
            rootPagerNavigator = navigator,
            miniPlayerContent = {
                if (showMiniPlayerButton && state.activeTrack != null) {
                    Button(onClick = { navigator.openNowPlaying() }) {
                        Text("Open Test Player")
                    }
                } else {
                    Spacer(modifier = Modifier.height(0.dp))
                }
            },
            libraryContent = { initialTab ->
                if (showMiniPlayerButton) {
                    Text("Library Placeholder")
                } else {
                    LibraryScreen(
                        libraryState = LibrarySnapshot(allSongs = listOf(trackA, trackB)),
                        favoriteTrackUris = emptySet(),
                        playbackState = state,
                        albumArtworkLoader = artworkLoader,
                        onRefreshLibrary = {},
                        onImportFolder = {},
                        onPlayTracks = { _, _, _ -> navigator.openNowPlaying() },
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
                        onOpenQueue = {},
                        initialTab = initialTab,
                    )
                }
            },
            playlistsContent = {
                PlaylistsScreen(
                    playlistsState = PlaylistsUiSnapshot(),
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
                    onOpenQueue = {},
                )
            },
            settingsContent = {
                RenderRootPagerSettingsScreen(
                    state = state,
                    onSetReplayGainPreampDb = onSetReplayGainPreampDb,
                    onBack = null,
                )
            },
            nowPlayingContent = { isPageActive, onBack, _ ->
                NowPlayingScreen(
                    playbackState = state,
                    playbackPositionMsFlow = posFlow,
                    sleepTimerStateFlow = MutableStateFlow(SleepTimerState()),
                    albumArtworkLoader = artworkLoader,
                    isFavorite = false,
                    artworkStyle = artworkStyle,
                    spectrumVisualizerEnabled = false,
                    onBack = onBack,
                    onToggleFavorite = {},
                    onAddToPlaylist = {},
                    onShowTrackInfo = {},
                    onSelectSleepTimerPreset = {},
                    onTogglePlayPause = {},
                    onPrevious = onPrevious,
                    onNext = onNext,
                    onSeekTo = onSeekTo,
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
                    isPageActive = isPageActive,
                )
            },
            settingsFromNowPlayingContent = { onBack ->
                RenderRootPagerSettingsScreen(
                    state = state,
                    onSetReplayGainPreampDb = onSetReplayGainPreampDb,
                    onBack = onBack,
                )
            },
        )
    }

    @Composable
    private fun RenderRootPagerSettingsScreen(
        state: PlaybackControllerState,
        onSetReplayGainPreampDb: (Float) -> Unit,
        onBack: (() -> Unit)?,
    ) {
        SettingsScreen(
            libraryState = LibrarySnapshot(),
            playbackState = state,
            sleepTimerStateFlow = MutableStateFlow(SleepTimerState()),
            onImportFolder = {},
            onRemoveSafFolder = {},
            onRefreshLibrary = {},
            onSetGaplessEnabled = {},
            onSetReplayGainMode = {},
            onSetReplayGainPreampDb = onSetReplayGainPreampDb,
            onSetReplayGainPreventClipping = {},
            onSelectSleepTimerPreset = {},
            onExportLocalBackup = {},
            onImportLocalBackup = {},
            onOpenDiagnostics = {},
            onBack = onBack,
        )
    }

    @Test
    @Config(qualifiers = "w411dp-h915dp")
    fun gaplessTransition_syncedEngineStateRestoresSeekSliderAndTimestampDisplay() {
        composeRule.mainClock.autoAdvance = false
        val posFlow = MutableStateFlow(172_000L)
        var playbackState by mutableStateOf(buildState(active = trackA))
        var seekCount by mutableIntStateOf(0)
        var lastSeekMs by mutableLongStateOf(0L)

        composeRule.setContent {
            BitPerfectPlayerTheme {
                RenderTestNowPlaying(
                    state = playbackState,
                    posFlow = posFlow,
                    onSeekTo = { target ->
                        seekCount++
                        lastSeekMs = target
                        posFlow.value = target
                    },
                )
            }
        }
        advanceSettle(48L)

        composeRule.runOnIdle {
            playbackState = buildState(active = trackB, engineUri = trackB.uriString)
            posFlow.value = 0L
        }
        advanceSettle(48L)

        composeRule.onNodeWithText("Jesus of Suburbia").assertIsDisplayed()
        composeRule.onNodeWithText("05:00").assertIsDisplayed()

        composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
            .performTouchInput { click(center) }
        advanceSettle(48L)

        assertEquals(1, seekCount)
        assertTrue("Expected seek near 02:30 (150_000 ms), got $lastSeekMs", lastSeekMs in 135_000L..165_000L)
        composeRule.onNodeWithText(formatDurationMs(lastSeekMs)).assertIsDisplayed()
        composeRule.onNodeWithText("00:00").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w411dp-h915dp")
    fun nowPlayingScreen_floatingAutoPauseBannerDoesNotShiftTransportButtons() {
        composeRule.mainClock.autoAdvance = false
        var state by mutableStateOf(buildState(active = trackA))
        composeRule.setContent {
            BitPerfectPlayerTheme {
                RenderTestNowPlaying(
                    state = state,
                    posFlow = MutableStateFlow(10_000L),
                )
            }
        }
        advanceSettle(48L)

        val nextTopWithoutBanner = composeRule.onNodeWithContentDescription("Next track")
            .fetchSemanticsNode().boundsInRoot.top

        composeRule.runOnIdle {
            state = state.copy(
                engineState = state.engineState.copy(
                    status = PlaybackStatus.PAUSED,
                    lastAutoPauseNotice = "Auto-paused: USB audio device connected in Safe Mode.",
                ),
            )
        }
        advanceSettle(200L)
        composeRule.onNodeWithText("Auto-paused: USB audio device connected in Safe Mode.").assertIsDisplayed()

        val nextTopWithBanner = composeRule.onNodeWithContentDescription("Next track")
            .fetchSemanticsNode().boundsInRoot.top
        assertTrue(
            "Floating safety alert banner must not shift transport controls (before=$nextTopWithoutBanner, after=$nextTopWithBanner)",
            abs(nextTopWithoutBanner - nextTopWithBanner) < 1f,
        )
    }

    @Test
    @Config(qualifiers = "w411dp-h915dp")
    fun nowPlayingScreen_twoLineLongTitleKeepsTransportNextButtonStationary() {
        composeRule.mainClock.autoAdvance = false
        var state by mutableStateOf(buildState(active = trackA))
        composeRule.setContent {
            BitPerfectPlayerTheme {
                RenderTestNowPlaying(
                    state = state,
                    posFlow = MutableStateFlow(10_000L),
                )
            }
        }
        advanceSettle(48L)

        val nextTopShortTitle = composeRule.onNodeWithContentDescription("Next track")
            .fetchSemanticsNode().boundsInRoot.top

        composeRule.runOnIdle {
            state = buildState(active = trackLongTitle)
        }
        advanceSettle(48L)
        composeRule.onNodeWithText(trackLongTitle.title).assertIsDisplayed()

        val nextTopLongTitle = composeRule.onNodeWithContentDescription("Next track")
            .fetchSemanticsNode().boundsInRoot.top
        assertTrue(
            "Fixed 2-line title slot must keep Next button stationary (short=$nextTopShortTitle, long=$nextTopLongTitle)",
            abs(nextTopShortTitle - nextTopLongTitle) < 1f,
        )
    }

    @Test
    @Config(qualifiers = "w411dp-h915dp")
    fun rootPager_liveAdjacentPagesSlideOneToOneWithoutEmptyBlackScreens() {
        composeRule.mainClock.autoAdvance = false
        val state = buildState(active = trackA)
        composeRule.setContent {
            BitPerfectPlayerTheme {
                RenderRootPagerNavHost(
                    state = state,
                    posFlow = MutableStateFlow(12_000L),
                    showMiniPlayerButton = true,
                )
            }
        }
        advanceSettle()

        // Enter Now Playing (Page 1)
        composeRule.onNodeWithText("Open Test Player").performClick()
        advanceSettle()
        composeRule.onNodeWithText("Now Playing").assertIsDisplayed()
        composeRule.onNodeWithText("Back").assertDoesNotExist()
        composeRule.onNodeWithText("Settings").assertDoesNotExist()

        // Mid-drag left -> right toward Library (Page 0): BOTH Now Playing and Library Placeholder are composed side-by-side!
        composeRule.onNodeWithTag(NOW_PLAYING_SWIPE_CONTAINER_TEST_TAG).performTouchInput {
            val yMargin = top + 24f
            down(Offset(width * 0.1f, yMargin))
            moveBy(Offset(width * 0.35f, 0f))
        }
        advanceSettle(16L)
        assertTrue(composeRule.onAllNodesWithText("Now Playing").fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeRule.onAllNodesWithText("Library Placeholder").fetchSemanticsNodes().isNotEmpty())
        composeRule.onNodeWithTag(NOW_PLAYING_SWIPE_CONTAINER_TEST_TAG).performTouchInput { up() }
        advanceSettle()
        composeRule.onNodeWithText("Now Playing").assertIsDisplayed()

        // Swipe right -> left from Now Playing opens full-screen Settings (Page 2)
        composeRule.onNodeWithTag(NOW_PLAYING_SWIPE_CONTAINER_TEST_TAG).performTouchInput {
            val yMargin = top + 24f
            swipe(Offset(width * 0.92f, yMargin), Offset(width * 0.08f, yMargin), durationMillis = 150L)
        }
        advanceSettle()
        composeRule.onNodeWithContentDescription("Back").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Bottom navigation bar").assertDoesNotExist()

        // Swipe left -> right on Settings slides back to Now Playing (Page 1), then left -> right again returns to Library (Page 0)
        composeRule.onNodeWithTag(SETTINGS_SWIPE_CONTAINER_TEST_TAG).performTouchInput {
            val yMargin = top + 24f
            swipe(Offset(width * 0.08f, yMargin), Offset(width * 0.92f, yMargin), durationMillis = 150L)
        }
        advanceSettle()
        composeRule.onNodeWithText("Now Playing").assertIsDisplayed()

        composeRule.onNodeWithTag(NOW_PLAYING_SWIPE_CONTAINER_TEST_TAG).performTouchInput {
            val yMargin = top + 24f
            swipe(Offset(width * 0.08f, yMargin), Offset(width * 0.92f, yMargin), durationMillis = 150L)
        }
        advanceSettle()
        composeRule.onNodeWithContentDescription("Bottom navigation bar").assertIsDisplayed()
        composeRule.onNodeWithText("Library Placeholder").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w411dp-h915dp")
    fun rootPager_bottomNavSettingsSwipeOpensNowPlayingAndContinuesCleanlyToLibrary() {
        composeRule.mainClock.autoAdvance = false
        var state by mutableStateOf(
            buildState(active = trackA).copy(queue = QueueSnapshot(), activeTrack = null),
        )
        composeRule.setContent {
            BitPerfectPlayerTheme {
                RenderRootPagerNavHost(
                    state = state,
                    posFlow = MutableStateFlow(10_000L),
                    showMiniPlayerButton = true,
                )
            }
        }
        advanceSettle()

        // Go to bottom-nav Settings while activeTrack == null -> swipe left->right is a strict no-op
        composeRule.onNodeWithContentDescription("Settings").performClick()
        advanceSettle()
        composeRule.onNodeWithTag(SETTINGS_SWIPE_CONTAINER_TEST_TAG).performTouchInput {
            val yMargin = top + 24f
            swipe(Offset(width * 0.08f, yMargin), Offset(width * 0.92f, yMargin), durationMillis = 150L)
        }
        advanceSettle()
        composeRule.onNodeWithText("AUDIO ENGINE").assertIsDisplayed()
        composeRule.onNodeWithText("Now Playing").assertDoesNotExist()

        // Activate a track: swipe right->left stays at boundary (no-op); swipe left->right opens live Now Playing!
        composeRule.runOnIdle { state = buildState(active = trackA) }
        advanceSettle(48L)
        composeRule.onNodeWithTag(SETTINGS_SWIPE_CONTAINER_TEST_TAG).performTouchInput {
            val yMargin = top + 24f
            swipe(Offset(width * 0.92f, yMargin), Offset(width * 0.08f, yMargin), durationMillis = 150L)
        }
        advanceSettle()
        composeRule.onNodeWithText("AUDIO ENGINE").assertIsDisplayed()

        composeRule.onNodeWithTag(SETTINGS_SWIPE_CONTAINER_TEST_TAG).performTouchInput {
            val yMargin = top + 24f
            swipe(Offset(width * 0.08f, yMargin), Offset(width * 0.92f, yMargin), durationMillis = 150L)
        }
        advanceSettle()
        composeRule.onNodeWithText("Now Playing").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Bottom navigation bar").assertDoesNotExist()

        // Continuing left->right from Now Playing goes cleanly to Library (NO loop back to Settings!)
        composeRule.onNodeWithTag(NOW_PLAYING_SWIPE_CONTAINER_TEST_TAG).performTouchInput {
            val yMargin = top + 24f
            swipe(Offset(width * 0.08f, yMargin), Offset(width * 0.92f, yMargin), durationMillis = 150L)
        }
        advanceSettle()
        composeRule.onNodeWithText("Library Placeholder").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Bottom navigation bar").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w411dp-h915dp")
    fun rootPager_protectsVinylSpindleSeekSliderAndSquareArtworkFromPageSwipes() {
        composeRule.mainClock.autoAdvance = false
        var artworkStyle by mutableStateOf(NowPlayingArtworkStyle.VINYL_DISC)
        var seekCalls by mutableIntStateOf(0)
        var nextCalls by mutableIntStateOf(0)
        var prevCalls by mutableIntStateOf(0)
        val posFlow = MutableStateFlow(45_000L)

        composeRule.setContent {
            BitPerfectPlayerTheme {
                RenderRootPagerNavHost(
                    state = buildState(active = trackA),
                    posFlow = posFlow,
                    showMiniPlayerButton = true,
                    artworkStyle = artworkStyle,
                    onPrevious = { prevCalls++ },
                    onNext = { nextCalls++ },
                    onSeekTo = {
                        seekCalls++
                        posFlow.value = it
                    },
                )
            }
        }
        advanceSettle()
        composeRule.onNodeWithText("Open Test Player").performClick()
        advanceSettle()

        val vinylNode = composeRule.onNodeWithContentDescription(
            "Vinyl disc turntable for ${trackA.title}. Drag circularly to seek.",
        )
        vinylNode.performTouchInput {
            swipe(start = center, end = Offset(right - 12f, centerY), durationMillis = 150L)
        }
        advanceSettle()
        vinylNode.performTouchInput {
            swipe(start = center, end = Offset(left + 12f, centerY), durationMillis = 150L)
        }
        advanceSettle()
        vinylNode.performTouchInput {
            swipe(
                start = Offset(centerX - width * 0.28f, centerY - height * 0.22f),
                end = Offset(centerX + width * 0.28f, centerY - height * 0.22f),
                durationMillis = 150L,
            )
        }
        advanceSettle()
        composeRule.onNodeWithText("Now Playing").assertIsDisplayed()

        val sliderNode = composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
        sliderNode.performTouchInput { swipeRight() }
        advanceSettle()
        sliderNode.performTouchInput {
            swipe(start = Offset(width * 0.25f, centerY), end = Offset(width * 0.75f, centerY), durationMillis = 150L)
        }
        advanceSettle()
        assertTrue(seekCalls > 0)
        composeRule.onNodeWithText("Now Playing").assertIsDisplayed()

        composeRule.runOnIdle { artworkStyle = NowPlayingArtworkStyle.SQUARE_COVER }
        advanceSettle(48L)
        composeRule.onNodeWithTag(NOW_PLAYING_SQUARE_ARTWORK_TEST_TAG).performTouchInput { swipeLeft() }
        advanceSettle()
        composeRule.onNodeWithTag(NOW_PLAYING_SQUARE_ARTWORK_TEST_TAG).performTouchInput { swipeRight() }
        advanceSettle()
        assertEquals(1, nextCalls)
        assertEquals(1, prevCalls)
        composeRule.onNodeWithTag(ROOT_HORIZONTAL_PAGER_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Now Playing").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w411dp-h915dp")
    fun rootPager_replayGainPreampSliderAdjustsValueWithoutSwipingSettingsBackToNowPlaying() {
        composeRule.mainClock.autoAdvance = false
        var preampChangeCalls by mutableIntStateOf(0)
        var state by mutableStateOf(
            buildState(active = trackA).copy(
                replayGainConfig = ReplayGainConfig(mode = ReplayGainMode.TRACK, preampDb = 0f),
            ),
        )

        composeRule.setContent {
            BitPerfectPlayerTheme {
                RenderRootPagerNavHost(
                    state = state,
                    posFlow = MutableStateFlow(10_000L),
                    showMiniPlayerButton = true,
                    onSetReplayGainPreampDb = { newDb ->
                        preampChangeCalls++
                        state = state.copy(replayGainConfig = state.replayGainConfig.copy(preampDb = newDb))
                    },
                )
            }
        }
        advanceSettle()
        composeRule.onNodeWithContentDescription("Settings").performClick()
        advanceSettle()

        val sliderNode = composeRule.onNodeWithTag(REPLAY_GAIN_PREAMP_SLIDER_TEST_TAG)
        sliderNode.performTouchInput { swipeRight() }
        advanceSettle()
        sliderNode.performTouchInput {
            swipe(start = Offset(width * 0.25f, centerY), end = Offset(width * 0.75f, centerY), durationMillis = 150L)
        }
        advanceSettle()

        assertTrue(preampChangeCalls > 0)
        composeRule.onNodeWithText("AUDIO ENGINE").assertIsDisplayed()
        composeRule.onNodeWithText("Now Playing").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w411dp-h915dp")
    fun miniPlayerBar_swipeRightToLeftAnimatesOutAndClearsSession_whileRightwardShortDragAndTapsWork() {
        composeRule.mainClock.autoAdvance = false
        var state by mutableStateOf(buildState(active = trackA))
        val posFlow = MutableStateFlow(30_000L)
        var openCalls by mutableIntStateOf(0)
        var toggleCalls by mutableIntStateOf(0)
        var nextCalls by mutableIntStateOf(0)
        var dismissCalls by mutableIntStateOf(0)

        composeRule.setContent {
            BitPerfectPlayerTheme {
                MiniPlayerBar(
                    playbackState = state,
                    playbackPositionMsFlow = posFlow,
                    artworkLoader = artworkLoader,
                    onOpenNowPlaying = { openCalls++ },
                    onTogglePlayPause = { toggleCalls++ },
                    onNext = { nextCalls++ },
                    onDismissPlayback = {
                        dismissCalls++
                        state = state.copy(queue = QueueSnapshot(), activeTrack = null)
                    },
                )
            }
        }
        advanceSettle(48L)

        composeRule.onNodeWithContentDescription("Pause playback").performClick()
        composeRule.onNodeWithContentDescription("Next track").performClick()
        composeRule.onNodeWithContentDescription("Open Now Playing for ${trackA.title}").performClick()
        advanceSettle(48L)
        assertEquals(1, toggleCalls)
        assertEquals(1, nextCalls)
        assertEquals(1, openCalls)
        assertEquals(0, dismissCalls)

        // Left -> right swipe does not dismiss
        composeRule.onNodeWithTag(MINI_PLAYER_BAR_TEST_TAG).performTouchInput {
            swipe(start = Offset(width * 0.15f, centerY), end = Offset(width * 0.85f, centerY), durationMillis = 150L)
        }
        advanceSettle(240L)
        assertEquals(0, dismissCalls)
        composeRule.onNodeWithTag(MINI_PLAYER_BAR_TEST_TAG).assertIsDisplayed()

        // Short slow drag below threshold springs back
        composeRule.onNodeWithTag(MINI_PLAYER_BAR_TEST_TAG).performTouchInput {
            swipe(start = Offset(width * 0.55f, centerY), end = Offset(width * 0.45f, centerY), durationMillis = 350L)
        }
        advanceSettle(240L)
        assertEquals(0, dismissCalls)
        composeRule.onNodeWithTag(MINI_PLAYER_BAR_TEST_TAG).assertIsDisplayed()

        // Full right -> left swipe animates out (140 ms) and clears activeTrack
        composeRule.onNodeWithTag(MINI_PLAYER_BAR_TEST_TAG).performTouchInput {
            swipe(start = Offset(width * 0.88f, centerY), end = Offset(width * 0.12f, centerY), durationMillis = 150L)
        }
        advanceSettle(240L)
        assertEquals(1, dismissCalls)
        assertNull(state.activeTrack)
        composeRule.onNodeWithTag(MINI_PLAYER_BAR_TEST_TAG).assertDoesNotExist()
    }
}
