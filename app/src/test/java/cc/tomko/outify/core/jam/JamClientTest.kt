package cc.tomko.outify.core.jam

import cc.tomko.outify.ui.components.bottomsheet.jam.JamListenTarget
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class JamClientTest {
    private val backend = FakeBackend()
    private val client = JamClient(Json { ignoreUnknownKeys = true }, backend)

    @Test
    fun `initialization registers once and dealer updates populate state`() {
        client.initialize()
        client.initialize()
        assertEquals(1, backend.registrations)
        backend.listener!!.onChanged(OWNED)
        val jam = client.state.value!!
        assertTrue(jam.owner)
        assertEquals(LINK, jam.link)
        assertEquals("Me", jam.members.single().name)
        assertTrue(jam.members.single().you)

        backend.listener!!.onChanged("null")
        assertNull(client.state.value)
    }

    @Test
    fun `start current and leave expose native operation results`() = runTest {
        backend.startResult = OWNED
        assertTrue(client.start().owner)
        assertEquals(listOf("start"), backend.operations)
        assertNull(client.current())
        client.leave()
        assertEquals(listOf("start", "current", "leave"), backend.operations)
    }

    @Test
    fun `info returns the invited Jam without replacing active membership`() = runTest {
        client.initialize()
        backend.listener!!.onChanged(OWNED)
        backend.infoResult = """{"members":[{"id":"host","name":"Host","host":true}]}"""

        val preview = client.info("AbC123")!!

        assertEquals("AbC123", backend.infoToken)
        assertEquals("Host", preview.members.single().name)
        assertFalse(preview.owner)
        assertTrue(client.state.value!!.owner)
        assertEquals(listOf("info"), backend.operations)
    }

    @Test
    fun `info and join return null for empty native results`() = runTest {
        assertNull(client.info("AbC123"))
        assertTrue(client.lastError.value!!.contains("invitation"))
        assertNull(client.join("AbC123", JamListenTarget.ThisDevice))
        assertTrue(client.lastError.value!!.contains("join"))
        assertNull(client.state.value)
    }

    @Test
    fun `info and join return null on native failures without changing membership`() = runTest {
        client.initialize()
        backend.listener!!.onChanged(OWNED)
        backend.failure = "Invitation unavailable"

        assertNull(client.info("AbC123"))
        assertEquals("Invitation unavailable", client.lastError.value)
        assertNull(client.join("AbC123", JamListenTarget.ThisDevice))
        assertEquals("Invitation unavailable", client.lastError.value)
        assertTrue(client.state.value!!.owner)
    }

    @Test
    fun `info and join handle rejected requests malformed responses and unavailable initialization`() = runTest {
        backend.accept = false
        assertNull(client.info("AbC123"))
        assertNull(client.join("AbC123", JamListenTarget.ThisDevice))
        assertTrue(client.lastError.value!!.contains("rejected"))

        backend.accept = true
        backend.infoResult = "garbled"
        backend.joinResult = "garbled"
        assertNull(client.info("AbC123"))
        assertNull(client.join("AbC123", JamListenTarget.ThisDevice))
        assertTrue(client.lastError.value!!.contains("decode"))

        client.close()
        backend.register = false
        assertNull(client.info("AbC123"))
        assertNull(client.join("AbC123", JamListenTarget.ThisDevice))
        assertTrue(client.lastError.value!!.contains("register"))
    }

    @Test
    fun `join returns the native Jam on success`() = runTest {
        backend.joinResult = OWNED
        assertTrue(client.join("AbC123", JamListenTarget.ThisDevice)!!.owner)
        assertEquals(listOf("join"), backend.operations)
    }

    @Test
    fun `info and join preserve coroutine cancellation`() = runTest {
        backend.defer = true
        val info = async { client.info("AbC123") }
        runCurrent()
        val infoCallback = backend.pending!!
        info.cancelAndJoin()
        assertTrue(info.isCancelled)
        infoCallback.onFailure("late info failure")

        val join = async { client.join("AbC123", JamListenTarget.ThisDevice) }
        runCurrent()
        val joinCallback = backend.pending!!
        join.cancelAndJoin()
        assertTrue(join.isCancelled)
        joinCallback.onSuccess(OWNED)

        assertNull(client.state.value)
        assertNull(client.lastError.value)
    }

    @Test
    fun `invite creates a Jam if needed and returns its shareable link`() = runTest {
        backend.startResult = OWNED
        assertEquals(LINK, client.invite())
        assertEquals(listOf("current", "start"), backend.operations)
    }

    @Test
    fun `invite reuses an owned Jam without starting another`() = runTest {
        backend.currentResult = OWNED
        assertEquals(LINK, client.invite())
        assertEquals(listOf("current"), backend.operations)
    }

    @Test
    fun `guests and owners without an invite link get actionable failures`() = runTest {
        for (response in listOf("{}", """{"owner":true}""")) {
            backend.currentResult = response
            try {
                client.invite()
                fail("Expected JamException")
            } catch (error: JamException) {
                assertEquals(error.message, client.lastError.value)
            }
        }
        assertFalse(backend.operations.contains("start"))
    }

    @Test
    fun `failures are separate from membership and successful requests cannot restore stale state`() = runTest {
        client.initialize()
        backend.listener!!.onChanged(OWNED)
        backend.failure = "Spotify is unavailable"
        try {
            client.current()
            fail("Expected JamException")
        } catch (error: JamException) {
            assertEquals("Spotify is unavailable", error.message)
        }
        assertTrue(client.state.value!!.owner)

        backend.failure = null
        backend.currentResult = OWNED
        backend.listener!!.onChanged("null") // teardown precedes request completion
        assertTrue(client.current()!!.owner)
        assertNull(client.state.value) // only listener notifications update state
    }

    @Test
    fun `malformed payloads do not throw through JNI callbacks`() = runTest {
        client.initialize()
        backend.listener!!.onChanged(OWNED)
        backend.listener!!.onChanged("garbled")
        assertTrue(client.state.value!!.owner)
        assertTrue(client.lastError.value!!.contains("decode"))
        backend.currentResult = "garbled"
        try {
            client.current()
            fail("Expected JamException")
        } catch (_: JamException) {
            // Decode errors are delivered to the suspended caller, not JNI.
        }
    }

    @Test
    fun `close ignores queued events from the old listener and reinitialization replays state`() {
        client.initialize()
        val old = backend.listener!!
        old.onChanged(OWNED)
        client.close()
        assertNull(client.state.value)
        assertEquals(1, backend.unregistrations)
        client.initialize()
        old.onChanged(OWNED)
        old.onError("stale error")
        assertNull(client.state.value)
        assertNull(client.lastError.value)
        backend.listener!!.onChanged(OWNED)
        assertTrue(client.state.value!!.owner)
    }

    @Test
    fun `cancelled callers safely ignore a delayed native callback`() = runTest {
        backend.defer = true
        val pending = async { client.start() }
        runCurrent()
        pending.cancelAndJoin()
        backend.pending!!.onSuccess(OWNED)
        assertNull(client.state.value)
    }

    @Test
    fun `native rejections fail instead of leaving a suspended caller`() = runTest {
        backend.accept = false
        try {
            client.current()
            fail("Expected JamException")
        } catch (error: JamException) {
            assertTrue(error.message!!.contains("rejected"))
        }
    }

    @Test
    fun `failed listener registration can be retried`() {
        backend.register = false
        try {
            client.initialize()
            fail("Expected JamException")
        } catch (_: JamException) {
            backend.register = true
        }
        client.initialize()
        assertEquals(2, backend.registrations)
    }

    private class FakeBackend : JamBackend {
        var listener: JamCallback? = null
        var registrations = 0
        var unregistrations = 0
        var register = true
        var accept = true
        var defer = false
        var pending: JamRequestCallback? = null
        var startResult = "null"
        var currentResult = "null"
        var infoResult = "null"
        var joinResult = "null"
        var infoToken: String? = null
        var failure: String? = null
        val operations = mutableListOf<String>()

        override fun registerListener(callback: JamCallback): Boolean {
            registrations++
            if (register) {
                listener = callback
                callback.onChanged("null") // native state replay
            }
            return register
        }

        override fun unregisterListener() {
            unregistrations++
            listener = null
        }

        override fun start(callback: JamRequestCallback) = reply("start", startResult, callback)
        override fun current(callback: JamRequestCallback) = reply("current", currentResult, callback)
        override fun leave(callback: JamRequestCallback) = reply("leave", "null", callback)
        override fun kick(id: String, callback: JamRequestCallback) = reply("kick", "null", callback)
        override fun join(token: String, joinModeRust: String, callback: JamRequestCallback) =
            reply("join", joinResult, callback)
        override fun info(token: String, callback: JamRequestCallback): Boolean {
            infoToken = token
            return reply("info", infoResult, callback)
        }

        private fun reply(operation: String, result: String, callback: JamRequestCallback): Boolean {
            operations += operation
            if (!accept) return false
            if (defer) pending = callback
            else failure?.let(callback::onFailure) ?: callback.onSuccess(result)
            return true
        }
    }

    companion object {
        private const val LINK = "https://open.spotify.com/socialsession/Tok9"
        private const val OWNED = """{"owner":true,"link":"https://open.spotify.com/socialsession/Tok9","members":[{"id":"me","name":"Me","image":null,"host":true,"you":true}]}"""
    }
}
