package cc.tomko.outify.ui.screens.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import cc.tomko.outify.core.model.Album
import cc.tomko.outify.core.model.OutifyUri
import cc.tomko.outify.core.model.Playlist
import cc.tomko.outify.core.model.PlaylistFolder
import cc.tomko.outify.core.model.toColor
import cc.tomko.outify.ui.GlobalPopupController
import cc.tomko.outify.ui.PopupSpec
import cc.tomko.outify.ui.components.ArtworkBackground
import cc.tomko.outify.ui.components.CollapsingHeader
import cc.tomko.outify.ui.components.ErrorScreen
import cc.tomko.outify.ui.components.bottomsheet.CreateFolderBottomSheet
import cc.tomko.outify.ui.components.bottomsheet.MoveToFolderBottomSheet
import cc.tomko.outify.ui.components.navigation.Route
import cc.tomko.outify.ui.components.rememberCollapsingHeaderState
import cc.tomko.outify.ui.components.rows.AlbumRow
import cc.tomko.outify.ui.components.rows.PlaylistRow
import cc.tomko.outify.ui.components.rows.SwipeableEpisodeRowConfigured
import cc.tomko.outify.ui.components.user.UserChipAvatar
import cc.tomko.outify.ui.screens.MaterialSearchBar
import cc.tomko.outify.ui.viewmodel.library.LibraryTab
import cc.tomko.outify.ui.viewmodel.library.LibraryViewModel
import kotlinx.coroutines.launch

