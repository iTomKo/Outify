package cc.tomko.outify.ui.components.bottomsheet

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.tomko.outify.ALBUM_COVER_URL
import cc.tomko.outify.R
import cc.tomko.outify.core.model.CoverSize
import cc.tomko.outify.core.model.Playlist
import cc.tomko.outify.core.model.Track
import cc.tomko.outify.core.model.getCover
import cc.tomko.outify.data.setting.LocalUiSettings
import cc.tomko.outify.ui.components.SmartImage
import cc.tomko.outify.ui.viewmodel.bottomsheet.AddToPlaylistViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistBottomSheet(
    viewModel: AddToPlaylistViewModel,
    tracks: List<Track>,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = false,
    )

    val coroutineScope = rememberCoroutineScope()

    val playlists by viewModel.ownedPlaylists.collectAsState(initial = emptyList())

    val trackIds = remember(tracks) { tracks.map { it.id }.toSet() }

    var selectedPlaylist by remember { mutableStateOf<Playlist?>(null) }
    var isRemoveMode by remember { mutableStateOf(false) }

    if (selectedPlaylist != null) {
        TrackSelectionBottomSheet(
            viewModel = viewModel,
            tracks = tracks,
            playlist = selectedPlaylist!!,
            isRemoveMode = isRemoveMode,
            onDismiss = { selectedPlaylist = null },
            onConfirm = {
                selectedPlaylist = null
                onDismiss?.invoke()
            }
        )
    } else {
        ModalBottomSheet(
            onDismissRequest = {
                coroutineScope.launch {
                    sheetState.hide()
                    onDismiss?.invoke()
                }
            },
            sheetState = sheetState,
            modifier = modifier,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp)
            ) {
                // Header with track info
                TrackInfoHeader(tracks = tracks)

                Spacer(modifier = Modifier.height(16.dp))

                // Action buttons
                if (tracks.size == 1) {
                    Text(
                        text = stringResource(R.string.queue_your_playlists),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    Text(
                        text = stringResource(R.string.count_tracks, tracks.size),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Playlist list
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(
                        items = playlists,
                        key = { it.playlist.id }
                    ) { playlistUi ->
                        val playlist = playlistUi.playlist
                        val existingCount = playlist.contents.count { it.id in trackIds }
                        val isInPlaylist = existingCount > 0

                        PlaylistItem(
                            playlistName = playlist.attributes.name,
                            artworkUrl = playlistUi.artworkUrl,
                            trackCount = playlist.length,
                            existingCount = existingCount,
                            isInPlaylist = isInPlaylist,
                            onAddClick = {
                                if (tracks.size == 1) {
                                    viewModel.addTrackToPlaylist(tracks.first(), playlist)
                                    onDismiss?.invoke()
                                } else {
                                    isRemoveMode = false
                                    selectedPlaylist = playlist
                                }
                            },
                            onRemoveClick = {
                                if (tracks.size == 1) {
                                    viewModel.removeTrackFromPlaylist(tracks.first(), playlist)
                                    onDismiss?.invoke()
                                } else {
                                    isRemoveMode = true
                                    selectedPlaylist = playlist
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TrackInfoHeader(tracks: List<Track>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        if (tracks.size == 1) {
            val track = tracks.first()
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.size(56.dp)
                ) {
                    val artworkUrl = remember(track) {
                        ALBUM_COVER_URL + (track.album?.getCover(CoverSize.MEDIUM)?.uri ?: "")
                    }
                    SmartImage(
                        url = artworkUrl,
                        contentDescription = stringResource(R.string.common_artwork),
                        modifier = Modifier.fillMaxSize(),
                        monochrome = LocalUiSettings.current.monochromeTracks
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = track.artists.joinToString(", ") { it.name },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        } else {
            // Multiple tracks - show preview
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                tracks.take(3).forEach { track ->
                    val artworkUrl = remember(track) {
                        ALBUM_COVER_URL + (track.album?.getCover(CoverSize.SMALL)?.uri ?: "")
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.size(48.dp)
                    ) {
                        if (artworkUrl.isNotBlank()) {
                            SmartImage(
                                url = artworkUrl,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                monochrome = LocalUiSettings.current.monochromeTracks
                            )
                        } else {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.MusicNote,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.queue_tracks_selected, tracks.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PlaylistItem(
    playlistName: String,
    artworkUrl: String?,
    trackCount: Int,
    existingCount: Int,
    isInPlaylist: Boolean,
    onAddClick: () -> Unit,
    onRemoveClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(
            containerColor = if (isInPlaylist)
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        ListItem(
            modifier = Modifier.clickable {
                if (isInPlaylist) onRemoveClick() else onAddClick()
            },
            headlineContent = {
                Text(
                    text = playlistName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            supportingContent = {
                Text(
                    text = if (isInPlaylist) "$existingCount track${if (existingCount > 1) "s" else ""} in playlist" else "$trackCount tracks",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            leadingContent = {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.size(48.dp)
                ) {
                    if (!artworkUrl.isNullOrBlank()) {
                        SmartImage(
                            url = artworkUrl,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            monochrome = LocalUiSettings.current.monochromeImages
                        )
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            trailingContent = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (!isInPlaylist) {
                        FilledTonalButton(
                            onClick = onAddClick,
                            modifier = Modifier.height(36.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = stringResource(R.string.common_add),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.common_add), style = MaterialTheme.typography.labelMedium)
                        }
                    } else {
                        Button(
                            onClick = onRemoveClick,
                            modifier = Modifier.height(36.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Remove,
                                contentDescription = stringResource(R.string.common_remove),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.common_remove), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            },
            colors = ListItemDefaults.colors(
                containerColor = androidx.compose.ui.graphics.Color.Transparent
            )
        )
    }
}