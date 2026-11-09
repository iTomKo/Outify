package cc.tomko.outify.data.metadata

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val TRACK_URI = Regex("spotify:track:[a-zA-Z0-9]{22}")

@Serializable
data class SavedTrack(
    val uri: String,
    @SerialName("added_at") val addedAt: Long? = null,
)

internal fun decodeSavedTracks(raw: String?, json: Json): List<SavedTrack> {
    requireNotNull(raw) { "Saved collection returned no response" }
    val tracks = json.decodeFromString<List<SavedTrack>>(raw)
    require(tracks.all { it.uri.matches(TRACK_URI) }) {
        "Saved collection contains an invalid track URI"
    }
    require(tracks.map { it.uri }.distinct().size == tracks.size) { "Saved collection contains duplicate tracks" }
    return tracks
}
