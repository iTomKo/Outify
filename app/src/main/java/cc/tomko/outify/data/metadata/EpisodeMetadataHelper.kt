package cc.tomko.outify.data.metadata

import android.util.Log
import cc.tomko.outify.core.model.Cover
import cc.tomko.outify.core.model.CoverSize
import cc.tomko.outify.core.model.Episode
import cc.tomko.outify.core.model.asInt
import cc.tomko.outify.core.model.asSize
import cc.tomko.outify.data.dao.EpisodeDao
import cc.tomko.outify.data.database.EpisodeEntity
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
class EpisodeMetadataHelper @Inject constructor(
    private val episodeDao: EpisodeDao,
    private val nativeMetadata: NativeMetadata,
    private val json: Json,
    @Named("metadataConcurrency") private val concurrency: Int,
) {

    fun observeEpisodes(uris: List<String>): Flow<List<Episode>> {
        if (uris.isEmpty()) return flowOf(emptyList())

        val cleanedIds = uris.map { it.removePrefix("spotify:episode:") }

        return episodeDao
            .observeEpisodesByIds(cleanedIds)
            .map { entities ->
                val byUri: Map<String, Episode> = entities
                    .associateBy { it.uri }
                    .mapValues { (_, v) -> v.toDomain() }

                uris.mapNotNull { uri -> byUri[uri] }
            }
            .distinctUntilChanged()
            .flowOn(Dispatchers.IO)
    }

    suspend fun getEpisodeMetadata(uris: List<String>): List<Episode> {
        if (uris.isEmpty()) return emptyList()
        val filtered = uris.filter { it.startsWith("spotify:episode:") }

        var cached = loadCached(filtered)

        val missing = filtered.filterNot { cached.containsKey(it) }
        if (missing.isNotEmpty()) {
            try {
                fetchAndPersist(missing)
                cached = loadCached(filtered)
            } catch (e: Exception) {
                Log.w("Metadata", "Failed to fetch missing episodes", e)
            }
        }

        return filtered.mapNotNull { uri ->
            cached[uri]?.let { entity ->
                try {
                    entity.toDomain()
                } catch (e: Exception) {
                    Log.w("Metadata", "Failed to map episode $uri", e)
                    null
                }
            }
        }
    }

    suspend fun getEpisodeMetadata(uri: String): Episode? {
        if (!uri.startsWith("spotify:episode:")) return null

        val cached = loadCached(listOf(uri))
        if (cached.containsKey(uri)) {
            return cached[uri]?.toDomain()
        }

        return try {
            val fetched = fetchAndPersist(listOf(uri))
            fetched[uri]?.toDomain()
        } catch (e: Exception) {
            Log.w("Metadata", "Failed to fetch episode $uri", e)
            null
        }
    }

    suspend fun getCoverByEpisodeId(episodeId: String, size: CoverSize): Cover? {
        if (episodeId.isBlank()) return null

        val episode = episodeDao.getEpisodeById(episodeId)
        if (episode != null) {
            val url: String? = when (size) {
                CoverSize.LARGE -> episode.largeCoverUri
                CoverSize.SMALL -> episode.smallCoverUri
                CoverSize.MEDIUM -> episode.mediumCoverUri
            }

            if (url != null)
                return Cover(url, size.asSize(), size.asSize(), size.asInt())
        }

        val episodeUri = "spotify:episode:$episodeId"
        val fetched = try {
            fetchAndPersist(listOf(episodeUri))
            loadCached(listOf(episodeUri))[episodeUri]?.toDomain()
        } catch (e: Exception) {
            Log.w("Metadata", "Failed to fetch episode for cover: $episodeUri", e)
            null
        }

        return fetched?.covers?.maxByOrNull { it.width * it.height }
    }

    private suspend fun fetchAndPersist(uris: List<String>): Map<String, EpisodeEntity> {
        if (uris.isEmpty()) return emptyMap()

        val fetched = fetchEpisodes(uris)
        if (fetched.isNotEmpty()) {
            persistEpisodes(fetched)
        }

        return loadCached(uris)
    }

    private suspend fun fetchEpisodes(uris: List<String>): List<Episode> = supervisorScope {
        if (uris.isEmpty()) return@supervisorScope emptyList()

        val results = mutableListOf<Episode>()

        uris.chunked(concurrency).forEach { chunk ->
            val deferred = chunk.map { uri ->
                async {
                    try {
                        val raw = nativeMetadata.retryOnRateLimit {
                            nativeMetadata.fetchMetadata(uri)
                        }
                        json.decodeFromString<Episode>(raw.toString())
                    } catch (e: RateLimitException) {
                        Log.w("Metadata", "fetchEpisodes: rate-limited for $uri, giving up", e)
                        null
                    } catch (e: Exception) {
                        Log.e("Metadata", "fetchEpisodes: failed for $uri", e)
                        null
                    }
                }
            }
            results += deferred.awaitAll().filterNotNull()
        }

        results
    }

    private suspend fun loadCached(uris: List<String>): Map<String, EpisodeEntity> {
        if (uris.isEmpty()) return emptyMap()
        val ids = uris.map { it.removePrefix("spotify:episode:") }
        return episodeDao.getEpisodesByIds(ids).associateBy { it.uri }
    }

    internal suspend fun persistEpisodes(episodes: List<Episode>) {
        if (episodes.isEmpty()) return

        val now = System.currentTimeMillis()

        val episodeEntities = episodes.map { episode ->
            val sortedByArea = episode.covers
                .sortedBy { it.width * it.height }

            val small = sortedByArea.firstOrNull()
            val large = sortedByArea.lastOrNull()
            val medium = sortedByArea.getOrNull(sortedByArea.size / 2)

            val keywordsJson = try {
                json.encodeToString(
                    ListSerializer(String.serializer()),
                    episode.keywords
                )
            } catch (_: Exception) {
                "[]"
            }

            EpisodeEntity(
                episodeId = episode.id,
                uri = episode.uri,
                name = episode.name,
                duration = episode.duration,
                description = episode.description,
                number = episode.number,
                publishTime = episode.publishTime,
                language = episode.language,
                isExplicit = episode.isExplicit,
                showName = episode.showName,
                showUri = episode.showUri,
                allowBackgroundPlayback = episode.allowBackgroundPlayback,
                externalUrl = episode.externalUrl,
                episodeType = episode.episodeType.name,
                hasMusicAndTalk = episode.hasMusicAndTalk,
                isAudiobookChapter = episode.isAudiobookChapter,
                keywordsJson = keywordsJson,
                smallCoverUri = small?.uri,
                mediumCoverUri = medium?.uri,
                largeCoverUri = large?.uri,
                isLibraryItem = false,
                lastAccessed = now,
                lastUpdated = now,
            )
        }

        episodeDao.insertAll(episodeEntities)
    }
}
