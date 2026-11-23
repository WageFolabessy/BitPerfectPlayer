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
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
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
import com.cangkir.bitperfectplayer.ui.nowplaying.SETTINGS_SWIPE_CONTAINER_TEST_TAG
import com.cangkir.bitperfectplayer.ui.playlists.PlaylistsScreen
import com.cangkir.bitperfectplayer.ui.settings.REPLAY_GAIN_PREAMP_SLIDER_TEST_TAG
import com.cangkir.bitperfectplayer.ui.settings.SettingsScreen
import com.cangkir.bitperfectplayer.ui.theme.BitPerfectPlayerTheme
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

    private fun advanceSettle(ms: Long = 260L) {
        composeRule.mainClock.advanceTimeBy(ms)
    }

    @Composable
    private fun RenderTestNowPlaying(
        state: PlaybackControllerState,
        posFlow: MutableStateFlow<Long>,
        artworkStyle: NowPlayingArtworkStyle = NowPlayingArtworkStyle.VINYL_DISC,
        onBack: () -> Unit = {},
        onOpenSettings: () -> Unit = {},
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
            onOpenSettings = onOpenSettings,
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

        // Simulate synced GAPLESS_SWAP to Track B (activeTrack + engineState.activeTrackUri both Track B)
        composeRule.runOnIdle {
            playbackState = buildState(active = trackB, engineUri = trackB.uriString)
            posFlow.value = 0L
        }
        advanceSettle(48L)

        composeRule.onNodeWithText("Jesus of Suburbia").assertIsDisplayed()
        composeRule.onNodeWithText("05:00").assertIsDisplayed()

        // Clicking midpoint of Slider must invoke onSeekTo AND update displayed timestamp (not stuck at 00:00)
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
    fun nowPlayingScreen_bodySwipeRightGoesBackAndSwipeLeftOpensSettings() {
        composeRule.mainClock.autoAdvance = false
        var backCalls by mutableIntStateOf(0)
        var settingsCalls by mutableIntStateOf(0)

        composeRule.setContent {
            BitPerfectPlayerTheme {
                RenderTestNowPlaying(
                    state = buildState(active = trackA),
                    posFlow = MutableStateFlow(12_000L),
                    onBack = { backCalls++ },
                    onOpenSettings = { settingsCalls++ },
                )
            }
        }
        advanceSettle(48L)

        composeRule.onNodeWithTag(NOW_PLAYING_SWIPE_CONTAINER_TEST_TAG).performTouchInput {
            val yMargin = top + 24f
            swipe(
                start = Offset(width * 0.08f, yMargin),
                end = Offset(width * 0.88f, yMargin),
                durationMillis = 150L,
            )
        }
        advanceSettle(48L)
        assertEquals(1, backCalls)
        assertEquals(0, settingsCalls)

        composeRule.onNodeWithTag(NOW_PLAYING_SWIPE_CONTAINER_TEST_TAG).performTouchInput {
            val yMargin = top + 24f
            swipe(
                start = Offset(width * 0.92f, yMargin),
                end = Offset(width * 0.12f, yMargin),
                durationMillis = 150L,
            )
        }
        advanceSettle(48L)
        assertEquals(1, backCalls)
        assertEquals(1, settingsCalls)
    }

    @Test
    @Config(qualifiers = "w411dp-h915dp")
    fun nowPlayingScreen_swipeInsideVinylDiscIncludingSpindle_doesNotTriggerNavigation() {
        composeRule.mainClock.autoAdvance = false
        var backCalls by mutableIntStateOf(0)
        var settingsCalls by mutableIntStateOf(0)

        composeRule.setContent {
            BitPerfectPlayerTheme {
                RenderTestNowPlaying(
                    state = buildState(active = trackA),
                    posFlow = MutableStateFlow(45_000L),
                    onBack = { backCalls++ },
                    onOpenSettings = { settingsCalls++ },
                )
            }
        }
        advanceSettle(48L)

        val vinylNode = composeRule.onNodeWithContentDescription(
            "Vinyl disc turntable for ${trackA.title}. Drag circularly to seek.",
        )

        // Horizontal drag across center spindle (both right and left) must NOT navigate away
        vinylNode.performTouchInput {
            swipe(start = center, end = Offset(right - 12f, centerY), durationMillis = 150L)
        }
        advanceSettle(48L)
        vinylNode.performTouchInput {
            swipe(start = center, end = Offset(left + 12f, centerY), durationMillis = 150L)
        }
        advanceSettle(48L)

        // Horizontal drag across upper vinyl scrub groove ring must scrub, never navigate
        vinylNode.performTouchInput {
            swipe(
                start = Offset(centerX - width * 0.28f, centerY - height * 0.22f),
                end = Offset(centerX + width * 0.28f, centerY - height * 0.22f),
                durationMillis = 150L,
            )
        }
        advanceSettle(48L)

        assertEquals(0, backCalls)
        assertEquals(0, settingsCalls)
    }

    @Test
    @Config(qualifiers = "w411dp-h915dp")
    fun nowPlayingScreen_swipeOnSeekSlider_seeksWithoutTriggeringNavigation() {
        composeRule.mainClock.autoAdvance = false
        var backCalls by mutableIntStateOf(0)
        var settingsCalls by mutableIntStateOf(0)
        var seekCalls by mutableIntStateOf(0)
        val posFlow = MutableStateFlow(60_000L)

        composeRule.setContent {
            BitPerfectPlayerTheme {
                RenderTestNowPlaying(
                    state = buildState(active = trackB),
                    posFlow = posFlow,
                    onBack = { backCalls++ },
                    onOpenSettings = { settingsCalls++ },
                    onSeekTo = {
                        seekCalls++
                        posFlow.value = it
                    },
                )
            }
        }
        advanceSettle(48L)

        val sliderNode = composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
        // Even edge-to-edge swipes starting on Slider's touch overhang must never trigger screen swipe
        sliderNode.performTouchInput { swipeRight() }
        advanceSettle(48L)
        sliderNode.performTouchInput { swipeLeft() }
        advanceSettle(48L)
        // Dragging along the active track updates seek position without navigating
        sliderNode.performTouchInput {
            swipe(start = Offset(width * 0.25f, centerY), end = Offset(width * 0.75f, centerY), durationMillis = 150L)
        }
        advanceSettle(48L)

        assertEquals(0, backCalls)
        assertEquals(0, settingsCalls)
        assertTrue(seekCalls > 0)
    }

    @Test
    @Config(qualifiers = "w411dp-h915dp")
    fun nowPlayingScreen_squareCoverSwipeChangesTrack_whileBodySwipeNavigates() {
        composeRule.mainClock.autoAdvance = false
        var backCalls by mutableIntStateOf(0)
        var settingsCalls by mutableIntStateOf(0)
        var nextCalls by mutableIntStateOf(0)
        var prevCalls by mutableIntStateOf(0)

        composeRule.setContent {
            BitPerfectPlayerTheme {
                RenderTestNowPlaying(
                    state = buildState(active = trackA),
                    posFlow = MutableStateFlow(15_000L),
                    artworkStyle = NowPlayingArtworkStyle.SQUARE_COVER,
                    onBack = { backCalls++ },
                    onOpenSettings = { settingsCalls++ },
                    onPrevious = { prevCalls++ },
                    onNext = { nextCalls++ },
                )
            }
        }
        advanceSettle(48L)

        composeRule.onNodeWithTag(NOW_PLAYING_SQUARE_ARTWORK_TEST_TAG).performTouchInput { swipeLeft() }
        advanceSettle(48L)
        composeRule.onNodeWithTag(NOW_PLAYING_SQUARE_ARTWORK_TEST_TAG).performTouchInput { swipeRight() }
        advanceSettle(48L)

        assertEquals(1, nextCalls)
        assertEquals(1, prevCalls)
        assertEquals(0, backCalls)
        assertEquals(0, settingsCalls)

        composeRule.onNodeWithTag(NOW_PLAYING_SWIPE_CONTAINER_TEST_TAG).performTouchInput {
            val yMargin = top + 24f
            swipe(
                start = Offset(width * 0.92f, yMargin),
                end = Offset(width * 0.12f, yMargin),
                durationMillis = 150L,
            )
        }
        advanceSettle(48L)
        assertEquals(1, settingsCalls)
    }

    @Composable
    private fun RenderSwipeTestNavHost(state: PlaybackControllerState, posFlow: MutableStateFlow<Long>, showMiniPlayerButton: Boolean) {
        val navController = rememberNavController()
        BitPerfectStartupNavHost(
            startupHomepage = DefaultHomepage.ALL_SONGS,
            navController = navController,
            miniPlayerContent = {
                if (showMiniPlayerButton) {
                    Button(onClick = { navController.navigate(NOW_PLAYING_ROUTE) { launchSingleTop = true } }) {
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
                        onPlayTracks = { _, _, _ -> navController.navigate(NOW_PLAYING_ROUTE) { launchSingleTop = true } },
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
                if (showMiniPlayerButton) {
                    Text("Playlists Placeholder")
                } else {
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
                }
            },
            settingsContent = {
                SettingsScreen(
                    libraryState = LibrarySnapshot(),
                    playbackState = state,
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
                    onBack = null,
                    onSwipeToNowPlaying = if (state.activeTrack != null) {
                        { navController.navigate(NOW_PLAYING_ROUTE) { launchSingleTop = true } }
                    } else {
                        null
                    },
                )
            },
        ) {
            composable(NOW_PLAYING_ROUTE) {
                RenderTestNowPlaying(
                    state = state,
                    posFlow = posFlow,
                    onBack = { navController.popBackStack() },
                    onOpenSettings = { navigateToSettingsFromNowPlaying(navController) },
                )
            }
            composable(NOW_PLAYING_SETTINGS_ROUTE) {
                SettingsScreen(
                    libraryState = LibrarySnapshot(),
                    playbackState = state,
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
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h915dp")
    fun navHost_nowPlayingHorizontalSwipesMaintainCleanSettingsAndLastScreenBackstack() {
        composeRule.mainClock.autoAdvance = false
        val state = buildState(active = trackA)
        val posFlow = MutableStateFlow(10_000L)
        composeRule.setContent {
            BitPerfectPlayerTheme {
                RenderSwipeTestNavHost(state = state, posFlow = posFlow, showMiniPlayerButton = false)
            }
        }
        advanceSettle()

        // Flow A: Library -> NowPlaying -> swipe left to NowPlayingSettings -> swipe right back to NowPlaying -> swipe right to Library
        composeRule.onNodeWithText("American Idiot").performClick()
        advanceSettle()
        composeRule.onNodeWithText("Now Playing").assertIsDisplayed()
        // Zero floating "Back" or "Settings" pill text nodes exist on NowPlayingScreen
        composeRule.onNodeWithText("Back").assertDoesNotExist()
        composeRule.onNodeWithText("Settings").assertDoesNotExist()

        composeRule.onNodeWithTag(NOW_PLAYING_SWIPE_CONTAINER_TEST_TAG).performTouchInput {
            val yMargin = top + 24f
            swipe(Offset(width * 0.92f, yMargin), Offset(width * 0.12f, yMargin), durationMillis = 150L)
        }
        advanceSettle()
        composeRule.onNodeWithContentDescription("Back").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Bottom navigation bar").assertDoesNotExist()

        // Bidirectional horizontal swipe: swipe left -> right on NowPlayingSettings returns to NowPlaying
        composeRule.onNodeWithTag(SETTINGS_SWIPE_CONTAINER_TEST_TAG).performTouchInput {
            val yMargin = top + 24f
            swipe(Offset(width * 0.08f, yMargin), Offset(width * 0.88f, yMargin), durationMillis = 150L)
        }
        advanceSettle()
        composeRule.onNodeWithText("Now Playing").assertIsDisplayed()

        composeRule.onNodeWithTag(NOW_PLAYING_SWIPE_CONTAINER_TEST_TAG).performTouchInput {
            val yMargin = top + 24f
            swipe(Offset(width * 0.08f, yMargin), Offset(width * 0.88f, yMargin), durationMillis = 150L)
        }
        advanceSettle()
        composeRule.onNodeWithContentDescription("Bottom navigation bar").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w411dp-h915dp")
    fun navHost_nowPlayingOpenedFromSettingsAndSwipedLeft_popsCleanlyWithoutDuplicateSettings() {
        composeRule.mainClock.autoAdvance = false
        val state = buildState(active = trackA)
        val posFlow = MutableStateFlow(10_000L)
        composeRule.setContent {
            BitPerfectPlayerTheme {
                RenderSwipeTestNavHost(state = state, posFlow = posFlow, showMiniPlayerButton = true)
            }
        }
        advanceSettle()

        composeRule.onNodeWithContentDescription("Settings").performClick()
        advanceSettle()
        composeRule.onNodeWithContentDescription("Back").assertDoesNotExist()

        composeRule.onNodeWithText("Open Test Player").performClick()
        advanceSettle()
        composeRule.onNodeWithText("Now Playing").assertIsDisplayed()

        // Swipe right-to-left while previousBackStackEntry is top-level Settings -> pops cleanly to Settings tab
        composeRule.onNodeWithTag(NOW_PLAYING_SWIPE_CONTAINER_TEST_TAG).performTouchInput {
            val yMargin = top + 24f
            swipe(Offset(width * 0.92f, yMargin), Offset(width * 0.12f, yMargin), durationMillis = 150L)
        }
        advanceSettle()
        composeRule.onNodeWithContentDescription("Bottom navigation bar").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Back").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w411dp-h915dp")
    fun bottomNavSettingsTab_swipeLeftToRightOpensNowPlayingOnlyWhenActiveTrackExists_andSwipeRightToLeftIsNoOp() {
        composeRule.mainClock.autoAdvance = false
        var state by mutableStateOf(
            buildState(active = trackA).copy(
                queue = QueueSnapshot(),
                activeTrack = null,
            ),
        )
        val posFlow = MutableStateFlow(10_000L)
        composeRule.setContent {
            BitPerfectPlayerTheme {
                RenderSwipeTestNavHost(state = state, posFlow = posFlow, showMiniPlayerButton = true)
            }
        }
        advanceSettle()

        // Navigate to bottom-nav Settings tab while activeTrack == null
        composeRule.onNodeWithContentDescription("Settings").performClick()
        advanceSettle()
        composeRule.onNodeWithText("AUDIO ENGINE").assertIsDisplayed()

        // 1. With activeTrack == null, swiping left -> right on bottom-nav Settings must be a strict no-op
        composeRule.onNodeWithTag(SETTINGS_SWIPE_CONTAINER_TEST_TAG).performTouchInput {
            val yMargin = top + 24f
            swipe(Offset(width * 0.08f, yMargin), Offset(width * 0.88f, yMargin), durationMillis = 150L)
        }
        advanceSettle()
        composeRule.onNodeWithText("AUDIO ENGINE").assertIsDisplayed()
        composeRule.onNodeWithText("Now Playing").assertDoesNotExist()

        // 2. Activate a track, verify right -> left swipe on bottom-nav Settings is still a no-op
        composeRule.runOnIdle {
            state = buildState(active = trackA)
        }
        advanceSettle(48L)
        composeRule.onNodeWithTag(SETTINGS_SWIPE_CONTAINER_TEST_TAG).performTouchInput {
            val yMargin = top + 24f
            swipe(Offset(width * 0.92f, yMargin), Offset(width * 0.12f, yMargin), durationMillis = 150L)
        }
        advanceSettle()
        composeRule.onNodeWithText("AUDIO ENGINE").assertIsDisplayed()

        // 3. With activeTrack != null, swiping left -> right on bottom-nav Settings opens Now Playing
        composeRule.onNodeWithTag(SETTINGS_SWIPE_CONTAINER_TEST_TAG).performTouchInput {
            val yMargin = top + 24f
            swipe(Offset(width * 0.08f, yMargin), Offset(width * 0.88f, yMargin), durationMillis = 150L)
        }
        advanceSettle()
        composeRule.onNodeWithText("Now Playing").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Bottom navigation bar").assertDoesNotExist()

        // 4. Round-trip: swiping right -> left on Now Playing pops back to bottom-nav Settings without duplicate Settings
        composeRule.onNodeWithTag(NOW_PLAYING_SWIPE_CONTAINER_TEST_TAG).performTouchInput {
            val yMargin = top + 24f
            swipe(Offset(width * 0.92f, yMargin), Offset(width * 0.12f, yMargin), durationMillis = 150L)
        }
        advanceSettle()
        composeRule.onNodeWithContentDescription("Bottom navigation bar").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Back").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w411dp-h915dp")
    fun settingsScreen_swipeOnReplayGainPreampSlider_adjustsPreampWithoutTriggeringPageSwipe() {
        composeRule.mainClock.autoAdvance = false
        var swipeToNowPlayingCalls by mutableIntStateOf(0)
        var preampChangeCalls by mutableIntStateOf(0)
        var state by mutableStateOf(
            buildState(active = trackA).copy(
                replayGainConfig = ReplayGainConfig(mode = ReplayGainMode.TRACK, preampDb = 0f),
            ),
        )

        composeRule.setContent {
            BitPerfectPlayerTheme {
                SettingsScreen(
                    libraryState = LibrarySnapshot(),
                    playbackState = state,
                    sleepTimerStateFlow = MutableStateFlow(SleepTimerState()),
                    onImportFolder = {},
                    onRemoveSafFolder = {},
                    onRefreshLibrary = {},
                    onSetGaplessEnabled = {},
                    onSetReplayGainMode = {},
                    onSetReplayGainPreampDb = { newDb ->
                        preampChangeCalls++
                        state = state.copy(replayGainConfig = state.replayGainConfig.copy(preampDb = newDb))
                    },
                    onSetReplayGainPreventClipping = {},
                    onSelectSleepTimerPreset = {},
                    onExportLocalBackup = {},
                    onImportLocalBackup = {},
                    onOpenDiagnostics = {},
                    onBack = null,
                    onSwipeToNowPlaying = { swipeToNowPlayingCalls++ },
                )
            }
        }
        advanceSettle(48L)

        val sliderNode = composeRule.onNodeWithTag(REPLAY_GAIN_PREAMP_SLIDER_TEST_TAG)
        sliderNode.performTouchInput { swipeRight() }
        advanceSettle(48L)
        sliderNode.performTouchInput { swipeLeft() }
        advanceSettle(48L)
        sliderNode.performTouchInput {
            swipe(start = Offset(width * 0.25f, centerY), end = Offset(width * 0.75f, centerY), durationMillis = 150L)
        }
        advanceSettle(48L)

        assertEquals(0, swipeToNowPlayingCalls)
        assertTrue(preampChangeCalls > 0)
    }

    @Test
    @Config(qualifiers = "w411dp-h915dp")
    fun miniPlayerBar_swipeRightToLeftDismissesAndClearsSession_whileLeftToRightShortSwipeAndTapsWork() {
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

        // 1. Tapping Play/Pause, Next, and body still work normally
        composeRule.onNodeWithContentDescription("Pause playback").performClick()
        composeRule.onNodeWithContentDescription("Next track").performClick()
        composeRule.onNodeWithContentDescription("Open Now Playing for ${trackA.title}").performClick()
        advanceSettle(48L)
        assertEquals(1, toggleCalls)
        assertEquals(1, nextCalls)
        assertEquals(1, openCalls)
        assertEquals(0, dismissCalls)

        // 2. Left-to-right swipe (wrong direction) must NOT dismiss MiniPlayerBar
        composeRule.onNodeWithTag(MINI_PLAYER_BAR_TEST_TAG).performTouchInput {
            swipe(start = Offset(width * 0.15f, centerY), end = Offset(width * 0.85f, centerY), durationMillis = 150L)
        }
        advanceSettle(120L)
        assertEquals(0, dismissCalls)
        composeRule.onNodeWithTag(MINI_PLAYER_BAR_TEST_TAG).assertIsDisplayed()

        // 3. Short slow right-to-left drag below threshold springs back without dismissing
        composeRule.onNodeWithTag(MINI_PLAYER_BAR_TEST_TAG).performTouchInput {
            swipe(start = Offset(width * 0.55f, centerY), end = Offset(width * 0.45f, centerY), durationMillis = 350L)
        }
        advanceSettle(120L)
        assertEquals(0, dismissCalls)
        composeRule.onNodeWithTag(MINI_PLAYER_BAR_TEST_TAG).assertIsDisplayed()

        // 4. Full right-to-left swipe dismisses and clears activeTrack so MiniPlayerBar disappears
        composeRule.onNodeWithTag(MINI_PLAYER_BAR_TEST_TAG).performTouchInput {
            swipe(start = Offset(width * 0.88f, centerY), end = Offset(width * 0.12f, centerY), durationMillis = 150L)
        }
        advanceSettle(120L)
        assertEquals(1, dismissCalls)
        assertNull(state.activeTrack)
        composeRule.onNodeWithTag(MINI_PLAYER_BAR_TEST_TAG).assertDoesNotExist()
    }
}
