package cc.tomko.outify.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import cc.tomko.outify.data.database.EpisodeEntity
import cc.tomko.outify.data.database.LikedTrackWithTrack
import cc.tomko.outify.data.database.ShowEntity
import cc.tomko.outify.data.database.TrackWithArtists
import cc.tomko.outify.data.database.track.LikedEpisodeEntity
import cc.tomko.outify.data.database.track.LikedShowEntity
import cc.tomko.outify.data.database.track.LikedTrackEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Singleton

@Dao
@Singleton
interface LikedDao {
    @Transaction
    @Query("SELECT * FROM liked_songs ORDER BY position ASC")
    fun observeLikedTracks(): Flow<List<LikedTrackWithTrack>>

    @Query("SELECT EXISTS(SELECT 1 FROM liked_songs WHERE trackId = :id)")
    suspend fun containsTrack(id: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM liked_songs WHERE trackId = :id)")
    fun observeIsTrackLiked(id: String): Flow<Boolean>

    @Transaction
    @Query(
        """
        SELECT * FROM tracks
        WHERE id IN (:trackIds)
    """
    )
    suspend fun getTracksWithArtists(trackIds: List<String>): List<TrackWithArtists>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(track: LikedTrackEntity)

    @Query("DELETE FROM liked_songs WHERE trackId = :id")
    suspend fun delete(id: String): Int

    @Query("UPDATE liked_songs SET position = position + 1 WHERE position >= :fromPosition")
    suspend fun shiftPositions(fromPosition: Double)

    @Query("UPDATE liked_songs SET position = position - 1 WHERE position > :fromPosition")
    suspend fun shiftPositionsDown(fromPosition: Double)

    @Query("UPDATE liked_songs SET position = :newPosition WHERE trackId = :id")
    suspend fun updatePosition(id: String, newPosition: Double)

    /** Ordered URI snapshot — used for sync comparison */
    @Query("SELECT trackId FROM liked_songs ORDER BY position ASC")
    suspend fun getLikedIds(): List<String>

    /** Total count, even before metadata loads */
    @Query("SELECT COUNT(*) FROM liked_songs")
    fun observeCount(): Flow<Int>

    /** Total count (suspend version for sync progress) */
    @Query("SELECT COUNT(*) FROM liked_songs")
    suspend fun getCount(): Int

    /** Wipe all cached positions (used during full re-sync) */
    @Query("DELETE FROM liked_songs")
    suspend fun clearAll()

    /** Id window for triggering metadata fetch */
    @Query("SELECT trackId FROM liked_songs ORDER BY position ASC LIMIT :limit OFFSET :offset")
    suspend fun getIdsWindow(limit: Int, offset: Int): List<String>

    /**
     * Inner-joins liked_songs with tracks — only returns rows where metadata exists.
     */
    @Transaction
    @Query(
        """
        SELECT t.* FROM liked_songs ls
        INNER JOIN tracks t ON ls.trackId = t.id
        ORDER BY ls.position ASC
    """
    )
    fun observeLikedTracksWithDetails(): Flow<List<TrackWithArtists>>

    @Transaction
    @Query(
        """
        SELECT DISTINCT t.*
        FROM liked_songs ls
        INNER JOIN tracks t ON ls.trackId = t.id
        LEFT JOIN track_artists ta ON ta.trackId = t.id
        LEFT JOIN artists a ON a.artistId = ta.artistId
        WHERE
            t.name LIKE '%' || :query || '%'
            OR a.name LIKE '%' || :query || '%'
        ORDER BY ls.position ASC
    """
    )
    fun observeSearchLikedTracks(query: String): Flow<List<TrackWithArtists>>

    @Query("SELECT trackId FROM liked_songs")
    fun observeLikedIds(): Flow<List<String>>

    @Query(
        """
        SELECT t.id
        FROM liked_songs ls
        JOIN track_artists ta ON ta.trackId = ls.trackId
        JOIN tracks t ON t.id = ls.trackId
        WHERE ta.artistId = :artistId
        ORDER BY ls.position
    """
    )
    fun observeLikedIdsByArtist(artistId: String): Flow<List<String>>

    // ── Episodes ──────────────────────────────────────────────────────────

