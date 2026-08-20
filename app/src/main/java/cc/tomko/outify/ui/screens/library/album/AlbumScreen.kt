package cc.tomko.outify.ui.screens.library.album

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
import androidx.compose.material.icons.rounded.Shuffle
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
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
import cc.tomko.outify.ui.components.rows.SwipeableTrackRowConfigured
import cc.tomko.outify.ui.viewmodel.detail.AlbumDetailViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SharedTransitionScope.AlbumDetailScreen(
    viewModel: AlbumDetailViewModel,
    onBack: () -> Unit,
    artistClick: (uri: String) -> Unit,
    artworkClick: (uri: String) -> Unit,
    highlightTrackUri: String? = null,
) {
    val uiState by viewModel.uiState.collectAsState()

    val coroutineScope = rememberCoroutineScope()
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

        uiState.album != null -> {
            val album = uiState.album!!
            val tracks = uiState.tracks
            val artworkUrl = ALBUM_COVER_URL + album.getCover(CoverSize.LARGE)?.uri
            val currentTrack by viewModel.currentAudio.collectAsState(initial = null)
            val isPlaybackPlaying by viewModel.isPlaying.collectAsState(initial = false)
            val spirc = viewModel.spirc

            val likedTracksId by viewModel.likedTrackIds.collectAsState()
            val isSaved by viewModel.isSaved.collectAsState()

            val lazyList = rememberLazyListState()

            val collapsingState = rememberCollapsingHeaderState()
            val atTop by remember {
                derivedStateOf {
                    lazyList.firstVisibleItemIndex == 0 &&
                            lazyList.firstVisibleItemScrollOffset == 0
                }
            }
            SideEffect { collapsingState.canExpand = atTop }
            val scope = rememberCoroutineScope()

            var highlightedUri by remember { mutableStateOf(highlightTrackUri) }

            LaunchedEffect(highlightedUri) {
                if (highlightedUri != null) {
                    delay(2500)
                    highlightedUri = null
                }
            }

            LaunchedEffect(highlightTrackUri, tracks) {
                if (highlightTrackUri != null && tracks.isNotEmpty()) {
                    val index = tracks.indexOfFirst { it.uri == highlightTrackUri }
                    if (index >= 0) {
                        lazyList.scrollToItem(index + 1)
                    }
                }
            }

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

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(color = MaterialTheme.colorScheme.surface)
            ) {
                val maxHeightDp = with(density) { collapsingState.maxHeightPx.toDp() }

                LazyColumn(
                    state = lazyList,
                    contentPadding = PaddingValues(
                        top = maxHeightDp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationY = -(collapsingState.maxHeightPx - collapsingState.height)
                        }
                        .nestedScroll(collapsingState.nestedScrollConnection)
                ) {
                    item {
                        Text(
                            text = "Tracks",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
                        )
                    }

                    items(tracks, key = { track -> "album_song_${track.uri}" }) { track ->
                        val isHighlighted = track.uri == highlightedUri
                        SwipeableTrackRowConfigured(
                            track = track,
                            modifier = if (isHighlighted)
                                Modifier.background(
                                    MaterialTheme.colorScheme.primaryContainer
                                ) else Modifier,
                            currentAudio = currentTrack,
                            isPlaybackPlaying = isPlaybackPlaying,
                            onRowClick = remember(track.uri) {
                                {
                                    spirc.load(album.toSpotifyUri(), track.toSpotifyUri())
                                    highlightedUri = null
                                    viewModel.setTrack(track)
                                }
                            },
                            isLiked = track.id in likedTracksId,
                            onArtistClick = { artistClick(it.uri) },
                            onArtworkClick = { artworkClick(track.uri) }
                        )
                    }
                }

                CollapsingHeader(
                    state = collapsingState,
                    onBackPressed = onBack,
                    backgroundContent = {
                        ArtworkBackground(
                            artworkUrl = artworkUrl,
                            modifier = Modifier
                                .sharedBounds(
                                    rememberSharedContentState(album.sharedTransitionKey()),
                                    LocalNavAnimatedContentScope.current
                                )
                        )
                    },
                    titleContent = {
                        Text(
                            text = album.name,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            text = "${album.artists.joinToString { it.name }} • ${tracks.size} songs",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    fabContent = {
                        LargeExtendedFloatingActionButton(
                            onClick = {
                                spirc.shuffleLoad(album.uri)
                            },
                            shape = MaterialShapes.Cookie9Sided.toShape()
                        ) {
                            Icon(Icons.Rounded.Shuffle, null)
                        }
                    },
                    actionButtonContent = {
                        FilledIconButton(onClick = { viewModel.toggleSave() }) {
                            Icon(
                                imageVector = if (isSaved) Icons.Rounded.Favorite else Icons.Filled.FavoriteBorder,
                                contentDescription = if (isSaved) "Unfavorite" else "Favorite"
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