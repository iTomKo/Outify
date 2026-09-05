package cc.tomko.outify.core.model

import cc.tomko.outify.utils.SharedElementKey
import kotlinx.serialization.Serializable

/**
 * Contains information about Album.
 * Fields sourced from JSON from FFI
 */
@Serializable
data class Album(
    val id: String,
    val uri: String,
    val name: String,
    val artists: List<Artist> = emptyList(),
    val popularity: Int = 0,
    /**
     * This list contains just the SpotifyUris of all tracks within the album
     */
    val tracks: List<String> = emptyList(),
    val covers: List<Cover> = emptyList(),
    val date: ReleaseDate,
)

fun Album.getCover(size: CoverSize): Cover? {
    return covers.firstOrNull { it.size == size.asInt() }
}

fun Album.sharedTransitionKey(): String {
    return "${SharedElementKey.ALBUM_ARTWORK}_$id"
}

fun Album.toSpotifyUri(): SpotifyUri =
    SpotifyUri.Album(id)

fun Album.toOutifyUri(): OutifyUri =
    OutifyUri.fromUriString(uri)
