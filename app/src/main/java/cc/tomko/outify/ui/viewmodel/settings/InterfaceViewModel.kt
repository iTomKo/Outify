package cc.tomko.outify.ui.viewmodel.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.tomko.outify.data.repository.InterfaceSettings
import cc.tomko.outify.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class InterfaceViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    val settings: Flow<InterfaceSettings> =
        settingsRepository.interfaceSettings

    fun setShowNavbarHistory(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setShowNavbarHistory(enabled)
        }
    }

    fun setNavbarHistoryOnEnd(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setNavbarHistoryOnEnd(enabled)
        }
    }

    fun setShowSystemNavigationPadding(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setShowSystemNavigationPadding(enabled)
        }
    }
}