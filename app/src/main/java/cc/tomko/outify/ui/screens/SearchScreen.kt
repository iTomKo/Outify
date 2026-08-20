package cc.tomko.outify.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.NoAccounts
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import cc.tomko.outify.ALBUM_COVER_URL
import cc.tomko.outify.R
import cc.tomko.outify.core.model.CoverSize
import cc.tomko.outify.core.model.getCover
import cc.tomko.outify.core.model.toOutifyUri
import cc.tomko.outify.core.model.toPlayableAudio
import cc.tomko.outify.core.model.toSpotifyUri
import cc.tomko.outify.ui.GlobalPopupController
import cc.tomko.outify.ui.PopupSpec
import cc.tomko.outify.ui.components.SkeletonTrackRow
import cc.tomko.outify.ui.components.navigation.Route
import cc.tomko.outify.ui.components.navigation.Route.ArtistScreen
import cc.tomko.outify.ui.components.navigation.Route.PlaylistScreen
import cc.tomko.outify.ui.components.navigation.Route.TrackScreen
import cc.tomko.outify.ui.components.rows.AlbumRow
import cc.tomko.outify.ui.components.rows.ArtistRow
import cc.tomko.outify.ui.components.rows.PlaylistRow
import cc.tomko.outify.ui.components.rows.ShowRow
import cc.tomko.outify.ui.components.rows.SwipeableEpisodeRowConfigured
import cc.tomko.outify.ui.components.rows.SwipeableTrackRowConfigured
import cc.tomko.outify.ui.viewmodel.SearchUiModel
import cc.tomko.outify.ui.viewmodel.SearchViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedTransitionScope.SearchScreen(
    backStack: NavBackStack<NavKey>,
    viewModel: SearchViewModel,
    modifier: Modifier = Modifier,
    showSearchUi: Boolean = true,
) {
    val results by viewModel.results.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isLoggedIn by viewModel.isLoggedIn.collectAsState()
    val searchHistory by viewModel.searchHistory.collectAsState()
    val historyResults by viewModel.historyResults.collectAsState()
    val spirc = viewModel.spirc

    val listState = rememberLazyListState()
    val currentTrack by viewModel.currentAudio.collectAsState(initial = null)
    val isPlaybackPlaying by viewModel.isPlaying.collectAsState(initial = false)
    val scope = rememberCoroutineScope()

    val isScrolled by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 2 ||
                    listState.firstVisibleItemScrollOffset > 100
        }
    }
    val showScrollToTop = isScrolled

    var query by rememberSaveable { mutableStateOf("") }
    var showAdvancedSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var showTracks by rememberSaveable { mutableStateOf(true) }
    var showArtists by rememberSaveable { mutableStateOf(true) }
    var showAlbums by rememberSaveable { mutableStateOf(true) }
    var showPlaylists by rememberSaveable { mutableStateOf(true) }
    var showShows by rememberSaveable { mutableStateOf(true) }
    var showEpisodes by rememberSaveable { mutableStateOf(true) }

    val filteredResults = remember(
        results,
        showTracks,
        showArtists,
        showAlbums,
        showPlaylists,
        showShows,
        showEpisodes
    ) {
        applyFiltersToSectionedResults(
            results,
            showTracks,
            showArtists,
            showAlbums,
            showPlaylists,
            showShows,
            showEpisodes
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .systemBarsPadding()
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
        ) {
            if (!showSearchUi) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = {
                            backStack.removeAt(backStack.lastIndex)
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        }

                        Text(
                            text = "Recommendations",
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                if (isLoading) {
                    item {
                        repeat(5) {
                            SkeletonTrackRow()
                        }
                    }
                } else {
                    items(
                        items = results,
                        key = { it.uri }
                    ) { item ->
                        when (item) {
                            is SearchUiModel.TrackItem -> {
                                val track = item.track
                                SwipeableTrackRowConfigured(
                                    track = track,
                                    currentAudio = currentTrack,
                                    isPlaybackPlaying = isPlaybackPlaying,
                                    onRowClick = remember(track.uri) {
                                        {
                                            spirc.load(track.toSpotifyUri())
                                            viewModel.setAudio(track.toPlayableAudio())
                                        }
                                    },
                                    onArtistClick = {
                                        backStack.add(ArtistScreen(it.uri))
                                    },
                                    onArtworkClick = {
                                        val albumUri = track.album?.uri
                                        if (albumUri != null) {
                                            backStack.add(Route.AlbumScreen(albumUri))
                                        } else {
                                            backStack.add(TrackScreen(track.uri))
                                        }
                                    },
                                    modifier = Modifier.animateItem()
                                )
                            }
                            else -> {}
                        }
                    }
                }
            }

            if (showSearchUi) {
                item {
                    Text(
                        text = "Search",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .padding(start = 24.dp, end = 24.dp, top = 16.dp),
                    )
                }

                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MaterialSearchBar(
                            query = query,
                            onQueryChange = { newQuery ->
                                query = newQuery
                                viewModel.onQueryChange(newQuery)
                            },
                            isLoading = isLoading,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { showAdvancedSheet = true }) {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = "Advanced search",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            if (showSearchUi) {
                if (query.isBlank() && searchHistory.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 400.dp),
                            contentAlignment = Alignment.Center
                        ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                modifier = Modifier.size(72.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "No history yet",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Tap search results to save them here",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            } else if (query.isBlank()) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Search History",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        TextButton(onClick = { viewModel.clearHistory() }) {
                            Text("Clear all")
                        }
                    }
                }

                items(
                    items = historyResults,
                    key = { it.uri }
                ) { item ->
                    val removeButton: @Composable () -> Unit = {
                        IconButton(onClick = { viewModel.removeFromHistory(item.uri) }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Remove from history",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    when (item) {
                        is SearchUiModel.TrackItem -> {
                            val track = item.track
                            SwipeableTrackRowConfigured(
                                track = track,
                                currentAudio = currentTrack,
                                isPlaybackPlaying = isPlaybackPlaying,
                                onRowClick = {
                                    spirc.load(track.toSpotifyUri())
                                    viewModel.setAudio(track.toPlayableAudio())
                                },
                                onArtistClick = {
                                    backStack.add(ArtistScreen(it.uri))
                                },
                                onArtworkClick = {
                                    val track = item.track
                                    val albumUri = track.album?.uri
                                    if (albumUri != null) {
                                        backStack.add(Route.AlbumScreen(albumUri))
                                    } else {
                                        backStack.add(TrackScreen(track.uri))
                                    }
                                },
                                trailingContent = removeButton,
                                modifier = Modifier.animateItem()
                            )
                        }

                        is SearchUiModel.AlbumItem -> {
                            val album = item.album
                            val artworkUrl = ALBUM_COVER_URL + album.getCover(CoverSize.MEDIUM)?.uri
                            AlbumRow(
                                album = album,
                                artworkUrl = artworkUrl,
                                onRowClick = {
                                    backStack.add(Route.AlbumScreen(album.uri))
                                },
                                trailingContent = removeButton,
                                modifier = Modifier.animateItem()
                            )
                        }

                        is SearchUiModel.ArtistItem -> {
                            val artist = item.artist
                            ArtistRow(
                                artist = artist,
                                artworkUrl = ALBUM_COVER_URL + artist.getCover(CoverSize.MEDIUM)?.uri,
                                onRowClick = {
                                    backStack.add(ArtistScreen(artist.uri))
                                },
                                trailingContent = removeButton,
                                modifier = Modifier.animateItem()
                            )
                        }

                        is SearchUiModel.PlaylistItem -> {
                            val playlist = item.playlist
                            var artworkUrl by remember(playlist.uri) { mutableStateOf<String?>(null) }
                            LaunchedEffect(playlist.uri) {
                                artworkUrl = viewModel.getArtworkUrl(playlist)
                            }
                            PlaylistRow(
                                playlist = playlist,
                                artworkUrl = artworkUrl,
                                onRowClick = {
                                    backStack.add(PlaylistScreen(playlist.uri))
                                },
                                onRowLongClick = {
                                    GlobalPopupController.show(
                                        PopupSpec.PlaylistInfo(
                                            playlist,
                                            artworkUrl
                                        )
                                    )
                                },
                                onArtistClick = {
                                    // TODO: Add author page
                                },
                                contentDescription = playlist.attributes.description,
                                sharedTransitionScope = this@SearchScreen,
                                trailingContent = removeButton,
                                modifier = Modifier.animateItem()
                            )
                        }

                        is SearchUiModel.EpisodeItem -> {
                            val episode = item.episode
                            val showUri = episode.showUri
                            val openShow: () -> Unit = {
                                if (showUri.isNotBlank()) {
                                    backStack.add(Route.ShowScreen(showUri))
                                } else {
                                    scope.launch {
                                        val resolved = viewModel.resolveEpisodeShowUri(episode.id)
                                        if (!resolved.isNullOrBlank()) {
                                            backStack.add(Route.ShowScreen(resolved))
                                        }
                                    }
                                }
                            }

                            SwipeableEpisodeRowConfigured(
                                episode = episode,
                                isPlaybackPlaying = isPlaybackPlaying,
                                onRowClick = {
                                    spirc.load(episode.toOutifyUri())
                                    viewModel.setAudio(episode.toPlayableAudio())
                                },
                                onArtworkClick = openShow,
                                onShowNameClick = openShow,
                                trailingContent = removeButton,
                                modifier = Modifier.animateItem()
                            )
                        }

                        is SearchUiModel.ShowItem -> {
                            val show = item.show
                            ShowRow(
                                show = show,
                                onRowClick = {
                                    backStack.add(Route.ShowScreen(show.uri))
                                },
                                onRowLongClick = {},
                                onPublisherClick = {},
                                trailingContent = removeButton,
                                modifier = Modifier.animateItem()
                            )
                        }

                        else -> {}
                    }
                }
            }

            if (query.isNotBlank()) {
                item {
                    FiltersBar(
                        showTracks = showTracks,
                        showArtists = showArtists,
                        showAlbums = showAlbums,
                        showPlaylists = showPlaylists,
                        showShows = showShows,
                        showEpisodes = showEpisodes,
                        onToggleTracks = { showTracks = it },
                        onToggleArtists = { showArtists = it },
                        onToggleAlbums = { showAlbums = it },
                        onTogglePlaylists = { showPlaylists = it },
                        onToggleShows = { showShows = it },
                        onToggleEpisodes = { showEpisodes = it }
                    )
                }

                if (!isLoggedIn) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Default.NoAccounts,
                                    contentDescription = "Logged out",
                                    modifier = Modifier.size(64.dp)
                                )

                                Spacer(modifier = Modifier.height(16.dp))

                                Text(
                                    text = "This feature is only available to logged in users",
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleMedium,
                                )

                                Icon(
                                    Icons.AutoMirrored.Filled.Login,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .clickable {
                                            backStack.add(Route.AccountsScreen)
                                        }
                                )
                            }
                        }
                    }
                } else if (filteredResults.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SearchOff,
                                    contentDescription = "Select filters",
                                    modifier = Modifier.size(64.dp)
                                )

                                Spacer(modifier = Modifier.height(16.dp))

                                Text(
                                    text = "Nothing found..",
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleMedium,
                                )

                                Text(
                                    text = "Type the query and select filters",
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                        }
                    }
                }

                items(
                    items = filteredResults,
                    key = { it.uri }
                ) { item ->
                    when (item) {
                        is SearchUiModel.SectionHeader -> {
                            Text(
                                text = stringResource(id = item.titleRes),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(
                                    horizontal = 16.dp,
                                    vertical = 8.dp
                                )
                            )
                        }

                        is SearchUiModel.SkeletonItem -> {
                            SkeletonTrackRow()
                        }

                        is SearchUiModel.TrackItem -> {
                            val track = item.track

                            SwipeableTrackRowConfigured(
                                track = track,
                                currentAudio = currentTrack,
                                isPlaybackPlaying = isPlaybackPlaying,
                                onRowClick = remember(track.uri) {
                                    {
                                        viewModel.addToHistory(item)
                                        spirc.load(track.toSpotifyUri()) // TODO: make context be the search screen
                                        // Optimistic UI
                                        viewModel.setAudio(track.toPlayableAudio())
                                    }
                                },
                                onArtistClick = {
                                    backStack.add(ArtistScreen(it.uri))
                                },
                                onArtworkClick = {
                                    val track = item.track
                                    val albumUri = track.album?.uri
                                    if (albumUri != null) {
                                        backStack.add(Route.AlbumScreen(albumUri))
                                    } else {
                                        backStack.add(TrackScreen(track.uri))
                                    }
                                },
                                modifier = Modifier.animateItem()
                            )
                        }

                        is SearchUiModel.AlbumItem -> {
                            val album = item.album
                            val artworkUrl =
                                ALBUM_COVER_URL + album.getCover(CoverSize.MEDIUM)?.uri;

                            AlbumRow(
                                album = album,
                                artworkUrl = artworkUrl,
                                onRowClick = {
                                    viewModel.addToHistory(item)
                                    backStack.add(Route.AlbumScreen(album.uri))
                                },
                                modifier = Modifier.animateItem()
                            )
                        }

                        is SearchUiModel.ArtistItem -> {
                            val artist = item.artist

                            ArtistRow(
                                artist = artist,
                                artworkUrl = ALBUM_COVER_URL + artist.getCover(CoverSize.MEDIUM)?.uri,
                                onRowClick = {
                                    viewModel.addToHistory(item)
                                    backStack.add(ArtistScreen(artist.uri))
                                },
                                modifier = Modifier.animateItem()
                            )
                        }

                        is SearchUiModel.PlaylistItem -> {
                            val playlist = item.playlist
                            var artworkUrl by remember(playlist.uri) { mutableStateOf<String?>(null) }

                            LaunchedEffect(playlist.uri) {
                                artworkUrl = viewModel.getArtworkUrl(playlist)
                            }

                            PlaylistRow(
                                playlist = playlist,
                                artworkUrl = artworkUrl,
                                onRowClick = {
                                    viewModel.addToHistory(item)
                                    backStack.add(PlaylistScreen(playlist.uri))
                                },
                                onRowLongClick = {
                                    GlobalPopupController.show(
                                        PopupSpec.PlaylistInfo(
                                            playlist,
                                            artworkUrl
                                        )
                                    )
                                },
                                onArtistClick = {
                                    // TODO: Add author page
                                },
                                contentDescription = playlist.attributes.description,
                                sharedTransitionScope = this@SearchScreen,
                                modifier = Modifier.animateItem()
                            )
                        }

                        is SearchUiModel.EpisodeItem -> {
                            val episode = item.episode
                            val showUri = episode.showUri
                            val openShow: () -> Unit = {
                                if (showUri.isNotBlank()) {
                                    backStack.add(Route.ShowScreen(showUri))
                                } else {
                                    scope.launch {
                                        val resolved = viewModel.resolveEpisodeShowUri(episode.id)
                                        if (!resolved.isNullOrBlank()) {
                                            backStack.add(Route.ShowScreen(resolved))
                                        }
                                    }
                                }
                            }

                            SwipeableEpisodeRowConfigured(
                                episode = episode,
                                isPlaybackPlaying = isPlaybackPlaying,
                                onRowClick = {
                                    spirc.load(episode.toOutifyUri())
                                    viewModel.setAudio(episode.toPlayableAudio())
                                },
                                onArtworkClick = openShow,
                                onShowNameClick = openShow,
                                modifier = Modifier.animateItem()
                            )
                        }

                        is SearchUiModel.ShowItem -> {
                            val show = item.show
                            ShowRow(
                                show = show,
                                onRowClick = {
                                    backStack.add(Route.ShowScreen(show.uri))
                                    viewModel.addToHistory(item)
                                },
                                onPublisherClick = {},
                                modifier = Modifier.animateItem()
                            )
                        }
                    }
                }
            }
            }
        }

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomEnd
        ) {
            AnimatedVisibility(
                visible = showScrollToTop,
                enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
                exit = fadeOut() + slideOutVertically(targetOffsetY = { it })
            ) {
                FloatingActionButton(
                    onClick = {
                        scope.launch {
                            listState.scrollToItem(0)
                        }
                    },
                    shape = CircleShape,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier
                        .padding(16.dp)
                        .size(40.dp)
                ) {
                    Icon(
                        Icons.Default.KeyboardArrowUp,
                        contentDescription = "Scroll to top"
                    )
                }
            }
        }
    }

    if (showAdvancedSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAdvancedSheet = false },
            sheetState = sheetState
        ) {
            AdvancedSearchContent(
                onSearch = { advancedQuery ->
                    query = advancedQuery
                    viewModel.onQueryChange(advancedQuery)
                    showAdvancedSheet = false
                },
                onDismiss = { showAdvancedSheet = false }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MaterialSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = true,
    placeholderText: String = "Search Spotify",
) {
    val expanded = false

    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(autoFocus) {
        if (autoFocus) {
            focusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    SearchBar(
        modifier = modifier
            .fillMaxWidth(),
        inputField = {
            SearchBarDefaults.InputField(
                modifier = Modifier.focusRequester(focusRequester),
                query = query,
                onQueryChange = { new ->
                    onQueryChange(new)
                },
                onSearch = {
                    onQueryChange(query)
                    keyboardController?.hide()
                },
                expanded = expanded,
                onExpandedChange = { /* no-op: keep collapsed so results render below */ },
                placeholder = { Text(text = placeholderText) },
                leadingIcon = {
                    Icon(imageVector = Icons.Default.Search, contentDescription = "Search")
                },
                trailingIcon = {
                    if (isLoading) {
                        ContainedLoadingIndicator(modifier = Modifier.size(20.dp))
                    } else {
                        IconButton(onClick = {
                            onQueryChange("")
                        }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear query"
                            )
                        }
                    }
                }
            )
        },
        expanded = expanded,
        onExpandedChange = { /* ignored */ }
    ) {}
}

@Composable
fun FiltersBar(
    modifier: Modifier = Modifier,
    showTracks: Boolean,
    showArtists: Boolean,
    showAlbums: Boolean,
    showPlaylists: Boolean,
    showShows: Boolean,
    showEpisodes: Boolean,
    onToggleTracks: (Boolean) -> Unit,
    onToggleArtists: (Boolean) -> Unit,
    onToggleAlbums: (Boolean) -> Unit,
    onTogglePlaylists: (Boolean) -> Unit,
    onToggleShows: (Boolean) -> Unit,
    onToggleEpisodes: (Boolean) -> Unit,
    chipSpacing: Dp = 8.dp
) {
    val scrollState = rememberScrollState()

    Row(
        modifier = modifier
            .horizontalScroll(scrollState)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(chipSpacing),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Reusable builder for chips
        @Composable
        fun FilterChipWithTick(
            selected: Boolean,
            onClick: () -> Unit,
            label: @Composable () -> Unit
        ) {
            FilterChip(
                selected = selected,
                onClick = onClick,
                label = label,
                shape = RoundedCornerShape(20.dp),
                leadingIcon = {
                    if (selected) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    selectedLabelColor = MaterialTheme.colorScheme.primary,
                    selectedLeadingIconColor = MaterialTheme.colorScheme.primary,
                    // unselected colors fall back to defaults
                )
            )
        }

        FilterChipWithTick(
            selected = showTracks,
            onClick = { onToggleTracks(!showTracks) }
        ) { Text(stringResource(R.string.search_section_tracks)) }

        FilterChipWithTick(
            selected = showArtists,
            onClick = { onToggleArtists(!showArtists) }
        ) { Text(stringResource(R.string.search_section_artists)) }

        FilterChipWithTick(
            selected = showAlbums,
            onClick = { onToggleAlbums(!showAlbums) }
        ) { Text(stringResource(R.string.search_section_albums)) }

        FilterChipWithTick(
            selected = showPlaylists,
            onClick = { onTogglePlaylists(!showPlaylists) }
        ) { Text(stringResource(R.string.search_section_playlists)) }

        FilterChipWithTick(
            selected = showShows,
            onClick = { onToggleShows(!showShows) }
        ) { Text(stringResource(R.string.search_section_shows)) }

        FilterChipWithTick(
            selected = showEpisodes,
            onClick = { onToggleEpisodes(!showEpisodes) }
        ) { Text(stringResource(R.string.search_section_episodes)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdvancedSearchContent(
    onSearch: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember { mutableStateOf("") }
    var artist by remember { mutableStateOf("") }
    var album by remember { mutableStateOf("") }
    var year by remember { mutableStateOf("") }
    var genre by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var matchAll by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Text(
            text = "Advanced Search",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("Title") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = artist,
            onValueChange = { artist = it },
            label = { Text("Artist") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = album,
            onValueChange = { album = it },
            label = { Text("Album") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = year,
            onValueChange = { year = it },
            label = { Text("Year (e.g. 1978 or 1980-1985)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = genre,
            onValueChange = { genre = it },
            label = { Text("Genre") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = label,
            onValueChange = { label = it },
            label = { Text("Label") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Match:")
            Spacer(Modifier.width(12.dp))
            FilterChip(
                selected = matchAll,
                onClick = { matchAll = true },
                label = { Text("All (AND)") }
            )
            Spacer(Modifier.width(8.dp))
            FilterChip(
                selected = !matchAll,
                onClick = { matchAll = false },
                label = { Text("Any (OR)") }
            )
        }

        Spacer(Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = {
                title = ""; artist = ""; album = ""
                year = ""; genre = ""; label = ""
                matchAll = true
            }) { Text("Clear") }
            Spacer(Modifier.width(12.dp))
            Button(onClick = {
                val advancedQuery = buildAdvancedQuery(
                    title, artist, album, year, genre, label, matchAll
                )
                onSearch(advancedQuery)
            }) { Text("Search") }
        }

        Spacer(Modifier.height(16.dp))
    }
}

private fun buildAdvancedQuery(
    title: String,
    artist: String,
    album: String,
    year: String,
    genre: String,
    label: String,
    matchAll: Boolean
): String {
    val parts = mutableListOf<String>()

    fun add(field: String, value: String) {
        if (value.isNotBlank()) {
            val quoted = if (value.contains(" ") && field != "year") "\"$value\"" else value
            parts.add("$field:$quoted")
        }
    }

    add("title", title)
    add("artist", artist)
    add("album", album)
    add("year", year)
    add("genre", genre)
    add("label", label)

    return when {
        parts.isEmpty() -> ""
        parts.size == 1 || matchAll -> parts.joinToString(" ")
        else -> parts.joinToString(" OR ")
    }
}

private fun applyFiltersToSectionedResults(
    results: List<SearchUiModel>,
    showTracks: Boolean,
    showArtists: Boolean,
    showAlbums: Boolean,
    showPlaylists: Boolean,
    showShows: Boolean,
    showEpisodes: Boolean
): List<SearchUiModel> {
    val out = mutableListOf<SearchUiModel>()
    var i = 0
    while (i < results.size) {
        val item = results[i]
        if (item is SearchUiModel.SectionHeader) {
            // collect the section items
            val sectionItems = mutableListOf<SearchUiModel>()
            var j = i + 1
            while (j < results.size && results[j] !is SearchUiModel.SectionHeader) {
                sectionItems.add(results[j])
                j++
            }

            // filter the section items based on selected filters
            val filtered = sectionItems.filter { si ->
                when (si) {
                    is SearchUiModel.TrackItem -> showTracks
                    is SearchUiModel.ArtistItem -> showArtists
                    is SearchUiModel.AlbumItem -> showAlbums
                    is SearchUiModel.PlaylistItem -> showPlaylists
                    is SearchUiModel.ShowItem -> showShows
                    is SearchUiModel.EpisodeItem -> showEpisodes
                    else -> true
                }
            }

            if (filtered.isNotEmpty()) {
                out.add(item) // header
                out.addAll(filtered)
            }

            i = j // skip to next header or end
        } else {
            val include = when (item) {
                is SearchUiModel.TrackItem -> showTracks
                is SearchUiModel.ArtistItem -> showArtists
                is SearchUiModel.AlbumItem -> showAlbums
                is SearchUiModel.PlaylistItem -> showPlaylists
                is SearchUiModel.EpisodeItem -> showEpisodes
                is SearchUiModel.ShowItem -> showShows
                is SearchUiModel.SkeletonItem -> true
                is SearchUiModel.SectionHeader -> true
            }
            if (include) out.add(item)
            i++
        }
    }
    return out
}