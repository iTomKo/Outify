package cc.tomko.outify.ui.viewmodel.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import cc.tomko.outify.core.SpClient
import cc.tomko.outify.core.spirc.SpircWrapper
import cc.tomko.outify.core.model.CoverSize
import cc.tomko.outify.core.model.PlayableAudio
import cc.tomko.outify.core.model.LyricLine
import cc.tomko.outify.core.model.Track
import cc.tomko.outify.core.model.getCover
import cc.tomko.outify.data.dao.LikedDao
import cc.tomko.outify.data.repository.LikedRepository
import cc.tomko.outify.data.repository.PlayerRepository
import cc.tomko.outify.data.repository.SettingsRepository
import cc.tomko.outify.playback.PlaybackStateHolder
import cc.tomko.outify.playback.model.PlaybackState
import cc.tomko.outify.playback.model.RepeatMode
import cc.tomko.outify.ui.model.player.PlayerAction
import cc.tomko.outify.ui.model.player.PlayerUIState
import coil3.ImageLoader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@HiltViewModel
class PlayerViewModel @Inject constructor(
    val spirc: SpircWrapper,
    val imageLoader: ImageLoader,
    private val playerRepository: PlayerRepository,
    private val playbackStateHolder: PlaybackStateHolder,
    private val settingsRepository: SettingsRepository,
    private val likedDao: LikedDao,
    private val likedRepository: LikedRepository,
    private val spClient: SpClient,
) : ViewModel() {
    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _lyrics = MutableStateFlow<List<LyricLine>>(emptyList())
    val lyrics: StateFlow<List<LyricLine>> = _lyrics

    private val _positionMs =
        MutableStateFlow(playbackStateHolder.estimatePosition().inWholeMilliseconds)
    val positionMs = _positionMs.asStateFlow()

    val isShuffling = settingsRepository.shuffleEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val romanizeLyrics: StateFlow<Boolean> = settingsRepository.romanizeLyrics
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val repeatMode: Flow<RepeatMode> = settingsRepository.repeatMode

    @OptIn(ExperimentalCoroutinesApi::class)
    val isLiked: StateFlow<Boolean> =
        playbackStateHolder.state
            .map { it.currentAudio }
            .flatMapLatest { audio ->
                if (audio == null || audio.isEpisode()) {
                    flowOf(false)
                } else {
                    likedDao.observeIsTrackLiked(audio.id)
                }
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                false
            )

    val forwardMilliseconds: Flow<Long> =
        settingsRepository.playbackSettings
            .map { it.forwardMilliseconds }

    init {
        viewModelScope.launch {
            while (isActive) {
                _positionMs.value = playbackStateHolder.estimatePosition().inWholeMilliseconds
                delay(250L)
            }
        }

        viewModelScope.launch {
            playbackStateHolder.state.collect { playback ->
                _state.value = playback
            }
        }
    }

    val uiState: StateFlow<PlayerUIState> =
        playbackStateHolder.state
            .map { state ->
                val audio = state.currentAudio
                val position = state.position
                PlayerUIState(
                    title = audio?.name ?: "Unknown Track",
                    artists = audio?.artists ?: emptyList(),
                    albumArt = audio?.getCover(CoverSize.LARGE)?.uri,
                    isPlaying = state.isPlaying,
                    isExplicit = audio?.explicit ?: false,
                    playbackSpeed = state.playbackSpeed,
                    totalLengthMs = audio?.duration ?: 0L,
                    positionMs = position.active.inWholeMilliseconds,
                    lastUpdateTime = position.lastSync,
                    isBuffering = state.isBuffering,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlayerUIState())

    /**
     * On Player UI action - like play/pause/..
     */
    fun onAction(action: PlayerAction) {
        viewModelScope.launch {
            when (action) {
                PlayerAction.PlayPause -> {
                    spirc.playerPlayPause()
                    viewModelScope.launch {
                        playbackStateHolder.setPlaying(!playbackStateHolder.state.value.isPlaying)
                    }
                }

                PlayerAction.Next -> spirc.playerNext()
                PlayerAction.Previous -> {
                    spirc.playerPrevious()
                    viewModelScope.launch {
                        playbackStateHolder.seekTo(Duration.ZERO)
                    }
                }

                is PlayerAction.SeekTo -> {
                    viewModelScope.launch {
                        playbackStateHolder.seekTo(action.position.toDuration(DurationUnit.MILLISECONDS))
                        spirc.seekTo(action.position)
                    }
                }

                PlayerAction.RepeatToggle -> {
                    val current = settingsRepository.repeatMode.first()
                    val next = current.next()
                    viewModelScope.launch {
                        settingsRepository.setRepeat(next.repeat)
                        settingsRepository.setRepeatTrack(next.repeatTrack)
                        spirc.repeat(next.repeat, next.repeatTrack)
                    }
                }

                PlayerAction.ShuffleToggle -> {
                    val newValue = !isShuffling.value
                    viewModelScope.launch {
                        settingsRepository.setShuffle(newValue)
                        spirc.shuffle(newValue)
                    }
                }
            }
        }
    }

    fun toggleFavorite() {
        val trackId = currentAudio.value?.id ?: return
        viewModelScope.launch {
            val wasLiked = likedRepository.isLiked(trackId)

            if (wasLiked) {
                likedRepository.removeLiked(trackId)
            } else {
                likedRepository.addLiked(trackId)
            }

            val success = if (wasLiked) {
                spClient.deleteItems(arrayOf("spotify:track:$trackId"))
            } else {
                spClient.saveItems(arrayOf("spotify:track:$trackId"))
            }

            if (!success) {
                if (wasLiked) {
                    likedRepository.addLiked(trackId)
                } else {
                    likedRepository.removeLiked(trackId)
                }
            }
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

    fun setPlaybackSpeed(speed: Float) {
        playbackStateHolder.setPlaybackSpeed(speed)
    }
}