private sealed class LibraryItem {
    abstract val key: String
    data class FolderHeader(val folder: PlaylistFolder, val isExpanded: Boolean) : LibraryItem() {
        override val key get() = "folder:${folder.id}"
    }
    data class PlaylistRow(val playlist: Playlist, val folderId: String?) : LibraryItem() {
        override val key get() = "playlist:${playlist.uri}"
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SharedTransitionScope.LibraryScreen(
    viewModel: LibraryViewModel,
    backStack: NavBackStack<NavKey>,
    modifier: Modifier = Modifier,
) {
    val libraryState by viewModel.libraryState.collectAsState()

    LaunchedEffect(Unit) { viewModel.loadPlaylistUris() }

    if (libraryState.error != null && libraryState.playlists.isEmpty() && libraryState.selectedTab == LibraryTab.Playlists) {
        ErrorScreen(
            message = libraryState.error!!,
            onRetry = { viewModel.retry() },
            modifier = modifier,
        )
        return
    }

    val density = LocalDensity.current
    var searchQuery by remember { mutableStateOf("") }
    val lazyListState = rememberLazyListState()
    val collapsingState = rememberCollapsingHeaderState()

    val atTop by remember {
        derivedStateOf {
            lazyListState.firstVisibleItemIndex == 0 &&
                    lazyListState.firstVisibleItemScrollOffset == 0
        }
    }
    SideEffect { collapsingState.canExpand = atTop }

    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val scope = rememberCoroutineScope()

    val isScrolled by remember {
        derivedStateOf {
            lazyListState.firstVisibleItemIndex > 1 ||
                    lazyListState.firstVisibleItemScrollOffset > 50
        }
    }
    val showScrollToTop = isScrolled

    var expandedFolderIds by remember { mutableStateOf(setOf<String>()) }
    var showCreateFolder by remember { mutableStateOf(false) }
    var editingFolder by remember { mutableStateOf<PlaylistFolder?>(null) }
    var showMoveToFolder by remember { mutableStateOf(false) }
    var movePlaylistUri by remember { mutableStateOf<String?>(null) }
    var moveCurrentFolderId by remember { mutableStateOf<String?>(null) }
    var deleteFolderId by remember { mutableStateOf<String?>(null) }

    val selectedTab = libraryState.selectedTab

    val filteredPlaylists = remember(libraryState.playlists, searchQuery) {
        if (searchQuery.isBlank()) libraryState.playlists
        else libraryState.playlists.filter { p ->
            p.attributes.name.contains(searchQuery, ignoreCase = true) ||
                    p.ownerUsername.contains(searchQuery, ignoreCase = true)
        }
    }

    val filteredAlbums = remember(libraryState.albums, searchQuery) {
        if (searchQuery.isBlank()) libraryState.albums
        else libraryState.albums.filter { a ->
            a.name.contains(searchQuery, ignoreCase = true)
        }
    }

    val filteredEpisodes = remember(libraryState.episodes, searchQuery) {
        if (searchQuery.isBlank()) libraryState.episodes
        else libraryState.episodes.filter { e ->
            e.name.contains(searchQuery, ignoreCase = true) ||
                    e.showName.contains(searchQuery, ignoreCase = true)
        }
    }

    val flatItems = remember(libraryState.folders, filteredPlaylists, expandedFolderIds) {
        buildFlatList(libraryState.folders, filteredPlaylists, expandedFolderIds)
    }

    LaunchedEffect(lazyListState.isScrollInProgress) {
        if (!lazyListState.isScrollInProgress) {
            val canExpand = lazyListState.firstVisibleItemIndex == 0 &&
                    lazyListState.firstVisibleItemScrollOffset == 0
            collapsingState.snapIfNeeded(canExpand)
        }
    }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = { viewModel.refresh() },
        modifier = modifier
            .fillMaxSize()
            .background(color = MaterialTheme.colorScheme.surface)
            .nestedScroll(collapsingState.nestedScrollConnection)
    ) {
        val topPadding = with(density) { collapsingState.height.value.toDp() }

        LazyColumn(
            state = lazyListState,
            contentPadding = PaddingValues(top = topPadding, bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item(key = "expressive_filter_bar") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    LibraryExpressiveFilters(
                        selectedTab = selectedTab,
                        onTabSelected = { viewModel.selectTab(it) }
                    )

                    MaterialSearchBar(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        isLoading = false,
                        autoFocus = false,
                        placeholderText = when (selectedTab) {
                            LibraryTab.Playlists -> "Search playlists..."
                            LibraryTab.Albums -> "Search albums..."
                            LibraryTab.Episodes -> "Search episodes..."
                        },
                    )
                }
            }

            when (selectedTab) {
                LibraryTab.Playlists -> {
                    if (flatItems.isEmpty()) {
                        item(key = "playlists_empty") {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (searchQuery.isBlank()) "Your playlist library is empty" else "No matching playlists found",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    items(
                        items = flatItems,
                        key = { it.key },
                        contentType = {
                            when (it) {
                                is LibraryItem.FolderHeader -> "folder_header"
                                is LibraryItem.PlaylistRow -> if (it.folderId != null) "folder_playlist" else "unorg_playlist"
                            }
                        }
                    ) { item ->
                        when (item) {
                            is LibraryItem.FolderHeader -> {
                                FolderHeaderContent(
                                    folder = item.folder,
                                    isExpanded = item.isExpanded,
                                    onToggleExpand = {
                                        expandedFolderIds = if (item.isExpanded)
                                            expandedFolderIds - item.folder.id
                                        else
                                            expandedFolderIds + item.folder.id
                                    },
                                    onEdit = { editingFolder = item.folder },
                                    onDelete = { deleteFolderId = item.folder.id },
                                )
                            }
                            is LibraryItem.PlaylistRow -> {
                                PlaylistRowContent(
                                    playlist = item.playlist,
                                    folderId = item.folderId,
                                    backStack = backStack,
                                    viewModel = viewModel,
                                    onMovePlaylist = { uri, fid ->
                                        movePlaylistUri = uri
                                        moveCurrentFolderId = fid
                                        showMoveToFolder = true
                                    },
                                )
                            }
                        }
                    }
                }

                LibraryTab.Albums -> {
                    if (libraryState.isLoadingAlbums && filteredAlbums.isEmpty()) {
                        item(key = "albums_loading") {
                            Text(
                                text = "Loading albums...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
                            )
                        }
                    }

                    if (!libraryState.isLoadingAlbums && filteredAlbums.isEmpty()) {
                        item(key = "albums_empty") {
                            Text(
                                text = if (searchQuery.isBlank()) "No saved albums yet" else "No matching albums found",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
                            )
                        }
                    }

                    items(
                        items = filteredAlbums,
                        key = { it.uri },
                        contentType = { "album" }
                    ) { album ->
                        Box(modifier = Modifier.padding(horizontal = 4.dp)) {
                            AlbumRow(
                                album = album,
                                artworkUrl = album.covers.firstOrNull()?.let { cover ->
                                    "https://i.scdn.co/image/${cover.uri}"
                                },
                                onRowClick = {
                                    backStack.add(Route.AlbumScreen(album.uri))
                                },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                }

                LibraryTab.Episodes -> {
                    if (libraryState.isLoadingEpisodes && filteredEpisodes.isEmpty()) {
                        item(key = "episodes_loading") {
                            Text(
                                text = "Loading episodes...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
                            )
                        }
                    }

                    if (!libraryState.isLoadingEpisodes && filteredEpisodes.isEmpty()) {
                        item(key = "episodes_empty") {
                            Text(
                                text = if (searchQuery.isBlank()) "No saved episodes yet" else "No matching episodes found",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
                            )
                        }
                    }

                    items(
                        items = filteredEpisodes,
                        key = { it.uri },
                        contentType = { "episode" }
                    ) { episode ->
                        val showUri = episode.showUri.ifBlank {
                            libraryState.episodeShowUris[episode.uri]
                        } ?: ""
                        SwipeableEpisodeRowConfigured(
                            episode = episode,
                            onRowClick = {
                                viewModel.playEpisode(episode)
                            },
                            onArtworkClick = {
                                if (showUri.isNotBlank()) {
                                    backStack.add(Route.ShowScreen(showUri))
                                } else {
                                    scope.launch {
                                        val details = viewModel.resolveEpisodeDetails(episode.id)
                                        if (details != null && details.showUri.isNotBlank()) {
                                            backStack.add(Route.ShowScreen(details.showUri))
                                        }
                                    }
                                }
                            },
                            onShowNameClick = {
                                if (showUri.isNotBlank()) {
                                    backStack.add(Route.ShowScreen(showUri))
                                } else {
                                    scope.launch {
                                        val details = viewModel.resolveEpisodeDetails(episode.id)
                                        if (details != null && details.showUri.isNotBlank()) {
                                            backStack.add(Route.ShowScreen(details.showUri))
                                        }
                                    }
                                }
                            },
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .animateItem(),
                        )
                    }
                }

                else -> { /* Managed safety fallback for removed states */ }
            }
        }

        CollapsingHeader(
            collapseFraction = collapsingState.collapseFraction,
            headerHeight = topPadding,
            onBackPressed = { backStack.removeAt(backStack.lastIndex) },
            backgroundContent = {
                var artworkUrl by viewModel.headerArtwork
                LaunchedEffect(libraryState.playlists) {
                    viewModel.loadHeaderArtwork(libraryState.playlists)
                }
                ArtworkBackground(
                    artworkUrl = artworkUrl,
                    fallback = { Icon(Icons.Default.LibraryMusic, contentDescription = null) }
                )
            },
            titleContent = {
                Text(
                    text = "Your library",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = when (selectedTab) {
                        LibraryTab.Playlists -> "Account • ${libraryState.playlists.count()} playlists"
                        LibraryTab.Albums -> "Account • ${libraryState.albums.count()} albums"
                        LibraryTab.Episodes -> "Account • ${libraryState.episodes.count()} episodes"
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            },
        )

        // Expressive Actions (FABs) Layout Block
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomEnd
        ) {
            var fabExpanded by remember { mutableStateOf(false) }

            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(16.dp)
            ) {
                AnimatedVisibility(
                    visible = showScrollToTop,
                    enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
                    exit = fadeOut() + slideOutVertically(targetOffsetY = { it })
                ) {
                    FloatingActionButton(
                        onClick = { scope.launch { lazyListState.animateScrollToItem(0) } },
                        shape = CircleShape,
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Scroll to top")
                    }
                }

                if (selectedTab == LibraryTab.Playlists) {
                    AnimatedVisibility(visible = fabExpanded) {
                        FloatingActionButton(
                            onClick = {
                                fabExpanded = false
                                GlobalPopupController.show(PopupSpec.CreatePlaylist())
                            },
                            shape = CircleShape,
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.PlaylistAdd,
                                contentDescription = "Add playlist"
                            )
                        }
                    }

                    AnimatedVisibility(visible = fabExpanded) {
                        FloatingActionButton(
                            onClick = {
                                fabExpanded = false
                                showCreateFolder = true
                            },
                            shape = CircleShape,
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(
                                Icons.Default.CreateNewFolder,
                                contentDescription = "Add folder"
                            )
                        }
                    }

                    val cornerRadius by animateFloatAsState(
                        targetValue = if (fabExpanded) 20f else 28f,
                        animationSpec = tween(durationMillis = 250),
                        label = "fab_corner"
                    )

                    FloatingActionButton(
                        onClick = { fabExpanded = !fabExpanded },
                        shape = RoundedCornerShape(cornerRadius.dp),
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(56.dp)
                    ) {
                        val rotation by animateFloatAsState(
                            targetValue = if (fabExpanded) 45f else 0f,
                            animationSpec = tween(durationMillis = 250),
                            label = "fab_rotation"
                        )
                        Icon(
                            Icons.Default.Add,
                            contentDescription = if (fabExpanded) "Close menu" else "Add item",
                            modifier = Modifier.rotate(rotation)
                        )
                    }
                }
            }
        }
    }

    // Sheet / Dialog Overlays Management
    if (showCreateFolder || editingFolder != null) {
        val folder = editingFolder
        CreateFolderBottomSheet(
            initialName = folder?.name ?: "",
            initialColor = folder?.toColor() ?: Color(0xFF1DB954),
            folderId = folder?.id,
            onDismiss = {
                showCreateFolder = false
                editingFolder = null
            },
            onCreated = { newFolder ->
                if (folder != null) viewModel.updateFolder(newFolder)
                else viewModel.createFolder(newFolder)
            }
        )
    }

    if (showMoveToFolder && movePlaylistUri != null) {
        MoveToFolderBottomSheet(
            folders = libraryState.folders,
            currentFolderId = moveCurrentFolderId,
            onDismiss = {
                showMoveToFolder = false
                movePlaylistUri = null
                moveCurrentFolderId = null
            },
            onSelectFolder = { folderId ->
                movePlaylistUri?.let { viewModel.movePlaylistToFolder(it, folderId) }
            }
        )
    }

    deleteFolderId?.let { folderId ->
        val folder = libraryState.folders.find { it.id == folderId }
        AlertDialog(
            onDismissRequest = { deleteFolderId = null },
            title = { Text("Delete folder") },
            text = {
                Text("Are you sure you want to delete \"${folder?.name}\"? Playlists inside will remain intact but unorganized.")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteFolder(folderId)
                    deleteFolderId = null
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteFolderId = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

/**
 * Expressive Material 3 Filter Pills Layout
 * Replaces old-style heavy structural tabs with casual, dynamic contextual selection tags.
 */
@Composable
private fun LibraryExpressiveFilters(
    selectedTab: LibraryTab,
    onTabSelected: (LibraryTab) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        // Explicitly handle desired dynamic filters instead of full structural tabs loop
        val allowedFilters = listOf(LibraryTab.Playlists, LibraryTab.Albums, LibraryTab.Episodes)

        allowedFilters.forEach { tab ->
            val isSelected = tab == selectedTab

            val containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            }

            val contentColor = if (isSelected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }

            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(containerColor)
                    .clickable { onTabSelected(tab) }
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = tab.name,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = contentColor
                )
            }
        }
    }
}

@Composable
private fun FolderHeaderContent(
    folder: PlaylistFolder,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val folderColor = folder.toColor()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(12.dp)) // Expressive modern radius
                .background(folderColor),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Folder, null, tint = Color.White, modifier = Modifier.size(24.dp))
        }

