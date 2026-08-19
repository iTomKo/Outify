package cc.tomko.outify.playback.callbacks

/**
 * Callback for all implemented PlayerEvent callbacks
 */
interface PlayerEventCallback {
    /**
     * Track or Episode
     */
    fun onTrackChange(spotify_uri: String, json: String)
    fun onPositionUpdate(spotify_uri: String, position_ms: Long, json: String)
    fun onPlayingStatus(playing: Boolean)
}