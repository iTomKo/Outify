package cc.tomko.outify.data.repository

import android.util.Log
import cc.tomko.outify.data.metadata.Metadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

enum class CollectionSyncPhase { Paused, Starting, Syncing, Ready, Recovering }

data class CollectionSyncState(
    val phase: CollectionSyncPhase = CollectionSyncPhase.Paused,
    val error: String? = null,
)

/** Session-scoped worker with bounded deltas and a dirty flag that survives failed retries. */
@Singleton
class CollectionSyncCoordinator internal constructor(
    private val scope: CoroutineScope,
    private val readSession: suspend () -> CollectionSession,
    private val activate: suspend (CollectionLease) -> Unit,
    private val sync: suspend (CollectionLease) -> Unit,
    private val applyChanges: suspend (CollectionLease, List<CollectionTrackChange>) -> Unit,
    private val deactivate: suspend (CollectionLease) -> Unit,
    private val sessions: CollectionSessionState,
    private val onFailure: (Exception) -> Unit,
    private val maxPendingChanges: Int = 512,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    @Inject
    constructor(repository: LikedRepository, metadata: Metadata, sessions: CollectionSessionState) : this(
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
        metadata::getCollectionSession,
        repository::activateCollectionSession,
        repository::syncLikedTracksFromCollection,
        repository::applyCollectionChanges,
        repository::deactivateCollectionSession,
        sessions,
        { Log.w("CollectionSync", "Could not refresh saved tracks", it) },
    )

    private val lock = Any()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val pending = ArrayDeque<CollectionTrackChange>()
    private var epoch = 0L
    private var paused = true
    private var worker: Job? = null
    private var dirty = true
    private var refreshRevision = 0L
    private val _state = MutableStateFlow(CollectionSyncState())
    val state = _state.asStateFlow()

    fun pause() {
        val previous = synchronized(lock) {
            paused = true
            ++epoch
            worker?.cancel()
            worker = null
            pending.clear()
            dirty = true
            _state.value = CollectionSyncState()
            sessions.invalidate()
        }
        if (previous != null) scope.launch {
            try { deactivate(previous) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { onFailure(error) }
        }
    }

    /** Called for both cold startup and reconnect, independently of playback transfer. */
    fun resume() = synchronized(lock) {
        if (!paused && worker?.isActive == true) return@synchronized
        paused = false
        val started = ++epoch
        dirty = true
        pending.clear()
        _state.value = CollectionSyncState(CollectionSyncPhase.Starting)
        worker = scope.launch { run(started) }
    }

    fun requestSync() = synchronized(lock) {
        dirty = true
        ++refreshRevision
        wake.trySend(Unit)
        Unit
    }

    fun onInvalidation(raw: String) {
        try {
            val identity = json.decodeFromString<CollectionSession>(raw).validate()
            synchronized(lock) {
                if (!paused && sessions.current.value?.session == identity) requestSync()
            }
        } catch (error: Exception) {
            onFailure(error)
            requestSync() // Invalid data can only trigger a guarded authoritative read.
        }
    }

    fun onDelta(raw: String) {
        try {
            val delta = decodeCollectionDelta(raw, json)
            synchronized(lock) {
                if (paused || sessions.current.value?.session != delta.session) return
                if (delta.changes.size > maxPendingChanges - pending.size) {
                    // Never silently discard an unrepresented mutation on overflow.
                    pending.clear()
                    dirty = true
                    ++refreshRevision
                } else pending.addAll(delta.changes)
                wake.trySend(Unit)
            }
        } catch (error: Exception) {
            onFailure(error)
            requestSync()
        }
    }

    private fun isCurrent(started: Long) = !paused && epoch == started

    private fun publish(started: Long, state: CollectionSyncState) = synchronized(lock) {
        if (!isCurrent(started)) throw StaleCollectionSessionException()
        _state.value = state
    }

    private suspend fun run(started: Long) {
        var failures = 0
        while (synchronized(lock) { isCurrent(started) }) {
            try {
                val identity = readSession()
                currentCoroutineContext().ensureActive()
                val lease = synchronized(lock) {
                    if (!isCurrent(started)) throw StaleCollectionSessionException()
                    sessions.activate(identity)
                }
                activate(lease)
                runSession(started, lease)
                return
            } catch (stale: StaleCollectionSessionException) {
                // A result identified a replaced native session before its lifecycle callback.
                val previous = synchronized(lock) {
                    if (!isCurrent(started)) throw stale
                    sessions.invalidate()
                }
                val error = try {
                    if (previous != null) deactivate(previous)
                    stale
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    failure
                }
                recover(started, error, ++failures)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                recover(started, error, ++failures)
            }
        }
    }

    private suspend fun runSession(started: Long, lease: CollectionLease) {
        var failures = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            sessions.ensureCurrent(lease)
            val work = synchronized(lock) {
                if (!isCurrent(started)) throw StaleCollectionSessionException()
                if (!dirty && pending.isEmpty()) null
                else {
                    val batch = pending.toList()
                    pending.clear()
                    Triple(dirty, refreshRevision, batch)
                }
            }
            if (work == null) {
                publish(started, CollectionSyncState(CollectionSyncPhase.Ready))
                wake.receive()
                delay(100) // Coalesce only idle bursts, never delay error recovery.
                continue
            }
            try {
                publish(started, CollectionSyncState(CollectionSyncPhase.Syncing))
                if (work.first) sync(lease) else applyChanges(lease, work.third)
                sessions.ensureCurrent(lease)
                synchronized(lock) {
                    if (work.first && refreshRevision == work.second) dirty = false
                }
                failures = 0
            } catch (stale: StaleCollectionSessionException) {
                throw stale
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                recover(started, error, ++failures)
            }
        }
    }

    private suspend fun recover(started: Long, error: Exception, failures: Int) {
        synchronized(lock) {
            if (!isCurrent(started)) throw StaleCollectionSessionException()
            dirty = true
            ++refreshRevision
            _state.value = CollectionSyncState(CollectionSyncPhase.Recovering, error.message)
        }
        onFailure(error)
        // Retry until repaired or paused. New deltas must never bypass this dirty state.
        val backoff = if (failures <= 3) 500L * failures
            else (5_000L shl (failures - 4).coerceAtMost(4)).coerceAtMost(60_000L)
        delay(backoff)
    }
}