    @Query("SELECT * FROM liked_episodes ORDER BY position ASC")
    fun observeLikedEpisodes(): Flow<List<LikedEpisodeEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM liked_episodes WHERE episodeId = :id)")
    suspend fun containsEpisode(id: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM liked_episodes WHERE episodeId = :id)")
    fun observeIsEpisodeLiked(id: String): Flow<Boolean>

    @Query(
        """
        SELECT e.* FROM episodes e
        INNER JOIN liked_episodes le ON e.episodeId = le.episodeId
        ORDER BY le.position ASC
    """
    )
    fun observeLikedEpisodesWithDetails(): Flow<List<EpisodeEntity>>

    @Query(
        """
        SELECT e.* FROM episodes e
        INNER JOIN liked_episodes le ON e.episodeId = le.episodeId
        WHERE e.name LIKE '%' || :query || '%'
           OR e.showName LIKE '%' || :query || '%'
        ORDER BY le.position ASC
    """
    )
    fun observeSearchLikedEpisodes(query: String): Flow<List<EpisodeEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEpisode(episode: LikedEpisodeEntity)

    @Query("DELETE FROM liked_episodes WHERE episodeId = :id")
    suspend fun deleteEpisode(id: String)

    @Query("UPDATE liked_episodes SET position = position + 1 WHERE position >= :fromPosition")
    suspend fun shiftEpisodePositions(fromPosition: Double)

    @Query("UPDATE liked_episodes SET position = position - 1 WHERE position > :fromPosition")
    suspend fun shiftEpisodePositionsDown(fromPosition: Double)

    @Query("UPDATE liked_episodes SET position = :newPosition WHERE episodeId = :id")
    suspend fun updateEpisodePosition(id: String, newPosition: Double)

    @Query("SELECT episodeId FROM liked_episodes ORDER BY position ASC")
    suspend fun getLikedEpisodeIds(): List<String>

    @Query("SELECT COUNT(*) FROM liked_episodes")
    fun observeEpisodeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM liked_episodes")
    suspend fun getEpisodeCount(): Int

    @Query("DELETE FROM liked_episodes")
    suspend fun clearAllEpisodes()

    @Query("SELECT episodeId FROM liked_episodes ORDER BY position ASC LIMIT :limit OFFSET :offset")
    suspend fun getEpisodeIdsWindow(limit: Int, offset: Int): List<String>

    @Query("SELECT episodeId FROM liked_episodes")
    fun observeLikedEpisodeIds(): Flow<List<String>>

    // ── Shows ──────────────────────────────────────────────────────────

    @Query("SELECT * FROM liked_shows ORDER BY position ASC")
    fun observeLikedShows(): Flow<List<LikedShowEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM liked_shows WHERE showId = :id)")
    suspend fun containsShow(id: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM liked_shows WHERE showId = :id)")
    fun observeIsShowLiked(id: String): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertShow(show: LikedShowEntity)

    @Query("DELETE FROM liked_shows WHERE showId = :id")
    suspend fun deleteShow(id: String)

    @Query("SELECT showId FROM liked_shows ORDER BY position ASC")
    suspend fun getLikedShowIds(): List<String>

    @Query("SELECT COUNT(*) FROM liked_shows")
    fun observeShowCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM liked_shows")
    suspend fun getShowCount(): Int

    @Query("DELETE FROM liked_shows")
    suspend fun clearAllShows()

    @Query("SELECT showId FROM liked_shows ORDER BY position ASC LIMIT :limit OFFSET :offset")
    suspend fun getShowIdsWindow(limit: Int, offset: Int): List<String>

    @Query("SELECT showId FROM liked_shows")
    fun observeLikedShowIds(): Flow<List<String>>

    @Query(
        """
        SELECT s.* FROM shows s
        INNER JOIN liked_shows ls ON s.showId = ls.showId
        ORDER BY ls.position ASC
    """
    )
    fun observeLikedShowsWithDetails(): Flow<List<ShowEntity>>

    @Query(
        """
        SELECT s.* FROM shows s
        INNER JOIN liked_shows ls ON s.showId = ls.showId
        WHERE s.name LIKE '%' || :query || '%'
        ORDER BY ls.position ASC
    """
    )
    fun observeSearchLikedShows(query: String): Flow<List<ShowEntity>>
}