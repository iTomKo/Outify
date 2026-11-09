package cc.tomko.outify.data.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CollectionSyncCoordinatorTest {
    private val uri = "spotify:track:0000000000000000000001"

    @Test
    fun `delta bursts preserve order without fetching the collection`() = runTest {
        var fullReads = 0
        val applied = mutableListOf<List<CollectionTrackChange>>()
        val sync = CollectionSyncCoordinator(backgroundScope, { fullReads++ }, { throw it }, { applied += it })
        sync.onDelta("""[{"uri":"$uri","removed":false}]""")
        sync.onDelta("""[{"uri":"$uri","removed":true}]""")
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        assertEquals(0, fullReads)
        assertEquals(listOf(false, true), applied.single().map { it.removed })
    }

    @Test
    fun `startup refresh supersedes queued deltas`() = runTest {
        var fullReads = 0
        val sync = CollectionSyncCoordinator(backgroundScope, { fullReads++ }, { throw it }, { error("Must reconcile") })
        sync.onDelta("""[{"uri":"$uri","removed":false}]""")
        sync.requestSync()
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        assertEquals(1, fullReads)
    }

    @Test
    fun `pushes received during sync are processed afterwards without cancellation`() = runTest {
        val release = CompletableDeferred<Unit>()
        var applied = false
        val sync = CollectionSyncCoordinator(backgroundScope, { release.await() }, { throw it }, { applied = true })
        sync.requestSync()
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        sync.onDelta("""[{"uri":"$uri","removed":true}]""")
        runCurrent()
        assertFalse(applied)
        release.complete(Unit)
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        assertTrue(applied)
    }

    @Test
    fun `failed delta application recovers with an authoritative read`() = runTest {
        var fullReads = 0
        var failures = 0
        val sync = CollectionSyncCoordinator(backgroundScope, { fullReads++ }, { failures++ }, { error("Interrupted") })
        sync.onDelta("""[{"uri":"$uri","removed":false}]""")
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
        assertEquals(1, failures)
        assertEquals(1, fullReads)
    }

    @Test
    fun `malformed deltas trigger reconciliation rather than deleting cached data`() = runTest {
        var fullReads = 0
        var failures = 0
        val sync = CollectionSyncCoordinator(backgroundScope, { fullReads++ }, { failures++ })
        sync.onDelta("""[{"uri":"bad","removed":true}]""")
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        assertEquals(1, failures)
        assertEquals(1, fullReads)
    }
}
