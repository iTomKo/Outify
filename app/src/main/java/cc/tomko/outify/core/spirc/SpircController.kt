package cc.tomko.outify.core.spirc

import android.util.Log
import cc.tomko.outify.core.Session
import cc.tomko.outify.core.SessionCallback
import cc.tomko.outify.core.jam.JamClient
import cc.tomko.outify.core.jam.JamException
import cc.tomko.outify.data.repository.SettingsRepository
import cc.tomko.outify.data.repository.CollectionSyncCoordinator
import cc.tomko.outify.playback.PlaybackStateHolder
import cc.tomko.outify.playback.model.getSpeed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
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
    private val jam: JamClient,
    private val collectionSync: CollectionSyncCoordinator,
) {
    private val _state = MutableStateFlow(SpircState.Stopped)

    /** Current lifecycle state of the Connect runtime. */
    val state: StateFlow<SpircState> = _state.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)

    /** Reason of the last failure, or `null` when the last attempt succeeded. */
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var bufferCallbacksRegistered = false
    private var transferPreferencesJob: Job? = null

    fun start() {
        if (transferPreferencesJob == null) {
            transferPreferencesJob = spirc.scope.launch {
                settingsRepository.playbackSettings
                    .map { it.autoTransfer to it.smartTransfer }
                    .distinctUntilChanged()
                    .collect { (auto, smart) -> Spirc.setTransferPreferences(auto, smart) }
            }
        }
        try {
            jam.initialize()
        } catch (error: JamException) {
            Log.w(TAG, "Jam listener registration failed", error)
        }
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
                finishRuntimeInitialization()
            }

            override fun onCollectionChanged(sessionJson: String) {
                collectionSync.onInvalidation(sessionJson)
            }

            override fun onCollectionDelta(changesJson: String) {
                collectionSync.onDelta(changesJson)
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
            val smartTransfer = settingsRepository.smartTransfer.first()
            Spirc.setTransferPreferences(autoTransfer, smartTransfer)

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
        val transferSettings = settingsRepository.playbackSettings.first()
        Spirc.setTransferPreferences(transferSettings.autoTransfer, transferSettings.smartTransfer)
        val gapless = settingsRepository.gaplessPlayback.first()
        val normalise = settingsRepository.normalizePlayback.first()
        val bitrate = settingsRepository.bitrate.first()
        val crossfadeMillis = settingsRepository.crossfadeMillis.first()
        val deviceName = settingsRepository.deviceName.first()

        updateState(SpircState.Starting)

        Spirc.initializeSpirc(object : SpircInitializationCallback {
            override fun initialized() {
                registerCallbacks()
                finishRuntimeInitialization { activateAndTransfer() }
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
     * Cold startup and reconnects both restore modes before exposing a usable
     * runtime. Direct JNI setters seed Rust's load options even while the Kotlin
     * wrapper is still gated, without clearing the saved queue or rewriting prefs.
     */
    private fun finishRuntimeInitialization(afterReady: suspend () -> Unit = {}) {
        spirc.scope.launch {
            try {
                restorePlaybackModes(
                    shuffleEnabled = settingsRepository.shuffleEnabled,
                    repeatMode = settingsRepository.repeatMode,
                    applyShuffle = Spirc::shuffle,
                    applyRepeat = Spirc::repeat,
                ) { repeat ->
                    playbackStateHolder.setRepeatMode(repeat)
                    updateState(SpircState.Ready)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "Failed to restore playback modes", error)
                _lastError.value = error.message
                updateState(SpircState.Failed)
                return@launch
            }
            collectionSync.resume()
            afterReady()
        }
    }

    private suspend fun activateAndTransfer() {
        val transferred = try {
            withContext(Dispatchers.IO) { Spirc.automaticTransfer() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Skipping startup transfer: could not check Connect playback", error)
            return
        }
        if (!transferred) {
            Log.d(TAG, "Skipping startup transfer: disabled, unavailable, or remote playback is protected")
            return
        }
        spirc.startPlaybackService()
        // Native device callbacks publish actual activation, not request acceptance.
    }

    private fun updateState(state: SpircState) {
        if (!state.isUsable) collectionSync.pause()
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
