package cc.tomko.outify.ui.viewmodel.library

import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.tomko.outify.core.SpClient
import cc.tomko.outify.core.EpisodeDetails
import cc.tomko.outify.core.Spirc.SpircWrapper
import cc.tomko.outify.core.UserProfile
import cc.tomko.outify.core.model.Album
import cc.tomko.outify.core.model.Episode
import cc.tomko.outify.core.model.Playlist
import cc.tomko.outify.core.model.PlaylistFolder
import cc.tomko.outify.core.model.Profile
import cc.tomko.outify.core.model.OutifyUri
import cc.tomko.outify.core.model.Show
import cc.tomko.outify.core.model.PlayableAudio
import cc.tomko.outify.core.model.Track
import cc.tomko.outify.core.model.getCover
import cc.tomko.outify.core.model.toOutifyUri
import cc.tomko.outify.core.model.toPlayableAudio
import cc.tomko.outify.core.model.toSpotifyUri
import cc.tomko.outify.data.metadata.Metadata
import cc.tomko.outify.data.repository.LikedRepository
import cc.tomko.outify.data.repository.SettingsRepository
import cc.tomko.outify.playback.PlaybackStateHolder
import dagger.hilt.android.lifecycle.HiltViewModel
import jakarta.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

enum class LibraryTab { Playlists, Albums, Shows, Episodes }

