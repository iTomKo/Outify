package cc.tomko.outify.ui.viewmodel.library

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.tomko.outify.core.Spirc.SpircWrapper
import cc.tomko.outify.core.model.CoverSize
import cc.tomko.outify.core.model.PlayableAudio
import cc.tomko.outify.core.model.Track
import cc.tomko.outify.core.model.getCover
import cc.tomko.outify.data.database.toDomain
import cc.tomko.outify.data.repository.LikedRepository
import cc.tomko.outify.playback.PlaybackStateHolder
import cc.tomko.outify.services.SyncNotificationManager
import coil3.ImageLoader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class SortBy {
    POSITION,       // Default - added index
    ARTIST_NAME,
    TRACK_NAME,
    DURATION
}

enum class ExplicitFilter {
    BOTH,               // Show all tracks
    EXPLICIT_ONLY,      // Show only explicit tracks
    NON_EXPLICIT_ONLY   // Show only non-explicit tracks
}

@HiltViewModel
class LikedViewModel @Inject constructor(
    val spirc: SpircWrapper,
    val imageLoader: ImageLoader,
    private val likedRepository: LikedRepository,
    private val playbackStateHolder: PlaybackStateHolder,
    private val syncNotificationManager: SyncNotificationManager,
) : ViewModel() {
    val isRefreshing = MutableStateFlow(false)
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    // Filter and sort states
    val filterExplicit = MutableStateFlow(ExplicitFilter.BOTH)
    val filterArtistName = MutableStateFlow("")
    val filterTrackName = MutableStateFlow("")
    val sortBy = MutableStateFlow(SortBy.POSITION)
    val sortAscending = MutableStateFlow(true)

    private val _isFetchingTracks = MutableStateFlow(false)
    val isFetchingTracks: StateFlow<Boolean> = _isFetchingTracks

    private val _fetchedCount = MutableStateFlow(0)
    val fetchedCount: StateFlow<Int> = _fetchedCount

    companion object {
        private const val PAGE_SIZE = 30
        private const val PREFETCH_THRESHOLD = 8
    }

    /** Total liked count from liked_songs — available even before metadata loads */
    val totalCount: StateFlow<Int> = likedRepository.observeCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val likedTracks: StateFlow<List<Track>> =
        _query
            .debounce(250)
            .mapLatest { q ->
                if (q.isBlank()) {
                    likedRepository.observeLikedTracksWithDetails()
                } else {
                    likedRepository.observeSearchLikedTracks(q)
                }
            }
            .flatMapLatest { it }
            .mapLatest { rows ->
                if (rows.isEmpty()) return@mapLatest emptyList()

                val albums = likedRepository.getAlbumsForTracks(rows)

                rows.mapNotNull { twa ->
                    runCatching {
                        twa.toDomain(twa.track.albumId?.let { albums[it] })
                    }.getOrNull()
                }
            }
            .flatMapLatest { tracks ->
                // Combine with filter/sort states
                kotlinx.coroutines.flow.combine(
                    filterExplicit,
                    filterArtistName,
                    filterTrackName,
                    sortBy,
                    sortAscending
                ) { explicit, artist, trackName, sort, ascending ->
                    applyFiltersAndSorts(tracks, explicit, artist, trackName, sort, ascending)
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Guards against duplicate concurrent fetches
    private val fetchLock = Mutex()
    private var lastFetchedOffset = -1

    init {
        refresh()
    }

    fun refresh() {
        if (spirc.isUsable) {
            syncNotificationManager.showIndeterminate()
            viewModelScope.launch {
                isRefreshing.value = true
                var totalTracks = 0
                val result = runCatching {
                    likedRepository.syncLikedTracks(
                        onProgress = { current, total ->
                            totalTracks = total
                            _fetchedCount.value = current
                            syncNotificationManager.showProgress(current, total)
                        }
                    )
                }
                result.onSuccess {
                    if (totalTracks > 0) {
                        syncNotificationManager.showComplete(totalTracks)
                    } else {
                        syncNotificationManager.cancel()
                    }

                    isRefreshing.value = false
                }.onFailure {
                    syncNotificationManager.showError(it.message ?: "Sync failed")

                    isRefreshing.value = false
                }

                // Sync liked episodes in the background (no progress UI)
                launch {
                    runCatching {
                        likedRepository.syncLikedEpisodes()
                    }.onFailure {
                        Log.w("LikedViewModel", "syncLikedEpisodes failed", it)
                    }
                }
            }
            // Kick off the first page
            triggerLoad(offset = 0)
        }
    }

    /**
     * Called from the screen as the visible index advances.
     */
    fun onVisibleIndex(visibleIndex: Int) {
        val loaded = likedTracks.value.size
        if (visibleIndex >= loaded - PREFETCH_THRESHOLD) {
            triggerLoad(offset = loaded)
        }
    }

    private fun triggerLoad(offset: Int) {
        if (offset == lastFetchedOffset) return
        viewModelScope.launch {
            var acquired = false
            try {
                fetchLock.withLock {
                    if (offset == lastFetchedOffset) return@withLock
                    lastFetchedOffset = offset
                    _isFetchingTracks.value = true
                    acquired = true
                }
                withContext(Dispatchers.IO) {
                    runCatching {
                        likedRepository.ensureWindowLoaded(offset, PAGE_SIZE)
                    }.onFailure {
                        Log.w("LikedViewModel", "Failed to load window at $offset", it)
                    }
                }
            } finally {
                if (acquired) {
                    _isFetchingTracks.value = false
                }
            }
        }
    }

    fun onQueryChange(newQuery: String) {
        _query.value = newQuery
    }

    fun setFilterExplicit(value: ExplicitFilter) {
        filterExplicit.value = value
    }

    fun setFilterArtistName(value: String) {
        filterArtistName.value = value
    }

    fun setFilterTrackName(value: String) {
        filterTrackName.value = value
    }

    fun setSortBy(value: SortBy) {
        sortBy.value = value
    }

    fun setSortAscending(value: Boolean) {
        sortAscending.value = value
    }

    private fun applyFiltersAndSorts(
        tracks: List<Track>,
        explicitFilter: ExplicitFilter,
        artistNameFilter: String,
        trackNameFilter: String,
        sort: SortBy,
        ascending: Boolean
    ): List<Track> {
        var result = tracks

        // Apply explicit filter
        result = when (explicitFilter) {
            ExplicitFilter.EXPLICIT_ONLY -> result.filter { it.explicit }
            ExplicitFilter.NON_EXPLICIT_ONLY -> result.filter { !it.explicit }
            ExplicitFilter.BOTH -> result
        }

        if (artistNameFilter.isNotBlank()) {
            result = result.filter { track ->
                track.artists.any { artist ->
                    artist.name.contains(artistNameFilter, ignoreCase = true)
                }
            }
        }

        if (trackNameFilter.isNotBlank()) {
            result = result.filter { track ->
                track.name.contains(trackNameFilter, ignoreCase = true)
            }
        }

        // Apply sorting
        result = when (sort) {
            SortBy.POSITION -> result // Already in position order
            SortBy.ARTIST_NAME -> result.sortedBy { track ->
                track.artists.firstOrNull()?.name?.lowercase() ?: ""
            }

            SortBy.TRACK_NAME -> result.sortedBy { it.name.lowercase() }
            SortBy.DURATION -> result.sortedBy { it.duration }
        }

        if (!ascending) {
            result = result.reversed()
        }

        return result
    }

    fun getArtwork(): Flow<String?> =
        likedTracks.map { tracks ->
            tracks.firstOrNull()
                ?.album
                ?.getCover(CoverSize.LARGE)
                ?.uri
        }

    fun setAudio(audio: PlayableAudio) {
        playbackStateHolder.setAudio(audio)
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
}