package cc.tomko.outify.data.repository

import cc.tomko.outify.core.home.HomeItem
import cc.tomko.outify.core.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeTrackState(val lease: CollectionLease? = null, val tracks: Map<String, Track> = emptyMap())

/** Keep real track metadata for configured gestures; never invent artists, durations, or album IDs. */
internal class HomeTrackMetadata(
    home: StateFlow<HomeFeedState>,
    private val sessions: CollectionSessionState,
    scope: CoroutineScope,
    private val load: suspend (List<String>) -> List<Track>,
) {
    private data class Request(val lease: CollectionLease?, val items: List<HomeItem>)
    private val _state = MutableStateFlow(HomeTrackState())
    val state = _state.asStateFlow()

    init {
        scope.launch {
            home.map { feed ->
                Request(feed.lease, (feed.quickPicks + feed.sections.flatMap { it.items })
                    .filter { it.kind == "track" }.distinctBy { it.uri })
            }.distinctUntilChanged().onEach { request ->
                val lease = request.lease?.takeIf { it == sessions.current.value }
                val uris = request.items.map { it.uri }.toSet()
                val cached = _state.value.takeIf { it.lease == lease }?.tracks.orEmpty().filterKeys { it in uris }
                val available = request.items.mapNotNull { item ->
                    item.track?.takeIf { it.uri == item.uri }?.let { item.uri to it }
                }.toMap()
                // Clear old account data before waiting for a cancelled JNI read to unwind.
                _state.value = if (lease == null) HomeTrackState() else HomeTrackState(lease, cached + available)
            }.collectLatest { request ->
                val lease = request.lease ?: return@collectLatest
                if (lease != sessions.current.value) return@collectLatest
                repeat(4) { attempt ->
                    val missing = request.items.map { it.uri }.filterNot { it in _state.value.tracks }
                    if (missing.isEmpty()) return@collectLatest
                    val tracks = try { load(missing) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { emptyList() }
                    currentCoroutineContext().ensureActive()
                    sessions.ensureCurrent(lease)
                    val resolved = tracks.filter { it.uri in missing }.associateBy { it.uri }
                    _state.update { current ->
                        if (current.lease == lease && sessions.current.value == lease) current.copy(tracks = current.tracks + resolved)
                        else current
                    }
                    if (request.items.all { it.uri in _state.value.tracks }) return@collectLatest
                    if (attempt < 3) delay(1000L shl attempt)
                }
            }
        }
    }
}
