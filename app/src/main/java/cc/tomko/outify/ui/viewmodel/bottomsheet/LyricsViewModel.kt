package cc.tomko.outify.ui.viewmodel.bottomsheet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.tomko.outify.core.spirc.SpircWrapper
import cc.tomko.outify.core.model.LyricLine
import cc.tomko.outify.core.model.PlayableAudio
import cc.tomko.outify.core.model.Track
import cc.tomko.outify.data.repository.PlayerRepository
import cc.tomko.outify.playback.PlaybackStateHolder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@HiltViewModel
class LyricsViewModel @Inject constructor(
    private val playerRepository: PlayerRepository,
    private val playbackStateHolder: PlaybackStateHolder,
    private val spirc: SpircWrapper,
) : ViewModel() {

    private val _lyrics = MutableStateFlow<List<LyricLine>>(emptyList())
    val lyrics: StateFlow<List<LyricLine>> = _lyrics.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _isCurrentTrack = MutableStateFlow(false)
    val isCurrentTrack: StateFlow<Boolean> = _isCurrentTrack.asStateFlow()

    private val _displayedTrack = MutableStateFlow<Track?>(null)
    val displayedTrack: StateFlow<Track?> = _displayedTrack.asStateFlow()

    private val _isEpisode = MutableStateFlow(false)
    val isEpisode: StateFlow<Boolean> = _isEpisode.asStateFlow()

    val hasSyncedContent: StateFlow<Boolean> = _lyrics
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val lyricsCache = mutableMapOf<String, List<LyricLine>>()
    private var followCurrentTrack = false
    private var currentAudioObserver: Job? = null

    val currentAudio: StateFlow<PlayableAudio?> = playbackStateHolder.state
        .map { it.currentAudio }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val isPlaying: StateFlow<Boolean> = playbackStateHolder.state
        .map { it.isPlaying }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val durationMs: StateFlow<Long> = playbackStateHolder.state
        .map { it.currentAudio?.duration ?: 0L }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    init {
        viewModelScope.launch {
            while (isActive) {
                _positionMs.value = playbackStateHolder.estimatePosition().inWholeMilliseconds
                delay(250L)
            }
        }
    }

    fun loadLyrics(track: Track, followCurrentTrack: Boolean = false) {
        this.followCurrentTrack = followCurrentTrack
        _displayedTrack.value = track
        _isEpisode.value = false

        _isCurrentTrack.value = playbackStateHolder.state.value.currentAudio?.id == track.id

        fetchLyrics(track)

        currentAudioObserver?.cancel()
        currentAudioObserver = viewModelScope.launch {
            currentAudio.collect { audio ->
                if (audio == null) return@collect

                val currentId = audio.id
                val displayedId = _displayedTrack.value?.id
                val matchesDisplayed = currentId == displayedId

                if (matchesDisplayed) {
                    _isCurrentTrack.value = true
                    return@collect
                }

                if (!this@LyricsViewModel.followCurrentTrack) {
                    _isCurrentTrack.value = false
                    return@collect
                }

                // following new audio track
                if (audio.isTrack()) {
                    val sourceTrack = audio.sourceTrack
                    if (sourceTrack != null) {
                        _displayedTrack.value = sourceTrack
                        _isEpisode.value = false
                        fetchLyrics(sourceTrack)
                        _isCurrentTrack.value = true
                    } else {
                        _isCurrentTrack.value = false
                    }
                } else {
                    _isEpisode.value = true
                    _lyrics.value = emptyList()
                    _isCurrentTrack.value = false
                }
            }
        }
    }

    private fun fetchLyrics(track: Track) {
        val trackId = track.id
        val cached = lyricsCache[trackId]
        if (cached != null) {
            _lyrics.value = cached
            return
        }
        viewModelScope.launch {
            val result = playerRepository.getLyrics(track)
            _lyrics.value = result
            lyricsCache[trackId] = result
        }
    }

    fun seekTo(timestampMs: Long) {
        viewModelScope.launch {
            spirc.seekTo(timestampMs)
            playbackStateHolder.seekTo(timestampMs.toDuration(DurationUnit.MILLISECONDS))
        }
    }

    fun playPause() {
        viewModelScope.launch {
            spirc.playerPlayPause()
            playbackStateHolder.setPlaying(!playbackStateHolder.state.value.isPlaying)
        }
    }

    fun skipPrevious() {
        viewModelScope.launch {
            spirc.playerPrevious()
        }
    }

    fun skipNext() {
        viewModelScope.launch {
            spirc.playerNext()
        }
    }
}
