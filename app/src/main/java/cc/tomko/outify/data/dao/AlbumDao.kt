package cc.tomko.outify.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import cc.tomko.outify.data.database.AlbumEntity
import cc.tomko.outify.data.database.album.AlbumWithArtists
import cc.tomko.outify.data.database.album.AlbumWithTracks
import kotlinx.coroutines.flow.Flow
import javax.inject.Singleton

@Dao
@Singleton
interface AlbumDao {
    data class CoverUris(
        val smallCoverUri: String?,
        val mediumCoverUri: String?,
        val largeCoverUri: String?
    )

    @Query("SELECT smallCoverUri, mediumCoverUri, largeCoverUri FROM albums WHERE albumId = :albumId LIMIT 1")
    suspend fun getCoverUris(albumId: String): CoverUris?

    @Query("SELECT smallCoverUri, mediumCoverUri, largeCoverUri FROM albums WHERE albumId = :albumId LIMIT 1")
    fun observeCoverUris(albumId: String): Flow<CoverUris?>

    @Transaction
    @Query("SELECT * FROM albums WHERE albumId IN (:albumIds)")
    fun observeAlbumsWithArtistsChunk(albumIds: List<String>): Flow<List<AlbumWithArtists>>

    fun observeAlbumsWithArtists(albumIds: List<String>): Flow<List<AlbumWithArtists>> =
        albumIds.chunkedObserve(::observeAlbumsWithArtistsChunk)

    @Transaction
    @Query("SELECT * FROM albums WHERE albumId = :albumId")
    suspend fun getAlbumWithArtists(albumId: String): AlbumWithArtists?

    @Transaction
    @Query("SELECT * FROM albums WHERE albumId IN (:albumIds)")
    suspend fun getAlbumsWithArtistsChunk(albumIds: List<String>): List<AlbumWithArtists>

    suspend fun getAlbumsWithArtists(albumIds: List<String>): List<AlbumWithArtists> =
        albumIds.chunkedQuery { getAlbumsWithArtistsChunk(it) }

    @Transaction
    @Query("SELECT * FROM albums WHERE albumId = :albumId")
    suspend fun getAlbumWithTracks(albumId: String): AlbumWithTracks

    @Transaction
    @Query(
        """
    SELECT t.trackUri 
    FROM tracks t
    INNER JOIN album_tracks at ON t.id = at.trackId
    WHERE at.albumId = :albumId
    ORDER BY at.position
"""
    )
    suspend fun getTrackUrisForAlbum(albumId: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(albums: List<AlbumEntity>)
}