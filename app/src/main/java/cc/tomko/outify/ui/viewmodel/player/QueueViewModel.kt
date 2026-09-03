package cc.tomko.outify.ui.viewmodel.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.tomko.outify.core.spirc.SpircWrapper
import cc.tomko.outify.core.model.PlayableAudio
import cc.tomko.outify.core.model.Track
import cc.tomko.outify.core.model.toPlayableAudio
import cc.tomko.outify.data.dao.LikedDao
import cc.tomko.outify.data.metadata.Metadata
import cc.tomko.outify.data.queue.SavedQueue
import cc.tomko.outify.data.repository.SavedQueueRepository
import cc.tomko.outify.data.repository.SettingsRepository
import cc.tomko.outify.playback.PlaybackStateHolder
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

data class QueueEntry(val id: Long, val audio: PlayableAudio)

@HiltViewModel
class QueueViewModel @Inject constructor(
    val metadata: Metadata,
    val json: Json,
    private val playbackStateHolder: PlaybackStateHolder,
    val spirc: SpircWrapper,
    private val likedDao: LikedDao,
    private val savedQueueRepository: SavedQueueRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val currentAudio: StateFlow<PlayableAudio?> = playbackStateHolder.state
        .map { it.currentAudio }
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

    val isShuffling: StateFlow<Boolean> = settingsRepository.shuffleEnabled
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

    fun toggleShuffle() {
        val newValue = !isShuffling.value

        viewModelScope.launch {
            settingsRepository.setShuffle(newValue)
            spirc.shuffle(newValue)
            loadQueue(currentAudio.value)
        }
    }

    val currentTrackEntry: QueueEntry?
        get() = _queueState.value.tracks.getOrNull(_queueState.value.currentIndex)

    suspend fun loadQueue(currentAudio: PlayableAudio?) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _queueState.update { it.copy(isLoading = true, error = null) }

                allPreviousUris = loadPreviousUris()
                allNextUris = loadNextUris()

                val currentIndex = allPreviousUris.size
                val total =
                    allPreviousUris.size + (if (currentAudio != null) 1 else 0) + allNextUris.size

                val half = INITIAL_LOAD_SIZE / 2
                val startIndex = max(0, currentIndex - half)
                val endIndex = min(total, currentIndex + half + 1)

                val initialEntries = loadAudioInRange(startIndex, endIndex, currentAudio)

                unloadedPreviousHead = allPreviousUris.take(startIndex)
                val loadedNextCount = max(0, endIndex - allPreviousUris.size - 1)
                unloadedNextTail = allNextUris.drop(loadedNextCount)

                _queueState.update {
                    it.copy(
                        tracks = initialEntries,
                        currentIndex = initialEntries
                            .indexOfFirst { entry -> currentAudio != null && entry.audio.uri == currentAudio.uri }
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
        currentAudio: PlayableAudio?
    ) {
        val state = _queueState.value
        if (state.isLoading || state.tracks.isEmpty()) return
        if (state.isLoadingPrevious || state.isLoadingNext) return

        val loadedRange = state.loadedRange
        val canonicalFirst = loadedRange.first + firstVisibleIndex
        val canonicalLast = loadedRange.first + lastVisibleIndex

        if (canonicalFirst < loadedRange.first + PREFETCH_THRESHOLD && loadedRange.first > 0) {
            loadPreviousPage(currentAudio)
        } else if (canonicalLast > loadedRange.last - PREFETCH_THRESHOLD && loadedRange.last + 1 < state.totalSize) {
            loadNextPage(currentAudio)
        }
    }

    private fun loadPreviousPage(currentAudio: PlayableAudio?) {
        if (previousLoadJob?.isActive == true) return
        previousLoadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val state = _queueState.value
                val currentRange = state.loadedRange
                val newStartIndex = max(0, currentRange.first - PAGE_SIZE)
                if (newStartIndex >= currentRange.first) return@launch

                _queueState.update { it.copy(isLoadingPrevious = true, error = null) }

                val newEntries = loadAudioInRange(newStartIndex, currentRange.first, currentAudio)

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

    private fun loadNextPage(currentAudio: PlayableAudio?) {
        if (nextLoadJob?.isActive == true) return
        nextLoadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val state = _queueState.value
                val currentRange = state.loadedRange
                val newEndIndex = min(state.totalSize, currentRange.last + PAGE_SIZE)
                if (newEndIndex <= currentRange.last + 1) return@launch

                _queueState.update { it.copy(isLoadingNext = true, error = null) }

                val newEntries = loadAudioInRange(currentRange.last, newEndIndex, currentAudio)

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

    private suspend fun loadAudioInRange(
        startIndex: Int,
        endIndex: Int,
        currentAudio: PlayableAudio?
    ): List<QueueEntry> = withContext(Dispatchers.IO) {
        val urisToLoad = mutableListOf<Pair<Int, String>>()
        val currentTrackIndex = allPreviousUris.size

        for (i in startIndex until endIndex) {
            when {
                i < allPreviousUris.size -> {
                    urisToLoad += i to allPreviousUris[i]
                }

                i == currentTrackIndex && currentAudio != null -> {
                    // Current audio is already loaded.
                }

                i > currentTrackIndex -> {
                    val nextIndex = i - currentTrackIndex - 1

                    if (nextIndex < allNextUris.size) {
                        urisToLoad += i to allNextUris[nextIndex]
                    }
                }
            }
        }

        val trackUris = urisToLoad
            .filter { (_, uri) -> uri.startsWith("spotify:track:") }
            .map { (_, uri) -> uri }

        val episodeUris = urisToLoad
            .filter { (_, uri) -> !uri.startsWith("spotify:track:") }
            .map { (_, uri) -> uri }

        val tracks = runCatching {
            metadata.getTrackMetadata(trackUris)
        }.getOrDefault(emptyList())

        val episodes = runCatching {
            metadata.getEpisodeMetadata(episodeUris)
        }.getOrDefault(emptyList())

        val tracksByUri = tracks.associateBy { it.uri }

        val episodesByUri = episodes
            .mapIndexedNotNull { index, episode ->
                episodeUris.getOrNull(index)?.let { uri ->
                    uri to episode
                }
            }
            .toMap()

        buildList {
            for (i in startIndex until endIndex) {
                when {
                    i < allPreviousUris.size -> {
                        val uri = allPreviousUris[i]

                        tracksByUri[uri]?.let {
                            add(QueueEntry(nextId(), it.toPlayableAudio()))
                        }

                        episodesByUri[uri]?.let {
                            add(QueueEntry(nextId(), it.toPlayableAudio()))
                        }
                    }

                    i == currentTrackIndex -> {
                        currentAudio?.let {
                            add(QueueEntry(nextId(), it))
                        }
                    }

                    i > currentTrackIndex -> {
                        val nextIndex = i - currentTrackIndex - 1

                        if (nextIndex < allNextUris.size) {
                            val uri = allNextUris[nextIndex]

                            tracksByUri[uri]?.let {
                                add(QueueEntry(nextId(), it.toPlayableAudio()))
                            }

                            episodesByUri[uri]?.let {
                                add(QueueEntry(nextId(), it.toPlayableAudio()))
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun syncQueueToSpirc() = withContext(Dispatchers.IO) {
        val state = _queueState.value

        val loadedPreviousUris = state.tracks.take(state.currentIndex).map { it.audio.uri }
        val loadedNextUris = state.tracks.drop(state.currentIndex + 1).map { it.audio.uri }

        val newPreviousUris = unloadedPreviousHead + loadedPreviousUris
        val newNextUris = loadedNextUris + unloadedNextTail

        // Keep local caches in sync so pagination still works correctly
        allPreviousUris = newPreviousUris
        allNextUris = newNextUris

        try {
            spirc.setQueue(newNextUris.toTypedArray(), currentTrackEntry?.audio?.uri)
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
        val safeIndex = startIndex.coerceIn(0, entries.size - 1)
        _queueState.value = QueueState(
            tracks = entries,
            currentIndex = safeIndex,
            totalSize = unloadedPreviousHead.size + entries.size + unloadedNextTail.size,
            loadedRange = unloadedPreviousHead.size until (unloadedPreviousHead.size + entries.size)
        )
        viewModelScope.launch { syncQueueToSpirc() }
    }

    fun debouncedSaveToRepository(entries: List<QueueEntry>) {
        viewModelScope.launch {
            val queue = SavedQueue(
                id = "current",
                name = "Current Queue",
                trackUris = entries.map { it.audio.uri },
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