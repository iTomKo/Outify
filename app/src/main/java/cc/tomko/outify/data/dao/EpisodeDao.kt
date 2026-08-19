package cc.tomko.outify.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import cc.tomko.outify.data.database.EpisodeEntity
import javax.inject.Singleton

@Dao
@Singleton
interface EpisodeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(episodes: List<EpisodeEntity>)

    @Query("SELECT * FROM episodes WHERE episodeId = :episodeId LIMIT 1")
    suspend fun getEpisodeById(episodeId: String): EpisodeEntity?

    @Query(
        """
        SELECT e.* FROM episodes e
        INNER JOIN show_episodes se ON e.episodeId = se.episodeId
        WHERE se.showId = :showId
        ORDER BY se.position ASC
    """
    )
    suspend fun getEpisodesForShow(showId: String): List<EpisodeEntity>

    @Query("DELETE FROM episodes WHERE episodeId IN (:episodeIds)")
    suspend fun deleteByIds(episodeIds: List<String>)
}
