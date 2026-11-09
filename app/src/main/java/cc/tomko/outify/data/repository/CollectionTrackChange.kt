package cc.tomko.outify.data.repository

import cc.tomko.outify.data.database.track.LikedTrackEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val TRACK_URI = Regex("spotify:track:[a-zA-Z0-9]{22}")

@Serializable
data class CollectionTrackChange(val uri: String, val removed: Boolean)

@Serializable
data class CollectionDelta(val session: CollectionSession, val changes: List<CollectionTrackChange>)

internal fun decodeCollectionDelta(raw: String, json: Json): CollectionDelta =
    json.decodeFromString<CollectionDelta>(raw).also { delta ->
        delta.session.validate()
        require(delta.changes.all { it.uri.matches(TRACK_URI) }) { "Invalid collection delta URI" }
    }

internal fun decodeCollectionChanges(raw: String, json: Json): List<CollectionTrackChange> =
    json.decodeFromString<List<CollectionTrackChange>>(raw).also { changes ->
        require(changes.all { it.uri.matches(TRACK_URI) }) {
            "Invalid collection delta URI"
        }
    }

internal fun applyTrackChanges(
    cached: List<LikedTrackEntity>,
    changes: List<CollectionTrackChange>,
    now: Long,
): SavedTrackReconciliation {
    val before = cached.associateBy { it.trackId }
    val after = before.toMutableMap()
    var firstPosition = cached.minOfOrNull { it.position } ?: 0.0
    for (change in changes) {
        val id = change.uri.removePrefix("spotify:track:")
        if (change.removed) after.remove(id)
        else if (id !in after) {
            firstPosition -= 1.0
            after[id] = LikedTrackEntity(id, firstPosition, now)
        }
    }
    val upserts = after.values.filter { before[it.trackId] != it }
    return SavedTrackReconciliation(
        upserts = upserts,
        removedIds = before.keys.filterNot(after::containsKey),
        addedUris = upserts.map { "spotify:track:${it.trackId}" },
    )
}
