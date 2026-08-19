package cc.tomko.outify.data.metadata

import android.util.Log
import cc.tomko.outify.core.SpClient
import cc.tomko.outify.core.model.Album
import cc.tomko.outify.core.model.Artist
import cc.tomko.outify.core.model.Cover
import cc.tomko.outify.core.model.CoverSize
import cc.tomko.outify.core.model.Episode
import cc.tomko.outify.core.model.Playlist
import cc.tomko.outify.core.model.Show
import cc.tomko.outify.core.model.Track
import cc.tomko.outify.data.dao.LikedItemsDao
import cc.tomko.outify.data.database.LikedItemsEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Metadata @Inject constructor(
    private val trackMetadataHelper: TrackMetadataHelper,
    private val albumMetadataHelper: AlbumMetadataHelper,
    private val playlistMetadataHelper: PlaylistMetadataHelper,
    private val showMetadataHelper: ShowMetadataHelper,
    private val episodeMetadataHelper: EpisodeMetadataHelper,
    private val nativeMetadata: NativeMetadata,
    private val spClient: SpClient,
    private val likedItemsDao: LikedItemsDao,
    private val json: Json,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeTracks(uris: List<String>): Flow<List<Track>> {
        return trackMetadataHelper.observeTracks(uris)
    }

    fun observeAlbums(uris: List<String>): Flow<List<Album>> {
        return albumMetadataHelper.observeAlbums(uris)
    }

    /**
     * Returns list of Tracks with their metadata
     */
    suspend fun getTrackMetadata(uris: List<String>): List<Track> {
        return trackMetadataHelper.getTrackMetadata(uris)
    }

    /**
     * Returns the Track's album URI
     */
    suspend fun getTrackAlbumId(trackUri: String): String? {
        return trackMetadataHelper.getTrackAlbumId(trackUri)
    }

    /**
     * Returns the cached album with its tracks (ordered URIs).
     *
     * If album missing in DB -> fetch remote, persist, return fetched.
     * If album exists but album_tracks missing -> fetch remote, persist cross-refs, return fetched.
     * If album + album_tracks exist -> fetch remote, compare track lists:
     *      - if different -> persist remote and return it
     *      - if identical  -> return cached immediately
     */
    suspend fun getAlbumMetadata(uri: String): Album? {
        return albumMetadataHelper.getAlbumMetadata(uri)
    }

    suspend fun getAlbumCover(albumId: String, size: CoverSize): Cover? {
        return albumMetadataHelper.getCoverByAlbumId(albumId, size)
    }

    suspend fun getAlbumCoverByTrackId(trackId: String, size: CoverSize): Cover? {
        return albumMetadataHelper.getCoverByTrackId(trackId, size)
    }

    fun observeShows(uris: List<String>): Flow<List<Show>> {
        return showMetadataHelper.observeShows(uris)
    }

    suspend fun getShowMetadata(uri: String): Show? {
        return showMetadataHelper.getShowMetadata(uri)
    }

    suspend fun getShowCover(showId: String, size: CoverSize): Cover? {
        return showMetadataHelper.getCoverByShowId(showId, size)
    }

    fun observeEpisodes(uris: List<String>): Flow<List<Episode>> {
        return episodeMetadataHelper.observeEpisodes(uris)
    }

    suspend fun getEpisodeMetadata(uris: List<String>): List<Episode> {
        return episodeMetadataHelper.getEpisodeMetadata(uris)
    }

    suspend fun getEpisodeMetadata(uri: String): Episode? {
        return episodeMetadataHelper.getEpisodeMetadata(uri)
    }

    suspend fun getEpisodeCover(episodeId: String, size: CoverSize): Cover? {
        return episodeMetadataHelper.getCoverByEpisodeId(episodeId, size)
    }

    suspend fun getArtistMetadata(uri: String): Artist? {
        try {
            val raw = nativeMetadata.getNativeMetadata(uri)
            NativeErrorHandler.handleErrorJson(raw, "getArtistMetadata:$uri")
            return json.decodeFromString<Artist>(raw)
        } catch (e: Exception) {
            Log.e("Metadata", "getArtistMetadata: failed for $uri", e)
            NativeErrorHandler.handleError(
                NativeError.fromJson("unknown", e.message ?: "Failed to get artist metadata"),
                "getArtistMetadata:$uri"
            )
            return null
        }
    }

    suspend fun getPlaylistUris(): List<String> {
        try {
            val uris = spClient.getRootlist()
            return uris.toList()
        } catch (e: Exception) {
            NativeErrorHandler.handleError(
                NativeError.fromJson("unknown", e.message ?: "Failed to get playlist URIs"),
                "getPlaylistUris"
            )
            return emptyList()
        }
    }

    suspend fun getLikedUris(): List<String> {
        try {
            val jsonUris = spClient.getUserCollection() ?: return emptyList()
            val checked = spClient.checkAndHandleError(jsonUris, "getLikedUris")
            val parsed = json.decodeFromString<List<String>>(checked)
            return parsed
        } catch (e: Exception) {
            NativeErrorHandler.handleError(
                NativeError.fromJson("unknown", e.message ?: "Failed to get liked URIs"),
                "getLikedUris"
            )
            return emptyList()
        }
    }

    suspend fun getPlaylistMetadata(uri: String, allowCached: Boolean): Playlist? {
        return playlistMetadataHelper.getPlaylistMetadata(uri, allowCached)
    }

    fun observePlaylist(uri: String) =
        playlistMetadataHelper.observePlaylist(uri)

    fun observePlaylists(uris: List<String>) =
        playlistMetadataHelper.observePlaylists(uris)

    companion object {
        const val TYPE_PLAYLIST = "playlist"
        const val TYPE_ALBUM = "album"
        const val TYPE_ARTIST = "artist"
        const val TYPE_SHOW = "show"
        const val TYPE_EPISODE = "episode"
    }

    suspend fun isLikedPlaylist(uri: String): Boolean = likedItemsDao.contains(uri)

    suspend fun isLikedAlbum(uri: String): Boolean = likedItemsDao.contains(uri)

    suspend fun isLikedArtist(uri: String): Boolean = likedItemsDao.contains(uri)

    fun observeLikedPlaylistUris(): Flow<List<String>> =
        likedItemsDao.observeUrisByType(TYPE_PLAYLIST)

    fun observeLikedAlbumUris(): Flow<List<String>> =
        likedItemsDao.observeUrisByType(TYPE_ALBUM)

    fun observeIsPlaylistLiked(uri: String): Flow<Boolean> =
        likedItemsDao.observeContains(uri)

    fun observeIsAlbumLiked(uri: String): Flow<Boolean> =
        likedItemsDao.observeContains(uri)

    suspend fun syncLikedPlaylists(): List<String> {
        return try {
            val uris = spClient.getRootlist().toList()

            likedItemsDao.clearAll()
            uris.forEach { uri ->
                val uri = uri.substringAfterLast(":").let { "spotify:playlist:$it" }
                likedItemsDao.insert(LikedItemsEntity(uri, TYPE_PLAYLIST))
            }
            uris
        } catch (e: Exception) {
            NativeErrorHandler.handleError(
                NativeError.fromJson("unknown", e.message ?: "Failed to sync liked playlists"),
                "syncLikedPlaylists"
            )
            likedItemsDao.getUrisByType(TYPE_PLAYLIST)
        }
    }

    suspend fun addLikedPlaylist(uri: String) {
        likedItemsDao.insert(LikedItemsEntity(uri, TYPE_PLAYLIST))
    }

    suspend fun removeLikedPlaylist(uri: String) {
        likedItemsDao.delete(uri)
    }

    suspend fun addLikedAlbum(uri: String) {
        likedItemsDao.insert(LikedItemsEntity(uri, TYPE_ALBUM))
    }

    suspend fun removeLikedAlbum(uri: String) {
        likedItemsDao.delete(uri)
    }

    suspend fun isLikedShow(uri: String): Boolean = likedItemsDao.contains(uri)

    suspend fun isLikedEpisode(uri: String): Boolean = likedItemsDao.contains(uri)

    fun observeLikedShowUris(): Flow<List<String>> =
        likedItemsDao.observeUrisByType(TYPE_SHOW)

    fun observeLikedEpisodeUris(): Flow<List<String>> =
        likedItemsDao.observeUrisByType(TYPE_EPISODE)

    fun observeIsShowLiked(uri: String): Flow<Boolean> =
        likedItemsDao.observeContains(uri)

    fun observeIsEpisodeLiked(uri: String): Flow<Boolean> =
        likedItemsDao.observeContains(uri)

    suspend fun addLikedShow(uri: String) {
        likedItemsDao.insert(LikedItemsEntity(uri, TYPE_SHOW))
    }

    suspend fun removeLikedShow(uri: String) {
        likedItemsDao.delete(uri)
    }

    suspend fun addLikedEpisode(uri: String) {
        likedItemsDao.insert(LikedItemsEntity(uri, TYPE_EPISODE))
    }

    suspend fun removeLikedEpisode(uri: String) {
        likedItemsDao.delete(uri)
    }
}