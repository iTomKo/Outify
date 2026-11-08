package cc.tomko.outify.ui.viewmodel.bottomsheet

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.tomko.outify.core.SpClient
import cc.tomko.outify.core.jam.JamClient
import cc.tomko.outify.core.model.Device
import cc.tomko.outify.core.model.DevicesResponse
import cc.tomko.outify.core.spirc.SpircWrapper
import cc.tomko.outify.ui.components.bottomsheet.jam.JamMemberUi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject

@HiltViewModel
class PlaybackDevicesViewModel @Inject constructor(
    private val spClient: SpClient,
    private val spircWrapper: SpircWrapper,
    private val json: Json,
    jamClient: JamClient,
) : ViewModel() {
    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices

    private val jamControls = PlaybackJamControls(jamClient, viewModelScope)
    val jam = jamControls.jam
    val jamAction = jamControls.action
    val jamError = jamControls.error
    val jamAvailable = spircWrapper.isUsableFlow

    fun refreshJam() = jamControls.refresh()
    fun startJam() = jamControls.start()
    fun leaveJam() = jamControls.leave()
    fun kickMember(member: JamMemberUi) = jamControls.kickMember(member.id)
    suspend fun inviteToJam(): String? = jamControls.invite()
    fun reportJamShareError(message: String) = jamControls.showError(message)

    fun selectDevice(device: Device) {
        val deviceId = device.id ?: return

        viewModelScope.launch {
            val previousState = _devices.value

            _devices.update { list ->
                list.map {
                    if (it.id == deviceId) it.copy(isActive = true)
                    else it.copy(isActive = false)
                }
            }

            val success = withContext(Dispatchers.IO) {
                spClient.transferPlaybackDevice(deviceId)
            }

            if (!success) {
                _devices.value = previousState
            }
        }
    }

    suspend fun makeActive() {
        spircWrapper.activate()
        spircWrapper.transfer()
    }

    suspend fun loadDevices() = withContext(Dispatchers.IO) {
        val raw = spClient.getDevices() ?: return@withContext
        try {
            val parsed = json.decodeFromString<DevicesResponse>(raw)
            _devices.value = parsed.devices
        } catch (e: Exception) {
            Log.e("PlaybackDevicesViewModel", "loadDevices: Failed to parse json", e)
        }
    }
}

enum class JamAction { Refreshing, Starting, Inviting, Leaving, Kicking }

/** Sheet actions outlive recomposition, but do not own or close the shared Jam listener. */
internal class PlaybackJamControls(
    private val client: JamClient,
    private val scope: CoroutineScope,
) {
    val jam = client.state
    private val _action = MutableStateFlow<JamAction?>(null)
    val action = _action.asStateFlow()
    private val actionError = MutableStateFlow<String?>(null)
    val error = combine(client.lastError, actionError) { native, local -> local ?: native }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), client.lastError.value)

    fun refresh() {
        scope.launch { perform(JamAction.Refreshing) { client.current() } }
    }

    fun start() {
        scope.launch { perform(JamAction.Starting) { client.start() } }
    }

    fun leave() {
        scope.launch { perform(JamAction.Leaving) { client.leave() } }
    }
    fun kickMember(id: String) {
        scope.launch { perform(JamAction.Kicking) { client.kick(id) } }
    }
    suspend fun invite(): String? = perform(JamAction.Inviting) { client.invite() }

    fun showError(message: String) {
        actionError.value = message
    }

    private suspend fun <T> perform(action: JamAction, block: suspend () -> T): T? {
        if (!_action.compareAndSet(null, action)) return null
        actionError.value = null
        return try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            actionError.value = error.message ?: error.javaClass.simpleName
            null
        } finally {
            _action.value = null
        }
    }
}
