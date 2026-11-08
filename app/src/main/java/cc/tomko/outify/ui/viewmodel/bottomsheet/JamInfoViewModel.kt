package cc.tomko.outify.ui.viewmodel.bottomsheet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.tomko.outify.core.UserProfile
import cc.tomko.outify.core.jam.Jam
import cc.tomko.outify.core.jam.JamClient
import cc.tomko.outify.core.jam.JamException
import cc.tomko.outify.core.jam.JamMember
import cc.tomko.outify.core.model.Profile
import cc.tomko.outify.core.spirc.SpircWrapper
import cc.tomko.outify.ui.components.bottomsheet.jam.JamListenTarget
import cc.tomko.outify.ui.components.bottomsheet.jam.JamUser
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

data class JamInfoUiState(
    val token: String? = null,
    val isLoading: Boolean = false,
    val isJoining: Boolean = false,
    val hasJoined: Boolean = false,
    val jam: Jam? = null,
    val currentUser: JamUser? = null,
    val host: JamUser? = null,
    val error: String? = null,
    val joinError: String? = null,
)

@HiltViewModel
class JamInfoViewModel internal constructor(
    private val fetchInfo: suspend (String) -> Jam?,
    private val joinJam: suspend (String, JamListenTarget) -> Jam?,
    private val fetchCurrentUser: suspend () -> JamUser,
    private val available: StateFlow<Boolean>,
    private val requestError: () -> String? = { null },
) : ViewModel() {
    @Inject
    constructor(
        jamClient: JamClient,
        spircWrapper: SpircWrapper,
        userProfile: UserProfile,
        json: Json,
    ) : this(
        fetchInfo = jamClient::info,
        joinJam = jamClient::join,
        fetchCurrentUser = {
            withContext(Dispatchers.IO) {
                val raw = userProfile.getUserProfile()
                val profile = raw?.let { json.decodeFromString<Profile>(it) }
                JamUser(
                    name = profile?.name?.takeIf { it.isNotBlank() }
                        ?: profile?.uri?.substringAfterLast(':')?.takeIf { it.isNotBlank() }
                        ?: "You",
                    avatarUrl = profile?.imageUrl?.takeIf { it.isNotBlank() },
                )
            }
        },
        available = spircWrapper.isUsableFlow,
        requestError = { jamClient.lastError.value },
    )

    private val _uiState = MutableStateFlow(JamInfoUiState())
    val uiState: StateFlow<JamInfoUiState> = _uiState.asStateFlow()
    private var loadJob: Job? = null

    /** Recomposition must not restart an in-flight or completed preview. */
    fun load(token: String) {
        if (_uiState.value.token == token &&
            (loadJob?.isActive == true || _uiState.value.jam != null)
        ) return
        loadInvitation(token)
    }

    suspend fun join(token: String, joinMode: JamListenTarget): Boolean {
        val state = _uiState.value
        if (state.token != token || state.jam == null || state.isJoining || state.hasJoined) return false
        if (!_uiState.compareAndSet(state, state.copy(isJoining = true, joinError = null))) return false
        return try {
            val joined = joinJam(token, joinMode)
            currentCoroutineContext().ensureActive()
            if (joined == null) {
                _uiState.update { it.copy(joinError = requestError() ?: "Cannot join the Jam") }
                false
            } else {
                _uiState.update { it.copy(jam = joined, hasJoined = true) }
                true
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _uiState.update { it.copy(joinError = error.message ?: "Cannot join the Jam") }
            false
        } finally {
            _uiState.update { it.copy(isJoining = false) }
        }
    }

    fun retry() {
        _uiState.value.token?.let(::loadInvitation)
    }

    private fun loadInvitation(token: String) {
        loadJob?.cancel()
        _uiState.value = JamInfoUiState(token = token, isLoading = true)
        if (token.isEmpty() || token.any { !it.isAsciiTokenCharacter() }) {
            _uiState.value = JamInfoUiState(token = token, error = "Invalid Jam invitation token")
            return
        }
        loadJob = viewModelScope.launch {
            try {
                withTimeoutOrNull(30_000.milliseconds) { available.first { it } }
                    ?: throw JamException("Spotify is not ready. Connect and try again.")
                val jam = fetchInfo(token)
                    ?: throw JamException(requestError() ?: "Cannot load the Jam invitation")
                val host = jam.members.firstOrNull { it.host }
                    ?: throw JamException("The invited Jam has no host information")
                val currentUser = jam.members.firstOrNull { it.you }?.toUser()
                    ?: loadCurrentUser()
                ensureActive()
                _uiState.value = JamInfoUiState(
                    token = token,
                    jam = jam,
                    currentUser = currentUser,
                    host = host.toUser(),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                ensureActive()
                _uiState.value = JamInfoUiState(
                    token = token,
                    error = error.message ?: "Cannot load the Jam invitation",
                )
            }
        }
    }

    private suspend fun loadCurrentUser(): JamUser = try {
        fetchCurrentUser()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // An unavailable avatar/profile must not prevent previewing an invitation.
        JamUser(name = "You", avatarUrl = null)
    }

    private fun JamMember.toUser() = JamUser(
        name = name.ifBlank { id },
        avatarUrl = image?.takeIf { it.isNotBlank() },
    )

    private fun Char.isAsciiTokenCharacter(): Boolean =
        this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this == '_' || this == '-'
}
