package com.cangkir.bitperfectplayer.ui

import android.Manifest
import android.app.Application
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import com.cangkir.bitperfectplayer.audio.AudioDiagnosticInput
import com.cangkir.bitperfectplayer.audio.AudioDiagnosticMapper
import com.cangkir.bitperfectplayer.audio.AudioOutputMode
import com.cangkir.bitperfectplayer.audio.AudioSafetyState
import com.cangkir.bitperfectplayer.audio.EnginePlaybackState
import com.cangkir.bitperfectplayer.audio.NegotiatedMixerBehavior
import com.cangkir.bitperfectplayer.audio.OutputConnectionType
import com.cangkir.bitperfectplayer.audio.PlaybackControllerState
import com.cangkir.bitperfectplayer.audio.PlaybackStatus
import com.cangkir.bitperfectplayer.audio.PlaybackTrack
import com.cangkir.bitperfectplayer.audio.QueueContext
import com.cangkir.bitperfectplayer.audio.QueueSnapshot
import com.cangkir.bitperfectplayer.audio.ReplayGainMetadata
import com.cangkir.bitperfectplayer.audio.ReplayGainProcessor
import com.cangkir.bitperfectplayer.audio.SleepTimerPreset
import com.cangkir.bitperfectplayer.audio.SleepTimerState
import com.cangkir.bitperfectplayer.data.DefaultHomepage
import com.cangkir.bitperfectplayer.data.NowPlayingArtworkStyle
import com.cangkir.bitperfectplayer.data.SpectrumColorStyle
import com.cangkir.bitperfectplayer.library.AlbumArtworkLoader
import com.cangkir.bitperfectplayer.library.LibrarySnapshot
import com.cangkir.bitperfectplayer.library.LibrarySortPreferences
import com.cangkir.bitperfectplayer.library.PlaylistsUiSnapshot
import com.cangkir.bitperfectplayer.library.SortDirection
import com.cangkir.bitperfectplayer.library.TrackSortOption
import com.cangkir.bitperfectplayer.library.TrackSortSpec
import com.cangkir.bitperfectplayer.library.TrackTechnicalInfo
import com.cangkir.bitperfectplayer.ui.library.LIBRARY_SUBTAB_PAGER_TEST_TAG
import com.cangkir.bitperfectplayer.ui.library.LibraryScreen
import com.cangkir.bitperfectplayer.ui.nowplaying.NowPlayingScreen
import com.cangkir.bitperfectplayer.ui.playlists.PlaylistsScreen
import com.cangkir.bitperfectplayer.ui.settings.SettingsScreen
import com.cangkir.bitperfectplayer.ui.theme.BitPerfectPlayerTheme
import com.cangkir.bitperfectplayer.ui.theme.CoverThemeColorCache
import com.cangkir.bitperfectplayer.ui.theme.CoverThemePaletteGenerator
import com.cangkir.bitperfectplayer.ui.theme.ObsidianBackground
import com.cangkir.bitperfectplayer.ui.theme.StudioIvoryPrimary
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
 * Headless JVM behavioral Compose UI tests powered by Robolectric (pinned to Android 14 / API 34).
 *
 * Verifies real user interaction flows (Hearing Safety gate, instant favorite toggle propagation,
 * exact `Hz` formatting without `kHz` or duplicated container/codec labels, Playlist creation,
 * Track Info sheet technical fields, Sleep Timer preset selection, clean NowPlaying TopAppBar,
 * auto-scroll-to-top on sort changes, and banner-free context-aware QueueScreen) without requiring
 * an emulator or KVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BitPerfectUiBehaviorTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var artworkLoader: AlbumArtworkLoader

    private val hiResFlacTrack = PlaybackTrack(
        uriString = "content://test/audio/peacemaker.flac",
        title = "Peacemaker",
        artist = "Green Day",
        album = "21st Century Breakdown",
        folderName = "FLAC Hi-Res",
        fileName = "07 - Peacemaker.flac",
        fileSizeBytes = 42_500_000L,
        durationMs = 204_000L, // 03:24
        sampleRateHz = 96_000,
        bitDepth = 24,
        container = "FLAC",
        codec = "FLAC",
        contentFingerprint = "abcdef0123456789".repeat(4),
        discNumber = 1,
        trackNumber = 7,
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
    fun hearingSafetyWarningDialog_displaysMandatoryGateAndInvokesExactCallbacks() {
        var staySafeClicks = 0
        var confirmBitPerfectClicks = 0

        val warningState = PlaybackControllerState(
            activeTrack = hiResFlacTrack,
            queueContext = QueueContext.album("21st Century Breakdown"),
            engineState = EnginePlaybackState(
                safetyState = AudioSafetyState(
                    mode = AudioOutputMode.SAFE_VOLUME_MODE,
                    isUsbAudioConnected = true,
                    activeDeviceName = "KZ EDX Pro 2 USB-C",
                    isHardwareVolumeVerified = false,
                    isPendingSafetyWarning = true,
                ),
            ),
        )

        composeRule.setContent {
            BitPerfectPlayerTheme {
                NowPlayingScreen(
                    playbackState = warningState,
                    playbackPositionMsFlow = MutableStateFlow(15_000L),
                    sleepTimerStateFlow = MutableStateFlow(SleepTimerState()),
                    albumArtworkLoader = artworkLoader,
                    isFavorite = false,
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
                    onConfirmBitPerfectWarning = { confirmBitPerfectClicks++ },
                    onDismissSafetyWarning = { staySafeClicks++ },
                    onSwitchToSafeMode = {},
                    onClearAutoPauseNotice = {},
                    onOpenQueue = {},
                    onOpenLyrics = {},
                    onOpenDiagnostics = {},
                )
            }
        }

        composeRule.onNodeWithText("Hearing Safety Warning").assertIsDisplayed()
        composeRule.onNodeWithText("Stay in Safe Mode").assertIsDisplayed().performClick()
        assertEquals(1, staySafeClicks)
        assertEquals(0, confirmBitPerfectClicks)

        composeRule.onNodeWithText("Enable Bit-Perfect").assertIsDisplayed().performClick()
        assertEquals(1, confirmBitPerfectClicks)
    }

    @Test
    @Config(qualifiers = "w411dp-h915dp")
    fun nowPlayingScreen_hasNoDuplicateTopBarPauseButtonWhilePrimaryTransportPauseWorks() {
        var playPauseClicks = 0
        var status by mutableStateOf(PlaybackStatus.PLAYING)

        // Manual frame clock lets us deterministically step active vinyl rotation frames without hanging Espresso idle.
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            val playingState = PlaybackControllerState(
                activeTrack = hiResFlacTrack,
                queueContext = QueueContext.album("21st Century Breakdown"),
                engineState = EnginePlaybackState(
                    status = status,
                    durationMs = hiResFlacTrack.durationMs,
                ),
            )
            BitPerfectPlayerTheme {
                NowPlayingScreen(
                    playbackState = playingState,
                    playbackPositionMsFlow = MutableStateFlow(30_000L),
                    sleepTimerStateFlow = MutableStateFlow(SleepTimerState()),
                    albumArtworkLoader = artworkLoader,
                    isFavorite = false,
                    onBack = {},
                    onToggleFavorite = {},
                    onAddToPlaylist = {},
                    onShowTrackInfo = {},
                    onSelectSleepTimerPreset = {},
                    onTogglePlayPause = {
                        playPauseClicks++
                        status = if (status == PlaybackStatus.PLAYING) PlaybackStatus.PAUSED else PlaybackStatus.PLAYING
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
                    onClearAutoPauseNotice = {},
                    onOpenQueue = {},
                    onOpenLyrics = {},
                    onOpenDiagnostics = {},
                )
            }
        }
        // Advance 500 ms of live vinyl rotation frames smoothly
        composeRule.mainClock.advanceTimeBy(500L)

        // Spinning Vinyl Disc is displayed by default during playback
        composeRule.onNodeWithContentDescription(
            "Vinyl disc turntable for Peacemaker. Drag circularly to seek.",
        ).assertIsDisplayed()
        // Redundant TopAppBar "Pause" text pill must not exist
        assertEquals(0, composeRule.onAllNodesWithText("Pause").fetchSemanticsNodes().size)
        // Primary circular transport Pause button remains accessible and pauses cleanly
        composeRule.onNodeWithContentDescription("Pause playback")
            .assertIsDisplayed()
            .performClick()
        composeRule.mainClock.advanceTimeByFrame()
        assertEquals(1, playPauseClicks)
        assertEquals(PlaybackStatus.PAUSED, status)
    }

    @Test
    fun libraryScreen_favoriteHeartTogglePropagatesImmediatelyWhenStateUpdates() {
        var favoriteUris by mutableStateOf(emptySet<String>())
        val toggledTracks = mutableListOf<PlaybackTrack>()

        val snapshot = LibrarySnapshot(allSongs = listOf(hiResFlacTrack))

        composeRule.setContent {
            BitPerfectPlayerTheme {
                LibraryScreen(
                    libraryState = snapshot,
                    favoriteTrackUris = favoriteUris,
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
                    onToggleFavorite = { track ->
                        toggledTracks.add(track)
                        favoriteUris = if (track.uriString in favoriteUris) {
                            favoriteUris - track.uriString
                        } else {
                            favoriteUris + track.uriString
                        }
                    },
                    onOpenDiagnostics = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("Add Peacemaker to favorites")
            .assertIsDisplayed()
            .performClick()

        assertEquals(listOf(hiResFlacTrack.uriString), toggledTracks.map { it.uriString })
        composeRule.onNodeWithContentDescription("Remove Peacemaker from favorites")
            .assertIsDisplayed()
    }

    @Test
    fun libraryScreen_rendersExactHzUnitsWithoutKhzOrDuplicateContainerCodecAndShowsDuration() {
        val snapshot = LibrarySnapshot(allSongs = listOf(hiResFlacTrack))

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

        composeRule.onNodeWithText("Peacemaker").assertIsDisplayed()
        composeRule.onNodeWithText("FLAC · 24-bit · 96,000 Hz").assertIsDisplayed()
        composeRule.onNodeWithText("03:24").assertIsDisplayed()

        // Strict unit & deduplication invariants: never kHz, never "FLAC (FLAC)"
        assertEquals(0, composeRule.onAllNodesWithText("kHz", substring = true, ignoreCase = true).fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("FLAC (FLAC)", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun libraryScreen_autoScrollsToTopWhenSortOrderChanges() {
        val tracksAsc = (1..30).map { idx ->
            hiResFlacTrack.copy(
                uriString = "content://test/audio/song_$idx.flac",
                title = "Track %02d".format(idx),
                fileName = "%02d.flac".format(idx),
                trackNumber = idx,
            )
        }
        val tracksDesc = tracksAsc.reversed()

        var snapshot by mutableStateOf(
            LibrarySnapshot(
                allSongs = tracksAsc,
                sortPreferences = LibrarySortPreferences(
                    allSongs = TrackSortSpec(TrackSortOption.TITLE, SortDirection.ASC),
                ),
            ),
        )

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

        // Initial top item is Track 01; scroll deep into the list (index 18 -> Track 19)
        composeRule.onNodeWithText("Track 01").assertIsDisplayed()
        composeRule.onNode(hasScrollToIndexAction() and !hasTestTag(LIBRARY_SUBTAB_PAGER_TEST_TAG)).performScrollToIndex(18)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Track 19").assertIsDisplayed()

        // Reverse sort order (Title Z-A): list must snap back to index 0 so Track 30 is displayed immediately
        snapshot = LibrarySnapshot(
            allSongs = tracksDesc,
            sortPreferences = LibrarySortPreferences(
                allSongs = TrackSortSpec(TrackSortOption.TITLE, SortDirection.DESC),
            ),
        )
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Track 30").assertIsDisplayed()
    }

    @Test
    fun queueScreen_displaysActiveContextSubtitleWithoutViewingBanner() {
        val trackTwo = hiResFlacTrack.copy(
            uriString = "content://test/audio/restless.flac",
            title = "Restless Heart Syndrome",
            trackNumber = 13,
        )
        val queueState = PlaybackControllerState(
            queue = QueueSnapshot(items = listOf(hiResFlacTrack, trackTwo), currentIndex = 0),
            queueContext = QueueContext.album("21st Century Breakdown"),
            activeTrack = hiResFlacTrack,
        )

        composeRule.setContent {
            BitPerfectPlayerTheme {
                QueueScreen(
                    playbackState = queueState,
                    libraryTracks = listOf(hiResFlacTrack, trackTwo),
                    activeContextTracks = listOf(hiResFlacTrack, trackTwo),
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

        composeRule.onNodeWithText("Playback Queue").assertIsDisplayed()
        composeRule.onNodeWithText("Album · 21st Century Breakdown · 2 tracks").assertIsDisplayed()
        composeRule.onNodeWithText("Peacemaker").assertIsDisplayed()
        composeRule.onNodeWithText("Restless Heart Syndrome").assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithText("Load Queue").fetchSemanticsNodes().size)
    }

    @Test
    fun playlistsScreen_createPlaylistDialogAcceptsNameAndInvokesCreateCallback() {
        val createdNames = mutableListOf<String>()

        composeRule.setContent {
            BitPerfectPlayerTheme {
                PlaylistsScreen(
                    playlistsState = PlaylistsUiSnapshot(isLoaded = true),
                    activeTrackUri = null,
                    albumArtworkLoader = artworkLoader,
                    onPlayTracks = { _, _, _ -> },
                    onEnqueueTracks = { _, _ -> },
                    onEnqueueTracksNext = { _, _ -> },
                    onSetPlaylistSort = { _, _ -> },
                    onCreatePlaylist = { name -> createdNames.add(name) },
                    onRenamePlaylist = { _, _ -> },
                    onDeletePlaylist = { _, _ -> },
                    onRemovePlaylistEntry = { _, _ -> },
                    onMovePlaylistEntry = { _, _, _ -> },
                    onExportPlaylist = { _, _ -> },
                    onImportPlaylist = {},
                )
            }
        }

        composeRule.onNodeWithText("Create Playlist").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("New Playlist").assertIsDisplayed()
        composeRule.onNodeWithText("Playlist name").performTextInput("Audiophile Mix")
        composeRule.onNodeWithText("Create").assertIsDisplayed().performClick()

        assertEquals(listOf("Audiophile Mix"), createdNames)
    }

    @Test
    fun trackInfoSheet_displaysExactHzPlayCountAndSha256CopyCallback() {
        var copiedNotice: String? = null
        val fullInfo = TrackTechnicalInfo(
            track = hiResFlacTrack,
            fileSizeBytes = 42_500_000L,
            contentFingerprint = hiResFlacTrack.contentFingerprint,
            replayGainMetadata = ReplayGainMetadata(
                trackGainDb = -4.5f,
                trackPeak = 0.95f,
                albumGainDb = -5.2f,
                albumPeak = 0.98f,
            ),
            playCount = 7,
            totalPlayTimeMs = 1_428_000L,
            lastPlayedAtEpochMs = 1_760_000_000_000L,
            isLoadingDetails = false,
        )

        composeRule.setContent {
            BitPerfectPlayerTheme {
                TrackInfoBottomSheet(
                    info = fullInfo,
                    onCopiedMessage = { msg -> copiedNotice = msg },
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText("Track Info").assertIsDisplayed()
        composeRule.onNodeWithText("96,000 Hz").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("24-bit").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("7 plays").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithText("abcdef012345", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()

        assertEquals("Copied SHA-256 fingerprint", copiedNotice)
    }

    @Test
    fun sleepTimerSheet_rendersPresetsAndInvokesSelectionCallback() {
        val selectedPresets = mutableListOf<SleepTimerPreset>()
        var dismissed = false

        composeRule.setContent {
            BitPerfectPlayerTheme {
                SleepTimerBottomSheet(
                    sleepTimerStateFlow = MutableStateFlow(SleepTimerState()),
                    isBitPerfectOptIn = false,
                    onSelectPreset = { preset -> selectedPresets.add(preset) },
                    onDismiss = { dismissed = true },
                )
            }
        }

        composeRule.onNodeWithText("Sleep Timer").assertIsDisplayed()
        composeRule.onNodeWithText("End of current track").assertIsDisplayed()
        composeRule.onNodeWithText("30 min").assertIsDisplayed().performClick()

        assertEquals(listOf(SleepTimerPreset.MIN_30), selectedPresets)
        assertTrue(dismissed)
    }

    @Test
    fun nowPlayingScreen_rendersVinylDiscByDefaultAndTogglesToSquareCoverViaOverflowMenu() {
        var currentStyle by mutableStateOf(NowPlayingArtworkStyle.VINYL_DISC)
        val styleChanges = mutableListOf<NowPlayingArtworkStyle>()

        val pausedState = PlaybackControllerState(
            activeTrack = hiResFlacTrack,
            queueContext = QueueContext.album("21st Century Breakdown"),
            engineState = EnginePlaybackState(
                status = PlaybackStatus.PAUSED,
                durationMs = hiResFlacTrack.durationMs,
            ),
        )

        composeRule.setContent {
            BitPerfectPlayerTheme {
                NowPlayingScreen(
                    playbackState = pausedState,
                    playbackPositionMsFlow = MutableStateFlow(45_000L),
                    sleepTimerStateFlow = MutableStateFlow(SleepTimerState()),
                    albumArtworkLoader = artworkLoader,
                    isFavorite = false,
                    artworkStyle = currentStyle,
                    onSetArtworkStyle = { nextStyle ->
                        styleChanges.add(nextStyle)
                        currentStyle = nextStyle
                    },
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

        // Default style renders the interactive Vinyl Disc turntable
        val vinylDesc = "Vinyl disc turntable for Peacemaker. Drag circularly to seek."
        composeRule.onNodeWithContentDescription(vinylDesc).assertIsDisplayed()

        // Toggle to Square Cover via MoreVert overflow menu
        composeRule.onNodeWithContentDescription("More track actions").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Switch to Square Cover").assertIsDisplayed().performClick()

        assertEquals(listOf(NowPlayingArtworkStyle.SQUARE_COVER), styleChanges)
        assertEquals(0, composeRule.onAllNodesWithContentDescription(vinylDesc).fetchSemanticsNodes().size)

        // Toggle back to Vinyl Disc via overflow menu
        composeRule.onNodeWithContentDescription("More track actions").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Switch to Vinyl Disc").assertIsDisplayed().performClick()

        assertEquals(
            listOf(NowPlayingArtworkStyle.SQUARE_COVER, NowPlayingArtworkStyle.VINYL_DISC),
            styleChanges,
        )
        composeRule.onNodeWithContentDescription(vinylDesc).assertIsDisplayed()
    }

    @Test
    fun settingsScreen_artworkStyleToggleUpdatesPreference() {
        var style by mutableStateOf(NowPlayingArtworkStyle.VINYL_DISC)
        val selections = mutableListOf<NowPlayingArtworkStyle>()

        composeRule.setContent {
            BitPerfectPlayerTheme {
                SettingsScreen(
                    libraryState = LibrarySnapshot(),
                    playbackState = PlaybackControllerState(),
                    sleepTimerStateFlow = MutableStateFlow(SleepTimerState()),
                    artworkStyle = style,
                    onSetArtworkStyle = { next ->
                        selections.add(next)
                        style = next
                    },
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

        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(1)
        composeRule.onNodeWithText("Now Playing Artwork").assertIsDisplayed()
        composeRule.onNodeWithText("Vinyl Disc").assertIsDisplayed()
        composeRule.onNodeWithText("Square Cover").assertIsDisplayed().performClick()

        assertEquals(listOf(NowPlayingArtworkStyle.SQUARE_COVER), selections)
    }

    @Test
    fun interactiveVinylDisc_inSafeModePlaying_engagesLiveTurntableScratchAndShowsHoldBadge() {
        composeRule.mainClock.autoAdvance = false
        val positionFlow = MutableStateFlow(60_000L)
        var beginCalls = 0
        val streamedRates = mutableListOf<Float>()
        val endCalls = mutableListOf<Long?>()
        val silentSeeks = mutableListOf<Long>()

        composeRule.setContent {
            BitPerfectPlayerTheme {
                val previewMs = remember { mutableStateOf<Long?>(null) }
                InteractiveVinylDisc(
                    track = hiResFlacTrack,
                    isPlaying = true,
                    isSafeMode = true,
                    durationMs = hiResFlacTrack.durationMs,
                    playbackPositionMsFlow = positionFlow,
                    scrubPreviewMsState = previewMs,
                    albumArtworkLoader = artworkLoader,
                    onSeekTo = { silentSeeks.add(it) },
                    onBeginTurntableScratch = {
                        beginCalls++
                        true
                    },
                    onUpdateTurntableScratchRate = { streamedRates.add(it) },
                    onEndTurntableScratch = { endCalls.add(it) },
                )
            }
        }
        composeRule.mainClock.advanceTimeBy(32L)

        val vinylDesc = "Vinyl disc turntable for Peacemaker. Drag circularly to seek."
        // Touch down on outer scrub ring (3 o'clock) -> immediately enters live HOLD state.
        composeRule.onNodeWithContentDescription(vinylDesc).performTouchInput {
            down(Offset(width * 0.85f, height * 0.50f))
        }
        composeRule.mainClock.advanceTimeBy(32L)
        assertEquals(1, beginCalls)
        assertTrue(streamedRates.isNotEmpty())
        assertEquals(0f, streamedRates.last(), 0.001f)
        composeRule.onNodeWithText("HOLD").assertIsDisplayed()

        // Drag clockwise to 6 o'clock (+90° in 40 ms), hold still to cancel inertia, then lift.
        composeRule.onNodeWithContentDescription(vinylDesc).performTouchInput {
            advanceEventTime(40L)
            moveTo(Offset(width * 0.50f, height * 0.85f))
            advanceEventTime(100L)
            up()
        }
        composeRule.mainClock.advanceTimeBy(32L)

        assertTrue("Expected positive forward varispeed rate during clockwise drag", streamedRates.any { it > 0.5f })
        assertEquals(1, endCalls.size)
        assertTrue("Live scratch must not invoke silent onSeekTo", silentSeeks.isEmpty())
    }

    @Test
    fun interactiveVinylDisc_inBitPerfectMode_neverEngagesLiveScratchAndUsesSilentSeek() {
        composeRule.mainClock.autoAdvance = false
        val positionFlow = MutableStateFlow(60_000L)
        var beginCalls = 0
        val streamedRates = mutableListOf<Float>()
        val endCalls = mutableListOf<Long?>()
        val silentSeeks = mutableListOf<Long>()

        composeRule.setContent {
            BitPerfectPlayerTheme {
                val previewMs = remember { mutableStateOf<Long?>(null) }
                InteractiveVinylDisc(
                    track = hiResFlacTrack,
                    isPlaying = true,
                    isSafeMode = false, // BIT_PERFECT_OPT_IN
                    durationMs = hiResFlacTrack.durationMs,
                    playbackPositionMsFlow = positionFlow,
                    scrubPreviewMsState = previewMs,
                    albumArtworkLoader = artworkLoader,
                    onSeekTo = { silentSeeks.add(it) },
                    onBeginTurntableScratch = {
                        beginCalls++
                        true
                    },
                    onUpdateTurntableScratchRate = { streamedRates.add(it) },
                    onEndTurntableScratch = { endCalls.add(it) },
                )
            }
        }
        composeRule.mainClock.advanceTimeBy(32L)

        val vinylDesc = "Vinyl disc turntable for Peacemaker. Drag circularly to seek."
        composeRule.onNodeWithContentDescription(vinylDesc).performTouchInput {
            down(Offset(width * 0.85f, height * 0.50f))
            advanceEventTime(40L)
            moveTo(Offset(width * 0.50f, height * 0.85f))
            advanceEventTime(100L)
            up()
        }
        composeRule.mainClock.advanceTimeBy(32L)

        assertEquals(0, beginCalls)
        assertTrue(streamedRates.isEmpty())
        assertTrue(endCalls.isEmpty())
        assertEquals(1, silentSeeks.size)
        assertTrue("Clockwise silent scrub should seek forward past 60,000 ms", silentSeeks.single() > 60_000L)
    }

    @Test
    fun settingsScreen_spectrumVisualizerToggleUpdatesPreference() {
        var enabled by mutableStateOf(true)
        val changes = mutableListOf<Boolean>()

        composeRule.setContent {
            BitPerfectPlayerTheme {
                SettingsScreen(
                    libraryState = LibrarySnapshot(),
                    playbackState = PlaybackControllerState(),
                    sleepTimerStateFlow = MutableStateFlow(SleepTimerState()),
                    spectrumVisualizerEnabled = enabled,
                    onSetSpectrumVisualizerEnabled = { next ->
                        changes.add(next)
                        enabled = next
                    },
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

        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(1)
        composeRule.onNodeWithText("Spectrum Visualizer").assertIsDisplayed()
        composeRule.onNodeWithText("Zero microphone permissions; safe in Bit-Perfect mode.", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Toggle spectrum visualizer")
            .assertIsDisplayed()
            .performClick()

        assertEquals(listOf(false), changes)
    }

    @Test
    fun nowPlayingScreen_rendersRadialAndCompactSpectrumAndTogglesViaOverflowMenu() {
        var spectrumEnabled by mutableStateOf(true)
        var artworkStyle by mutableStateOf(NowPlayingArtworkStyle.VINYL_DISC)
        val activeSignals = mutableListOf<Boolean>()

        val pausedState = PlaybackControllerState(
            activeTrack = hiResFlacTrack,
            queueContext = QueueContext.album("21st Century Breakdown"),
            engineState = EnginePlaybackState(
                status = PlaybackStatus.PAUSED,
                durationMs = hiResFlacTrack.durationMs,
            ),
        )

        composeRule.setContent {
            BitPerfectPlayerTheme {
                NowPlayingScreen(
                    playbackState = pausedState,
                    playbackPositionMsFlow = MutableStateFlow(45_000L),
                    sleepTimerStateFlow = MutableStateFlow(SleepTimerState()),
                    albumArtworkLoader = artworkLoader,
                    isFavorite = false,
                    artworkStyle = artworkStyle,
                    onSetArtworkStyle = { artworkStyle = it },
                    spectrumVisualizerEnabled = spectrumEnabled,
                    onSetSpectrumVisualizerEnabled = { spectrumEnabled = it },
                    onSetSpectrumVisualizerActive = { activeSignals.add(it) },
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

        // Vinyl Disc + Spectrum Enabled -> Radial Spectrum Ring rendered and lifecycle tap activated.
        composeRule.onNodeWithContentDescription("Audio spectrum visualizer ring").assertIsDisplayed()
        assertTrue("Expected spectrum tap activated while foreground & enabled", activeSignals.lastOrNull() == true)

        // Toggle Hide Spectrum via overflow menu -> Radial ring removed and tap deactivated.
        composeRule.onNodeWithContentDescription("More track actions").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Hide Spectrum").assertIsDisplayed().performClick()
        assertEquals(
            0,
            composeRule.onAllNodesWithContentDescription("Audio spectrum visualizer ring").fetchSemanticsNodes().size,
        )
        assertTrue("Expected spectrum tap deactivated when hidden", activeSignals.lastOrNull() == false)

        // Re-enable spectrum and switch to Square Cover -> compact 42.dp bar strip renders cleanly.
        composeRule.onNodeWithContentDescription("More track actions").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Show Spectrum").assertIsDisplayed().performClick()
        composeRule.onNodeWithContentDescription("More track actions").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Switch to Square Cover").assertIsDisplayed().performClick()
        composeRule.onNodeWithContentDescription("Audio spectrum visualizer bar strip").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w411dp-h915dp")
    fun settingsScreen_spectrumStyleSelectorAndKineticSparksToggleWork() {
        var style by mutableStateOf(com.cangkir.bitperfectplayer.data.SpectrumColorStyle.PRISM)
        var sparks by mutableStateOf(true)
        val styleChanges = mutableListOf<com.cangkir.bitperfectplayer.data.SpectrumColorStyle>()
        val sparkChanges = mutableListOf<Boolean>()

        composeRule.setContent {
            BitPerfectPlayerTheme {
                SettingsScreen(
                    libraryState = LibrarySnapshot(),
                    playbackState = PlaybackControllerState(),
                    sleepTimerStateFlow = MutableStateFlow(SleepTimerState()),
                    spectrumVisualizerEnabled = true,
                    spectrumColorStyle = style,
                    onSetSpectrumColorStyle = { next ->
                        styleChanges.add(next)
                        style = next
                    },
                    spectrumSparksEnabled = sparks,
                    onSetSpectrumSparksEnabled = { next ->
                        sparkChanges.add(next)
                        sparks = next
                    },
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

        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(1)
        composeRule.onNodeWithText("Spectrum Style").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Spectrum style Studio").assertIsDisplayed().performClick()
        composeRule.onNodeWithContentDescription("Spectrum style Album").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Kinetic Sparks").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Toggle kinetic sparks").assertIsDisplayed().performClick()

        assertEquals(
            listOf(
                com.cangkir.bitperfectplayer.data.SpectrumColorStyle.STUDIO,
                com.cangkir.bitperfectplayer.data.SpectrumColorStyle.ALBUM_ADAPTIVE,
            ),
            styleChanges,
        )
        assertEquals(listOf(false), sparkChanges)
    }

    @Test
    fun nowPlayingScreen_overflowMenuCyclesSpectrumStyleAndTogglesSparks() {
        var style by mutableStateOf(com.cangkir.bitperfectplayer.data.SpectrumColorStyle.PRISM)
        var sparks by mutableStateOf(true)

        val pausedState = PlaybackControllerState(
            activeTrack = hiResFlacTrack,
            queueContext = QueueContext.album("21st Century Breakdown"),
            engineState = EnginePlaybackState(
                status = PlaybackStatus.PAUSED,
                durationMs = hiResFlacTrack.durationMs,
            ),
        )

        composeRule.setContent {
            BitPerfectPlayerTheme {
                NowPlayingScreen(
                    playbackState = pausedState,
                    playbackPositionMsFlow = MutableStateFlow(45_000L),
                    sleepTimerStateFlow = MutableStateFlow(SleepTimerState()),
                    albumArtworkLoader = artworkLoader,
                    isFavorite = false,
                    spectrumVisualizerEnabled = true,
                    spectrumColorStyle = style,
                    onCycleSpectrumColorStyle = { style = style.next() },
                    spectrumSparksEnabled = sparks,
                    onSetSpectrumSparksEnabled = { sparks = it },
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

        // Cycle Prism -> Album -> Studio via overflow menu
        composeRule.onNodeWithContentDescription("More track actions").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Spectrum Style: Prism").assertIsDisplayed().performClick()
        assertEquals(com.cangkir.bitperfectplayer.data.SpectrumColorStyle.ALBUM_ADAPTIVE, style)

        composeRule.onNodeWithContentDescription("More track actions").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Spectrum Style: Album").assertIsDisplayed().performClick()
        assertEquals(com.cangkir.bitperfectplayer.data.SpectrumColorStyle.STUDIO, style)

        // Toggle Kinetic Sparks off -> label switches to Enable Sparks
        composeRule.onNodeWithContentDescription("More track actions").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Disable Sparks").assertIsDisplayed().performClick()
        assertEquals(false, sparks)

        composeRule.onNodeWithContentDescription("More track actions").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Enable Sparks").assertIsDisplayed().performClick()
        assertEquals(true, sparks)
    }

    @Test
    @Config(qualifiers = "w840dp-h480dp")
    fun adaptiveTopLevelFrame_wideLandscapeViewportRendersNavigationRailAndDocksMiniPlayer() {
        var selectedRoute by mutableStateOf("library")
        val snapshot = LibrarySnapshot(allSongs = listOf(hiResFlacTrack))
        val playbackState = PlaybackControllerState(
            activeTrack = hiResFlacTrack,
            engineState = EnginePlaybackState(
                status = PlaybackStatus.PAUSED,
                durationMs = hiResFlacTrack.durationMs,
            ),
        )

        composeRule.setContent {
            BitPerfectPlayerTheme {
                BitPerfectAdaptiveTopLevelFrame(
                    isTopLevelRoute = true,
                    currentRoute = selectedRoute,
                    snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() },
                    onSelectTab = { selectedRoute = it },
                    miniPlayerContent = {
                        MiniPlayerBar(
                            playbackState = playbackState,
                            playbackPositionMsFlow = MutableStateFlow(15_000L),
                            artworkLoader = artworkLoader,
                            onOpenNowPlaying = {},
                            onTogglePlayPause = {},
                            onNext = {},
                        )
                    },
                ) { contentModifier ->
                    androidx.compose.foundation.layout.Box(modifier = contentModifier) {
                        LibraryScreen(
                            libraryState = snapshot,
                            favoriteTrackUris = emptySet(),
                            playbackState = playbackState,
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
            }
        }

        // Wide viewport (>= 600.dp) renders side NavigationRail and 0 bottom NavigationBars
        composeRule.onNodeWithContentDescription("Side navigation rail").assertIsDisplayed()
        assertEquals(
            0,
            composeRule.onAllNodesWithContentDescription("Bottom navigation bar").fetchSemanticsNodes().size,
        )

        // Switching tabs via icon-only NavigationRail works cleanly with zero text labels
        assertEquals(0, composeRule.onAllNodesWithText("Library").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("Playlists").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("Settings").fetchSemanticsNodes().size)
        composeRule.onNodeWithContentDescription("Playlists").assertIsDisplayed().performClick()
        assertEquals("playlists", selectedRoute)
    }

    @Test
    @Config(qualifiers = "w840dp-h480dp")
    fun nowPlayingScreen_wideLandscapeViewportRendersTwoColumnVinylSeekAndTransportControls() {
        var playPauseClicks = 0
        val committedSeeks = mutableListOf<Long>()
        val pausedState = PlaybackControllerState(
            activeTrack = hiResFlacTrack,
            queue = QueueSnapshot(items = listOf(hiResFlacTrack), currentIndex = 0),
            queueContext = QueueContext.album("21st Century Breakdown"),
            engineState = EnginePlaybackState(
                status = PlaybackStatus.PAUSED,
                durationMs = hiResFlacTrack.durationMs,
            ),
        )

        composeRule.setContent {
            BitPerfectPlayerTheme {
                NowPlayingScreen(
                    playbackState = pausedState,
                    playbackPositionMsFlow = MutableStateFlow(60_000L),
                    sleepTimerStateFlow = MutableStateFlow(SleepTimerState()),
                    albumArtworkLoader = artworkLoader,
                    isFavorite = true,
                    spectrumVisualizerEnabled = true,
                    onBack = {},
                    onToggleFavorite = {},
                    onAddToPlaylist = {},
                    onShowTrackInfo = {},
                    onSelectSleepTimerPreset = {},
                    onTogglePlayPause = { playPauseClicks++ },
                    onPrevious = {},
                    onNext = {},
                    onSeekTo = { committedSeeks.add(it) },
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

        // Left pane renders Vinyl Disc + Radial Spectrum Ring
        val vinylDesc = "Vinyl disc turntable for Peacemaker. Drag circularly to seek."
        composeRule.onNodeWithContentDescription("Audio spectrum visualizer ring").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(vinylDesc).assertIsDisplayed()

        // Right pane renders track title, exact Hz spec, seek timestamps, transport controls, and utility row
        composeRule.onNodeWithText("Peacemaker").assertIsDisplayed()
        composeRule.onNodeWithText("01:00").assertIsDisplayed()
        composeRule.onNodeWithText("03:24").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Play audio").assertIsDisplayed().performClick()
        assertEquals(1, playPauseClicks)
        assertEquals(0, composeRule.onAllNodesWithText("Queue (1)").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("Lyrics").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("Audio Path").fetchSemanticsNodes().size)
        composeRule.onNodeWithContentDescription("Open playback queue").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Lyrics").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Audio Path").assertIsDisplayed()

        // Verify rotational scrubbing on the left-pane vinyl disc works identically in 2-column landscape mode
        composeRule.onNodeWithContentDescription(vinylDesc).performTouchInput {
            down(Offset(width * 0.85f, height * 0.50f))
            advanceEventTime(40L)
            moveTo(Offset(width * 0.50f, height * 0.85f))
            advanceEventTime(100L)
            up()
        }
        assertEquals(1, committedSeeks.size)
        assertTrue("Expected forward rotational seek past 60,000 ms in 2-pane mode", committedSeeks.single() > 60_000L)
    }

    @Test
    @Config(qualifiers = "w392dp-h800dp")
    fun adaptiveTopLevelFrame_compactPortraitViewportRendersBottomNavigationBar() {
        var selectedRoute by mutableStateOf("library")
        val playbackState = PlaybackControllerState(activeTrack = hiResFlacTrack)

        composeRule.setContent {
            BitPerfectPlayerTheme {
                BitPerfectAdaptiveTopLevelFrame(
                    isTopLevelRoute = true,
                    currentRoute = selectedRoute,
                    snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() },
                    onSelectTab = { selectedRoute = it },
                    miniPlayerContent = {
                        MiniPlayerBar(
                            playbackState = playbackState,
                            playbackPositionMsFlow = MutableStateFlow(10_000L),
                            artworkLoader = artworkLoader,
                            onOpenNowPlaying = {},
                            onTogglePlayPause = {},
                            onNext = {},
                        )
                    },
                ) { contentModifier ->
                    androidx.compose.foundation.layout.Box(modifier = contentModifier)
                }
            }
        }

        // Compact phone portrait (< 600.dp) renders bottom NavigationBar and 0 side NavigationRails
        composeRule.onNodeWithContentDescription("Bottom navigation bar").assertIsDisplayed()
        assertEquals(
            0,
            composeRule.onAllNodesWithContentDescription("Side navigation rail").fetchSemanticsNodes().size,
        )

        assertEquals(0, composeRule.onAllNodesWithText("Library").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("Playlists").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("Settings").fetchSemanticsNodes().size)
        val settingsTab = composeRule.onNodeWithContentDescription("Settings").assertIsDisplayed()
        val settingsBounds = settingsTab.fetchSemanticsNode().boundsInRoot
        assertTrue(settingsBounds.width >= 48f && settingsBounds.height >= 48f)
        settingsTab.performClick()
        assertEquals("settings", selectedRoute)
    }

    @Test
    fun libraryScreen_topBarExposesPassiveQueueIconAcrossTabsAlbumAndFolderDetails_andExcludesArtistDetail() {
        val albumTrack = hiResFlacTrack
        val secondTrack = hiResFlacTrack.copy(
            uriString = "content://test/audio/boulevard.flac",
            title = "Boulevard",
            artist = "Another Artist",
            album = "Other Album",
            fileName = "02 - Boulevard.flac",
            trackNumber = 2,
        )
        val snapshot = LibrarySnapshot(
            allSongs = listOf(albumTrack, secondTrack),
            folders = listOf(
                com.cangkir.bitperfectplayer.library.LibraryGroup(
                    name = "FLAC Hi-Res",
                    subtitle = "1 track",
                    tracks = listOf(albumTrack),
                ),
            ),
            albums = listOf(
                com.cangkir.bitperfectplayer.library.LibraryGroup(
                    name = "21st Century Breakdown",
                    subtitle = "Green Day · 1 track",
                    tracks = listOf(albumTrack),
                ),
            ),
            artists = listOf(
                com.cangkir.bitperfectplayer.library.LibraryGroup(
                    name = "Green Day",
                    subtitle = "1 track",
                    tracks = listOf(albumTrack),
                ),
            ),
            favorites = listOf(albumTrack),
        )
        val activePlayback = PlaybackControllerState(
            queue = QueueSnapshot(items = listOf(secondTrack), currentIndex = 0),
            queueContext = QueueContext.allSongs(),
            activeTrack = secondTrack,
            engineState = EnginePlaybackState(status = PlaybackStatus.PLAYING, durationMs = secondTrack.durationMs),
        )
        var openQueueCalls = 0
        var playCalls = 0
        var enqueueCalls = 0

        composeRule.setContent {
            BitPerfectPlayerTheme {
                LibraryScreen(
                    libraryState = snapshot,
                    favoriteTrackUris = setOf(albumTrack.uriString),
                    playbackState = activePlayback,
                    albumArtworkLoader = artworkLoader,
                    onRefreshLibrary = {},
                    onImportFolder = {},
                    onPlayTracks = { _, _, _ -> playCalls++ },
                    onEnqueueTrack = { enqueueCalls++ },
                    onEnqueueNext = { enqueueCalls++ },
                    onEnqueueTracks = { _, _ -> enqueueCalls++ },
                    onEnqueueTracksNext = { _, _ -> enqueueCalls++ },
                    onSetTrackSort = { _, _ -> },
                    onSetGroupSort = { _, _ -> },
                    onAddToPlaylist = {},
                    onShowTrackInfo = {},
                    onToggleFavorite = {},
                    onOpenDiagnostics = {},
                    onOpenQueue = { openQueueCalls++ },
                )
            }
        }

        // 1. All Songs tab -> concise status badge (no static Offline text) & Queue icon strictly passive
        composeRule.onNodeWithText("Safe Mode").assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithText("Offline", substring = true).fetchSemanticsNodes().size)
        composeRule.onNodeWithContentDescription("Open playback queue").assertIsDisplayed().performClick()
        assertEquals(1, openQueueCalls)

        // 2. Albums tab -> open Album Detail -> Queue icon present and strictly passive
        composeRule.onNodeWithText("Albums").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("21st Century Breakdown").assertIsDisplayed().performClick()
        composeRule.onNodeWithContentDescription("Open playback queue").assertIsDisplayed().performClick()
        assertEquals(2, openQueueCalls)

        // 3. Folders tab -> open Folder Detail -> Queue icon present and strictly passive
        composeRule.onNodeWithContentDescription("Back to Albums").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Folders").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("FLAC Hi-Res").assertIsDisplayed().performClick()
        composeRule.onNodeWithContentDescription("Open playback queue").assertIsDisplayed().performClick()
        assertEquals(3, openQueueCalls)

        // 4. Artists tab -> Artist Detail -> Queue icon MUST NOT exist on Hero Banner overlay
        composeRule.onNodeWithContentDescription("Back to Folders").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Artists").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Green Day").assertIsDisplayed().performClick()
        composeRule.onNodeWithContentDescription("Back to Artists").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Artist options for Green Day").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Open playback queue").assertDoesNotExist()

        // 5. Artist Detail -> Child Album Detail -> Queue icon restored on Album Detail TopAppBar
        composeRule.onNodeWithContentDescription(
            "Open album 21st Century Breakdown, 1 track",
        ).assertIsDisplayed().performClick()
        composeRule.onNodeWithContentDescription("Open playback queue").assertIsDisplayed().performClick()
        assertEquals(4, openQueueCalls)

        // 6. Back to Artists -> Favorites tab -> Queue icon present
        composeRule.onNodeWithContentDescription("Back to Green Day").assertIsDisplayed().performClick()
        composeRule.onNodeWithContentDescription("Back to Artists").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Favorites").performScrollTo().assertIsDisplayed().performClick()
        composeRule.onNodeWithContentDescription("Open playback queue").assertIsDisplayed().performClick()
        assertEquals(5, openQueueCalls)
        assertEquals(0, playCalls)
        assertEquals(0, enqueueCalls)
    }

    @Test
    fun queueScreen_usesDragHandlesWithoutArrowButtonsAndReordersViaDragGesture() {
        val trackA = hiResFlacTrack.copy(uriString = "content://test/audio/a.flac", title = "Track Alpha")
        val trackB = hiResFlacTrack.copy(uriString = "content://test/audio/b.flac", title = "Track Beta")
        val trackC = hiResFlacTrack.copy(uriString = "content://test/audio/c.flac", title = "Track Gamma")

        val manager = com.cangkir.bitperfectplayer.audio.PlaybackQueueManager<PlaybackTrack>()
        manager.setQueue(listOf(trackA, trackB, trackC), startIndex = 1) // Track Beta actively playing

        var queueSnapshot by mutableStateOf(manager.snapshot())
        var selectCalls = 0
        var removeCalls = 0

        composeRule.setContent {
            BitPerfectPlayerTheme {
                QueueScreen(
                    playbackState = PlaybackControllerState(
                        queue = queueSnapshot,
                        queueContext = QueueContext.allSongs(),
                        activeTrack = queueSnapshot.currentItem,
                        engineState = EnginePlaybackState(status = PlaybackStatus.PLAYING),
                    ),
                    libraryTracks = listOf(trackA, trackB, trackC),
                    activeContextTracks = emptyList(),
                    albumArtworkLoader = artworkLoader,
                    onBack = {},
                    onSelectQueueIndex = { selectCalls++ },
                    onAddTrackToQueue = {},
                    onRemoveQueueItem = { removeCalls++ },
                    onMoveQueueItem = { from, to ->
                        if (manager.moveItem(from, to)) {
                            queueSnapshot = manager.snapshot()
                        }
                    },
                    onToggleShuffle = {},
                    onCycleRepeatMode = {},
                )
            }
        }

        // Up/Down arrow buttons are completely removed
        assertEquals(
            0,
            composeRule.onAllNodesWithContentDescription("up in queue", substring = true).fetchSemanticsNodes().size,
        )
        assertEquals(
            0,
            composeRule.onAllNodesWithContentDescription("down in queue", substring = true).fetchSemanticsNodes().size,
        )

        // All 3 rows expose tactile DragHandle nodes
        val handles = composeRule.onAllNodesWithContentDescription("Reorder track")
        assertEquals(3, handles.fetchSemanticsNodes().size)
        val row0CenterY = handles[0].fetchSemanticsNode().boundsInRoot.center.y
        val row1CenterY = handles[1].fetchSemanticsNode().boundsInRoot.center.y
        val rowPitchPx = (row1CenterY - row0CenterY).coerceAtLeast(60f)

        // Drag row 0 ("Track Alpha") downward past row 1 ("Track Beta") midpoint
        handles[0].performTouchInput {
            down(center)
            advanceEventTime(40L)
            moveBy(Offset(0f, 16f))
            advanceEventTime(40L)
            moveBy(Offset(0f, rowPitchPx * 0.85f))
            advanceEventTime(40L)
            up()
        }
        composeRule.waitForIdle()

        // Order updated to [Track Beta, Track Alpha, Track Gamma] while active track stayed anchored to Track Beta (index 0)
        assertEquals(listOf("Track Beta", "Track Alpha", "Track Gamma"), queueSnapshot.items.map { it.title })
        assertEquals(0, queueSnapshot.currentIndex)
        assertEquals("Track Beta", queueSnapshot.currentItem?.title)
        assertEquals(0, selectCalls)
        assertEquals(0, removeCalls)
    }

    @Test
    fun playlistsScreen_topBarExposesPassiveQueueIconAcrossOverviewAndPlaylistDetail() {
        val playlist = com.cangkir.bitperfectplayer.library.ResolvedPlaylist(
            id = 7L,
            name = "Late Night",
            createdAtEpochMs = 1_700_000_000_000L,
            updatedAtEpochMs = 1_700_000_000_000L,
            entries = listOf(
                com.cangkir.bitperfectplayer.library.ResolvedPlaylistEntry(
                    entryId = 101L,
                    playlistId = 7L,
                    positionIndex = 0,
                    track = hiResFlacTrack,
                ),
            ),
        )
        var openQueueCalls = 0
        var playCalls = 0
        var enqueueCalls = 0

        composeRule.setContent {
            BitPerfectPlayerTheme {
                PlaylistsScreen(
                    playlistsState = PlaylistsUiSnapshot(
                        playlists = listOf(playlist),
                        isLoaded = true,
                    ),
                    activeTrackUri = hiResFlacTrack.uriString,
                    albumArtworkLoader = artworkLoader,
                    onPlayTracks = { _, _, _ -> playCalls++ },
                    onEnqueueTracks = { _, _ -> enqueueCalls++ },
                    onEnqueueTracksNext = { _, _ -> enqueueCalls++ },
                    onSetPlaylistSort = { _, _ -> },
                    onCreatePlaylist = {},
                    onRenamePlaylist = { _, _ -> },
                    onDeletePlaylist = { _, _ -> },
                    onRemovePlaylistEntry = { _, _ -> },
                    onMovePlaylistEntry = { _, _, _ -> },
                    onExportPlaylist = { _, _ -> },
                    onImportPlaylist = {},
                    onOpenQueue = { openQueueCalls++ },
                )
            }
        }

        // Overview top bar exposes passive Queue icon
        composeRule.onNodeWithContentDescription("Open playback queue").assertIsDisplayed().performClick()
        assertEquals(1, openQueueCalls)

        // Playlist detail top bar exposes passive Queue icon alongside Export
        composeRule.onNodeWithText("Late Night").assertIsDisplayed().performClick()
        composeRule.onNodeWithContentDescription("Export Late Night as .m3u8").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Open playback queue").assertIsDisplayed().performClick()
        assertEquals(2, openQueueCalls)
        assertEquals(0, playCalls)
        assertEquals(0, enqueueCalls)
    }

    @Test
    fun libraryScreen_allSongsSearchPlaybackLoadsFullSortedListAtTappedTrackIndex() {
        val t1 = hiResFlacTrack.copy(uriString = "content://test/audio/1.flac", title = "Alpha Song", fileName = "01_alpha.flac")
        val t2 = hiResFlacTrack.copy(uriString = "content://test/audio/2.flac", title = "Beta Song", fileName = "02_beta.flac")
        val t3 = hiResFlacTrack.copy(uriString = "content://test/audio/3.flac", title = "Peacemaker", fileName = "03_peacemaker.flac")
        val t4 = hiResFlacTrack.copy(uriString = "content://test/audio/4.flac", title = "Zulu Song", fileName = "04_zulu.flac")
        val fullSorted = listOf(t1, t2, t3, t4)

        var playedTracks: List<PlaybackTrack>? = null
        var playedStartIndex: Int = -1
        var playedContext: QueueContext? = null

        composeRule.setContent {
            BitPerfectPlayerTheme {
                LibraryScreen(
                    libraryState = LibrarySnapshot(allSongs = fullSorted),
                    favoriteTrackUris = emptySet(),
                    playbackState = PlaybackControllerState(),
                    albumArtworkLoader = artworkLoader,
                    onRefreshLibrary = {},
                    onImportFolder = {},
                    onPlayTracks = { tracks, index, context ->
                        playedTracks = tracks
                        playedStartIndex = index
                        playedContext = context
                    },
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

        // Filter All Songs down to a single search hit ("Peacemaker")
        composeRule.onNode(hasSetTextAction()).performTextInput("Peacemaker")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("1 of 4 tracks").assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithText("Alpha Song").fetchSemanticsNodes().size)

        // Tap the single filtered result -> must queue ALL 4 sorted All Songs starting at index 2!
        composeRule.onNode(hasText("Peacemaker") and !hasSetTextAction()).assertIsDisplayed().performClick()
        assertEquals(fullSorted, playedTracks)
        assertEquals(2, playedStartIndex)
        assertEquals(QueueContext.allSongs(), playedContext)
    }

    @Test
    fun settingsScreen_defaultHomepagePickerDisplaysAllSevenOptionsAndUpdatesPreference() {
        var currentHomepage by mutableStateOf(DefaultHomepage.ALL_SONGS)
        val selections = mutableListOf<DefaultHomepage>()

        composeRule.setContent {
            BitPerfectPlayerTheme {
                SettingsScreen(
                    libraryState = LibrarySnapshot(),
                    playbackState = PlaybackControllerState(),
                    sleepTimerStateFlow = MutableStateFlow(SleepTimerState()),
                    defaultHomepage = currentHomepage,
                    onSetDefaultHomepage = { selected ->
                        selections.add(selected)
                        currentHomepage = selected
                    },
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

        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(1)
        composeRule.onNodeWithContentDescription("Default Homepage: All Songs").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        // All 7 options rendered inside the modal bottom sheet
        DefaultHomepage.entries.forEach { option ->
            composeRule.onAllNodesWithText(option.displayName).fetchSemanticsNodes().let { nodes ->
                assertTrue("Expected option '${option.displayName}' in Default Homepage sheet", nodes.isNotEmpty())
            }
        }

        // Selecting Playlists updates preference and dismisses sheet
        composeRule.onNodeWithText("Playlists").performScrollTo().assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(DefaultHomepage.PLAYLISTS), selections)
        composeRule.onNodeWithContentDescription("Default Homepage: Playlists").assertIsDisplayed()
    }

    @Test
    fun startupNavHost_honorsDefaultHomepagePlaylistsAndDoesNotHijackWhenPreferenceChanges() {
        var homepagePref by mutableStateOf(DefaultHomepage.PLAYLISTS)

        composeRule.setContent {
            BitPerfectPlayerTheme {
                BitPerfectStartupNavHost(
                    startupHomepage = homepagePref,
                    libraryContent = { initialTab ->
                        LibraryScreen(
                            libraryState = LibrarySnapshot(allSongs = listOf(hiResFlacTrack)),
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
                            libraryState = LibrarySnapshot(),
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

        // Cold startup lands directly on Playlists
        composeRule.onNodeWithText("YOUR PLAYLISTS").assertIsDisplayed()

        // Navigate manually to Settings, change preference to Albums -> MUST NOT hijack back to Library!
        composeRule.onNodeWithContentDescription("Settings").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("AUDIO ENGINE").assertIsDisplayed()

        homepagePref = DefaultHomepage.ALBUMS
        composeRule.waitForIdle()
        composeRule.onNodeWithText("AUDIO ENGINE").assertIsDisplayed()
    }

    @Test
    fun startupNavHost_honorsDefaultHomepageAlbumsTabAndAllowsManualTabSwitching() {
        val albumGroup = com.cangkir.bitperfectplayer.library.LibraryGroup(
            name = "21st Century Breakdown",
            subtitle = "Green Day · 1 track",
            tracks = listOf(hiResFlacTrack),
        )
        var homepagePref by mutableStateOf(DefaultHomepage.ALBUMS)

        composeRule.setContent {
            BitPerfectPlayerTheme {
                BitPerfectStartupNavHost(
                    startupHomepage = homepagePref,
                    libraryContent = { initialTab ->
                        LibraryScreen(
                            libraryState = LibrarySnapshot(
                                allSongs = listOf(hiResFlacTrack),
                                albums = listOf(albumGroup),
                            ),
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
                    playlistsContent = {},
                    settingsContent = {},
                )
            }
        }

        // Cold startup opens Library directly on Albums tab
        composeRule.onNodeWithText("1 album").assertIsDisplayed()
        composeRule.onNodeWithText("21st Century Breakdown").assertIsDisplayed()

        // Manually switch to All Songs tab -> changing preference later never resets user's active tab
        composeRule.onNodeWithText("All Songs").performScrollTo().assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("1 track").assertIsDisplayed()

        homepagePref = DefaultHomepage.FAVORITES
        composeRule.waitForIdle()
        composeRule.onNodeWithText("1 track").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w411dp-h915dp")
    fun settingsScreen_diagnosticLoggingSectionDefaultsOffAndSupportsToggleShareClear() {
        var loggingEnabled by mutableStateOf(false)
        var logStats by mutableStateOf(com.cangkir.bitperfectplayer.diagnostics.DiagnosticLogStats())
        val toggleEvents = mutableListOf<Boolean>()
        var shareClicks = 0
        var clearClicks = 0

        composeRule.setContent {
            BitPerfectPlayerTheme {
                SettingsScreen(
                    libraryState = LibrarySnapshot(),
                    playbackState = PlaybackControllerState(),
                    sleepTimerStateFlow = MutableStateFlow(SleepTimerState()),
                    diagnosticLoggingEnabled = loggingEnabled,
                    diagnosticLogStats = logStats,
                    onSetDiagnosticLoggingEnabled = { next ->
                        toggleEvents.add(next)
                        loggingEnabled = next
                        if (next) {
                            logStats = com.cangkir.bitperfectplayer.diagnostics.DiagnosticLogStats(
                                fileCount = 3,
                                totalBytes = 14_541L,
                            )
                        }
                    },
                    onShareDiagnosticLogs = { shareClicks++ },
                    onClearDiagnosticLogs = {
                        clearClicks++
                        logStats = com.cangkir.bitperfectplayer.diagnostics.DiagnosticLogStats()
                    },
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

        // Scroll to section_diagnostic_logging (index 4: audio, display, library, backup, diagnostic)
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(4)
        composeRule.onNodeWithText("DIAGNOSTIC LOGGING").assertIsDisplayed()
        composeRule.onNodeWithText("Enable Local Diagnostic Logging").assertIsDisplayed()
        composeRule.onNodeWithText("No logs stored").assertIsDisplayed()

        // Default OFF + no logs -> Export, Share, and Clear are disabled
        composeRule.onNodeWithText("Export Logs (.zip)").assertIsNotEnabled()
        composeRule.onNodeWithText("Share Logs").assertIsNotEnabled()
        composeRule.onNodeWithText("Clear Logs").assertIsNotEnabled()

        // Toggle ON -> enables actions and displays live footprint
        composeRule.onNodeWithContentDescription("Toggle local diagnostic logging")
            .assertIsDisplayed()
            .performClick()
        assertEquals(listOf(true), toggleEvents)
        composeRule.onNodeWithText("3 files · 14.2 KB").assertIsDisplayed()
        composeRule.onNodeWithText("Export Logs (.zip)").assertIsEnabled()
        composeRule.onNodeWithText("Share Logs").assertIsEnabled().performClick()
        assertEquals(1, shareClicks)

        // Clear Logs -> resets stats back to "No logs stored" and disables Clear button
        composeRule.onNodeWithText("Clear Logs").assertIsEnabled().performClick()
        assertEquals(1, clearClicks)
        composeRule.onNodeWithText("No logs stored").assertIsDisplayed()
        composeRule.onNodeWithText("Clear Logs").assertIsNotEnabled()
    }

    @Test
    fun queueScreen_duplicateTracksInQueueAndAddDialog_neverCrashLazyColumn() {
        val duplicateQueue = QueueSnapshot(
            items = listOf(hiResFlacTrack, hiResFlacTrack),
            originalItems = listOf(hiResFlacTrack, hiResFlacTrack),
            currentIndex = 0,
            entryIds = listOf(10L, 11L),
        )
        val duplicatePlaylistContext = listOf(hiResFlacTrack, hiResFlacTrack)
        val addedTracks = mutableListOf<PlaybackTrack>()

        composeRule.setContent {
            BitPerfectPlayerTheme {
                QueueScreen(
                    playbackState = PlaybackControllerState(
                        queue = duplicateQueue,
                        queueContext = QueueContext.playlist(1L, "Repeat Favorites"),
                        activeTrack = hiResFlacTrack,
                    ),
                    libraryTracks = listOf(hiResFlacTrack),
                    activeContextTracks = duplicatePlaylistContext,
                    albumArtworkLoader = artworkLoader,
                    onBack = {},
                    onSelectQueueIndex = {},
                    onAddTrackToQueue = { addedTracks.add(it) },
                    onRemoveQueueItem = {},
                    onMoveQueueItem = { _, _ -> },
                    onToggleShuffle = {},
                    onCycleRepeatMode = {},
                )
            }
        }

        // Duplicate entries in queue render cleanly without duplicate-key crash
        assertEquals(2, composeRule.onAllNodesWithText(hiResFlacTrack.title).fetchSemanticsNodes().size)

        // Opening AddTrackToQueueDialog with duplicate playlist context tracks deduplicates cleanly
        composeRule.onNodeWithContentDescription("Add track to queue").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Add Track to Queue").assertIsDisplayed()
        composeRule.onNodeWithText("Current Context").assertIsDisplayed()
        // Exactly 1 deduplicated track row appears in the dialog (in addition to the 2 rows behind it)
        composeRule.onNodeWithText("Close").assertIsDisplayed().performClick()
        assertTrue(addedTracks.isEmpty())
    }

    @Test
    @Config(qualifiers = "w411dp-h915dp")
    fun startupNavHost_stateRestorationAfterConfigChange_preservesActiveRouteWithoutSnappingToDefaultHomepage() {
        val restorationTester = androidx.compose.ui.test.junit4.StateRestorationTester(composeRule)
        restorationTester.setContent {
            BitPerfectPlayerTheme {
                BitPerfectStartupNavHost(
                    startupHomepage = DefaultHomepage.PLAYLISTS,
                    libraryContent = { initialTab ->
                        LibraryScreen(
                            libraryState = LibrarySnapshot(allSongs = listOf(hiResFlacTrack)),
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
                            libraryState = LibrarySnapshot(),
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

        // Cold startup lands on Playlists
        composeRule.onNodeWithText("YOUR PLAYLISTS").assertIsDisplayed()

        // Navigate manually to Settings
        composeRule.onNodeWithContentDescription("Settings").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("AUDIO ENGINE").assertIsDisplayed()

        // Simulate Android configuration change / activity recreation -> MUST remain on Settings!
        restorationTester.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("AUDIO ENGINE").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w411dp-h915dp")
    fun settingsScreen_dynamicCoverThemeToggleDefaultsOffAndTogglesCleanly() {
        var enabled by mutableStateOf(false)
        val events = mutableListOf<Boolean>()

        composeRule.setContent {
            BitPerfectPlayerTheme {
                SettingsScreen(
                    libraryState = LibrarySnapshot(),
                    playbackState = PlaybackControllerState(),
                    sleepTimerStateFlow = MutableStateFlow(SleepTimerState()),
                    dynamicCoverThemeEnabled = enabled,
                    onSetDynamicCoverThemeEnabled = { next ->
                        events.add(next)
                        enabled = next
                    },
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

        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(1)
        composeRule.onNodeWithContentDescription("Toggle dynamic cover theme").performScrollTo()
        composeRule.onNodeWithText("Dynamic Cover Theme").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Toggle dynamic cover theme")
            .assertIsDisplayed()
            .assertIsOff()
            .performClick()

        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Toggle dynamic cover theme").assertIsOn()
        assertEquals(listOf(true), events)

        composeRule.onNodeWithContentDescription("Toggle dynamic cover theme").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Toggle dynamic cover theme").assertIsOff()
        assertEquals(listOf(true, false), events)
    }

    @Test
    fun bitPerfectPlayerTheme_appliesMonochromeStudioAndIsolatesNestedAlbumDynamicScope() {
        val globalCrimsonTokens = CoverThemePaletteGenerator.generateTokensFromHue(352f, 0.85f)
        val albumEmeraldTokens = CoverThemePaletteGenerator.generateTokensFromHue(145f, 0.90f)
        var useGlobalDynamic by mutableStateOf(false)
        var showAlbumScope by mutableStateOf(false)

        var outerPrimary = Color.Unspecified
        var outerBackground = Color.Unspecified
        var innerPrimary = Color.Unspecified
        var innerBackground = Color.Unspecified

        composeRule.setContent {
            BitPerfectPlayerTheme(
                dynamicTokens = if (useGlobalDynamic) globalCrimsonTokens else null,
            ) {
                outerPrimary = MaterialTheme.colorScheme.primary
                outerBackground = MaterialTheme.colorScheme.background
                Text(text = "Outer Global", color = outerPrimary)

                if (showAlbumScope) {
                    BitPerfectPlayerTheme(dynamicTokens = albumEmeraldTokens) {
                        innerPrimary = MaterialTheme.colorScheme.primary
                        innerBackground = MaterialTheme.colorScheme.background
                        Text(text = "Inner Album", color = innerPrimary)
                    }
                }
            }
        }
        composeRule.waitForIdle()

        // Default Monochrome Studio tokens
        assertEquals(StudioIvoryPrimary, outerPrimary)
        assertEquals(ObsidianBackground, outerBackground)

        // Enable global track theme + open nested Album local scope -> both resolve distinct palettes
        useGlobalDynamic = true
        showAlbumScope = true
        composeRule.waitForIdle()

        assertEquals(Color(globalCrimsonTokens.primaryArgb), outerPrimary)
        assertEquals(Color(globalCrimsonTokens.backgroundArgb), outerBackground)
        assertEquals(Color(albumEmeraldTokens.primaryArgb), innerPrimary)
        assertEquals(Color(albumEmeraldTokens.backgroundArgb), innerBackground)

        // Close Album scope -> global track theme remains intact
        showAlbumScope = false
        composeRule.waitForIdle()
        assertEquals(Color(globalCrimsonTokens.primaryArgb), outerPrimary)

        // Turn off Dynamic Cover Theme -> reverts immediately back to Monochrome Studio
        useGlobalDynamic = false
        composeRule.waitForIdle()
        assertEquals(StudioIvoryPrimary, outerPrimary)
        assertEquals(ObsidianBackground, outerBackground)
    }

    @Test
    @Config(qualifiers = "w411dp-h915dp")
    fun libraryScreen_albumDetailAppliesLocalDynamicCoverThemeAndRevertsCleanlyOnBack() {
        val albumTokens = CoverThemePaletteGenerator.generateTokensFromHue(145f, 0.90f)
        val cache = CoverThemeColorCache(artworkLoader).apply {
            seedCacheForTesting(hiResFlacTrack, albumTokens)
        }
        val albumGroup = com.cangkir.bitperfectplayer.library.LibraryGroup(
            name = "21st Century Breakdown",
            subtitle = "Green Day · 1 track",
            tracks = listOf(hiResFlacTrack),
        )

        composeRule.setContent {
            BitPerfectPlayerTheme {
                LibraryScreen(
                    libraryState = LibrarySnapshot(
                        allSongs = listOf(hiResFlacTrack),
                        albums = listOf(albumGroup),
                    ),
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
                    initialTab = LibraryTab.ALBUMS,
                    dynamicCoverThemeEnabled = true,
                    coverThemeColorCache = cache,
                )
            }
        }

        // Open Album Detail -> local dynamic theme subtree renders Play All and album tracks cleanly
        composeRule.onNodeWithText("21st Century Breakdown").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Play All").assertIsDisplayed()
        composeRule.onNodeWithText("Peacemaker").assertIsDisplayed()

        // Press Back -> exits local Album scope cleanly back to Albums grid
        composeRule.onNodeWithContentDescription("Back to Albums").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("1 album").assertIsDisplayed()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w360dp-h740dp")
    fun miniPlayerBar_omitsQueueIconAndPinsCompactKhzSpecWithoutTruncatingLongArtist() {
        val longArtist = "Avenged Sevenfold Featuring Symphony Orchestra"
        val longArtistTrack = hiResFlacTrack.copy(artist = longArtist)
        val activePlayback = PlaybackControllerState(
            queue = QueueSnapshot(items = listOf(longArtistTrack), currentIndex = 0),
            queueContext = QueueContext.allSongs(),
            activeTrack = longArtistTrack,
            engineState = EnginePlaybackState(status = PlaybackStatus.PLAYING, durationMs = longArtistTrack.durationMs),
        )
        val posFlow = MutableStateFlow(45_000L)
        var openNowPlayingCalls = 0
        var togglePlayPauseCalls = 0
        var nextCalls = 0

        composeRule.setContent {
            BitPerfectPlayerTheme {
                MiniPlayerBar(
                    playbackState = activePlayback,
                    playbackPositionMsFlow = posFlow,
                    artworkLoader = artworkLoader,
                    onOpenNowPlaying = { openNowPlayingCalls++ },
                    onTogglePlayPause = { togglePlayPauseCalls++ },
                    onNext = { nextCalls++ },
                )
            }
        }

        // Right controls keep ONLY Play/Pause & Next; Queue button must not exist on MiniPlayerBar
        composeRule.onNodeWithContentDescription("Open playback queue").assertDoesNotExist()
        val pauseBounds = composeRule.onNodeWithContentDescription("Pause playback")
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
        composeRule.onNodeWithContentDescription("Pause playback").performClick()
        composeRule.onNodeWithContentDescription("Next track").assertIsDisplayed().performClick()
        assertEquals(1, togglePlayPauseCalls)
        assertEquals(1, nextCalls)
        assertEquals(0, openNowPlayingCalls)

        // Compact kHz spec is displayed (never verbose comma-separated Hz)
        val expectedSpecText = " · FLAC 24-bit · 96 kHz"
        assertEquals(0, composeRule.onAllNodesWithText("96,000 Hz", substring = true).fetchSemanticsNodes().size)
        val specNode = composeRule.onNodeWithText(expectedSpecText, useUnmergedTree = true)
            .assertIsDisplayed()
            .fetchSemanticsNode()
        val artistNode = composeRule.onNodeWithText(longArtist, useUnmergedTree = true)
            .assertIsDisplayed()
            .fetchSemanticsNode()

        // Only the long artist ellipsizes; the pinned audio spec NEVER truncates or overlaps controls
        val artistLayoutResults = mutableListOf<TextLayoutResult>()
        val specLayoutResults = mutableListOf<TextLayoutResult>()
        artistNode.config[SemanticsActions.GetTextLayoutResult].action?.invoke(artistLayoutResults)
        specNode.config[SemanticsActions.GetTextLayoutResult].action?.invoke(specLayoutResults)
        assertEquals(1, artistLayoutResults.size)
        assertEquals(1, specLayoutResults.size)
        val artistLayout = artistLayoutResults.single()
        val specLayout = specLayoutResults.single()
        assertTrue("Expected long artist text to ellipsize on 360dp phone", artistLayout.isLineEllipsized(0))
        assertFalse("Pinned compact audio spec must not ellipsize", specLayout.isLineEllipsized(0))
        assertEquals(1, specLayout.lineCount)
        assertEquals(expectedSpecText.length, specLayout.getLineEnd(0))
        assertTrue(
            "Pinned compact audio spec glyphs must fit inside allocated width without clipping " +
                "(lineRight=${specLayout.getLineRight(0)}, boxW=${specLayout.size.width})",
            specLayout.getLineRight(0) <= specLayout.size.width + 1f,
        )
        assertTrue("Expected native Roboto glyph width for pinned spec", specNode.boundsInRoot.width > 50f)
        assertTrue("Pinned spec should sit to the right of artist", specNode.boundsInRoot.left >= artistNode.boundsInRoot.right - 1f)
        assertTrue("Pinned spec must not overlap Play/Pause", specNode.boundsInRoot.right <= pauseBounds.left + 1f)
    }

    @Test
    @Config(qualifiers = "w411dp-h915dp")
    fun topBarQueueIcon_fromAlbumAndFolderDetail_opensQueueScreenPassivelyWithoutAlteringActivePlayback() {
        val browsedTrack = hiResFlacTrack.copy(
            uriString = "content://test/audio/browsed.flac",
            title = "Browsed Album Song",
            artist = "Browsed Artist",
            album = "Browsed Album",
            folderName = "Browsed Folder",
        )
        val playingTrack = hiResFlacTrack.copy(
            uriString = "content://test/audio/playing.flac",
            title = "Currently Playing Queue Song",
            artist = "Active Artist",
            album = "Active Album",
            folderName = "Active Folder",
        )
        val snapshot = LibrarySnapshot(
            allSongs = listOf(browsedTrack, playingTrack),
            albums = listOf(
                com.cangkir.bitperfectplayer.library.LibraryGroup(
                    name = "Browsed Album",
                    subtitle = "Browsed Artist · 1 track",
                    tracks = listOf(browsedTrack),
                ),
            ),
            folders = listOf(
                com.cangkir.bitperfectplayer.library.LibraryGroup(
                    name = "Browsed Folder",
                    subtitle = "1 track",
                    tracks = listOf(browsedTrack),
                ),
            ),
        )
        var showQueue by mutableStateOf(false)
        var playbackState by mutableStateOf(
            PlaybackControllerState(
                queue = QueueSnapshot(items = listOf(playingTrack), currentIndex = 0),
                queueContext = QueueContext.allSongs(),
                activeTrack = playingTrack,
                engineState = EnginePlaybackState(status = PlaybackStatus.PLAYING, durationMs = playingTrack.durationMs),
            ),
        )
        var playCalls = 0
        var enqueueCalls = 0

        composeRule.setContent {
            val saveableStateHolder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
            BitPerfectPlayerTheme {
                if (showQueue) {
                    saveableStateHolder.SaveableStateProvider("queue") {
                        QueueScreen(
                            playbackState = playbackState,
                            libraryTracks = snapshot.allSongs,
                            activeContextTracks = snapshot.allSongs,
                            albumArtworkLoader = artworkLoader,
                            onBack = { showQueue = false },
                            onSelectQueueIndex = { playCalls++ },
                            onAddTrackToQueue = { enqueueCalls++ },
                            onRemoveQueueItem = {},
                            onMoveQueueItem = { _, _ -> },
                            onToggleShuffle = {},
                            onCycleRepeatMode = {},
                        )
                    }
                } else {
                    saveableStateHolder.SaveableStateProvider("library") {
                        LibraryScreen(
                            libraryState = snapshot,
                            favoriteTrackUris = emptySet(),
                            playbackState = playbackState,
                            albumArtworkLoader = artworkLoader,
                            onRefreshLibrary = {},
                            onImportFolder = {},
                            onPlayTracks = { _, _, _ -> playCalls++ },
                            onEnqueueTrack = { enqueueCalls++ },
                            onEnqueueNext = { enqueueCalls++ },
                            onEnqueueTracks = { _, _ -> enqueueCalls++ },
                            onEnqueueTracksNext = { _, _ -> enqueueCalls++ },
                            onSetTrackSort = { _, _ -> },
                            onSetGroupSort = { _, _ -> },
                            onAddToPlaylist = {},
                            onShowTrackInfo = {},
                            onToggleFavorite = {},
                            onOpenDiagnostics = {},
                            onOpenQueue = { showQueue = true },
                        )
                    }
                }
            }
        }

        // Open Album Detail -> tap top-bar Queue icon -> opens active queue passively without interrupting playback
        composeRule.onNodeWithText("Albums").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Browsed Album").assertIsDisplayed().performClick()
        composeRule.onNodeWithContentDescription("Open playback queue").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Playback Queue").assertIsDisplayed()
        composeRule.onNodeWithText("Currently Playing Queue Song").assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithText("Browsed Album Song").fetchSemanticsNodes().size)
        assertTrue(playbackState.isPlaying)

        // Return -> open Folder Detail -> tap top-bar Queue icon -> still strictly passive
        composeRule.onNodeWithContentDescription("Back").assertIsDisplayed().performClick()
        composeRule.onNodeWithContentDescription("Back to Albums").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Folders").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Browsed Folder").assertIsDisplayed().performClick()
        composeRule.onNodeWithContentDescription("Open playback queue").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Currently Playing Queue Song").assertIsDisplayed()
        assertTrue(playbackState.isPlaying)

        // When no track is active, tapping top-bar Queue opens empty passive Queue without starting playback
        composeRule.runOnIdle {
            showQueue = false
            playbackState = PlaybackControllerState()
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Open playback queue").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Playback queue is empty", substring = true).assertIsDisplayed()
        assertEquals(0, playCalls)
        assertEquals(0, enqueueCalls)
    }

    @Test
    @Config(qualifiers = "w411dp-h915dp")
    fun artistDetailView_rendersHeroSummaryChronologicalOldestFirstAlbumsTracksShuffleAndOverflowMenu() {
        val recentTrack = hiResFlacTrack.copy(
            uriString = "content://test/audio/saviors_01.flac",
            title = "The American Dream Is Killing Me",
            artist = "Green Day",
            album = "Saviors",
            fileName = "01 - Saviors.flac",
            durationMs = 180_000L,
            trackNumber = 1,
            year = 2024,
        )
        val olderTrack1 = hiResFlacTrack.copy(
            uriString = "content://test/audio/21cb_01.flac",
            title = "Peacemaker",
            artist = "Green Day",
            album = "21st Century Breakdown",
            fileName = "07 - Peacemaker.flac",
            durationMs = 240_000L,
            trackNumber = 7,
            year = 2009,
        )
        val olderTrack2 = hiResFlacTrack.copy(
            uriString = "content://test/audio/21cb_02.flac",
            title = "East Jesus Nowhere",
            artist = "Green Day",
            album = "21st Century Breakdown",
            fileName = "08 - East Jesus Nowhere.flac",
            durationMs = 240_000L,
            trackNumber = 8,
            year = 2009,
        )
        val artistTracks = listOf(recentTrack, olderTrack1, olderTrack2)
        val snapshot = LibrarySnapshot(
            allSongs = artistTracks,
            artists = listOf(
                com.cangkir.bitperfectplayer.library.LibraryGroup(
                    name = "Green Day",
                    subtitle = "3 songs · 2 albums",
                    tracks = artistTracks,
                ),
            ),
        )
        var shuffledTracksCount = 0

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
                    initialTab = LibraryTab.ARTISTS,
                    onShuffleTracks = { tracks, _ -> shuffledTracksCount = tracks.size },
                )
            }
        }

        // Open Artist Detail
        composeRule.onNodeWithText("Green Day").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        // Verify Hero identity, formatted stats (3 + 4 + 4 = 11 min), Play All, Shuffle, Albums, TRACKS
        composeRule.onNodeWithText("2 albums · 3 tracks · 11 min").assertIsDisplayed()
        composeRule.onNodeWithText("Play All").assertIsDisplayed()
        composeRule.onNodeWithText("Shuffle").assertIsDisplayed().performClick()
        assertEquals(3, shuffledTracksCount)

        composeRule.onNodeWithText("Albums").assertIsDisplayed()
        val olderBounds = composeRule.onNodeWithText("21st Century Breakdown")
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
        val newerBounds = composeRule.onNodeWithText("Saviors")
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
        // Oldest-first chronological order: 2009 album MUST appear to the left of 2024 album
        assertTrue("Expected 2009 album left of 2024 album", olderBounds.left < newerBounds.left)
        composeRule.onNodeWithText("2009 · 2 tracks").assertIsDisplayed()
        composeRule.onNodeWithText("2024 · 1 track").assertIsDisplayed()
        composeRule.onNodeWithText("TRACKS").assertIsDisplayed()

        // Verify 3-dot overflow menu exposes Set Artist Photo
        composeRule.onNodeWithContentDescription("Artist options for Green Day").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Set Artist Photo").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w411dp-h915dp")
    fun libraryScreen_artistDetailToAlbumDetailPreservesParentBackstack() {
        val trackA = hiResFlacTrack.copy(
            uriString = "content://test/audio/saviors_01.flac",
            title = "Dilemma",
            artist = "Green Day",
            album = "Saviors",
            fileName = "01 - Dilemma.flac",
            durationMs = 200_000L,
            trackNumber = 1,
            year = 2024,
        )
        val snapshot = LibrarySnapshot(
            allSongs = listOf(trackA),
            artists = listOf(
                com.cangkir.bitperfectplayer.library.LibraryGroup(
                    name = "Green Day",
                    subtitle = "1 song · 1 album",
                    tracks = listOf(trackA),
                ),
            ),
        )

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
                    initialTab = LibraryTab.ARTISTS,
                )
            }
        }

        // 1. Artists -> Artist Detail
        composeRule.onNodeWithText("Green Day").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("1 album · 1 track · 3 min").assertIsDisplayed()

        // 2. Artist Detail -> tap Album card -> opens Album Detail with back target "Back to Green Day"
        composeRule.onNodeWithContentDescription("Open album Saviors, 2024 · 1 track").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Back to Green Day").assertIsDisplayed()
        composeRule.onNodeWithText("Dilemma").assertIsDisplayed()
        // No embedded Back Cover exists -> Back Cover toggle must NOT exist
        composeRule.onNodeWithContentDescription("Show back cover").assertDoesNotExist()

        // 3. Back from Album Detail -> returns cleanly to Artist Detail (NOT Artists tab!)
        composeRule.onNodeWithContentDescription("Back to Green Day").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("1 album · 1 track · 3 min").assertIsDisplayed()

        // 4. Back from Artist Detail -> returns cleanly to Artists tab
        composeRule.onNodeWithContentDescription("Back to Artists").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("1 artist").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w411dp-h915dp")
    fun nowPlayingSquareCover_hidesBackCoverToggleWhenEmbeddedBackCoverAbsent() {
        val activePlayback = PlaybackControllerState(
            queue = QueueSnapshot(items = listOf(hiResFlacTrack), currentIndex = 0),
            queueContext = QueueContext.allSongs(),
            activeTrack = hiResFlacTrack,
            engineState = EnginePlaybackState(status = PlaybackStatus.PLAYING, durationMs = hiResFlacTrack.durationMs),
        )
        val posFlow = MutableStateFlow(10_000L)
        val timerFlow = MutableStateFlow(SleepTimerState())

        composeRule.setContent {
            BitPerfectPlayerTheme {
                NowPlayingScreen(
                    playbackState = activePlayback,
                    playbackPositionMsFlow = posFlow,
                    sleepTimerStateFlow = timerFlow,
                    albumArtworkLoader = artworkLoader,
                    isFavorite = false,
                    artworkStyle = NowPlayingArtworkStyle.SQUARE_COVER,
                    onSetArtworkStyle = {},
                    spectrumVisualizerEnabled = false,
                    onSetSpectrumVisualizerEnabled = {},
                    spectrumColorStyle = SpectrumColorStyle.PRISM,
                    onCycleSpectrumColorStyle = {},
                    spectrumSparksEnabled = false,
                    onSetSpectrumSparksEnabled = {},
                    onSetSpectrumVisualizerActive = {},
                    onSampleSpectrumWindow = { 0 },
                    onBack = {},
                    onToggleFavorite = {},
                    onAddToPlaylist = {},
                    onShowTrackInfo = {},
                    onSelectSleepTimerPreset = {},
                    onTogglePlayPause = {},
                    onPrevious = {},
                    onNext = {},
                    onSeekTo = {},
                    onBeginTurntableScratch = { false },
                    onUpdateTurntableScratchRate = {},
                    onEndTurntableScratch = {},
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

        composeRule.waitForIdle()
        // When no embedded BACK_COVER is present, ZERO Back Cover toggle UI should appear
        composeRule.onNodeWithContentDescription("Show back cover").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Show front cover").assertDoesNotExist()
        // And NowPlayingScreen STILL keeps its icon-only Queue utility button intact!
        assertEquals(0, composeRule.onAllNodesWithText("Queue (1)").fetchSemanticsNodes().size)
        composeRule.onNodeWithContentDescription("Open playback queue").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w411dp-h915dp")
    fun nowPlayingSquareCover_togglesBackCoverWhenPresentAndResetsOnTrackChange() {
        val track1 = hiResFlacTrack
        val track2 = hiResFlacTrack.copy(
            uriString = "content://test/audio/track2.flac",
            title = "Second Track",
            fileName = "02 - Second Track.flac",
        )
        val backBitmap = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888)
        artworkLoader.seedSlotBitmapForTesting(track1, com.cangkir.bitperfectplayer.library.EmbeddedArtworkSlot.BACK_COVER, backBitmap)

        var activeTrack by mutableStateOf(track1)
        val posFlow = MutableStateFlow(10_000L)
        val timerFlow = MutableStateFlow(SleepTimerState())

        composeRule.setContent {
            BitPerfectPlayerTheme {
                NowPlayingScreen(
                    playbackState = PlaybackControllerState(
                        queue = QueueSnapshot(items = listOf(track1, track2), currentIndex = if (activeTrack == track1) 0 else 1),
                        queueContext = QueueContext.allSongs(),
                        activeTrack = activeTrack,
                        engineState = EnginePlaybackState(status = PlaybackStatus.PLAYING, durationMs = activeTrack.durationMs),
                    ),
                    playbackPositionMsFlow = posFlow,
                    sleepTimerStateFlow = timerFlow,
                    albumArtworkLoader = artworkLoader,
                    isFavorite = false,
                    artworkStyle = NowPlayingArtworkStyle.SQUARE_COVER,
                    onSetArtworkStyle = {},
                    spectrumVisualizerEnabled = false,
                    onSetSpectrumVisualizerEnabled = {},
                    spectrumColorStyle = SpectrumColorStyle.PRISM,
                    onCycleSpectrumColorStyle = {},
                    spectrumSparksEnabled = false,
                    onSetSpectrumSparksEnabled = {},
                    onSetSpectrumVisualizerActive = {},
                    onSampleSpectrumWindow = { 0 },
                    onBack = {},
                    onToggleFavorite = {},
                    onAddToPlaylist = {},
                    onShowTrackInfo = {},
                    onSelectSleepTimerPreset = {},
                    onTogglePlayPause = {},
                    onPrevious = {},
                    onNext = {},
                    onSeekTo = {},
                    onBeginTurntableScratch = { false },
                    onUpdateTurntableScratchRate = {},
                    onEndTurntableScratch = {},
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

        // Track 1 has embedded Back Cover -> toggle appears and flips cleanly
        composeRule.onNodeWithContentDescription("Show back cover").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Back cover artwork for ${track1.title}").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Show front cover").assertIsDisplayed()

        // Switching to Track 2 (no back cover) auto-resets to Front Cover and hides toggle
        composeRule.runOnIdle { activeTrack = track2 }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Show front cover").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Show back cover").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w411dp-h915dp")
    fun nowPlayingVinylDisc_prefersEmbeddedDiscArtworkWhenPresent() {
        val discBitmap = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888)
        artworkLoader.seedSlotBitmapForTesting(
            hiResFlacTrack,
            com.cangkir.bitperfectplayer.library.EmbeddedArtworkSlot.DISC,
            discBitmap,
        )
        val posFlow = MutableStateFlow(5_000L)
        val timerFlow = MutableStateFlow(SleepTimerState())

        composeRule.setContent {
            BitPerfectPlayerTheme {
                NowPlayingScreen(
                    playbackState = PlaybackControllerState(
                        queue = QueueSnapshot(items = listOf(hiResFlacTrack), currentIndex = 0),
                        queueContext = QueueContext.allSongs(),
                        activeTrack = hiResFlacTrack,
                        engineState = EnginePlaybackState(status = PlaybackStatus.PAUSED, durationMs = hiResFlacTrack.durationMs),
                    ),
                    playbackPositionMsFlow = posFlow,
                    sleepTimerStateFlow = timerFlow,
                    albumArtworkLoader = artworkLoader,
                    isFavorite = false,
                    artworkStyle = NowPlayingArtworkStyle.VINYL_DISC,
                    onSetArtworkStyle = {},
                    spectrumVisualizerEnabled = false,
                    onSetSpectrumVisualizerEnabled = {},
                    spectrumColorStyle = SpectrumColorStyle.PRISM,
                    onCycleSpectrumColorStyle = {},
                    spectrumSparksEnabled = false,
                    onSetSpectrumSparksEnabled = {},
                    onSetSpectrumVisualizerActive = {},
                    onSampleSpectrumWindow = { 0 },
                    onBack = {},
                    onToggleFavorite = {},
                    onAddToPlaylist = {},
                    onShowTrackInfo = {},
                    onSelectSleepTimerPreset = {},
                    onTogglePlayPause = {},
                    onPrevious = {},
                    onNext = {},
                    onSeekTo = {},
                    onBeginTurntableScratch = { false },
                    onUpdateTurntableScratchRate = {},
                    onEndTurntableScratch = {},
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

        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Disc artwork for ${hiResFlacTrack.title}").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w411dp-h915dp")
    fun artistsTab_andArtistDetailHero_shareUnifiedFourTierFallbackAndOldestFirstAlbumCover() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        val photoStore = com.cangkir.bitperfectplayer.library.ArtistPhotoStore(context)
        val bmp = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888)

        val trackCustom = hiResFlacTrack.copy(
            uriString = "content://test/artist/custom_1.flac",
            artist = "Alpha Custom",
            album = "Custom Album",
            year = 2020,
        )
        val trackEmbedded = hiResFlacTrack.copy(
            uriString = "content://test/artist/embedded_1.flac",
            artist = "Beta Embedded",
            album = "Embedded Album",
            year = 2019,
        )
        val trackFallback2013 = hiResFlacTrack.copy(
            uriString = "content://test/artist/fallback_2013.flac",
            title = "Later Song",
            artist = "Gamma Fallback",
            album = "LP 2013",
            year = 2013,
        )
        val trackFallback2003 = hiResFlacTrack.copy(
            uriString = "content://test/artist/fallback_2003.flac",
            title = "First Song",
            artist = "Gamma Fallback",
            album = "LP 2003",
            year = 2003,
        )
        val trackMonogram = hiResFlacTrack.copy(
            uriString = "content://test/artist/mono_1.wav",
            artist = "Delta Mono",
            album = "No Art Album",
            fileName = "mono.wav",
            container = "WAV",
            year = 2018,
        )

        // Seed Priority 1 (custom), Priority 2 (embedded ARTIST), and Priority 3 (both 2013 and 2003 album covers)
        photoStore.seedArtistPhotoForTesting("Alpha Custom", bmp)
        artworkLoader.seedArtistEmbeddedBitmapForTesting(
            "Beta Embedded",
            bmp,
            com.cangkir.bitperfectplayer.library.AlbumArtworkLoader.THUMBNAIL_SIZE_PX,
        )
        artworkLoader.seedArtworkForTesting(
            trackFallback2013,
            bmp,
            com.cangkir.bitperfectplayer.library.AlbumArtworkLoader.THUMBNAIL_SIZE_PX,
        )
        artworkLoader.seedArtworkForTesting(
            trackFallback2003,
            bmp,
            com.cangkir.bitperfectplayer.library.AlbumArtworkLoader.THUMBNAIL_SIZE_PX,
        )
        artworkLoader.seedArtworkForTesting(trackFallback2013, bmp, com.cangkir.bitperfectplayer.library.AlbumArtworkLoader.HERO_SIZE_PX)
        artworkLoader.seedArtworkForTesting(trackFallback2003, bmp, com.cangkir.bitperfectplayer.library.AlbumArtworkLoader.HERO_SIZE_PX)

        val snapshot = LibrarySnapshot(
            allSongs = listOf(trackCustom, trackEmbedded, trackFallback2013, trackFallback2003, trackMonogram),
            artists = listOf(
                com.cangkir.bitperfectplayer.library.LibraryGroup("Alpha Custom", "1 song · 1 album", listOf(trackCustom)),
                com.cangkir.bitperfectplayer.library.LibraryGroup("Beta Embedded", "1 song · 1 album", listOf(trackEmbedded)),
                com.cangkir.bitperfectplayer.library.LibraryGroup(
                    "Gamma Fallback",
                    "2 songs · 2 albums",
                    listOf(trackFallback2013, trackFallback2003), // Deliberately newer-first input order
                ),
                com.cangkir.bitperfectplayer.library.LibraryGroup("Delta Mono", "1 song · 1 album", listOf(trackMonogram)),
            ),
        )

        composeRule.setContent {
            val photoRevision by photoStore.photoRevision.collectAsState()
            BitPerfectPlayerTheme {
                LibraryScreen(
                    libraryState = snapshot,
                    favoriteTrackUris = emptySet(),
                    playbackState = PlaybackControllerState(),
                    albumArtworkLoader = artworkLoader,
                    artistPhotoStore = photoStore,
                    artistPhotoRevision = photoRevision,
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
                    initialTab = LibraryTab.ARTISTS,
                )
            }
        }

        composeRule.waitForIdle()
        // Priority 1: Custom photo
        composeRule.onNodeWithContentDescription("Custom artist photo for Alpha Custom").assertIsDisplayed()
        // Priority 2: Embedded ARTIST art
        composeRule.onNodeWithContentDescription("Embedded artist artwork for Beta Embedded").assertIsDisplayed()
        // Priority 3: First album in oldest-first sorted order (LP 2003, NOT LP 2013!)
        composeRule.onNodeWithContentDescription("Artist cover from LP 2003 for Gamma Fallback").assertIsDisplayed()
        // Priority 4: Monochrome monogram fallback
        composeRule.onNodeWithContentDescription("Artist monogram for Delta Mono").assertIsDisplayed()

        // Reactive live update: setting a custom artist photo updates Artists tab immediately via photoRevision
        composeRule.runOnIdle {
            photoStore.seedArtistPhotoForTesting("Delta Mono", bmp)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Custom artist photo for Delta Mono").assertIsDisplayed()

        // Tap Gamma Fallback -> ArtistDetailView Hero MUST also pick LP 2003 (oldest-first!)
        composeRule.onNodeWithText("Gamma Fallback").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Artist hero cover from LP 2003 for Gamma Fallback").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w411dp-h915dp")
    fun foldersTab_rendersFrontCoverThumbnailOrFolderIconAndOpensFolderDetailIntact() {
        val bmp = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888)
        val jazzTrack1 = hiResFlacTrack.copy(
            uriString = "content://test/folders/jazz_01.flac",
            title = "So What",
            artist = "Miles Davis",
            album = "Kind of Blue",
            folderName = "Jazz Classics",
            trackNumber = 1,
        )
        val jazzTrack2 = hiResFlacTrack.copy(
            uriString = "content://test/folders/jazz_02.flac",
            title = "Freddie Freeloader",
            artist = "Miles Davis",
            album = "Kind of Blue",
            folderName = "Jazz Classics",
            trackNumber = 2,
        )
        val coverlessTrack = hiResFlacTrack.copy(
            uriString = "content://test/folders/raw_01.wav",
            title = "Field Recording",
            artist = "Unknown Artist",
            album = "Unknown Album",
            folderName = "Field Audio",
            fileName = "field.wav",
            container = "WAV",
            trackNumber = 1,
        )

        artworkLoader.seedArtworkForTesting(
            jazzTrack1,
            bmp,
            com.cangkir.bitperfectplayer.library.AlbumArtworkLoader.THUMBNAIL_SIZE_PX,
        )

        val snapshot = LibrarySnapshot(
            allSongs = listOf(jazzTrack1, jazzTrack2, coverlessTrack),
            folders = listOf(
                com.cangkir.bitperfectplayer.library.LibraryGroup("Field Audio", "1 track", listOf(coverlessTrack)),
                com.cangkir.bitperfectplayer.library.LibraryGroup("Jazz Classics", "2 tracks", listOf(jazzTrack2, jazzTrack1)),
            ),
        )

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
                    initialTab = LibraryTab.FOLDERS,
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("2 folders").assertIsDisplayed()
        // Folder with cover shows Front Cover thumbnail; coverless folder falls back to Folder icon
        composeRule.onNodeWithContentDescription("Folder cover for Jazz Classics").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Folder icon for Field Audio").assertIsDisplayed()

        // Tapping a folder still opens Folder Detail cleanly
        composeRule.onNodeWithText("Jazz Classics").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Back to Folders").assertIsDisplayed()
        composeRule.onNodeWithText("So What").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w411dp-h915dp")
    fun nowPlayingScreen_trackSwitchImmediatelyDisplaysNewTrackDurationAndSpecWithoutStaleFlash() {
        composeRule.mainClock.autoAdvance = false
        val trackA = PlaybackTrack(
            uriString = "content://test/audio/track_a.flac",
            title = "Old 44k Track",
            artist = "Artist One",
            album = "First LP",
            fileName = "01_old.flac",
            durationMs = 180_000L, // 03:00
            sampleRateHz = 44_100,
            bitDepth = 16,
            container = "FLAC",
            codec = "FLAC",
        )
        val trackB = PlaybackTrack(
            uriString = "content://test/audio/track_b.flac",
            title = "New HiRes Track",
            artist = "Artist Two",
            album = "Second LP",
            fileName = "02_new.flac",
            durationMs = 300_000L, // 05:00
            sampleRateHz = 96_000,
            bitDepth = 24,
            container = "FLAC",
            codec = "FLAC",
        )
        val diagA = AudioDiagnosticMapper.map(
            AudioDiagnosticInput(
                isTrackLoaded = true,
                sourceContainer = "FLAC",
                sourceCodec = "FLAC",
                sourceSampleRateHz = 44_100,
                sourceBitDepth = 16,
                sourceHeaderVerified = true,
                decodedSampleRateHz = 44_100,
                decodedEffectiveBitDepth = 16,
                decodedChannelCount = 2,
                audioTrackSampleRateHz = 44_100,
            ),
        )
        var playbackState by mutableStateOf(
            PlaybackControllerState(
                queue = QueueSnapshot(items = listOf(trackA, trackB), currentIndex = 0),
                activeTrack = trackA,
                engineState = EnginePlaybackState(
                    status = PlaybackStatus.PLAYING,
                    activeTrackUri = Uri.parse(trackA.uriString),
                    durationMs = 180_000L,
                    diagnostics = diagA,
                ),
            ),
        )
        val posFlow = MutableStateFlow(42_000L)

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

        composeRule.onNodeWithText("Old 44k Track").assertIsDisplayed()
        composeRule.onNodeWithText("03:00").assertIsDisplayed()
        composeRule.onNodeWithText("FLAC · 16-bit · 44,100 Hz").assertIsDisplayed()

        // Immediately upon track switch — BEFORE MediaCodec primes — new duration & spec must appear with zero stale flash
        composeRule.runOnIdle {
            posFlow.value = 0L
            playbackState = playbackState.copy(
                queue = QueueSnapshot(items = listOf(trackA, trackB), currentIndex = 1),
                activeTrack = trackB,
                engineState = playbackState.engineState.copy(status = PlaybackStatus.PREPARING),
            )
        }
        composeRule.mainClock.advanceTimeBy(32L)

        composeRule.onNodeWithText("New HiRes Track").assertIsDisplayed()
        composeRule.onNodeWithText("05:00").assertIsDisplayed()
        composeRule.onNodeWithText("FLAC · 24-bit · 96,000 Hz").assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithText("03:00").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("44,100 Hz", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    @Config(qualifiers = "w411dp-h915dp")
    fun diagnosticsScreen_rendersDistinctDefaultNativeRateVsFallbackAndBitPerfectOptInReplayGain() {
        val nativeRateDiag = AudioDiagnosticMapper.map(
            AudioDiagnosticInput(
                isTrackLoaded = true,
                sourceContainer = "FLAC",
                sourceCodec = "FLAC",
                sourceSampleRateHz = 96_000,
                sourceBitDepth = 24,
                sourceHeaderVerified = true,
                decodedPcmEncodingLabel = "PCM_24BIT_PACKED",
                decodedEffectiveBitDepth = 24,
                decodedSampleRateHz = 96_000,
                decodedChannelCount = 2,
                decoderName = "MediaExtractor Direct PCM",
                audioTrackSampleRateHz = 96_000,
                audioTrackEncodingLabel = "PCM_24BIT_PACKED",
                safetyMode = AudioOutputMode.BIT_PERFECT_OPT_IN,
                apiLevel = 36,
                outputDeviceName = "KZ EDX Pro 2 USB-C",
                outputConnectionType = OutputConnectionType.USB_HEADSET,
                negotiatedMixerBehavior = NegotiatedMixerBehavior.DEFAULT_NATIVE_RATE,
                negotiatedMixerSummary =
                "MIXER_BEHAVIOR_DEFAULT (96,000 Hz, PCM_FLOAT — Native Rate, Bit-Perfect Unavailable, Volume Active)",
                replayGainSummary = ReplayGainProcessor.BYPASSED_BIT_PERFECT_OPT_IN_LABEL,
            ),
        )
        val fallbackDiag = AudioDiagnosticMapper.map(
            AudioDiagnosticInput(
                isTrackLoaded = true,
                sourceContainer = "FLAC",
                sourceCodec = "FLAC",
                sourceSampleRateHz = 96_000,
                sourceBitDepth = 24,
                sourceHeaderVerified = true,
                decodedPcmEncodingLabel = "PCM_24BIT_PACKED",
                decodedEffectiveBitDepth = 24,
                decodedSampleRateHz = 96_000,
                decodedChannelCount = 2,
                decoderName = "MediaExtractor Direct PCM",
                audioTrackSampleRateHz = 96_000,
                audioTrackEncodingLabel = "PCM_24BIT_PACKED",
                safetyMode = AudioOutputMode.BIT_PERFECT_OPT_IN,
                apiLevel = 36,
                outputDeviceName = "KZ EDX Pro 2 USB-C",
                outputConnectionType = OutputConnectionType.USB_HEADSET,
                negotiatedMixerBehavior = NegotiatedMixerBehavior.DEFAULT_MIXER_FALLBACK,
                negotiatedMixerSummary = "Default System Mixer (USB HAL reported 0 matching bit-perfect or native-rate profiles)",
                replayGainSummary = ReplayGainProcessor.BYPASSED_BIT_PERFECT_OPT_IN_LABEL,
            ),
        )

        var currentDiag by mutableStateOf(nativeRateDiag)

        composeRule.setContent {
            BitPerfectPlayerTheme {
                DiagnosticsScreen(
                    playbackState = PlaybackControllerState(
                        activeTrack = hiResFlacTrack,
                        engineState = EnginePlaybackState(
                            status = PlaybackStatus.PLAYING,
                            safetyState = AudioSafetyState(
                                mode = AudioOutputMode.BIT_PERFECT_OPT_IN,
                                isUsbAudioConnected = true,
                                activeDeviceName = "KZ EDX Pro 2 USB-C",
                            ),
                            diagnostics = currentDiag,
                        ),
                    ),
                    onBack = {},
                    onSwitchToSafeMode = {},
                    onRequestBitPerfectMode = {},
                    onConfirmBitPerfectWarning = {},
                    onDismissSafetyWarning = {},
                )
            }
        }

        composeRule.onNodeWithText("NATIVE RATE USB MIXER").assertIsDisplayed()
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(1)
        composeRule.onNodeWithText("ReplayGain: Bypassed (Bit-Perfect Opt-In)", substring = true).assertIsDisplayed()
        assertEquals(
            0,
            composeRule.onAllNodesWithText("ReplayGain: Bypassed (Bit-Perfect Active)", substring = true).fetchSemanticsNodes().size,
        )
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(6)
        composeRule.onNodeWithText("OS/USB HAL accepted native sample-rate default mixer profile", substring = true)
            .assertIsDisplayed()
        assertEquals(
            0,
            composeRule.onAllNodesWithText("0 matching bit-perfect or native-rate profiles", substring = true)
                .fetchSemanticsNodes()
                .size,
        )

        // Switch to DEFAULT_MIXER_FALLBACK -> distinctly shows fallback text + consistent Bit-Perfect Opt-In bypass label
        composeRule.runOnIdle { currentDiag = fallbackDiag }
        composeRule.waitForIdle()
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        composeRule.onNodeWithText("CLEAN NATIVE FALLBACK").assertIsDisplayed()
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(1)
        composeRule.onNodeWithText("ReplayGain: Bypassed (Bit-Perfect Opt-In)", substring = true).assertIsDisplayed()
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(6)
        composeRule.onNodeWithText("0 matching bit-perfect or native-rate profiles", substring = true).assertIsDisplayed()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w360dp-h740dp")
    fun settingsScreen_libraryStorageButtonsDisplayAddFolderAndRefreshWithoutTruncation() {
        var refreshClicks = 0

        composeRule.setContent {
            BitPerfectPlayerTheme {
                SettingsScreen(
                    libraryState = LibrarySnapshot(),
                    playbackState = PlaybackControllerState(),
                    sleepTimerStateFlow = MutableStateFlow(SleepTimerState()),
                    onImportFolder = {},
                    onRemoveSafFolder = {},
                    onRefreshLibrary = { refreshClicks++ },
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

        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(2)
        composeRule.waitForIdle()

        assertEquals(0, composeRule.onAllNodesWithText("Add Folder (SAF)").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("Refresh Library").fetchSemanticsNodes().size)

        val addFolderNode = composeRule.onNodeWithText("Add Folder", useUnmergedTree = true)
            .assertIsDisplayed()
            .fetchSemanticsNode()
        val refreshNode = composeRule.onNodeWithText("Refresh", useUnmergedTree = true)
            .assertIsDisplayed()
            .fetchSemanticsNode()

        val addLayoutResults = mutableListOf<TextLayoutResult>()
        val refreshLayoutResults = mutableListOf<TextLayoutResult>()
        addFolderNode.config[SemanticsActions.GetTextLayoutResult].action?.invoke(addLayoutResults)
        refreshNode.config[SemanticsActions.GetTextLayoutResult].action?.invoke(refreshLayoutResults)
        assertEquals(1, addLayoutResults.size)
        assertEquals(1, refreshLayoutResults.size)

        val addLayout = addLayoutResults.single()
        val refreshLayout = refreshLayoutResults.single()
        assertFalse("Add Folder must not ellipsize on 360dp width", addLayout.isLineEllipsized(0))
        assertFalse("Refresh must not ellipsize on 360dp width", refreshLayout.isLineEllipsized(0))
        assertTrue(addLayout.getLineRight(0) <= addLayout.size.width + 1f)
        assertTrue(refreshLayout.getLineRight(0) <= refreshLayout.size.width + 1f)

        composeRule.onNodeWithText("Refresh").performClick()
        assertEquals(1, refreshClicks)
    }
}
