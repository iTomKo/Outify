package cc.tomko.outify.playback.model

import cc.tomko.outify.core.model.PlayableAudio
import cc.tomko.outify.core.connect.ConnectTarget

enum class PlaybackOwner { UNKNOWN, LOCAL, REMOTE }

/**
 * Holds current playback state
 */
data class PlaybackState(
    val owner: PlaybackOwner = PlaybackOwner.UNKNOWN,
    val localGeneration: Long = 0,
    val remoteTarget: ConnectTarget? = null,
    val deviceName: String? = null,
    val state: PlayState = PlayState.IDLE,
    val currentAudio: PlayableAudio? = null,
    val queue: List<PlayableAudio> = emptyList(),
    val queueIndex: Int = 0,
    val position: PositionInfo = PositionInfo.EMPTY,
    val playbackSpeed: Float = 1.0f,
    val repeatMode: RepeatMode = RepeatMode.NONE,
    val shuffleEnabled: Boolean = false,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = true,
    val isActiveDevice: Boolean = false,
    val volume: Int = 65_535,
)
