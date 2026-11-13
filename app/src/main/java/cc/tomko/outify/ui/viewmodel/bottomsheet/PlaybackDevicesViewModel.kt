package cc.tomko.outify.ui.viewmodel.bottomsheet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.tomko.outify.core.connect.NativeConnectDevicesClient
import cc.tomko.outify.core.connect.ConnectTarget
import cc.tomko.outify.core.connect.ConnectCommand
import cc.tomko.outify.core.jam.JamClient
import cc.tomko.outify.core.model.Device
import cc.tomko.outify.core.spirc.SpircWrapper
import cc.tomko.outify.data.repository.CollectionLease
import cc.tomko.outify.data.repository.CollectionSessionState
import cc.tomko.outify.ui.components.bottomsheet.jam.JamMemberUi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlaybackDevicesViewModel @Inject constructor(
    deviceClient: NativeConnectDevicesClient,
    private val spircWrapper: SpircWrapper,
    sessions: CollectionSessionState,
    jamClient: JamClient,
) : ViewModel() {
    private val deviceControls = PlaybackDeviceControls(deviceClient, sessions, spircWrapper.isUsableFlow, viewModelScope)
    val deviceState = deviceControls.state
    val playback = spircWrapper.connectPlayback.snapshot
    val controlError = spircWrapper.connectPlayback.error

    fun setRemoteVolume(target: ConnectTarget, volume: Int) {
        spircWrapper.submitRemote(ConnectCommand(target, "volume", volume.toLong()))
    }

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

    fun selectDevice(device: Device, expectedLease: CollectionLease?) = deviceControls.select(device, expectedLease)
    suspend fun makeActive(expectedLease: CollectionLease?) = deviceControls.takeOver(expectedLease)
    suspend fun loadDevices() = deviceControls.refresh()
    suspend fun observeDevices() = deviceControls.observe()
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
