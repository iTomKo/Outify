package cc.tomko.outify.data.remote

import android.util.Log
import cc.tomko.outify.BuildConfig
import cc.tomko.outify.core.model.LyricLine
import cc.tomko.outify.core.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.roundToInt

private const val TAG = "LrcLibClient"
private const val LRCLIB_HOST = "lrclib.net"
private const val CALL_TIMEOUT_SECONDS = 6L

/** Candidates whose duration differs more than this from the track are discarded. */
internal const val LRCLIB_DURATION_TOLERANCE_SECONDS = 5

/**
 * One row of the LRCLIB API (https://lrclib.net/docs).
 */
@Serializable
data class LrcLibTrack(
    val duration: Double? = null,
    val instrumental: Boolean = false,
    val syncedLyrics: String? = null,
)

/**
 * Fallback lyrics provider used when Spotify has no lyrics for a track.
 *
 * Lookup order: exact match on `/api/get` (title, artist, album, duration), then a
 * `/api/search` by title and artist filtered by duration. Only synced lyrics are returned.
 */
@Singleton
class LrcLibClient @Inject constructor(
    private val json: Json,
) {
    private val client = OkHttpClient.Builder()
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val userAgent = "Outify/${BuildConfig.VERSION_NAME} (https://github.com/iTomKo/Outify)"

    suspend fun getLyrics(track: Track): List<LyricLine> = withContext(Dispatchers.IO) {
        val title = track.name.trim()
        val artist = track.artists.firstOrNull()?.name?.trim().orEmpty()
        if (title.isEmpty()) return@withContext emptyList()

        val durationSeconds = (track.duration / 1000.0).roundToInt()

        try {
            val exact = fetchExact(title, artist, track.album?.name, durationSeconds)
            resolveLrcLib(exact, durationSeconds) { search(title, artist) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "LRCLIB lookup failed", e)
            emptyList()
        }
    }

    /** `null` means the API answered 404 (no exact match). Network failures throw. */
    private fun fetchExact(title: String, artist: String, album: String?, durationSeconds: Int): LrcLibTrack? {
        val url = baseUrl("api/get")
            .addQueryParameter("track_name", title)
            .addQueryParameter("artist_name", artist)
            .apply { if (!album.isNullOrBlank()) addQueryParameter("album_name", album) }
            .addQueryParameter("duration", durationSeconds.toString())
            .build()

        client.newCall(request(url)).execute().use { response ->
            if (response.code == 404) return null
            if (!response.isSuccessful) throw IOException("LRCLIB get returned HTTP ${response.code}")
            val body = response.body?.string() ?: throw IOException("LRCLIB get returned no body")
            return json.decodeFromString<LrcLibTrack>(body)
        }
    }

    private fun search(title: String, artist: String): List<LrcLibTrack> {
        val url = baseUrl("api/search")
            .addQueryParameter("track_name", title)
            .apply { if (artist.isNotEmpty()) addQueryParameter("artist_name", artist) }
            .build()

        client.newCall(request(url)).execute().use { response ->
            if (response.code == 404) return emptyList()
            if (!response.isSuccessful) throw IOException("LRCLIB search returned HTTP ${response.code}")
            val body = response.body?.string() ?: return emptyList()
            return json.decodeFromString<List<LrcLibTrack>>(body)
        }
    }

    private fun baseUrl(path: String): HttpUrl.Builder =
        HttpUrl.Builder()
            .scheme("https")
            .host(LRCLIB_HOST)
            .addPathSegments(path)

    private fun request(url: HttpUrl): Request =
        Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")
            .get()
            .build()
}

/**
 * Picks the search result with usable synced lyrics closest in duration, within
 * [LRCLIB_DURATION_TOLERANCE_SECONDS]. Instrumental and unsynced rows are skipped.
 */
internal fun pickBestMatch(candidates: List<LrcLibTrack>, durationSeconds: Int): LrcLibTrack? =
    candidates
        .asSequence()
        .filter { it.syncedLines().isNotEmpty() }
        .map { it to abs((it.duration ?: 0.0).roundToInt() - durationSeconds) }
        .filter { (_, diff) -> diff <= LRCLIB_DURATION_TOLERANCE_SECONDS }
        .minByOrNull { (_, diff) -> diff }
        ?.first

/** Uses the exact match when it has synced lyrics, otherwise searches once. */
internal suspend fun resolveLrcLib(
    exact: LrcLibTrack?,
    durationSeconds: Int,
    search: suspend () -> List<LrcLibTrack>,
): List<LyricLine> {
    if (exact?.instrumental == true) return emptyList()

    val lines = exact?.syncedLines().orEmpty()
    if (lines.isNotEmpty()) return lines

    return pickBestMatch(search(), durationSeconds)?.syncedLines().orEmpty()
}

internal fun LrcLibTrack.syncedLines(): List<LyricLine> {
    if (instrumental) return emptyList()
    return syncedLyrics?.takeIf { it.isNotBlank() }?.let(LrcParser::parseSynced).orEmpty()
}

/**
 * Parses LRC text (`[mm:ss.xx]` or `[mm:ss.xxx]` prefixes, several per line allowed).
 */
object LrcParser {
    private val timestamp = Regex("""\[(\d+):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    fun parseSynced(text: String): List<LyricLine> {
        val lines = mutableListOf<LyricLine>()
        text.lineSequence().forEach { rawLine ->
            val stamps = timestamp.findAll(rawLine).toList()
            if (stamps.isEmpty()) return@forEach

            val words = rawLine.substring(stamps.last().range.last + 1).trim()
            if (words.isEmpty()) return@forEach

            stamps.forEach { match ->
                val minutes = match.groupValues[1].toLong()
                val seconds = match.groupValues[2].toLong()
                val fraction = match.groupValues[3]
                val millis = when (fraction.length) {
                    0 -> 0L
                    1 -> fraction.toLong() * 100
                    2 -> fraction.toLong() * 10
                    else -> fraction.take(3).toLong()
                }
                lines += LyricLine(
                    timestampMs = minutes * 60_000 + seconds * 1_000 + millis,
                    text = words,
                )
            }
        }
        return lines.sortedBy { it.timestampMs }
    }
}
