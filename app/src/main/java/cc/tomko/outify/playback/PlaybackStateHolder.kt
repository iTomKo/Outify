package cc.tomko.outify.playback

import cc.tomko.outify.core.model.PlayableAudio
import cc.tomko.outify.core.model.Track
import cc.tomko.outify.playback.model.PlayState
import cc.tomko.outify.playback.model.PlaybackState
import cc.tomko.outify.playback.model.PositionInfo
import cc.tomko.outify.playback.model.RepeatMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@Singleton
class PlaybackStateHolder @Inject constructor() {
    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _position = MutableStateFlow(Duration.ZERO)
    val position: StateFlow<Duration> = _position.asStateFlow()

    private val mutex = Mutex()

    fun setQueue(queue: List<PlayableAudio>, startIndex: Int = 0) {
        val currentAudio = queue.getOrNull(startIndex)
        _state.update {
            it.copy(
                queue = queue,
                queueIndex = startIndex,
                currentAudio = currentAudio,
                position = PositionInfo.EMPTY.copy(
                    lastSync = System.currentTimeMillis()
                )
            )
        }
    }

    suspend fun play() {
        mutex.withLock {
            _state.value = _state.value.copy(
                isPlaying = true
            )
        }
    }

    suspend fun pause() {
        mutex.withLock {
            val cur = _state.value
            val pos = computePositionLocked()

            _state.value = cur.copy(
                isPlaying = false,
                position = cur.position.copy(
                    active = pos,
                    lastSync = System.currentTimeMillis()
                )
            )
        }
    }

    suspend fun setRepeatMode(mode: RepeatMode) {
        mutex.withLock {
            _state.value = _state.value.copy(repeatMode = mode)
        }
    }

    suspend fun seekTo(ms: Duration) {
        mutex.withLock {
            _state.value = _state.value.copy(
                position = _state.value.position.copy(
                    active = ms,
                    lastSync = System.currentTimeMillis()
                )
            )
        }
    }

    suspend fun setState(state: PlayState) {
        mutex.withLock {
            _state.value = _state.value.copy(
                state = state
            )
        }
    }

    fun setAudio(audio: PlayableAudio?) {
        _state.update { current ->
            if (current.currentAudio?.id == audio?.id) current
            else current.copy(currentAudio = audio)
        }
    }

    suspend fun setPlaying(playing: Boolean) {
        mutex.withLock {
            _state.value = _state.value.copy(isPlaying = playing)
        }
    }

    fun setBuffering(buffering: Boolean) {
        _state.update { it.copy(isBuffering = buffering) }
    }


    fun setActiveDevice(active: Boolean) {
        _state.update { it.copy(isActiveDevice = active) }
    }

    fun setVolume(volume: Int) {
        _state.update { it.copy(volume = volume) }
    }

    fun reset() {
        _state.update { PlaybackState() }
    }

    private fun computePositionLocked(): Duration {
        val cur = _state.value
        if (!cur.isPlaying) return cur.position.active
        val elapsed = System.currentTimeMillis() - cur.position.lastSync

        // multiply by playbackSpeed
        val scaled = (elapsed * cur.playbackSpeed).toLong()
        return cur.position.active.plus(scaled.toDuration(DurationUnit.MILLISECONDS))
    }

    // Non suspending getter for UI
    fun estimatePosition(): Duration {
        return computePositionLocked()
    }

    fun updatePosition(positionMs: Long) {
        _position.value = positionMs.toDuration(DurationUnit.MILLISECONDS)
    }
}