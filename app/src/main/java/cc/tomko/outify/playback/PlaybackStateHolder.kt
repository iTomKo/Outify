package cc.tomko.outify.playback

import cc.tomko.outify.core.model.PlayableAudio
import cc.tomko.outify.core.connect.ConnectPlayback
import cc.tomko.outify.core.connect.ConnectTarget
import cc.tomko.outify.core.connect.metadataScope
import cc.tomko.outify.playback.model.PlaybackOwner
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
    val playbackIntent = PlaybackIntent()

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _position = MutableStateFlow(Duration.ZERO)
    val position: StateFlow<Duration> = _position.asStateFlow()

    /**
     * Mini-player visibility is a user decision, not a property of playback metadata.
     */
    private val _playerDismissed = MutableStateFlow(false)
    val playerDismissed: StateFlow<Boolean> = _playerDismissed.asStateFlow()

    /** Called by the swipe-down gesture. Playback state (including artwork) is left intact. */
    fun dismissPlayer() {
        _playerDismissed.value = true
    }

    /** Called only for explicit user playback actions or a genuinely new track. */
    fun restorePlayer() {
        _playerDismissed.value = false
    }

    private val mutex = Mutex()
    private val localGeneration = java.util.concurrent.atomic.AtomicLong()

    /** Wall-clock time of the latest native buffer transition, for snapshot freshness. */
    @Volatile
    private var lastBufferEventAtMs = 0L

    val localPlaybackAllowed: Boolean get() = state.value.owner == PlaybackOwner.LOCAL
    fun captureLocalEvent(): Long? = state.value.takeIf { it.owner == PlaybackOwner.LOCAL }?.localGeneration
    fun acceptsLocalEvent(generation: Long?): Boolean = generation != null && captureLocalEvent() == generation

    /** Native decoder setters must never mutate another device's presentation. */
    private inline fun updateLocal(transform: (PlaybackState) -> PlaybackState) {
        _state.update { if (it.owner == PlaybackOwner.LOCAL) transform(it) else it }
    }

    fun connectUnavailable() {
        _state.update { PlaybackState() }
        _position.value = Duration.ZERO
    }

    fun applyConnectPlayback(snapshot: ConnectPlayback) {
        val previousUri = _state.value.currentAudio?.uri
        if (snapshot.uri.isNotBlank() && previousUri != snapshot.uri) restorePlayer()

        if (snapshot.isLocal) {
            _state.update { old ->
                // Decoder metadata wins; an unresolved snapshot placeholder must still advance
                // when a track selected during a handoff reaches the local player.
                val sameTrack = old.owner == PlaybackOwner.LOCAL &&
                    old.currentAudio?.uri == snapshot.uri
                if (sameTrack) {
                    if (!snapshot.buffering && snapshot.sampledAtMs >= lastBufferEventAtMs) {
                        old.copy(isBuffering = false, state = PlayState.READY)
                    } else old
                } else if (old.owner == PlaybackOwner.LOCAL && old.currentAudio?.let {
                    !it.isMetadataPlaceholder()
                } == true) old else PlaybackState(
                    owner = PlaybackOwner.LOCAL, isActiveDevice = true,
                    localGeneration = if (old.owner == PlaybackOwner.LOCAL) old.localGeneration else localGeneration.incrementAndGet(),
                    currentAudio = placeholder(snapshot),
                    isPlaying = snapshot.playing && playbackIntent.canPlay,
                    isBuffering = snapshot.buffering,
                    state = if (snapshot.buffering) PlayState.BUFFERING else PlayState.READY,
                    position = PositionInfo(snapshot.positionMs.toDuration(DurationUnit.MILLISECONDS),
                        snapshot.durationMs.toDuration(DurationUnit.MILLISECONDS), snapshot.sampledAtMs),
                    shuffleEnabled = snapshot.shuffle,
                    repeatMode = RepeatMode.fromSettings(snapshot.repeatContext, snapshot.repeatTrack),
                )
            }
            return
        }
        if (!snapshot.isRemote) {
            connectUnavailable()
            return
        }
        _state.update { old ->
            val audio = old.currentAudio?.takeIf {
                old.remoteTarget?.metadataScope() == snapshot.target.metadataScope() && it.uri == snapshot.uri
            } ?: placeholder(snapshot)
            PlaybackState(
                owner = PlaybackOwner.REMOTE, remoteTarget = snapshot.target, deviceName = snapshot.deviceName,
                currentAudio = audio, isPlaying = snapshot.playing, isBuffering = snapshot.buffering,
                state = if (snapshot.buffering) PlayState.BUFFERING else PlayState.READY,
                position = cc.tomko.outify.playback.model.PositionInfo(
                    snapshot.positionMs.toDuration(DurationUnit.MILLISECONDS),
                    snapshot.durationMs.toDuration(DurationUnit.MILLISECONDS), snapshot.sampledAtMs),
                playbackSpeed = snapshot.speed.toFloat(), volume = snapshot.volume ?: 0,
                shuffleEnabled = snapshot.shuffle,
                repeatMode = RepeatMode.fromSettings(snapshot.repeatContext, snapshot.repeatTrack),
            )
        }
        _position.value = snapshot.positionMs.toDuration(DurationUnit.MILLISECONDS)
    }

    private fun placeholder(snapshot: ConnectPlayback): PlayableAudio? = snapshot.uri.takeIf { it.isNotBlank() }?.let { uri ->
        PlayableAudio(id = uri.substringAfterLast(':'), uri = uri, name = uri,
            covers = emptyList(), duration = snapshot.durationMs)
    }

    fun setRemoteAudio(target: ConnectTarget, audio: PlayableAudio) {
        _state.update { if (it.owner == PlaybackOwner.REMOTE && it.remoteTarget == target
            && it.currentAudio?.uri == audio.uri) it.copy(currentAudio = audio) else it }
    }

    fun setLocalSnapshotAudio(uri: String, audio: PlayableAudio) {
        if (audio.uri != uri) return
        updateLocal { current ->
            if (current.currentAudio?.uri.let { it == null || it == uri }) current.copy(currentAudio = audio)
            else current
        }
    }

    /** Position events carry metadata too, including when TrackChanged arrived during a handoff. */
    fun needsLocalMetadata(uri: String): Boolean {
        val current = state.value
        return current.owner == PlaybackOwner.LOCAL &&
            (current.currentAudio?.uri != uri || current.currentAudio.isMetadataPlaceholder())
    }

    private fun PlayableAudio?.isMetadataPlaceholder(): Boolean = this == null ||
        (sourceTrack == null && sourceEpisode == null)

    fun setQueue(queue: List<PlayableAudio>, startIndex: Int = 0) {
        val currentAudio = queue.getOrNull(startIndex)
        updateLocal {
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
        setPlaying(true)
    }

    suspend fun pause() {
        mutex.withLock {
            updateLocal { cur -> cur.copy(
                isPlaying = false,
                position = cur.position.copy(
                    active = computePosition(cur),
                    lastSync = System.currentTimeMillis()
                )
            ) }
        }
    }

    suspend fun setRepeatMode(mode: RepeatMode) {
        mutex.withLock {
            updateLocal { it.copy(repeatMode = mode) }
        }
    }

    fun setShuffleEnabled(enabled: Boolean) {
        updateLocal { it.copy(shuffleEnabled = enabled) }
    }

    suspend fun seekTo(ms: Duration) {
        mutex.withLock {
            updateLocal { it.copy(
                position = it.position.copy(
                    active = ms,
                    lastSync = System.currentTimeMillis()
                )
            ) }
        }
    }

    suspend fun setState(state: PlayState) {
        mutex.withLock {
            updateLocal { it.copy(
                state = state
            ) }
        }
    }

    fun setAudio(audio: PlayableAudio?) {
        updateLocal { current ->
            if (current.currentAudio == audio) current
            else current.copy(currentAudio = audio)
        }
    }

    suspend fun setPlaying(playing: Boolean) {
        mutex.withLock {
            val effectivePlaying = playing && playbackIntent.canPlay
            updateLocal { cur -> cur.copy(
                isPlaying = effectivePlaying,
                position = if (cur.isPlaying != effectivePlaying) {
                    cur.position.copy(
                        active = computePosition(cur),
                        lastSync = System.currentTimeMillis(),
                    )
                } else cur.position,
            ) }
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        updateLocal { it.copy(playbackSpeed = speed) }
    }

    fun setBuffering(buffering: Boolean) {
        lastBufferEventAtMs = System.currentTimeMillis()
        updateLocal { current ->
            current.copy(
                isBuffering = buffering,
                state = when {
                    buffering -> PlayState.BUFFERING
                    current.state == PlayState.BUFFERING -> PlayState.READY
                    else -> current.state
                },
            )
        }
    }


    fun setActiveDevice(active: Boolean) {
        _state.update {
            if (active && it.owner != PlaybackOwner.LOCAL) PlaybackState(owner = PlaybackOwner.LOCAL,
                isActiveDevice = true, localGeneration = localGeneration.incrementAndGet())
            else if (active) it.copy(isActiveDevice = true)
            else if (it.owner == PlaybackOwner.LOCAL) PlaybackState()
            else it
        }
    }

    fun setVolume(volume: Int) {
        updateLocal { it.copy(volume = volume) }
    }

    fun setSpeed(speed: Float) {
        updateLocal { it.copy(playbackSpeed = speed) }
    }

    fun reset() {
        _state.update { PlaybackState() }
    }

    private fun computePosition(cur: PlaybackState): Duration {
        if (!cur.isPlaying || cur.isBuffering) return cur.position.active
        val elapsed = System.currentTimeMillis() - cur.position.lastSync

        // multiply by playbackSpeed
        val scaled = (elapsed * cur.playbackSpeed).toLong()
        val position = cur.position.active.plus(scaled.toDuration(DurationUnit.MILLISECONDS))
        val duration = cur.position.duration.takeIf { it > Duration.ZERO }
            ?: cur.currentAudio?.duration?.takeIf { it > 0 }?.toDuration(DurationUnit.MILLISECONDS)
        return if (duration != null) position.coerceIn(Duration.ZERO, duration) else position
    }

    // Non suspending getter for UI
    fun estimatePosition(): Duration {
        return computePosition(_state.value)
    }

    fun updatePosition(positionMs: Long) {
        if (!localPlaybackAllowed) return
        _position.value = positionMs.toDuration(DurationUnit.MILLISECONDS)
    }
}
