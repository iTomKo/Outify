package cc.tomko.outify.core.spirc

import android.content.Context
import android.media.AudioManager
import cc.tomko.outify.playback.PlaybackStateHolder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VolumeController @Inject constructor(
    private val spirc: SpircWrapper,
    private val playbackStateHolder: PlaybackStateHolder,
    @ApplicationContext context: Context,
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var syncJob: Job? = null

    companion object {
        const val SPOTIFY_MAX_VOLUME = 65535
    }

    fun start() {
        val initial = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val spotifyVolume = (initial.toDouble() / max * SPOTIFY_MAX_VOLUME).toInt()
        playbackStateHolder.setVolume(spotifyVolume)
    }

    fun onRemoteVolumeChanged(spotifyVolume: Int) {
        playbackStateHolder.setVolume(spotifyVolume)

        if (!playbackStateHolder.state.value.isPlaying) return

        val androidVolume = mapSpotifyToAndroid(spotifyVolume)
        if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) != androidVolume) {
            audioManager.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                androidVolume,
                0,
            )
        }
    }

    fun onAndroidVolumeChanged() {
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val spotifyVolume =
            (current.toDouble() / max * SPOTIFY_MAX_VOLUME).toInt().coerceIn(0, SPOTIFY_MAX_VOLUME)

        playbackStateHolder.setVolume(spotifyVolume)

        if (!playbackStateHolder.state.value.isPlaying) return

        syncJob?.cancel()
        syncJob = scope.launch {
            delay(50)
            spirc.setVolume(spotifyVolume)
        }
    }

    private fun mapSpotifyToAndroid(spotifyVolume: Int): Int {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return (spotifyVolume.toDouble() / SPOTIFY_MAX_VOLUME * max).toInt().coerceIn(0, max)
    }
}