        Spacer(Modifier.width(16.dp))

        Column(
            Modifier
                .weight(1f)
                .clickable { onToggleExpand() }
        ) {
            Text(
                folder.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                "${folder.playlistIds.size} playlists",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Default.Edit,
                "Edit location",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }

        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Default.Delete,
                "Remove row",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp)
            )
        }

        Icon(
            imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = if (isExpanded) "Collapse" else "Expand",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PlaylistRowContent(
    playlist: Playlist,
    folderId: String?,
    backStack: NavBackStack<NavKey>,
    viewModel: LibraryViewModel,
    onMovePlaylist: (String, String?) -> Unit,
) {
    val artworkUrl by produceState<String?>(null, playlist.uri) {
        value = viewModel.getArtworkUrl(playlist)
    }
    val authors by produceState<List<cc.tomko.outify.core.model.Profile>>(
        emptyList(),
        playlist.uri
    ) { value = viewModel.getAuthors(playlist).take(2) }

    val startIndent = if (folderId != null) 32.dp else 12.dp

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = startIndent, end = 16.dp),
    ) {
        Box(modifier = Modifier.weight(1f)) {
            PlaylistRow(
                playlist = playlist,
                artworkUrl = artworkUrl,
                onRowClick = { backStack.add(Route.PlaylistScreen(playlist.uri)) },
                onRowLongClick = {
                    GlobalPopupController.show(
                        PopupSpec.PlaylistInfo(playlist, artworkUrl)
                    )
                },
                trailingContent = {
                    Row(horizontalArrangement = Arrangement.spacedBy((-4).dp)) {
                        authors.forEach { author ->
                            UserChipAvatar(
                                artworkUrl = author.imageUrl,
                                modifier = Modifier
                                    .size(24.dp)
                                    .clickable { backStack.add(Route.ProfileScreen(author.uri)) },
                            )
                        }
                    }
                },
            )
        }

        IconButton(
            onClick = { onMovePlaylist(playlist.uri, folderId) },
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                Icons.Default.CreateNewFolder,
                "Reorganize item",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

private fun buildFlatList(
    folders: List<PlaylistFolder>,
    playlists: List<Playlist>,
    expandedFolderIds: Set<String>,
): List<LibraryItem> {
    val items = mutableListOf<LibraryItem>()
    val organizedUris = folders.flatMap { it.playlistIds }.toSet()
    val byUri = playlists.associateBy { it.uri }

    for (folder in folders) {
        val exp = folder.id in expandedFolderIds
        items.add(LibraryItem.FolderHeader(folder, exp))
        if (exp) {
            for (uri in folder.playlistIds) {
                byUri[uri]?.let { items.add(LibraryItem.PlaylistRow(it, folder.id)) }
            }
        }
    }

    for (p in playlists) {
        if (p.uri !in organizedUris) items.add(LibraryItem.PlaylistRow(p, null))
    }
    return items
}