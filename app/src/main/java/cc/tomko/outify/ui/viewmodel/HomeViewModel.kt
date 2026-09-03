package cc.tomko.outify.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.tomko.outify.core.AuthManager
import cc.tomko.outify.core.AuthStateEvent
import cc.tomko.outify.core.AuthStateEventBus
import cc.tomko.outify.core.SpClient
import cc.tomko.outify.core.spirc.SpircWrapper
import cc.tomko.outify.core.UserProfile
import cc.tomko.outify.core.model.PlayableAudio
import cc.tomko.outify.core.model.Profile
import cc.tomko.outify.core.model.Track
import cc.tomko.outify.core.model.toOutifyUri
import cc.tomko.outify.data.metadata.NativeErrorHandler
import cc.tomko.outify.data.metadata.TrackMetadataHelper
import cc.tomko.outify.data.repository.SettingsRepository
import cc.tomko.outify.playback.PlaybackStateHolder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject

sealed class HomeUiState {
    data object Loading : HomeUiState()
    data object NotAuthenticated : HomeUiState()
    data class Success(
        val topArtists: List<TopArtist>,
        val topTracks: List<Track>,
    ) : HomeUiState()

    data object EmptyResult : HomeUiState()
    data class Error(val message: String) : HomeUiState()
}

@Serializable
data class TopArtist(
    val uri: String,
    val name: String,
    val imageUrl: String?,
    val rank: Int = 0,
)

enum class TopItemsDuration(val value: String, val label: String) {
    SHORT_TERM("short_term", "Last 4 weeks"),
    MEDIUM_TERM("medium_term", "Last 6 months"),
    LONG_TERM("long_term", "Last year"),
}

@Serializable
private data class DurationTops(
    val artists: List<TopArtist> = emptyList(),
    val trackUris: List<String> = emptyList(),
)

