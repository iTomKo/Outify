package cc.tomko.outify.data.database.show

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "show_episodes",
    primaryKeys = ["showId", "episodeId"],
    indices = [Index("showId"), Index("episodeId")]
)
data class ShowEpisodeCrossRef(
    val showId: String,
    val episodeId: String,
    val position: Int,
)
