package com.cangkir.bitperfectplayer.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.cangkir.bitperfectplayer.audio.AudioOutputMode
import com.cangkir.bitperfectplayer.audio.BrowsingQueueContext
import com.cangkir.bitperfectplayer.audio.PlaybackControllerState
import com.cangkir.bitperfectplayer.audio.PlaybackTrack
import com.cangkir.bitperfectplayer.audio.QueueContext
import com.cangkir.bitperfectplayer.library.AlbumArtworkLoader
import com.cangkir.bitperfectplayer.library.GroupSortScope
import com.cangkir.bitperfectplayer.library.GroupSortSpec
import com.cangkir.bitperfectplayer.library.LibraryGroup
import com.cangkir.bitperfectplayer.library.LibrarySnapshot
import com.cangkir.bitperfectplayer.library.TrackSortScope
import com.cangkir.bitperfectplayer.library.TrackSortSpec
import com.cangkir.bitperfectplayer.ui.theme.AmberIndicated
import com.cangkir.bitperfectplayer.ui.theme.EmeraldSafe
import com.cangkir.bitperfectplayer.ui.theme.IcePrimary
import com.cangkir.bitperfectplayer.ui.theme.TextMuted

enum class LibraryTab(val label: String) {
    ALL_SONGS("All Songs"),
    FOLDERS("Folders"),
    ARTISTS("Artists"),
    ALBUMS("Albums"),
    RECENT("Recent"),
    FAVORITES("Favorites"),
}