@Serializable
private data class TopsCacheData(
    val shortTerm: DurationTops = DurationTops(),
    val mediumTerm: DurationTops = DurationTops(),
    val longTerm: DurationTops = DurationTops(),
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val spClient: SpClient,
    private val json: Json,
    private val trackMetadataHelper: TrackMetadataHelper,
    private val spirc: SpircWrapper,
    private val playbackStateHolder: PlaybackStateHolder,
    private val userProfile: UserProfile,
    private val settingsRepository: SettingsRepository,
    private val authManager: AuthManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val uiState: StateFlow<HomeUiState> = _uiState

    private val _selectedDuration = MutableStateFlow(TopItemsDuration.SHORT_TERM)
    val selectedDuration: StateFlow<TopItemsDuration> = _selectedDuration.asStateFlow()

    val userId: Flow<String?> = settingsRepository.userId
    val username: Flow<String?> = settingsRepository.username
    val userImageUrl: Flow<String?> = settingsRepository.userImageUrl

    val isRefreshing = MutableStateFlow(false)

    private val _isPlaybackLoggedIn = MutableStateFlow(false)
    val isPlaybackLoggedIn: StateFlow<Boolean> = _isPlaybackLoggedIn.asStateFlow()

    init {
        viewModelScope.launch {
            _isPlaybackLoggedIn.value = withContext(Dispatchers.IO) { authManager.hasCachedCredentials() }
        }
        loadData()
        viewModelScope.launch {
            AuthStateEventBus.events.collect { event ->
                when (event) {
                    is AuthStateEvent.AccountLoggedIn -> {
                        delay(200)
                        loadData()
                    }

                    is AuthStateEvent.AccountLoggedOut -> {
                        _uiState.value = HomeUiState.NotAuthenticated
                        loadUserProfile()
                    }

                    is AuthStateEvent.PlaybackLoggedIn -> {
                        delay(200)
                        _isPlaybackLoggedIn.value = withContext(Dispatchers.IO) { authManager.hasCachedCredentials() }
                    }

                    is AuthStateEvent.PlaybackLoggedOut -> {
                        _isPlaybackLoggedIn.value = withContext(Dispatchers.IO) { authManager.hasCachedCredentials() }
                    }
                }
            }
        }
    }

    fun refreshPlaybackLoginState() {
        viewModelScope.launch {
            _isPlaybackLoggedIn.value = withContext(Dispatchers.IO) { authManager.hasCachedCredentials() }
        }
    }

    fun setDuration(duration: TopItemsDuration) {
        if (_selectedDuration.value != duration) {
            _selectedDuration.value = duration
            loadData()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            isRefreshing.value = true
            loadData()
            isRefreshing.value = false
        }
    }

    fun retry() {
        viewModelScope.launch {
            isRefreshing.value = true
            spirc.restart()
            loadData()
            isRefreshing.value = false
        }
    }

    fun loadTrack(audio: PlayableAudio) {
        // TODO: set the context
        spirc.load(audio.toOutifyUri())

        playbackStateHolder.setAudio(audio)
    }

    fun loadData() {
        viewModelScope.launch {
            _uiState.value = HomeUiState.Loading
            delay(150)

            try {
                val isAuthenticated = withContext(Dispatchers.IO) { spClient.isOAuthAuthenticated() }
                if (!isAuthenticated) {
                    _uiState.value = HomeUiState.NotAuthenticated
                    loadUserProfile()
                    return@launch
                }

                val duration = _selectedDuration.value

                settingsRepository.cachedTops.first()?.let { raw ->
                    try {
                        val allCaches = json.decodeFromString<TopsCacheData>(raw)
                        val hit = when (duration) {
                            TopItemsDuration.SHORT_TERM -> allCaches.shortTerm
                            TopItemsDuration.MEDIUM_TERM -> allCaches.mediumTerm
                            TopItemsDuration.LONG_TERM -> allCaches.longTerm
                        }
                        if (hit.artists.isNotEmpty()) {
                            val cachedTracks = withContext(Dispatchers.IO) { trackMetadataHelper.getTrackMetadata(hit.trackUris) }
                            _uiState.value = HomeUiState.Success(hit.artists, cachedTracks)
                        }
                    } catch (_: Exception) {
                    }
                }

                val durationsToTry = listOf(
                    TopItemsDuration.SHORT_TERM,
                    TopItemsDuration.MEDIUM_TERM,
                    TopItemsDuration.LONG_TERM,
                )

                for (fallbackDuration in durationsToTry) {
                    val durationValue = fallbackDuration.value

                    val topArtistsJson = withContext(Dispatchers.IO) { spClient.getUserTop("artists", durationValue) }
                    if (topArtistsJson == null) {
                        _uiState.value = HomeUiState.NotAuthenticated
                        loadUserProfile()
                        return@launch
                    }
                    val topArtistsError =
                        NativeErrorHandler.handleErrorJson(topArtistsJson, "top artists")
                    if (topArtistsError != null) {
                        _uiState.value = HomeUiState.NotAuthenticated
                        loadUserProfile()
                        return@launch
                    }

                    val topTracksJson = withContext(Dispatchers.IO) { spClient.getUserTop("tracks", durationValue) }
                    if (topTracksJson == null) {
                        _uiState.value = HomeUiState.NotAuthenticated
                        loadUserProfile()
                        return@launch
                    }
                    val topTracksError =
                        NativeErrorHandler.handleErrorJson(topTracksJson, "top tracks")
                    if (topTracksError != null) {
                        _uiState.value = HomeUiState.NotAuthenticated
                        loadUserProfile()
                        return@launch
                    }

                    val topArtists = parseTopArtists(topArtistsJson)
                    val topTracks = withContext(Dispatchers.IO) { fetchTrackMetadata(topTracksJson) }

                    if (topArtists.isNotEmpty() || topTracks.isNotEmpty()) {
                        _selectedDuration.value = fallbackDuration
                        _uiState.value = HomeUiState.Success(topArtists, topTracks)
                        updateTopCache(fallbackDuration, topArtists, topTracks)
                        loadUserProfile()
                        return@launch
                    }
                }

                _uiState.value = HomeUiState.EmptyResult
                loadUserProfile()
            } catch (e: Exception) {
                _uiState.value = HomeUiState.Error(e.message ?: "Unknown error")
            }
        }
    }

    private suspend fun updateTopCache(
        duration: TopItemsDuration,
        artists: List<TopArtist>,
        tracks: List<Track>
    ) {
        val trackUris = tracks.map { it.uri }
        val cache = try {
            settingsRepository.cachedTops.first()?.let { json.decodeFromString<TopsCacheData>(it) }
                ?: TopsCacheData()
        } catch (_: Exception) {
            TopsCacheData()
        }

        val durationCache = DurationTops(artists, trackUris)
        val updated = when (duration) {
            TopItemsDuration.SHORT_TERM -> cache.copy(shortTerm = durationCache)
            TopItemsDuration.MEDIUM_TERM -> cache.copy(mediumTerm = durationCache)
            TopItemsDuration.LONG_TERM -> cache.copy(longTerm = durationCache)
        }
        settingsRepository.saveCachedTops(json.encodeToString(updated))
    }

    private fun loadUserProfile() {
        viewModelScope.launch {
            try {
                val userId = withContext(Dispatchers.IO) { spClient.username() } ?: return@launch

                val profileJson = withContext(Dispatchers.IO) { userProfile.getUserProfile(userId) }
                var profileName: String? = null
                var profileImageUrl: String? = null

                if (profileJson != null) {
                    try {
                        val profile = json.decodeFromString<Profile>(profileJson)
                        profileName = profile.name
                        profileImageUrl = profile.imageUrl
                    } catch (e: Exception) {
                        // Ignore parse errors
                    }
                }

                settingsRepository.saveUserProfile(userId, profileName, profileImageUrl)
            } catch (e: Exception) {
                // Ignore errors
            }
        }
    }

    private fun parseTopArtists(raw: String): List<TopArtist> {
        return try {
            val data = json.decodeFromString<TopArtistsResponse>(raw)
            data.items.mapIndexed { index, artist ->
                TopArtist(
                    uri = artist.uri ?: "",
                    name = artist.name,
                    imageUrl = artist.images?.firstOrNull()?.url,
                    rank = index + 1
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun fetchTrackMetadata(raw: String): List<Track> {
        return try {
            val data = json.decodeFromString<TopTracksResponse>(raw)
            val trackUris =
                data.items.mapNotNull { it.uri }.filter { it.startsWith("spotify:track:") }

            if (trackUris.isEmpty()) {
                return emptyList()
            }

            trackMetadataHelper.getTrackMetadata(trackUris)
        } catch (e: Exception) {
            emptyList()
        }
    }

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

    @Serializable
    private data class TopArtistsResponse(
        val items: List<TopArtistItem> = emptyList(),
    )

    @Serializable
    private data class TopArtistItem(
        val id: String? = null,
        val name: String = "",
        val uri: String? = null,
        val images: List<Image>? = null,
    )

    @Serializable
    private data class TopTracksResponse(
        val items: List<TopTrackItem> = emptyList(),
    )

    @Serializable
    private data class TopTrackItem(
        val id: String? = null,
        val name: String = "",
        val uri: String? = null,
        val duration_ms: Int? = null,
        val artists: List<Artist>? = null,
        val album: Album? = null,
    )

    @Serializable
    private data class Image(val url: String)

    @Serializable
    private data class Artist(val name: String)

    @Serializable
    private data class Album(val images: List<Image>? = null)
}