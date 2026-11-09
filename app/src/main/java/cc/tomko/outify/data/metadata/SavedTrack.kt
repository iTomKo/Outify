package cc.tomko.outify.data.metadata

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import cc.tomko.outify.data.repository.CollectionSession

private val TRACK_URI = Regex("spotify:track:[a-zA-Z0-9]{22}")

@Serializable
data class SavedTrack(
    val uri: String,
    @SerialName("added_at") val addedAt: Long? = null,
)

@Serializable
data class CollectionSnapshot(val session: CollectionSession, val tracks: List<SavedTrack>)

internal fun decodeCollectionSnapshot(raw: String?, json: Json): CollectionSnapshot {
    requireNotNull(raw) { "Saved collection returned no response" }
    val snapshot = json.decodeFromString<CollectionSnapshot>(raw)
    snapshot.session.validate()
    validateTracks(snapshot.tracks)
    return snapshot
}

internal fun decodeSavedTracks(raw: String?, json: Json): List<SavedTrack> {
    requireNotNull(raw) { "Saved collection returned no response" }
    val tracks = json.decodeFromString<List<SavedTrack>>(raw)
    validateTracks(tracks)
    return tracks
}

private fun validateTracks(tracks: List<SavedTrack>) {
    require(tracks.all { it.uri.matches(TRACK_URI) }) {
        "Saved collection contains an invalid track URI"
    }
    require(tracks.map { it.uri }.distinct().size == tracks.size) { "Saved collection contains duplicate tracks" }
}
