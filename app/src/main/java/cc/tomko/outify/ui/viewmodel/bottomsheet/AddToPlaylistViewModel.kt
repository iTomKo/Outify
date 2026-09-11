package cc.tomko.outify.ui.viewmodel.bottomsheet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.tomko.outify.core.SpClient
import cc.tomko.outify.core.model.CoverSize
import cc.tomko.outify.core.model.Playlist
import cc.tomko.outify.core.model.Track
import cc.tomko.outify.core.model.getCover
import cc.tomko.outify.data.dao.PlaylistDao
import cc.tomko.outify.data.database.playlist.PlaylistItemEntity
import cc.tomko.outify.data.database.playlist.canModify
import cc.tomko.outify.data.database.playlist.toDomain
import cc.tomko.outify.data.metadata.Metadata
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class AddToPlaylistViewModel @Inject constructor(
    private val playlistDao: PlaylistDao,
    private val spClient: SpClient,
    private val metadata: Metadata,
) : ViewModel() {
    private val usernameFlow = flow {
        emit(spClient.username() ?: "no-username")
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        ""
    )

    private val playlistsWithArtwork = playlistDao.getPlaylistsWithItemsFlow()
        .combine(usernameFlow) { playlists, username ->
            playlists
                .filter { it.canModify(username) }
                .map { entity ->
                    val playlist = entity.toDomain()
                    val artwork = playlist.getCover(metadata, CoverSize.MEDIUM)
                    PlaylistUi(playlist = playlist, artworkUrl = artwork)
                }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            emptyList()
        )

    val ownedPlaylists = playlistsWithArtwork

    fun addToPlaylist(tracks: List<Track>, playlist: Playlist) {
        viewModelScope.launch {
            val trackUris = tracks.map { it.uri }
            val maxPosition = playlistDao.getMaxPosition(playlist.id)

            val newItems = tracks.mapIndexed { index, track ->
                PlaylistItemEntity(
                    playlistId = playlist.id,
                    position = maxPosition + 1 + index,
                    trackUri = track.uri,
                    addedBy = withContext(Dispatchers.IO) { spClient.username() } ?: "no-username",
                    timestamp = System.currentTimeMillis(),
                    seenAt = 0L,
                    isPublic = false,
                )
            }

            playlistDao.insertItems(newItems)

            val success = withContext(Dispatchers.IO) {
                spClient.addToPlaylist(playlist.id, trackUris.toTypedArray())
            }

            if (!success) {
                playlistDao.deleteItemsByUris(playlist.id, trackUris)
            }
        }
    }

    fun addTrackToPlaylist(track: Track, playlist: Playlist) {
        viewModelScope.launch {
            val maxPosition = playlistDao.getMaxPosition(playlist.id)

            val newItem = PlaylistItemEntity(
                playlistId = playlist.id,
                position = maxPosition + 1,
                trackUri = track.uri,
                addedBy = withContext(Dispatchers.IO) { spClient.username() } ?: "no-username",
                timestamp = System.currentTimeMillis(),
                seenAt = 0L,
                isPublic = false,
            )

            playlistDao.insertItems(listOf(newItem))

            val success = withContext(Dispatchers.IO) {
                spClient.addToPlaylist(playlist.id, arrayOf(track.uri))
            }

            if (!success) {
                playlistDao.deleteItemsByUris(playlist.id, listOf(track.uri))
            }
        }
    }

    fun removeFromPlaylist(tracks: List<Track>, playlist: Playlist) {
        viewModelScope.launch {
            val trackUris = tracks.map { it.uri }
            val playlistWithItems = playlistDao.getPlaylistWithItems(playlist.id)
            val removedItems =
                playlistWithItems?.items?.filter { it.trackUri in trackUris } ?: emptyList()

            playlistDao.deleteItemsByUris(playlist.id, trackUris)

            val success = withContext(Dispatchers.IO) {
                spClient.deleteFromPlaylist(playlist.id, trackUris.toTypedArray())
            }

            if (!success) {
                playlistDao.insertItems(removedItems)
            }
        }
    }

    fun removeTrackFromPlaylist(track: Track, playlist: Playlist) {
        viewModelScope.launch {
            val trackUris = listOf(track.uri)
            val playlistWithItems = playlistDao.getPlaylistWithItems(playlist.id)
            val removedItem = playlistWithItems?.items?.find { it.trackUri == track.uri }

            playlistDao.deleteItemsByUris(playlist.id, trackUris)

            val success = withContext(Dispatchers.IO) {
                spClient.deleteFromPlaylist(playlist.id, arrayOf(track.uri))
            }

            if (!success && removedItem != null) {
                playlistDao.insertItems(listOf(removedItem))
            }
        }
    }

    data class PlaylistUi(
        val playlist: Playlist,
        val artworkUrl: String?
    )
}