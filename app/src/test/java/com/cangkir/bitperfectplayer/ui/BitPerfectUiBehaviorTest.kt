package com.cangkir.bitperfectplayer.ui

import android.Manifest
import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
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
import androidx.test.core.app.ApplicationProvider
import com.cangkir.bitperfectplayer.audio.AudioOutputMode
import com.cangkir.bitperfectplayer.audio.AudioSafetyState
import com.cangkir.bitperfectplayer.audio.EnginePlaybackState
import com.cangkir.bitperfectplayer.audio.PlaybackControllerState
import com.cangkir.bitperfectplayer.audio.PlaybackStatus
import com.cangkir.bitperfectplayer.audio.PlaybackTrack
import com.cangkir.bitperfectplayer.audio.QueueContext
import com.cangkir.bitperfectplayer.audio.QueueSnapshot
import com.cangkir.bitperfectplayer.audio.ReplayGainMetadata
import com.cangkir.bitperfectplayer.audio.SleepTimerPreset
import com.cangkir.bitperfectplayer.audio.SleepTimerState
import com.cangkir.bitperfectplayer.data.NowPlayingArtworkStyle
import com.cangkir.bitperfectplayer.library.AlbumArtworkLoader
import com.cangkir.bitperfectplayer.library.LibrarySnapshot
import com.cangkir.bitperfectplayer.library.LibrarySortPreferences
import com.cangkir.bitperfectplayer.library.PlaylistsUiSnapshot
import com.cangkir.bitperfectplayer.library.SortDirection
import com.cangkir.bitperfectplayer.library.TrackSortOption
import com.cangkir.bitperfectplayer.library.TrackSortSpec
import com.cangkir.bitperfectplayer.library.TrackTechnicalInfo
import com.cangkir.bitperfectplayer.ui.library.LibraryScreen
import com.cangkir.bitperfectplayer.ui.nowplaying.NowPlayingScreen
import com.cangkir.bitperfectplayer.ui.playlists.PlaylistsScreen
import com.cangkir.bitperfectplayer.ui.settings.SettingsScreen
import com.cangkir.bitperfectplayer.ui.theme.BitPerfectPlayerTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

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
                    onOpenQueue = {},
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
                    onOpenQueue = {},
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
                    onOpenQueue = {},
                    onOpenDiagnostics = {},
                )
            }
        }

        // Initial top item is Track 01; scroll deep into the list (index 18 -> Track 19)
        composeRule.onNodeWithText("Track 01").assertIsDisplayed()
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(18)
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
                    onOpenQueue = {},
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
                            onOpenQueue = {},
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

        // Switching tabs via NavigationRail works cleanly
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
        composeRule.onNodeWithText("Queue (1)").assertIsDisplayed()
        composeRule.onNodeWithText("Lyrics").assertIsDisplayed()
        composeRule.onNodeWithText("Audio Path").assertIsDisplayed()

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

        composeRule.onNodeWithContentDescription("Settings").assertIsDisplayed().performClick()
        assertEquals("settings", selectedRoute)
    }
}
