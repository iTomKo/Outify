package cc.tomko.outify.data.repository

import cc.tomko.outify.ALBUM_COVER_URL
import cc.tomko.outify.core.home.HomeItem
import cc.tomko.outify.core.model.CoverSize
import cc.tomko.outify.core.model.Track
import cc.tomko.outify.core.model.getCover
import cc.tomko.outify.data.dao.LikedDao
import cc.tomko.outify.data.metadata.TrackMetadataHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import javax.inject.Inject
import kotlin.random.Random

class HomeLibraryPicks @Inject constructor(
    private val liked: LikedDao,
    private val metadata: TrackMetadataHelper,
    private val sessions: CollectionSessionState,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(lease: CollectionLease): Flow<List<HomeItem>> =
        liked.observeHomeLikedIds(lease.session.accountId, lease.session.sessionId, lease.generation)
            .distinctUntilChanged().mapLatest { ids ->
                sessions.ensureCurrent(lease)
                val seed = lease.session.sessionId.hashCode()
                val candidates = (ids.take(10) + ids.drop(10).shuffled(Random(seed)).take(90)).distinct()
                val tracks = metadata.getTrackMetadata(candidates.map { "spotify:track:$it" })
                sessions.ensureCurrent(lease)
                mixedHomeTracks(tracks, seed).map { track ->
                    HomeItem(track.uri, "track", track.name, track.artists.joinToString { it.name },
                        track.album?.getCover(CoverSize.MEDIUM)?.uri?.let { ALBUM_COVER_URL + it }, track = track)
                }
            }.flowOn(Dispatchers.IO)
}

/** recent saves, random saved tracks, then artist variety, stable per login. */
internal fun mixedHomeTracks(tracks: List<Track>, seed: Int): List<Track> {
    val playable = tracks.filter { it.uri.startsWith("spotify:track:") && it.name.isNotBlank() && it.files.isNotEmpty() }.distinctBy { it.uri }
    val selected = playable.take(10).toMutableList()
    val remaining = playable.drop(10).shuffled(Random(seed)).toMutableList()
    selected += remaining.take(10)
    repeat(minOf(10, remaining.size)) { remaining.removeAt(0) }
    val artists = selected.map { it.artists.firstOrNull()?.id ?: it.uri }.toMutableSet()
    val diverse = remaining.filter { artists.add(it.artists.firstOrNull()?.id ?: it.uri) }.take(10)
    selected += diverse
    selected += remaining.filter { candidate -> selected.none { it.uri == candidate.uri } }.take(30 - selected.size)
    return selected.shuffled(Random(seed)).take(30)
}
