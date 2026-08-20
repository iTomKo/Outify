package cc.tomko.outify.data.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "episodes",
    indices = [
        Index("episodeId"),
        Index("lastAccessed"),
        Index("lastUpdated"),
    ]
)
data class EpisodeEntity(
    @PrimaryKey val episodeId: String,
    val uri: String,
    val name: String,
    val duration: Long,
    val description: String,
    val number: Int,
    val publishTime: Long,
    val language: String,
    val isExplicit: Boolean,
    val showName: String,
    val showUri: String = "",
    val allowBackgroundPlayback: Boolean,
    val externalUrl: String,
    val episodeType: String,
    val hasMusicAndTalk: Boolean,
    val isAudiobookChapter: Boolean,
    val keywordsJson: String,

    val smallCoverUri: String?,
    val mediumCoverUri: String?,
    val largeCoverUri: String?,

    var isLibraryItem: Boolean,
    val lastAccessed: Long,
    val lastUpdated: Long,
    val fullyPlayed: Boolean = false,
    val resumePositionMs: Long = 0,
)
