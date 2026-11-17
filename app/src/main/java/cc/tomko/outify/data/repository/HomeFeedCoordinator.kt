package cc.tomko.outify.data.repository

import cc.tomko.outify.core.home.HomeFeed
import cc.tomko.outify.core.home.HomeFeedClient
import cc.tomko.outify.core.home.HomeItem
import cc.tomko.outify.core.home.HomeSection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

data class HomeFeedState(
    val lease: CollectionLease? = null,
    val quickPicks: List<HomeItem> = emptyList(),
    val pickedAlbums: List<HomeItem> = emptyList(),
    val sections: List<HomeSection> = emptyList(),
    val refreshing: Boolean = false,
    val error: String? = null,
    val partial: Boolean = false,
) {
    val hasContent: Boolean get() = quickPicks.isNotEmpty() || pickedAlbums.isNotEmpty() || sections.isNotEmpty()
}

/** Every publication, including cancelled blocking JNI results, belongs to one playback lease. */
class HomeFeedCoordinator(
    private val client: HomeFeedClient,
    private val sessions: CollectionSessionState,
    scope: CoroutineScope,
    private val library: (CollectionLease) -> Flow<List<HomeItem>>,
) {
    private val _state = MutableStateFlow(HomeFeedState())
    val state = _state.asStateFlow()
    private val refreshes = MutableStateFlow(0L)

    init {
        scope.launch {
            sessions.current.onEach { lease ->
                // Clear before collectLatest waits for a blocking old JNI call to unwind.
                _state.value = HomeFeedState(lease = lease, refreshing = lease != null)
            }.collectLatest { lease ->
                if (lease == null) return@collectLatest
                coroutineScope {
                    var recent = emptyList<HomeItem>()
                    var picks = emptyList<HomeItem>()
                    launch {
                        try {
                            library(lease).collect { items ->
                                sessions.ensureCurrent(lease)
                                // Do not reshuffle a deck already on screen as background library sync advances.
                                if (picks.isEmpty() && items.isNotEmpty()) {
                                    picks = items
                                    publish(lease) { it.copy(quickPicks = quickPicks(recent, picks)) }
                                }
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { /* Optional saved-track fallback must not hide Spotify's feed. */ }
                    }
                    refreshes.collectLatest refresh@{ revision ->
                        publish(lease) { it.copy(refreshing = true, error = null) }
                        val retries = listOf(3000L, 10000L, 30000L)
                        var attempt = 0
                        while (true) {
                            try {
                                val feed: HomeFeed = client.home(lease.session)
                                currentCoroutineContext().ensureActive()
                                sessions.ensureSource(lease, feed.session)
                                if (revision != refreshes.value) return@refresh
                                recent = feed.recent
                                publish(lease) { HomeFeedState(
                                    lease = lease, quickPicks = quickPicks(recent, picks),
                                    pickedAlbums = feed.pickedAlbums, sections = feed.sections,
                                    partial = feed.warnings.isNotEmpty(),
                                ) }
                                break
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (error: Exception) {
                                sessions.ensureCurrent(lease)
                                if (revision != refreshes.value) return@refresh
                                publish(lease) { it.copy(error = error.message ?: "Could not load recommendations") }
                                if (attempt == retries.size) {
                                    publish(lease) { it.copy(refreshing = false) }
                                    break
                                }
                                delay(retries[attempt++])
                            }
                        }
                    }
                }
            }
        }
    }

    fun refresh() { refreshes.value += 1 }

    private fun publish(lease: CollectionLease, transform: (HomeFeedState) -> HomeFeedState) {
        _state.update { if (it.lease == lease && sessions.current.value == lease) transform(it) else it }
    }
}

internal fun quickPicks(recent: List<HomeItem>, library: List<HomeItem>): List<HomeItem> =
    (recent.take(10) + library).filter { it.kind in setOf("track", "album", "playlist", "artist") && it.title.isNotBlank() }
        .distinctBy { it.uri }.take(30)
