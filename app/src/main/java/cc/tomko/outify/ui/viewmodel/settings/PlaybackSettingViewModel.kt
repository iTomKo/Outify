package cc.tomko.outify.ui.viewmodel.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import kotlinx.coroutines.launch

@HiltViewModel
class PlaybackSettingViewModel @Inject constructor(
    val settingsRepository: SettingsRepository,
    val spirc: SpircController
) : ViewModel() {
    private val _needsRestart = MutableStateFlow(false)
    val needsRestart: StateFlow<Boolean> = _needsRestart

    val settings: Flow<PlaybackSettings> =
        settingsRepository.playbackSettings

    val romanizeLyrics: Flow<Boolean> =
        settingsRepository.romanizeLyrics

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

    fun restartSpirc() {
        viewModelScope.launch {
            val id = settingsRepository.clientId.first()
            val secret = settingsRepository.clientSecret.first()
            if (id != null && secret != null) {
                LibrespotFfi.updateClientCredentials(id, secret)
            } else {
                LibrespotFfi.updateClientCredentials(
                    "819a62c83de24821b2654387bc84f136",
                    "6db424c706d34cf7810a5c8c59324182"
                )
            }
            spirc.restart()
            _needsRestart.value = false
        }
    }
}
