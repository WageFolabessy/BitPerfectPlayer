package com.cangkir.bitperfectplayer.ui.library

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.cangkir.bitperfectplayer.audio.AudioOutputMode
import com.cangkir.bitperfectplayer.audio.PlaybackControllerState
import com.cangkir.bitperfectplayer.audio.PlaybackTrack
import com.cangkir.bitperfectplayer.audio.QueueContext
import com.cangkir.bitperfectplayer.audio.QueueContextResolver
import com.cangkir.bitperfectplayer.library.AlbumArtworkLoader
import com.cangkir.bitperfectplayer.library.ArtistDetailHelper
import com.cangkir.bitperfectplayer.library.ArtistPhotoStore
import com.cangkir.bitperfectplayer.library.GroupSortScope
import com.cangkir.bitperfectplayer.library.GroupSortSpec
import com.cangkir.bitperfectplayer.library.LibraryGroup
import com.cangkir.bitperfectplayer.library.LibrarySnapshot
import com.cangkir.bitperfectplayer.library.TrackSortScope
import com.cangkir.bitperfectplayer.library.TrackSortSpec
import com.cangkir.bitperfectplayer.ui.GroupSortBottomSheet
import com.cangkir.bitperfectplayer.ui.LibraryAndLyricsCoordinator
import com.cangkir.bitperfectplayer.ui.LibraryTab
import com.cangkir.bitperfectplayer.ui.TrackSortBottomSheet
import com.cangkir.bitperfectplayer.ui.theme.AmberIndicated
import com.cangkir.bitperfectplayer.ui.theme.BitPerfectPlayerTheme
import com.cangkir.bitperfectplayer.ui.theme.CoverThemeColorCache
import com.cangkir.bitperfectplayer.ui.theme.CoverThemeLookup
import com.cangkir.bitperfectplayer.ui.theme.DynamicThemeTokens
import com.cangkir.bitperfectplayer.ui.theme.EmeraldSafe
import com.cangkir.bitperfectplayer.ui.theme.LocalActiveDynamicTokens
import com.cangkir.bitperfectplayer.ui.theme.ThemeArtworkCandidateSelector
import kotlinx.coroutines.launch

internal const val LIBRARY_SUBTAB_PAGER_TEST_TAG = "library_subtab_pager"

