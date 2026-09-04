package cc.tomko.outify.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Plain album entity with basic info.
 * Use AlbumWithArtists.kt instead.
 */
@Entity(
    tableName = "albums",
)
data class AlbumEntity(
    @PrimaryKey val albumId: String,
    val uri: String,
    val name: String,
    val artistNames: String,
    val popularity: Int,

    val releaseYear: Int?,
    val releaseMonth: Int?,
    val releaseDay: Int?,

    val lastUpdated: Long,

    // Covers
    val albumCoverBaseUrl: String? = null,
    val smallCoverUri: String?,
    val mediumCoverUri: String?,
    val largeCoverUri: String?,
)