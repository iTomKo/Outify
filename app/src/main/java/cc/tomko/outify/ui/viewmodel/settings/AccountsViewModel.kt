package cc.tomko.outify.ui.viewmodel.settings

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.tomko.outify.core.AuthCallbackServerManager
import cc.tomko.outify.core.AuthManager
import cc.tomko.outify.core.AuthStateEventBus
import cc.tomko.outify.core.SpClient
import cc.tomko.outify.core.UserProfile
import cc.tomko.outify.core.model.CurrentUserProfile
import cc.tomko.outify.core.spirc.SpircController
import cc.tomko.outify.data.metadata.NativeErrorHandler
import cc.tomko.outify.data.repository.SettingsRepository
import cc.tomko.outify.services.OAuthService
import cc.tomko.outify.ui.GlobalPopupController
import cc.tomko.outify.ui.PopupSpec
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject

@HiltViewModel
class AccountsViewModel @Inject constructor(
    val spClient: SpClient,
    val userProfile: UserProfile,
    val authManager: AuthManager,
    private val spircController: SpircController,
    private val settingsRepository: SettingsRepository,
    private val serverManager: AuthCallbackServerManager,
) : ViewModel() {
    private val json = Json { ignoreUnknownKeys = true }

    private val _isPlaybackLoggedIn = MutableStateFlow(false)
    val isPlaybackLoggedIn: StateFlow<Boolean> = _isPlaybackLoggedIn.asStateFlow()

    private val _isAccountLoggedIn = MutableStateFlow(false)
    val isAccountLoggedIn: StateFlow<Boolean> = _isAccountLoggedIn.asStateFlow()

    private val _username = MutableStateFlow<String?>(null)
    val username: StateFlow<String?> = _username.asStateFlow()

    private val _userImageUrl = MutableStateFlow<String?>(null)
    val userImageUrl: StateFlow<String?> = _userImageUrl.asStateFlow()

    private val _isPremium = MutableStateFlow(true)
    val isPremium: StateFlow<Boolean> = _isPremium.asStateFlow()

    private val _scopes = MutableStateFlow<List<String>>(emptyList())
    val scopes: StateFlow<List<String>> = _scopes.asStateFlow()

    init {
        checkAuthState()
        loadSavedUserProfile()
    }

    override fun onCleared() {
        super.onCleared()
        serverManager.stop()
    }

    fun checkAuthState() {
        _isAccountLoggedIn.value = spClient.isOAuthAuthenticated()
        _isPlaybackLoggedIn.value = authManager.hasCachedCredentials()

        _scopes.value = spClient.getOAuthScope()?.split(" ") ?: emptyList()
    }

    private fun loadSavedUserProfile() {
        viewModelScope.launch {
            _username.value = settingsRepository.username.first()
            _userImageUrl.value = settingsRepository.userImageUrl.first()
        }
    }

    fun startSpircAuth(context: Context) {
        OAuthService.start(context)

        serverManager.start(onCodeReceived = { code, state ->
            OAuthService.stop(context)
            val result = authManager.handleOAuthCode(code, state)
            val isSuccess = result.contains("\"success\":true")
            val errorDetails = if (!isSuccess) parseErrorMessage(result) else null
            if (!isSuccess) {
                NativeErrorHandler.handleErrorJson(result, "spirc oauth")
            }
            GlobalPopupController.show(PopupSpec.AuthResult(isSuccess, errorDetails = errorDetails))
            if (isSuccess) {
                _isPlaybackLoggedIn.value = authManager.hasCachedCredentials()
                checkAuthState()
                AuthStateEventBus.tryEmitPlaybackLoggedIn()
                spircController.restart()
            }
        })

        val url = authManager.getAuthorizationURL()

        context.startActivity(
            Intent(Intent.ACTION_VIEW, url.toUri())
        )
    }

    fun startAccountAuth(context: Context) {
        OAuthService.start(context)

        serverManager.start(onCodeReceived = { code, state ->
            OAuthService.stop(context)
            val result = spClient.completeOAuthFlow(code)
            val isSuccess = result.contains("\"success\":true")
            val errorDetails = if (!isSuccess) parseErrorMessage(result) else null
            if (!isSuccess) {
                NativeErrorHandler.handleErrorJson(result, "account oauth")
            }
            GlobalPopupController.show(PopupSpec.AuthResult(isSuccess, errorDetails = errorDetails))
            if (isSuccess) {
                // Restart the Web API client so it picks up the fresh credentials
                spClient.reset()
                viewModelScope.launch {
                    delay(100)
                    var authenticated = spClient.isOAuthAuthenticated()
                    if (!authenticated) {
                        delay(300)
                        authenticated = spClient.isOAuthAuthenticated()
                    }
                    _isAccountLoggedIn.value = authenticated
                    checkAuthState()
                    AuthStateEventBus.tryEmitAccountLoggedIn()
                    if (authenticated) {
                        fetchProfile()
                    }
                }
            }
        })

        val url = spClient.startOAuthFlow()

        context.startActivity(
            Intent(Intent.ACTION_VIEW, url.toUri())
        )
    }

    fun logoutPlayback() {
        authManager.logout()
        _isPlaybackLoggedIn.value = false
    }

    fun logoutAccount() {
        spClient.logout()
        _isAccountLoggedIn.value = false

        viewModelScope.launch {
            try {
                settingsRepository.removeUserProfile()
                _scopes.value = emptyList()
            } catch (e: Exception) {
            }
        }
    }

    fun fetchProfile() {
        viewModelScope.launch {
            try {
                val profile = spClient.getCurrentUserProfile()
                if (profile == null) {
                    return@launch
                }
                val jsonObject = json.decodeFromString<CurrentUserProfile>(profile)

                val id = jsonObject.id
                val username = jsonObject.displayName
                val imageUrl = jsonObject.images.first().url

                _isPremium.value = jsonObject.product == "premium"

                _username.value = username
                _userImageUrl.value = imageUrl

                settingsRepository.saveUserProfile(id, username, imageUrl)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun parseErrorMessage(json: String): String? {
        return try {
            val obj = org.json.JSONObject(json)
            if (obj.has("error")) {
                val err = obj.getJSONObject("error")
                err.optString("message", null)
            } else null
        } catch (e: Exception) {
            null
        }
    }
}