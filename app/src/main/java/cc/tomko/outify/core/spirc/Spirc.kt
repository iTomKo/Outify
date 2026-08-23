package cc.tomko.outify.core.spirc

import cc.tomko.outify.playback.model.Bitrate
import cc.tomko.outify.playback.model.getSpeed

interface SpircInitializationCallback {
    /**
     * Called when Spirc initializes
     */
    fun initialized()

    /**
     * Called when Spirc fails to initialize
     */
    fun failed()
}

interface SpircBufferCallback {
    /**
     * Called when spirc is buffering
     */
    fun started()

    /**
     * Called when spirc stops buffering
     */
    fun stopped()
}

interface SpircDeviceCallback {
    /**
     * Called when this device becomes the active playback device
     */
    fun becameActive()

    /**
     * Called when another device becomes the active playback device
     */
    fun becameInactive()

    /**
     * Called when the stream volume level is changed.
     * u16 volume
     */
    fun volumeChanged(volume: Int)
}

object Spirc {
    /**
     * Initializes the SpircRuntime
     * @param deviceName The device name shown in Spotify Connect (default "Outify")
     */
    @JvmStatic
    external fun initializeSpirc(
        callback: SpircInitializationCallback,
        gapless: Boolean,
        normalisation: Boolean,
        bitrateSpeed: Int = Bitrate.KBPS320.getSpeed(),
        deviceName: String = "Outify"
    ): Boolean

    /**
     * Shuts down the SpircRuntime
     */
    @JvmStatic
    external fun shutdown()

    /**
     * Unregisters the buffer callback, freeing its JNI GlobalRef
     */
    @JvmStatic
    external fun unregisterBufferCallback()

    /**
     * Unregisters the device callback, freeing its JNI GlobalRef
     */
    @JvmStatic
    external fun unregisterDeviceCallback()

    /**
     * Sets the buffer callback for spirc
     */
    @JvmStatic
    external fun bufferCallback(callback: SpircBufferCallback): Boolean

    /**
     * Sets the device callback for spirc to notify of active/inactive state changes
     */
    @JvmStatic
    external fun deviceCallback(callback: SpircDeviceCallback): Boolean

    /**
     * Loads a SpotifyURI
     * @param context valid form of URI, that will get loaded. Leave empty for liked tracks
     * @param playingTrackUri from which to start playing in this context. Leave empty for first/random
     * @return `true` if loaded successfully
     */
    @JvmStatic
    external fun load(context: String? = null, playingTrackUri: String? = null): Boolean

    @JvmStatic
    external fun localLoad(uri: String): Boolean

    /**
     * Loads the context URI and starts playing randomly within it
     */
    @JvmStatic
    external fun shuffleLoad(uri: String? = null): Boolean

    /**
     * Adds a SpotifyURI to queue
     * @param spotifyUri valid form of URI, that will get loaded
     * @return `true` if loaded successfully
     */
    @JvmStatic
    external fun addToQueue(spotifyUri: String?): Boolean

    /**
     * Loads context of given uris
     */
    @JvmStatic
    external fun setQueue(uris: Array<String>, playingTrackUri: String?): Boolean

    /**
     * Activates current Spirc session
     * @return `true` if success
     */
    @JvmStatic
    external fun activate(): Boolean

    /**
     * Transfers current Spirc session
     * @return `true` if success
     */
    @JvmStatic
    external fun transfer(): Boolean

    /**
     * Sets the volume for the current Spotify Connect session
     * @param volume 0-65535 (u16 range)
     * @return `true` if success
     */
    @JvmStatic
    external fun setVolume(volume: Int): Boolean

    /**
     * Seeks the current track to given position
     * @return `true` if success
     */
    @JvmStatic
    external fun seekTo(positionMs: Long): Boolean

    /**
     * Shuffles the playback
     * @return <code>true</code> if success
     */
    @JvmStatic
    external fun shuffle(enabled: Boolean): Boolean

    /**
     * Repeats the playback
		 * @param repeat whether to even repeat
		 * @param repeatTrack whether to repeat current track
     * @return <code>true</code> if success
     */
    @JvmStatic
    external fun repeat(repeat: Boolean, repeatTrack: Boolean): Boolean

    /**
     * Tells the player to start playing
     */
    @JvmStatic
    external fun playerPlay(): Boolean

    /**
     * Tells the player to pause playing
     */
    @JvmStatic
    external fun playerPause(): Boolean

    /**
     * Tells the player to toggle play status
     */
    @JvmStatic
    external fun playerPlayPause(): Boolean

    /**
     * Tells the player to skip to the next track
     */
    @JvmStatic
    external fun playerNext(): Boolean

    /**
     * Tells the player to play the previous track, or return to the start of current track
     */
    @JvmStatic
    external fun playerPrevious(): Boolean

    /**
     * Gets the previous tracks from queue
     */
    @JvmStatic
    external fun previousTracks(): String

    /**
     * Gets the next tracks from queue
     */
    @JvmStatic
    external fun nextTracks(): String
}
