package cc.tomko.outify.ui.components.bottomsheet

import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.tomko.outify.ALBUM_COVER_URL
import cc.tomko.outify.core.model.CoverSize
import cc.tomko.outify.core.model.OutifyUri
import cc.tomko.outify.core.model.Track
import cc.tomko.outify.core.model.getCover
import cc.tomko.outify.core.model.toSpotifyUri
import cc.tomko.outify.data.setting.LocalUiSettings
import cc.tomko.outify.ui.components.SmartImage
import cc.tomko.outify.ui.components.rows.SwipeableTrackRowConfigured
import cc.tomko.outify.ui.viewmodel.detail.ArtistDetailViewModel
import cc.tomko.outify.ui.viewmodel.detail.ArtistUiState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedTransitionScope.ArtistLikedTracksBottomSheet(
    viewModel: ArtistDetailViewModel,
    onArtworkClick: (Track) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = false,
    )

    val coroutineScope = rememberCoroutineScope()

    ModalBottomSheet(
        onDismissRequest = {
            coroutineScope.launch {
                onDismiss()
                sheetState.hide()
            }
        },
        sheetState = sheetState,
        modifier = modifier,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        when (uiState) {
            ArtistUiState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            is ArtistUiState.Error -> {
                val msg = (uiState as ArtistUiState.Error).message
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(text = "Error", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = msg, color = MaterialTheme.colorScheme.error)
                }
            }

            is ArtistUiState.Success -> {
                val artistState = (uiState as ArtistUiState.Success).artist
                val artworkUrl =
                    ALBUM_COVER_URL + (artistState.getCover(CoverSize.LARGE)?.uri ?: "")
                val likedTracks by viewModel.likedTracks.collectAsState()
                val currentTrack by viewModel.currentAudio.collectAsState(initial = null)
                val isPlaybackPlaying by viewModel.isPlaying.collectAsState(initial = false)
                val spirc = viewModel.spirc

                val context = OutifyUri.ArtistLiked(artistState.id)

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                ) {
                    // Header row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.size(56.dp)
                            ) {
                                if (artworkUrl.isNotBlank()) {
                                    SmartImage(
                                        url = artworkUrl,
                                        contentDescription = "Artist artwork",
                                        modifier = Modifier.fillMaxSize(),
                                        monochrome = LocalUiSettings.current.monochromeArtists
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column {
                                Text(
                                    text = artistState.name,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "• ${likedTracks.size} liked songs",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { spirc.shuffleLoad(context.toUriString()) }) {
                                Icon(Icons.Rounded.Shuffle, contentDescription = "Shuffle")
                            }
                            IconButton(onClick = { spirc.load(context) }) {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = "Play in order")
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(thickness = 2.dp)

                    // Track list
                    val listState = rememberLazyListState()
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        items(likedTracks, key = { t -> "liked_song_${t.uri}" }) { track ->
                            SwipeableTrackRowConfigured(
                                track = track,
                                currentAudio = currentTrack,
                                isPlaybackPlaying = isPlaybackPlaying,
                                showAlbumName = true,
                                onRowClick = remember(track.uri) {
                                    {
                                        spirc.load(context, track.toSpotifyUri())
                                        // Optimistic UI
                                        viewModel.setTrack(track)
                                    }
                                },
                                onArtworkClick = {
                                    onArtworkClick(track)
                                },
                                onArtistClick = { _ -> },
                            )
                        }
                    }
                }
            }
        }
    }
}