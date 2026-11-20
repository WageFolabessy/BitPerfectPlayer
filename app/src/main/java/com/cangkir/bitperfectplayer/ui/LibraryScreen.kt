package com.cangkir.bitperfectplayer.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.AudioFile
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.cangkir.bitperfectplayer.audio.AudioOutputMode
import com.cangkir.bitperfectplayer.audio.PlaybackControllerState
import com.cangkir.bitperfectplayer.audio.PlaybackTrack
import com.cangkir.bitperfectplayer.library.LibraryGroup
import com.cangkir.bitperfectplayer.library.LibrarySnapshot
import com.cangkir.bitperfectplayer.ui.theme.AmberIndicated
import com.cangkir.bitperfectplayer.ui.theme.EmeraldSafe
import com.cangkir.bitperfectplayer.ui.theme.HiResGold
import com.cangkir.bitperfectplayer.ui.theme.HiResGoldContainer
import com.cangkir.bitperfectplayer.ui.theme.IcePrimary
import com.cangkir.bitperfectplayer.ui.theme.IcePrimaryContainer

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
    playbackState: PlaybackControllerState,
    onRefreshLibrary: () -> Unit,
    onImportFolder: (android.net.Uri) -> Unit,
    onImportFile: (android.net.Uri) -> Unit,
    onPlayTracks: (List<PlaybackTrack>, Int) -> Unit,
    onEnqueueTrack: (PlaybackTrack) -> Unit,
    onEnqueueNext: (PlaybackTrack) -> Unit,
    onToggleFavorite: (PlaybackTrack) -> Unit,
    isTrackFavorite: (PlaybackTrack) -> Boolean,
    onOpenNowPlaying: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
) {
    val context = LocalContext.current
    var selectedTab by rememberSaveable { mutableStateOf(LibraryTab.ALL_SONGS) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var openedGroupName by rememberSaveable { mutableStateOf<String?>(null) }

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

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { fileUri ->
        if (fileUri != null) {
            onImportFile(fileUri)
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

    val isBitPerfectActive = playbackState.engineState.diagnostics.isBitPerfectMixerActive
    val isBitPerfectOptIn = playbackState.engineState.safetyState.mode == AudioOutputMode.BIT_PERFECT_OPT_IN
    val statusText = when {
        isBitPerfectActive -> "Bit-Perfect USB Active"
        isBitPerfectOptIn -> "Clean Native Fallback • Offline"
        else -> "100% Offline • Safe Mode"
    }
    val statusColor = when {
        isBitPerfectActive -> IcePrimary
        isBitPerfectOptIn -> AmberIndicated
        else -> EmeraldSafe
    }

    Scaffold(
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
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor,
                        )
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
                        onClick = { folderPickerLauncher.launch(null) },
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CreateNewFolder,
                            contentDescription = "Import music folder via SAF",
                        )
                    }
                    IconButton(
                        onClick = { filePickerLauncher.launch(arrayOf("audio/*")) },
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AudioFile,
                            contentDescription = "Import audio file via SAF",
                        )
                    }
                    IconButton(
                        onClick = onOpenQueue,
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                            contentDescription = "Open playback queue",
                        )
                    }
                    IconButton(
                        onClick = onOpenDiagnostics,
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.GraphicEq,
                            contentDescription = "Open Audio Diagnostics Panel",
                            tint = statusColor,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        bottomBar = {
            MiniPlayerBar(
                playbackState = playbackState,
                onOpenNowPlaying = onOpenNowPlaying,
                onTogglePlayPause = onTogglePlayPause,
                onNext = onNext,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (libraryState.isScanning) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (!hasAudioPermission || !hasNotificationPermission) {
                PermissionRequestCard(
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
                    onImportFolderInstead = { folderPickerLauncher.launch(null) },
                )
            }

            if (openedGroup == null) {
                PrimaryScrollableTabRow(
                    selectedTabIndex = selectedTab.ordinal,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                    edgePadding = 12.dp,
                ) {
                    LibraryTab.entries.forEach { tab ->
                        Tab(
                            selected = selectedTab == tab,
                            onClick = {
                                selectedTab = tab
                                openedGroupName = null
                            },
                            text = { Text(text = tab.label, maxLines = 1) },
                        )
                    }
                }
            }

            if (openedGroup != null) {
                GroupDetailTrackList(
                    group = openedGroup,
                    activeTrackUri = playbackState.activeTrack?.uriString,
                    onPlayTracks = onPlayTracks,
                    onEnqueueTrack = onEnqueueTrack,
                    onEnqueueNext = onEnqueueNext,
                    onToggleFavorite = onToggleFavorite,
                    isTrackFavorite = isTrackFavorite,
                )
            } else {
                when (selectedTab) {
                    LibraryTab.ALL_SONGS -> {
                        SearchAndRefreshRow(
                            query = searchQuery,
                            totalCount = libraryState.allSongs.size,
                            onQueryChange = { searchQuery = it },
                            onRefresh = onRefreshLibrary,
                        )
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
                        TrackListView(
                            tracks = filtered,
                            emptyTitle = "No local music tracks found",
                            emptySubtitle = "Auto-scan filters out WhatsApp audio, voice notes, recordings & ringtones. Grant audio access or pick a folder via SAF.",
                            activeTrackUri = playbackState.activeTrack?.uriString,
                            onPlayTracks = onPlayTracks,
                            onEnqueueTrack = onEnqueueTrack,
                            onEnqueueNext = onEnqueueNext,
                            onToggleFavorite = onToggleFavorite,
                            isTrackFavorite = isTrackFavorite,
                            onImportFolder = { folderPickerLauncher.launch(null) },
                        )
                    }
                    LibraryTab.FOLDERS -> GroupListView(
                        groups = libraryState.folders,
                        icon = Icons.Filled.Folder,
                        emptyMessage = "No music folders scanned yet.",
                        onSelectGroup = { openedGroupName = it.name },
                        onPlayGroup = { onPlayTracks(it.tracks, 0) },
                    )
                    LibraryTab.ARTISTS -> GroupListView(
                        groups = libraryState.artists,
                        icon = Icons.Filled.Person,
                        emptyMessage = "No artists found in local library.",
                        onSelectGroup = { openedGroupName = it.name },
                        onPlayGroup = { onPlayTracks(it.tracks, 0) },
                    )
                    LibraryTab.ALBUMS -> GroupListView(
                        groups = libraryState.albums,
                        icon = Icons.Filled.Album,
                        emptyMessage = "No albums found in local library.",
                        onSelectGroup = { openedGroupName = it.name },
                        onPlayGroup = { onPlayTracks(it.tracks, 0) },
                    )
                    LibraryTab.RECENT -> TrackListView(
                        tracks = libraryState.recentlyPlayed,
                        emptyTitle = "No recently played tracks",
                        emptySubtitle = "Tracks you listen to will appear here locally.",
                        activeTrackUri = playbackState.activeTrack?.uriString,
                        onPlayTracks = onPlayTracks,
                        onEnqueueTrack = onEnqueueTrack,
                        onEnqueueNext = onEnqueueNext,
                        onToggleFavorite = onToggleFavorite,
                        isTrackFavorite = isTrackFavorite,
                        onImportFolder = null,
                    )
                    LibraryTab.FAVORITES -> TrackListView(
                        tracks = libraryState.favorites,
                        emptyTitle = "No favorite tracks yet",
                        emptySubtitle = "Tap the heart icon on any track to bind it persistently by SHA-256 fingerprint.",
                        activeTrackUri = playbackState.activeTrack?.uriString,
                        onPlayTracks = onPlayTracks,
                        onEnqueueTrack = onEnqueueTrack,
                        onEnqueueNext = onEnqueueNext,
                        onToggleFavorite = onToggleFavorite,
                        isTrackFavorite = isTrackFavorite,
                        onImportFolder = null,
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionRequestCard(
    needsAudioPermission: Boolean,
    needsNotificationPermission: Boolean,
    onRequestPermissions: () -> Unit,
    onImportFolderInstead: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = if (needsAudioPermission) {
                    "Local Audio Access Needed"
                } else {
                    "Enable Media Playback Notification"
                },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = buildString {
                    if (needsAudioPermission) {
                        append("Allow local audio access to scan music (excludes WhatsApp/recordings), or pick folders via SAF. ")
                    }
                    if (needsNotificationPermission) {
                        append("Allow notifications for lock-screen & background playback controls.")
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
                        Text("Pick Folder (SAF)")
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchAndRefreshRow(
    query: String,
    totalCount: Int,
    onQueryChange: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text("Search songs, artists, albums...") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(
                            onClick = { onQueryChange("") },
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                ),
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(6.dp))
            IconButton(
                onClick = onRefresh,
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = "Rescan local music library",
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "$totalCount ${if (totalCount == 1) "track" else "tracks"} • Auto-scan excludes WhatsApp, voice notes & recordings",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun GroupListView(
    groups: List<LibraryGroup>,
    icon: ImageVector,
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

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(groups, key = { it.name }) { group ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelectGroup(group) },
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        modifier = Modifier.size(44.dp),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surface,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = group.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
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
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupDetailTrackList(
    group: LibraryGroup,
    activeTrackUri: String?,
    onPlayTracks: (List<PlaybackTrack>, Int) -> Unit,
    onEnqueueTrack: (PlaybackTrack) -> Unit,
    onEnqueueNext: (PlaybackTrack) -> Unit,
    onToggleFavorite: (PlaybackTrack) -> Unit,
    isTrackFavorite: (PlaybackTrack) -> Boolean,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = group.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(
                onClick = { onPlayTracks(group.tracks, 0) },
                modifier = Modifier.sizeIn(minHeight = 48.dp),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Play All")
            }
        }
        TrackListView(
            tracks = group.tracks,
            emptyTitle = "Empty group",
            emptySubtitle = "",
            activeTrackUri = activeTrackUri,
            onPlayTracks = onPlayTracks,
            onEnqueueTrack = onEnqueueTrack,
            onEnqueueNext = onEnqueueNext,
            onToggleFavorite = onToggleFavorite,
            isTrackFavorite = isTrackFavorite,
            onImportFolder = null,
        )
    }
}

@Composable
private fun TrackListView(
    tracks: List<PlaybackTrack>,
    emptyTitle: String,
    emptySubtitle: String,
    activeTrackUri: String?,
    onPlayTracks: (List<PlaybackTrack>, Int) -> Unit,
    onEnqueueTrack: (PlaybackTrack) -> Unit,
    onEnqueueNext: (PlaybackTrack) -> Unit,
    onToggleFavorite: (PlaybackTrack) -> Unit,
    isTrackFavorite: (PlaybackTrack) -> Boolean,
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
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(44.dp),
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = emptyTitle,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (emptySubtitle.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = emptySubtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    Text("Import Folder (SAF)")
                }
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        itemsIndexed(tracks, key = { _, track -> track.uriString }) { index, track ->
            val isActive = track.uriString == activeTrackUri
            val isFav = isTrackFavorite(track)
            var menuExpanded by remember { mutableStateOf(false) }

            val tileBg = when {
                isActive -> IcePrimaryContainer
                track.isHiResLossless() -> HiResGoldContainer.copy(alpha = 0.75f)
                track.isLosslessFormat() -> MaterialTheme.colorScheme.surfaceContainerHighest
                else -> MaterialTheme.colorScheme.surface
            }
            val tileIconTint = when {
                isActive -> IcePrimary
                track.isHiResLossless() -> HiResGold
                track.isLosslessFormat() -> IcePrimary.copy(alpha = 0.85f)
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPlayTracks(tracks, index) },
                shape = RoundedCornerShape(12.dp),
                color = if (isActive) {
                    IcePrimaryContainer.copy(alpha = 0.28f)
                } else {
                    Color.Transparent
                },
                border = if (isActive) {
                    BorderStroke(1.dp, IcePrimary.copy(alpha = 0.35f))
                } else {
                    null
                },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 10.dp, end = 2.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        modifier = Modifier.size(44.dp),
                        shape = RoundedCornerShape(10.dp),
                        color = tileBg,
                        border = BorderStroke(0.75.dp, tileIconTint.copy(alpha = 0.25f)),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isActive) Icons.Filled.GraphicEq else Icons.Filled.MusicNote,
                                contentDescription = null,
                                tint = tileIconTint,
                                modifier = Modifier.size(21.dp),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
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
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "${track.artist} • ${track.album}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(modifier = Modifier.height(5.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TrackFormatBadge(track = track)
                            if (track.durationMs > 0L) {
                                Text(
                                    text = formatDurationMs(track.durationMs),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    IconButton(
                        onClick = { onToggleFavorite(track) },
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    ) {
                        Icon(
                            imageVector = if (isFav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            contentDescription = if (isFav) "Remove ${track.title} from favorites" else "Add ${track.title} to favorites",
                            tint = if (isFav) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
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
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
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
                        }
                    }
                }
            }
            if (!isActive) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.14f),
                    modifier = Modifier.padding(start = 66.dp, end = 12.dp),
                )
            }
        }
    }
}
