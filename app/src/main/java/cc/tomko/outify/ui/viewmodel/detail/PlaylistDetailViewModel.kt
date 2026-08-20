package cc.tomko.outify.ui.viewmodel.detail

import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.tomko.outify.core.SpClient
import cc.tomko.outify.core.Spirc.SpircWrapper
import cc.tomko.outify.core.UserProfile
import cc.tomko.outify.core.model.PlayableAudio
import cc.tomko.outify.core.model.Playlist
import cc.tomko.outify.core.model.Profile
import cc.tomko.outify.core.model.Track
import cc.tomko.outify.core.model.getCover
import cc.tomko.outify.core.model.toPlayableAudio
import cc.tomko.outify.data.dao.LikedDao
import cc.tomko.outify.data.metadata.Metadata
import cc.tomko.outify.playback.PlaybackStateHolder
import dagger.hilt.android.lifecycle.HiltViewModel
import jakarta.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

@HiltViewModel
class PlaylistDetailViewModel @Inject constructor(
    private val metadata: Metadata,
    private val playbackStateHolder: PlaybackStateHolder,
    val spirc: SpircWrapper,
    val userProfile: UserProfile,
    val likedDao: LikedDao,
    val spClient: SpClient,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val PLAYLIST_STATE_KEY = "playlist_state"
    private val PLAYLIST_URI_KEY = "playlist_uri"

    val json = Json { ignoreUnknownKeys = true }

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
            val playlistUri = when (val state = _uiState.value) {
                is PlaylistUiState.Success -> state.playlist?.uri
                else -> null
            } ?: return@launch

            if (_isSaved.value) {
                spClient.deleteItems(arrayOf(playlistUri))
                metadata.removeLikedPlaylist(playlistUri)
            } else {
                spClient.saveItems(arrayOf(playlistUri))
                metadata.addLikedPlaylist(playlistUri)
            }
            _isSaved.value = !_isSaved.value
        }
    }

    private fun checkIsSaved(playlistUri: String) {
        viewModelScope.launch {
            _isSaved.value = metadata.isLikedPlaylist(playlistUri)
        }
    }

    private val _authors = MutableStateFlow<Map<String, Profile>>(emptyMap())
    val authors: StateFlow<Map<String, Profile>> = _authors

    private val _uiState = MutableStateFlow<PlaylistUiState>(PlaylistUiState.Loading)
    val uiState: StateFlow<PlaylistUiState> = _uiState

    private val _trackMetadataMap = mutableStateMapOf<String, Track>()
    val trackMetadataMap: Map<String, Track> = _trackMetadataMap

    private val _trackMetadata = MutableStateFlow<Map<String, Track>>(emptyMap())
    val trackMetadata: StateFlow<Map<String, Track>> = _trackMetadata.asStateFlow()

    val isRefreshing = MutableStateFlow(false)

    val likedTrackIds: StateFlow<Set<String>> =
        likedDao.observeLikedIds()
            .map { it.toHashSet() }
            .distinctUntilChanged()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptySet()
            )

    init {
        savedStateHandle.get<String>(PLAYLIST_URI_KEY)?.let { uri ->
            loadPlaylist(uri, false)
        }
    }

    fun loadPlaylist(playlistUri: String, cleanFetch: Boolean) {
        val uri = playlistUri.substringAfterLast(":").let { "spotify:playlist:$it" }

        viewModelScope.launch {
            savedStateHandle[PLAYLIST_URI_KEY] = uri
            isRefreshing.value = true
            _uiState.value = PlaylistUiState.Loading

            runCatching {
                metadata.getPlaylistMetadata(uri, !cleanFetch)
            }.onSuccess { playlist ->
                isRefreshing.value = false
                _uiState.value = PlaylistUiState.Success(playlist)
                _isSaved.value = false
                checkIsSaved(uri)
            }.onFailure { e ->
                isRefreshing.value = false
                _uiState.value = PlaylistUiState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun retry() {
        val uri = savedStateHandle.get<String>(PLAYLIST_URI_KEY)
        if (uri != null) {
            viewModelScope.launch {
                spirc.restart()
                loadPlaylist(uri, true)
            }
        }
    }

    fun refresh() {
        val currentPlaylistUri = when (val state = _uiState.value) {
            is PlaylistUiState.Success -> state.playlist?.uri
            else -> null
        }

        currentPlaylistUri?.let { uri ->
            loadPlaylist(uri, true)
        } ?: run {
            _uiState.value = PlaylistUiState.Error("No playlist loaded to refresh")
        }
    }

    fun trackFlow(uri: String): Flow<Track?> =
        trackMetadata
            .map { it[uri] }
            .distinctUntilChanged()

    fun getTrackState(uri: String): Track? =
        _trackMetadataMap[uri] ?: trackMetadata.value[uri]

    suspend fun getOrLoadTrack(uri: String): Track? {
        _trackMetadataMap[uri]?.let { return it }
        trackMetadata.value[uri]?.let { return it }

        val fetched = withContext(Dispatchers.IO) {
            metadata.getTrackMetadata(listOf(uri)).firstOrNull()
        } ?: return null

        _trackMetadataMap[uri] = fetched
        _trackMetadata.update { current -> current + (uri to fetched) }

        return fetched
    }

    fun buildPlaylistRows(playlist: Playlist): List<PlaylistRow> =
        playlist.contents.mapIndexed { index, item ->
            PlaylistRow(
                key = "${playlist.uri}:$index:${item.uri}",
                trackUri = item.uri,
                addedBy = item.attributes.addedBy
            )
        }

    suspend fun getArtworkUrl(playlist: Playlist): String {
        return playlist.getCover(metadata) ?: "unknown cover"
    }

    suspend fun getAuthors(playlist: Playlist): List<Profile> = coroutineScope {
        val ids = playlist.contents
            .map { it.attributes.addedBy }
            .distinct()

        ids.map { id ->
            async(Dispatchers.IO) {
                _authors.value[id]?.let { return@async it }

                val jsonRaw = try {
                    userProfile.getUserProfile(id)
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                } ?: return@async null

                val profile = try {
                    json.decodeFromString<Profile>(jsonRaw)
                } catch (e: Exception) {
                    e.printStackTrace()
                    return@async null
                }

                _authors.update { current -> current + (id to profile) }

                profile
            }
        }.awaitAll()
            .filterNotNull()
    }

    fun setAudio(audio: PlayableAudio) {
        playbackStateHolder.setAudio(audio)
    }
}

data class PlaylistRow(
    val key: String,
    val trackUri: String,
    val addedBy: String,
)

sealed interface PlaylistUiState {
    object Loading : PlaylistUiState
    data class Success(val playlist: Playlist?) : PlaylistUiState
    data class Error(val error: String) : PlaylistUiState
}
