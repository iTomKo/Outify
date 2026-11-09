package cc.tomko.outify.data.database.track

import androidx.room.Entity
import androidx.room.PrimaryKey

/** One account owns the membership cache; track metadata remains shared. */
@Entity(tableName = "collection_owner")
data class CollectionOwnerEntity(
    @PrimaryKey val slot: Int = 1,
    val accountId: String,
    val sessionId: String,
    val generation: Long,
    val active: Boolean,
)
