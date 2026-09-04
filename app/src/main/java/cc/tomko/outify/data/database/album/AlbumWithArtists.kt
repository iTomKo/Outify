package cc.tomko.outify.data.database.album

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation
import cc.tomko.outify.core.model.Album
import cc.tomko.outify.core.model.Cover
import cc.tomko.outify.core.model.CoverSize
import cc.tomko.outify.core.model.ReleaseDate
import cc.tomko.outify.core.model.asInt
import cc.tomko.outify.data.database.AlbumEntity
import cc.tomko.outify.data.database.ArtistEntity
import cc.tomko.outify.data.database.toDomain

data class AlbumWithArtists(
    @Embedded val album: AlbumEntity,
    @Relation(
        parentColumn = "albumId",
        entityColumn = "artistId",
        associateBy = Junction(AlbumArtistEntity::class)
    )
    val artists: List<ArtistEntity>
)

fun AlbumWithArtists.toDomain(): Album {
    val domainArtists = artists.map { it.toDomain() }

    val covers = listOfNotNull(
        album.smallCoverUri?.takeIf { it.isNotBlank() }?.let {
            Cover(
                uri = it, size = CoverSize.SMALL.asInt(),
                width = 64,
                height = 64
            )
        },
        album.mediumCoverUri?.takeIf { it.isNotBlank() }?.let {
            Cover(
                uri = it, size = CoverSize.MEDIUM.asInt(),
                width = 300,
                height = 300
            )
        },
        album.largeCoverUri?.takeIf { it.isNotBlank() }?.let {
            Cover(
                uri = it, size = CoverSize.LARGE.asInt(),
                width = 640,
                height = 640
            )
        }
    )

    return Album(
        id = album.albumId,
        uri = album.uri,
        name = album.name,
        artists = domainArtists,
        popularity = album.popularity,
        covers = covers,
        date = ReleaseDate(
            year = album.releaseYear,
            month = album.releaseMonth,
            day = album.releaseDay,
        )
    )
}
