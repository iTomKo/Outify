package cc.tomko.outify.ui.viewmodel.library

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.tomko.outify.core.SpClient
import cc.tomko.outify.core.UserProfile
import cc.tomko.outify.core.model.Profile
import cc.tomko.outify.core.spirc.SpircController
import cc.tomko.outify.core.spirc.SpircWrapper
import cc.tomko.outify.data.dao.LikedDao
import cc.tomko.outify.data.metadata.Metadata
import cc.tomko.outify.data.repository.CollectionSessionState
import cc.tomko.outify.playback.PlaybackStateHolder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
    private val spircController: SpircController,
    val spClient: SpClient,
    val json: Json,
    val likedDao: LikedDao,
    val userProfile: UserProfile,
    private val savedStateHandle: SavedStateHandle,
    private val collectionSessions: CollectionSessionState,
) : ViewModel() {
    val currentUserId = collectionSessions.current.map { it?.session?.accountId }
    private val followMutex = Mutex()
    private val _uiState = MutableStateFlow<ProfileUiState>(ProfileUiState.Loading)
    val uiState: StateFlow<ProfileUiState> = _uiState

    private var _lastProfileUri: String? = null

    fun retry() {
        val uri = _lastProfileUri ?: return
        viewModelScope.launch {
            spircController.restartAndAwaitReady("profile retry")
            _uiState.value = ProfileUiState.Loading
            loadProfile(uri)
        }
    }

    private fun saveState(state: ProfileUiState) {
        savedStateHandle[PROFILE_STATE_KEY] = state.toString()
    }

    fun toggleFollow() {
        viewModelScope.launch {
            followMutex.withLock {
                val currentState = _uiState.value as? ProfileUiState.Success ?: return@withLock
                val uri = currentState.profile?.uri ?: return@withLock
                val lease = collectionSessions.requireCurrent()
                val success = withContext(Dispatchers.IO) {
                    if (currentState.isFollowing) {
                        spClient.deleteItems(arrayOf(uri))
                    } else {
                        spClient.saveItems(arrayOf(uri))
                    }
                }
                collectionSessions.ensureCurrent(lease)
                if (success && (_uiState.value as? ProfileUiState.Success)?.profile?.uri == uri) {
                    _uiState.value = currentState.copy(isFollowing = !currentState.isFollowing)
                } else if (!success) {
                    Log.w("ProfileDetailViewModel", "Could not update user follow state")
                }
            }
        }
    }

    suspend fun loadProfile(profileUri: String) {
        _lastProfileUri = profileUri
        try {
            val username = profileUri.removePrefix("spotify:user:")
            val lease = collectionSessions.requireCurrent()

            val raw = withContext(Dispatchers.IO) { userProfile.getUserProfile(username) }
            collectionSessions.ensureCurrent(lease)
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
            viewModelScope.launch {
                try {
                    followMutex.withLock {
                        collectionSessions.ensureCurrent(lease)
                        val following = withContext(Dispatchers.IO) { spClient.isFollowingUser(profile.uri) }
                        collectionSessions.ensureCurrent(lease)
                        val current = _uiState.value as? ProfileUiState.Success
                        if (current?.profile?.uri == profile.uri) {
                            _uiState.value = current.copy(isFollowing = following)
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w("ProfileDetailViewModel", "Could not refresh user follow state", error)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            e.printStackTrace()
            _uiState.value = ProfileUiState.Error("Profile not found")
        }
    }
}