@Composable
fun LibraryScreen(
    libraryState: LibrarySnapshot,
    favoriteTrackUris: Set<String>,
    playbackState: PlaybackControllerState,
    albumArtworkLoader: AlbumArtworkLoader,
    onRefreshLibrary: () -> Unit,
    onImportFolder: (android.net.Uri) -> Unit,
    onPlayTracks: (List<PlaybackTrack>, Int, QueueContext) -> Unit,
    onEnqueueTrack: (PlaybackTrack) -> Unit,
    onEnqueueNext: (PlaybackTrack) -> Unit,
    onEnqueueTracks: (List<PlaybackTrack>, QueueContext) -> Unit,
    onEnqueueTracksNext: (List<PlaybackTrack>, QueueContext) -> Unit,
    onSetTrackSort: (TrackSortScope, TrackSortSpec) -> Unit,
    onSetGroupSort: (GroupSortScope, GroupSortSpec) -> Unit,
    onAddToPlaylist: (PlaybackTrack) -> Unit,
    onShowTrackInfo: (PlaybackTrack) -> Unit,
    onToggleFavorite: (PlaybackTrack) -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenQueue: () -> Unit = {},
    initialTab: LibraryTab = LibraryTab.ALL_SONGS,
    dynamicCoverThemeEnabled: Boolean = false,
    dynamicThemeIntensity: com.cangkir.bitperfectplayer.ui.theme.DynamicThemeIntensity =
        com.cangkir.bitperfectplayer.ui.theme.DynamicThemeIntensity.VIBRANT,
    coverThemeColorCache: CoverThemeColorCache? = null,
    artistPhotoStore: ArtistPhotoStore? = null,
    artistPhotoRevision: Int = 0,
    onShuffleTracks: (List<PlaybackTrack>, QueueContext) -> Unit = { tracks, ctx -> onPlayTracks(tracks, 0, ctx) },
    onSetCustomArtistPhoto: (String, android.net.Uri) -> Unit = { _, _ -> },
    onResetCustomArtistPhoto: (String) -> Unit = {},
    onToggleFavoriteFromFavorites: ((PlaybackTrack) -> Unit)? = null,
    onDetailViewOpenChanged: (Boolean) -> Unit = {},
    onContextualDynamicThemeChanged: ((DynamicThemeTokens?) -> Unit)? = null,
    isForegroundPage: Boolean = true,
    externalSearchQuery: String? = null,
    onExternalSearchQueryChange: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val pagerState = rememberPagerState(
        initialPage = initialTab.ordinal,
        pageCount = { LibraryTab.entries.size },
    )
    val coroutineScope = rememberCoroutineScope()
    var pendingTapTabIndex by remember { mutableStateOf<Int?>(null) }
    val selectedTab = LibraryTab.fromPageIndex(pagerState.currentPage)
    val tabRowSelectedTab = LibraryTab.fromPageIndex(pendingTapTabIndex ?: pagerState.currentPage)

    val allSongsListState = rememberLazyListState()
    val foldersListState = rememberLazyListState()
    val artistsListState = rememberLazyListState()
    val albumsListState = rememberLazyListState()
    val recentListState = rememberLazyListState()
    val favoritesListState = rememberLazyListState()

    var localSearchQuery by rememberSaveable { mutableStateOf("") }
    val searchQuery = externalSearchQuery ?: localSearchQuery
    val onSearchQueryChange: (String) -> Unit = { updated ->
        if (onExternalSearchQueryChange != null) {
            onExternalSearchQueryChange(updated)
        } else {
            localSearchQuery = updated
        }
    }
    var openedGroupName by rememberSaveable { mutableStateOf<String?>(null) }
    var openedArtistAlbumName by rememberSaveable { mutableStateOf<String?>(null) }

    var activeTrackSortSheet by remember { mutableStateOf<TrackSortScope?>(null) }
    var activeGroupSortSheet by remember { mutableStateOf<GroupSortScope?>(null) }

    val audioPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    var hasAudioPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, audioPermission) == PackageManager.PERMISSION_GRANTED)
    }
    var hasNotificationPermission by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        hasAudioPermission = results[audioPermission] == true ||
            ContextCompat.checkSelfPermission(context, audioPermission) == PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasNotificationPermission = results[Manifest.permission.POST_NOTIFICATIONS] == true ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        }
        if (hasAudioPermission) onRefreshLibrary()
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri ->
        if (treeUri != null) onImportFolder(treeUri)
    }

    val activeGroups = when (selectedTab) {
        LibraryTab.FOLDERS -> libraryState.folders
        LibraryTab.ARTISTS -> libraryState.artists
        LibraryTab.ALBUMS -> libraryState.albums
        else -> emptyList()
    }
    val openedGroup = openedGroupName?.let { name -> activeGroups.firstOrNull { it.name == name } }
    val sortPrefs = libraryState.sortPreferences

    // Child Album Detail opened from inside an Artist Detail view (preserves parent backstack)
    val openedArtistAlbumGroup: LibraryGroup? = remember(
        selectedTab,
        openedGroup,
        openedArtistAlbumName,
        sortPrefs.albumDetail,
    ) {
        val targetAlbum = openedArtistAlbumName
        if (selectedTab != LibraryTab.ARTISTS || openedGroup == null || targetAlbum == null) {
            null
        } else {
            val detail = ArtistDetailHelper.buildArtistDetail(
                artistName = openedGroup.name,
                artistTracks = openedGroup.tracks,
                albumTrackSortSpec = sortPrefs.albumDetail,
            )
            detail.albums.firstOrNull { it.albumName == targetAlbum }?.let { albumItem ->
                val trackWord = if (albumItem.trackCount == 1) "track" else "tracks"
                val yearPrefix = if (albumItem.year > 0) "${albumItem.year} · " else ""
                LibraryGroup(
                    name = albumItem.albumName,
                    subtitle = "${openedGroup.name} · $yearPrefix${albumItem.trackCount} $trackWord",
                    tracks = albumItem.tracks,
                )
            }
        }
    }

    val isArtistDetailOpen = selectedTab == LibraryTab.ARTISTS && openedGroup != null && openedArtistAlbumGroup == null
    val isArtistAlbumDetailOpen = selectedTab == LibraryTab.ARTISTS && openedGroup != null && openedArtistAlbumGroup != null
    val isStandaloneAlbumDetailOpen = selectedTab == LibraryTab.ALBUMS && openedGroup != null
    val isAnyAlbumDetailOpen = isStandaloneAlbumDetailOpen || isArtistAlbumDetailOpen
    val isAnyDetailOpen = openedGroup != null || openedArtistAlbumGroup != null

    val latestOnDetailOpenChanged by rememberUpdatedState(onDetailViewOpenChanged)
    LaunchedEffect(isForegroundPage, isAnyDetailOpen) {
        if (isForegroundPage) {
            latestOnDetailOpenChanged(isAnyDetailOpen)
        }
    }
    DisposableEffect(Unit) {
        onDispose { latestOnDetailOpenChanged(false) }
    }

    val effectiveDetailGroup = openedArtistAlbumGroup ?: openedGroup
    val effectiveDetailContext: QueueContext? = remember(selectedTab, openedGroup, openedArtistAlbumGroup) {
        when {
            openedArtistAlbumGroup != null -> QueueContext.album(openedArtistAlbumGroup.name)
            openedGroup != null -> when (selectedTab) {
                LibraryTab.ALBUMS -> QueueContext.album(openedGroup.name)
                LibraryTab.ARTISTS -> QueueContext.artist(openedGroup.name)
                LibraryTab.FOLDERS -> QueueContext.folder(openedGroup.name)
                else -> QueueContext.allSongs()
            }
            else -> null
        }
    }
    val effectiveDetailSortScope: TrackSortScope? = when {
        openedArtistAlbumGroup != null -> TrackSortScope.ALBUM_DETAIL
        openedGroup != null -> when (selectedTab) {
            LibraryTab.ALBUMS -> TrackSortScope.ALBUM_DETAIL
            LibraryTab.ARTISTS -> TrackSortScope.ARTIST_DETAIL
            LibraryTab.FOLDERS -> TrackSortScope.FOLDER_DETAIL
            else -> null
        }
        else -> null
    }

    val isBitPerfectActive = playbackState.engineState.diagnostics.isBitPerfectMixerActive
    val isBitPerfectOptIn = playbackState.engineState.safetyState.mode == AudioOutputMode.BIT_PERFECT_OPT_IN
    val statusText = when {
        isBitPerfectActive -> "Bit-Perfect USB"
        isBitPerfectOptIn -> "Clean Native"
        else -> "Safe Mode"
    }

    val launchImportFolder = remember(folderPickerLauncher) { { folderPickerLauncher.launch(null) } }
    val onSelectGroupForTab = remember(pagerState, coroutineScope) {
        { tab: LibraryTab, group: LibraryGroup ->
            openedGroupName = group.name
            openedArtistAlbumName = null
            if (pagerState.currentPage != tab.ordinal) {
                coroutineScope.launch { pagerState.scrollToPage(tab.ordinal) }
            }
        }
    }
    val navigateDetailBack = remember {
        {
            if (openedArtistAlbumName != null) {
                openedArtistAlbumName = null
            } else {
                openedGroupName = null
            }
        }
    }

    BackHandler(enabled = openedArtistAlbumName != null || openedGroup != null) {
        navigateDetailBack()
    }

    val albumThemeCandidates = remember(isAnyAlbumDetailOpen, effectiveDetailGroup) {
        if (isAnyAlbumDetailOpen && effectiveDetailGroup != null) {
            ThemeArtworkCandidateSelector.selectStableCandidates(effectiveDetailGroup.tracks)
        } else {
            emptyList()
        }
    }

    AlbumDetailDynamicThemeWrapper(
        enabled = dynamicCoverThemeEnabled && isAnyAlbumDetailOpen,
        dynamicThemeIntensity = dynamicThemeIntensity,
        isForegroundPage = isForegroundPage && !isArtistDetailOpen,
        manageHostContextualTheme = !isArtistDetailOpen,
        candidates = albumThemeCandidates,
        coverThemeColorCache = coverThemeColorCache,
        onContextualDynamicThemeChanged = onContextualDynamicThemeChanged,
    ) {
        activeTrackSortSheet?.let { scope ->
            TrackSortBottomSheet(
                scope = scope,
                currentSpec = sortPrefs.specFor(scope),
                onSelectSpec = { spec -> onSetTrackSort(scope, spec) },
                onDismiss = { activeTrackSortSheet = null },
            )
        }

        activeGroupSortSheet?.let { scope ->
            GroupSortBottomSheet(
                scope = scope,
                currentSpec = sortPrefs.specFor(scope),
                onSelectSpec = { spec -> onSetGroupSort(scope, spec) },
                onDismiss = { activeGroupSortSheet = null },
            )
        }

        if (isArtistDetailOpen) {
            val activeArtistGroup = openedGroup
            val artistContext = QueueContext.artist(activeArtistGroup.name)
            ArtistDetailView(
                artistGroup = activeArtistGroup,
                albumTrackSortSpec = sortPrefs.albumDetail,
                artistTrackSortLabel = sortPrefs.artistDetail.compactLabel,
                scrollResetKey = Pair(TrackSortScope.ARTIST_DETAIL, sortPrefs.artistDetail),
                activeTrackUri = playbackState.activeTrack?.uriString,
                favoriteTrackUris = favoriteTrackUris,
                favoriteFingerprints = libraryState.favoriteFingerprints,
                albumArtworkLoader = albumArtworkLoader,
                artistPhotoStore = artistPhotoStore,
                artistPhotoRevision = artistPhotoRevision,
                dynamicCoverThemeEnabled = dynamicCoverThemeEnabled,
                dynamicThemeIntensity = dynamicThemeIntensity,
                coverThemeColorCache = coverThemeColorCache,
                onBack = {
                    openedArtistAlbumName = null
                    openedGroupName = null
                },
                onOpenAlbum = { albumItem -> openedArtistAlbumName = albumItem.albumName },
                onOpenSort = { activeTrackSortSheet = TrackSortScope.ARTIST_DETAIL },
                onPlayTracks = { tracks, index -> onPlayTracks(tracks, index, artistContext) },
                onShuffleTracks = { tracks -> onShuffleTracks(tracks, artistContext) },
                onEnqueueAllNext = { onEnqueueTracksNext(activeArtistGroup.tracks, artistContext) },
                onEnqueueAll = { onEnqueueTracks(activeArtistGroup.tracks, artistContext) },
                onEnqueueTrack = onEnqueueTrack,
                onEnqueueNext = onEnqueueNext,
                onAddToPlaylist = onAddToPlaylist,
                onShowTrackInfo = onShowTrackInfo,
                onToggleFavorite = onToggleFavorite,
                onSetCustomArtistPhoto = onSetCustomArtistPhoto,
                onResetCustomArtistPhoto = onResetCustomArtistPhoto,
                isForegroundPage = isForegroundPage,
                onContextualDynamicThemeChanged = onContextualDynamicThemeChanged,
            )
            return@AlbumDetailDynamicThemeWrapper
        }

        val statusDotColor = when {
            isBitPerfectActive -> MaterialTheme.colorScheme.primary
            isBitPerfectOptIn -> AmberIndicated
            else -> EmeraldSafe
        }

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = ScaffoldDefaults.contentWindowInsets.only(
                WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
            ),
            topBar = {
                LibraryTopAppBar(
                    openedGroupTitle = effectiveDetailGroup?.name,
                    backTargetLabel = if (isArtistAlbumDetailOpen) {
                        openedGroup.name
                    } else {
                        selectedTab.label
                    },
                    statusText = statusText,
                    statusDotColor = statusDotColor,
                    isBitPerfectActive = isBitPerfectActive,
                    onCloseGroup = navigateDetailBack,
                    onImportFolder = launchImportFolder,
                    onOpenQueue = onOpenQueue,
                    onOpenDiagnostics = onOpenDiagnostics,
                )
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                if (libraryState.isScanning) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    )
                }

                if (!hasAudioPermission || !hasNotificationPermission) {
                    PermissionRequestBanner(
                        needsAudioPermission = !hasAudioPermission,
                        needsNotificationPermission = !hasNotificationPermission,
                        onRequestPermissions = {
                            val toRequest = buildList {
                                if (!hasAudioPermission) add(audioPermission)
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission) {
                                    add(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }.toTypedArray()
                            if (toRequest.isNotEmpty()) permissionLauncher.launch(toRequest)
                        },
                        onImportFolderInstead = launchImportFolder,
                    )
                }

                if (effectiveDetailGroup == null) {
                    LibraryTabRow(
                        selectedTab = tabRowSelectedTab,
                        onSelectTab = { tab ->
                            openedGroupName = null
                            openedArtistAlbumName = null
                            if (pagerState.currentPage != tab.ordinal || pagerState.currentPageOffsetFraction != 0f) {
                                pendingTapTabIndex = tab.ordinal
                                coroutineScope.launch {
                                    try {
                                        pagerState.animateScrollToPage(tab.ordinal)
                                    } finally {
                                        if (pendingTapTabIndex == tab.ordinal) {
                                            pendingTapTabIndex = null
                                        }
                                    }
                                }
                            }
                        },
                    )
                }

                if (effectiveDetailGroup != null && effectiveDetailContext != null && effectiveDetailSortScope != null) {
                    GroupDetailTrackList(
                        group = effectiveDetailGroup,
                        groupContext = effectiveDetailContext,
                        sortLabel = sortPrefs.specFor(effectiveDetailSortScope).compactLabel,
                        scrollResetKey = Pair(effectiveDetailSortScope, sortPrefs.specFor(effectiveDetailSortScope)),
                        showTrackNumber = isAnyAlbumDetailOpen,
                        activeTrackUri = playbackState.activeTrack?.uriString,
                        favoriteTrackUris = favoriteTrackUris,
                        favoriteFingerprints = libraryState.favoriteFingerprints,
                        albumArtworkLoader = albumArtworkLoader,
                        onOpenSort = { activeTrackSortSheet = effectiveDetailSortScope },
                        onPlayTracks = { tracks, index -> onPlayTracks(tracks, index, effectiveDetailContext) },
                        onShuffleTracks = { tracks -> onShuffleTracks(tracks, effectiveDetailContext) },
                        onEnqueueAllNext = { onEnqueueTracksNext(effectiveDetailGroup.tracks, effectiveDetailContext) },
                        onEnqueueAll = { onEnqueueTracks(effectiveDetailGroup.tracks, effectiveDetailContext) },
                        onEnqueueTrack = onEnqueueTrack,
                        onEnqueueNext = onEnqueueNext,
                        onAddToPlaylist = onAddToPlaylist,
                        onShowTrackInfo = onShowTrackInfo,
                        onToggleFavorite = onToggleFavorite,
                    )
                } else {
                    HorizontalPager(
                        state = pagerState,
                        key = { page -> LibraryTab.fromPageIndex(page).name },
                        verticalAlignment = Alignment.Top,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .testTag(LIBRARY_SUBTAB_PAGER_TEST_TAG),
                    ) { page ->
                        LibraryPagerPageContent(
                            tab = LibraryTab.fromPageIndex(page),
                            libraryState = libraryState,
                            searchQuery = searchQuery,
                            onSearchQueryChange = onSearchQueryChange,
                            favoriteTrackUris = favoriteTrackUris,
                            activeTrackUri = playbackState.activeTrack?.uriString,
                            albumArtworkLoader = albumArtworkLoader,
                            artistPhotoStore = artistPhotoStore,
                            artistPhotoRevision = artistPhotoRevision,
                            allSongsListState = allSongsListState,
                            foldersListState = foldersListState,
                            artistsListState = artistsListState,
                            albumsListState = albumsListState,
                            recentListState = recentListState,
                            favoritesListState = favoritesListState,
                            onRefreshLibrary = onRefreshLibrary,
                            onImportFolder = launchImportFolder,
                            onOpenTrackSort = { activeTrackSortSheet = it },
                            onOpenGroupSort = { activeGroupSortSheet = it },
                            onSelectGroup = onSelectGroupForTab,
                            onPlayTracks = onPlayTracks,
                            onEnqueueTrack = onEnqueueTrack,
                            onEnqueueNext = onEnqueueNext,
                            onAddToPlaylist = onAddToPlaylist,
                            onShowTrackInfo = onShowTrackInfo,
                            onToggleFavorite = onToggleFavorite,
                            onToggleFavoriteFromFavorites = onToggleFavoriteFromFavorites,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryPagerPageContent(
    tab: LibraryTab,
    libraryState: LibrarySnapshot,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    favoriteTrackUris: Set<String>,
    activeTrackUri: String?,
    albumArtworkLoader: AlbumArtworkLoader,
    artistPhotoStore: ArtistPhotoStore?,
    artistPhotoRevision: Int,
    allSongsListState: LazyListState,
    foldersListState: LazyListState,
    artistsListState: LazyListState,
    albumsListState: LazyListState,
    recentListState: LazyListState,
    favoritesListState: LazyListState,
    onRefreshLibrary: () -> Unit,
    onImportFolder: () -> Unit,
    onOpenTrackSort: (TrackSortScope) -> Unit,
    onOpenGroupSort: (GroupSortScope) -> Unit,
    onSelectGroup: (LibraryTab, LibraryGroup) -> Unit,
    onPlayTracks: (List<PlaybackTrack>, Int, QueueContext) -> Unit,
    onEnqueueTrack: (PlaybackTrack) -> Unit,
    onEnqueueNext: (PlaybackTrack) -> Unit,
    onAddToPlaylist: (PlaybackTrack) -> Unit,
    onShowTrackInfo: (PlaybackTrack) -> Unit,
    onToggleFavorite: (PlaybackTrack) -> Unit,
    onToggleFavoriteFromFavorites: ((PlaybackTrack) -> Unit)? = null,
) {
    val sortPrefs = libraryState.sortPreferences
    when (tab) {
        LibraryTab.ALL_SONGS -> {
            val filtered = remember(libraryState.allSongs, searchQuery) {
                LibraryAndLyricsCoordinator.filterTracks(libraryState.allSongs, searchQuery)
            }
            Column(modifier = Modifier.fillMaxSize()) {
                CompactSearchBarRow(
                    query = searchQuery,
                    shownCount = filtered.size,
                    totalCount = libraryState.allSongs.size,
                    sortLabel = sortPrefs.allSongs.compactLabel,
                    onQueryChange = onSearchQueryChange,
                    onRefresh = onRefreshLibrary,
                    onOpenSort = { onOpenTrackSort(TrackSortScope.ALL_SONGS) },
                )
                TrackListView(
                    tracks = filtered,
                    scrollResetKey = Pair(sortPrefs.allSongs, searchQuery.trim()),
                    albumArtworkLoader = albumArtworkLoader,
                    emptyTitle = if (searchQuery.isNotBlank()) "No matching tracks" else "No local audio tracks",
                    emptySubtitle = if (searchQuery.isNotBlank()) {
                        "Try a different title, artist, album, or filename."
                    } else {
                        "Grant local audio access or import a music folder via SAF."
                    },
                    activeTrackUri = activeTrackUri,
                    favoriteTrackUris = favoriteTrackUris,
                    favoriteFingerprints = libraryState.favoriteFingerprints,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    listState = allSongsListState,
                    onPlayTracks = { displayedTracks, displayedIndex ->
                        val tappedTrack = displayedTracks.getOrNull(displayedIndex)
                        if (tappedTrack != null && searchQuery.isNotBlank()) {
                            val (fullQueue, fullIndex) = QueueContextResolver.resolveFullListPlaybackSelection(
                                fullTrackList = libraryState.allSongs,
                                tappedTrack = tappedTrack,
                                fallbackTracks = displayedTracks,
                                fallbackIndex = displayedIndex,
                            )
                            onPlayTracks(fullQueue, fullIndex, QueueContext.allSongs())
                        } else {
                            onPlayTracks(displayedTracks, displayedIndex, QueueContext.allSongs())
                        }
                    },
                    onEnqueueTrack = onEnqueueTrack,
                    onEnqueueNext = onEnqueueNext,
                    onAddToPlaylist = onAddToPlaylist,
                    onShowTrackInfo = onShowTrackInfo,
                    onToggleFavorite = onToggleFavorite,
                    onImportFolder = if (searchQuery.isBlank()) onImportFolder else null,
                )
            }
        }
        LibraryTab.FOLDERS -> FolderGroupListView(
            groups = libraryState.folders,
            scrollResetKey = sortPrefs.foldersList,
            sortLabel = sortPrefs.foldersList.compactLabel,
            albumArtworkLoader = albumArtworkLoader,
            listState = foldersListState,
            onOpenSort = { onOpenGroupSort(GroupSortScope.FOLDERS_LIST) },
            onSelectGroup = { group -> onSelectGroup(LibraryTab.FOLDERS, group) },
            onPlayGroup = { group -> onPlayTracks(group.tracks, 0, QueueContext.folder(group.name)) },
        )
        LibraryTab.ARTISTS -> ArtistGroupListView(
            groups = libraryState.artists,
            scrollResetKey = sortPrefs.artistsList,
            sortLabel = sortPrefs.artistsList.compactLabel,
            albumTrackSortSpec = sortPrefs.albumDetail,
            albumArtworkLoader = albumArtworkLoader,
            artistPhotoStore = artistPhotoStore,
            artistPhotoRevision = artistPhotoRevision,
            listState = artistsListState,
            onOpenSort = { onOpenGroupSort(GroupSortScope.ARTISTS_LIST) },
            onSelectGroup = { group -> onSelectGroup(LibraryTab.ARTISTS, group) },
            onPlayGroup = { group -> onPlayTracks(group.tracks, 0, QueueContext.artist(group.name)) },
        )
        LibraryTab.ALBUMS -> GroupListView(
            groups = libraryState.albums,
            scrollResetKey = sortPrefs.albumsList,
            singularUnit = "album",
            pluralUnit = "albums",
            sortLabel = sortPrefs.albumsList.compactLabel,
            onOpenSort = { onOpenGroupSort(GroupSortScope.ALBUMS_LIST) },
            icon = Icons.Filled.Album,
            showArtworkThumbnail = true,
            albumArtworkLoader = albumArtworkLoader,
            emptyMessage = "No albums found in local library.",
            listState = albumsListState,
            onSelectGroup = { group -> onSelectGroup(LibraryTab.ALBUMS, group) },
            onPlayGroup = { group -> onPlayTracks(group.tracks, 0, QueueContext.album(group.name)) },
        )
        LibraryTab.RECENT -> Column(modifier = Modifier.fillMaxSize()) {
            if (libraryState.recentlyPlayed.isNotEmpty()) {
                val recentCount = libraryState.recentlyPlayed.size
                SubtleListSortHeaderRow(
                    countLabel = "$recentCount ${if (recentCount == 1) "track" else "tracks"}",
                    sortLabel = sortPrefs.recent.compactLabel,
                    onOpenSort = { onOpenTrackSort(TrackSortScope.RECENT) },
                )
            }
            TrackListView(
                tracks = libraryState.recentlyPlayed,
                scrollResetKey = sortPrefs.recent,
                albumArtworkLoader = albumArtworkLoader,
                emptyTitle = "No recently played tracks",
                emptySubtitle = "Tracks you play appear here locally.",
                activeTrackUri = activeTrackUri,
                favoriteTrackUris = favoriteTrackUris,
                favoriteFingerprints = libraryState.favoriteFingerprints,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                listState = recentListState,
                onPlayTracks = { tracks, index -> onPlayTracks(tracks, index, QueueContext.recent()) },
                onEnqueueTrack = onEnqueueTrack,
                onEnqueueNext = onEnqueueNext,
                onAddToPlaylist = onAddToPlaylist,
                onShowTrackInfo = onShowTrackInfo,
                onToggleFavorite = onToggleFavorite,
                onImportFolder = null,
            )
        }
        LibraryTab.FAVORITES -> Column(modifier = Modifier.fillMaxSize()) {
            if (libraryState.favorites.isNotEmpty()) {
                val favCount = libraryState.favorites.size
                SubtleListSortHeaderRow(
                    countLabel = "$favCount ${if (favCount == 1) "track" else "tracks"}",
                    sortLabel = sortPrefs.favorites.compactLabel,
                    onOpenSort = { onOpenTrackSort(TrackSortScope.FAVORITES) },
                )
            }
            TrackListView(
                tracks = libraryState.favorites,
                scrollResetKey = sortPrefs.favorites,
                albumArtworkLoader = albumArtworkLoader,
                emptyTitle = "No favorite tracks yet",
                emptySubtitle = "Tap the heart icon on any track to save it locally.",
                activeTrackUri = activeTrackUri,
                favoriteTrackUris = favoriteTrackUris,
                favoriteFingerprints = libraryState.favoriteFingerprints,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                listState = favoritesListState,
                onPlayTracks = { tracks, index -> onPlayTracks(tracks, index, QueueContext.favorites()) },
                onEnqueueTrack = onEnqueueTrack,
                onEnqueueNext = onEnqueueNext,
                onAddToPlaylist = onAddToPlaylist,
                onShowTrackInfo = onShowTrackInfo,
                onToggleFavorite = onToggleFavoriteFromFavorites ?: onToggleFavorite,
                onImportFolder = null,
            )
        }
    }
}

/**
 * Album Dynamic Theme scope:
 * - When [onContextualDynamicThemeChanged] is supplied (main shell), pushes resolved album tokens
 *   to root `BitPerfectPlayerTheme` so bottom nav, rail, MiniPlayer, TopAppBar, and content all
 *   adapt seamlessly and revert when leaving or switching tabs.
 * - Otherwise (standalone preview/tests), wraps the opened Album Detail subtree locally.
 */
@Composable
private fun AlbumDetailDynamicThemeWrapper(
    enabled: Boolean,
    dynamicThemeIntensity: com.cangkir.bitperfectplayer.ui.theme.DynamicThemeIntensity,
    isForegroundPage: Boolean,
    manageHostContextualTheme: Boolean,
    candidates: List<PlaybackTrack>,
    coverThemeColorCache: CoverThemeColorCache?,
    onContextualDynamicThemeChanged: ((DynamicThemeTokens?) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val parentTokens = LocalActiveDynamicTokens.current
    val latestOnThemeChanged by rememberUpdatedState(onContextualDynamicThemeChanged)
    val cacheKeys = remember(candidates, coverThemeColorCache) {
        if (coverThemeColorCache == null) emptyList() else candidates.map { coverThemeColorCache.cacheKeyFor(it) }
    }
    var resolvedPair by remember(enabled, dynamicThemeIntensity, cacheKeys) {
        val peeked = if (enabled && coverThemeColorCache != null && candidates.isNotEmpty()) {
            val allCached = candidates.all {
                coverThemeColorCache.peekCached(it, intensity = dynamicThemeIntensity) is CoverThemeLookup.Resolved
            }
            val firstChromatic = candidates.firstNotNullOfOrNull {
                (coverThemeColorCache.peekCached(it, intensity = dynamicThemeIntensity) as? CoverThemeLookup.Resolved)?.tokens
            }
            if (firstChromatic != null) {
                CoverThemeLookup.Resolved(firstChromatic)
            } else if (allCached) {
                CoverThemeLookup.Resolved(null)
            } else {
                CoverThemeLookup.Pending
            }
        } else {
            CoverThemeLookup.Resolved(null)
        }
        mutableStateOf(peeked)
    }

    val activeCache = coverThemeColorCache?.takeIf { enabled && candidates.isNotEmpty() }
    LaunchedEffect(activeCache, dynamicThemeIntensity, isForegroundPage, cacheKeys) {
        if (activeCache != null && resolvedPair is CoverThemeLookup.Pending) {
            resolvedPair = CoverThemeLookup.Resolved(
                activeCache.resolveFirstChromaticTokens(
                    candidates = candidates,
                    intensity = dynamicThemeIntensity,
                ),
            )
        }
    }

    val targetTokens = if (!enabled) {
        null
    } else {
        when (val cur = resolvedPair) {
            CoverThemeLookup.Pending -> parentTokens
            is CoverThemeLookup.Resolved -> cur.tokens
        }
    }

    if (onContextualDynamicThemeChanged != null) {
        LaunchedEffect(manageHostContextualTheme, isForegroundPage, enabled, targetTokens) {
            if (manageHostContextualTheme) {
                latestOnThemeChanged?.invoke(if (isForegroundPage && enabled) targetTokens else null)
            }
        }
        DisposableEffect(Unit) {
            onDispose { latestOnThemeChanged?.invoke(null) }
        }
        content()
        return
    }

    if (!enabled || candidates.isEmpty() || coverThemeColorCache == null) {
        content()
        return
    }

    BitPerfectPlayerTheme(
        darkTheme = true,
        dynamicColor = false,
        dynamicTokens = targetTokens,
        content = content,
    )
}
