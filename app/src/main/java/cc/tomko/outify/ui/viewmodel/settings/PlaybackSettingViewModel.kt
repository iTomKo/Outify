package cc.tomko.outify.ui.viewmodel.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.tomko.outify.BuildConfig
import cc.tomko.outify.LibrespotFfi
import cc.tomko.outify.core.spirc.SpircController
import cc.tomko.outify.data.repository.PlaybackSettings
import cc.tomko.outify.data.repository.SettingsRepository
import cc.tomko.outify.playback.model.Bitrate
import dagger.hilt.android.lifecycle.HiltViewModel
import jakarta.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

@HiltViewModel
class PlaybackSettingViewModel @Inject constructor(
    val settingsRepository: SettingsRepository,
    val spirc: SpircController
) : ViewModel() {
    private val _needsRestart = MutableStateFlow(false)
    val needsRestart: StateFlow<Boolean> = _needsRestart

    private val _isRestarting = MutableStateFlow(false)

    /** `true` while a restart triggered from this screen is still running. */
    val isRestarting: StateFlow<Boolean> = _isRestarting

    val settings: Flow<PlaybackSettings> =
        settingsRepository.playbackSettings

    val romanizeLyrics: Flow<Boolean> =
        settingsRepository.romanizeLyrics

    val lyricsFallbackEnabled: Flow<Boolean> =
        settingsRepository.lyricsFallbackEnabled

    val clientId: Flow<String?> =
        settingsRepository.clientId

    val clientSecret: Flow<String?> =
        settingsRepository.clientSecret

    fun setGaplessPlayback(enabled: Boolean) {
        viewModelScope.launch {
            _needsRestart.value = true
            settingsRepository.setGaplessPlayback(enabled)
        }
    }

    fun setFastForwardMs(ms: Long) {
        viewModelScope.launch {
            settingsRepository.setFastForwardMs(ms)
        }
    }

    fun setNormalizeAudio(enabled: Boolean) {
        viewModelScope.launch {
            _needsRestart.value = true
            settingsRepository.setNormalizePlayback(enabled)
        }
    }

    fun setKeepAlive(enabled: Boolean) {
        viewModelScope.launch {
            _needsRestart.value = true
            settingsRepository.setKeepalive(enabled)
        }
    }

    fun setBitrate(bitrate: Bitrate) {
        viewModelScope.launch {
            _needsRestart.value = true
            settingsRepository.setBitrate(bitrate)
        }
    }

    fun setCrossfade(crossfadeMillis: Int) {
        viewModelScope.launch {
            _needsRestart.value = true
            settingsRepository.setCrossfade(crossfadeMillis)
        }
    }

    fun setAutoTransfer(transfer: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAutoTransfer(transfer)
        }
    }

    fun setRomanizeLyrics(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setRomanizeLyrics(enabled)
        }
    }

    fun setLyricsFallbackEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setLyricsFallbackEnabled(enabled)
        }
    }

    fun setDeviceName(name: String) {
        viewModelScope.launch {
            _needsRestart.value = true
            settingsRepository.setDeviceName(name)
        }
    }

    fun setClientId(id: String) {
        viewModelScope.launch {
            settingsRepository.setClientId(id.ifBlank { null })
            _needsRestart.value = true
        }
    }

    fun setClientSecret(secret: String) {
        viewModelScope.launch {
            settingsRepository.setClientSecret(secret.ifBlank { null })
            _needsRestart.value = true
        }
    }

    /**
     * Rebuilds the spirc runtime so changed playback settings take effect.
     *
     * Waits for the runtime to come back so the button cannot be reported as
     * done while the rebuild is still running.
     */
    fun restartSpirc() {
        if (_isRestarting.value) return

        viewModelScope.launch {
            val id = settingsRepository.clientId.firstOrNull() ?: BuildConfig.SPOTIFY_CLIENT_ID
            val secret = settingsRepository.clientSecret.first() ?: BuildConfig.SPOTIFY_CLIENT_SECRET

            _isRestarting.value = true
            try {
                LibrespotFfi.updateClientCredentials(id, secret)

                if (spirc.restartAndAwaitReady("settings")) {
                    _needsRestart.value = false
                }
            } finally {
                _isRestarting.value = false
            }
        }
    }
}
