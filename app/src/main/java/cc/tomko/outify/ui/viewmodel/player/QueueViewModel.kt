package cc.tomko.outify.ui.viewmodel.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.tomko.outify.core.Spirc.SpircWrapper
import cc.tomko.outify.core.model.Track
import cc.tomko.outify.data.dao.LikedDao
import cc.tomko.outify.data.metadata.Metadata
import cc.tomko.outify.data.queue.SavedQueue
import cc.tomko.outify.data.repository.SavedQueueRepository
import cc.tomko.outify.data.repository.SettingsRepository
import cc.tomko.outify.playback.PlaybackStateHolder
import cc.tomko.outify.reccobeats.RecommendationConfig
import cc.tomko.outify.reccobeats.Recommendations
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
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
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min

data class QueueEntry(
    val id: Long,
    val track: Track,
    val isRecommendation: Boolean = false,
)

@HiltViewModel
class QueueViewModel @Inject constructor(
    val metadata: Metadata,
    val json: Json,
    private val playbackStateHolder: PlaybackStateHolder,
    val spirc: SpircWrapper,
    private val likedDao: LikedDao,
    private val savedQueueRepository: SavedQueueRepository,
    private val settingsRepository: SettingsRepository,
    private val recommendations: Recommendations,
) : ViewModel() {

    private val _recommendationTracks = MutableStateFlow<List<Track>>(emptyList())
    val recommendationTracks: StateFlow<List<Track>> = _recommendationTracks.asStateFlow()

    val queueRecommendationsEnabled: StateFlow<Boolean> = settingsRepository.queueRecommendationsEnabled
        .stateIn(viewModelScope, SharingStarted.Lazily, false)
    val queueRecommendationRatio: StateFlow<Float> = settingsRepository.queueRecommendationRatio
        .stateIn(viewModelScope, SharingStarted.Lazily, 0.3f)
    val queueRecommendationConfig: StateFlow<RecommendationConfig?> = settingsRepository.queueRecommendationConfig
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    fun setRecsEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setQueueRecommendationsEnabled(enabled) }
    }

    fun setRecRatio(ratio: Float) {
        viewModelScope.launch { settingsRepository.setQueueRecommendationRatio(ratio) }
    }

    fun setRecConfig(config: RecommendationConfig?) {
        viewModelScope.launch { settingsRepository.setQueueRecommendationConfig(config) }
    }

    val currentTrack: StateFlow<Track?> = playbackStateHolder.state
        .map { it.currentTrack }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null
        )

    val flipQueueGestures: StateFlow<Boolean> = settingsRepository.interfaceSettings
        .map { it.flipQueueGestures }
        .stateIn(viewModelScope, SharingStarted.Lazily, false)

    val isPlaying: StateFlow<Boolean> = playbackStateHolder.state
        .map { it.isPlaying }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = false
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    val likedTrackIds: StateFlow<Set<String>> =
        likedDao.observeLikedIds()
            .map { it.toHashSet() }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptySet()
            )

    companion object {
        private const val INITIAL_LOAD_SIZE = 20
        private const val PAGE_SIZE = 15
        private const val PREFETCH_THRESHOLD = 5

        private val uidCounter = AtomicLong(0L)
        private fun nextId(): Long = uidCounter.incrementAndGet()
    }

    private val _queueState = MutableStateFlow(QueueState())
    val queueState: StateFlow<QueueState> = _queueState.asStateFlow()

    // Full URI lists - kept in sync after every mutation
    private var allPreviousUris: List<String> = emptyList()
    private var allNextUris: List<String> = emptyList()

    // Parts of the queue outside the loaded window.
    private var unloadedPreviousHead: List<String> = emptyList()
    private var unloadedNextTail: List<String> = emptyList()

    private var previousLoadJob: Job? = null
    private var nextLoadJob: Job? = null

    val currentTrackEntry: QueueEntry?
        get() = _queueState.value.tracks.getOrNull(_queueState.value.currentIndex)

    suspend fun loadQueue(currentTrack: Track?) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _queueState.update { it.copy(isLoading = true, error = null) }

                allPreviousUris = loadPreviousUris()
                allNextUris = loadNextUris()

                val currentIndex = allPreviousUris.size
                val total =
                    allPreviousUris.size + (if (currentTrack != null) 1 else 0) + allNextUris.size

                val half = INITIAL_LOAD_SIZE / 2
                val startIndex = max(0, currentIndex - half)
                val endIndex = min(total, currentIndex + half + 1)

                val initialEntries = loadTracksInRange(startIndex, endIndex, currentTrack)

                unloadedPreviousHead = allPreviousUris.take(startIndex)
                val loadedNextCount = max(0, endIndex - allPreviousUris.size - 1)
                unloadedNextTail = allNextUris.drop(loadedNextCount)

                _queueState.update {
                    it.copy(
                        tracks = initialEntries,
                        currentIndex = initialEntries
                            .indexOfFirst { entry -> currentTrack != null && entry.track.uri == currentTrack.uri }
                            .let { idx -> if (idx >= 0) idx else 0 },
                        totalSize = total,
                        loadedRange = startIndex until endIndex,
                        isLoading = false,
                        isLoadingPrevious = false,
                        isLoadingNext = false,
                        error = null
                    )
                }
            } catch (e: Exception) {
                _queueState.update { it.copy(isLoading = false, error = e.message) }
            }
        }.join()
    }

    fun onScrollPositionChanged(
        firstVisibleIndex: Int,
        lastVisibleIndex: Int,
        currentTrack: Track?
    ) {
        val state = _queueState.value
        if (state.isLoading || state.tracks.isEmpty()) return
        if (state.isLoadingPrevious || state.isLoadingNext) return

        val loadedRange = state.loadedRange
        val canonicalFirst = loadedRange.first + firstVisibleIndex
        val canonicalLast = loadedRange.first + lastVisibleIndex

        if (canonicalFirst < loadedRange.first + PREFETCH_THRESHOLD && loadedRange.first > 0) {
            loadPreviousPage(currentTrack)
        } else if (canonicalLast > loadedRange.last - PREFETCH_THRESHOLD && loadedRange.last + 1 < state.totalSize) {
            loadNextPage(currentTrack)
        }
    }

    private fun loadPreviousPage(currentTrack: Track?) {
        if (previousLoadJob?.isActive == true) return
        previousLoadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val state = _queueState.value
                val currentRange = state.loadedRange
                val newStartIndex = max(0, currentRange.first - PAGE_SIZE)
                if (newStartIndex >= currentRange.first) return@launch

                _queueState.update { it.copy(isLoadingPrevious = true, error = null) }

                val newEntries = loadTracksInRange(newStartIndex, currentRange.first, currentTrack)

                unloadedPreviousHead = unloadedPreviousHead.drop(newEntries.size)

                _queueState.update { current ->
                    current.copy(
                        tracks = newEntries + current.tracks,
                        currentIndex = current.currentIndex + newEntries.size,
                        loadedRange = newStartIndex until current.loadedRange.last,
                        isLoadingPrevious = false
                    )
                }
            } catch (e: Exception) {
                _queueState.update { it.copy(isLoadingPrevious = false, error = e.message) }
            }
        }
    }

    private fun loadNextPage(currentTrack: Track?) {
        if (nextLoadJob?.isActive == true) return
        nextLoadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val state = _queueState.value
                val currentRange = state.loadedRange
                val newEndIndex = min(state.totalSize, currentRange.last + PAGE_SIZE)
                if (newEndIndex <= currentRange.last + 1) return@launch

                _queueState.update { it.copy(isLoadingNext = true, error = null) }

                val newEntries = loadTracksInRange(currentRange.last, newEndIndex, currentTrack)

                unloadedNextTail = unloadedNextTail.drop(newEntries.size)

                _queueState.update { current ->
                    current.copy(
                        tracks = current.tracks + newEntries,
                        loadedRange = current.loadedRange.first until newEndIndex,
                        isLoadingNext = false
                    )
                }
            } catch (e: Exception) {
                _queueState.update { it.copy(isLoadingNext = false, error = e.message) }
            }
        }
    }

    private suspend fun loadTracksInRange(
        startIndex: Int,
        endIndex: Int,
        currentTrack: Track?
    ): List<QueueEntry> = withContext(Dispatchers.IO) {
        val urisToLoad = mutableListOf<String>()
        val positionsToUriIndex = mutableListOf<Pair<Int, Int>>()
        val currentTrackIndex = allPreviousUris.size

        for (i in startIndex until endIndex) {
            when {
                i < allPreviousUris.size -> {
                    positionsToUriIndex += (i to urisToLoad.size)
                    urisToLoad.add(allPreviousUris[i])
                }

                i == currentTrackIndex && currentTrack != null -> {
                    positionsToUriIndex += (i to -1)
                }

                i > currentTrackIndex -> {
                    val nextIndex = i - currentTrackIndex - 1
                    if (nextIndex < allNextUris.size) {
                        positionsToUriIndex += (i to urisToLoad.size)
                        urisToLoad.add(allNextUris[nextIndex])
                    } else {
                        positionsToUriIndex += (i to -2)
                    }
                }
            }
        }

        val loadedTracks: List<Track> = if (urisToLoad.isNotEmpty()) {
            try {
                metadata.getTrackMetadata(urisToLoad)
            } catch (t: Throwable) {
                emptyList()
            }
        } else {
            emptyList()
        }

        val entries = mutableListOf<QueueEntry>()
        var loadedCursor = 0
        for ((_, uriLoadIndex) in positionsToUriIndex) {
            when {
                uriLoadIndex >= 0 -> {
                    loadedTracks.getOrNull(loadedCursor)?.let {
                        entries.add(QueueEntry(nextId(), it))
                        loadedCursor++
                    }
                }

                uriLoadIndex == -1 -> currentTrack?.let { entries.add(QueueEntry(nextId(), it)) }
                // -2 = out-of-range, skip
            }
        }
        entries
    }

    private suspend fun syncQueueToSpirc() = withContext(Dispatchers.IO) {
        val state = _queueState.value
        val realTracks = state.tracks.filter { !it.isRecommendation }

        val currentRealIndex = realTracks.indexOfFirst { it.id == state.tracks.getOrNull(state.currentIndex)?.id }
            .coerceAtLeast(0)

        val loadedPreviousUris = realTracks.take(currentRealIndex).map { it.track.uri }
        val loadedNextUris = realTracks.drop(currentRealIndex + 1).map { it.track.uri }

        val newPreviousUris = unloadedPreviousHead + loadedPreviousUris
        val newNextUris = loadedNextUris + unloadedNextTail

        // Keep local caches in sync so pagination still works correctly
        allPreviousUris = newPreviousUris
        allNextUris = newNextUris

        try {
            spirc.setQueue(newNextUris.toTypedArray(), currentTrackEntry?.track?.uri)
        } catch (e: Exception) {
            _queueState.update { it.copy(error = e.message) }
        }
    }

    fun setQueueEntries(
        entries: List<QueueEntry>,
        startIndex: Int = _queueState.value.currentIndex
    ) {
        if (entries.isEmpty()) {
            _queueState.value = QueueState()
            return
        }
        val realEntries = entries.filter { !it.isRecommendation }
        val safeIndex = startIndex.coerceIn(0, realEntries.size - 1)
        val realCurrentIndex = realEntries.indexOfFirst { it.track.uri == currentTrackEntry?.track?.uri }
            .coerceAtLeast(0)
        _queueState.value = QueueState(
            tracks = realEntries,
            currentIndex = realCurrentIndex,
            totalSize = unloadedPreviousHead.size + realEntries.size + unloadedNextTail.size,
            loadedRange = unloadedPreviousHead.size until (unloadedPreviousHead.size + realEntries.size)
        )
        viewModelScope.launch { syncQueueToSpirc() }
    }

    fun debouncedSaveToRepository(entries: List<QueueEntry>) {
        viewModelScope.launch {
            val realUris = entries.filter { !it.isRecommendation }.map { it.track.uri }
            val queue = SavedQueue(
                id = "current",
                name = "Current Queue",
                trackUris = realUris,
                currentIndex = _queueState.value.currentIndex
            )
            savedQueueRepository.debouncedSaveQueue(queue)
        }
    }

    private suspend fun loadPreviousUris(): List<String> = withContext(Dispatchers.IO) {
        try {
            json.decodeFromString<List<String>>(spirc.previousTracks())
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun loadNextUris(): List<String> = withContext(Dispatchers.IO) {
        try {
            json.decodeFromString<List<String>>(spirc.nextTracks())
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun fetchQueueRecommendations(seedUris: List<String>, config: RecommendationConfig) {
        viewModelScope.launch(Dispatchers.IO) {
            val allIds = mutableListOf<String>()
            val idCounts = mutableMapOf<String, Int>()

            for (uri in seedUris.take(5)) {
                val seedId = uri.substringAfter("spotify:track:").ifEmpty { uri }
                val ids = recommendations.fetchRecommendations(50, arrayOf(seedId), config)
                for (id in ids) {
                    idCounts[id] = (idCounts[id] ?: 0) + 1
                }
                allIds.addAll(ids)
            }

            val sortedIds = allIds.distinct()
                .sortedByDescending { idCounts[it] ?: 0 }
                .take(50)

            if (sortedIds.isEmpty()) {
                _recommendationTracks.value = emptyList()
                return@launch
            }

            val uris = sortedIds.map { "spotify:track:$it" }
            val tracks = try {
                metadata.getTrackMetadata(uris)
            } catch (t: Throwable) {
                emptyList()
            }
            _recommendationTracks.value = tracks
        }
    }

    override fun onCleared() {
        super.onCleared()
        previousLoadJob?.cancel()
        nextLoadJob?.cancel()
    }
}

data class QueueState(
    val tracks: List<QueueEntry> = emptyList(),
    val currentIndex: Int = 0,
    val totalSize: Int = 0,
    val loadedRange: IntRange = 0 until 0,
    val isLoading: Boolean = false,
    val isLoadingPrevious: Boolean = false,
    val isLoadingNext: Boolean = false,
    val error: String? = null
)