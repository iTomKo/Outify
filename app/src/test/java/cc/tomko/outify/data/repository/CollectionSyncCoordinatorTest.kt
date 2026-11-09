package cc.tomko.outify.data.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CollectionSyncCoordinatorTest {
    private val accountA = CollectionSession("account-a", "session-a")
    private val accountB = CollectionSession("account-b", "session-b")
    private val uri = "spotify:track:0000000000000000000001"

    private fun TestScope.coordinator(
        sessions: CollectionSessionState = CollectionSessionState(),
        read: suspend () -> CollectionSession = { accountA },
        sync: suspend (CollectionLease) -> Unit = {},
        apply: suspend (CollectionLease, List<CollectionTrackChange>) -> Unit = { _, _ -> },
        activate: suspend (CollectionLease) -> Unit = {},
        onFailure: (Exception) -> Unit = { throw it },
        capacity: Int = 512,
    ) = CollectionSyncCoordinator(
        scope = backgroundScope,
        readSession = read,
        activate = activate,
        sync = sync,
        applyChanges = apply,
        deactivate = {},
        sessions = sessions,
        onFailure = onFailure,
        maxPendingChanges = capacity,
    ).also { it.resume() }

    private fun delta(session: CollectionSession = accountA, removed: Boolean = false) =
        """{"session":{"accountId":"${session.accountId}","sessionId":"${session.sessionId}"},"changes":[{"uri":"$uri","removed":$removed}]}"""

    @Test
    fun `cold startup reconciles without waiting for a liked screen or a push`() = runTest {
        var reads = 0
        val sync = coordinator(sync = { reads++ })
        runCurrent()
        assertEquals(1, reads)
        assertEquals(CollectionSyncPhase.Ready, sync.state.value.phase)
        sync.resume()
        runCurrent()
        assertEquals(1, reads)
    }

    @Test
    fun `delta bursts preserve order without fetching the collection`() = runTest {
        var fullReads = 0
        val applied = mutableListOf<List<CollectionTrackChange>>()
        val sync = coordinator(sync = { fullReads++ }, apply = { _, changes -> applied += changes })
        runCurrent()
        sync.onDelta(delta(removed = false))
        sync.onDelta(delta(removed = true))
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        assertEquals(1, fullReads)
        assertEquals(listOf(false, true), applied.single().map { it.removed })
    }

    @Test
    fun `refresh supersedes queued deltas`() = runTest {
        var fullReads = 0
        val sync = coordinator(sync = { fullReads++ }, apply = { _, _ -> error("Must reconcile") })
        runCurrent()
        sync.onDelta(delta())
        sync.requestSync()
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        assertEquals(2, fullReads)
    }

    @Test
    fun `reconciliation continues after three failures and deltas cannot bypass recovery`() = runTest {
        var fullReads = 0
        var failures = 0
        var applied = 0
        val sync = coordinator(
            sync = { if (++fullReads <= 4) error("Offline") },
            apply = { _, _ -> applied++ },
            onFailure = { failures++ },
        )
        runCurrent()
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(4, failures)
        assertEquals(CollectionSyncPhase.Recovering, sync.state.value.phase)
        sync.onDelta(delta())
        runCurrent()
        assertEquals(0, applied)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(5, fullReads)
        assertEquals(0, applied) // The successful full snapshot supersedes this queued delta.
        assertEquals(CollectionSyncPhase.Ready, sync.state.value.phase)
    }

    @Test
    fun `a failed delta requires reconciliation before applying more deltas`() = runTest {
        var fullReads = 0
        var failures = 0
        val sync = coordinator(
            sync = { fullReads++ },
            apply = { _, _ -> error("Interrupted write") },
            onFailure = { failures++ },
        )
        runCurrent()
        sync.onDelta(delta())
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        assertEquals(1, failures)
        advanceTimeBy(500)
        runCurrent()
        assertEquals(2, fullReads)
        assertEquals(CollectionSyncPhase.Ready, sync.state.value.phase)
    }

    @Test
    fun `queue overflow triggers a full read instead of dropping an unrepresented mutation`() = runTest {
        var fullReads = 0
        var applied = 0
        val sync = coordinator(sync = { fullReads++ }, apply = { _, _ -> applied++ }, capacity = 2)
        runCurrent()
        repeat(3) { sync.onDelta(delta(removed = it == 2)) }
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        assertEquals(2, fullReads)
        assertEquals(0, applied)
    }

    @Test
    fun `late old account results and callbacks cannot affect a new account`() = runTest {
        val sessions = CollectionSessionState()
        val releaseOld = CompletableDeferred<Unit>()
        val commits = mutableListOf<String>()
        var identity = accountA
        var applied = 0
        val sync = coordinator(
            sessions = sessions,
            read = { identity },
            sync = { lease ->
                if (lease.session == accountA) withContext(NonCancellable) { releaseOld.await() }
                sessions.ensureCurrent(lease)
                commits += lease.session.accountId
            },
            apply = { _, _ -> applied++ },
        )
        runCurrent()
        sync.pause()
        identity = accountB
        sync.resume()
        runCurrent()
        sync.onDelta(delta(accountA))
        sync.onInvalidation("""{"accountId":"account-a","sessionId":"session-a"}""")
        releaseOld.complete(Unit)
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        assertEquals(listOf("account-b"), commits)
        assertEquals(0, applied)
        assertEquals(accountB, sessions.current.value!!.session)
        assertEquals(CollectionSyncPhase.Ready, sync.state.value.phase)
    }

    @Test
    fun `a late identity lookup cannot reactivate the previous account`() = runTest {
        val sessions = CollectionSessionState()
        val oldLookup = CompletableDeferred<CollectionSession>()
        var lookups = 0
        val activations = mutableListOf<CollectionSession>()
        val sync = coordinator(
            sessions = sessions,
            read = { if (++lookups == 1) withContext(NonCancellable) { oldLookup.await() } else accountB },
            activate = { activations += it.session },
        )
        runCurrent()
        sync.pause()
        sync.resume()
        runCurrent()
        oldLookup.complete(accountA)
        runCurrent()
        assertEquals(listOf(accountB), activations)
        assertEquals(accountB, sessions.current.value!!.session)
    }

    @Test
    fun `pause cancels recovery and reconnect of the same account rejects old session deltas`() = runTest {
        val sessions = CollectionSessionState()
        var reads = 0
        var offline = true
        var identity = accountA
        var applied = 0
        val sync = coordinator(
            sessions = sessions,
            read = { identity },
            sync = { reads++; if (offline) error("Offline") },
            apply = { _, _ -> applied++ },
            onFailure = {},
        )
        runCurrent()
        sync.pause()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, reads)
        assertNull(sessions.current.value)
        assertEquals(CollectionSyncPhase.Paused, sync.state.value.phase)
        identity = accountA.copy(sessionId = "replacement")
        offline = false
        sync.resume()
        runCurrent()
        sync.onDelta(delta(accountA))
        sync.onDelta(delta(identity))
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        assertEquals(1, applied)
        assertEquals(2, reads)
    }

    @Test
    fun `malformed envelopes trigger a guarded read not membership changes`() = runTest {
        var reads = 0
        var failures = 0
        val sync = coordinator(sync = { reads++ }, onFailure = { failures++ })
        runCurrent()
        sync.onDelta("""[{"uri":"$uri","removed":true}]""")
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        assertEquals(1, failures)
        assertEquals(2, reads)
    }
}
