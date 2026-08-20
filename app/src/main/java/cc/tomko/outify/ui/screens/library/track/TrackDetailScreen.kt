package cc.tomko.outify.ui.screens.library.track

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import cc.tomko.outify.ALBUM_COVER_URL
import cc.tomko.outify.core.model.Album
import cc.tomko.outify.core.model.Artist
import cc.tomko.outify.core.model.CoverSize
import cc.tomko.outify.core.model.LyricLine
import cc.tomko.outify.core.model.getCover
import cc.tomko.outify.core.model.sharedTransitionKey
import cc.tomko.outify.core.model.toSpotifyUri
import cc.tomko.outify.ui.components.ArtworkBackground
import cc.tomko.outify.ui.components.CollapsingHeader
import cc.tomko.outify.ui.components.ErrorScreen
import cc.tomko.outify.ui.components.SmartImage
import cc.tomko.outify.ui.components.TrackDetailSkeleton
import cc.tomko.outify.ui.components.rememberCollapsingHeaderState
import cc.tomko.outify.ui.components.rows.SwipeableTrackRowConfigured
import cc.tomko.outify.ui.viewmodel.detail.TrackDetailViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SharedTransitionScope.TrackDetailScreen(
    viewModel: TrackDetailViewModel,
    onBack: () -> Unit,
    artistClick: (uri: String) -> Unit,
    artworkClick: (album: Album?) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()

    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    when {
        uiState.isLoading -> {
            TrackDetailSkeleton()
        }

        uiState.error != null -> {
            ErrorScreen(
                message = uiState.error!!,
                onRetry = { viewModel.retry() },
            )
        }

        uiState.track != null -> {
            val track = uiState.track!!
            val lyrics = uiState.lyrics
            val artworkUrl = ALBUM_COVER_URL + track.album?.getCover(CoverSize.LARGE)?.uri
            val currentTrack by viewModel.currentAudio.collectAsState(initial = null)
            val isPlaybackPlaying by viewModel.isPlaying.collectAsState(initial = false)
            val spirc = viewModel.spirc

            val likedTrackIds by viewModel.likedTrackIds.collectAsState()

            val lazyList = rememberLazyListState()

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
                    modifier = Modifier.fillMaxSize()
                ) {
                    item {
                        Text(
                            text = "Track",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
                        )
                    }

                    item(key = "track_row") {
                        SwipeableTrackRowConfigured(
                            track = track,
                            currentAudio = currentTrack,
                            isPlaybackPlaying = isPlaybackPlaying,
                            onRowClick = remember(track.uri) {
                                {
                                    spirc.load(track.toSpotifyUri())
                                    viewModel.setTrack(track)
                                }
                            },
                            isLiked = track.id in likedTrackIds,
                            onArtistClick = { artistClick(it.uri) },
                            onArtworkClick = { artworkClick(track.album) },
                        )
                    }

                    if (lyrics.isNotEmpty()) {
                        item {
                            Spacer(modifier = Modifier.height(24.dp))
                            Text(
                                text = "Lyrics",
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
                            )
                        }

                        item(key = "lyrics") {
                            LyricsSection(lyrics = lyrics)
                        }
                    }

                    if (track.artists.isNotEmpty()) {
                        item {
                            Spacer(modifier = Modifier.height(24.dp))
                            Text(
                                text = "Artists",
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
                            )
                        }

                        items(
                            items = track.artists,
                            key = { "artist_${it.uri}" }
                        ) { artist ->
                            ArtistRow(
                                artist = artist,
                                onClick = { artistClick(artist.uri) },
                            )
                        }
                    }

                    item {
                        Spacer(modifier = Modifier.height(48.dp))
                    }
                }

                CollapsingHeader(
                    collapseFraction = collapsingState.collapseFraction,
                    headerHeight = currentTopBarHeightDp,
                    onBackPressed = onBack,
                    backgroundContent = {
                        ArtworkBackground(
                            artworkUrl = artworkUrl,
                            modifier = track.album?.let { album ->
                                Modifier.sharedBounds(
                                    rememberSharedContentState(album.sharedTransitionKey()),
                                    LocalNavAnimatedContentScope.current
                                )
                            } ?: Modifier
                        )
                    },
                    titleContent = {
                        Text(
                            text = track.name,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )

                        Text(
                            text = buildString {
                                append(track.artists.joinToString { it.name })
                                track.album?.let { album ->
                                    append(" • ${album.name}")
                                }
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    fabContent = {
                        LargeExtendedFloatingActionButton(
                            onClick = {
                                spirc.load(track.toSpotifyUri())
                                viewModel.setTrack(track)
                            },
                            shape = MaterialShapes.Cookie9Sided.toShape()
                        ) {
                            Icon(Icons.Rounded.PlayArrow, null)
                        }
                    },
                    actionButtonContent = {
                        FilledIconButton(onClick = { viewModel.toggleLike(track.uri) }) {
                            Icon(
                                imageVector = if (track.id in likedTrackIds)
                                    Icons.Rounded.Favorite else Icons.Filled.FavoriteBorder,
                                contentDescription = if (track.id in likedTrackIds)
                                    "Unlike" else "Like"
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

@Composable
private fun LyricsSection(lyrics: List<LyricLine>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        lyrics.forEach { line ->
            Text(
                text = line.text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Start,
            )
        }
    }
}

@Composable
private fun ArtistRow(
    artist: Artist,
    onClick: () -> Unit,
) {
    val artistCoverUrl = ALBUM_COVER_URL + artist.getCover(CoverSize.MEDIUM)?.uri

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(56.dp)
            ) {
                SmartImage(
                    url = artistCoverUrl,
                    contentDescription = artist.name,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Text(
                text = artist.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
