package cc.tomko.outify.ui.screens.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SharedTransitionScope
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeExtendedFloatingActionButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import cc.tomko.outify.core.model.Artist
import cc.tomko.outify.core.model.Profile
import cc.tomko.outify.core.model.Track
import cc.tomko.outify.core.model.toPlayableAudio
import cc.tomko.outify.core.model.toSpotifyUri
import cc.tomko.outify.ui.GlobalPopupController
import cc.tomko.outify.ui.PopupSpec
import cc.tomko.outify.ui.components.ArtworkBackground
import cc.tomko.outify.ui.components.AutoScrollingText
import cc.tomko.outify.ui.components.CollapsingHeader
import cc.tomko.outify.ui.components.ErrorScreen
import cc.tomko.outify.ui.components.PlaylistDetailSkeleton
import cc.tomko.outify.ui.components.rememberCollapsingHeaderState
import cc.tomko.outify.ui.components.rows.SwipeableTrackRowConfigured
import cc.tomko.outify.ui.components.user.UserChipAvatar
import cc.tomko.outify.ui.screens.MaterialSearchBar
import cc.tomko.outify.ui.viewmodel.detail.PlaylistDetailViewModel
import cc.tomko.outify.ui.viewmodel.detail.PlaylistUiState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SharedTransitionScope.PlaylistScreen(
    viewModel: PlaylistDetailViewModel,
    onArtworkClick: (track: Track) -> Unit,
    onArtistClick: (artist: Artist) -> Unit,
    onAuthorClick: (profile: Profile) -> Unit,
    onBack: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current

    val uiState by viewModel.uiState.collectAsState()

    when (uiState) {
        is PlaylistUiState.Loading -> {
            PlaylistDetailSkeleton()
        }

        is PlaylistUiState.Error -> {
            ErrorScreen(
                message = (uiState as PlaylistUiState.Error).error,
                onRetry = { viewModel.retry() },
            )
        }

        is PlaylistUiState.Success -> {
            val playlist = (uiState as PlaylistUiState.Success).playlist!!
            val tracks = playlist.contents
            val likedIds by viewModel.likedTrackIds.collectAsState(initial = emptySet())
            val isRefreshing by viewModel.isRefreshing.collectAsState()
            val isSaved by viewModel.isSaved.collectAsState()

            var searchQuery by remember { mutableStateOf("") }
            var showSearch by remember { mutableStateOf(false) }

            val lazyList = rememberLazyListState()
            val currentTrack by viewModel.currentAudio.collectAsState(initial = null)
            val isPlaybackPlaying by viewModel.isPlaying.collectAsState(initial = false)
            val spirc = viewModel.spirc

            var artworkUrl by remember { mutableStateOf("") }
            val authorMap by viewModel.authors.collectAsState()
            var authors by remember { mutableStateOf(emptyList<Profile>()) }
            LaunchedEffect(playlist.uri) {
                artworkUrl = viewModel.getArtworkUrl(playlist)
                authors = viewModel.getAuthors(playlist)
            }
            val showAvatarCount = 4

            val playlistRows = remember(playlist.uri, tracks) {
                viewModel.buildPlaylistRows(playlist)
            }

            val filteredRows = remember(playlistRows, searchQuery, viewModel) {
                if (searchQuery.isBlank()) playlistRows
                else playlistRows.filter { row ->
                    val state = viewModel.getTrackState(row.trackUri)
                    state?.name?.contains(searchQuery, ignoreCase = true) == true ||
                            state?.artists?.any {
                                it.name.contains(
                                    searchQuery,
                                    ignoreCase = true
                                )
                            } == true
                }
            }

            val collapsingState = rememberCollapsingHeaderState()
            val atTop by remember {
                derivedStateOf {
                    lazyList.firstVisibleItemIndex == 0 &&
                            lazyList.firstVisibleItemScrollOffset == 0
                }
            }
            SideEffect { collapsingState.canExpand = atTop }
            val scope = rememberCoroutineScope()

            val isScrolled by remember {
                derivedStateOf {
                    lazyList.firstVisibleItemIndex > 2 ||
                            lazyList.firstVisibleItemScrollOffset > 100
                }
            }
            val showScrollToTop = isScrolled

            LaunchedEffect(lazyList.isScrollInProgress) {
                if (!lazyList.isScrollInProgress) {
                    val canExpand =
                        lazyList.firstVisibleItemIndex == 0 &&
                                lazyList.firstVisibleItemScrollOffset == 0

                    collapsingState.snapIfNeeded(canExpand)
                }
            }

            val playlistUri = playlist.toSpotifyUri()

            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = {
                    viewModel.refresh()
                },
                modifier = Modifier
                    .fillMaxSize()
                    .background(color = MaterialTheme.colorScheme.surface)
                    .nestedScroll(collapsingState.nestedScrollConnection)
            ) {
                val currentTopBarHeightDp =
                    with(density) { collapsingState.height.value.toDp() }

                LazyColumn(
                    state = lazyList,
                    contentPadding = PaddingValues(
                        top = currentTopBarHeightDp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            MaterialSearchBar(
                                query = searchQuery,
                                onQueryChange = { searchQuery = it },
                                isLoading = false,
                                autoFocus = false,
                                placeholderText = "Search tracks..",
                            )
                        }
                    }
                    
                    if(playlistRows.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier.fillParentMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.MusicNote,
                                        contentDescription = null,
                                        modifier = Modifier.size(56.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                    )
                                    Spacer(Modifier.height(16.dp))
                                    Text(
                                        text = "Playlist is empty",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )

                                    Spacer(Modifier.height(16.dp))
                                    Text(
                                        text = "Try adding some tracks into it",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }

                    items(
                        items = filteredRows,
                        key = { it.key },
                        contentType = { "playlist_track_row" }
                    ) { row ->
                        val track by remember(row.trackUri) {
                            viewModel.trackFlow(row.trackUri)
                        }.collectAsState(initial = null)

                        LaunchedEffect(row.trackUri) {
                            if (track == null) viewModel.getOrLoadTrack(row.trackUri)
                        }

                        SwipeableTrackRowConfigured(
                            track = track,
                            currentAudio = currentTrack,
                            isPlaybackPlaying = isPlaybackPlaying,
                            isLiked = track?.id in likedIds,
                            onRowClick = track?.let { t ->
                                remember(t.uri) {
                                    {
                                        spirc.load(playlistUri, t.toSpotifyUri())
                                        // Optimistic UI
                                        viewModel.setAudio(t.toPlayableAudio())
                                    }
                                }
                            },
                            onArtworkClick = track?.let { { onArtworkClick(it) } },
                            onArtistClick = onArtistClick,
                            trailingContent = {
                                val author = authorMap[row.addedBy]

                                author?.let {
                                    UserChipAvatar(
                                        it.imageUrl,
                                        modifier = Modifier
                                            .clickable {
                                                onAuthorClick(it)
                                            }
                                    )
                                }
                            }
                        )
                    }
                }

                CollapsingHeader(
                    collapseFraction = collapsingState.collapseFraction,
                    headerHeight = currentTopBarHeightDp,
                    onBackPressed = onBack,
                    backgroundContent = {
                        ArtworkBackground(
                            artworkUrl = artworkUrl,
                        )
                    },
                    titleContent = {
                        AutoScrollingText(
                            text = playlist.attributes.name,
                            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                            gradientEdgeColor = MaterialTheme.colorScheme.surface,
                        )

                        Row {
                            Box {
                                authors.take(showAvatarCount).forEachIndexed { index, user ->
                                    UserChipAvatar(
                                        artworkUrl = user.imageUrl,
                                        size = 20.dp,
                                        modifier = Modifier
                                            .offset(x = (index * 12).dp)
                                            .zIndex((showAvatarCount - index).toFloat())
                                            .clickable {
                                                onAuthorClick(user)
                                            }
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width((authors.take(showAvatarCount).size * 12).dp))

                            Text(
                                text = "• ${tracks.size} songs",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    },
                    fabContent = {
                        LargeExtendedFloatingActionButton(
                            onClick = {
                                spirc.shuffleLoad(playlist.uri)
                            },
                            shape = MaterialShapes.Cookie9Sided.toShape()
                        ) {
                            Icon(Icons.Rounded.Shuffle, null)
                        }
                    },
                    actionButtonContent = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledIconButton(onClick = {
                                GlobalPopupController.show(
                                    PopupSpec.ModifyPlaylist(
                                        playlistId = playlist.id,
                                        name = playlist.attributes.name,
                                        description = playlist.attributes.description,
                                        collaborative = playlist.attributes.isCollaborative,
                                    )
                                )
                            }) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = "Edit playlist"
                                )
                            }
                            FilledIconButton(onClick = { viewModel.toggleSave() }) {
                                Icon(
                                    imageVector = if (isSaved) Icons.Rounded.Favorite else Icons.Filled.FavoriteBorder,
                                    contentDescription = if (isSaved) "Unfavorite" else "Favorite"
                                )
                            }
                            FilledIconButton(onClick = {
                                GlobalPopupController.show(
                                    PopupSpec.PlaylistInfo(
                                        playlist,
                                        artworkUrl = artworkUrl
                                    )
                                )
                            }) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "More information"
                                )
                            }
                        }
                    }
                )

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
                                    lazyList.animateScrollToItem(0)
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
        }
    }
}