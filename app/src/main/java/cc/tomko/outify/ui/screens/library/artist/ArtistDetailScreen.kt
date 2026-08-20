package cc.tomko.outify.ui.screens.library.artist

import androidx.compose.animation.SharedTransitionScope
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import cc.tomko.outify.ALBUM_COVER_URL
import cc.tomko.outify.core.model.Album
import cc.tomko.outify.core.model.Artist
import cc.tomko.outify.core.model.CoverSize
import cc.tomko.outify.core.model.Track
import cc.tomko.outify.core.model.getCover
import cc.tomko.outify.core.model.sharedTransitionKey
import cc.tomko.outify.core.model.toSpotifyUri
import cc.tomko.outify.data.setting.LocalUiSettings
import cc.tomko.outify.ui.GlobalPopupController
import cc.tomko.outify.ui.PopupSpec
import cc.tomko.outify.ui.components.ArtistDetailSkeleton
import cc.tomko.outify.ui.components.ArtworkBackground
import cc.tomko.outify.ui.components.CollapsingHeader
import cc.tomko.outify.ui.components.ErrorScreen
import cc.tomko.outify.ui.components.SmartImage
import cc.tomko.outify.ui.components.bottomsheet.ArtistLikedTracksBottomSheet
import cc.tomko.outify.ui.components.rememberCollapsingHeaderState
import cc.tomko.outify.ui.components.rows.SwipeableTrackRowConfigured
import cc.tomko.outify.ui.viewmodel.detail.ArtistDetailViewModel
import cc.tomko.outify.ui.viewmodel.detail.ArtistUiState
import coil3.compose.AsyncImage

