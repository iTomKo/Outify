package cc.tomko.outify.playback

import android.app.Application
import androidx.core.graphics.scale
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.audio.AudioFocusManager
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import cc.tomko.outify.ALBUM_COVER_URL
import cc.tomko.outify.R
import cc.tomko.outify.core.model.CoverSize
import cc.tomko.outify.core.model.Episode
import cc.tomko.outify.core.model.Track
import cc.tomko.outify.core.model.PlayableAudio
import cc.tomko.outify.core.model.asInt
import cc.tomko.outify.core.model.getCover
import cc.tomko.outify.core.model.toPlayableAudio
import cc.tomko.outify.core.spirc.SpircWrapper
import cc.tomko.outify.data.repository.SettingsRepository
import cc.tomko.outify.playback.callbacks.PlayerEventCallback
import cc.tomko.outify.playback.model.RepeatMode
import cc.tomko.outify.services.PlaybackService
import coil3.Bitmap
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@Singleton
@UnstableApi
class Player @Inject constructor(
    private val application: Application,
    val stateHolder: PlaybackStateHolder,
    val spirc: SpircWrapper,
    val json: Json,
    val imageLoader: ImageLoader,
    private val settingsRepository: SettingsRepository,
) : SimpleBasePlayer(application.mainLooper) {

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    @Volatile
    var currentArtworkBitmap: Bitmap? = null

    @Volatile
    private var currentArtworkBytes: ByteArray? = null

    @Volatile
    private var currentArtworkUri: String? = null

    private var artworkJob: Job? = null

    var engine: AudioEngine =
        AudioEngine(
            application.applicationContext,
            object : PlayerEventCallback {
                override fun onTrackChange(spotify_uri: String, json_str: String) {
                    val generation = stateHolder.captureLocalEvent()
                    scope.launch {
                        if (!stateHolder.acceptsLocalEvent(generation)) return@launch
                        val audio = if (spotify_uri.startsWith("spotify:episode:")) {
                            val episode: Episode = try {
                                json.decodeFromString(json_str)
                            } catch (e: Exception) {
                                Log.w("Player", "Failed to decode episode JSON", e)
                                return@launch
                            }
                            episode.toPlayableAudio()
                        } else {
                            val track: Track = try {
                                json.decodeFromString(json_str)
                            } catch (e: Exception) {
                                Log.w("Player", "Failed to decode track JSON", e)
                                return@launch
                            }
                            track.toPlayableAudio()
                        }
                        stateHolder.setAudio(audio)
                    }
                }

                override fun onPositionUpdate(
                    spotify_uri: String,
                    position_ms: Long,
                    json_raw: String
                ) {
                    val generation = stateHolder.captureLocalEvent()
                    scope.launch {
                        if (!stateHolder.acceptsLocalEvent(generation)) return@launch
                        stateHolder.seekTo(position_ms.toDuration(DurationUnit.MILLISECONDS))

                        if (spotify_uri.startsWith("spotify:episode:") && stateHolder.needsLocalMetadata(spotify_uri)) {
                            val episode: Episode? = try {
                                json.decodeFromString(json_raw)
                            } catch (e: Exception) {
                                Log.w(
                                    "Player",
                                    "Failed to decode episode JSON in position update",
                                    e
                                )
                                null
                            }
                            episode?.let { stateHolder.setAudio(it.toPlayableAudio()) }
                        } else if (spotify_uri.startsWith("spotify:track:") && stateHolder.needsLocalMetadata(spotify_uri)) {
                            val track: Track? = try {
                                json.decodeFromString(json_raw)
                            } catch (e: Exception) {
                                Log.w("Player", "Failed to decode track JSON in position update", e)
                                null
                            }
                            track?.let { stateHolder.setAudio(it.toPlayableAudio()) }
                        }

                        invalidateState()
                    }
                }

                override fun onPlayingStatus(playing: Boolean) {
                    val generation = stateHolder.captureLocalEvent()
                    if (!stateHolder.localPlaybackAllowed) { engine.pause(); return }
                    if (!playing || !stateHolder.playbackIntent.canPlay) engine.pause()
                    scope.launch {
                        if (!stateHolder.acceptsLocalEvent(generation)) return@launch
                        if (playing && stateHolder.playbackIntent.canPlay) {
                            val command = audioFocusManager.updateAudioFocus(true, STATE_READY)
                            if (command == AudioFocusManager.PLAYER_COMMAND_PLAY_WHEN_READY) {
                                engine.resume()
                                stateHolder.setPlaying(true)
                            } else {
                                engine.pause()
                                spirc.pauseForAudioFocus()
                            }
                        } else {
                            stateHolder.setPlaying(false)
                            if (playing) {
                                // A queued event or a Connect transfer cannot undo a local pause.
                                spirc.enforcePause()
                            }
                        }
                        invalidateState()
                    }
                }

                override fun onShuffleChanged(shuffle: Boolean) {
                    val generation = stateHolder.captureLocalEvent()
                    scope.launch {
                        if (!stateHolder.acceptsLocalEvent(generation)) return@launch
                        stateHolder.setShuffleEnabled(shuffle)
                        invalidateState()
                        // Persist the runtime state without sending another Connect command.
                        settingsRepository.setShuffle(shuffle)
                    }
                }

                override fun onRepeatChanged(context: Boolean, track: Boolean) {
                    val generation = stateHolder.captureLocalEvent()
                    scope.launch {
                        if (!stateHolder.acceptsLocalEvent(generation)) return@launch
                        stateHolder.setRepeatMode(RepeatMode.fromSettings(context, track))
                        invalidateState()
                        settingsRepository.setRepeatMode(context, track)
                    }
                }
            }, stateHolder
        )

    private val audioFocusManager = AudioFocusManager(
        application.applicationContext,
        application.mainLooper,
        object : AudioFocusManager.PlayerControl {
            override fun setVolumeMultiplier(volume: Float) {
                Log.i("Player", "Volume changed to $volume")
                engine.setVolume(volume)
            }

            override fun executePlayerCommand(command: Int) {
                if (!stateHolder.localPlaybackAllowed) { engine.pause(); return }
                when (command) {
                    AudioFocusManager.PLAYER_COMMAND_WAIT_FOR_CALLBACK,
                    AudioFocusManager.PLAYER_COMMAND_DO_NOT_PLAY -> {
                        engine.pause()
                        spirc.pauseForAudioFocus()
                        scope.launch {
                            stateHolder.setPlaying(false)
                            invalidateState()
                        }
                    }

                    AudioFocusManager.PLAYER_COMMAND_PLAY_WHEN_READY -> {
                        spirc.resumeAfterAudioFocus()
                    }
                }
            }
        }
    )

    init {
        audioFocusManager.setAudioAttributes(PlaybackService.AUDIO_ATTRIBUTES)
        scope.launch {
            stateHolder.playbackIntent.state.collect { intent ->
                if (!stateHolder.localPlaybackAllowed) { engine.pause(); return@collect }
                if (!intent.canPlay) {
                    engine.pause()
                    stateHolder.setPlaying(false)
                    if (intent.userPaused) audioFocusManager.updateAudioFocus(false, STATE_READY)
                    invalidateState()
                } else {
                    engine.resume()
                }
            }
        }
        scope.launch {
            var audio: PlayableAudio? = null
            var outputPlaying = false
            stateHolder.state.collect { state ->
                if (!stateHolder.localPlaybackAllowed) {
                    engine.pause()
                    audioFocusManager.updateAudioFocus(false, STATE_IDLE)
                }
                val shouldPlay = stateHolder.localPlaybackAllowed && state.isPlaying && stateHolder.playbackIntent.canPlay
                if (shouldPlay && !outputPlaying) {
                    val command = audioFocusManager.updateAudioFocus(true, STATE_READY)
                    if (command == AudioFocusManager.PLAYER_COMMAND_PLAY_WHEN_READY) engine.resume()
                    else spirc.pauseForAudioFocus()
                }
                outputPlaying = shouldPlay
                if (audio != state.currentAudio) {
                    audio = state.currentAudio
                    updateArtwork(audio)
                }
                invalidateState()
            }
        }
    }

    private fun updateArtwork(audio: PlayableAudio?) {
        artworkJob?.cancel()
        val cover = audio?.covers?.firstOrNull { it.size == CoverSize.LARGE.asInt() }
            ?: audio?.covers?.firstOrNull()
        val url = cover?.let { ALBUM_COVER_URL + it.uri }
        currentArtworkUri = url
        currentArtworkBitmap = null
        currentArtworkBytes = null
        if (url == null) return
        artworkJob = scope.launch {
            try {
                val (bitmap, bytes) = withContext(Dispatchers.IO) {
                    val result = imageLoader.execute(ImageRequest.Builder(application).data(url).allowHardware(false).build())
                    val original = result.image?.toBitmap()
                    val bitmap = original?.let {
                        val ratio = minOf(1f, 1024f / maxOf(it.width, it.height))
                        if (ratio < 1f) it.scale((it.width * ratio).toInt(), (it.height * ratio).toInt()) else it
                    }
                    val bytes = bitmap?.let {
                        ByteArrayOutputStream().use { stream ->
                            it.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
                            stream.toByteArray()
                        }
                    }
                    bitmap to bytes
                }
                if (stateHolder.state.value.currentAudio != audio) return@launch
                currentArtworkBitmap = bitmap
                currentArtworkBytes = bytes
                invalidateState()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("Player", "artwork load failed", error)
            }
        }
    }

    override fun getState(): State {
        val ps = stateHolder.state.value
        val audio = ps.currentAudio ?: return State.Builder()
            .setPlaybackState(STATE_IDLE)
            .setShuffleModeEnabled(ps.shuffleEnabled)
            .setRepeatMode(ps.repeatMode.toMediaRepeatMode())
            .setAvailableCommands(determineCommands())
            .setPlayWhenReady(false, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaylist(emptyList())
            .build()

        val subtitle = audio.artists?.joinToString { it.name }
            ?: audio.showName
            ?: application.getString(R.string.player_unknown_source)

        val mediaMetadata = MediaMetadata.Builder()
            .setTitle(audio.name)
            .setDisplayTitle(audio.name)
            .setArtist(subtitle)
            .setMediaType(if (audio.isEpisode()) MediaMetadata.MEDIA_TYPE_PODCAST else MediaMetadata.MEDIA_TYPE_MUSIC)
            .apply {
                currentArtworkUri?.let { setArtworkUri(it.toUri()) }
                currentArtworkBytes?.let { bytes ->
                    setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                }
            }
            .build()

        val mediaItem = MediaItem.Builder()
            .setMediaId(audio.id)
            .setUri(audio.uri)
            .setMediaMetadata(mediaMetadata)
            .build()

        val playlist = listOf(
            MediaItemData.Builder(audio.id)
                .setMediaItem(mediaItem)
                .setDurationUs(audio.duration * 1000L)
                .setDefaultPositionUs(0)
                .setIsSeekable(true)
                .setMediaMetadata(mediaItem.mediaMetadata)
                .build()
        )

        val playbackState = if (ps.isBuffering) STATE_BUFFERING else STATE_READY

        return State.Builder()
            .setAudioAttributes(PlaybackService.AUDIO_ATTRIBUTES)
            .setShuffleModeEnabled(ps.shuffleEnabled)
            .setRepeatMode(ps.repeatMode.toMediaRepeatMode())
            .setPlaybackState(playbackState)
            .setAvailableCommands(determineCommands())
            .setPlayWhenReady(ps.isPlaying, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackParameters(PlaybackParameters(ps.playbackSpeed))
            .setCurrentMediaItemIndex(if (playlist.isNotEmpty()) 0 else C.INDEX_UNSET)
            .setContentPositionMs(ps.position.active.inWholeMilliseconds)
            .setIsLoading(ps.isBuffering)
            .setPlaylist(playlist)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (!stateHolder.localPlaybackAllowed) {
            if (playWhenReady) spirc.playerPlay() else spirc.playerPause()
            return Futures.immediateVoidFuture()
        }
        if (!playWhenReady) {
            spirc.playerPause()
            engine.pause()
            audioFocusManager.updateAudioFocus(false, STATE_READY)
            return Futures.immediateVoidFuture()
        }
        val currentMedia3State =
            if (stateHolder.state.value.currentAudio == null) STATE_IDLE else STATE_READY

        if (!spirc.playerPlay()) return Futures.immediateVoidFuture()
        val playerCommand = audioFocusManager.updateAudioFocus(playWhenReady, currentMedia3State)

        when (playerCommand) {
            AudioFocusManager.PLAYER_COMMAND_DO_NOT_PLAY,
            AudioFocusManager.PLAYER_COMMAND_WAIT_FOR_CALLBACK -> {
                spirc.pauseForAudioFocus()
            }
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int
    ): ListenableFuture<*> {
//        spirc.seekTo(mediaItemIndex, positionMs)
        when (seekCommand) {
            COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> spirc.playerPrevious()
            COMMAND_SEEK_TO_PREVIOUS -> spirc.playerPrevious()

            COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> spirc.playerNext()
            COMMAND_SEEK_TO_NEXT -> spirc.playerNext()

            COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM -> scope.launch {
                spirc.seekTo(positionMs)
            }
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        spirc.playerPause()
        engine.pause()
        audioFocusManager.updateAudioFocus(false, STATE_IDLE)
        return Futures.immediateVoidFuture()
    }

    // TODO: Handle repeat mode
    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
//        spirc.setRepeatMode(when (repeatMode) {
//            Player.REPEAT_MODE_ONE -> RepeatMode.ONE
//            Player.REPEAT_MODE_ALL -> RepeatMode.ALL
//            else -> RepeatMode.NONE
//        })
        return Futures.immediateVoidFuture()
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        scope.launch(Dispatchers.IO) {
            spirc.shuffle(shuffleModeEnabled)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        setSpeed(playbackParameters.speed)
        return Futures.immediateVoidFuture()
    }

    fun setSpeed(speed: Float) {
        if (!stateHolder.localPlaybackAllowed) return
        scope.launch {
            stateHolder.setSpeed(speed)
            invalidateState()
        }
    }

    override fun handlePrepare(): ListenableFuture<*> {
        // Nothing to do: spirc commands are gated on the runtime state, so a
        // prepare arriving during a rebuild is simply dropped.
        return super.handlePrepare()
    }

    public override fun handleRelease(): ListenableFuture<*> {
        scope.cancel()
        engine.pause()
        engine.releaseAudioTrack()
        engine.releaseNative()

        audioFocusManager.release()
        return Futures.immediateVoidFuture()
    }

    private fun determineCommands(): Player.Commands {
        if (!stateHolder.localPlaybackAllowed) {
            val allowed = spirc.connectPlayback.snapshot.value?.allowed.orEmpty()
            return Player.Commands.Builder()
                .add(COMMAND_GET_CURRENT_MEDIA_ITEM).add(COMMAND_GET_METADATA)
                .apply {
                    if ("resume" in allowed || "pause" in allowed) add(COMMAND_PLAY_PAUSE)
                    if ("seek_to" in allowed) add(COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                    if ("skip_next" in allowed) { add(COMMAND_SEEK_TO_NEXT); add(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM) }
                    if ("skip_prev" in allowed) { add(COMMAND_SEEK_TO_PREVIOUS); add(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM) }
                }.build()
        }
        val builder = Player.Commands.Builder()
            .add(COMMAND_PLAY_PAUSE)
            .add(COMMAND_PREPARE)
            .add(COMMAND_GET_CURRENT_MEDIA_ITEM)
            .add(COMMAND_GET_METADATA)
            .add(COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            .add(COMMAND_SEEK_TO_NEXT)
            .add(COMMAND_SEEK_TO_PREVIOUS)
            .add(COMMAND_SEEK_TO_MEDIA_ITEM)
            .add(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .add(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .add(COMMAND_SET_REPEAT_MODE)
            .add(COMMAND_SET_SHUFFLE_MODE)
            .add(COMMAND_STOP)

        return builder.build()
    }
}
