package cc.tomko.outify.ui.viewmodel.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.tomko.outify.core.SpClient
import cc.tomko.outify.core.spirc.SpircWrapper
import cc.tomko.outify.core.UserProfile
import cc.tomko.outify.core.model.Profile
import cc.tomko.outify.data.dao.LikedDao
import cc.tomko.outify.data.metadata.Metadata
import cc.tomko.outify.playback.PlaybackStateHolder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject

private const val PROFILE_STATE_KEY = "profile_state"

sealed class ProfileUiState {
    object Loading : ProfileUiState()
    data class Success(
        val profile: Profile? = null,
        val isFollowing: Boolean = false,
        val followersCount: Int = 0,
        val followingCount: Int = 0
    ) : ProfileUiState()

    data class Error(val message: String) : ProfileUiState()
}

@HiltViewModel
class ProfileDetailViewModel @Inject constructor(
    private val metadata: Metadata,
    private val playbackStateHolder: PlaybackStateHolder,
    val spirc: SpircWrapper,
    val spClient: SpClient,
    val json: Json,
    val likedDao: LikedDao,
    val userProfile: UserProfile,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val _uiState = MutableStateFlow<ProfileUiState>(ProfileUiState.Loading)
    val uiState: StateFlow<ProfileUiState> = _uiState

    private var _lastProfileUri: String? = null

    fun retry() {
        val uri = _lastProfileUri ?: return
        viewModelScope.launch {
            spirc.restart()
            _uiState.value = ProfileUiState.Loading
            loadProfile(uri)
        }
    }

    private fun saveState(state: ProfileUiState) {
        savedStateHandle[PROFILE_STATE_KEY] = state.toString()
    }

    fun toggleFollow() {
        val currentState = _uiState.value
        if (currentState is ProfileUiState.Success) {
            val uri = currentState.profile?.uri ?: return
            _uiState.value = currentState.copy(isFollowing = !currentState.isFollowing)

            if (currentState.isFollowing) {
                if (!spClient.saveItems(arrayOf(uri))) {
                    _uiState.value = currentState.copy(isFollowing = false)
                }
            } else {
                if (!spClient.deleteItems(arrayOf(uri))) {
                    _uiState.value = currentState.copy(isFollowing = true)
                }
            }
        }
    }

    suspend fun loadProfile(profileUri: String) {
        try {
            val username = profileUri.removePrefix("spotify:user:")

            val raw = userProfile.getUserProfile(username)
            if (raw == null) {
                _uiState.value = ProfileUiState.Error("Profile not found")
                return
            }

            val profile = json.decodeFromString<Profile>(raw)

            _uiState.value = ProfileUiState.Success(
                profile = profile,
                isFollowing = profile.isFollowing,
                followersCount = profile.followersCount ?: 0,
                followingCount = profile.followingCount ?: 0
            )
        } catch (e: Exception) {
            e.printStackTrace()
            _uiState.value = ProfileUiState.Error("Profile not found")
        }
    }
}