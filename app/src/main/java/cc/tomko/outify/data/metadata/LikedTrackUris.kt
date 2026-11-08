package cc.tomko.outify.data.metadata

import kotlinx.serialization.json.Json

/** A failed/partial response must never be interpreted as an empty library. */
internal fun decodeLikedTrackUris(raw: String?, json: Json): List<String> {
    checkNotNull(raw) { "No response while fetching saved tracks" }
    val uris = json.decodeFromString<List<String>>(raw)
    require(uris.all { it.startsWith("spotify:track:") && it.substringAfterLast(':').isNotBlank() }) {
        "Saved track response contains invalid URIs"
    }
    return uris.distinct()
}
