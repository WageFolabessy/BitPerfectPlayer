package com.cangkir.bitperfectplayer.ui.navigation

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cangkir.bitperfectplayer.audio.PlaybackTrack
import com.cangkir.bitperfectplayer.library.PlaylistsUiSnapshot
import com.cangkir.bitperfectplayer.library.TrackTechnicalInfo
import com.cangkir.bitperfectplayer.ui.MainViewModel
import com.cangkir.bitperfectplayer.ui.RootPagerPolicy
import com.cangkir.bitperfectplayer.ui.TrackInfoBottomSheet
import com.cangkir.bitperfectplayer.ui.UiSnackbarMessage
import com.cangkir.bitperfectplayer.ui.WindowLayoutMetrics
import com.cangkir.bitperfectplayer.ui.playlists.AddToPlaylistDialog

internal object AppNavRoutes {
    const val MAIN_SHELL = "main_shell"
    const val LIBRARY = "library"
    const val PLAYLISTS = "playlists"
    const val SETTINGS = "settings"
    const val QUEUE = "queue"
    const val LYRICS = "lyrics"
    const val DIAGNOSTICS = "diagnostics"

    fun topLevelRouteForPage(page: Int): String = when (page) {
        RootPagerPolicy.TOP_LEVEL_PAGE_PLAYLISTS -> PLAYLISTS
        RootPagerPolicy.TOP_LEVEL_PAGE_SETTINGS -> SETTINGS
        else -> LIBRARY
    }

    fun topLevelPageForRoute(route: String): Int = when (route) {
        PLAYLISTS -> RootPagerPolicy.TOP_LEVEL_PAGE_PLAYLISTS
        SETTINGS -> RootPagerPolicy.TOP_LEVEL_PAGE_SETTINGS
        else -> RootPagerPolicy.TOP_LEVEL_PAGE_LIBRARY
    }
}

internal data class TopLevelTab(val route: String, val label: String, val selectedIcon: ImageVector, val unselectedIcon: ImageVector)

internal val TOP_LEVEL_TABS = listOf(
    TopLevelTab(
        route = AppNavRoutes.LIBRARY,
        label = "Library",
        selectedIcon = Icons.Filled.LibraryMusic,
        unselectedIcon = Icons.Outlined.LibraryMusic,
    ),
    TopLevelTab(
        route = AppNavRoutes.PLAYLISTS,
        label = "Playlists",
        selectedIcon = Icons.AutoMirrored.Filled.PlaylistPlay,
        unselectedIcon = Icons.AutoMirrored.Outlined.PlaylistPlay,
    ),
    TopLevelTab(
        route = AppNavRoutes.SETTINGS,
        label = "Settings",
        selectedIcon = Icons.Filled.Settings,
        unselectedIcon = Icons.Outlined.Settings,
    ),
)

@Composable
internal fun RootGlobalOverlays(
    viewModel: MainViewModel,
    playlistsState: PlaylistsUiSnapshot,
    trackPendingAddToPlaylist: PlaybackTrack?,
    onDismissAddToPlaylist: () -> Unit,
    trackInfoState: TrackTechnicalInfo?,
    uiSnackbarMessage: UiSnackbarMessage?,
    snackbarHostState: SnackbarHostState,
) {
    LaunchedEffect(uiSnackbarMessage?.id) {
        val current = uiSnackbarMessage ?: return@LaunchedEffect
        try {
            snackbarHostState.currentSnackbarData?.dismiss()
            val result = snackbarHostState.showSnackbar(
                message = current.message,
                actionLabel = current.actionLabel,
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) {
                current.onAction?.invoke()
            }
        } finally {
            viewModel.clearUiMessage(current.id)
        }
    }

    trackPendingAddToPlaylist?.let { targetTrack ->
        AddToPlaylistDialog(
            track = targetTrack,
            playlists = playlistsState.playlists,
            onSelectPlaylist = { playlist ->
                onDismissAddToPlaylist()
                viewModel.playlists.addTrackToPlaylist(
                    playlistId = playlist.id,
                    playlistName = playlist.name,
                    track = targetTrack,
                )
            },
            onCreateAndAdd = { newName ->
                onDismissAddToPlaylist()
                viewModel.playlists.createPlaylistAndAddTrack(newName, targetTrack)
            },
            onDismiss = onDismissAddToPlaylist,
        )
    }

    trackInfoState?.let { info ->
        TrackInfoBottomSheet(
            info = info,
            onCopiedMessage = viewModel::showUiMessage,
            onDismiss = viewModel.library::dismissTrackInfo,
        )
    }
}

@Composable
internal fun AdaptiveTopLevelNavigationFrame(
    isTopLevelRoute: Boolean,
    currentRoute: String,
    snackbarHostState: SnackbarHostState,
    onSelectTab: (String) -> Unit,
    miniPlayerContent: @Composable () -> Unit,
    mainContent: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavigationRail = remember(maxWidth) {
            WindowLayoutMetrics.useNavigationRail(maxWidth.value.toInt())
        }

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = {
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = if (isTopLevelRoute && !useNavigationRail) Modifier else Modifier.navigationBarsPadding(),
                )
            },
            bottomBar = {
                if (isTopLevelRoute && !useNavigationRail) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        miniPlayerContent()
                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                        )
                        BitPerfectBottomNavBar(
                            currentRoute = currentRoute,
                            onSelectTab = onSelectTab,
                        )
                    }
                }
            },
        ) { rootInnerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = rootInnerPadding.calculateBottomPadding()),
            ) {
                if (useNavigationRail && isTopLevelRoute) {
                    BitPerfectNavigationRail(
                        currentRoute = currentRoute,
                        onSelectTab = onSelectTab,
                    )
                    VerticalDivider(
                        modifier = Modifier.fillMaxHeight(),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                ) {
                    mainContent(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    )
                    if (useNavigationRail && isTopLevelRoute) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding(),
                        ) {
                            miniPlayerContent()
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun BitPerfectBottomNavBar(currentRoute: String, onSelectTab: (String) -> Unit) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        modifier = Modifier.semantics {
            contentDescription = "Bottom navigation bar"
        },
    ) {
        TOP_LEVEL_TABS.forEach { tab ->
            val selected = currentRoute == tab.route
            NavigationBarItem(
                selected = selected,
                onClick = { onSelectTab(tab.route) },
                modifier = Modifier
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .semantics {
                        contentDescription = tab.label
                    },
                icon = {
                    Icon(
                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                        contentDescription = null,
                    )
                },
                label = null,
                alwaysShowLabel = false,
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}

@Composable
internal fun BitPerfectNavigationRail(currentRoute: String, onSelectTab: (String) -> Unit) {
    NavigationRail(
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxHeight()
            .semantics {
                contentDescription = "Side navigation rail"
            },
    ) {
        Spacer(modifier = Modifier.height(12.dp))
        TOP_LEVEL_TABS.forEach { tab ->
            val selected = currentRoute == tab.route
            NavigationRailItem(
                selected = selected,
                onClick = { onSelectTab(tab.route) },
                modifier = Modifier
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .semantics {
                        contentDescription = tab.label
                    },
                icon = {
                    Icon(
                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                        contentDescription = null,
                    )
                },
                label = null,
                alwaysShowLabel = false,
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}
