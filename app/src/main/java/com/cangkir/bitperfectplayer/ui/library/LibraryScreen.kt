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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.cangkir.bitperfectplayer.audio.AudioOutputMode
import com.cangkir.bitperfectplayer.audio.PlaybackControllerState
import com.cangkir.bitperfectplayer.audio.PlaybackTrack
import com.cangkir.bitperfectplayer.audio.QueueContext
import com.cangkir.bitperfectplayer.audio.QueueContextResolver
import com.cangkir.bitperfectplayer.library.AlbumArtworkLoader
import com.cangkir.bitperfectplayer.library.GroupSortScope
import com.cangkir.bitperfectplayer.library.GroupSortSpec
import com.cangkir.bitperfectplayer.library.LibraryGroup
import com.cangkir.bitperfectplayer.library.LibrarySnapshot
import com.cangkir.bitperfectplayer.library.TrackSortScope
import com.cangkir.bitperfectplayer.library.TrackSortSpec
import com.cangkir.bitperfectplayer.ui.GroupSortBottomSheet
import com.cangkir.bitperfectplayer.ui.LibraryTab
import com.cangkir.bitperfectplayer.ui.TrackSortBottomSheet
import com.cangkir.bitperfectplayer.ui.theme.AmberIndicated
import com.cangkir.bitperfectplayer.ui.theme.EmeraldSafe
import com.cangkir.bitperfectplayer.ui.theme.IcePrimary

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
    onOpenQueue: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    initialTab: LibraryTab = LibraryTab.ALL_SONGS,
) {
    val context = LocalContext.current
    var selectedTab by rememberSaveable { mutableStateOf(initialTab) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var openedGroupName by rememberSaveable { mutableStateOf<String?>(null) }

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
    val openedGroupContext: QueueContext? = remember(selectedTab, openedGroup) {
        openedGroup?.let { grp ->
            when (selectedTab) {
                LibraryTab.ALBUMS -> QueueContext.album(grp.name)
                LibraryTab.ARTISTS -> QueueContext.artist(grp.name)
                LibraryTab.FOLDERS -> QueueContext.folder(grp.name)
                else -> QueueContext.allSongs()
            }
        }
    }
    val openedGroupSortScope: TrackSortScope? = when (selectedTab) {
        LibraryTab.ALBUMS -> TrackSortScope.ALBUM_DETAIL
        LibraryTab.ARTISTS -> TrackSortScope.ARTIST_DETAIL
        LibraryTab.FOLDERS -> TrackSortScope.FOLDER_DETAIL
        else -> null
    }

    val sortPrefs = libraryState.sortPreferences

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

    val isBitPerfectActive = playbackState.engineState.diagnostics.isBitPerfectMixerActive
    val isBitPerfectOptIn = playbackState.engineState.safetyState.mode == AudioOutputMode.BIT_PERFECT_OPT_IN
    val statusText = when {
        isBitPerfectActive -> "Bit-Perfect USB · Offline"
        isBitPerfectOptIn -> "Clean Native · Offline"
        else -> "Safe Mode · Offline"
    }
    val statusDotColor = when {
        isBitPerfectActive -> IcePrimary
        isBitPerfectOptIn -> AmberIndicated
        else -> EmeraldSafe
    }

    val launchImportFolder = remember(folderPickerLauncher) { { folderPickerLauncher.launch(null) } }
    val onSelectGroup = remember { { group: LibraryGroup -> openedGroupName = group.name } }

    BackHandler(enabled = openedGroup != null) {
        openedGroupName = null
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets.only(
            WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
        ),
        topBar = {
            LibraryTopAppBar(
                openedGroupTitle = openedGroup?.name,
                selectedTabLabel = selectedTab.label,
                statusText = statusText,
                statusDotColor = statusDotColor,
                isBitPerfectActive = isBitPerfectActive,
                onCloseGroup = { openedGroupName = null },
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

            if (openedGroup == null) {
                LibraryTabRow(
                    selectedTab = selectedTab,
                    onSelectTab = { tab ->
                        selectedTab = tab
                        openedGroupName = null
                    },
                )
            }

            if (openedGroup != null && openedGroupContext != null && openedGroupSortScope != null) {
                GroupDetailTrackList(
                    group = openedGroup,
                    groupContext = openedGroupContext,
                    sortLabel = sortPrefs.specFor(openedGroupSortScope).compactLabel,
                    scrollResetKey = Pair(openedGroupSortScope, sortPrefs.specFor(openedGroupSortScope)),
                    showTrackNumber = selectedTab == LibraryTab.ALBUMS,
                    activeTrackUri = playbackState.activeTrack?.uriString,
                    favoriteTrackUris = favoriteTrackUris,
                    favoriteFingerprints = libraryState.favoriteFingerprints,
                    albumArtworkLoader = albumArtworkLoader,
                    onOpenSort = { activeTrackSortSheet = openedGroupSortScope },
                    onPlayTracks = { tracks, index -> onPlayTracks(tracks, index, openedGroupContext) },
                    onEnqueueAllNext = { onEnqueueTracksNext(openedGroup.tracks, openedGroupContext) },
                    onEnqueueAll = { onEnqueueTracks(openedGroup.tracks, openedGroupContext) },
                    onEnqueueTrack = onEnqueueTrack,
                    onEnqueueNext = onEnqueueNext,
                    onAddToPlaylist = onAddToPlaylist,
                    onShowTrackInfo = onShowTrackInfo,
                    onToggleFavorite = onToggleFavorite,
                )
            } else {
                when (selectedTab) {
                    LibraryTab.ALL_SONGS -> {
                        val filtered = remember(libraryState.allSongs, searchQuery) {
                            val q = searchQuery.trim()
                            if (q.isEmpty()) {
                                libraryState.allSongs
                            } else {
                                libraryState.allSongs.filter {
                                    it.title.contains(q, ignoreCase = true) ||
                                        it.artist.contains(q, ignoreCase = true) ||
                                        it.album.contains(q, ignoreCase = true) ||
                                        it.fileName.contains(q, ignoreCase = true)
                                }
                            }
                        }
                        CompactSearchBarRow(
                            query = searchQuery,
                            shownCount = filtered.size,
                            totalCount = libraryState.allSongs.size,
                            sortLabel = sortPrefs.allSongs.compactLabel,
                            onQueryChange = { searchQuery = it },
                            onRefresh = onRefreshLibrary,
                            onOpenSort = { activeTrackSortSheet = TrackSortScope.ALL_SONGS },
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
                            activeTrackUri = playbackState.activeTrack?.uriString,
                            favoriteTrackUris = favoriteTrackUris,
                            favoriteFingerprints = libraryState.favoriteFingerprints,
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
                            onImportFolder = if (searchQuery.isBlank()) launchImportFolder else null,
                        )
                    }
                    LibraryTab.FOLDERS -> GroupListView(
                        groups = libraryState.folders,
                        scrollResetKey = sortPrefs.foldersList,
                        singularUnit = "folder",
                        pluralUnit = "folders",
                        sortLabel = sortPrefs.foldersList.compactLabel,
                        onOpenSort = { activeGroupSortSheet = GroupSortScope.FOLDERS_LIST },
                        icon = Icons.Filled.Folder,
                        showArtworkThumbnail = false,
                        albumArtworkLoader = albumArtworkLoader,
                        emptyMessage = "No music folders scanned yet.",
                        onSelectGroup = onSelectGroup,
                        onPlayGroup = { group -> onPlayTracks(group.tracks, 0, QueueContext.folder(group.name)) },
                    )
                    LibraryTab.ARTISTS -> GroupListView(
                        groups = libraryState.artists,
                        scrollResetKey = sortPrefs.artistsList,
                        singularUnit = "artist",
                        pluralUnit = "artists",
                        sortLabel = sortPrefs.artistsList.compactLabel,
                        onOpenSort = { activeGroupSortSheet = GroupSortScope.ARTISTS_LIST },
                        icon = Icons.Filled.Person,
                        showArtworkThumbnail = false,
                        albumArtworkLoader = albumArtworkLoader,
                        emptyMessage = "No artists found in local library.",
                        onSelectGroup = onSelectGroup,
                        onPlayGroup = { group -> onPlayTracks(group.tracks, 0, QueueContext.artist(group.name)) },
                    )
                    LibraryTab.ALBUMS -> GroupListView(
                        groups = libraryState.albums,
                        scrollResetKey = sortPrefs.albumsList,
                        singularUnit = "album",
                        pluralUnit = "albums",
                        sortLabel = sortPrefs.albumsList.compactLabel,
                        onOpenSort = { activeGroupSortSheet = GroupSortScope.ALBUMS_LIST },
                        icon = Icons.Filled.Album,
                        showArtworkThumbnail = true,
                        albumArtworkLoader = albumArtworkLoader,
                        emptyMessage = "No albums found in local library.",
                        onSelectGroup = onSelectGroup,
                        onPlayGroup = { group -> onPlayTracks(group.tracks, 0, QueueContext.album(group.name)) },
                    )
                    LibraryTab.RECENT -> {
                        if (libraryState.recentlyPlayed.isNotEmpty()) {
                            val recentCount = libraryState.recentlyPlayed.size
                            SubtleListSortHeaderRow(
                                countLabel = "$recentCount ${if (recentCount == 1) "track" else "tracks"}",
                                sortLabel = sortPrefs.recent.compactLabel,
                                onOpenSort = { activeTrackSortSheet = TrackSortScope.RECENT },
                            )
                        }
                        TrackListView(
                            tracks = libraryState.recentlyPlayed,
                            scrollResetKey = sortPrefs.recent,
                            albumArtworkLoader = albumArtworkLoader,
                            emptyTitle = "No recently played tracks",
                            emptySubtitle = "Tracks you play appear here locally.",
                            activeTrackUri = playbackState.activeTrack?.uriString,
                            favoriteTrackUris = favoriteTrackUris,
                            favoriteFingerprints = libraryState.favoriteFingerprints,
                            onPlayTracks = { tracks, index -> onPlayTracks(tracks, index, QueueContext.recent()) },
                            onEnqueueTrack = onEnqueueTrack,
                            onEnqueueNext = onEnqueueNext,
                            onAddToPlaylist = onAddToPlaylist,
                            onShowTrackInfo = onShowTrackInfo,
                            onToggleFavorite = onToggleFavorite,
                            onImportFolder = null,
                        )
                    }
                    LibraryTab.FAVORITES -> {
                        if (libraryState.favorites.isNotEmpty()) {
                            val favCount = libraryState.favorites.size
                            SubtleListSortHeaderRow(
                                countLabel = "$favCount ${if (favCount == 1) "track" else "tracks"}",
                                sortLabel = sortPrefs.favorites.compactLabel,
                                onOpenSort = { activeTrackSortSheet = TrackSortScope.FAVORITES },
                            )
                        }
                        TrackListView(
                            tracks = libraryState.favorites,
                            scrollResetKey = sortPrefs.favorites,
                            albumArtworkLoader = albumArtworkLoader,
                            emptyTitle = "No favorite tracks yet",
                            emptySubtitle = "Tap the heart icon on any track to save it locally.",
                            activeTrackUri = playbackState.activeTrack?.uriString,
                            favoriteTrackUris = favoriteTrackUris,
                            favoriteFingerprints = libraryState.favoriteFingerprints,
                            onPlayTracks = { tracks, index -> onPlayTracks(tracks, index, QueueContext.favorites()) },
                            onEnqueueTrack = onEnqueueTrack,
                            onEnqueueNext = onEnqueueNext,
                            onAddToPlaylist = onAddToPlaylist,
                            onShowTrackInfo = onShowTrackInfo,
                            onToggleFavorite = onToggleFavorite,
                            onImportFolder = null,
                        )
                    }
                }
            }
        }
    }
}
