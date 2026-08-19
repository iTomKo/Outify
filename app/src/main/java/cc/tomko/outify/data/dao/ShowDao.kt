package cc.tomko.outify.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import cc.tomko.outify.data.database.ShowEntity
import cc.tomko.outify.data.database.show.ShowWithEpisodes
import kotlinx.coroutines.flow.Flow
import javax.inject.Singleton

@Dao
@Singleton
interface ShowDao {
    data class CoverUris(
        val smallCoverUri: String?,
        val mediumCoverUri: String?,
        val largeCoverUri: String?,
    )

    @Query("SELECT smallCoverUri, mediumCoverUri, largeCoverUri FROM shows WHERE showId = :showId LIMIT 1")
    suspend fun getCoverUris(showId: String): CoverUris?

    @Transaction
    @Query("SELECT * FROM shows WHERE showId IN (:showIds)")
    fun observeShowsWithEpisodes(showIds: List<String>): Flow<List<ShowWithEpisodes>>

    @Transaction
    @Query("SELECT * FROM shows WHERE showId = :showId")
    suspend fun getShowWithEpisodes(showId: String): ShowWithEpisodes?

    @Transaction
    @Query("SELECT * FROM shows WHERE showId IN (:showIds)")
    suspend fun getShowsWithEpisodes(showIds: List<String>): List<ShowWithEpisodes>

    @Query("SELECT episodeId FROM show_episodes WHERE showId = :showId ORDER BY position ASC")
    suspend fun getEpisodeIdsForShow(showId: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(shows: List<ShowEntity>)
}
