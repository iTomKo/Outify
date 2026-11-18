package cc.tomko.outify.core.spirc

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import cc.tomko.outify.core.RadioResult
import cc.tomko.outify.core.SpClient
import cc.tomko.outify.core.connect.ConnectPlaybackMonitor
import cc.tomko.outify.core.connect.ConnectCommand
import cc.tomko.outify.core.connect.PlaybackRoute
import cc.tomko.outify.core.connect.playbackRoute
import cc.tomko.outify.core.connect.ConnectPlay
import cc.tomko.outify.core.connect.dispatchPlaybackLoad
import cc.tomko.outify.core.model.DevicesResponse
import cc.tomko.outify.core.model.OutifyUri
import cc.tomko.outify.data.repository.SavedQueueRepository
import cc.tomko.outify.data.repository.SettingsRepository
import cc.tomko.outify.playback.PlaybackStateHolder
import cc.tomko.outify.services.PlaybackService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.DurationUnit
import kotlin.time.toDuration

private const val TAG = "SpircWrapper"

@Serializable
data class QueueTrackDto(
    val uri: String,
    @SerialName("is_queue") val isQueue: Boolean
)

@Singleton
class SpircWrapper @Inject constructor(
    @ApplicationContext val context: Context,
    private val playbackStateHolder: PlaybackStateHolder,
    private val spClient: SpClient,
    private val settingsRepository: SettingsRepository,
    private val savedQueueRepository: SavedQueueRepository,
    private val json: Json,
    val connectPlayback: ConnectPlaybackMonitor,
) : ISpircWrapper {
    val scope = CoroutineScope(
        Dispatchers.Main.immediate + SupervisorJob()
    )

    private val _isUsable = MutableStateFlow(false)

    /**
     * Whether playback commands may be forwarded to the native runtime.
     */
    val isUsable: Boolean
        get() = _isUsable.value

    val isUsableFlow: StateFlow<Boolean> = _isUsable.asStateFlow()

    init {
        connectPlayback.start(isUsableFlow)
        scope.launch {
            playbackStateHolder.state.map { it.owner to (it.currentAudio != null) }.distinctUntilChanged()
                .collect { (_, hasAudio) ->
                    if (hasAudio) try {
                        startPlaybackService()
                    } catch (error: IllegalStateException) {
                        Log.d(TAG, "Cannot start media session from background", error)
                    }
                }
        }
        scope.launch {
            connectPlayback.error.collect { error ->
                if (error != null) android.widget.Toast.makeText(context, error, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    /** null selects the local branch; false rejects unknown/restricted ownership. */
    private fun routeRemote(action: String, value: Long? = null): Boolean? {
        return when (val route = playbackRoute(playbackStateHolder.localPlaybackAllowed,
            connectPlayback.snapshot.value, action, value)) {
            PlaybackRoute.Local -> null
            PlaybackRoute.Unavailable -> false
            is PlaybackRoute.Remote -> {
                if (route.command.target != playbackStateHolder.state.value.remoteTarget) false
                else connectPlayback.submit(route.command)
            }
        }
    }

    fun captureRemoteVolume(volume: Int): ConnectCommand? = connectPlayback.capture("volume", volume.toLong())
    fun submitRemote(command: ConnectCommand): Boolean = connectPlayback.submit(command)

    /** Reflects a lifecycle state published by [SpircController]. */
    fun onStateChanged(state: SpircState) {
        _isUsable.value = state.isUsable
        if (!state.isUsable) connectPlayback.clear()
    }

    @Synchronized
    private fun playByUser() {
        playbackStateHolder.playbackIntent.playByUser()
        playbackStateHolder.restorePlayer()
        Spirc.setPlaybackPaused(false)
    }

    /** Focus changes must not be mistaken for an explicit Play/Pause action. */
    @Synchronized
    fun pauseForAudioFocus() {
        if (!playbackStateHolder.localPlaybackAllowed) return
        playbackStateHolder.playbackIntent.pauseForAudioFocus()
        Spirc.setPlaybackPaused(true)
        scope.launch { playbackStateHolder.setPlaying(false) }
        if (isUsable) Spirc.playerPause()
    }

    @Synchronized
    fun resumeAfterAudioFocus(): Boolean {
        if (!playbackStateHolder.localPlaybackAllowed) return false
        if (!playbackStateHolder.playbackIntent.resumeAfterAudioFocus()) return false
        Spirc.setPlaybackPaused(false)
        return isUsable && Spirc.playerPlay()
    }

    @Synchronized
    fun enforcePause() {
        if (!playbackStateHolder.localPlaybackAllowed) return
        if (playbackStateHolder.playbackIntent.canPlay) return
        Spirc.setPlaybackPaused(true)
        if (isUsable) Spirc.playerPause()
    }

    override fun shutdown() {
        // Native teardown publishes no callback, so drop the gate here or the
        // wrapper would keep reporting itself as usable.
        _isUsable.value = false
        connectPlayback.clear()
        Spirc.shutdown()
    }

    override suspend fun startRadio(trackUri: OutifyUri, shuffle: Boolean): Boolean {
        val generation = playbackStateHolder.playbackIntent.state.value.generation
        val target = connectPlayback.snapshot.value?.target ?: return false
        return withContext(Dispatchers.IO) {
            val jsonResult = spClient.getRadioForTrack(trackUri.toUriString()) ?: return@withContext false
            val result: RadioResult = json.decodeFromString(jsonResult)

            if (result.total == 0 || result.mediaItems.isEmpty()) {
                return@withContext false
            }

            val playlistUri = result.mediaItems.first().uri
            val uri = OutifyUri.fromUriString(playlistUri)

            synchronized(this@SpircWrapper) {
                // Fetching a radio can outlive a subsequent user pause or selection.
                if (playbackStateHolder.playbackIntent.state.value.generation != generation
                    || connectPlayback.snapshot.value?.target != target) {
                    return@synchronized false
                }
                if (shuffle) shuffleLoad(playlistUri) else load(uri, trackUri)
            }
        }
    }

    @OptIn(UnstableApi::class)
    fun startPlaybackService() {
        val intent = Intent(context, PlaybackService::class.java)
        context.startService(intent)
    }

    @OptIn(UnstableApi::class)
    private fun startForegroundPlaybackService() {
        val intent = Intent(context, PlaybackService::class.java)
        try {
            context.startService(intent)
        } catch (e: IllegalStateException) {
            Log.d(TAG, "background service start not allowed, starting in foreground", e)
            intent.putExtra(PlaybackService.EXTRA_REQUIRE_FOREGROUND, true)
            ContextCompat.startForegroundService(context, intent)
        }
    }

    private fun makeThisDeviceActive() {
        if (playbackStateHolder.state.value.isActiveDevice) return
        Log.i(TAG, "not the active device, activating before playback")
        if (Spirc.activate()) {
            playbackStateHolder.setActiveDevice(true)
        } else {
            Log.w(TAG, "failed to activate this device")
        }
    }

    /** Logs and rejects a command that arrived while the runtime is unavailable. */
    private fun requireReady(command: String): Boolean {
        if (isUsable) return true

        Log.d(TAG, "dropping $command, spirc is not ready")
        return false
    }

    /**
     * Loads a SpotifyURI
     * @param context valid form of URI, that will get loaded. Leave empty for liked tracks
     * @param playingTrackUri from which to start playing in this context. Leave empty for first/random
     * @return `true` if loaded successfully
     */
    @Synchronized
    override fun load(context: OutifyUri?, playingTrackUri: OutifyUri?): Boolean {
        if (!requireReady("load")) return false
        return dispatchPlaybackLoad(connectPlayback.snapshot.value,
            ConnectPlay(context?.toUriString() ?: OutifyUri.Liked.toUriString(), playingTrackUri?.toUriString()),
            connectPlayback::submit) {
            playByUser()

            scope.launch {
                savedQueueRepository.setActiveQueueId(null)

                val currentPosition = playbackStateHolder.estimatePosition().inWholeMilliseconds
                settingsRepository.saveLastPlayback(
                    trackUri = playingTrackUri?.toUriString(),
                    contextUri = context?.toUriString(),
                    positionMs = if (currentPosition > 0) currentPosition else null
                )
            }

            makeThisDeviceActive()
            startForegroundPlaybackService()
            Spirc.load(context?.toUriString(), playingTrackUri?.toUriString())
        }
    }

    override fun setQueue(uris: Array<String>, playingTrackUri: String?): Boolean {
        if (!requireReady("setQueue")) return false
        // Remote queue editing is not implemented; it must not steal playback.
        if (connectPlayback.snapshot.value?.let { it.isLocal || it.target.deviceId.isBlank() } != true) return false

        makeThisDeviceActive()
        startForegroundPlaybackService()
        return Spirc.setQueue(uris, playingTrackUri)
    }

    @Synchronized
    override fun localLoad(uri: String): Boolean {
        if (!requireReady("localLoad")) return false
        if (connectPlayback.snapshot.value?.let { it.isLocal || it.target.deviceId.isBlank() } != true) return false
        playByUser()

        scope.launch {
            savedQueueRepository.setActiveQueueId(null)
        }

        makeThisDeviceActive()
        startForegroundPlaybackService()
        return Spirc.localLoad(uri)
    }

    /**
     * Shuffles the playback
     * @return <code>true</code> if success
     */
    override fun shuffle(enabled: Boolean): Boolean {
        if (!playbackStateHolder.localPlaybackAllowed) return false
        scope.launch {
            savedQueueRepository.setActiveQueueId(null)
            settingsRepository.setShuffle(enabled)
        }

        if (!requireReady("shuffle")) return false
        return Spirc.shuffle(enabled)
    }

    /**
     * Repeats the playback
     * @param repeat whether to even repeat
     * @param repeatTrack whether to repeat current track
     * @return <code>true</code> if success
     */
    override fun repeat(repeat: Boolean, repeatTrack: Boolean): Boolean {
        if (!playbackStateHolder.localPlaybackAllowed) return false
        scope.launch {
            settingsRepository.setRepeatMode(repeat, repeatTrack)
        }

        if (!requireReady("repeat")) return false
        return Spirc.repeat(repeat, repeatTrack)
    }

    /**
     * Loads the context URI and starts playing randomly within it
     */
    @Synchronized
    override fun shuffleLoad(uri: String?): Boolean {
        if (!requireReady("shuffleLoad")) return false
        return dispatchPlaybackLoad(connectPlayback.snapshot.value,
            ConnectPlay(uri ?: OutifyUri.Liked.toUriString(), shuffle = true), connectPlayback::submit) {
            playByUser()

            scope.launch {
                savedQueueRepository.setActiveQueueId(null)
                settingsRepository.saveLastPlayback(
                    trackUri = null,
                    contextUri = uri,
                    positionMs = null
                )
            }

            makeThisDeviceActive()
            startForegroundPlaybackService()
            Spirc.shuffleLoad(uri)
        }
    }

    /**
     * Adds a SpotifyURI to queue
     * @param spotifyUri valid form of URI, that will get loaded
     * @return `true` if loaded successfully
     */
    override fun addToQueue(spotifyUri: String?): Boolean {
        if (!playbackStateHolder.localPlaybackAllowed) return false
        if (!requireReady("addToQueue")) return false

        // TODO: cache in kotlin, so we can have faster UX
        return Spirc.addToQueue(spotifyUri)
    }

    /**
     * Activates current Spirc session
     * @return `true` if success
     */
    override fun activate(): Boolean {
        if (!requireReady("activate")) return false
        return Spirc.activate()
    }

    /**
     * Transfers current Spirc session
     * @return `true` if success
     */
    override fun transfer(): Boolean {
        if (!requireReady("transfer")) return false
        return Spirc.transfer()
    }

    /**
     * Transfers only if native Connect state says it is safe, including paused
     * remote sessions and Jams. Like other blocking JNI calls, call off the main thread.
     */
    override fun smartTransfer(): Boolean {
        if (!requireReady("smartTransfer")) return false

        return Spirc.automaticTransfer(forceSmart = true)
    }

    /**
     * Sets the volume for the current Spotify Connect session.
     */
    override fun setVolume(volume: Int): Boolean {
        if (!requireReady("setVolume")) return false
        routeRemote("volume", volume.toLong())?.let { return it }
        return Spirc.setVolume(volume)
    }

    /**
     * Checks if Spotify already has an active playback device (including paused
     * sessions). Retries startup failures, then throws rather than treating an
     * unknown device state as safe to take over.
     */
    override suspend fun hasActiveDevice(): Boolean = withContext(Dispatchers.IO) {
        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
                val raw = spClient.getDevices() ?: error("Device lookup returned no response")
                val devices = json.decodeFromString<DevicesResponse>(raw)
                // A hidden/restricted device can own playback without being selectable.
                return@withContext devices.activeDeviceId?.isNotBlank()
                    ?: devices.devices.any { it.isActive }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                lastError = error
            }
            if (attempt < 2) delay(500)
        }
        throw IllegalStateException("Could not check active playback devices", lastError)
    }

    /**
     * Seeks the current track to given position
     * @return `true` if success
     */
    override suspend fun seekTo(positionMs: Long): Boolean {
        if (positionMs < 0) {
            return false
        }

        // Checked before the optimistic UI update below, so a seek that cannot be
        // delivered does not leave the progress bar lying about the position.
        if (!requireReady("seekTo")) return false
        routeRemote("seek_to", positionMs)?.let { return it }

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
    @Synchronized
    override fun playerPlay(): Boolean {
        if (!requireReady("playerPlay")) return false
        routeRemote("resume")?.let { return it }

        playByUser()
        startForegroundPlaybackService()
        val accepted = Spirc.playerPlay()
        if (accepted) scope.launch { playbackStateHolder.setPlaying(true) }
        return accepted
    }

    /**
     * Tells the player to pause playing
     */
    @Synchronized
    override fun playerPause(): Boolean {
        routeRemote("pause")?.let { return it }
        // Record intent even during a rebuild, before a delayed Playing event can arrive.
        playbackStateHolder.playbackIntent.pauseByUser()
        Spirc.setPlaybackPaused(true)
        scope.launch { playbackStateHolder.setPlaying(false) }
        return !isUsable || Spirc.playerPause()
    }

    /**
     * Tells the player to toggle play status
     */
    @Synchronized
    override fun playerPlayPause(): Boolean {
        return if (playbackStateHolder.state.value.isPlaying) playerPause() else playerPlay()
    }

    /**
     * Tells the player to skip to the next track
     */
    override fun playerNext(): Boolean {
        if (!requireReady("playerNext")) return false
        playbackStateHolder.restorePlayer()
        routeRemote("skip_next")?.let { return it }
        return Spirc.playerNext()
    }

    /**
     * Tells the player to play the previous track, or return to the start of current track
     */
    override fun playerPrevious(): Boolean {
        if (!requireReady("playerPrevious")) return false
        playbackStateHolder.restorePlayer()
        routeRemote("skip_prev")?.let { return it }
        return Spirc.playerPrevious()
    }

    /**
     * Gets the previous tracks from queue
     */
    override suspend fun previousTracks(): List<QueueTrackDto> = withContext(Dispatchers.IO) {
        val previousTracksJson = Spirc.nextTracks()
        if (previousTracksJson.isEmpty() || previousTracksJson == "[]") return@withContext emptyList()

        try {
            json.decodeFromString<List<QueueTrackDto>>(previousTracksJson)
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * Gets the next tracks from queue
     */
    override suspend fun nextTracks(): List<QueueTrackDto> = withContext(Dispatchers.IO) {
        val nextTracksJson = Spirc.nextTracks()
        if (nextTracksJson.isEmpty() || nextTracksJson == "[]") return@withContext emptyList()

        try {
            json.decodeFromString<List<QueueTrackDto>>(nextTracksJson)
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * Adds a track to play next (inserts at the beginning of the queue)
     * @param trackUri the track URI to play next
     * @return `true` if successful
     */
    override suspend fun playNext(trackUri: String): Boolean {
        return try {
            withContext(Dispatchers.IO) {
                val nextTracks: List<QueueTrackDto> = nextTracks()

                val nextUris = nextTracks.map { it.uri }
                val newQueue = arrayOf(trackUri) + nextUris.toTypedArray()

                setQueue(newQueue, trackUri)
            }
        } catch (_: Exception) {
            false
        }
    }
}
