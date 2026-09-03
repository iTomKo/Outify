package cc.tomko.outify.ui.viewmodel

import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.tomko.outify.R
import cc.tomko.outify.core.SpClient
import cc.tomko.outify.core.spirc.SpircWrapper
import cc.tomko.outify.core.UserProfile
import cc.tomko.outify.core.model.*
import cc.tomko.outify.data.dao.LikedDao
import cc.tomko.outify.data.metadata.Metadata
import cc.tomko.outify.data.repository.SearchRepository
import cc.tomko.outify.data.repository.SettingsRepository
import cc.tomko.outify.playback.PlaybackStateHolder
import cc.tomko.outify.reccobeats.RecommendationConfig
import cc.tomko.outify.reccobeats.Recommendations
import cc.tomko.outify.ui.model.search.SearchHistoryItem
import cc.tomko.outify.ui.model.search.SearchResultType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import kotlin.collections.List
import kotlin.collections.distinct
import kotlin.collections.drop
import kotlin.collections.emptyList
import kotlin.collections.filterNotNull
import kotlin.collections.first
import kotlin.collections.firstOrNull
import kotlin.collections.indexOfLast
import kotlin.collections.isNotEmpty
import kotlin.collections.listOf
import kotlin.collections.map
import kotlin.collections.mapIndexed
import kotlin.collections.mapNotNull
import kotlin.collections.toMutableList
import kotlin.collections.toTypedArray
import kotlin.sequences.filterNotNull
import kotlin.text.get
import kotlin.text.isBlank
import kotlin.text.set

