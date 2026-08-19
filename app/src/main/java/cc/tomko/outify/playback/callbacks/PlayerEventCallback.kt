package cc.tomko.outify.playback.callbacks

/**
 * Callback for all implemented PlayerEvent callbacks
 */
interface PlayerEventCallback {
    fun onTrackChange(spotify_uri: String, json: String)
    fun onPositionUpdate(spotify_uri: String, position_ms: Long)
    fun onPlayingStatus(playing: Boolean)
}