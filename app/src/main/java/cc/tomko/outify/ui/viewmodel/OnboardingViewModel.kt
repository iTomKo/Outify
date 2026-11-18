package cc.tomko.outify.ui.viewmodel

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.tomko.outify.core.AuthCallbackServerManager
import cc.tomko.outify.core.AuthManager
import cc.tomko.outify.core.AuthStateEvent
import cc.tomko.outify.core.AuthStateEventBus
import cc.tomko.outify.core.SpClient
import cc.tomko.outify.core.model.CurrentUserProfile
import cc.tomko.outify.core.spirc.SpircController
import cc.tomko.outify.data.repository.SettingsRepository
import cc.tomko.outify.services.OAuthService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val authManager: AuthManager,
    private val spClient: SpClient,
    private val spircController: SpircController,
    private val settingsRepository: SettingsRepository,
    private val serverManager: AuthCallbackServerManager,
) : ViewModel() {
    private val json = Json { ignoreUnknownKeys = true }

    private val _isPlaybackLoggedIn = MutableStateFlow(false)
    val isPlaybackLoggedIn: StateFlow<Boolean> = _isPlaybackLoggedIn.asStateFlow()

    private val _isComplete = MutableStateFlow(false)
    val isComplete: StateFlow<Boolean> = _isComplete.asStateFlow()

    init {
        refreshState()
        viewModelScope.launch {
            AuthStateEventBus.events.collect { event ->
                when (event) {
                    is AuthStateEvent.PlaybackLoggedOut -> refreshState()
                    is AuthStateEvent.PlaybackLoggedIn -> refreshState()
                    else -> Unit
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        serverManager.stop()
    }

    fun refreshState() {
        viewModelScope.launch {
            val playback = withContext(Dispatchers.IO) { authManager.hasCachedCredentials() }
            _isPlaybackLoggedIn.value = playback
            _isComplete.value = playback;
        }
    }

    fun startPlaybackAuth(context: Context) {
        OAuthService.start(context)

        serverManager.start(onCodeReceived = { code, state ->
            OAuthService.stop(context)
            val result = authManager.handleOAuthCode(code, state)
            val isSuccess = result.contains("\"success\":true")
            if (isSuccess) {
                refreshState()
                viewModelScope.launch { AuthStateEventBus.emitPlaybackLoggedIn() }
                spircController.restart()
                fetchProfile()
            }
        })

        val url = authManager.getAuthorizationURL()
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    }

    private fun fetchProfile() {
        viewModelScope.launch {
            try {
                val profile = withContext(Dispatchers.IO) { spClient.getCurrentUserProfile() } ?: return@launch
                val jsonObject = json.decodeFromString<CurrentUserProfile>(profile)
                settingsRepository.saveUserProfile(
                    jsonObject.username,
                    jsonObject.displayName,
                    jsonObject.images.firstOrNull()?.url,
                )
            } catch (_: Exception) {
            }
        }
    }
}
