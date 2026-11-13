package cc.tomko.outify.core.connect

import cc.tomko.outify.core.model.PlayableAudio
import cc.tomko.outify.playback.PlaybackStateHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Independent from snapshot sampling: a failed read never permanently strands a URI placeholder. */
internal suspend fun hydrateConnectMetadata(
    snapshots: StateFlow<ConnectPlayback?>,
    holder: PlaybackStateHolder,
    resolve: suspend (String) -> PlayableAudio?,
    onFailure: (Exception) -> Unit = {},
) {
    snapshots.map { it?.metadataKey() }.distinctUntilChanged().collectLatest { key ->
        if (key == null || !(key.uri.startsWith("spotify:track:") || key.uri.startsWith("spotify:episode:"))) {
            return@collectLatest
        }
        var retryDelayMs = 1000L
        while (snapshots.value?.metadataKey() == key) {
            val audio = try {
                resolve(key.uri)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                onFailure(error)
                null
            }
            currentCoroutineContext().ensureActive()
            val current = snapshots.value
            if (current?.metadataKey() != key) return@collectLatest
            if (audio?.uri == key.uri && audio.name.isNotBlank() && audio.name != key.uri) {
                if (current.isRemote) {
                    // Apply to the latest playback instance, not the obsolete ID that began the read.
                    holder.setRemoteAudio(current.target, audio)
                } else if (current.isLocal) {
                    holder.setLocalSnapshotAudio(current.uri, audio)
                }
                return@collectLatest
            }
            // Retry metadata reads only. Mutating transport commands remain single-attempt.
            delay(retryDelayMs)
            retryDelayMs = (retryDelayMs * 2).coerceAtMost(30000L)
        }
    }
}
