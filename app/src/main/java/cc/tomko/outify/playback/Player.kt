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
import cc.tomko.outify.core.Spirc.SpircWrapper
import cc.tomko.outify.core.model.CoverSize
import cc.tomko.outify.core.model.Track
import cc.tomko.outify.core.model.getCover
import cc.tomko.outify.core.model.toPlayableAudio
import cc.tomko.outify.playback.callbacks.PlayerEventCallback
import cc.tomko.outify.playback.model.PlayState
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
    application: Application,
    val stateHolder: PlaybackStateHolder,
    val spirc: SpircWrapper,
    val json: Json,
    val imageLoader: ImageLoader,
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
        AudioEngine(application.applicationContext, object : PlayerEventCallback {

            override fun onTrackChange(spotify_uri: String, json_str: String) {
                scope.launch {
                    // TODO: Add support for Episodes -> PlayableAudio
                    val track: Track = try {
                        json.decodeFromString(json_str)
                    } catch (e: Exception) {
                        Log.w("Player", "Failed to decode track JSON", e)
                        return@launch
                    }
                    stateHolder.setAudio(track.toPlayableAudio())

                    val cover = track.album?.getCover(CoverSize.LARGE)
                    val artworkUrl = cover?.let { ALBUM_COVER_URL + it.uri }
                    currentArtworkUri = artworkUrl

                    invalidateState()

                    artworkJob?.cancel()

                    if (artworkUrl == null) return@launch

                    artworkJob = scope.launch {
                        val loadResult = withContext(Dispatchers.IO) {
                            try {
                                val request = ImageRequest.Builder(application)
                                    .data(artworkUrl)
                                    .allowHardware(false)
                                    .build()

                                val result = imageLoader.execute(request)
                                val bmp = result.image?.toBitmap()

                                val finalBmp = bmp?.let {
                                    val max = 1024
                                    if (it.width > max || it.height > max) {
                                        val ratio = minOf(
                                            max.toFloat() / it.width,
                                            max.toFloat() / it.height
                                        )
                                        it.scale(
                                            (it.width * ratio).toInt(),
                                            (it.height * ratio).toInt()
                                        )
                                    } else it
                                }

                                val bytes = finalBmp?.let { fb ->
                                    ByteArrayOutputStream().use { stream ->
                                        fb.compress(
                                            android.graphics.Bitmap.CompressFormat.PNG,
                                            100,
                                            stream
                                        )
                                        stream.toByteArray()
                                    }
                                }

                                Pair(finalBmp, bytes)
                            } catch (e: Exception) {
                                Log.w("Player", "artwork load failed", e)
                                null
                            }
                        }

                        if (loadResult == null) return@launch

                        val (loadedBitmap, loadedBytes) = loadResult

                        val currentTrackId = stateHolder.state.value.currentAudio?.id
                        if (currentTrackId != track.id) {
                            return@launch
                        }

                        withContext(Dispatchers.Main) {
                            currentArtworkBitmap = loadedBitmap
                            currentArtworkBytes = loadedBytes

                            invalidateState()
                        }
                    }
                }
            }

            override fun onPositionUpdate(
                spotify_uri: String,
                position_ms: Long,
            ) {
                scope.launch {
                    stateHolder.seekTo(position_ms.toDuration(DurationUnit.MILLISECONDS))
                    invalidateState()
                }
            }

            override fun onPlayingStatus(playing: Boolean) {
                scope.launch {
                    stateHolder.setPlaying(playing)
                    invalidateState()
                }
            }
        })

    private val audioFocusManager = AudioFocusManager(
        application.applicationContext,
        application.mainLooper,
        object : AudioFocusManager.PlayerControl {
            override fun setVolumeMultiplier(volume: Float) {
                Log.i("Player", "Volume changed to $volume")
                engine.setVolume(volume)
            }

            override fun executePlayerCommand(command: Int) {
                when (command) {
                    AudioFocusManager.PLAYER_COMMAND_WAIT_FOR_CALLBACK,
                    AudioFocusManager.PLAYER_COMMAND_DO_NOT_PLAY -> {
                        scope.launch(Dispatchers.IO) {
                            spirc.playerPause()
                        }
                        scope.launch {
                            stateHolder.setPlaying(false)
                            invalidateState()
                        }
                    }

                    AudioFocusManager.PLAYER_COMMAND_PLAY_WHEN_READY -> {
                        scope.launch(Dispatchers.IO) {
                            spirc.playerPlay()
                        }
                        scope.launch {
                            stateHolder.setPlaying(true)
                            invalidateState()
                        }
                    }
                }
            }
        }
    )

    init {
        audioFocusManager.setAudioAttributes(PlaybackService.AUDIO_ATTRIBUTES)
    }

    override fun getState(): State {
        val ps = stateHolder.state.value
        val audio = ps.currentAudio ?: return State.Builder()
            .setPlaybackState(STATE_IDLE)
            .setAvailableCommands(determineCommands())
            .setPlayWhenReady(false, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaylist(emptyList())
            .build()

        val subtitle = audio.artists?.joinToString { it.name }
            ?: audio.showName
            ?: "Unknown source"

        val mediaMetadata = MediaMetadata.Builder()
            .setTitle(audio.name)
            .setDisplayTitle(audio.name)
            .setArtist(subtitle)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
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

        val playbackState = when {
            ps.state == PlayState.BUFFERING -> STATE_BUFFERING
            playlist.isEmpty() -> STATE_IDLE
            ps.isPlaying -> STATE_READY
            else -> STATE_READY
        }

        return State.Builder()
            .setAudioAttributes(PlaybackService.AUDIO_ATTRIBUTES)
            .setPlaybackState(playbackState)
            .setAvailableCommands(determineCommands())
            .setPlayWhenReady(ps.isPlaying, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackParameters(PlaybackParameters(ps.playbackSpeed))
            .setCurrentMediaItemIndex(if (playlist.isNotEmpty()) 0 else C.INDEX_UNSET)
            .setContentPositionMs(ps.position.active.inWholeMilliseconds)
            .setIsLoading(ps.state == PlayState.BUFFERING)
            .setPlaylist(playlist)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        val currentMedia3State = if (stateHolder.state.value.currentAudio == null) STATE_IDLE else STATE_READY

        val playerCommand = audioFocusManager.updateAudioFocus(playWhenReady, currentMedia3State)

        scope.launch(Dispatchers.IO) {
            when (playerCommand) {
                AudioFocusManager.PLAYER_COMMAND_PLAY_WHEN_READY -> {
                    if (playWhenReady) spirc.playerPlay() else spirc.playerPause()
                }

                AudioFocusManager.PLAYER_COMMAND_DO_NOT_PLAY,
                AudioFocusManager.PLAYER_COMMAND_WAIT_FOR_CALLBACK -> {
                    spirc.playerPause()
                }
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
            COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> scope.launch(Dispatchers.IO) { spirc.playerPrevious() }
            COMMAND_SEEK_TO_PREVIOUS -> scope.launch(Dispatchers.IO) { spirc.playerPrevious() }

            COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> scope.launch(Dispatchers.IO) { spirc.playerNext() }
            COMMAND_SEEK_TO_NEXT -> scope.launch(Dispatchers.IO) { spirc.playerNext() }

            COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM -> scope.launch(Dispatchers.IO) {
                spirc.seekTo(positionMs)
            }
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        scope.launch(Dispatchers.IO) {
            spirc.playerPause() //TODO: Implement playerStop
        }
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
//        spirc.setPlaybackSpeed(playbackParameters.speed)
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        scope.launch(Dispatchers.IO) {
            spirc.ensureUsable()
        }

        return super.handlePrepare()
    }

    public override fun handleRelease(): ListenableFuture<*> {
        engine.releaseAudioTrack()
        engine.releaseNative()

        audioFocusManager.release()
        return Futures.immediateVoidFuture()
    }

    private fun determineCommands(): Player.Commands {
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