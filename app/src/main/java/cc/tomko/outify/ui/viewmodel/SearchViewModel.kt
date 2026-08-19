package cc.tomko.outify.ui.viewmodel

import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.tomko.outify.R
import cc.tomko.outify.core.SpClient
import cc.tomko.outify.core.Spirc.SpircWrapper
import cc.tomko.outify.core.model.Album
import cc.tomko.outify.core.model.Artist
import cc.tomko.outify.core.model.Episode
import cc.tomko.outify.core.model.Playlist
import cc.tomko.outify.core.model.Show
import cc.tomko.outify.core.model.Track
import cc.tomko.outify.core.model.getCover
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
import javax.inject.Inject

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

    val currentTrack: StateFlow<Track?> = playbackStateHolder.state
        .map { it.currentTrack }
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
                                        println(show)
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
                                        println(episode)
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

    fun saveItem(uri: String) {
        viewModelScope.launch {
            if (!spClient.saveItems(arrayOf(uri))) {
                Log.w("SearchViewModel", "saveItem failed")
            }
        }
    }

    fun setTrack(track: Track) {
        playbackStateHolder.setTrack(track)
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