@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    val metadata: Metadata,
    val spirc: SpircWrapper,
    val spClient: SpClient,
    private val repository: SearchRepository,
    private val playbackStateHolder: PlaybackStateHolder,
    private val settingsRepository: SettingsRepository,
    private val recommendations: Recommendations,
    private val likedDao: LikedDao,
    private val json: Json,
    private val userProfile: UserProfile,
) : ViewModel() {
    private val queryFlow = MutableStateFlow("")

    private val _results = MutableStateFlow<List<SearchUiModel>>(emptyList())
    val results: StateFlow<List<SearchUiModel>> = _results

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn

    private val _isRecommendationMode = MutableStateFlow(false)
    val isRecommendationMode: StateFlow<Boolean> = _isRecommendationMode

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

    val searchHistory: StateFlow<List<SearchHistoryItem>> = settingsRepository.searchHistory
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    private val _historyResults = MutableStateFlow<List<SearchUiModel>>(emptyList())
    val historyResults: StateFlow<List<SearchUiModel>> = _historyResults

    private val authorsCache = mutableMapOf<String, List<Profile>>()
    private val _authors = MutableStateFlow<Map<String, Profile>>(emptyMap())
    val authors: StateFlow<Map<String, Profile>> = _authors

    init {
        _isLoggedIn.value = spClient.isOAuthAuthenticated()

        viewModelScope.launch {
            queryFlow
                .debounce(500)
                .distinctUntilChanged()
                .collectLatest { query ->

                    if (query.isBlank()) {
                        _results.value = emptyList()
                        return@collectLatest
                    }

                    _results.value = listOf(
                        SearchUiModel.SectionHeader(R.string.search_section_tracks),
                        SearchUiModel.SkeletonItem(0),
                        SearchUiModel.SectionHeader(R.string.search_section_artists),
                        SearchUiModel.SkeletonItem(1),
                        SearchUiModel.SectionHeader(R.string.search_section_albums),
                        SearchUiModel.SkeletonItem(2),
                        SearchUiModel.SectionHeader(R.string.search_section_playlists),
                        SearchUiModel.SkeletonItem(3),
                        SearchUiModel.SectionHeader(R.string.search_section_shows),
                        SearchUiModel.SkeletonItem(4),
                        SearchUiModel.SectionHeader(R.string.search_section_episodes),
                        SearchUiModel.SkeletonItem(5),
                    )

                    launch {
                        searchSection("track", R.string.search_section_tracks) { uris ->
                            withContext(Dispatchers.IO) {
                                metadata.getTrackMetadata(uris).map { track ->
                                    SearchUiModel.TrackItem(track.uri, track)
                                }
                            }
                        }
                    }

                    launch {
                        searchSection("artist", R.string.search_section_artists) { uris ->
                            withContext(Dispatchers.IO) {
                                uris.mapNotNull { uri ->
                                    runCatching {
                                        metadata.getArtistMetadata(uri)
                                    }.getOrNull()?.let { artist ->
                                        SearchUiModel.ArtistItem(uri, artist)
                                    }
                                }
                            }
                        }
                    }

                    launch {
                        searchSection("album", R.string.search_section_albums) { uris ->
                            withContext(Dispatchers.IO) {
                                uris.mapNotNull { uri ->
                                    runCatching {
                                        metadata.getAlbumMetadata(uri)
                                    }.getOrNull()?.let { album ->
                                        SearchUiModel.AlbumItem(uri, album)
                                    }
                                }
                            }
                        }
                    }

                    launch {
                        searchSection("playlist", R.string.search_section_playlists) { uris ->
                            withContext(Dispatchers.IO) {
                                uris.mapNotNull { uri ->
                                    runCatching {
                                        metadata.getPlaylistMetadata(uri, true)
                                    }.getOrNull()?.let { playlist ->
                                        SearchUiModel.PlaylistItem(uri, playlist)
                                    }
                                }
                            }
                        }
                    }

                    launch {
                        searchSection("show", R.string.search_section_shows) { uris ->
                            withContext(Dispatchers.IO) {
                                uris.mapNotNull { uri ->
                                    runCatching {
                                        metadata.getShowMetadata(uri)
                                    }.getOrNull()?.let { show ->
                                        SearchUiModel.ShowItem(uri, show)
                                    }
                                }
                            }
                        }
                    }

                    launch {
                        searchSection("episode", R.string.search_section_episodes) { uris ->
                            withContext(Dispatchers.IO) {
                                runCatching {
                                    metadata.getEpisodeMetadata(uris)
                                }.getOrNull()
                                    ?.mapIndexed { index, episode ->
                                        SearchUiModel.EpisodeItem(uris[index], episode)
                                    }
                                    ?: emptyList()
                            }
                        }
                    }
                }
        }

        viewModelScope.launch {
            settingsRepository.searchHistory.collect { items ->
                if (items.isEmpty()) {
                    _historyResults.value = emptyList()
                    return@collect
                }
                val results = withContext(Dispatchers.IO) {
                    items.mapNotNull { item ->
                        try {
                            when (item.type) {
                                SearchResultType.TRACK -> {
                                    val tracks = metadata.getTrackMetadata(listOf(item.uri))
                                    tracks.firstOrNull()?.let { track ->
                                        SearchUiModel.TrackItem(item.uri, track)
                                    }
                                }

                                SearchResultType.ARTIST -> {
                                    val artist = metadata.getArtistMetadata(item.uri)
                                    artist?.let { SearchUiModel.ArtistItem(item.uri, it) }
                                }

                                SearchResultType.ALBUM -> {
                                    val album = metadata.getAlbumMetadata(item.uri)
                                    album?.let { SearchUiModel.AlbumItem(item.uri, it) }
                                }

                                SearchResultType.PLAYLIST -> {
                                    val playlist = metadata.getPlaylistMetadata(item.uri, true)
                                    playlist?.let { SearchUiModel.PlaylistItem(item.uri, it) }
                                }

                                SearchResultType.SHOW -> {
                                    val show = metadata.getShowMetadata(item.uri)
                                    show?.let { SearchUiModel.ShowItem(item.uri, it) }
                                }

                                SearchResultType.EPISODE -> {
                                    val episode = metadata.getEpisodeMetadata(item.uri)
                                    episode?.let { SearchUiModel.EpisodeItem(item.uri, it) }
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(
                                "SearchViewModel",
                                "Failed to load history metadata for ${item.uri}",
                                e
                            )
                            null
                        }
                    }
                }
                _historyResults.value = results
            }
        }
    }

    private suspend fun searchSection(
        type: String,
        headerRes: Int,
        fetch: suspend (List<String>) -> List<SearchUiModel>,
    ) {
        try {
            val results = repository.searchByType(queryFlow.value, type)
            val items = if (results.isNotEmpty()) {
                fetch(results.map { it.uri })
            } else emptyList()
            replaceSkeleton(headerRes, items)
        } catch (e: Exception) {
            Log.w("SearchViewModel", "$type search failed", e)
            replaceSkeleton(headerRes, emptyList())
        }
    }

    private fun replaceSkeleton(headerRes: Int, items: List<SearchUiModel>) {
        _results.update { current ->
            val out = current.toMutableList()
            val headerIdx =
                out.indexOfLast { it is SearchUiModel.SectionHeader && it.titleRes == headerRes }
            if (headerIdx < 0) return@update current
            val skeletonIdx = headerIdx + 1
            if (skeletonIdx >= out.size || out[skeletonIdx] !is SearchUiModel.SkeletonItem) return@update current

            if (items.isEmpty()) {
                out.removeAt(skeletonIdx)
                out.removeAt(headerIdx)
            } else {
                out[skeletonIdx] = items.first()
                out.addAll(skeletonIdx + 1, items.drop(1))
            }
            out
        }
    }

    fun onQueryChange(query: String) {
        queryFlow.value = query
    }

    fun fetchRecommendations(seedIds: List<String>, config: RecommendationConfig) {
        viewModelScope.launch {
            _isLoading.value = true
            val trackIds = recommendations.fetchRecommendations(50, seedIds.toTypedArray(), config)
            if (trackIds.isEmpty()) {
                _isLoading.value = false
                return@launch
            }
            val uris = trackIds.map { "spotify:track:$it" }
            val tracks = withContext(Dispatchers.IO) {
                metadata.getTrackMetadata(uris)
            }
            loadTrackResults(tracks)
        }
    }

    fun loadTrackResults(tracks: List<Track>) {
        _results.value = tracks.map { SearchUiModel.TrackItem(it.uri, it) }
        _isRecommendationMode.value = true
        _isLoading.value = false
    }

    suspend fun getArtworkUrl(playlist: Playlist): String? {
        return playlist.getCover(metadata)
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

    fun saveItem(uri: String) {
        viewModelScope.launch {
            if (!spClient.saveItems(arrayOf(uri))) {
                Log.w("SearchViewModel", "saveItem failed")
            }
        }
    }

    fun isLiked(uri: OutifyUri): Flow<Boolean> {
        val id = uri.id
        return if (uri.isTrack) {
            likedDao.observeIsTrackLiked(id)
        } else {
            likedDao.observeIsEpisodeLiked(id)
        }
    }

    fun setAudio(audio: PlayableAudio) {
        playbackStateHolder.setAudio(audio)
    }

    fun addToHistory(item: SearchUiModel) {
        val historyItem = when (item) {
            is SearchUiModel.TrackItem -> SearchHistoryItem(item.uri, SearchResultType.TRACK)
            is SearchUiModel.ArtistItem -> SearchHistoryItem(item.uri, SearchResultType.ARTIST)
            is SearchUiModel.AlbumItem -> SearchHistoryItem(item.uri, SearchResultType.ALBUM)
            is SearchUiModel.PlaylistItem -> SearchHistoryItem(item.uri, SearchResultType.PLAYLIST)
            is SearchUiModel.ShowItem -> SearchHistoryItem(item.uri, SearchResultType.SHOW)
            is SearchUiModel.EpisodeItem -> SearchHistoryItem(item.uri, SearchResultType.EPISODE)
            else -> return
        }
        viewModelScope.launch {
            settingsRepository.addSearchHistoryItems(listOf(historyItem))
        }
    }

    fun removeFromHistory(uri: String) {
        viewModelScope.launch {
            settingsRepository.removeSearchHistoryItem(uri)
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            settingsRepository.clearSearchHistory()
        }
    }
}

sealed class SearchUiModel {
    abstract val uri: String

    data class SectionHeader(
        @StringRes val titleRes: Int
    ) : SearchUiModel() {
        override val uri: String = "header_$titleRes"
    }

    data class SkeletonItem(
        val id: Int
    ) : SearchUiModel() {
        override val uri: String = "skeleton_$id"
    }

    data class TrackItem(
        override val uri: String,
        val track: Track
    ) : SearchUiModel()

    data class ArtistItem(
        override val uri: String,
        val artist: Artist
    ) : SearchUiModel()

    data class AlbumItem(
        override val uri: String,
        val album: Album
    ) : SearchUiModel()

    data class PlaylistItem(
        override val uri: String,
        val playlist: Playlist
    ) : SearchUiModel()

    data class ShowItem(
        override val uri: String,
        val show: Show
    ) : SearchUiModel()

    data class EpisodeItem(
        override val uri: String,
        val episode: Episode
    ) : SearchUiModel()
}
