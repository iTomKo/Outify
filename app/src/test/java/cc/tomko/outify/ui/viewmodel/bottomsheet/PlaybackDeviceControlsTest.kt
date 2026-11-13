package cc.tomko.outify.ui.viewmodel.bottomsheet

import cc.tomko.outify.core.connect.ConnectDevicesClient
import cc.tomko.outify.core.model.Device
import cc.tomko.outify.core.model.DevicesResponse
import cc.tomko.outify.data.repository.CollectionSession
import cc.tomko.outify.data.repository.CollectionSessionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackDeviceControlsTest {
    private fun device(id: String, active: Boolean = false, restricted: Boolean = false) =
        Device(id, active, false, restricted, id, "Computer", 50, true)

    private class FakeClient(var response: DevicesResponse) : ConnectDevicesClient {
        var reads = 0
        val transfers = mutableListOf<Pair<String, CollectionSession>>()
        var read: suspend () -> DevicesResponse = { response }
        var transferAction: suspend () -> Unit = {}
        override suspend fun devices(): DevicesResponse { reads++; return read() }
        override suspend fun transfer(deviceId: String, expected: CollectionSession) {
            transfers += deviceId to expected
            transferAction()
        }
    }

    private inner class Fixture(scope: TestScope, ready: Boolean) {
        val sessions = CollectionSessionState()
        val lease = sessions.activate(CollectionSession("account", "session"))
        val available = MutableStateFlow(ready)
        val local = device("local")
        val remote = device("remote", active = true)
        val speaker = device("speaker")
        val client = FakeClient(DevicesResponse(listOf(local, remote, speaker), "local", "remote", false, lease.session))
        val controls = PlaybackDeviceControls(client, sessions, available, scope.backgroundScope)
        init { scope.backgroundScope.launch { controls.observe() } }
    }
    private fun TestScope.fixture(ready: Boolean = true) = Fixture(this, ready)

    @Test
    fun `discovery reads the existing roster without taking playback`() = runTest {
        val f = fixture()
        runCurrent()
        assertEquals(3, f.controls.state.value.devices.size)
        assertEquals("remote", f.controls.state.value.activeDeviceId)
        assertFalse(f.controls.state.value.isPlaying)
        assertTrue(f.client.transfers.isEmpty())
    }

    @Test
    fun `accepted transfers never optimistically select a device and duplicate taps are ignored`() = runTest {
        val f = fixture()
        runCurrent()
        val done = CompletableDeferred<Unit>()
        f.client.transferAction = { done.await() }
        f.controls.select(f.speaker, f.lease)
        f.controls.select(f.local, f.lease)
        runCurrent()
        assertEquals("speaker", f.controls.state.value.transferringDeviceId)
        assertEquals("remote", f.controls.state.value.devices.single { it.isActive }.id)
        assertEquals(listOf("speaker" to f.lease.session), f.client.transfers)
        done.complete(Unit)
        runCurrent()
        assertNull(f.controls.state.value.transferringDeviceId)
        assertEquals("remote", f.controls.state.value.devices.single { it.isActive }.id)
        f.client.response = f.client.response.copy(
            activeDeviceId = "speaker", devices = listOf(f.local, f.remote.copy(isActive = false), f.speaker.copy(isActive = true))
        )
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals("speaker", f.controls.state.value.devices.single { it.isActive }.id)
    }

    @Test
    fun `failed transfers keep newer server ownership instead of restoring an old list`() = runTest {
        val f = fixture()
        runCurrent()
        val done = CompletableDeferred<Unit>()
        f.client.transferAction = { done.await(); error("Transfer failed") }
        f.controls.select(f.speaker, f.lease)
        runCurrent()
        f.client.response = f.client.response.copy(
            activeDeviceId = "local", devices = listOf(f.local.copy(isActive = true), f.remote.copy(isActive = false), f.speaker)
        )
        advanceTimeBy(2_000)
        runCurrent()
        done.complete(Unit)
        runCurrent()
        assertEquals("local", f.controls.state.value.devices.single { it.isActive }.id)
        assertEquals("Transfer failed", f.controls.state.value.error)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals("Transfer failed", f.controls.state.value.error)
    }

    @Test
    fun `active restricted missing and null-id devices cannot be selected`() = runTest {
        val f = fixture()
        f.client.response = f.client.response.copy(devices = listOf(f.local, f.remote, f.speaker.copy(isRestricted = true)))
        runCurrent()
        listOf(f.remote, f.speaker, device("missing"), f.local.copy(id = null)).forEach { f.controls.select(it, f.lease) }
        runCurrent()
        assertTrue(f.client.transfers.isEmpty())
    }

    @Test
    fun `takeover selects the local Connect device without a separate activation step`() = runTest {
        val f = fixture()
        runCurrent()
        f.controls.takeOver(f.lease)
        assertEquals(listOf("local" to f.lease.session), f.client.transfers)
        assertEquals("remote", f.controls.state.value.activeDeviceId)
    }

    @Test
    fun `transferring away and back keeps device controls usable on the same login lease`() = runTest {
        val f = fixture()
        runCurrent()
        f.client.transferAction = {
            val target = f.client.transfers.last().first
            f.client.response = f.client.response.copy(
                activeDeviceId = target,
                devices = f.client.response.devices.map { it.copy(isActive = it.id == target) },
            )
        }
        f.controls.select(f.speaker, f.lease)
        runCurrent()
        assertEquals("speaker", f.controls.state.value.activeDeviceId)
        assertNull(f.controls.state.value.error)
        f.controls.takeOver(f.lease)
        assertEquals("local", f.controls.state.value.activeDeviceId)
        assertNull(f.controls.state.value.error)
        f.controls.select(f.remote, f.lease)
        runCurrent()
        assertEquals("remote", f.controls.state.value.activeDeviceId)
        assertEquals(listOf("speaker", "local", "remote"), f.client.transfers.map { it.first })
        assertTrue(f.client.transfers.all { it.second == f.lease.session })
        assertEquals(f.lease, f.sessions.current.value)
    }

    @Test
    fun `unavailable playback and logout clear the roster and block requests`() = runTest {
        val f = fixture(ready = false)
        runCurrent()
        assertEquals(0, f.client.reads)
        f.available.value = true
        runCurrent()
        assertEquals(3, f.controls.state.value.devices.size)
        f.sessions.invalidate()
        runCurrent()
        f.controls.select(f.speaker, f.lease)
        runCurrent()
        assertTrue(f.controls.state.value.devices.isEmpty())
        assertFalse(f.controls.state.value.available)
        assertTrue(f.client.transfers.isEmpty())
    }

    @Test
    fun `account changes clear visible devices before an uncancellable JNI read finishes`() = runTest {
        val f = fixture()
        runCurrent()
        val oldResponse = f.client.response
        val late = CompletableDeferred<DevicesResponse>()
        var blockNext = true
        f.client.read = { if (blockNext) { blockNext = false; withContext(NonCancellable) { late.await() } } else f.client.response }
        backgroundScope.launch { f.controls.refresh() }
        runCurrent()
        val next = f.sessions.activate(CollectionSession("other", "replacement"))
        f.client.response = oldResponse.copy(session = next.session, devices = listOf(device("new")))
        runCurrent()
        assertTrue(f.controls.state.value.devices.isEmpty())
        f.controls.select(f.speaker, f.lease)
        runCurrent()
        assertTrue(f.client.transfers.isEmpty())
        late.complete(oldResponse)
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(listOf("new"), f.controls.state.value.devices.map { it.id })
    }

    @Test
    fun `foreign or failed snapshots are errors not an idle-device decision`() = runTest {
        val f = fixture()
        f.client.response = f.client.response.copy(session = CollectionSession("other", "session"))
        runCurrent()
        assertTrue(f.controls.state.value.devices.isEmpty())
        assertNotNull(f.controls.state.value.error)
        f.client.read = { error("Connect disconnected") }
        f.controls.refresh()
        assertEquals("Connect disconnected", f.controls.state.value.error)
        assertNull(f.controls.state.value.activeDeviceId)
    }

    @Test
    fun `native roster JSON keeps account identity and active hidden ownership`() {
        val response = Json.decodeFromString<DevicesResponse>("""{
            "devices":[], "local_device_id":"local", "active_device_id":"hidden",
            "is_playing":false, "session":{"accountId":"account","sessionId":"session"}
        }""")
        assertEquals("hidden", response.activeDeviceId)
        assertEquals(CollectionSession("account", "session"), response.session)
        assertFalse(response.isPlaying)
    }
}
