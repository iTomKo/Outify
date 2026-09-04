package cc.tomko.outify.data.database

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation
import cc.tomko.outify.core.model.Album
import cc.tomko.outify.core.model.ReleaseDate
import cc.tomko.outify.core.model.Track
import cc.tomko.outify.data.database.album.AlbumWithArtists
import cc.tomko.outify.data.database.album.toDomain

data class TrackWithArtists(
    @Embedded val track: TrackEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "artistId",
        associateBy = Junction(
            value = TrackArtistEntity::class,
            parentColumn = "trackId",
            entityColumn = "artistId"
        )
    )
    val artists: List<ArtistEntity>
)

fun TrackWithArtists.toDomain(albumWithArtists: AlbumWithArtists? = null): Track {
    val domainArtists = artists.map { it.toDomain() }

    val albumDomain = albumWithArtists?.toDomain() ?: track.albumId?.let { albumId ->
        Album(
            id = albumId,
            uri = "spotify:album:$albumId",
            name = track.albumName ?: "",
            artists = emptyList(),
            popularity = 0,
            covers = emptyList(),
            date = ReleaseDate()
        )
    }

    return Track(
        id = track.id,
        uri = track.trackUri,
        name = track.name,
        album = albumDomain,
        artists = domainArtists,
        popularity = track.popularity,
        duration = track.durationMs,
        explicit = track.explicit,
        files = emptyList()
    )
}
