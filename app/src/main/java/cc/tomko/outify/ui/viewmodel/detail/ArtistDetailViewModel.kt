package cc.tomko.outify.ui.viewmodel.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.tomko.outify.core.SpClient
import cc.tomko.outify.core.Spirc.SpircWrapper
import cc.tomko.outify.core.model.Album
import cc.tomko.outify.core.model.Artist
import cc.tomko.outify.core.model.PlayableAudio
import cc.tomko.outify.core.model.Track
import cc.tomko.outify.core.model.toPlayableAudio
import cc.tomko.outify.data.dao.LikedDao
import cc.tomko.outify.data.metadata.Metadata
import cc.tomko.outify.playback.PlaybackStateHolder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject

private const val POPULAR_TRACKS_KEY = "popular_tracks"
private const val ALBUMS_KEY = "albums"

sealed class ArtistUiState {
    object Loading : ArtistUiState()
    data class Success(val artist: Artist, val isContentLoading: Boolean = true) : ArtistUiState()
    data class Error(val message: String) : ArtistUiState()
}

@HiltViewModel
class ArtistDetailViewModel @Inject constructor(
    private val metadata: Metadata,
    private val spClient: SpClient,
    private val playbackStateHolder: PlaybackStateHolder,
    val spirc: SpircWrapper,
    val likedDao: LikedDao,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val json = Json { ignoreUnknownKeys = true }

    private val _uiState = MutableStateFlow<ArtistUiState>(ArtistUiState.Loading)
    val uiState: StateFlow<ArtistUiState> = _uiState

    private val currentArtistId = MutableStateFlow<String?>(
        (_uiState.value as? ArtistUiState.Success)?.artist?.id
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    val likedTrackIds: StateFlow<Set<String>> = currentArtistId
        .flatMapLatest { artistId ->
            if (artistId.isNullOrEmpty()) {
                flowOf(emptySet())
            } else {
                likedDao.observeLikedIdsByArtist(artistId)
                    .map { it.toHashSet() }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptySet()
        )
    private val popularTrackUris = MutableStateFlow<List<String>>(
        savedStateHandle.get<List<String>>(POPULAR_TRACKS_KEY) ?: emptyList()
    )
    private val albumUris = MutableStateFlow<List<String>>(
        savedStateHandle.get<List<String>>(ALBUMS_KEY) ?: emptyList()
    )

    val currentAudio: StateFlow<PlayableAudio?> = playbackStateHolder.state
        .map { it.currentAudio }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null
        )

    val isPlaying: StateFlow<Boolean> = playbackStateHolder.state
        .map { it.isPlaying }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = false
        )

    private val _isSaved = MutableStateFlow(false)
    val isSaved: StateFlow<Boolean> = _isSaved

    fun toggleSave() {
        viewModelScope.launch {
            val artistUri = when (val state = _uiState.value) {
                is ArtistUiState.Success -> state.artist.uri
                else -> null
            } ?: return@launch

            if (_isSaved.value) {
                spClient.deleteItems(arrayOf(artistUri))
            } else {
                spClient.saveItems(arrayOf(artistUri))
            }
            _isSaved.value = !_isSaved.value
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val likedTracks: StateFlow<List<Track>> = likedTrackIds
        .flatMapLatest { ids ->
            flow {
                if (ids.isEmpty()) emit(emptyList())
                else {
                    val uris = ids.map { "spotify:track:$it" }
                    metadata.observeTracks(uris)
                        .collect { tracks ->
                            emit(tracks)
                        }
                }
            }.flowOn(Dispatchers.IO)
        }
        .debounce(50)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val popularTracks: StateFlow<List<Track>> = popularTrackUris
        .flatMapLatest { uris ->
            if (uris.isEmpty()) flowOf(emptyList())
            else metadata.observeTracks(uris)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    val albums: StateFlow<List<Album>> = albumUris
        .flatMapLatest { uris ->
            if (uris.isEmpty()) flowOf(emptyList())
            else metadata.observeAlbums(uris)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    val isContentLoading: StateFlow<Boolean> = combine(
        popularTracks,
        albums
    ) { tracks, albums ->
        tracks.isEmpty() && albums.isEmpty()
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = true
    )

    private var _lastArtistUri: String? = null

    fun retry() {
        val uri = _lastArtistUri ?: return
        viewModelScope.launch {
            spirc.restart()
            _uiState.value = ArtistUiState.Loading
            loadArtist(uri)
        }
    }

    private fun saveState() {
        savedStateHandle[POPULAR_TRACKS_KEY] = popularTrackUris.value
        savedStateHandle[ALBUMS_KEY] = albumUris.value
    }

    suspend fun loadArtist(artistUri: String) {
        _lastArtistUri = artistUri
        val artist = withContext(Dispatchers.IO) {
            metadata.getArtistMetadata(artistUri)
        }

        if (artist == null) {
            _uiState.value = ArtistUiState.Error("Artist failed to fetch")
            saveState()
            return
        }

        withContext(Dispatchers.Main.immediate) {
            currentArtistId.value = artist.id
            popularTrackUris.value = artist.tracks
            albumUris.value = artist.albums
            _uiState.value = ArtistUiState.Success(artist)
            _isSaved.value = false
            saveState()
        }
    }

    fun setTrack(track: Track) {
        playbackStateHolder.setAudio(track.toPlayableAudio())
    }
}