data class LibraryState(
    val playlists: List<Playlist> = emptyList(),
    val albums: List<Album> = emptyList(),
    val shows: List<Show> = emptyList(),
    val episodes: List<Episode> = emptyList(),
    val tracks: List<Track> = emptyList(),
    val folders: List<PlaylistFolder> = emptyList(),
    val selectedTab: LibraryTab = LibraryTab.Playlists,
    val error: String? = null,
    val isLoadingAlbums: Boolean = false,
    val isLoadingShows: Boolean = false,
    val isLoadingEpisodes: Boolean = false,
    val isLoadingTracks: Boolean = false,
    val episodeShowUris: Map<String, String> = emptyMap(),
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val metadata: Metadata,
    private val json: Json,
    private val userProfile: UserProfile,
    private val settingsRepository: SettingsRepository,
    private val spClient: SpClient,
    private val spirc: SpircWrapper,
    private val playbackStateHolder: PlaybackStateHolder,
    private val likedRepository: LikedRepository,
) : ViewModel() {

    init {
        viewModelScope.launch {
            metadata.syncLikedPlaylists()
        }
    }

    private val _headerArtwork = mutableStateOf<String?>(null)
    val headerArtwork = _headerArtwork

    private val playlistUris = MutableStateFlow<List<String>>(emptyList())
    private var playlistsLoaded = false

    private val albumUris = MutableStateFlow<List<String>>(emptyList())
    private var albumsLoaded = false

    private val showUris = MutableStateFlow<List<String>>(emptyList())
    private var showsLoaded = false

    private val episodeUris = MutableStateFlow<List<String>>(emptyList())
    private var episodesLoaded = false

    private val _authors = MutableStateFlow<Map<String, Profile>>(emptyMap())
    val authors: StateFlow<Map<String, Profile>> = _authors
    val isRefreshing = MutableStateFlow(false)

    private val artworkCache = mutableMapOf<String, String?>()
    private val authorsCache = mutableMapOf<String, List<Profile>>()

    private val foldersFlow = settingsRepository.folders

    private val _error = MutableStateFlow<String?>(null)
    private val _selectedTab = MutableStateFlow(LibraryTab.Playlists)
    val selectedTab: StateFlow<LibraryTab> = _selectedTab

    private val _isLoadingAlbums = MutableStateFlow(false)
    private val _isLoadingShows = MutableStateFlow(false)
    private val _isLoadingEpisodes = MutableStateFlow(false)
    private val _isLoadingTracks = MutableStateFlow(false)

    private val _episodeShowUris = MutableStateFlow<Map<String, String>>(emptyMap())

    fun selectTab(tab: LibraryTab) {
        _selectedTab.value = tab
        when (tab) {
            LibraryTab.Albums -> loadAlbumUris()
            LibraryTab.Shows -> loadShowUris()
            LibraryTab.Episodes -> loadEpisodeUris()
            else -> {}
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val playlists: StateFlow<List<Playlist>> =
        playlistUris
            .flatMapLatest { uris ->
                if (uris.isEmpty()) {
                    flow { emit(emptyList()) }
                } else {
                    metadata.observePlaylists(uris)
                }
            }
            .debounce(50)
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                emptyList()
            )

    @OptIn(ExperimentalCoroutinesApi::class)
    val albums: StateFlow<List<Album>> =
        albumUris
            .flatMapLatest { uris ->
                if (uris.isEmpty()) {
                    flow { emit(emptyList()) }
                } else {
                    metadata.observeAlbums(uris)
                }
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                emptyList()
            )

    @OptIn(ExperimentalCoroutinesApi::class)
    val shows: StateFlow<List<Show>> =
        showUris
            .flatMapLatest { uris ->
                if (uris.isEmpty()) {
                    flow { emit(emptyList()) }
                } else {
                    metadata.observeShows(uris)
                }
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                emptyList()
            )

    @OptIn(ExperimentalCoroutinesApi::class)
    val episodes: StateFlow<List<Episode>> =
        episodeUris
            .flatMapLatest { uris ->
                if (uris.isEmpty()) {
                    flow { emit(emptyList()) }
                } else {
                    metadata.observeEpisodes(uris)
                }
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                emptyList()
            )

    val libraryState: StateFlow<LibraryState> = combine(
        listOf(playlists, albums, shows, episodes, foldersFlow, _selectedTab, _error, _isLoadingAlbums, _isLoadingShows, _isLoadingEpisodes, _isLoadingTracks, _episodeShowUris)
    ) { values ->
        LibraryState(
            playlists = values[0] as List<Playlist>,
            albums = values[1] as List<Album>,
            shows = values[2] as List<Show>,
            episodes = values[3] as List<Episode>,
            folders = values[4] as List<PlaylistFolder>,
            selectedTab = values[5] as LibraryTab,
            error = values[6] as String?,
            isLoadingAlbums = values[7] as Boolean,
            isLoadingShows = values[8] as Boolean,
            isLoadingEpisodes = values[9] as Boolean,
            isLoadingTracks = values[10] as Boolean,
            episodeShowUris = values[11] as Map<String, String>,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        LibraryState()
    )

    suspend fun getArtworkUrl(playlist: Playlist): String? {
        artworkCache[playlist.uri]?.let { return it }
        val url = playlist.getCover(metadata)
        artworkCache[playlist.uri] = url
        return url
    }

    fun loadPlaylistUris(force: Boolean = false) {
        if (!force && playlistsLoaded) return
        viewModelScope.launch {
            isRefreshing.value = true
            _error.value = null

            val cached = settingsRepository.cachedUris.first()
            if (cached.isNotEmpty()) {
                playlistUris.value = cached
            }

            runCatching {
                metadata.getPlaylistUris()
            }.onSuccess { uris ->
                playlistUris.value = uris
                settingsRepository.saveCachedUris(uris)
                playlistsLoaded = true
            }.onFailure { e ->
                Log.w("LibraryViewModel", "Failed to fetch playlist URIs", e)
                _error.value = e.message ?: "Failed to load library"
            }

            isRefreshing.value = false
        }
    }

    fun loadAlbumUris(force: Boolean = false) {
        if (!force && albumsLoaded) return
        viewModelScope.launch {
            _isLoadingAlbums.value = true

            runCatching {
                val raw = spClient.getSavedItems(SpClient.ALBUMS)
                raw.split(",").filter { it.isNotBlank() }
            }.onSuccess { uris ->
                albumUris.value = uris
                albumsLoaded = true
            }.onFailure { e ->
                Log.w("LibraryViewModel", "Failed to fetch album URIs", e)
            }

            _isLoadingAlbums.value = false
        }
    }

    fun loadShowUris(force: Boolean = false) {
        if (!force && showsLoaded) return
        viewModelScope.launch {
            _isLoadingShows.value = true

            runCatching {
                val raw = spClient.getSavedItems(SpClient.SHOWS)
                raw.split(",").filter { it.isNotBlank() }
            }.onSuccess { uris ->
                showUris.value = uris
                showsLoaded = true
            }.onFailure { e ->
                Log.w("LibraryViewModel", "Failed to fetch show URIs", e)
            }

            _isLoadingShows.value = false
        }
    }

    fun loadEpisodeUris(force: Boolean = false) {
        if (!force && episodesLoaded) return
        viewModelScope.launch {
            _isLoadingEpisodes.value = true

            runCatching {
                val pairs = metadata.getSavedEpisodeInfo()
                val uris = pairs.map { it.first }
                val showMap = pairs.associate { it.first to it.second }
                uris to showMap
            }.onSuccess { (uris, showMap) ->
                episodeUris.value = uris
                _episodeShowUris.value = showMap
                episodesLoaded = true
            }.onFailure { e ->
                Log.w("LibraryViewModel", "Failed to fetch episode URIs", e)
            }

            _isLoadingEpisodes.value = false
        }
    }

    val currentAudio: StateFlow<PlayableAudio?> = playbackStateHolder.state
        .map { it.currentAudio }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val isPlaying: StateFlow<Boolean> = playbackStateHolder.state
        .map { it.isPlaying }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun load(track: Track) {
        spirc.load(OutifyUri.Liked, track.toSpotifyUri())
    }

    fun loadHeaderArtwork(playlists: List<Playlist>) {
        if (_headerArtwork.value != null) return
        if (playlists.isNotEmpty()) {
            viewModelScope.launch {
                _headerArtwork.value =
                    getArtworkUrl(playlists.random())
            }
        }
    }

    suspend fun getAuthors(playlist: Playlist): List<Profile> = coroutineScope {
        authorsCache[playlist.uri]?.let { return@coroutineScope it }

        val ids = playlist.contents
            .map { it.attributes.addedBy }
            .distinct()

        val profiles = ids.map { id ->
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

        authorsCache[playlist.uri] = profiles
        profiles
    }

    fun retry() {
        viewModelScope.launch {
            spirc.restart()
            refresh()
        }
    }

    fun playEpisode(episode: Episode) {
        spirc.load(episode.toOutifyUri())
        playbackStateHolder.setAudio(episode.toPlayableAudio())
    }

    suspend fun resolveEpisodeDetails(episodeId: String): EpisodeDetails? {
        return withContext(Dispatchers.IO) {
            runCatching {
                val raw = spClient.getEpisodeDetails(episodeId)
                val checked = spClient.checkAndHandleError(raw, "resolveEpisodeDetails:$episodeId")
                EpisodeDetails.fromJson(checked)
            }.getOrNull()
        }
    }

    fun refresh() {
        playlistsLoaded = false
        artworkCache.clear()
        authorsCache.clear()
        loadPlaylistUris(force = true)
        loadAlbumUris(force = true)
        loadShowUris(force = true)
        loadEpisodeUris(force = true)
    }

    fun createFolder(folder: PlaylistFolder) {
        viewModelScope.launch {
            val current = settingsRepository.folders.first()
            settingsRepository.saveFolders(current + folder)
        }
    }

    fun updateFolder(folder: PlaylistFolder) {
        viewModelScope.launch {
            val current = settingsRepository.folders.first()
            settingsRepository.saveFolders(current.map { if (it.id == folder.id) folder else it })
        }
    }

    fun deleteFolder(folderId: String) {
        viewModelScope.launch {
            val current = settingsRepository.folders.first()
            settingsRepository.saveFolders(current.filter { it.id != folderId })
        }
    }

    fun movePlaylistToFolder(playlistUri: String, folderId: String?) {
        viewModelScope.launch {
            val current = settingsRepository.folders.first()
            val updated = current.map { folder ->
                if (folder.id == folderId) {
                    if (playlistUri in folder.playlistIds) folder
                    else folder.copy(playlistIds = folder.playlistIds + playlistUri)
                } else {
                    folder.copy(playlistIds = folder.playlistIds - playlistUri)
                }
            }
            settingsRepository.saveFolders(updated)
        }
    }
}