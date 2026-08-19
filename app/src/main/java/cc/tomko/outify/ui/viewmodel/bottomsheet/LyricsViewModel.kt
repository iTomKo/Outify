package cc.tomko.outify.ui.viewmodel.bottomsheet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.tomko.outify.core.Spirc.SpircWrapper
import cc.tomko.outify.core.model.PlayableAudio
import cc.tomko.outify.core.model.SyncedLyric
import cc.tomko.outify.core.model.Track
import cc.tomko.outify.data.repository.PlayerRepository
import cc.tomko.outify.playback.PlaybackStateHolder
import dagger.hilt.android.lifecycle.HiltViewModel
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

    private val _lyrics = MutableStateFlow<List<SyncedLyric>>(emptyList())
    val lyrics: StateFlow<List<SyncedLyric>> = _lyrics.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _isCurrentTrack = MutableStateFlow(false)
    val isCurrentTrack: StateFlow<Boolean> = _isCurrentTrack.asStateFlow()

    private val lyricsCache = mutableMapOf<String, List<SyncedLyric>>()

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

    fun loadLyrics(track: Track) {
        _isCurrentTrack.value = playbackStateHolder.state.value.currentAudio?.id == track.id

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
}
