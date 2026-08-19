package cc.tomko.outify.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import cc.tomko.outify.data.database.show.ShowEpisodeCrossRef
import javax.inject.Singleton

@Dao
@Singleton
interface ShowEpisodeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<ShowEpisodeCrossRef>)

    @Query("DELETE FROM show_episodes WHERE showId = :showId")
    suspend fun deleteByShowId(showId: String)

    @Query("DELETE FROM show_episodes WHERE showId IN (:showIds)")
    suspend fun deleteByShowIds(showIds: List<String>)

    @Query("SELECT episodeId FROM show_episodes WHERE showId = :showId ORDER BY position ASC")
    suspend fun getEpisodeIdsForShow(showId: String): List<String>
}
