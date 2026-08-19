package cc.tomko.outify.core.model

import androidx.compose.runtime.Immutable
import cc.tomko.outify.data.database.ArtistEntity
import cc.tomko.outify.data.database.TrackArtistEntity
import cc.tomko.outify.data.database.TrackEntity
import cc.tomko.outify.utils.canonicalIdFromUri
import kotlinx.serialization.Serializable
import kotlin.math.exp

/**
 * Contains information about single Track.
 * Data sourced from JSON from FFI
 */
@Serializable
@Immutable
data class Track(
    val id: String,
    val uri: String,
    val name: String,
    val album: Album? = null,
    val artists: List<Artist> = emptyList(),
    val popularity: Int = 0,
    val duration: Long = 0,
    val explicit: Boolean = false,
    val files: List<FileId>,
) {
    companion object {
        fun dummy(): Track {
            val artists = listOf(
                Artist(
                    id = "6xBZgSMsnKVmaAxzWEwMSD",
                    uri = "spotify:artist:6xBZgSMsnKVmaAxzWEwMSD",
                    name = "Mike Shinoda",
                    popularity = 0
                )
            )

            return Track(
                "6D62fCTKuWTtO4WhRw1RvT",
                "spotify:track:6D62fCTKuWTtO4WhRw1RvT",
                name = "Prove You Wrong - Remastered",
                album = Album(
                    id = "6raEoLfzOskhSksjJIHjA3",
                    uri = "spotify:album:6raEoLfzOskhSksjJIHjA3",
                    name = "Post Traumatic (Deluxe Remastered Version)",
                    artists = artists,
                    popularity = 0,
                    tracks = listOf("6D62fCTKuWTtO4WhRw1RvT"),
                    covers = listOf(
                        Cover(
                            "ab67616d000048518c03cf97818d390d723ef1a9", CoverSize.SMALL.asSize(),
                            CoverSize.SMALL.asSize(), CoverSize.SMALL.asInt()
                        ),
                        Cover(
                            "ab67616d000048518c03cf97818d390d723ef1a9", CoverSize.MEDIUM.asSize(),
                            CoverSize.MEDIUM.asSize(), CoverSize.MEDIUM.asInt()
                        ),
                        Cover(
                            "ab67616d000048518c03cf97818d390d723ef1a9", CoverSize.LARGE.asSize(),
                            CoverSize.LARGE.asSize(), CoverSize.LARGE.asInt()
                        ),
                    )
                ),
                artists = artists,
                popularity = 0,
                duration = 12345,
                explicit = false,
                files = emptyList(),
            )
        }
    }
}

fun Track.getFileId(format: FileType = FileType.OGG_VORBIS_320): String =
    files.first { it.type == format }.id

fun Track.toEntities(now: Long): Triple<TrackEntity, List<ArtistEntity>, List<TrackArtistEntity>> {
    val canonicalId = canonicalIdFromUri(id.ifBlank { uri })

    val trackEntity = TrackEntity(
        id = canonicalId,
        trackUri = uri,
        name = name,
        albumId = album?.id,
        albumName = album?.name,
        durationMs = duration,
        explicit = explicit,
        popularity = popularity,
        isLibraryItem = true,
        lastAccessed = now,
        lastUpdated = now
    )

    val artistEntities = artists.map {
        ArtistEntity(
            artistId = canonicalIdFromUri(it.id.ifBlank { it.uri }),
            name = it.name,
            imageUrl = "", // TODO: Add the image urls
            lastUpdated = now,
            uri = "spotify:artist:" + it.id,
            popularity = popularity
        )
    }

    val joins = artists.mapIndexed { index, artist ->
        TrackArtistEntity(
            trackId = canonicalId,
            artistId = artist.id,
            position = index
        )
    }

    return Triple(trackEntity, artistEntities, joins)
}

fun Track.toPlayableAudio() =
    PlayableAudio(
        id = id,
        uri = uri,
        name = name,
        covers = album?.covers ?: emptyList(),
        duration = duration,
        explicit = explicit,
        artists = artists,
        sourceTrack = this
    )

fun Track.toSpotifyUri(): SpotifyUri =
    SpotifyUri.Track(id)

fun Track.toOutifyUri(): OutifyUri =
    OutifyUri.fromUriString(uri)