package cc.tomko.outify.core.spirc

import android.util.Log
import cc.tomko.outify.core.Session
import cc.tomko.outify.core.SessionCallback
import cc.tomko.outify.core.model.OutifyUri
import cc.tomko.outify.data.repository.SettingsRepository
import cc.tomko.outify.playback.PlaybackStateHolder
import cc.tomko.outify.playback.model.getSpeed
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SpircController @Inject constructor(
    private val session: Session,
    private val spirc: SpircWrapper,
    private val playbackStateHolder: PlaybackStateHolder,
    private val settingsRepository: SettingsRepository,
    private val volumeController: VolumeController,
) {
    private var spircReady = false

    fun start() {
        session.initializeSession(object : SessionCallback {
            override fun onInitialized() {
                spirc.scope.launch {
                    initializeSpirc()
                }
            }

            override fun onShutdown() {
                handleSessionShutdown()
            }

            override fun onAutoRestart() {
                handleSessionAutoRestart()
            }
        })
    }

    fun restart() {
        Log.w("SpircController", "Restarting session...")
        playbackStateHolder.reset()
        spirc.setUsable(false)
        session.shutdown()
    }

    private suspend fun initializeSpirc() {
        val gapless = settingsRepository.gaplessPlayback.first()
        val normalise = settingsRepository.normalizePlayback.first()
        val bitrate = settingsRepository.bitrate.first()
        val deviceName = settingsRepository.deviceName.first()

        Spirc.initializeSpirc(object : SpircInitializationCallback {
            override fun initialized() {
                if (spircReady) return
                spircReady = true

                Spirc.bufferCallback(object : SpircBufferCallback {
                    override fun started() {
                        playbackStateHolder.setBuffering(true)
                    }

                    override fun stopped() {
                        playbackStateHolder.setBuffering(false)
                    }

                })

                Spirc.deviceCallback(object : SpircDeviceCallback {
                    override fun becameActive() {
                        spirc.startPlaybackService()
                        playbackStateHolder.setActiveDevice(true)
                    }

                    override fun becameInactive() {
                        playbackStateHolder.setActiveDevice(false)
                    }

                    override fun volumeChanged(volume: Int) {
                        //volumeController.onRemoteVolumeChanged(volume)
                    }
                })

                spirc.scope.launch {
                    activateAndTransfer()
//                    restoreLastPlayback()
                }
            }

            override fun failed() {
                handleSpircFailure()
            }

        }, gapless, normalise, bitrate.getSpeed(), deviceName)
    }

    private suspend fun restoreLastPlayback() {
        val lastContextUri = settingsRepository.lastContextUri.first() ?: return
        val lastTrackUri = settingsRepository.lastTrackUri.first()
        val lastPositionMs = settingsRepository.lastPositionMs.first()

        if (lastContextUri.isNullOrBlank()) return

        Log.i(
            "SpircController",
            "Restoring last playback: $lastContextUri @ ${lastTrackUri ?: "first"}"
        )

        if (lastTrackUri != null) {
            spirc.load(
                OutifyUri.fromUriString(lastContextUri),
                OutifyUri.fromUriString(lastTrackUri)
            )
        } else {
            spirc.load(OutifyUri.fromUriString(lastContextUri), null)
        }
        spirc.playerPause()

        if (lastPositionMs != null && lastPositionMs > 0) {
            spirc.seekTo(lastPositionMs)
        }
    }

    private suspend fun activateAndTransfer() {
        spirc.startPlaybackService()

        if (!spirc.activate()) {
            Log.e("SpircController", "Failed to activate Spirc session!")
            return
        }

        spirc.setUsable(true)

        spirc.scope.launch {
            if (settingsRepository.autoTransfer.first()) {
                if (!spirc.smartTransfer()) {
                    Log.w("SpircController", "Spirc session did not transfer!")
                    playbackStateHolder.setActiveDevice(false)
                    return@launch
                }

                playbackStateHolder.setActiveDevice(true)
            }
        }

        spirc.scope.launch {
            val shuffle = settingsRepository.shuffleEnabled.first()
            val repeat = settingsRepository.repeatEnabled.first()
            val repeatTrack = settingsRepository.repeatTrackEnabled.first()

            spirc.shuffle(shuffle)
            spirc.repeat(repeat, repeatTrack)
        }
    }

    private fun handleSessionShutdown() {
        Log.w("SpircController", "Session has shut down! Restarting..");
        spirc.setUsable(false)
    }

    private fun handleSessionAutoRestart() {
        spirc.setUsable(true)
    }

    private fun handleSpircFailure() {
        // Retry?
        // Tear down session?
        spirc.setUsable(false)
    }
}
