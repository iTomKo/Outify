package cc.tomko.outify.core.connect

import cc.tomko.outify.core.SpClient
import cc.tomko.outify.core.model.toPlayableAudio
import cc.tomko.outify.data.metadata.Metadata
import cc.tomko.outify.data.repository.CollectionSessionState
import cc.tomko.outify.playback.PlaybackStateHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Samples native Dealer cache only. Metadata never stalls ownership/transport updates. */
@Singleton
class ConnectPlaybackMonitor @Inject constructor(
    private val client: SpClient,
    private val json: Json,
    private val metadata: Metadata,
    private val sessions: CollectionSessionState,
    private val holder: PlaybackStateHolder,
) {
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val _snapshot = MutableStateFlow<ConnectPlayback?>(null)
    val snapshot: StateFlow<ConnectPlayback?> = _snapshot.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private var started = false
    private val epoch = java.util.concurrent.atomic.AtomicLong()
    private val commandMutex = Mutex()

    fun start(ready: StateFlow<Boolean>) {
        if (started) return
        started = true
        scope.launch {
            combine(ready, sessions.current) { usable, lease -> usable to lease }
                .collectLatest { (usable, lease) ->
                    clear()
                    if (!usable || lease == null) return@collectLatest
                    while (isActive) {
                        try {
                            val readEpoch = epoch.get()
                            val next = withContext(Dispatchers.IO) {
                                json.decodeFromString<ConnectPlayback>(requireNotNull(client.getConnectPlayback()))
                            }
                            currentCoroutineContext().ensureActive()
                            if (!ready.value || readEpoch != epoch.get() || sessions.current.value != lease
                                || next.target.session != lease.session) {
                                clear()
                            } else {
                                holder.applyConnectPlayback(next)
                                _snapshot.value = next
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            clear()
                        }
                        delay(500)
                    }
                }
        }
        scope.launch {
            hydrateConnectMetadata(snapshot, holder, resolve = { uri ->
                withContext(Dispatchers.IO) {
                    if (uri.startsWith("spotify:track:")) {
                        metadata.getTrackMetadata(listOf(uri)).firstOrNull()?.toPlayableAudio()
                    } else {
                        metadata.getEpisodeMetadata(uri)?.toPlayableAudio()
                    }
                }
            }, onFailure = { error -> android.util.Log.w("ConnectPlayback", "Now-playing metadata lookup failed; will retry", error) })
        }
    }

    fun clear() {
        epoch.incrementAndGet()
        _snapshot.value = null
        _error.value = null
        holder.connectUnavailable()
    }

    fun capture(action: String, value: Long? = null): ConnectCommand? = remoteCommand(snapshot.value, action, value)

    /** true means queued, not confirmed. Dealer state remains authoritative. No retries. */
    fun submit(command: ConnectCommand): Boolean {
        val current = snapshot.value ?: return false
        val validCommand = if (command.action == "play") {
            command.play != null && playbackLoadRoute(current, command.play) is PlaybackRoute.Remote
        } else remoteCommand(current, command.action, command.value) != null
        if (current.target != command.target || !current.isRemote
            || sessions.current.value?.session != command.target.session
            || !validCommand) return false
        val requestEpoch = epoch.get()
        scope.launch {
            try {
                _error.value = null
                commandMutex.withLock {
                    withContext(Dispatchers.IO) {
                        check(requestEpoch == epoch.get() && sessions.current.value?.session == command.target.session) {
                            "Playback session changed before remote command"
                        }
                        check(client.sendConnectCommand(json.encodeToString(command))) { "Remote command rejected" }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // A failure can follow delivery. Never retry Next or fall back to local Play.
                if (snapshot.value?.target == command.target) _error.value = error.message
            }
        }
        return true
    }
}
