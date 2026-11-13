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
import cc.tomko.outify.data.repository.CollectionSessionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class AddToPlaylistViewModel @Inject constructor(
    private val playlistDao: PlaylistDao,
    private val spClient: SpClient,
    private val metadata: Metadata,
    private val collectionSessions: CollectionSessionState,
) : ViewModel() {
    private val mutationMutex = Mutex()
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
            mutationMutex.withLock {
                val lease = collectionSessions.requireCurrent()
                val trackUris = tracks.map { it.uri }
                val success = withContext(Dispatchers.IO) {
                    spClient.addToPlaylist(playlist.id, trackUris.toTypedArray())
                }
                collectionSessions.ensureCurrent(lease)
                if (!success) return@withLock
                val maxPosition = playlistDao.getMaxPosition(playlist.id)

                val newItems = tracks.mapIndexed { index, track ->
                    PlaylistItemEntity(
                        playlistId = playlist.id,
                        position = maxPosition + 1 + index,
                        trackUri = track.uri,
                        addedBy = lease.session.accountId,
                        timestamp = System.currentTimeMillis(),
                        seenAt = 0L,
                        isPublic = false,
                    )
                }
                playlistDao.insertItems(newItems)
            }
        }
    }

    fun addTrackToPlaylist(track: Track, playlist: Playlist) {
        addToPlaylist(listOf(track), playlist)
    }

    fun removeFromPlaylist(tracks: List<Track>, playlist: Playlist) {
        viewModelScope.launch {
            mutationMutex.withLock {
                val lease = collectionSessions.requireCurrent()
                val trackUris = tracks.map { it.uri }
                val success = withContext(Dispatchers.IO) {
                    spClient.deleteFromPlaylist(playlist.id, trackUris.toTypedArray())
                }
                collectionSessions.ensureCurrent(lease)
                if (success) playlistDao.deleteItemsByUris(playlist.id, trackUris)
            }
        }
    }

    fun removeTrackFromPlaylist(track: Track, playlist: Playlist) {
        removeFromPlaylist(listOf(track), playlist)
    }

    data class PlaylistUi(
        val playlist: Playlist,
        val artworkUrl: String?
    )
}
