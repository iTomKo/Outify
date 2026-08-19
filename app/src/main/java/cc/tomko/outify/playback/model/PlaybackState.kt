package cc.tomko.outify.playback.model

import cc.tomko.outify.core.model.PlayableAudio

/**
 * Holds current playback state
 */
data class PlaybackState(
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
