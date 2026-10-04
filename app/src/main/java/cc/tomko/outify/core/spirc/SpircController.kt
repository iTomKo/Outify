package cc.tomko.outify.core.spirc

import android.util.Log
import cc.tomko.outify.core.Session
import cc.tomko.outify.core.SessionCallback
import cc.tomko.outify.data.repository.SettingsRepository
import cc.tomko.outify.playback.PlaybackStateHolder
import cc.tomko.outify.playback.model.getSpeed
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

private const val TAG = "SpircController"

/** Upper bound for [SpircController.restartAndAwaitReady]. */
private const val RESTART_TIMEOUT_MS = 30_000L

/**
 * Owns the spirc lifecycle.
 */
@Singleton
class SpircController @Inject constructor(
    private val session: Session,
    private val spirc: SpircWrapper,
    private val playbackStateHolder: PlaybackStateHolder,
    private val settingsRepository: SettingsRepository,
) {
    private val _state = MutableStateFlow(SpircState.Stopped)

    /** Current lifecycle state of the Connect runtime. */
    val state: StateFlow<SpircState> = _state.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)

    /** Reason of the last failure, or `null` when the last attempt succeeded. */
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var bufferCallbacksRegistered = false

    fun start() {
        session.initializeSession(object : SessionCallback {
            override fun onInitialized() {
                spirc.scope.launch {
                    initializeSpirc()
                }
            }

            override fun onShutdown() {
                // The core session is gone; a rebuild is already under way.
                updateState(SpircState.Rebuilding)
            }

            override fun onRestarting() {
                updateState(SpircState.Rebuilding)
            }

            override fun onRestarted() {
                updateState(SpircState.Ready)
                refreshPlaybackState()
            }

            override fun onFailed(reason: String) {
                Log.e(TAG, "spirc failed: $reason")
                _lastError.value = reason
                updateState(SpircState.Failed)
            }
        })
    }

    /**
     * Rebuilds the Connect runtime, applying the current playback settings.
     */
    fun restart(reason: String = "requested") {
        updateState(SpircState.Rebuilding)

        spirc.scope.launch {
            _lastError.value = null

            val gapless = settingsRepository.gaplessPlayback.first()
            val normalise = settingsRepository.normalizePlayback.first()
            val bitrate = settingsRepository.bitrate.first()
            val crossfadeMillis = settingsRepository.crossfadeMillis.first()
            val deviceName = settingsRepository.deviceName.first()
            val autoTransfer = settingsRepository.autoTransfer.first()

            if (!Spirc.requestRestart(reason, gapless, normalise, bitrate.getSpeed(), crossfadeMillis, deviceName, autoTransfer)) {
                Log.e(TAG, "restart request was rejected")
                updateState(SpircState.Failed)
            }
        }
    }

    /**
     * Requests a restart and suspends until the runtime is usable again.
     */
    suspend fun restartAndAwaitReady(
        reason: String = "retry",
        timeoutMs: Long = RESTART_TIMEOUT_MS,
    ): Boolean {
        restart(reason)

        val settled = withTimeoutOrNull(timeoutMs.milliseconds) {
            state.first { it == SpircState.Ready || it == SpircState.Failed }
        }

        return settled == SpircState.Ready
    }

    private suspend fun initializeSpirc() {
        val gapless = settingsRepository.gaplessPlayback.first()
        val normalise = settingsRepository.normalizePlayback.first()
        val bitrate = settingsRepository.bitrate.first()
        val crossfadeMillis = settingsRepository.crossfadeMillis.first()
        val deviceName = settingsRepository.deviceName.first()

        updateState(SpircState.Starting)

        Spirc.initializeSpirc(object : SpircInitializationCallback {
            override fun initialized() {
                registerCallbacks()
                updateState(SpircState.Ready)

                spirc.scope.launch {
                    activateAndTransfer()
                }
            }

            override fun failed() {
                Log.e(TAG, "spirc initialization failed")
                updateState(SpircState.Failed)
            }

        }, gapless, normalise, bitrate.getSpeed(), crossfadeMillis, deviceName)
    }

    /**
     * Registers the native playback callbacks once per process.
     */
    private fun registerCallbacks() {
        if (bufferCallbacksRegistered) return
        bufferCallbacksRegistered = true

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
    }

    /**
     * Re-applies the persisted playback preferences to the fresh runtime.
     */
    private fun refreshPlaybackState() {
        spirc.scope.launch {
            val shuffle = settingsRepository.shuffleEnabled.first()
            val repeat = settingsRepository.repeatEnabled.first()
            val repeatTrack = settingsRepository.repeatTrackEnabled.first()

            spirc.shuffle(shuffle)
            spirc.repeat(repeat, repeatTrack)
        }
    }

    private suspend fun activateAndTransfer() {
        spirc.startPlaybackService()

        if (!spirc.activate()) {
            Log.e(TAG, "Failed to activate Spirc session!")
            return
        }

        if (!settingsRepository.autoTransfer.first()) {
            return
        }

        if (!spirc.smartTransfer()) {
            Log.w(TAG, "Spirc session did not transfer!")
            playbackStateHolder.setActiveDevice(false)
            return
        }

        playbackStateHolder.setActiveDevice(true)
    }

    private fun updateState(state: SpircState) {
        if (_state.value == state) return

        Log.i(TAG, "state ${_state.value} -> $state")
        _state.value = state
        spirc.onStateChanged(state)
    }
}

/**
 * Lifecycle of the Connect runtime.
 */
enum class SpircState {
    /** Nothing is running. */
    Stopped,

    /** First startup is in progress. */
    Starting,

    /** Runtime is usable; playback commands are accepted. */
    Ready,

    /** Runtime is being rebuilt; playback commands are dropped. */
    Rebuilding,

    /** Initialization or the last rebuild gave up. */
    Failed;

    /** Whether playback commands can be forwarded right now. */
    val isUsable: Boolean
        get() = this == Ready
}
