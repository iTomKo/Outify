package cc.tomko.outify.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Binder
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player.REPEAT_MODE_ALL
import androidx.media3.common.Player.REPEAT_MODE_OFF
import androidx.media3.common.Player.REPEAT_MODE_ONE
import androidx.media3.common.Player.STATE_IDLE
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionToken
import cc.tomko.outify.MainActivity
import cc.tomko.outify.MediaSessionConstants
import cc.tomko.outify.R
import cc.tomko.outify.core.SpClient
import cc.tomko.outify.core.spirc.SpircWrapper
import cc.tomko.outify.core.model.SpotifyUri
import cc.tomko.outify.data.dao.LikedDao
import cc.tomko.outify.data.metadata.TrackMetadataHelper
import cc.tomko.outify.data.repository.LikedRepository
import cc.tomko.outify.data.repository.SettingsRepository
import cc.tomko.outify.playback.PlaybackStateHolder
import cc.tomko.outify.playback.Player
import cc.tomko.outify.playback.model.RepeatMode
import cc.tomko.outify.utils.CoilBitmapLoader
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.AndroidEntryPoint
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Singleton


@UnstableApi
@Singleton
@AndroidEntryPoint
class PlaybackService : MediaLibraryService(),
    androidx.media3.common.Player.Listener,
    AudioManager.OnAudioFocusChangeListener {
    companion object {
        const val ROOT = "root"
        const val TRACK = "track"
        const val ARTIST = "artist"
        const val ALBUM = "album"
        const val PLAYLIST = "playlist"
        const val SEARCH = "search"
        const val LIKED = "liked"
        const val RECENT = "recent"

        const val NOTIFICATION_ID = 4894
        const val CHANNEL_ID = "outify_channel_01"
        const val CHANNEL_NAME = "Media Playback"

        val TAG = PlaybackService::class.simpleName.toString()

        val AUDIO_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder().apply {
            setUsage(C.USAGE_MEDIA)
            setContentType(AUDIO_CONTENT_TYPE_MUSIC)
        }.build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Media playback controls"
            setShowBadge(false)
        }
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private val scope = CoroutineScope(Dispatchers.Main)
    private val offloadScope = CoroutineScope(Dispatchers.IO)

    @Inject
    lateinit var player: Player

    @Inject
    lateinit var trackDatabase: TrackMetadataHelper

    @Inject
    lateinit var settings: SettingsRepository

    @Inject
    lateinit var mediaLibrarySessionCallback: MediaLibrarySessionCallback

    @Inject
    lateinit var playbackStateHolder: PlaybackStateHolder

    @Inject
    lateinit var spirc: SpircWrapper

    @Inject
    lateinit var spClient: SpClient

    @Inject
    lateinit var likedDao: LikedDao

    @Inject
    lateinit var likedRepository: LikedRepository

    private lateinit var audioManager: AudioManager
    private var hasAudioFocus = false
    private var mediaLibrarySession: MediaLibrarySession? = null
    private var keepAlive: Boolean = true
    private val binder = MusicBinder()

    private val becomingNoisyListener = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != AudioManager.ACTION_AUDIO_BECOMING_NOISY) return
            if (player.playWhenReady) player.pause()
        }
    }

    override fun onGetSession(controller: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaLibrarySession
    }

    override fun onCreate() {
        Log.i(TAG, "Starting PlaybackService")
        super.onCreate()

        Log.i(TAG, "Creating audio focus manager")
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        player.setAudioAttributes(AUDIO_ATTRIBUTES, true)

        createNotificationChannel()

        startForeground(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Outify")
                .setContentText("Loading...")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .build()
        )

        mediaLibrarySessionCallback.apply {
            service = this@PlaybackService
            toggleLike = ::toggleLike
            toggleStartRadio = ::toggleStartRadio
            toggleRepeatMode = ::toggleRepeatMode
        }

        mediaLibrarySession = MediaLibrarySession.Builder(this, player, mediaLibrarySessionCallback)
            .setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setBitmapLoader(
                CoilBitmapLoader(
                    scope,
                    context = this,
                )
            )
            .build()

        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val browserFuture = MediaBrowser.Builder(this, sessionToken).buildAsync()
        browserFuture.addListener({ browserFuture.get() }, MoreExecutors.directExecutor())

        if(requestAudioFocus()) {
            Log.i(TAG, "Focus requested :)")
        } else {
            Log.i(TAG, "Focus failed to request :(")
        }

        scope.launch {
            playbackStateHolder.state
                .map { it.currentAudio to it.repeatMode }
                .distinctUntilChanged()
                .collect { _ ->
                    updateNotification()
                }

            settings.keepalive.collect {
                keepAlive = it
            }
        }

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider(
                this@PlaybackService,
                { NOTIFICATION_ID },
                CHANNEL_ID,
                R.string.app_name
            ).apply {
                setSmallIcon(R.drawable.ic_launcher_foreground)
            }
        )

        registerReceiver(
            becomingNoisyListener,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        )
    }

    private fun toggleLike() {
        val item = player.currentMediaItem ?: return
        val id = item.mediaId
        val audio = playbackStateHolder.state.value.currentAudio ?: return
        Log.i(TAG, "Toggling like for $id")

        scope.launch {
            val isTrack = audio.isTrack()
            val uri = if (isTrack) "spotify:track:$id" else "spotify:episode:$id"

            val wasLiked = if (isTrack) {
                likedRepository.isLiked(id)
            } else {
                likedRepository.isLikedEpisode(id)
            }

            if (isTrack) {
                if (wasLiked) likedRepository.removeLiked(id) else likedRepository.addLiked(id)
            } else {
                if (wasLiked) likedRepository.removeLikedEpisode(id) else likedRepository.addLikedEpisode(id)
            }
            updateNotification()

            val success = try {
                if (wasLiked) spClient.deleteItems(arrayOf(uri)) else spClient.saveItems(arrayOf(uri))
            } catch (e: Exception) {
                Log.w(TAG, "spClient failed to ${if (wasLiked) "delete" else "save"} $uri", e)
                false
            }

            if (!success) {
                Log.w(TAG, "Rolling back like state for $uri")
                if (isTrack) {
                    if (wasLiked) likedRepository.addLiked(id) else likedRepository.removeLiked(id)
                } else {
                    if (wasLiked) likedRepository.addLikedEpisode(id) else likedRepository.removeLikedEpisode(id)
                }
                updateNotification()
            }
        }
    }

    private fun toggleStartRadio() {
        val item = player.currentMediaItem ?: return
        val id = item.mediaId
        Log.i(TAG, "Starting radio for $id")

        scope.launch {
            spirc.startRadio(SpotifyUri.Track(id), false)
        }
    }

    private fun toggleRepeatMode() {
        val state = playbackStateHolder.state.value
        val repeatMode = state.repeatMode.next()

        player.repeatMode = repeatMode.toMediaRepeatMode()

        scope.launch {
            settings.setRepeat(repeatMode.repeat)
            settings.setRepeatTrack(repeatMode.repeatTrack)
            playbackStateHolder.setRepeatMode(repeatMode)
            spirc.repeat(repeatMode.repeat, repeatMode.repeatTrack)
        }
    }

    fun updateNotification() {
        mediaLibrarySession ?: return
        val state = playbackStateHolder.state.value
        val audio = state.currentAudio
        val hasAudio = audio != null

        val repeatMode = state.repeatMode

        scope.launch {
            val isLiked = hasAudio && if (audio.isTrack()) {
                likedDao.containsTrack(audio.id)
            } else {
                likedDao.containsEpisode(audio.id)
            }
            val buttons = listOf(
                CommandButton.Builder(
                    when (repeatMode) {
                        RepeatMode.NONE -> CommandButton.ICON_REPEAT_OFF
                        RepeatMode.ONE  -> CommandButton.ICON_REPEAT_ONE
                        RepeatMode.ALL  -> CommandButton.ICON_REPEAT_ALL
                    }
                )
                    .setDisplayName(
                        getString(
                            when (player.repeatMode) {
                                REPEAT_MODE_OFF -> R.string.repeat_mode_off
                                REPEAT_MODE_ONE -> R.string.repeat_mode_one
                                REPEAT_MODE_ALL -> R.string.repeat_mode_all
                                else -> {
                                    Log.w(
                                        TAG,
                                        "Unknown repeat mode for display name: ${player.repeatMode}"
                                    )
                                    R.string.repeat_mode_off
                                }
                            }
                        )
                    )
                    .setSessionCommand(MediaSessionConstants.CommandToggleRepeatMode)
                    .build(),
                CommandButton.Builder(
                    when (isLiked) {
                        true -> CommandButton.ICON_HEART_FILLED
                        false -> CommandButton.ICON_HEART_UNFILLED
                    }
                )
                    .setDisplayName(getString(R.string.like))
                    .setSessionCommand(MediaSessionConstants.CommandToggleLike)
                    .setEnabled(hasAudio && audio.isTrack())
                    .build(),
                CommandButton.Builder(CommandButton.ICON_RADIO)
                    .setDisplayName(getString(R.string.start_radio))
                    .setSessionCommand(MediaSessionConstants.CommandToggleStartRadio)
                    .setEnabled(hasAudio && audio.isTrack())
                    .build()
            )
            mediaLibrarySession!!.setMediaButtonPreferences(buttons)
            Log.i(TAG, "Updated notification with ${buttons.size} buttons, isLiked=$isLiked")
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        super.onPlayerError(error)

        Toast.makeText(
            this@PlaybackService,
            "plr: ${error.message} (${error.errorCode}): ${error.cause?.message ?: ""}",
            Toast.LENGTH_LONG
        ).show()
    }

    override fun onPlaybackStateChanged(@androidx.media3.common.Player.State playbackState: Int) {
        if (playbackState == STATE_IDLE) {
            Log.i(TAG, "Playback idling")
        }
    }

    override fun onRepeatModeChanged(repeatMode: Int) {
        updateNotification()
        offloadScope.launch {
            settings.setRepeat(repeatMode != REPEAT_MODE_OFF)
        }
    }

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
        player.shuffleModeEnabled = shuffleModeEnabled
    }

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        if (!keepAlive)
            return

        super.onUpdateNotification(session, startInForegroundRequired)
    }

    override fun onDestroy() {
        Log.i(TAG, "Terminating PlaybackService")

        mediaLibrarySession?.run {
            player.stop()
            player.release()
            release()
            mediaLibrarySession = null
        }
        scope.cancel()
        offloadScope.cancel()
        unregisterReceiver(becomingNoisyListener)
        abandonAudioFocus()

        super.onDestroy()

        Log.i(TAG, "Terminated PlaybackService")
    }

    override fun onBind(intent: Intent?) = super.onBind(intent) ?: binder
    override fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasAudioFocus = true
                Log.i(TAG, "Resuming playback")
//                resumePlayback()
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasAudioFocus = false
                Log.i(TAG, "Stopping playback")
//                stopPlayback()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                hasAudioFocus = false
                Log.i(TAG, "Pausing playback")
//                pausePlayback()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Log.i(TAG, "Ducking playback")
//                mediaPlayer?.setVolume(0.2f, 0.2f)
            }
        }
    }

    private var audioFocusRequest: AudioFocusRequest? = null

    private fun requestAudioFocus(): Boolean {
        audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .build())
            .build()

        val result = audioManager.requestAudioFocus(audioFocusRequest!!)
        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hasAudioFocus
    }

    private fun abandonAudioFocus() {
        audioFocusRequest?.let {
            audioManager.abandonAudioFocusRequest(it)
        }
        hasAudioFocus = false
    }

    inner class MusicBinder : Binder() {
        val service: PlaybackService
            get() = this@PlaybackService
    }
}
