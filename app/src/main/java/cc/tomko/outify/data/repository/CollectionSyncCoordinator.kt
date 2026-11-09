package cc.tomko.outify.data.repository

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Keeps delta ordering; a recovery/startup refresh replaces a burst with an authoritative read. */
@Singleton
class CollectionSyncCoordinator internal constructor(
    scope: CoroutineScope,
    private val sync: suspend () -> Unit,
    private val onFailure: (Exception) -> Unit,
    private val applyChanges: suspend (List<CollectionTrackChange>) -> Unit = {},
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    @Inject
    constructor(likedRepository: LikedRepository) : this(
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
        likedRepository::syncLikedTracksFromCollection,
        { Log.w("CollectionSync", "Could not refresh saved tracks", it) },
        likedRepository::applyCollectionChanges,
    )

    private sealed interface Update {
        data object Refresh : Update
        data class Delta(val changes: List<CollectionTrackChange>) : Update
    }
    private val changes = Channel<Update>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (first in changes) {
                var refresh = first is Update.Refresh
                val deltas = mutableListOf<CollectionTrackChange>()
                if (first is Update.Delta) deltas += first.changes
                delay(100)
                while (true) {
                    when (val next = changes.tryReceive().getOrNull() ?: break) {
                        Update.Refresh -> refresh = true
                        is Update.Delta -> deltas += next.changes
                    }
                }
                for (attempt in 0 until 3) {
                    try {
                        if (refresh) sync() else applyChanges(deltas)
                        break
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        onFailure(error)
                        // Metadata or delta writes may have partially completed.
                        // Re-read authoritative membership rather than replaying old deltas.
                        refresh = true
                        if (attempt < 2) delay(500L * (attempt + 1))
                    }
                }
                // A push received during sync stays pending for one follow-up pass.
            }
        }
    }

    fun requestSync() {
        changes.trySend(Update.Refresh)
    }

    fun onDelta(raw: String) {
        try {
            val deltas = decodeCollectionChanges(raw, json)
            if (deltas.isNotEmpty()) changes.trySend(Update.Delta(deltas))
        } catch (error: Exception) {
            onFailure(error)
            requestSync()
        }
    }
}
