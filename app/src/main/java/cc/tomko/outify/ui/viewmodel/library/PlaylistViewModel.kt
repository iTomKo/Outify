package cc.tomko.outify.ui.viewmodel.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.tomko.outify.core.spirc.SpircWrapper
import cc.tomko.outify.core.UserProfile
import cc.tomko.outify.core.model.PlayableAudio
import cc.tomko.outify.core.model.Playlist
import cc.tomko.outify.core.model.Profile
import cc.tomko.outify.core.model.Track
import cc.tomko.outify.core.model.getCover
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
class PlaylistViewModel @Inject constructor(
    private val metadata: Metadata,
    private val playbackStateHolder: PlaybackStateHolder,
    val spirc: SpircWrapper,
    val userProfile: UserProfile,
    val likedDao: LikedDao,
) : ViewModel() {
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

    private val _authors = MutableStateFlow<Map<String, Profile>>(emptyMap())
    val authors: StateFlow<Map<String, Profile>> = _authors

    private val _uiState = MutableStateFlow<PlaylistUiState>(PlaylistUiState.Loading)
    val uiState: StateFlow<PlaylistUiState> = _uiState

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

    fun loadPlaylist(playlistUri: String, cleanFetch: Boolean) {
        viewModelScope.launch {
            isRefreshing.value = true
            _uiState.value = PlaylistUiState.Loading

            runCatching {
                metadata.getPlaylistMetadata(playlistUri, !cleanFetch)
            }.onSuccess { playlist ->
                isRefreshing.value = false
                _uiState.value = PlaylistUiState.Success(playlist)
            }.onFailure { e ->
                isRefreshing.value = false
                _uiState.value = PlaylistUiState.Error(e.message ?: "Unknown error")
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

    suspend fun getOrLoadTrack(uri: String): Track? {
        trackMetadata.value[uri]?.let { return it }

        val fetched = withContext(Dispatchers.IO) {
            metadata.getTrackMetadata(listOf(uri)).firstOrNull()
        } ?: return null

        _trackMetadata.update { current -> current + (uri to fetched) }

        return fetched
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

sealed interface PlaylistUiState {
    object Loading : PlaylistUiState
    data class Success(val playlist: Playlist?) : PlaylistUiState
    data class Error(val error: String) : PlaylistUiState
}
