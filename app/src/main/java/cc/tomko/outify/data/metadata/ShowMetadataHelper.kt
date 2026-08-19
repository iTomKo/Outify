package cc.tomko.outify.data.metadata

import android.util.Log
import androidx.room.withTransaction
import cc.tomko.outify.core.model.Cover
import cc.tomko.outify.core.model.CoverSize
import cc.tomko.outify.core.model.Show
import cc.tomko.outify.core.model.asInt
import cc.tomko.outify.core.model.asSize
import cc.tomko.outify.data.dao.ShowDao
import cc.tomko.outify.data.dao.ShowEpisodeDao
import cc.tomko.outify.data.database.AppDatabase
import cc.tomko.outify.data.database.ShowEntity
import cc.tomko.outify.data.database.show.ShowEpisodeCrossRef
import cc.tomko.outify.data.database.show.ShowWithEpisodes
import cc.tomko.outify.data.database.show.toDomain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class ShowMetadataHelper @Inject constructor(
    private val db: AppDatabase,
    private val showDao: ShowDao,
    private val showEpisodeDao: ShowEpisodeDao,
    private val episodeMetadataHelper: EpisodeMetadataHelper,
    private val nativeMetadata: NativeMetadata,
    private val json: Json,
    @Named("metadataConcurrency") private val concurrency: Int,
) {

    fun observeShows(uris: List<String>): Flow<List<Show>> {
        if (uris.isEmpty()) return flowOf(emptyList())

        val cleanedIds = uris.map { it.removePrefix("spotify:show:") }

        return showDao
            .observeShowsWithEpisodes(cleanedIds)
            .map { entities ->
                val byUri: Map<String, Show> = entities
                    .associateBy { it.show.uri }
                    .mapValues { (_, v) -> v.toDomain() }

                uris.mapNotNull { uri -> byUri[uri] }
            }
            .distinctUntilChanged()
            .flowOn(Dispatchers.IO)
    }

    /**
     * Returns the show with its episodes.
     *
     * Since episodes are dynamic, we always re-fetch the show from native
     * to get the latest episode list, then fetch any new/missing episodes.
     */
    suspend fun getShowMetadata(uri: String): Show? {
        if (uri.isBlank()) return null

        val cleanedId = uri.removePrefix("spotify:show:")

        // Always fetch the show from native to get the latest episode list
        val fetched = try {
            fetchShows(listOf(uri))
        } catch (e: Exception) {
            Log.w("Metadata", "Failed to fetch show $uri", e)
            emptyList()
        }

        if (fetched.isEmpty()) {
            // Fallback to cached if available
            val cached = showDao.getShowWithEpisodes(cleanedId)
            return cached?.toDomain()
        }

        val show = fetched.first()

        // Persist show and cross-refs
        persistShowMetadata(fetched)

        // Now fetch any episodes that are new or missing
        val cachedEpisodeIds = showEpisodeDao.getEpisodeIdsForShow(cleanedId)

        // Fetch episodes that aren't cached yet
        val missingEpisodeUris = show.episodes.filter { epUri ->
            val epId = epUri.removePrefix("spotify:episode:")
            epId !in cachedEpisodeIds
        }

        if (missingEpisodeUris.isNotEmpty()) {
            try {
                episodeMetadataHelper.getEpisodeMetadata(missingEpisodeUris)
            } catch (e: Exception) {
                Log.w("Metadata", "Failed to fetch episodes for show $uri", e)
            }
        }

        // Return the show with episode URIs
        return show
    }

    /**
     * Gets the cover by show ID with given CoverSize.
     * Fetches the show if not cached.
     */
    suspend fun getCoverByShowId(showId: String, size: CoverSize): Cover? {
        if (showId.isBlank()) return null

        val cover = showDao.getCoverUris(showId)
        if (cover != null) {
            val url: String? = when (size) {
                CoverSize.LARGE -> cover.largeCoverUri
                CoverSize.SMALL -> cover.smallCoverUri
                CoverSize.MEDIUM -> cover.mediumCoverUri
            }

            if (url != null)
                return Cover(url, size.asSize(), size.asSize(), size.asInt())
        }

        val showUri = "spotify:show:$showId"
        val fetched = try {
            getShowMetadata(showUri)
        } catch (e: Exception) {
            Log.w("Metadata", "Failed to fetch show for cover retrieval: $showUri", e)
            null
        }

        fetched?.let { show ->
            return show.covers.maxByOrNull { it.width * it.height }
        }

        return null
    }

    /**
     * Gets the cover by episode ID with given CoverSize.
     * Fetches the episode if not cached.
     */
    suspend fun getCoverByEpisodeId(episodeId: String, size: CoverSize): Cover? {
        return episodeMetadataHelper.getCoverByEpisodeId(episodeId, size)
    }

    /**
     * Fetches shows from native source.
     */
    private suspend fun fetchShows(uris: List<String>): List<Show> = supervisorScope {
        if (uris.isEmpty()) return@supervisorScope emptyList()

        val results = mutableListOf<Show>()

        uris.chunked(concurrency).forEach { chunk ->
            val deferred = chunk.map { uri ->
                async {
                    try {
                        val raw = nativeMetadata.retryOnRateLimit {
                            nativeMetadata.fetchMetadata(uri)
                        }
                        json.decodeFromString<Show>(raw.toString())
                    } catch (e: RateLimitException) {
                        Log.w("Metadata", "fetchShows: rate-limited for $uri, giving up", e)
                        null
                    } catch (e: Exception) {
                        Log.e("Metadata", "fetchShows: failed for $uri", e)
                        null
                    }
                }
            }
            results += deferred.awaitAll().filterNotNull()
        }

        results
    }

    /**
     * Persist show metadata and show-episode joins.
     * Episodes are dynamic, so we always replace the cross-refs.
     */
    private suspend fun persistShowMetadata(shows: List<Show>) {
        if (shows.isEmpty()) return

        val now = System.currentTimeMillis()

        val showEntities = mutableListOf<ShowEntity>()
        val showEpisodeJoins = mutableListOf<ShowEpisodeCrossRef>()

        shows.forEach { show ->
            val sortedByArea = show.covers
                .sortedBy { it.width * it.height }

            val small = sortedByArea.firstOrNull()
            val large = sortedByArea.lastOrNull()
            val medium = sortedByArea.getOrNull(sortedByArea.size / 2)

            val keywordsJson = try {
                json.encodeToString(
                    ListSerializer(String.serializer()),
                    show.keywords
                )
            } catch (_: Exception) {
                "[]"
            }

            showEntities += ShowEntity(
                showId = show.id,
                uri = show.uri,
                name = show.name,
                description = show.description,
                publisher = show.publisher,
                language = show.language,
                isExplicit = show.isExplicit,
                mediaType = show.mediaType.name,
                consumptionOrder = show.consumptionOrder.name,
                trailerUri = show.trailerUri,
                hasMusicAndTalk = show.hasMusicAndTalk,
                isAudiobook = show.isAudiobook,
                keywordsJson = keywordsJson,
                smallCoverUri = small?.uri,
                mediumCoverUri = medium?.uri,
                largeCoverUri = large?.uri,
                lastUpdated = now,
            )

            show.episodes.forEachIndexed { index, episodeUri ->
                val episodeId = episodeUri.removePrefix("spotify:episode:")
                showEpisodeJoins += ShowEpisodeCrossRef(
                    showId = show.id,
                    episodeId = episodeId,
                    position = index,
                )
            }
        }

        db.withTransaction {
            val showIds = shows.map { it.id }

            showDao.insertAll(showEntities)

            // Replace joins atomically for the affected shows
            showEpisodeDao.deleteByShowIds(showIds)
            if (showEpisodeJoins.isNotEmpty()) {
                showEpisodeDao.insertAll(showEpisodeJoins)
            }
        }
    }
}
