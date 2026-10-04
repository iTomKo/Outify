package cc.tomko.outify.ui.viewmodel.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.tomko.outify.core.AuthManager
import cc.tomko.outify.core.SpClient
import cc.tomko.outify.core.model.CurrentUserProfile
import cc.tomko.outify.core.spirc.Spirc
import cc.tomko.outify.core.spirc.SpircController
import cc.tomko.outify.core.spirc.SpircState
import cc.tomko.outify.core.spirc.SpircWrapper
import cc.tomko.outify.data.repository.SettingsRepository
import cc.tomko.outify.playback.PlaybackStateHolder
import cc.tomko.outify.utils.ExceptionCollector
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject

/** How often the native restart snapshot is re-read. */
private const val DIAGNOSTICS_POLL_MS = 1_000L

@HiltViewModel
class DebugViewModel @Inject constructor(
    @ApplicationContext val context: Context,
    val spClient: SpClient,
    val authManager: AuthManager,
    val json: Json,
    val spircController: SpircController,
    val spircWrapper: SpircWrapper,
    val playbackStateHolder: PlaybackStateHolder,
    val settingsRepository: SettingsRepository,
    val exceptionCollector: ExceptionCollector,
) : ViewModel() {
    //region Accounts
    private val _isPlaybackLoggedIn = MutableStateFlow(false)
    val isPlaybackLoggedIn: StateFlow<Boolean> = _isPlaybackLoggedIn.asStateFlow()

    private val _isAccountLoggedIn = MutableStateFlow(false)
    val isAccountLoggedIn: StateFlow<Boolean> = _isAccountLoggedIn.asStateFlow()

    private val _hasAccountsFile = MutableStateFlow(false)
    val hasAccountsFile: StateFlow<Boolean> = _hasAccountsFile.asStateFlow()

    private val _hasPlaybackFile = MutableStateFlow(false)
    val hasPlaybackFile: StateFlow<Boolean> = _hasPlaybackFile.asStateFlow()

    private val _userId = MutableStateFlow<String?>(null)
    val userId: StateFlow<String?> = _userId.asStateFlow()

    private val _username = MutableStateFlow<String?>(null)
    val username: StateFlow<String?> = _username.asStateFlow()

    private val _isPremium = MutableStateFlow(true)
    val isPremium: StateFlow<Boolean> = _isPremium.asStateFlow()
    //endregion

    //region Spirc
    /**
     * Live lifecycle state from [SpircController], not a snapshot.
     */
    val spircState: StateFlow<SpircState> = spircController.state

    /** Gate the command wrapper actually enforces; mirrors [spircState]. */
    val isSpircUsable: StateFlow<Boolean> = spircWrapper.isUsableFlow

    /** Reason of the last rebuild failure, or `null` while healthy. */
    val spircLastError: StateFlow<String?> = spircController.lastError

    /** Raw `key=value` snapshot read back from the native supervisor. */
    private val _spircDiagnostics = MutableStateFlow<Map<String, String>>(emptyMap())
    val spircDiagnostics: StateFlow<Map<String, String>> = _spircDiagnostics.asStateFlow()

    private val _spircDiagnosticsError = MutableStateFlow<String?>(null)
    val spircDiagnosticsError: StateFlow<String?> = _spircDiagnosticsError.asStateFlow()

    private var diagnosticsPolling = false

    private val _isRestarting = MutableStateFlow(false)

    /** `true` while the debug-triggered restart is still in flight. */
    val isRestarting: StateFlow<Boolean> = _isRestarting.asStateFlow()

    /**
     * Restarts the runtime on demand and waits for it to settle.
     */
    fun restartSpirc() {
        if (_isRestarting.value) return

        viewModelScope.launch {
            _isRestarting.value = true
            try {
                spircController.restartAndAwaitReady("debug screen")
            } finally {
                _isRestarting.value = false
            }
        }
    }

    /**
     * Polls the native snapshot on a fixed interval.
     */
    private fun startDiagnosticsPolling() {
        if (diagnosticsPolling) return
        diagnosticsPolling = true

        viewModelScope.launch {
            while (isActive) {
                refreshSpircDiagnostics()
                delay(DIAGNOSTICS_POLL_MS)
            }
        }
    }
    //endregion

    //region Playback
    val isPlaying = playbackStateHolder.state.map { it.isPlaying }
    val isBuffering = playbackStateHolder.state.map { it.isBuffering }
    val isActiveDevice = playbackStateHolder.state.map { it.isActiveDevice }
    val currentAudioName = playbackStateHolder.state.map { it.currentAudio?.name }
    val queueSize = playbackStateHolder.state.map { it.queue.size }
    //endregion

    //region Preferences
    private val _preferences = MutableStateFlow<Map<String, String>>(emptyMap())
    val preferences: StateFlow<Map<String, String>> = _preferences.asStateFlow()
    //endregion

    fun loadData() {
        _isPlaybackLoggedIn.value = authManager.hasCachedCredentials()
        _hasAccountsFile.value = File(context.filesDir, "account.json").exists()
        _hasPlaybackFile.value = File(context.filesDir, "credentials.json").exists()

        startDiagnosticsPolling()

        viewModelScope.launch {
            val authenticated = withContext(Dispatchers.IO) { spClient.isOAuthAuthenticated() }
            _isAccountLoggedIn.value = authenticated
            if (authenticated) {
                fetchProfile()
            }
        }

        viewModelScope.launch {
            _preferences.value = mapOf(
                "Bitrate" to settingsRepository.bitrate.first().name,
                "Gapless" to settingsRepository.gaplessPlayback.first().toString(),
                "Keep alive" to settingsRepository.keepalive.first().toString(),
                "Auto transfer" to settingsRepository.autoTransfer.first().toString(),
                "Device name" to settingsRepository.deviceName.first(),
                "Shuffle" to settingsRepository.shuffleEnabled.first().toString(),
                "Repeat" to settingsRepository.repeatEnabled.first().toString(),
                "Repeat track" to settingsRepository.repeatTrackEnabled.first().toString(),
                "Romanize lyrics" to settingsRepository.romanizeLyrics.first().toString(),
                "Show lyrics by default" to settingsRepository.showLyricsByDefault.first()
                    .toString(),
                "Normalize audio" to settingsRepository.normalizePlayback.first().toString(),
            )
        }
    }


    /**
     * Reads the native snapshot into a map, one entry per `key=value` line.
     *
     * Kept off the main thread because it crosses into JNI, and tolerant of a
     * null result because the library may not be loaded.
     */
    private suspend fun refreshSpircDiagnostics() {
        val raw = withContext(Dispatchers.IO) {
            runCatching { Spirc.diagnostics() }.getOrNull()
        }

        if (raw.isNullOrEmpty()) {
            _spircDiagnosticsError.value = "diagnostics unavailable"
            return
        }

        _spircDiagnosticsError.value = null
        _spircDiagnostics.value = raw.lineSequence()
            .mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) {
                    null
                } else {
                    line.substring(0, separator) to line.substring(separator + 1)
                }
            }
            .toMap()
    }

    private fun fetchProfile() {
        viewModelScope.launch {
            try {
                val profile = withContext(Dispatchers.IO) { spClient.getCurrentUserProfile() }
                if (profile == null) {
                    return@launch
                }
                val jsonObject = json.decodeFromString<CurrentUserProfile>(profile)

                val id = jsonObject.id
                val username = jsonObject.displayName
                val imageUrl = jsonObject.images.first().url

                _isPremium.value = jsonObject.product == "premium"

                _userId.value = id
                _username.value = username
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
