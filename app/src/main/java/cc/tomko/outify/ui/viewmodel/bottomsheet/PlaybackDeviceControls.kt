package cc.tomko.outify.ui.viewmodel.bottomsheet

import cc.tomko.outify.core.connect.ConnectDevicesClient
import cc.tomko.outify.core.model.Device
import cc.tomko.outify.data.repository.CollectionLease
import cc.tomko.outify.data.repository.CollectionSessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

data class PlaybackDeviceState(
    val devices: List<Device> = emptyList(),
    val lease: CollectionLease? = null,
    val available: Boolean = false,
    val localDeviceId: String? = null,
    val activeDeviceId: String? = null,
    val isPlaying: Boolean = false,
    val isRefreshing: Boolean = false,
    val transferringDeviceId: String? = null,
    val loadError: String? = null,
    val transferError: String? = null,
) {
    val error: String? get() = transferError ?: loadError
}

/** The sheet observes the existing native cache; polling never registers or publishes a device. */
internal class PlaybackDeviceControls(
    private val client: ConnectDevicesClient,
    private val sessions: CollectionSessionState,
    private val available: StateFlow<Boolean>,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(PlaybackDeviceState())
    val state = _state.asStateFlow()
    private val refreshLock = Mutex()

    /** Runs only while the device sheet is composed. Native Dealer updates maintain the cache. */
    suspend fun observe() {
        combine(sessions.current, available) { lease, ready -> lease.takeIf { ready } }
            .onEach { lease ->
                // Clear before collectLatest waits for a cancelled blocking JNI call to return.
                _state.value = PlaybackDeviceState(lease = lease, available = lease != null, isRefreshing = lease != null)
            }
            .collectLatest { lease ->
                if (lease == null) return@collectLatest
                while (currentCoroutineContext().isActive) {
                    refresh(showLoading = _state.value.devices.isEmpty())
                    delay(2_000)
                }
            }
    }

    private fun current(lease: CollectionLease) =
        sessions.current.value == lease && available.value && _state.value.lease == lease

    suspend fun refresh(showLoading: Boolean = true) {
        val lease = _state.value.lease ?: return
        if (!current(lease) || !refreshLock.tryLock()) return
        if (showLoading) _state.update { it.copy(isRefreshing = true) }
        try {
            val response = client.devices()
            currentCoroutineContext().ensureActive()
            if (!current(lease)) return
            check(response.session == lease.session) { "Connect device snapshot belongs to a different playback session" }
            check(!response.localDeviceId.isNullOrBlank()) { "Connect device snapshot has no local device ID" }
            _state.update { it.copy(
                devices = response.devices,
                localDeviceId = response.localDeviceId,
                activeDeviceId = response.activeDeviceId,
                isPlaying = response.isPlaying,
                loadError = null,
            ) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            if (current(lease)) _state.update { it.copy(
                devices = emptyList(), localDeviceId = null, activeDeviceId = null,
                loadError = error.message ?: error.javaClass.simpleName,
            ) }
        } finally {
            if (current(lease)) _state.update { it.copy(isRefreshing = false) }
            refreshLock.unlock()
        }
    }

    fun select(device: Device, expectedLease: CollectionLease?) {
        val id = device.id ?: return
        scope.launch { transfer(id, expectedLease) }
    }

    suspend fun takeOver(expectedLease: CollectionLease?) {
        val lease = expectedLease ?: return
        if (!current(lease)) return
        val id = _state.value.localDeviceId ?: return
        transfer(id, lease)
    }

    private suspend fun transfer(deviceId: String, expectedLease: CollectionLease?) {
        val lease = expectedLease ?: return
        if (!current(lease) || _state.value.transferringDeviceId != null) return
        val target = _state.value.devices.firstOrNull { it.id == deviceId } ?: return
        if (target.isRestricted || target.isActive) return
        _state.update { it.copy(transferringDeviceId = deviceId, transferError = null) }
        try {
            client.transfer(deviceId, lease.session)
            currentCoroutineContext().ensureActive()
            if (!current(lease)) return
            // HTTP acceptance is not playback ownership. Only a cluster snapshot can select a row.
            refresh(showLoading = false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            if (current(lease)) _state.update { it.copy(transferError = error.message ?: error.javaClass.simpleName) }
        } finally {
            if (current(lease)) _state.update { it.copy(transferringDeviceId = null) }
        }
    }
}
