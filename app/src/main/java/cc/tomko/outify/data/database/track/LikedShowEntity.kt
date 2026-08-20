package cc.tomko.outify.data.database.track

import androidx.room.Entity

@Entity(
    tableName = "liked_shows",
    primaryKeys = ["showId"]
)
data class LikedShowEntity(
    val showId: String,
    val position: Double,
    val addedAt: Long,
)
