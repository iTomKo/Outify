package cc.tomko.outify.ui.model.player

import cc.tomko.outify.core.model.Artist

/**
 * Holds the UI state of the player
 */
data class PlayerUIState(
    val title: String = "",
    val artists: List<Artist> = emptyList(),
    val albumArt: String? = null,
    val isBuffering: Boolean = true,
    val isPlaying: Boolean = false,
    var isExplicit: Boolean = false,
    var playbackSpeed: Float = 1f,

    /**
     * The total track length
     */
    val totalLengthMs: Long = 0L,
    /**
     * Elapsed track time
     */
    val positionMs: Long = 0L,
    /**
     * When did we last get positionMs from Spotify
     */
    val lastUpdateTime: Long = 0L,
)
