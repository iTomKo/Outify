package cc.tomko.outify.ui.components.bottomsheet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
fun TrackSelectionBottomSheet(
    viewModel: AddToPlaylistViewModel,
    tracks: List<Track>,
    playlist: Playlist,
    isRemoveMode: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = false,
    )

    val coroutineScope = rememberCoroutineScope()

    val playlistTrackIds = remember(playlist.contents) {
        playlist.contents.map { it.id }.toSet()
    }

    var selectedTrackIds by remember(tracks) {
        mutableStateOf(tracks.map { it.id }.toSet())
    }

    val tracksToProcess = tracks.filter { it.id in selectedTrackIds }
    val alreadyPresentCount = tracks.count { it.id in playlistTrackIds }

    ModalBottomSheet(
        onDismissRequest = {
            coroutineScope.launch {
                sheetState.hide()
                onDismiss()
            }
        },
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = playlist.attributes.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (isRemoveMode) "Select tracks to remove" else "Select tracks to add",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (tracksToProcess.isNotEmpty()) {
                    Button(
                        onClick = {
                            if (isRemoveMode) {
                                viewModel.removeFromPlaylist(tracksToProcess, playlist)
                            } else {
                                viewModel.addToPlaylist(tracksToProcess, playlist)
                            }
                            onConfirm()
                        },
                        modifier = Modifier
                            .padding(4.dp)
                            .clip(RoundedCornerShape(16.dp))
                    ) {
                        Text(
                            text = if (isRemoveMode) "Remove ${tracksToProcess.size}" else "Add ${tracksToProcess.size}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (alreadyPresentCount > 0) {
                Text(
                    text = if (isRemoveMode) "Tracks in playlist: $alreadyPresentCount" else "Already in playlist: $alreadyPresentCount",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            LazyColumn(
                modifier = Modifier.weight(1f, fill = false)
            ) {
                items(tracks, key = { it.id }) { track ->
                    val isAlreadyInPlaylist = track.id in playlistTrackIds
                    val isSelected = track.id in selectedTrackIds

                    val showInList = if (isRemoveMode) isAlreadyInPlaylist else !isAlreadyInPlaylist

                    if (showInList) {
                        TrackSelectionRow(
                            track = track,
                            isSelected = isSelected,
                            isAlreadyInPlaylist = isAlreadyInPlaylist,
                            showCheckbox = !isRemoveMode,
                            onToggle = {
                                selectedTrackIds = if (isSelected) {
                                    selectedTrackIds - track.id
                                } else {
                                    selectedTrackIds + track.id
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
private fun TrackSelectionRow(
    track: Track,
    isSelected: Boolean,
    isAlreadyInPlaylist: Boolean,
    showCheckbox: Boolean = true,
    onToggle: () -> Unit,
) {
    val artworkUrl =
        remember(track) { ALBUM_COVER_URL + (track.album?.getCover(CoverSize.SMALL)?.uri ?: "") }

    val background = when (isAlreadyInPlaylist) {
        true -> {
            when (isSelected) {
                true -> MaterialTheme.colorScheme.onPrimaryFixedVariant
                false -> MaterialTheme.colorScheme.background
            }
        }

        false -> {
            when (isSelected) {
                true -> MaterialTheme.colorScheme.onPrimaryFixedVariant
                false -> MaterialTheme.colorScheme.errorContainer
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .background(color = background)
            .clip(RoundedCornerShape(16.dp))
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showCheckbox) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggle() }
            )

            Spacer(modifier = Modifier.width(8.dp))
        }

        Surface(
            shape = RoundedCornerShape(4.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.size(48.dp)
        ) {
            if (artworkUrl.isNotBlank()) {
                SmartImage(
                    url = artworkUrl,
                    contentDescription = stringResource(R.string.common_artwork),
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
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                track.artists.take(2).forEachIndexed { index, artist ->
                    Text(
                        text = artist.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (index < track.artists.take(2).lastIndex) {
                        Text(
                            text = ",",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        if (isAlreadyInPlaylist && !showCheckbox) {
            Text(
                text = stringResource(R.string.in_playlist),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}