package cc.tomko.outify.data.repository

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Coalesces collection pushes without cancelling a database sync already in progress. */
@Singleton
class CollectionSyncCoordinator internal constructor(
    scope: CoroutineScope,
    private val sync: suspend () -> Unit,
    private val onFailure: (Exception) -> Unit,
) {
    @Inject
    constructor(likedRepository: LikedRepository) : this(
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
        likedRepository::syncLikedTracksFromCollection,
        { Log.w("CollectionSync", "Could not refresh saved tracks", it) },
    )

    private val changes = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (ignored in changes) {
                delay(500)
                while (changes.tryReceive().isSuccess) { /* Coalesce the current burst. */ }
                for (attempt in 0 until 3) {
                    try {
                        sync()
                        break
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        onFailure(error)
                        if (attempt < 2) delay(500L * (attempt + 1))
                    }
                }
                // A push received during sync stays pending for one follow-up pass.
            }
        }
    }

    fun requestSync() {
        changes.trySend(Unit)
    }
}
