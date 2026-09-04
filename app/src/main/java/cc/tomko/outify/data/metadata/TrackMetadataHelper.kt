package cc.tomko.outify.data.metadata

import android.util.Log
import androidx.room.withTransaction
import cc.tomko.outify.core.model.Track
import cc.tomko.outify.core.model.toEntities
import cc.tomko.outify.core.model.toEntity
import cc.tomko.outify.data.dao.AlbumArtistDao
import cc.tomko.outify.data.dao.AlbumDao
import cc.tomko.outify.data.dao.ArtistDao
import cc.tomko.outify.data.dao.TrackArtistDao
import cc.tomko.outify.data.dao.TrackDao
import cc.tomko.outify.data.dao.TrackFileDao
import cc.tomko.outify.data.database.AlbumEntity
import cc.tomko.outify.data.database.AppDatabase
import cc.tomko.outify.data.database.ArtistEntity
import cc.tomko.outify.data.database.TrackArtistEntity
import cc.tomko.outify.data.database.TrackEntity
import cc.tomko.outify.data.database.TrackFileEntity
import cc.tomko.outify.data.database.TrackFull
import cc.tomko.outify.data.database.album.AlbumArtistEntity
import cc.tomko.outify.data.database.album.AlbumTrackCrossRef
import cc.tomko.outify.data.database.album.AlbumWithArtists
import cc.tomko.outify.data.database.toDomain
import cc.tomko.outify.data.repository.TrackRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class TrackMetadataHelper @Inject constructor(
    private val db: AppDatabase,
    private val trackRepo: TrackRepository,
    private val trackDao: TrackDao,
    private val artistDao: ArtistDao,
    private val trackArtistDao: TrackArtistDao,
    private val albumDao: AlbumDao,
    private val albumArtistDao: AlbumArtistDao,
    private val trackFileDao: TrackFileDao,
    private val nativeMetadata: NativeMetadata,
    private val json: Json,
    @Named("metadataConcurrency") private val concurrency: Int,
) {
    suspend fun getTrackMetadata(trackUris: List<String>): List<Track> {
        if (trackUris.isEmpty()) return emptyList()
        val uris = trackUris.filter { it.startsWith("spotify:track:") }

        var cached = loadCached(uris)

        val missing = uris.filterNot { cached.containsKey(it) }
        if (missing.isNotEmpty()) {
            try {
                fetchAndPersist(missing)
                cached = loadCached(uris)
            } catch (e: Exception) {
                Log.w("Metadata", "Failed to fetch missing tracks", e)
            }
        }

        return uris.mapNotNull { uri ->
            cached[uri]?.let { twa ->
                try {
                    twa.toDomain()
                } catch (e: Exception) {
                    Log.w("Metadata", "Failed to map $uri", e)
                    null
                }
            }
        }
    }

    suspend fun getTrackMetadata(trackUri: String): Track? {
        if (!trackUri.startsWith("spotify:track:")) return null

        val cached = loadCached(listOf(trackUri))
        if (cached.containsKey(trackUri)) {
            return cached[trackUri]?.toDomain()
        }

        return try {
            val fetched = fetchAndPersist(listOf(trackUri))
            fetched[trackUri]?.toDomain()
        } catch (e: Exception) {
            Log.w("Metadata", "Failed to fetch $trackUri", e)
            null
        }
    }

    suspend fun getTrackAlbumId(trackUri: String): String? {
        return trackDao.getAlbumIdForTrack(trackUri)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeTracks(uris: List<String>): Flow<List<Track>> {
        if (uris.isEmpty()) return flowOf(emptyList())

        return flow {
            coroutineScope {
                launch {
                    val cached = loadCached(uris)
                    val missing = uris.filterNot { cached.containsKey(it) }
                    if (missing.isNotEmpty()) {
                        try {
                            fetchAndPersist(missing)
                        } catch (e: Exception) {
                            Log.w("Metadata", "Background fetch failed", e)
                        }
                    }
                }
            }

            emitAll(
                trackDao.observeTracksFullFlow(uris).mapLatest { rows ->
                    if (rows.isEmpty()) return@mapLatest emptyList()

                    val albumIds = rows.mapNotNull { it.track.albumId }.distinct()
                    val albumsMap = loadAlbums(albumIds)

                    rows.associateBy { it.track.trackUri }
                        .mapNotNull { (_, tf) ->
                            try {
                                tf.toDomain()
                            } catch (e: Exception) {
                                null
                            }
                        }
                }
            )
        }.flowOn(Dispatchers.IO)
    }

    private suspend fun fetchAndPersist(uris: List<String>): Map<String, TrackFull> {
        if (uris.isEmpty()) return emptyMap()

        val fetched = fetch(uris)
        if (fetched.isNotEmpty()) {
            persist(fetched)
        }

        return loadCached(uris)
    }

    private suspend fun fetch(uris: List<String>): List<Pair<String, Track>> = supervisorScope {
        if (uris.isEmpty()) return@supervisorScope emptyList()

        uris.chunked(concurrency).flatMap { chunk ->
            chunk.map { uri ->
                async {
                    try {
                        val raw = nativeMetadata.retryOnRateLimit {
                            nativeMetadata.fetchMetadata(uri)
                        }
                        uri to json.decodeFromString<Track>(raw.toString())
                    } catch (e: RateLimitException) {
                        Log.w("Metadata", "Rate limited: $uri", e)
                        null
                    } catch (e: Exception) {
                        Log.e("Metadata", "Fetch failed: $uri", e)
                        null
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    private suspend fun loadCached(uris: List<String>): Map<String, TrackFull> {
        if (uris.isEmpty()) return emptyMap()
        return trackDao.getTracksFull(uris).associateBy { it.track.trackUri }
    }

    private suspend fun loadAlbums(albumIds: List<String>): Map<String, AlbumWithArtists?> {
        if (albumIds.isEmpty()) return emptyMap()
        return albumDao.getAlbumsWithArtists(albumIds).associateBy { it.album.albumId }
    }

    private suspend fun persist(metadata: List<Pair<String, Track>>) {
        if (metadata.isEmpty()) return

        val now = System.currentTimeMillis()
        val trackEntities = mutableListOf<TrackEntity>()
        val artistEntities = mutableListOf<ArtistEntity>()
        val trackArtistJoins = mutableListOf<TrackArtistEntity>()
        val albumEntities = mutableListOf<AlbumEntity>()
        val albumArtistJoins = mutableListOf<AlbumArtistEntity>()
        val albumTrackJoins = mutableListOf<AlbumTrackCrossRef>()
        val trackFileEntities = mutableListOf<TrackFileEntity>()

        metadata.forEach { (_, track) ->
            val (tEntity, aEntities, joins) = track.toEntities(now)
            trackEntities += tEntity
            artistEntities += aEntities
            trackArtistJoins += joins

            track.album?.let { album ->
                val sortedCovers = album.covers.sortedBy { it.width * it.height }
                val small = sortedCovers.firstOrNull()
                val large = sortedCovers.lastOrNull()
                val medium = sortedCovers.getOrNull(sortedCovers.size / 2)

                albumEntities += AlbumEntity(
                    albumId = album.id,
                    uri = album.uri,
                    name = album.name,
                    artistNames = album.artists.joinToString(", ") { it.name },
                    popularity = album.popularity,
                    lastUpdated = now,
                    releaseYear = album.date.year,
                    releaseMonth = album.date.month,
                    releaseDay = album.date.day,
                    smallCoverUri = small?.uri,
                    mediumCoverUri = medium?.uri,
                    largeCoverUri = large?.uri
                )

                album.artists.forEachIndexed { idx, art ->
                    albumArtistJoins += AlbumArtistEntity(album.id, art.id, idx)
                }

                album.tracks.forEachIndexed { index, trackId ->
                    albumTrackJoins += AlbumTrackCrossRef(album.id, trackId, index)
                }
            }

            track.files.forEach { file ->
                trackFileEntities += file.toEntity(tEntity.id)
            }
        }

        db.withTransaction {
            if (artistEntities.isNotEmpty()) artistDao.insertAll(artistEntities)
            if (albumEntities.isNotEmpty()) albumDao.insertAll(albumEntities)
            if (albumArtistJoins.isNotEmpty()) albumArtistDao.insertAll(albumArtistJoins)
            if (trackEntities.isNotEmpty()) trackRepo.upsertTracks(trackEntities, isLibrary = true)
            if (trackArtistJoins.isNotEmpty()) trackArtistDao.insertAll(trackArtistJoins)

            if (trackFileEntities.isNotEmpty()) {
                trackFileDao.deleteFilesForTracks(trackEntities.map { it.id })
                trackFileDao.insertTrackFiles(trackFileEntities)
            }
        }
    }
}