/**
 * Shout out to PixelPlay.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SharedTransitionScope.ArtistDetailScreen(
    viewModel: ArtistDetailViewModel,
    onArtworkClick: (Track) -> Unit,
    onAlbumClick: (Album) -> Unit,
    onArtistClick: (Artist) -> Unit,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()

    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current

    var showLikedSheet by remember { mutableStateOf(false) }

    when (uiState) {
        ArtistUiState.Loading -> {
            ArtistDetailSkeleton()
        }

        is ArtistUiState.Error -> {
            ErrorScreen(
                message = (uiState as ArtistUiState.Error).message,
                onRetry = { viewModel.retry() },
            )
        }

        is ArtistUiState.Success -> {
            val artist = (uiState as ArtistUiState.Success).artist
            val isContentLoading by viewModel.isContentLoading.collectAsState()
            val artworkUrl = ALBUM_COVER_URL + artist.getCover(CoverSize.LARGE)?.uri
            val spirc = viewModel.spirc

            val likedTracks by viewModel.likedTracks.collectAsState()
            val likedTrackCount = likedTracks.size
            val likedTrackIds by viewModel.likedTrackIds.collectAsState(initial = emptySet())
            val isSaved by viewModel.isSaved.collectAsState()

            val albums by viewModel.albums.collectAsState()

            val popularTracks by viewModel.popularTracks.collectAsState()

            val currentTrack by viewModel.currentAudio.collectAsState(initial = null)
            val isPlaybackPlaying by viewModel.isPlaying.collectAsState(initial = false)

            val lazyList = rememberLazyListState()

            val collapsingState = rememberCollapsingHeaderState()
            val atTop by remember {
                derivedStateOf {
                    lazyList.firstVisibleItemIndex == 0 &&
                            lazyList.firstVisibleItemScrollOffset == 0
                }
            }
            SideEffect { collapsingState.canExpand = atTop }

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

                val topPadding = currentTopBarHeightDp + if (likedTrackCount > 0) 8.dp else 0.dp

                if (isContentLoading) {
                    ArtistDetailSkeleton(
                        modifier = Modifier.fillMaxSize(),
                        trackCount = 5,
                        albumCount = 4,
                        topPadding = topPadding
                    )
                }

                LazyColumn(
                    state = lazyList,
                    contentPadding = PaddingValues(
                        top = topPadding,
                    ),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .fillMaxSize()
                ) {
                    item {
                        Text(
                            text = "Popular tracks",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
                        )
                    }

                    if (likedTrackCount > 0) {
                        item {
                            ArtistTracksHeader(
                                likedCount = likedTrackCount,
                                artworkUrl = artworkUrl,
                                onClick = {
                                    showLikedSheet = true
                                },
                                previewTracks = likedTracks
                            )
                        }
                    }

                    items(popularTracks, key = { track -> track.uri }) { track ->
                        SwipeableTrackRowConfigured(
                            track = track,
                            currentAudio = currentTrack,
                            isLiked = track.id in likedTrackIds,
                            isPlaybackPlaying = isPlaybackPlaying,
                            showAlbumName = true,
                            onRowClick = remember(track.uri) {
                                {
                                    spirc.load(artist.toSpotifyUri(), track.toSpotifyUri())
                                    // Optimistic UI
                                    viewModel.setTrack(track)
                                }
                            },
                            onArtistClick = { onArtistClick(it) },
                            onArtworkClick = { onArtworkClick(track) },
                        )
                    }

                    if (albums.isNotEmpty()) {
                        item {
                            Spacer(modifier = Modifier.height(48.dp))

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Albums",
                                    style = MaterialTheme.typography.bodyLarge
                                )

                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = "See all albums",
                                    modifier = Modifier
                                        .clickable {
                                        }
                                )
                            }

                            val albumImageSize = 84.dp

                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                            ) {
                                items(
                                    items = albums,
                                    key = { album -> "artist_album_${album.uri}" }
                                ) { album ->
                                    AlbumCard(
                                        album = album,
                                        size = albumImageSize,
                                        onClick = {
                                            onAlbumClick(album)
                                        },
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(48.dp))
                        }
                    }
                }

                if (showLikedSheet) {
                    ArtistLikedTracksBottomSheet(
                        viewModel = viewModel,
                        onArtworkClick = onArtworkClick,
                        onDismiss = { showLikedSheet = false }
                    )
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
                        Text(
                            text = artist.name,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            text = "Artist • $likedTrackCount liked songs",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    fabContent = {
                        LargeExtendedFloatingActionButton(
                            onClick = {
                                spirc.shuffleLoad(artist.uri)
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
                                contentDescription = if (isSaved) "Unfollow" else "Follow"
                            )
                        }
                        FilledIconButton(onClick = {
                            GlobalPopupController.show(
                                PopupSpec.ArtistInfo(
                                    artist = artist,
                                    isSaved = isSaved,
                                    onToggleSave = { viewModel.toggleSave() },
                                )
                            )
                        }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "More information"
                            )
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun ArtistTracksHeader(
    likedCount: Int,
    artworkUrl: String?,
    modifier: Modifier = Modifier,
    imageSize: Dp = 48.dp,
    onClick: () -> Unit = {},
    previewTracks: List<Track> = emptyList(),
) {
    val previewSize = 36.dp
    val previewsToShow = previewTracks.take(3)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        tonalElevation = 6.dp,
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // artwork + heart overlay
            Box(
                modifier = Modifier
                    .size(imageSize)
                    .clip(RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (!artworkUrl.isNullOrBlank()) {
                    SmartImage(
                        url = artworkUrl,
                        contentDescription = "Liked songs artwork",
                        modifier = Modifier.fillMaxSize(),
                        monochrome = LocalUiSettings.current.monochromeTracks
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.MusicNote,
                            contentDescription = null
                        )
                    }
                }

                // Full overlay scrim
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(Color.Black.copy(alpha = 0.35f))
                )

                // Centered heart icon
                Icon(
                    imageVector = Icons.Filled.Favorite,
                    contentDescription = "Liked",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Title + subtitle
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
            ) {
                Text(
                    text = "Liked Songs",
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "You have $likedCount liked songs",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (previewsToShow.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    previewsToShow.forEach { track ->
                        val previewArtwork =
                            ALBUM_COVER_URL + (track.album?.getCover(CoverSize.SMALL)?.uri ?: "")
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            tonalElevation = 2.dp,
                            modifier = Modifier
                                .size(previewSize)
                        ) {
                            if (previewArtwork.isNotBlank()) {
                                AsyncImage(
                                    model = previewArtwork,
                                    contentDescription = "Preview artwork",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.MusicNote,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // trailing arrow
            IconButton(onClick = onClick) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "Open liked songs"
                )
            }
        }
    }
}

@Composable
fun SharedTransitionScope.AlbumCard(
    album: Album,
    size: Dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    val artworkUri = remember(album) {
        ALBUM_COVER_URL + (album.getCover(CoverSize.MEDIUM)?.uri ?: "")
    }

    Column(
        modifier = modifier
            .width(size)
            .clickable(onClick = onClick)
    ) {
        Surface(
            tonalElevation = 4.dp,
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Box(
                modifier = Modifier
                    .size(size)
            ) {
                if (artworkUri.isNotBlank()) {
                    SmartImage(
                        url = artworkUri,
                        contentDescription = "Album artwork",
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(8.dp))
                            .sharedBounds(
                                rememberSharedContentState(album.sharedTransitionKey()),
                                LocalNavAnimatedContentScope.current
                            ),
                        monochrome = LocalUiSettings.current.monochromeAlbums
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.MusicNote,
                            contentDescription = null
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    MaterialTheme.colorScheme.surface.copy(alpha = 0.08f)
                                )
                            )
                        )
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = album.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = Int.MAX_VALUE,
            overflow = TextOverflow.Clip,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(4.dp))
    }
}
