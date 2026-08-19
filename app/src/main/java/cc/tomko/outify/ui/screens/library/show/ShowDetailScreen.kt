package cc.tomko.outify.ui.screens.library.show

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeExtendedFloatingActionButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import cc.tomko.outify.ALBUM_COVER_URL
import cc.tomko.outify.core.model.CoverSize
import cc.tomko.outify.core.model.getCover
import cc.tomko.outify.core.model.sharedTransitionKey
import cc.tomko.outify.core.model.toSpotifyUri
import cc.tomko.outify.ui.components.AlbumDetailSkeleton
import cc.tomko.outify.ui.components.ArtworkBackground
import cc.tomko.outify.ui.components.CollapsingHeader
import cc.tomko.outify.ui.components.ErrorScreen
import cc.tomko.outify.ui.components.rememberCollapsingHeaderState
import cc.tomko.outify.ui.components.rows.SwipeableEpisodeRowConfigured
import cc.tomko.outify.ui.viewmodel.library.ShowDetailViewModel
import kotlinx.coroutines.launch

private const val LOADING_MORE_SKELETON_COUNT = 3

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SharedTransitionScope.ShowDetailScreen(
    viewModel: ShowDetailViewModel,
    onBack: () -> Unit,
    artworkClick: (uri: String) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()

    val density = LocalDensity.current

    when {
        uiState.isLoading -> {
            AlbumDetailSkeleton()
        }

        uiState.error != null -> {
            ErrorScreen(
                message = uiState.error!!,
                onRetry = { viewModel.retry() },
            )
        }

        uiState.show != null -> {
            val show = uiState.show!!
            val totalEpisodes = show.episodes.size
            val episodes = uiState.episodes
            val isLoadingMore = uiState.isLoadingMore
            val hasMore = uiState.hasMore

            val artworkUrl = ALBUM_COVER_URL + show.getCover(CoverSize.LARGE)?.uri
            val currentAudio by viewModel.currentAudio.collectAsState(initial = null)
            val isPlaybackPlaying by viewModel.isPlaying.collectAsState(initial = false)
            val spirc = viewModel.spirc

            val likedEpisodeIds by viewModel.likedEpisodeIds.collectAsState()
            val isSaved by viewModel.isSaved.collectAsState()

            val lazyList = rememberLazyListState()
            val scope = rememberCoroutineScope()

            val collapsingState = rememberCollapsingHeaderState()
            val atTop by remember {
                derivedStateOf {
                    lazyList.firstVisibleItemIndex == 0 &&
                            lazyList.firstVisibleItemScrollOffset == 0
                }
            }
            SideEffect { collapsingState.canExpand = atTop }

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

            // Procedural / infinite-scroll loading: fire once the user is
            // within the last 5 rows of what's currently loaded.
            //
            // IMPORTANT: episodes/hasMore/isLoadingMore are plain locals, not
            // State objects, so they must be passed as `remember` keys —
            // otherwise this derivedStateOf freezes on whatever those values
            // were the first time this branch composed, and never notices
            // that more episodes loaded or hasMore flipped.
            val shouldLoadMore by remember(episodes.size, hasMore, isLoadingMore) {
                derivedStateOf {
                    val lastVisible =
                        lazyList.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                    hasMore && !isLoadingMore && lastVisible >= episodes.size - 5
                }
            }
            LaunchedEffect(shouldLoadMore) {
                if (shouldLoadMore) {
                    viewModel.loadMoreEpisodes()
                }
            }

            Box(
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
                    modifier = Modifier
                        .fillMaxSize()
                ) {
                    item {
                        Text(
                            text = "Episodes",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
                        )
                    }

                    items(episodes, key = { episode -> "show_episode_${episode.uri}" }) { episode ->
                        SwipeableEpisodeRowConfigured(
                            episode = episode,
                            isLoaded = currentAudio?.uri == episode.uri,
                            isPlaybackPlaying = isPlaybackPlaying,
                            isLiked = episode.id in likedEpisodeIds,
                            onRowClick = remember(episode.uri) {
                                {
                                    spirc.load(show.toSpotifyUri(), episode.toSpotifyUri())
                                    viewModel.setEpisode(episode)
                                }
                            },
                            onArtworkClick = { artworkClick(episode.uri) }
                        )
                    }

                    if (isLoadingMore) {
                        items(
                            count = LOADING_MORE_SKELETON_COUNT,
                            key = { index -> "show_episode_skeleton_$index" }
                        ) {
                            SwipeableEpisodeRowConfigured(episode = null)
                        }
                    }
                }

                CollapsingHeader(
                    collapseFraction = collapsingState.collapseFraction,
                    headerHeight = currentTopBarHeightDp,
                    onBackPressed = onBack,
                    backgroundContent = {
                        ArtworkBackground(
                            artworkUrl = artworkUrl,
                            modifier = Modifier
                                .sharedBounds(
                                    rememberSharedContentState(show.sharedTransitionKey()),
                                    LocalNavAnimatedContentScope.current
                                )
                        )
                    },
                    titleContent = {
                        Text(
                            text = show.name,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            text = "${show.publisher} • ${totalEpisodes} episodes",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    fabContent = {
                        LargeExtendedFloatingActionButton(
                            onClick = {
                                episodes.firstOrNull()?.let { latest ->
                                    spirc.load(show.toSpotifyUri(), latest.toSpotifyUri())
                                    viewModel.setEpisode(latest)
                                }
                            },
                            shape = MaterialShapes.Cookie9Sided.toShape()
                        ) {
                            Icon(Icons.Rounded.PlayArrow, null)
                        }
                    },
                    actionButtonContent = {
                        FilledIconButton(onClick = { viewModel.toggleSave() }) {
                            Icon(
                                imageVector = if (isSaved) Icons.Rounded.Favorite else Icons.Filled.FavoriteBorder,
                                contentDescription = if (isSaved) "Unfollow" else "Follow"
                            )
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
                                    lazyList.scrollToItem(0)
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