@OptIn(ExperimentalMaterial3Api::class)
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
    onOpenQueue: (BrowsingQueueContext?) -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    val context = LocalContext.current
    var selectedTab by rememberSaveable { mutableStateOf(LibraryTab.ALL_SONGS) }
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
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, audioPermission) == PackageManager.PERMISSION_GRANTED,
        )
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
        if (hasAudioPermission) {
            onRefreshLibrary()
        }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri ->
        if (treeUri != null) {
            onImportFolder(treeUri)
        }
    }

    val activeGroups = when (selectedTab) {
        LibraryTab.FOLDERS -> libraryState.folders
        LibraryTab.ARTISTS -> libraryState.artists
        LibraryTab.ALBUMS -> libraryState.albums
        else -> emptyList()
    }
    val openedGroup = openedGroupName?.let { name ->
        activeGroups.firstOrNull { it.name == name }
    }
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

    val currentBrowsingContext: BrowsingQueueContext? = remember(
        openedGroup,
        openedGroupContext,
        selectedTab,
        libraryState.recentlyPlayed,
        libraryState.favorites,
    ) {
        when {
            openedGroup != null && openedGroupContext != null && openedGroup.tracks.isNotEmpty() ->
                BrowsingQueueContext(context = openedGroupContext, tracks = openedGroup.tracks)
            selectedTab == LibraryTab.RECENT && libraryState.recentlyPlayed.isNotEmpty() ->
                BrowsingQueueContext(context = QueueContext.recent(), tracks = libraryState.recentlyPlayed)
            selectedTab == LibraryTab.FAVORITES && libraryState.favorites.isNotEmpty() ->
                BrowsingQueueContext(context = QueueContext.favorites(), tracks = libraryState.favorites)
            else -> null
        }
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
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = openedGroup?.name ?: "BitPerfectPlayer",
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(statusDotColor),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    if (openedGroup != null) {
                        IconButton(
                            onClick = { openedGroupName = null },
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back to ${selectedTab.label}",
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = launchImportFolder,
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CreateNewFolder,
                            contentDescription = "Import music folder via SAF",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = { onOpenQueue(currentBrowsingContext) },
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                            contentDescription = "Open playback queue",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = onOpenDiagnostics,
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.GraphicEq,
                            contentDescription = "Open Audio Diagnostics Panel",
                            tint = if (isBitPerfectActive) IcePrimary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
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
                        if (toRequest.isNotEmpty()) {
                            permissionLauncher.launch(toRequest)
                        }
                    },
                    onImportFolderInstead = launchImportFolder,
                )
            }

            if (openedGroup == null) {
                PrimaryScrollableTabRow(
                    selectedTabIndex = selectedTab.ordinal,
                    containerColor = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    edgePadding = 16.dp,
                    divider = {
                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                        )
                    },
                ) {
                    LibraryTab.entries.forEach { tab ->
                        val selected = selectedTab == tab
                        Tab(
                            selected = selected,
                            onClick = {
                                selectedTab = tab
                                openedGroupName = null
                            },
                            text = {
                                Text(
                                    text = tab.label,
                                    maxLines = 1,
                                    softWrap = false,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            },
                        )
                    }
                }
            }

            if (openedGroup != null && openedGroupContext != null && openedGroupSortScope != null) {
                GroupDetailTrackList(
                    group = openedGroup,
                    groupContext = openedGroupContext,
                    sortLabel = sortPrefs.specFor(openedGroupSortScope).compactLabel,
                    showTrackNumber = selectedTab == LibraryTab.ALBUMS,
                    activeTrackUri = playbackState.activeTrack?.uriString,
                    favoriteTrackUris = favoriteTrackUris,
                    favoriteFingerprints = libraryState.favoriteFingerprints,
                    albumArtworkLoader = albumArtworkLoader,
                    onOpenSort = { activeTrackSortSheet = openedGroupSortScope },
                    onPlayTracks = { tracks, index ->
                        onPlayTracks(tracks, index, openedGroupContext)
                    },
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
                            onPlayTracks = { tracks, index ->
                                onPlayTracks(tracks, index, QueueContext.allSongs())
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
                        singularUnit = "folder",
                        pluralUnit = "folders",
                        sortLabel = sortPrefs.foldersList.compactLabel,
                        onOpenSort = { activeGroupSortSheet = GroupSortScope.FOLDERS_LIST },
                        icon = Icons.Filled.Folder,
                        showArtworkThumbnail = false,
                        albumArtworkLoader = albumArtworkLoader,
                        emptyMessage = "No music folders scanned yet.",
                        onSelectGroup = onSelectGroup,
                        onPlayGroup = { group ->
                            onPlayTracks(group.tracks, 0, QueueContext.folder(group.name))
                        },
                    )
                    LibraryTab.ARTISTS -> GroupListView(
                        groups = libraryState.artists,
                        singularUnit = "artist",
                        pluralUnit = "artists",
                        sortLabel = sortPrefs.artistsList.compactLabel,
                        onOpenSort = { activeGroupSortSheet = GroupSortScope.ARTISTS_LIST },
                        icon = Icons.Filled.Person,
                        showArtworkThumbnail = false,
                        albumArtworkLoader = albumArtworkLoader,
                        emptyMessage = "No artists found in local library.",
                        onSelectGroup = onSelectGroup,
                        onPlayGroup = { group ->
                            onPlayTracks(group.tracks, 0, QueueContext.artist(group.name))
                        },
                    )
                    LibraryTab.ALBUMS -> GroupListView(
                        groups = libraryState.albums,
                        singularUnit = "album",
                        pluralUnit = "albums",
                        sortLabel = sortPrefs.albumsList.compactLabel,
                        onOpenSort = { activeGroupSortSheet = GroupSortScope.ALBUMS_LIST },
                        icon = Icons.Filled.Album,
                        showArtworkThumbnail = true,
                        albumArtworkLoader = albumArtworkLoader,
                        emptyMessage = "No albums found in local library.",
                        onSelectGroup = onSelectGroup,
                        onPlayGroup = { group ->
                            onPlayTracks(group.tracks, 0, QueueContext.album(group.name))
                        },
                    )
                    LibraryTab.RECENT -> {
                        if (libraryState.recentlyPlayed.isNotEmpty()) {
                            SubtleListSortHeaderRow(
                                countLabel = "${libraryState.recentlyPlayed.size} ${if (libraryState.recentlyPlayed.size == 1) "track" else "tracks"}",
                                sortLabel = sortPrefs.recent.compactLabel,
                                onOpenSort = { activeTrackSortSheet = TrackSortScope.RECENT },
                            )
                        }
                        TrackListView(
                            tracks = libraryState.recentlyPlayed,
                            albumArtworkLoader = albumArtworkLoader,
                            emptyTitle = "No recently played tracks",
                            emptySubtitle = "Tracks you play appear here locally.",
                            activeTrackUri = playbackState.activeTrack?.uriString,
                            favoriteTrackUris = favoriteTrackUris,
                            favoriteFingerprints = libraryState.favoriteFingerprints,
                            onPlayTracks = { tracks, index ->
                                onPlayTracks(tracks, index, QueueContext.recent())
                            },
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
                            SubtleListSortHeaderRow(
                                countLabel = "${libraryState.favorites.size} ${if (libraryState.favorites.size == 1) "track" else "tracks"}",
                                sortLabel = sortPrefs.favorites.compactLabel,
                                onOpenSort = { activeTrackSortSheet = TrackSortScope.FAVORITES },
                            )
                        }
                        TrackListView(
                            tracks = libraryState.favorites,
                            albumArtworkLoader = albumArtworkLoader,
                            emptyTitle = "No favorite tracks yet",
                            emptySubtitle = "Tap the heart icon on any track to save it locally.",
                            activeTrackUri = playbackState.activeTrack?.uriString,
                            favoriteTrackUris = favoriteTrackUris,
                            favoriteFingerprints = libraryState.favoriteFingerprints,
                            onPlayTracks = { tracks, index ->
                                onPlayTracks(tracks, index, QueueContext.favorites())
                            },
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

@Composable
private fun SubtleListSortHeaderRow(
    countLabel: String,
    sortLabel: String,
    onOpenSort: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = countLabel,
            style = MaterialTheme.typography.labelMedium,
            color = TextMuted,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        InlineSortTriggerButton(
            label = sortLabel,
            onClick = onOpenSort,
        )
    }
    HorizontalDivider(
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
    )
}

@Composable
private fun PermissionRequestBanner(
    needsAudioPermission: Boolean,
    needsNotificationPermission: Boolean,
    onRequestPermissions: () -> Unit,
    onImportFolderInstead: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = if (needsAudioPermission) {
                    "Local Audio Access Needed"
                } else {
                    "Enable Media Controls Notification"
                },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = buildString {
                    if (needsAudioPermission) {
                        append("Allow local audio access to scan your library, or pick folders via SAF. ")
                    }
                    if (needsNotificationPermission) {
                        append("Allow notifications for lock-screen playback controls.")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onRequestPermissions,
                    modifier = Modifier.sizeIn(minHeight = 48.dp),
                ) {
                    Text("Grant Access")
                }
                if (needsAudioPermission) {
                    OutlinedButton(
                        onClick = onImportFolderInstead,
                        modifier = Modifier.sizeIn(minHeight = 48.dp),
                    ) {
                        Text("Pick Folder")
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactSearchBarRow(
    query: String,
    shownCount: Int,
    totalCount: Int,
    sortLabel: String,
    onQueryChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onOpenSort: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 12.dp, end = if (query.isNotEmpty()) 0.dp else 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (query.isEmpty()) {
                            Text(
                                text = "Search tracks, artists, albums",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextMuted,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        BasicTextField(
                            value = query,
                            onValueChange = onQueryChange,
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (query.isNotEmpty()) {
                        IconButton(
                            onClick = { onQueryChange("") },
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Clear search",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.width(4.dp))
            IconButton(
                onClick = onRefresh,
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = "Refresh local library",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val countLabel = if (query.isBlank()) {
            "$totalCount ${if (totalCount == 1) "track" else "tracks"}"
        } else {
            "$shownCount of $totalCount ${if (totalCount == 1) "track" else "tracks"}"
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = countLabel,
                style = MaterialTheme.typography.labelMedium,
                color = TextMuted,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 2.dp),
            )
            InlineSortTriggerButton(
                label = sortLabel,
                onClick = onOpenSort,
            )
        }
    }
}

@Composable
private fun GroupListView(
    groups: List<LibraryGroup>,
    singularUnit: String,
    pluralUnit: String,
    sortLabel: String,
    onOpenSort: () -> Unit,
    icon: ImageVector,
    showArtworkThumbnail: Boolean,
    albumArtworkLoader: AlbumArtworkLoader,
    emptyMessage: String,
    onSelectGroup: (LibraryGroup) -> Unit,
    onPlayGroup: (LibraryGroup) -> Unit,
) {
    if (groups.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = emptyMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        SubtleListSortHeaderRow(
            countLabel = "${groups.size} ${if (groups.size == 1) singularUnit else pluralUnit}",
            sortLabel = sortLabel,
            onOpenSort = onOpenSort,
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            items(
                items = groups,
                key = { it.name },
                contentType = { "group_row" },
            ) { group ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectGroup(group) }
                        .padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (showArtworkThumbnail) {
                        TrackArtworkThumbnail(
                            track = group.tracks.firstOrNull(),
                            artworkLoader = albumArtworkLoader,
                            size = 48.dp,
                            cornerRadius = 8.dp,
                            placeholderIcon = icon,
                            contentDescriptionText = group.name,
                        )
                    } else {
                        Surface(
                            modifier = Modifier.size(44.dp),
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = group.name,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = group.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(
                        onClick = { onPlayGroup(group) },
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = "Play all tracks in ${group.name}",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                    modifier = Modifier.padding(start = 76.dp, end = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun GroupDetailTrackList(
    group: LibraryGroup,
    groupContext: QueueContext,
    sortLabel: String,
    showTrackNumber: Boolean,
    activeTrackUri: String?,
    favoriteTrackUris: Set<String>,
    favoriteFingerprints: Set<String>,
    albumArtworkLoader: AlbumArtworkLoader,
    onOpenSort: () -> Unit,
    onPlayTracks: (List<PlaybackTrack>, Int) -> Unit,
    onEnqueueAllNext: () -> Unit,
    onEnqueueAll: () -> Unit,
    onEnqueueTrack: (PlaybackTrack) -> Unit,
    onEnqueueNext: (PlaybackTrack) -> Unit,
    onAddToPlaylist: (PlaybackTrack) -> Unit,
    onShowTrackInfo: (PlaybackTrack) -> Unit,
    onToggleFavorite: (PlaybackTrack) -> Unit,
) {
    var groupMenuExpanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 6.dp, top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = group.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            InlineSortTriggerButton(
                label = sortLabel,
                onClick = onOpenSort,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 6.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalButton(
                onClick = { onPlayTracks(group.tracks, 0) },
                enabled = group.tracks.isNotEmpty(),
                modifier = Modifier
                    .weight(1f)
                    .sizeIn(minHeight = 48.dp),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Play All", maxLines = 1, softWrap = false)
            }
            Box {
                IconButton(
                    onClick = { groupMenuExpanded = true },
                    enabled = group.tracks.isNotEmpty(),
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = "Queue options for ${groupContext.sourceLabel}",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (groupMenuExpanded) {
                    DropdownMenu(
                        expanded = true,
                        onDismissRequest = { groupMenuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Play Next") },
                            onClick = {
                                groupMenuExpanded = false
                                onEnqueueAllNext()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Add All to Queue") },
                            onClick = {
                                groupMenuExpanded = false
                                onEnqueueAll()
                            },
                        )
                    }
                }
            }
        }
        HorizontalDivider(
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
        )
        TrackListView(
            tracks = group.tracks,
            albumArtworkLoader = albumArtworkLoader,
            emptyTitle = "Empty group",
            emptySubtitle = "",
            activeTrackUri = activeTrackUri,
            favoriteTrackUris = favoriteTrackUris,
            favoriteFingerprints = favoriteFingerprints,
            showTrackNumber = showTrackNumber,
            onPlayTracks = onPlayTracks,
            onEnqueueTrack = onEnqueueTrack,
            onEnqueueNext = onEnqueueNext,
            onAddToPlaylist = onAddToPlaylist,
            onShowTrackInfo = onShowTrackInfo,
            onToggleFavorite = onToggleFavorite,
            onImportFolder = null,
        )
    }
}

@Composable
private fun TrackListView(
    tracks: List<PlaybackTrack>,
    albumArtworkLoader: AlbumArtworkLoader,
    emptyTitle: String,
    emptySubtitle: String,
    activeTrackUri: String?,
    favoriteTrackUris: Set<String>,
    favoriteFingerprints: Set<String>,
    showTrackNumber: Boolean = false,
    onPlayTracks: (List<PlaybackTrack>, Int) -> Unit,
    onEnqueueTrack: (PlaybackTrack) -> Unit,
    onEnqueueNext: (PlaybackTrack) -> Unit,
    onAddToPlaylist: (PlaybackTrack) -> Unit,
    onShowTrackInfo: (PlaybackTrack) -> Unit,
    onToggleFavorite: (PlaybackTrack) -> Unit,
    onImportFolder: (() -> Unit)?,
) {
    if (tracks.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Filled.MusicNote,
                contentDescription = null,
                tint = TextMuted,
                modifier = Modifier.size(40.dp),
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = emptyTitle,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            if (emptySubtitle.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = emptySubtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            if (onImportFolder != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onImportFolder,
                    modifier = Modifier.sizeIn(minHeight = 48.dp),
                ) {
                    Icon(Icons.Filled.CreateNewFolder, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Import Folder")
                }
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 12.dp),
    ) {
        itemsIndexed(
            items = tracks,
            key = { _, track -> track.uriString },
            contentType = { _, _ -> "track_row" },
        ) { index, track ->
            val isActive = track.uriString == activeTrackUri
            val isFav = track.uriString in favoriteTrackUris ||
                track.contentFingerprint?.lowercase()?.let { it in favoriteFingerprints } == true

            TrackListRow(
                track = track,
                isActive = isActive,
                isFavorite = isFav,
                showTrackNumber = showTrackNumber,
                albumArtworkLoader = albumArtworkLoader,
                onPlay = { onPlayTracks(tracks, index) },
                onToggleFavorite = onToggleFavorite,
                onEnqueueNext = onEnqueueNext,
                onEnqueueTrack = onEnqueueTrack,
                onAddToPlaylist = onAddToPlaylist,
                onShowTrackInfo = onShowTrackInfo,
            )
            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                modifier = Modifier.padding(start = 76.dp, end = 16.dp),
            )
        }
    }
}

@Composable
private fun TrackListRow(
    track: PlaybackTrack,
    isActive: Boolean,
    isFavorite: Boolean,
    showTrackNumber: Boolean,
    albumArtworkLoader: AlbumArtworkLoader,
    onPlay: () -> Unit,
    onToggleFavorite: (PlaybackTrack) -> Unit,
    onEnqueueNext: (PlaybackTrack) -> Unit,
    onEnqueueTrack: (PlaybackTrack) -> Unit,
    onAddToPlaylist: (PlaybackTrack) -> Unit,
    onShowTrackInfo: (PlaybackTrack) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isActive) {
                    MaterialTheme.colorScheme.surfaceVariant
                } else {
                    Color.Transparent
                },
            )
            .clickable(onClick = onPlay)
            .padding(start = 16.dp, end = 2.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showTrackNumber) {
            Text(
                text = track.trackNumberLabel.ifEmpty { "·" },
                style = MaterialTheme.typography.labelMedium,
                color = if (isActive) MaterialTheme.colorScheme.primary else TextMuted,
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.width(28.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
        }

        TrackArtworkThumbnail(
            track = track,
            artworkLoader = albumArtworkLoader,
            size = 48.dp,
            cornerRadius = 8.dp,
            targetSizePx = AlbumArtworkLoader.THUMBNAIL_SIZE_PX,
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (isActive) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (track.durationLabel.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = track.durationLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = track.artistAlbumLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = track.formatBadge,
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        IconButton(
            onClick = { onToggleFavorite(track) },
            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
        ) {
            Icon(
                imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = if (isFavorite) "Remove ${track.title} from favorites" else "Add ${track.title} to favorites",
                tint = if (isFavorite) MaterialTheme.colorScheme.primary else TextMuted,
                modifier = Modifier.size(20.dp),
            )
        }

        Box {
            IconButton(
                onClick = { menuExpanded = true },
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = "More actions for ${track.title}",
                    tint = TextMuted,
                    modifier = Modifier.size(20.dp),
                )
            }
            // Only compose DropdownMenu popup state when explicitly opened.
            if (menuExpanded) {
                DropdownMenu(
                    expanded = true,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Add to Playlist") },
                        onClick = {
                            menuExpanded = false
                            onAddToPlaylist(track)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Play Next") },
                        onClick = {
                            menuExpanded = false
                            onEnqueueNext(track)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Add to Queue") },
                        onClick = {
                            menuExpanded = false
                            onEnqueueTrack(track)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Track Info") },
                        onClick = {
                            menuExpanded = false
                            onShowTrackInfo(track)
                        },
                    )
                }
            }
        }
    }
}
