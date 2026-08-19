package cc.tomko.outify.data.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "shows",
    indices = [
        Index("showId"),
        Index("lastUpdated"),
    ]
)
data class ShowEntity(
    @PrimaryKey val showId: String,
    val uri: String,
    val name: String,
    val description: String,
    val publisher: String,
    val language: String,
    val isExplicit: Boolean,
    val mediaType: String,
    val consumptionOrder: String,
    val trailerUri: String?,
    val hasMusicAndTalk: Boolean,
    val isAudiobook: Boolean,
    val keywordsJson: String,

    val smallCoverUri: String?,
    val mediumCoverUri: String?,
    val largeCoverUri: String?,

    val lastUpdated: Long,
)
