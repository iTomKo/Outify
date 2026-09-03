package cc.tomko.outify.ui.viewmodel.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.tomko.outify.core.EpisodeDetails
import cc.tomko.outify.core.SpClient
import cc.tomko.outify.core.spirc.SpircWrapper
import cc.tomko.outify.core.model.ConsumptionOrder
import cc.tomko.outify.core.model.Episode
import cc.tomko.outify.core.model.PlayableAudio
import cc.tomko.outify.core.model.toPlayableAudio
import cc.tomko.outify.core.model.toSpotifyUri
import cc.tomko.outify.data.dao.EpisodeDao
import cc.tomko.outify.data.dao.LikedDao
import cc.tomko.outify.data.metadata.Metadata
import cc.tomko.outify.data.repository.LikedRepository
import cc.tomko.outify.playback.PlaybackStateHolder
import cc.tomko.outify.ui.screens.library.show.ShowUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject

private const val SHOW_STATE_KEY = "show_state"
private const val EPISODES_PAGE_SIZE = 20

@HiltViewModel
class ShowDetailViewModel @Inject constructor(
    private val metadata: Metadata,
    private val playbackStateHolder: PlaybackStateHolder,
    val spirc: SpircWrapper,
    val spClient: SpClient,
    val json: Json,
    val likedDao: LikedDao,
    private val likedRepository: LikedRepository,
    private val episodeDao: EpisodeDao,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        savedStateHandle.get<String>(SHOW_STATE_KEY)?.let {
            try {
                json.decodeFromString<ShowUiState>(it)
            } catch (e: Exception) {
                ShowUiState()
            }
        } ?: ShowUiState()
    )
    val uiState: StateFlow<ShowUiState> = _uiState

    init {
        // Restoring user selected consumption order
        savedStateHandle.get<String>("consumption_order")?.let { name ->
            runCatching { ConsumptionOrder.valueOf(name) }.getOrNull()?.let { restored ->
                _uiState.update { it.copy(consumptionOrder = restored) }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val likedEpisodeIds: StateFlow<Set<String>> =
        likedDao.observeLikedIds()
            .map { it.toHashSet() }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptySet()
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
            val show = _uiState.value.show ?: return@launch
            val showId = show.id
            val uri = show.uri
            if (_isSaved.value) {
                spClient.deleteItems(arrayOf(uri))
                likedRepository.removeLikedShow(showId)
            } else {
                spClient.saveItems(arrayOf(uri))
                likedRepository.addLikedShow(showId)
            }
            _isSaved.value = !_isSaved.value
        }
    }

    private var _lastShowUri: String? = null

    fun retry() {
        val uri = _lastShowUri ?: return
        viewModelScope.launch {
            spirc.restart()
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            loadShow(uri)
        }
    }

    private fun checkIsSaved(showUri: String) {
        viewModelScope.launch {
            val showId = showUri.removePrefix("spotify:show:")
            _isSaved.value = likedRepository.isLikedShow(showId)
        }
    }

    private fun saveState(state: ShowUiState) {
        savedStateHandle[SHOW_STATE_KEY] = json.encodeToString(ShowUiState.serializer(), state)
    }

    suspend fun loadShow(showUri: String) {
        _lastShowUri = showUri
        val requestedOrder = _uiState.value.consumptionOrder
        loadShowInternal(showUri, requestedOrder)
    }

    fun setConsumptionOrder(order: ConsumptionOrder) {
        val showUri = _lastShowUri ?: return
        if (order == _uiState.value.consumptionOrder) return

        savedStateHandle["consumption_order"] = order.name

        _uiState.update {
            it.copy(
                consumptionOrder = order,
                episodes = emptyList(),
                hasMore = true,
                isLoadingMore = false,
                isLoading = true,
            )
        }

        viewModelScope.launch {
            loadShowInternal(showUri, order)
        }
    }

    private suspend fun loadShowInternal(showUri: String, order: ConsumptionOrder?) {
        try {
            val show = withContext(Dispatchers.IO) {
                metadata.getShowMetadata(showUri)
            }

            if (show == null) {
                val newState = ShowUiState(
                    isLoading = false,
                    error = "Show not found",
                    consumptionOrder = order,
                )
                _uiState.value = newState
                saveState(newState)
                return
            }

            val firstPageUris = show.episodes.take(EPISODES_PAGE_SIZE)

            val firstPage: List<Episode> = if (firstPageUris.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    metadata.getEpisodeMetadata(firstPageUris)
                }
            } else emptyList()

            val newState = ShowUiState(
                isLoading = false,
                show = show,
                episodes = firstPage,
                hasMore = show.episodes.size > firstPage.size,
                consumptionOrder = order ?: show.consumptionOrder,
            )
            _uiState.value = newState
            _isSaved.value = false
            checkIsSaved(showUri)
            saveState(newState)
        } catch (e: Exception) {
            val newState = ShowUiState(
                isLoading = false,
                error = e.message,
                consumptionOrder = order,
            )
            _uiState.value = newState
            saveState(newState)
        }
    }

    fun loadMoreEpisodes() {
        val state = _uiState.value
        val show = state.show ?: return
        if (state.isLoadingMore || !state.hasMore) return

        viewModelScope.launch {
            _uiState.value = state.copy(isLoadingMore = true)

            try {
                val alreadyLoaded = state.episodes.size
                val nextUris = show.episodes
                    .drop(alreadyLoaded)
                    .take(EPISODES_PAGE_SIZE)

                if (nextUris.isEmpty()) {
                    val newState = _uiState.value.copy(isLoadingMore = false, hasMore = false)
                    _uiState.value = newState
                    saveState(newState)
                    return@launch
                }

                val nextPage = withContext(Dispatchers.IO) {
                    metadata.getEpisodeMetadata(nextUris)
                }

                val combined = state.episodes + nextPage
                val newState = _uiState.value.copy(
                    episodes = combined,
                    isLoadingMore = false,
                    hasMore = show.episodes.size > combined.size,
                )
                _uiState.value = newState
                saveState(newState)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoadingMore = false)
            }
        }
    }

    fun setEpisode(episode: Episode) {
        playbackStateHolder.setAudio(episode.toPlayableAudio())
    }

    fun fetchEpisodeDetailsForShow() {
        val episodes = _uiState.value.episodes
        if (episodes.isEmpty()) return

        viewModelScope.launch {
            val updated = withContext(Dispatchers.IO) {
                episodes.map { episode ->
                    try {
                        val raw = spClient.getEpisodeDetails(episode.id)
                        val checked = spClient.checkAndHandleError(raw, "getEpisodeDetails:${episode.id}")
                        val details = EpisodeDetails.fromJson(checked)
                        episodeDao.updateEpisodePlayState(
                            episodeId = episode.id,
                            fullyPlayed = details.fullyPlayed,
                            resumePositionMs = details.resumePositionMs,
                        )
                        episode.copy(
                            fullyPlayed = details.fullyPlayed,
                            resumePositionMs = details.resumePositionMs,
                        )
                    } catch (_: Exception) {
                        episode
                    }
                }
            }
            _uiState.update { it.copy(episodes = updated) }
            saveState(_uiState.value)
        }
    }

    fun playEpisode(episode: Episode) {
        val showUri = _uiState.value.show?.uri
        spirc.load(showUri?.let { cc.tomko.outify.core.model.OutifyUri.fromUriString(it) }, episode.toSpotifyUri())
        setEpisode(episode)
        if (episode.resumePositionMs > 0 && !episode.fullyPlayed) {
            viewModelScope.launch {
                delay(600)
                spirc.seekTo(episode.resumePositionMs)
            }
        }
    }

    fun playNextInSequence() {
        val episodes = _uiState.value.episodes
        if (episodes.isEmpty()) return

        val nextEpisode = findNextEpisode(episodes)
        playEpisode(nextEpisode)
    }

    internal fun findNextEpisode(episodes: List<Episode>): Episode {
        val lastFullyPlayed = episodes.indexOfLast { it.fullyPlayed }
        val firstResumable = episodes.firstOrNull {
            !it.fullyPlayed && it.resumePositionMs > 0
        }
        return when {
            firstResumable != null -> firstResumable
            lastFullyPlayed in episodes.indices -> {
                val nextIndex = lastFullyPlayed + 1
                if (nextIndex < episodes.size) episodes[nextIndex] else episodes.first()
            }
            else -> episodes.first()
        }
    }
}