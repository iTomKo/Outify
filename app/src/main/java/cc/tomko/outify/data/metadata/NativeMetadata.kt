package cc.tomko.outify.data.metadata

import android.util.Log
import kotlinx.coroutines.delay
import org.json.JSONObject
import org.json.JSONArray
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

data class NativeResult(
    val metadata: JSONObject? = null,
    val error: cc.tomko.outify.data.metadata.NativeError? = null
)

@Singleton
class NativeMetadata @Inject constructor() {
    companion object {
        private const val TAG = "NativeMetadata"
    }

    fun fetchMetadata(uri: String): JSONObject {
        val result = getNativeResult(uri)

        result.error?.let { err ->
            NativeErrorHandler.handleError(err, "fetchMetadata:$uri")

            when (err) {
                is cc.tomko.outify.data.metadata.NativeError.RateLimited ->
                    throw RateLimitException(err.message, err.retryAfterSeconds)

                else -> throw NativeOperationException(err.message, err)
            }
        }

        return result.metadata ?: throw Exception("Metadata is null")
    }

    private fun getNativeResult(uri: String): NativeResult {
        val result = getNativeMetadata(uri)

        if (result.startsWith("{")) {
            val obj = JSONObject(result)

            if (obj.has("error")) {
                val err = obj.getJSONObject("error")
                val type = err.getString("type")
                val message = err.getString("message")
                val retryAfter =
                    if (err.isNull("retry_after_seconds")) null else err.getLong("retry_after_seconds")

                val nativeError =
                    cc.tomko.outify.data.metadata.NativeError.fromJson(type, message, retryAfter)
                return NativeResult(metadata = null, error = nativeError)
            }
        }

        if (result.startsWith("{")) {
            try {
                return NativeResult(metadata = JSONObject(result), error = null)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse metadata: $result", e)
            }
        }

        return NativeResult(
            metadata = null,
            error = cc.tomko.outify.data.metadata.NativeError.Unknown("Invalid response: $result")
        )
    }

    /** One native call for up to 500 tracks, not one request per URI. */
    fun fetchTracks(uris: List<String>): String {
        val raw = getNativeTracks(JSONArray(uris).toString())
            ?: throw NativeOperationException("No batch metadata response", NativeError.Unknown("No batch metadata response"))
        if (raw.trimStart().startsWith("{")) {
            val error = JSONObject(raw).getJSONObject("error")
            val nativeError = NativeError.fromJson(
                error.getString("type"),
                error.getString("message"),
                if (error.isNull("retry_after_seconds")) null else error.getLong("retry_after_seconds"),
            )
            when (nativeError) {
                is NativeError.RateLimited -> throw RateLimitException(nativeError.message, nativeError.retryAfterSeconds)
                else -> throw NativeOperationException(nativeError.message, nativeError)
            }
        }
        return raw
    }

    suspend fun <T> retryOnRateLimit(
        maxAttempts: Int = 5,
        baseDelayMs: Long = 1000L,
        block: suspend () -> T
    ): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: RateLimitException) {
                attempt++
                if (attempt >= maxAttempts) throw e

                val backoffMs =
                    e.retryAfterSeconds?.times(1000L) ?: (baseDelayMs * (1L shl (attempt - 1)))
                val jitter = Random.nextLong(0, (backoffMs / 3).coerceAtLeast(1L))
                val delayMs = backoffMs + jitter

                delay(delayMs)
            }
        }
    }

    external fun getNativeMetadata(uri: String): String
    private external fun getNativeTracks(urisJson: String): String?
}

class NativeOperationException(
    message: String,
    val error: cc.tomko.outify.data.metadata.NativeError
) : Exception(message)
