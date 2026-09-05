package cc.tomko.outify.data.metadata

import android.util.Log
import androidx.room.withTransaction
import cc.tomko.outify.core.model.Album
import cc.tomko.outify.core.model.Cover
import cc.tomko.outify.core.model.CoverSize
import cc.tomko.outify.core.model.asInt
import cc.tomko.outify.core.model.asSize
import cc.tomko.outify.core.model.getCover
import cc.tomko.outify.data.dao.AlbumArtistDao
import cc.tomko.outify.data.dao.AlbumDao
import cc.tomko.outify.data.dao.AlbumTrackDao
import cc.tomko.outify.data.database.AlbumEntity
import cc.tomko.outify.data.database.AppDatabase
import cc.tomko.outify.data.database.album.AlbumArtistEntity
import cc.tomko.outify.data.database.album.AlbumTrackCrossRef
import cc.tomko.outify.data.database.album.AlbumWithArtists
import cc.tomko.outify.data.database.album.toDomain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class AlbumMetadataHelper @Inject constructor(
    private val db: AppDatabase,
    private val albumDao: AlbumDao,
    private val albumArtistDao: AlbumArtistDao,
    private val albumTrackDao: AlbumTrackDao,
    private val nativeMetadata: NativeMetadata,
    private val trackMetadataHelper: TrackMetadataHelper,
    private val json: Json,
    @Named("metadataConcurrency") private val concurrency: Int,
) {

    fun observeAlbums(uris: List<String>): Flow<List<Album>> {
        if (uris.isEmpty()) return flowOf(emptyList())

        val cleanedIds = uris.map { it.removePrefix("spotify:album:") }

        return albumDao
            .observeAlbumsWithArtists(cleanedIds)
            .map { entities ->
                val byUri: Map<String, Album> = entities
                    .associateBy { it.album.uri }
                    .mapValues { (_, v) -> v.toDomain() }

                uris.mapNotNull { uri -> byUri[uri] }
            }
            .distinctUntilChanged()
            .flowOn(Dispatchers.IO)
    }

    /**
     * Returns the cached album with its tracks (ordered URIs).
     *
     * If album missing in DB -> fetch remote, persist, return fetched.
     * If album exists but album_tracks missing -> fetch remote, persist cross-refs, return fetched.
     * If album + album_tracks exist -> fetch remote, compare track lists:
     *      - if different -> persist remote and return it
     *      - if identical  -> return cached immediately
     */
    suspend fun getAlbumMetadata(uri: String): Album? {
        if (uri.isBlank()) return null

        val cleanedId = uri.removePrefix("spotify:album:")

        val albumMap = loadCachedAlbumsByUri(listOf(uri))
        val albumWithArtists = albumMap[uri]

        if (albumWithArtists == null) {
            val fetched = try {
                fetchAlbums(listOf(uri))
            } catch (e: Exception) {
                Log.w("Metadata", "Failed to fetch album $uri", e)
                emptyList()
            }

            if (fetched.isNotEmpty()) {
                persistAlbumMetadata(fetched)
                return fetched.first()
            }

            return null
        }

        val cachedTrackUris = getCachedAlbumTracks(cleanedId)

        if (cachedTrackUris.isEmpty()) {
            val fetched = try {
                fetchAlbums(listOf(uri))
            } catch (e: Exception) {
                Log.w("Metadata", "Failed to fetch album tracks for $uri", e)
                emptyList()
            }

            if (fetched.isNotEmpty()) {
                persistAlbumMetadata(fetched)
                return fetched.first()
            }

            // fallback: cached album without tracks
            val cachedDomain = albumWithArtists.toDomain()
            return cachedDomain.copy(tracks = emptyList())
        }

        // Fetch remote album and compare track lists.
        val remoteAlbums = try {
            fetchAlbums(listOf(uri))
        } catch (e: Exception) {
            Log.w("Metadata", "Failed to fetch album (verification) for $uri", e)
            null
        }

        if (remoteAlbums == null || remoteAlbums.isEmpty()) {
            // Remote unavailable
            val cachedDomain = albumWithArtists.toDomain()
            return cachedDomain.copy(tracks = cachedTrackUris)
        }

        val remoteAlbum = remoteAlbums.first()

        val remoteTrackUris = remoteAlbum.tracks

        val unchanged = remoteTrackUris.size == cachedTrackUris.size &&
                remoteTrackUris.zip(cachedTrackUris).all { (r, c) -> r == c }

        return if (unchanged) {
            val cachedDomain = albumWithArtists.toDomain()
            cachedDomain.copy(tracks = cachedTrackUris)
        } else {
            persistAlbumMetadata(listOf(remoteAlbum))
            remoteAlbum
        }
    }

    /**
     * Gets the cover by album ID with given CoverSize
     * Fetches the album if not cached
     */
    suspend fun getCoverByAlbumId(albumId: String, size: CoverSize): Cover? {
        if (albumId.isBlank()) return null

        val cover = albumDao.getCoverUris(albumId)
        if (cover != null) {
            val url: String? = when (size) {
                CoverSize.LARGE -> cover.largeCoverUri
                CoverSize.SMALL -> cover.smallCoverUri
                CoverSize.MEDIUM -> cover.mediumCoverUri
            }

            if (url != null)
                return Cover(url, size.asSize(), size.asSize(), size.asInt())
        }

        val albumUri = "spotify:album:$albumId"
        val fetched = try {
            getAlbumMetadata(albumUri)
        } catch (e: Exception) {
            Log.w("Metadata", "Failed to fetch album for cover retrieval: $albumUri", e)
            null
        }

        fetched?.let { album ->
            return album.getCover(size)
        }

        return null
    }

    suspend fun getCoverByTrackId(trackId: String, size: CoverSize): Cover? {
        if (trackId.isBlank()) return null
        var albumId = albumTrackDao.getAlbumIdForTrack(trackId)
        if (albumId == null) {
            albumId = trackMetadataHelper.getTrackMetadata(listOf("spotify:track:$trackId"))
                .firstOrNull()?.album?.id
            if (albumId == null) return null
        }

        return getCoverByAlbumId(albumId, size)
    }

    /**
     * Fetches albums from native source.
     */
    private suspend fun fetchAlbums(uris: List<String>): List<Album> = supervisorScope {
        if (uris.isEmpty()) return@supervisorScope emptyList()

        val results = mutableListOf<Album>()

        uris.chunked(concurrency).forEach { chunk ->
            val deferred = chunk.map { uri ->
                async {
                    try {
                        // Retry on rate limit
                        val raw = nativeMetadata.retryOnRateLimit {
                            nativeMetadata.fetchMetadata(uri)
                        }

                        // Decode into Album
                        json.decodeFromString<Album>(raw.toString())
                    } catch (e: RateLimitException) {
                        Log.w("Metadata", "fetchAlbums: rate-limited for $uri, giving up", e)
                        null
                    } catch (e: Exception) {
                        Log.e("Metadata", "fetchAlbums: failed for $uri", e)
                        null
                    }
                }
            }
            results += deferred.awaitAll().filterNotNull()
        }

        results
    }

    /**
     * Loads album entities for given spotify album URIs.
     */
    private suspend fun loadCachedAlbumsByUri(
        uris: List<String>
    ): Map<String, AlbumWithArtists> {
        if (uris.isEmpty()) return emptyMap()

        val cleanedIds = uris.map { it.removePrefix("spotify:album:") }
        val entities = albumDao.getAlbumsWithArtists(cleanedIds)

        val byUri: Map<String, AlbumWithArtists> = entities
            .associateBy { it.album.uri }
            .mapValues { (_, v) ->
                AlbumWithArtists(album = v.album, artists = v.artists)
            }

        return uris.mapNotNull { uri ->
            byUri[uri]?.let { uri to it }
        }.toMap()
    }

    /**
     * Persist album metadata and album-track joins
     */
    private suspend fun persistAlbumMetadata(albums: List<Album>) {
        if (albums.isEmpty()) return

        val now = System.currentTimeMillis()

        val albumEntities = mutableListOf<AlbumEntity>()
        val albumArtistJoins = mutableListOf<AlbumArtistEntity>()
        val albumTrackJoins = mutableListOf<AlbumTrackCrossRef>()

        albums.forEach { album ->
            val sortedByArea = album.covers
                .sortedBy { it.width * it.height }

            val small = sortedByArea.firstOrNull()
            val large = sortedByArea.lastOrNull()
            val medium = sortedByArea.getOrNull(sortedByArea.size / 2)

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

            album.artists.forEachIndexed { idx, artist ->
                albumArtistJoins += AlbumArtistEntity(
                    albumId = album.id,
                    artistId = artist.id,
                    position = idx
                )
            }

            album.tracks.forEachIndexed { index, trackUri ->
                albumTrackJoins += AlbumTrackCrossRef(
                    albumId = album.id,
                    trackId = trackUri,
                    position = index
                )
            }
        }

        db.withTransaction {
            val albumIds = albums.map { it.id }

            albumDao.insertAll(albumEntities)

            if (albumArtistJoins.isNotEmpty()) {
                albumArtistDao.insertAll(albumArtistJoins)
            }

            // Replace joins atomically for the affected albums
            albumTrackDao.deleteByAlbumIds(albumIds)
            if (albumTrackJoins.isNotEmpty()) {
                albumTrackDao.insertAll(albumTrackJoins)
            }
        }
    }

    /**
     * Returns cached track URIs for the given album ID (ordered by position).
     */
    private suspend fun getCachedAlbumTracks(albumId: String): List<String> {
        if (albumId.isBlank()) return emptyList()
        return albumTrackDao.getTrackIdsForAlbum(albumId)
    }

}