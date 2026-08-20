package cc.tomko.outify.core.Spirc

import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import cc.tomko.outify.core.RadioResult
import cc.tomko.outify.core.SpClient
import cc.tomko.outify.core.model.DevicesResponse
import cc.tomko.outify.core.model.OutifyUri
import cc.tomko.outify.core.spirc.ISpircWrapper
import cc.tomko.outify.core.spirc.Spirc
import cc.tomko.outify.data.repository.SavedQueueRepository
import cc.tomko.outify.data.repository.SettingsRepository
import cc.tomko.outify.playback.PlaybackStateHolder
import cc.tomko.outify.services.PlaybackService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.Volatile
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@Singleton
class SpircWrapper @Inject constructor(
    @ApplicationContext val context: Context,
    private val playbackStateHolder: PlaybackStateHolder,
    private val spClient: SpClient,
    private val settingsRepository: SettingsRepository,
    private val savedQueueRepository: SavedQueueRepository,
    private val json: Json,
) : ISpircWrapper {
    val scope = CoroutineScope(
        Dispatchers.Main.immediate + SupervisorJob()
    )

    private var restartCallback: (() -> Unit)? = null

    fun setRestartCallback(callback: () -> Unit) {
        this.restartCallback = callback
    }

    fun ensureUsable() {
        if (!isUsable) {
            scope.launch(Dispatchers.IO) {
                restartCallback?.invoke()
            }
        }
    }

    @Volatile
    var isUsable = false
        private set

    fun setUsable(usable: Boolean) {
        isUsable = usable
    }

    fun restart() {
        setUsable(false)
        scope.launch(Dispatchers.IO) {
            restartCallback?.invoke()
        }
    }

    override fun shutdown() {
        setUsable(false)
        Spirc.shutdown()
    }

    override fun startRadio(trackUri: OutifyUri, shuffle: Boolean): Boolean {
        val jsonResult = spClient.getRadioForTrack(trackUri.toUriString()) ?: return false
        val result: RadioResult = json.decodeFromString(jsonResult)

        if (result.total == 0 || result.mediaItems.isEmpty()) {
            return false
        }

        val playlistUri = result.mediaItems.first().uri
        val uri = OutifyUri.fromUriString(playlistUri)

        if (shuffle) {
            shuffleLoad(playlistUri)
        } else {
            load(uri, trackUri)
        }

        return true
    }

    @OptIn(UnstableApi::class)
    private fun ensureServiceRunning() {
        ensureUsable()

        val intent = Intent(context, PlaybackService::class.java)
        ContextCompat.startForegroundService(context, intent)
    }


    /**
     * Loads a SpotifyURI
     * @param context valid form of URI, that will get loaded. Leave empty for liked tracks
     * @param playingTrackUri from which to start playing in this context. Leave empty for first/random
     * @return `true` if loaded successfully
     */
    override fun load(context: OutifyUri?, playingTrackUri: OutifyUri?): Boolean {
        scope.launch {
            savedQueueRepository.setActiveQueueId(null)

            val currentPosition = playbackStateHolder.estimatePosition().inWholeMilliseconds
            settingsRepository.saveLastPlayback(
                trackUri = playingTrackUri?.toUriString(),
                contextUri = context?.toUriString(),
                positionMs = if (currentPosition > 0) currentPosition else null
            )
        }

        ensureServiceRunning()
        return Spirc.load(context?.toUriString(), playingTrackUri?.toUriString())
    }

    override fun setQueue(uris: Array<String>, playingTrackUri: String?): Boolean {
        ensureServiceRunning()
        return Spirc.setQueue(uris, playingTrackUri)
    }

    override fun localLoad(uri: String): Boolean {
        scope.launch {
            savedQueueRepository.setActiveQueueId(null)
        }

        ensureServiceRunning()
        return Spirc.localLoad(uri)
    }

    /**
     * Shuffles the playback
     * @return <code>true</code> if success
     */
    override fun shuffle(enabled: Boolean): Boolean {
        scope.launch {
            savedQueueRepository.setActiveQueueId(null)
            settingsRepository.setShuffle(enabled)
        }

        return Spirc.shuffle(enabled)
    }

    /**
     * Repeats the playback
     * @return <code>true</code> if success
     */
    override fun repeat(enabled: Boolean): Boolean {
        scope.launch {
            settingsRepository.setRepeat(enabled)
        }
        return Spirc.repeat(enabled)
    }

    /**
     * Loads the context URI and starts playing randomly within it
     */
    override fun shuffleLoad(uri: String?): Boolean {
        scope.launch {
            savedQueueRepository.setActiveQueueId(null)
            settingsRepository.saveLastPlayback(
                trackUri = null,
                contextUri = uri,
                positionMs = null
            )
        }

        ensureServiceRunning()
        return Spirc.shuffleLoad(uri)
    }

    /**
     * Adds a SpotifyURI to queue
     * @param spotifyUri valid form of URI, that will get loaded
     * @return `true` if loaded successfully
     */
    override fun addToQueue(spotifyUri: String?): Boolean {
        // TODO: cache in kotlin, so we can have faster UX
        return Spirc.addToQueue(spotifyUri)
    }

    /**
     * Activates current Spirc session
     * @return `true` if success
     */
    override fun activate(): Boolean {
        return Spirc.activate()
    }

    /**
     * Transfers current Spirc session
     * @return `true` if success
     */
    override fun transfer(): Boolean {
        return Spirc.transfer()
    }

    /**
     * Transfers current Spirc session only if no other session is streaming.
     */
    override fun smartTransfer(): Boolean {
        val json = spClient.getDevices() ?: return false
        val devices = Json.decodeFromString<DevicesResponse>(json)

        for (device in devices.devices) {
            if (device.isActive) return false
        }

        return Spirc.transfer()
    }

    /**
     * Sets the volume for the current Spotify Connect session.
     */
    override fun setVolume(volume: Int): Boolean {
        return Spirc.setVolume(volume)
    }

    /**
     * Checks if any other device is actively playing.
     * Retries up to 3 times since SpClient may not be ready immediately at startup.
     */
    override suspend fun hasActiveDevice(): Boolean = withContext(Dispatchers.IO) {
        repeat(3) {
            try {
                val json = spClient.getDevices()
                if (json != null) {
                    val devices = Json.decodeFromString<DevicesResponse>(json)
                    return@withContext devices.devices.any { it.isActive }
                }
            } catch (_: Exception) {
            }
            delay(500)
        }
        false
    }

    /**
     * Seeks the current track to given position
     * @return `true` if success
     */
    override suspend fun seekTo(positionMs: Long): Boolean {
        if (positionMs < 0) {
            return false
        }

        // Assuming it went successfully - pre-updating the position
        playbackStateHolder.seekTo(positionMs.toDuration(DurationUnit.MILLISECONDS))
        playbackStateHolder.updatePosition(positionMs)

        settingsRepository.saveLastPlayback(
            trackUri = null,
            contextUri = null,
            positionMs = positionMs
        )

        return Spirc.seekTo(positionMs)
    }

    /**
     * Tells the player to start playing
     */
    override fun playerPlay(): Boolean {
        ensureServiceRunning()
        if (!isUsable) return false
        return Spirc.playerPlay()
    }

    /**
     * Tells the player to pause playing
     */
    override fun playerPause(): Boolean {
        ensureServiceRunning()
        if (!isUsable) return false
        return Spirc.playerPause()
    }

    /**
     * Tells the player to toggle play status
     */
    override fun playerPlayPause(): Boolean {
        ensureServiceRunning()
        if (!isUsable) return false
        return Spirc.playerPlayPause()
    }

    /**
     * Tells the player to skip to the next track
     */
    override fun playerNext(): Boolean {
        return Spirc.playerNext()
    }

    /**
     * Tells the player to play the previous track, or return to the start of current track
     */
    override fun playerPrevious(): Boolean {
        return Spirc.playerPrevious()
    }

    /**
     * Gets the previous tracks from queue
     */
    override fun previousTracks(): String {
        return Spirc.previousTracks()
    }

    /**
     * Gets the next tracks from queue
     */
    override fun nextTracks(): String {
        return Spirc.nextTracks()
    }

    /**
     * Adds a track to play next (inserts at the beginning of the queue)
     * @param trackUri the track URI to play next
     * @return `true` if successful
     */
    override fun playNext(trackUri: String): Boolean {
        val nextTracksJson = nextTracks()
        return try {
            val nextTracks: List<String> = json.decodeFromString(nextTracksJson)
            val newQueue = arrayOf(trackUri) + nextTracks.toTypedArray()
            setQueue(newQueue, trackUri)
        } catch (_: Exception) {
            false
        }
    }
}