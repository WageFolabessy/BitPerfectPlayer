package com.cangkir.bitperfectplayer.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.cangkir.bitperfectplayer.audio.PlaybackAudioControl
import com.cangkir.bitperfectplayer.audio.PlaybackControllerState
import com.cangkir.bitperfectplayer.audio.PlaybackTrack
import com.cangkir.bitperfectplayer.audio.PlaybackTransportControl
import com.cangkir.bitperfectplayer.audio.QueueContext
import com.cangkir.bitperfectplayer.audio.QueueSnapshot
import com.cangkir.bitperfectplayer.audio.ReplayGainMode
import com.cangkir.bitperfectplayer.audio.SafetyCommand
import com.cangkir.bitperfectplayer.audio.SafetyEventType
import com.cangkir.bitperfectplayer.audio.SleepTimerPreset
import com.cangkir.bitperfectplayer.audio.SleepTimerState
import com.cangkir.bitperfectplayer.data.DefaultHomepage
import com.cangkir.bitperfectplayer.data.LocalBackupExportSummary
import com.cangkir.bitperfectplayer.data.LocalBackupRestoreSummary
import com.cangkir.bitperfectplayer.data.LocalBackupStore
import com.cangkir.bitperfectplayer.data.NowPlayingArtworkStyle
import com.cangkir.bitperfectplayer.data.PlaylistTrackEntity
import com.cangkir.bitperfectplayer.data.SpectrumColorStyle
import com.cangkir.bitperfectplayer.data.UserPreferences
import com.cangkir.bitperfectplayer.data.UserPreferencesStore
import com.cangkir.bitperfectplayer.diagnostics.DiagnosticExportResult
import com.cangkir.bitperfectplayer.diagnostics.DiagnosticLogExporterContract
import com.cangkir.bitperfectplayer.diagnostics.DiagnosticLogStats
import com.cangkir.bitperfectplayer.diagnostics.DiagnosticShareResult
import com.cangkir.bitperfectplayer.library.AddTrackToPlaylistOutcome
import com.cangkir.bitperfectplayer.library.ArtistPhotoStorage
import com.cangkir.bitperfectplayer.library.GroupSortOption
import com.cangkir.bitperfectplayer.library.GroupSortScope
import com.cangkir.bitperfectplayer.library.GroupSortSpec
import com.cangkir.bitperfectplayer.library.LibraryRepository
import com.cangkir.bitperfectplayer.library.LibrarySnapshot
import com.cangkir.bitperfectplayer.library.M3u8ImportSummary
import com.cangkir.bitperfectplayer.library.PlaylistStore
import com.cangkir.bitperfectplayer.library.PlaylistsUiSnapshot
import com.cangkir.bitperfectplayer.library.ResolvedPlaylist
import com.cangkir.bitperfectplayer.library.ResolvedPlaylistEntry
import com.cangkir.bitperfectplayer.library.SortDirection
import com.cangkir.bitperfectplayer.library.TrackDetailsLoader
import com.cangkir.bitperfectplayer.library.TrackSortOption
import com.cangkir.bitperfectplayer.library.TrackSortScope
import com.cangkir.bitperfectplayer.library.TrackSortSpec
import com.cangkir.bitperfectplayer.library.TrackTechnicalInfo
import com.cangkir.bitperfectplayer.lyrics.LyricsStore
import com.cangkir.bitperfectplayer.lyrics.TrackLyricsState
import com.cangkir.bitperfectplayer.ui.theme.CoverThemeResolver
import com.cangkir.bitperfectplayer.ui.theme.DynamicThemeTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelAndCoordinatorsTest {

    private lateinit var testDispatcher: TestDispatcher
    private val activeStores = mutableListOf<ViewModelStore>()

    @Before
    fun setUp() {
        testDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        activeStores.forEach { it.clear() }
        activeStores.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun `factory creates MainViewModel without AppContainer and rejects unknown ViewModel class`() = runTest(testDispatcher) {
        val h = createHarness()
        try {
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals(1, h.library.initialSyncCalls)

            try {
                h.factory.create(UnrelatedViewModel::class.java)
                fail("Expected IllegalArgumentException for unrelated ViewModel subclass")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().contains("Unknown ViewModel class"))
            }
        } finally {
            h.dispose()
            testDispatcher.scheduler.advanceUntilIdle()
        }
    }

    @Test
    fun `dismissActiveSession delegates directly to transport control and clears active state`() = runTest(testDispatcher) {
        val track = sampleTrack(id = 1, title = "Blue Train")
        val h = createHarness()
        try {
            h.playback.stateFlow.value = PlaybackControllerState(
                queue = QueueSnapshot(items = listOf(track), currentIndex = 0),
                activeTrack = track,
                activeFingerprint = track.contentFingerprint,
            )
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals(track, h.viewModel.playbackState.value.activeTrack)

            h.viewModel.dismissActiveSession()

            assertEquals(1, h.playback.dismissCount)
            assertEquals("MINI_PLAYER_SWIPE", h.playback.lastDismissSource)
            assertNull(h.viewModel.playbackState.value.activeTrack)
        } finally {
            h.dispose()
            testDispatcher.scheduler.advanceUntilIdle()
        }
    }

    @Test
    fun `playlist coordinator handles creation duplicate guard removal and Undo restoration`() = runTest(testDispatcher) {
        val track = sampleTrack(id = 10, title = "Time", fingerprint = "fp-time-01")
        val h = createHarness(tracks = listOf(track))
        try {
            testDispatcher.scheduler.advanceUntilIdle()

            h.viewModel.playlists.createPlaylistAndAddTrack("Late Night", track)
            testDispatcher.scheduler.advanceUntilIdle()

            val createdPlaylist = h.viewModel.playlistsState.value.playlists.single()
            assertEquals("Late Night", createdPlaylist.name)
            assertEquals(1, createdPlaylist.entries.size)
            assertEquals("Added \"Time\" to \"Late Night\"", h.viewModel.uiSnackbarMessage.value?.message)

            h.viewModel.playlists.addTrackToPlaylist(createdPlaylist.id, createdPlaylist.name, track)
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(1, h.viewModel.playlistsState.value.playlists.single().entries.size)
            assertEquals("\"Time\" is already in \"Late Night\"", h.viewModel.uiSnackbarMessage.value?.message)

            val entryId = createdPlaylist.entries.single().entryId
            h.viewModel.playlists.removePlaylistEntry(
                playlistId = createdPlaylist.id,
                entryId = entryId,
                playlistName = createdPlaylist.name,
                trackTitle = track.title,
            )
            testDispatcher.scheduler.advanceUntilIdle()

            assertTrue(h.viewModel.playlistsState.value.playlists.single().entries.isEmpty())
            val removalSnackbar = h.viewModel.uiSnackbarMessage.value
            assertNotNull(removalSnackbar)
            assertEquals("Undo", removalSnackbar?.actionLabel)
            assertEquals("Removed \"Time\" from \"Late Night\"", removalSnackbar?.message)

            removalSnackbar?.onAction?.invoke()
            testDispatcher.scheduler.advanceUntilIdle()

            val restoredEntries = h.viewModel.playlistsState.value.playlists.single().entries
            assertEquals(1, restoredEntries.size)
            assertEquals(track.uriString, restoredEntries.single().track.uriString)
        } finally {
            h.dispose()
            testDispatcher.scheduler.advanceUntilIdle()
        }
    }

    @Test
    fun `initialStartupHomepage latches once while settings updates propagate reactively`() = runTest(testDispatcher) {
        val h = createHarness(initialPrefs = UserPreferences(defaultHomepage = DefaultHomepage.PLAYLISTS))
        try {
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals(DefaultHomepage.PLAYLISTS, h.viewModel.initialStartupHomepage.value)
            assertEquals(DefaultHomepage.PLAYLISTS, h.viewModel.defaultHomepage.value)

            h.viewModel.settings.setDefaultHomepage(DefaultHomepage.FAVORITES)
            h.viewModel.settings.setNowPlayingArtworkStyle(NowPlayingArtworkStyle.SQUARE_COVER)
            h.viewModel.settings.setSpectrumVisualizerEnabled(false)
            h.viewModel.settings.setSpectrumColorStyle(SpectrumColorStyle.ALBUM_ADAPTIVE)
            h.viewModel.settings.setSpectrumSparksEnabled(false)
            h.viewModel.settings.setDynamicCoverThemeEnabled(true)
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(DefaultHomepage.PLAYLISTS, h.viewModel.initialStartupHomepage.value)
            assertEquals(DefaultHomepage.FAVORITES, h.viewModel.defaultHomepage.value)
            assertEquals(NowPlayingArtworkStyle.SQUARE_COVER, h.viewModel.nowPlayingArtworkStyle.value)
            assertFalse(h.viewModel.spectrumVisualizerEnabled.value)
            assertEquals(listOf(false), h.playback.spectrumTapActiveCalls)
            assertEquals(SpectrumColorStyle.ALBUM_ADAPTIVE, h.viewModel.spectrumColorStyle.value)
            assertFalse(h.viewModel.spectrumSparksEnabled.value)
            assertTrue(h.viewModel.dynamicCoverThemeEnabled.value)
        } finally {
            h.dispose()
            testDispatcher.scheduler.advanceUntilIdle()
        }
    }

    @Test
    fun `diagnostic logging toggle writes summary emits feedback and clears logs deterministically`() = runTest(testDispatcher) {
        val h = createHarness()
        try {
            testDispatcher.scheduler.advanceUntilIdle()

            h.viewModel.settings.setDiagnosticLoggingEnabled(true)
            testDispatcher.scheduler.advanceUntilIdle()

            assertTrue(h.diagnostics.isEnabled)
            assertTrue(h.viewModel.diagnosticLoggingEnabled.value)
            assertEquals(1, h.diagnostics.writtenSummaries.size)
            assertTrue(h.viewModel.uiSnackbarMessage.value?.message.orEmpty().contains("enabled"))

            h.viewModel.settings.clearDiagnosticLogs()
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(1, h.diagnostics.clearLogsCalls)
            assertEquals("Cleared local diagnostic logs", h.viewModel.uiSnackbarMessage.value?.message)
        } finally {
            h.dispose()
            testDispatcher.scheduler.advanceUntilIdle()
        }
    }

    @Test
    fun `library search query filters across title artist album filename and restores on blank`() = runTest(testDispatcher) {
        val tracks = listOf(
            sampleTrack(1, title = "Blue Train", artist = "John Coltrane", album = "Blue Train", fileName = "01_blue_train.flac"),
            sampleTrack(2, title = "Time", artist = "Pink Floyd", album = "Dark Side of the Moon", fileName = "04_time.wav"),
            sampleTrack(3, title = "Hammers", artist = "Nils Frahm", album = "Spaces", fileName = "hammers_live.ogg"),
        )
        val h = createHarness(tracks = tracks)
        try {
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals(3, h.viewModel.filteredAllSongs.value.size)

            h.viewModel.library.updateSearchQuery("  coltrane ")
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals(listOf("Blue Train"), h.viewModel.filteredAllSongs.value.map { it.title })

            h.viewModel.library.updateSearchQuery("dark side")
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals(listOf("Time"), h.viewModel.filteredAllSongs.value.map { it.title })

            h.viewModel.library.updateSearchQuery(".ogg")
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals(listOf("Hammers"), h.viewModel.filteredAllSongs.value.map { it.title })

            h.viewModel.library.updateSearchQuery("   ")
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals(3, h.viewModel.filteredAllSongs.value.size)
        } finally {
            h.dispose()
            testDispatcher.scheduler.advanceUntilIdle()
        }
    }

    @Test
    fun `library sort updates remain isolated per track and group scope`() = runTest(testDispatcher) {
        val h = createHarness()
        try {
            testDispatcher.scheduler.advanceUntilIdle()
            val initialSort = h.viewModel.libraryState.value.sortPreferences
            assertEquals(TrackSortOption.TITLE, initialSort.allSongs.option)
            assertEquals(TrackSortOption.RELEASE_YEAR, initialSort.artistDetail.option)
            assertEquals(SortDirection.ASC, initialSort.artistDetail.direction)

            h.viewModel.library.setTrackSort(
                TrackSortScope.ALL_SONGS,
                TrackSortSpec(TrackSortOption.SAMPLE_RATE, SortDirection.DESC),
            )
            h.viewModel.library.setGroupSort(
                GroupSortScope.ARTISTS_LIST,
                GroupSortSpec(GroupSortOption.TRACK_COUNT, SortDirection.DESC),
            )
            testDispatcher.scheduler.advanceUntilIdle()

            val updatedSort = h.viewModel.libraryState.value.sortPreferences
            assertEquals(TrackSortSpec(TrackSortOption.SAMPLE_RATE, SortDirection.DESC), updatedSort.allSongs)
            assertEquals(TrackSortSpec(TrackSortOption.RELEASE_YEAR, SortDirection.ASC), updatedSort.artistDetail)
            assertEquals(TrackSortScope.ALBUM_DETAIL.defaultSpec, updatedSort.albumDetail)
            assertEquals(GroupSortSpec(GroupSortOption.TRACK_COUNT, SortDirection.DESC), updatedSort.artistsList)
            assertEquals(GroupSortScope.ALBUMS_LIST.defaultSpec, updatedSort.albumsList)
            assertEquals(GroupSortScope.FOLDERS_LIST.defaultSpec, updatedSort.foldersList)
        } finally {
            h.dispose()
            testDispatcher.scheduler.advanceUntilIdle()
        }
    }

    private fun createHarness(tracks: List<PlaybackTrack> = emptyList(), initialPrefs: UserPreferences = UserPreferences()): TestHarness {
        val playback = FakePlaybackControl()
        val preferences = FakePreferencesStore(initialPrefs)
        val library = FakeLibraryRepository(
            LibrarySnapshot(
                allSongs = tracks,
                sortPreferences = initialPrefs.sortPreferences,
            ),
            tracks = tracks.associateBy { it.uriString },
        )
        preferences.onSortUpdated = { updatedPrefs ->
            library.updateFromPreferences(updatedPrefs)
        }
        val playlists = FakePlaylistStore(tracks)
        val diagnostics = FakeDiagnosticControls()
        val factory = MainViewModel.Factory(
            playbackTransport = playback,
            playbackAudio = playback,
            libraryRepository = library,
            playlistStore = playlists,
            artistPhotoStorage = FakeArtistPhotoStorage(),
            trackDetailsLoader = FakeTrackDetailsLoader(),
            lyricsStore = FakeLyricsStore(),
            preferencesStore = preferences,
            backupStore = FakeBackupStore(),
            diagnosticServices = DiagnosticServices(
                controls = diagnostics,
                exporter = FakeDiagnosticExporter(),
            ),
            mediaServices = MainViewModelMediaServices(
                coverThemeResolver = FakeCoverThemeResolver(),
                ioDispatcher = testDispatcher,
            ),
        )
        val store = ViewModelStore()
        activeStores.add(store)
        val viewModel = ViewModelProvider(store, factory)[MainViewModel::class.java]
        return TestHarness(
            viewModel = viewModel,
            factory = factory,
            store = store,
            playback = playback,
            library = library,
            diagnostics = diagnostics,
        )
    }

    private fun sampleTrack(
        id: Int,
        title: String,
        artist: String = "Artist",
        album: String = "Album",
        fileName: String = "track_$id.flac",
        fingerprint: String = "fp-$id",
    ): PlaybackTrack = PlaybackTrack(
        uriString = "content://media/external/audio/media/$id",
        title = title,
        artist = artist,
        album = album,
        fileName = fileName,
        contentFingerprint = fingerprint,
        durationMs = 180_000L,
        sampleRateHz = 96_000,
        bitDepth = 24,
        container = "FLAC",
        codec = "FLAC",
    )

    private class UnrelatedViewModel : ViewModel()

    private class TestHarness(
        val viewModel: MainViewModel,
        val factory: MainViewModel.Factory,
        private val store: ViewModelStore,
        val playback: FakePlaybackControl,
        val library: FakeLibraryRepository,
        val diagnostics: FakeDiagnosticControls,
    ) {
        fun dispose() {
            store.clear()
        }
    }

    private class FakePlaybackControl :
        PlaybackTransportControl,
        PlaybackAudioControl {
        val stateFlow = MutableStateFlow(PlaybackControllerState())
        override val state: StateFlow<PlaybackControllerState> = stateFlow
        override val playbackPositionMs: StateFlow<Long> = MutableStateFlow(0L)
        override val sleepTimerState: StateFlow<SleepTimerState> = MutableStateFlow(SleepTimerState())

        var dismissCount: Int = 0
        var lastDismissSource: String? = null
        val spectrumTapActiveCalls = mutableListOf<Boolean>()

        override fun restoreLastSessionIfNeeded(library: LibrarySnapshot, playlists: PlaylistsUiSnapshot) = Unit

        override fun playQueue(
            tracks: List<PlaybackTrack>,
            startIndex: Int,
            startPositionMs: Long,
            autoPlay: Boolean,
            context: QueueContext,
        ) = Unit

        override fun playQueueShuffled(tracks: List<PlaybackTrack>, context: QueueContext) = Unit

        override fun playTrackAt(queueIndex: Int) = Unit

        override fun enqueue(track: PlaybackTrack) = Unit

        override fun enqueueNext(track: PlaybackTrack) = Unit

        override fun enqueueAll(tracks: List<PlaybackTrack>, fallbackContext: QueueContext?) = Unit

        override fun enqueueAllNext(tracks: List<PlaybackTrack>, fallbackContext: QueueContext?) = Unit

        override fun removeQueueItem(activeIndex: Int) = Unit

        override fun moveQueueItem(fromIndex: Int, toIndex: Int) = Unit

        override fun togglePlayPause() = Unit

        override fun next(userInitiated: Boolean) = Unit

        override fun previous() = Unit

        override fun seekTo(positionMs: Long) = Unit

        override fun toggleShuffle() = Unit

        override fun cycleRepeatMode() = Unit

        override fun dismissActiveSession(source: String) {
            dismissCount += 1
            lastDismissSource = source
            stateFlow.value = PlaybackControllerState()
        }

        override fun beginTurntableScratch(): Boolean = false

        override fun updateTurntableScratchRate(playbackRate: Float) = Unit

        override fun endTurntableScratch(fallbackTargetMs: Long?) = Unit

        override fun setGaplessEnabled(enabled: Boolean) = Unit

        override fun setReplayGainMode(mode: ReplayGainMode) = Unit

        override fun setReplayGainPreampDb(preampDb: Float) = Unit

        override fun setReplayGainPreventClipping(preventClipping: Boolean) = Unit

        override fun setSpectrumTapActive(active: Boolean) {
            spectrumTapActiveCalls.add(active)
        }

        override fun sampleCurrentSpectrumWindow(outMonoSamples: FloatArray): Int = 0

        override fun setSleepTimerPreset(preset: SleepTimerPreset) = Unit

        override fun requestBitPerfectMode(): SafetyCommand = SafetyCommand(SafetyEventType.REQUIRES_SAFETY_WARNING)

        override fun confirmBitPerfectWarning(): SafetyCommand = SafetyCommand(SafetyEventType.NONE)

        override fun dismissSafetyWarning(): SafetyCommand = SafetyCommand(SafetyEventType.NONE)

        override fun switchToSafeMode(): SafetyCommand = SafetyCommand(SafetyEventType.MODE_CHANGED_TO_SAFE)

        override fun clearAutoPauseNotice() = Unit
    }

    private class FakeLibraryRepository(initial: LibrarySnapshot, tracks: Map<String, PlaybackTrack>) : LibraryRepository {
        private val mutableSnapshot = MutableStateFlow(initial)
        override val librarySnapshot: StateFlow<LibrarySnapshot> = mutableSnapshot
        private val tracksByUri = tracks
        var initialSyncCalls = 0
        var forcedRefreshCalls = 0

        override suspend fun refreshLibrary(forceYearBackfill: Boolean) {
            if (forceYearBackfill) forcedRefreshCalls++ else initialSyncCalls++
        }

        override suspend fun importSafFolder(treeUri: Uri): Int = 0

        override suspend fun removeSafFolder(treeUriString: String) = Unit

        override suspend fun toggleFavorite(track: PlaybackTrack): Result<Boolean> = Result.success(true)

        fun updateFromPreferences(prefs: UserPreferences) {
            mutableSnapshot.update { it.copy(sortPreferences = prefs.sortPreferences, allSongs = tracksByUri.values.toList()) }
        }
    }

    private class FakePreferencesStore(initial: UserPreferences) : UserPreferencesStore {
        private val mutablePrefs = MutableStateFlow(initial)
        override val preferencesFlow: Flow<UserPreferences> = mutablePrefs
        var onSortUpdated: ((UserPreferences) -> Unit)? = null

        override suspend fun getSnapshot(): UserPreferences = mutablePrefs.value

        override suspend fun setTrackSort(scope: TrackSortScope, spec: TrackSortSpec) {
            mutablePrefs.update { current ->
                val sort = current.sortPreferences
                val updatedSort = when (scope) {
                    TrackSortScope.ALL_SONGS -> sort.copy(allSongs = spec)
                    TrackSortScope.ALBUM_DETAIL -> sort.copy(albumDetail = spec)
                    TrackSortScope.ARTIST_DETAIL -> sort.copy(artistDetail = spec)
                    TrackSortScope.FOLDER_DETAIL -> sort.copy(folderDetail = spec)
                    TrackSortScope.RECENT -> sort.copy(recent = spec)
                    TrackSortScope.FAVORITES -> sort.copy(favorites = spec)
                    TrackSortScope.PLAYLIST_DETAIL -> sort.copy(playlistDetail = spec)
                }
                current.copy(sortPreferences = updatedSort)
            }
            onSortUpdated?.invoke(mutablePrefs.value)
        }

        override suspend fun setGroupSort(scope: GroupSortScope, spec: GroupSortSpec) {
            mutablePrefs.update { current ->
                val sort = current.sortPreferences
                val updatedSort = when (scope) {
                    GroupSortScope.FOLDERS_LIST -> sort.copy(foldersList = spec)
                    GroupSortScope.ARTISTS_LIST -> sort.copy(artistsList = spec)
                    GroupSortScope.ALBUMS_LIST -> sort.copy(albumsList = spec)
                }
                current.copy(sortPreferences = updatedSort)
            }
            onSortUpdated?.invoke(mutablePrefs.value)
        }

        override suspend fun setNowPlayingArtworkStyle(style: NowPlayingArtworkStyle) {
            mutablePrefs.update { it.copy(nowPlayingArtworkStyle = style) }
        }

        override suspend fun setSpectrumVisualizerEnabled(enabled: Boolean) {
            mutablePrefs.update { it.copy(spectrumVisualizerEnabled = enabled) }
        }

        override suspend fun setSpectrumColorStyle(style: SpectrumColorStyle) {
            mutablePrefs.update { it.copy(spectrumColorStyle = style) }
        }

        override suspend fun setSpectrumSparksEnabled(enabled: Boolean) {
            mutablePrefs.update { it.copy(spectrumSparksEnabled = enabled) }
        }

        override suspend fun setDefaultHomepage(homepage: DefaultHomepage) {
            mutablePrefs.update { it.copy(defaultHomepage = homepage) }
        }

        override suspend fun setDynamicCoverThemeEnabled(enabled: Boolean) {
            mutablePrefs.update { it.copy(dynamicCoverThemeEnabled = enabled) }
        }

        override suspend fun setDynamicThemeIntensity(intensity: com.cangkir.bitperfectplayer.ui.theme.DynamicThemeIntensity) {
            mutablePrefs.update { it.copy(dynamicThemeIntensity = intensity) }
        }

        override suspend fun setDiagnosticLoggingEnabled(enabled: Boolean) {
            mutablePrefs.update { it.copy(diagnosticLoggingEnabled = enabled) }
        }
    }

    private class FakePlaylistStore(knownTracks: List<PlaybackTrack>) : PlaylistStore {
        private val tracksByUri = knownTracks.associateBy { it.uriString }.toMutableMap()
        private var nextPlaylistId = 1L
        private var nextEntryId = 1L
        private val names = linkedMapOf<Long, String>()
        private val entriesByPlaylist = linkedMapOf<Long, MutableList<PlaylistTrackEntity>>()
        private val mutablePlaylists = MutableStateFlow(PlaylistsUiSnapshot(isLoaded = true))
        override val playlistsSnapshot: StateFlow<PlaylistsUiSnapshot> = mutablePlaylists

        override suspend fun createPlaylist(rawName: String): Result<Long> {
            val clean = rawName.trim()
            if (clean.isEmpty()) return Result.failure(IllegalArgumentException("Playlist name cannot be empty"))
            val id = nextPlaylistId++
            names[id] = clean
            entriesByPlaylist[id] = mutableListOf()
            publish()
            return Result.success(id)
        }

        override suspend fun renamePlaylist(playlistId: Long, newRawName: String): Result<Unit> {
            names[playlistId] = newRawName.trim()
            publish()
            return Result.success(Unit)
        }

        override suspend fun deletePlaylist(playlistId: Long): Result<Unit> {
            names.remove(playlistId)
            entriesByPlaylist.remove(playlistId)
            publish()
            return Result.success(Unit)
        }

        override suspend fun addTracksToPlaylist(playlistId: Long, tracks: List<PlaybackTrack>): Result<Int> {
            val list = entriesByPlaylist[playlistId] ?: return Result.failure(IllegalStateException("Missing playlist"))
            tracks.forEach { track ->
                tracksByUri[track.uriString] = track
                list.add(
                    PlaylistTrackEntity(
                        id = nextEntryId++,
                        playlistId = playlistId,
                        contentFingerprint = track.contentFingerprint,
                        trackUri = track.uriString,
                        fileName = track.fileName,
                        positionIndex = list.size,
                        addedAtEpochMs = 1L,
                    ),
                )
            }
            publish()
            return Result.success(tracks.size)
        }

        override suspend fun addTrackToPlaylistUnique(playlistId: Long, track: PlaybackTrack): Result<AddTrackToPlaylistOutcome> {
            val list = entriesByPlaylist[playlistId] ?: return Result.failure(IllegalStateException("Missing playlist"))
            val duplicate = list.any {
                it.trackUri == track.uriString ||
                    (track.contentFingerprint != null && it.contentFingerprint == track.contentFingerprint)
            }
            if (duplicate) return Result.success(AddTrackToPlaylistOutcome.ALREADY_EXISTS)
            tracksByUri[track.uriString] = track
            list.add(
                PlaylistTrackEntity(
                    id = nextEntryId++,
                    playlistId = playlistId,
                    contentFingerprint = track.contentFingerprint,
                    trackUri = track.uriString,
                    fileName = track.fileName,
                    positionIndex = list.size,
                    addedAtEpochMs = 1L,
                ),
            )
            publish()
            return Result.success(AddTrackToPlaylistOutcome.ADDED)
        }

        override suspend fun removeEntryFromPlaylist(playlistId: Long, entryId: Long): Result<PlaylistTrackEntity?> {
            val list = entriesByPlaylist[playlistId] ?: return Result.success(null)
            val idx = list.indexOfFirst { it.id == entryId }
            val removed = if (idx >= 0) list.removeAt(idx) else null
            publish()
            return Result.success(removed)
        }

        override suspend fun restoreRemovedEntry(entity: PlaylistTrackEntity): Result<Unit> {
            val list = entriesByPlaylist[entity.playlistId] ?: return Result.failure(IllegalStateException("Missing playlist"))
            val insertIdx = entity.positionIndex.coerceIn(0, list.size)
            list.add(insertIdx, entity)
            publish()
            return Result.success(Unit)
        }

        override suspend fun commitPlaylistOrder(playlistId: Long, orderedVisibleEntryIds: List<Long>): Result<Unit> = Result.success(Unit)

        override suspend fun exportPlaylistToM3u8(playlist: ResolvedPlaylist, destinationUri: Uri): Result<Int> = unusedResult()

        override suspend fun importPlaylistFromM3u8(sourceUri: Uri, libraryTracks: List<PlaybackTrack>): Result<M3u8ImportSummary> =
            unusedResult()

        private fun publish() {
            val resolved = names.map { (id, name) ->
                val entries = entriesByPlaylist[id].orEmpty().mapNotNull { entity ->
                    val track = tracksByUri[entity.trackUri] ?: return@mapNotNull null
                    ResolvedPlaylistEntry(
                        entryId = entity.id,
                        playlistId = id,
                        positionIndex = entity.positionIndex,
                        track = track,
                    )
                }
                ResolvedPlaylist(
                    id = id,
                    name = name,
                    createdAtEpochMs = 1L,
                    updatedAtEpochMs = 1L,
                    entries = entries,
                )
            }
            mutablePlaylists.value = PlaylistsUiSnapshot(playlists = resolved, isLoaded = true)
        }
    }

    private class FakeDiagnosticControls : DiagnosticCoordinatorControls {
        override var isEnabled: Boolean = false
            private set
        override val storageStats: MutableStateFlow<DiagnosticLogStats> = MutableStateFlow(DiagnosticLogStats())
        val writtenSummaries = mutableListOf<UserPreferences>()
        var clearLogsCalls: Int = 0

        override fun setEnabled(enabled: Boolean) {
            isEnabled = enabled
        }

        override fun writeDeviceSummary(prefs: UserPreferences) {
            writtenSummaries.add(prefs)
        }

        override fun buildExportSummaryFieldsIfEnabled(prefs: UserPreferences): Map<String, Any?>? = if (isEnabled) {
            mapOf("defaultHomepage" to prefs.defaultHomepage.name)
        } else {
            null
        }

        override fun refreshStorageStatsSync(): DiagnosticLogStats = storageStats.value

        override fun clearAllLogs(): DiagnosticLogStats {
            clearLogsCalls += 1
            storageStats.value = DiagnosticLogStats()
            return storageStats.value
        }
    }

    private class FakeArtistPhotoStorage : ArtistPhotoStorage {
        override val photoRevision: StateFlow<Int> = MutableStateFlow(0)

        override suspend fun saveArtistPhotoResult(artistName: String, sourceUri: Uri): Result<Unit> = Result.success(Unit)

        override suspend fun clearArtistPhoto(artistName: String): Boolean = true
    }

    private class FakeTrackDetailsLoader : TrackDetailsLoader {
        override suspend fun loadFullDetails(track: PlaybackTrack): TrackTechnicalInfo = TrackTechnicalInfo(track, isLoadingDetails = false)
    }

    private class FakeLyricsStore : LyricsStore {
        override suspend fun loadLyricsForTrack(track: PlaybackTrack): TrackLyricsState? = null

        override suspend fun importLrcFromSaf(track: PlaybackTrack, lrcUri: Uri): Result<TrackLyricsState> = unusedResult()

        override suspend fun saveManualLyrics(track: PlaybackTrack, rawText: String): Result<TrackLyricsState> = unusedResult()

        override suspend fun deleteLyrics(track: PlaybackTrack): Result<TrackLyricsState> = unusedResult()
    }

    private class FakeBackupStore : LocalBackupStore {
        override suspend fun exportBackupToUri(destinationUri: Uri): Result<LocalBackupExportSummary> = unusedResult()

        override suspend fun importBackupFromUri(sourceUri: Uri): Result<LocalBackupRestoreSummary> = unusedResult()
    }

    private class FakeDiagnosticExporter : DiagnosticLogExporterContract {
        override suspend fun exportToUri(destinationUri: Uri, summaryFields: Map<String, Any?>?): Result<DiagnosticExportResult> =
            unusedResult()

        override suspend fun prepareShareIntent(summaryFields: Map<String, Any?>?): Result<DiagnosticShareResult> = unusedResult()
    }

    private class FakeCoverThemeResolver : CoverThemeResolver {
        override fun cacheKeyFor(track: PlaybackTrack): String = track.uriString

        override suspend fun resolveTokens(track: PlaybackTrack?): DynamicThemeTokens? = null

        override fun clear() = Unit
    }

    private companion object {
        fun <T> unusedResult(): Result<T> {
            val err = UnsupportedOperationException("Not exercised in pure JVM test")
            return Result.failure(err)
        }
    }
}
