package cc.tomko.outify.reccobeats

import android.util.Log
import androidx.annotation.FloatRange
import androidx.annotation.IntRange
import androidx.annotation.Size
import cc.tomko.outify.core.model.OutifyUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton


@Serializable
private data class RecommendationResponse(
    val content: List<RecommendationTrack>
)

@Serializable
private data class RecommendationTrack(
    val href: String,
) {
    fun getTrackId(): String =
        href.substringAfterLast("/")
}

@Serializable
data class RecommendationConfig(
    val acousticness: Float? = null,
    val danceability: Float? = null,
    val energy: Float? = null,
    val instrumentalness: Float? = null,
    val liveness: Float? = null,
    val loudness: Float? = null,
    val speechiness: Float? = null,
    val tempo: Float? = null,
    val valence: Float? = null,
    val featureWeight: Float? = null,
)


@Singleton
class Recommendations @Inject constructor(
    val json: Json,
) {
    val client = OkHttpClient()

    suspend fun fetchRecommendations(@IntRange(from = 1, to = 100) size: Int, @Size(min = 1, max = 5) seeds: Array<String>, config: RecommendationConfig): List<String> {
        return fetchRecommendations(size, seeds, config.acousticness, config.danceability, config.energy, config.instrumentalness, null, config.liveness, config.loudness, null, config.speechiness, config.tempo, config.valence, null, config.featureWeight)
    }

    /**
     * https://reccobeats.com/docs/apis/get-recommendation
     * @param size how many tracks to return
     * @param seeds IDs of tracks to seed by
     */
    suspend fun fetchRecommendations(@IntRange(from = 1, to = 100) size: Int,
                                     @Size(min = 1, max = 5) seeds: Array<String>,

                                     @FloatRange(from = 0.0, to = 1.0) acousticness: Float? = null,
                                     @FloatRange(from = 0.0, to = 1.0) danceability: Float? = null,
                                     @FloatRange(from = 0.0, to = 1.0) energy: Float? = null,
                                     @FloatRange(from = 0.0, to = 1.0) instrumentalness: Float? = null,
                                     @IntRange(from = -1, to = 11) key: Int? = null,
                                     @FloatRange(from = 0.0, to = 1.0) liveness: Float? = null,
                                     @FloatRange(from = -60.0, to = 2.0) loudness: Float? = null,
                                     mode: Mode? = null,
                                     @FloatRange(from = 0.0, to = 1.0) speechiness: Float? = null,
                                     @FloatRange(from = 0.0, to = 250.0) tempo: Float? = null,
                                     @FloatRange(from = 0.0, to = 1.0) valence: Float? = null,
                                     @IntRange(from = 0, to = 100) popularity: Int? = null,
                                     @FloatRange(from = 1.0, to = 5.0) featureWeight: Float? = null,
    ): List<String> = withContext(Dispatchers.IO) {
        require(seeds.size in 1..5)

        val url = HttpUrl.Builder()
            .scheme("https")
            .host("api.reccobeats.com")
            .addPathSegments("v1/track/recommendation")
            .addQueryParameter("size", size.toString())
            .addQueryParameter("seeds", seeds.joinToString(","))
            .param("acousticness", acousticness)
            .param("danceability", danceability)
            .param("energy", energy)
            .param("instrumentalness", instrumentalness)
            .param("key", key)
            .param("liveness", liveness)
            .param("loudness", loudness)
            .param("mode", mode?.value)
            .param("speechiness", speechiness)
            .param("tempo", tempo)
            .param("valence", valence)
            .param("popularity", popularity)
            .param("featureWeight", featureWeight)
            .build()

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        client.newCall(request).execute().use { resp ->
            if(!resp.isSuccessful) {
                Log.w("Recommendations", "Request failed with status code: ${resp.code}")
                return@withContext emptyList()
            }

            val body = resp.body?.string()
            if(body == null) {
                Log.w("Recommendations", "Response body is empty!")
                return@withContext emptyList()
            }

            val data = json.decodeFromString<RecommendationResponse>(body)
            data.content.map { it.getTrackId() }
        }
    }
}

sealed class Mode(val value: Int) {
    object Minor : Mode(0)
    object Major : Mode(1)
}

fun HttpUrl.Builder.param(name: String, value: Any?) = apply {
    when (value) {
        null -> Unit
        else -> addQueryParameter(name, value.toString())
    }
}