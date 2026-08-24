package cc.tomko.outify.services

import android.content.Context
import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MediaMetadata.MEDIA_TYPE_MUSIC
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import cc.tomko.outify.ALBUM_COVER_URL
import cc.tomko.outify.MediaSessionConstants
import cc.tomko.outify.core.SpClient
import cc.tomko.outify.core.model.CoverSize
import cc.tomko.outify.core.model.Track
import cc.tomko.outify.core.model.getCover
import cc.tomko.outify.core.spirc.SpircController
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.qualifiers.ApplicationContext
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@UnstableApi
class MediaLibrarySessionCallback @Inject constructor(
    @ApplicationContext private val context: Context,
    private val spClient: SpClient,
    private val spircController: SpircController,
) : MediaLibraryService.MediaLibrarySession.Callback {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    lateinit var service: PlaybackService
    var toggleLike: () -> Unit = {}
    var toggleStartRadio: () -> Unit = {}
    var toggleRepeatMode: () -> Unit = {}

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo
    ): MediaSession.ConnectionResult {
        val connectionResult = super.onConnect(session, controller)
        return MediaSession.ConnectionResult.accept(
            connectionResult.availableSessionCommands
                .buildUpon()
                .add(MediaSessionConstants.CommandToggleLike)
                .add(MediaSessionConstants.CommandToggleStartRadio)
                .add(MediaSessionConstants.CommandToggleShuffle)
                .add(MediaSessionConstants.CommandToggleRepeatMode)
                .build(),
            connectionResult.availablePlayerCommands
        )
    }

    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        isForPlayback: Boolean
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        scope.launch {
            spircController.restart()
        }
        return super.onPlaybackResumption(mediaSession, controller, isForPlayback)
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle
    ): ListenableFuture<SessionResult> {
        when (customCommand.customAction) {
            MediaSessionConstants.ACTION_TOGGLE_LIKE -> toggleLike()
            MediaSessionConstants.ACTION_TOGGLE_START_RADIO -> toggleStartRadio()
						MediaSessionConstants.ACTION_TOGGLE_REPEAT_MODE -> toggleRepeatMode()
        }
        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }

    override fun onGetLibraryRoot(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<MediaItem>> {
        val rootItem = MediaItem.Builder()
            .setMediaId(PlaybackService.ROOT)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setIsPlayable(false)
                    .setIsBrowsable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .setTitle("Outify")
                    .build()
            )
            .build()

        val libraryParams = MediaLibraryService.LibraryParams.Builder()
            .setExtras(Bundle().apply {
                putBoolean("androidx.media3.session.LIBRARY_PARAM_KEY_RECENT", true)
                putBoolean("androidx.media3.session.LIBRARY_PARAM_KEY_OFFLINE", false)
            })
            .build()

        return Futures.immediateFuture(LibraryResult.ofItem(rootItem, libraryParams))
    }

    override fun onGetChildren(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val children = when (parentId) {
            PlaybackService.ROOT -> getRootChildren()
            PlaybackService.PLAYLIST -> getPlaylists()
            PlaybackService.LIKED -> getLikedTracks()
            PlaybackService.ARTIST -> getTopArtists()
            PlaybackService.RECENT -> getRecentTracks()
            else -> emptyList()
        }

        return Futures.immediateFuture(LibraryResult.ofItemList(children, params))
    }

    override fun onGetItem(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String
    ): ListenableFuture<LibraryResult<MediaItem>> {
        val item = service.player.currentMediaItem
        return if (item != null && item.mediaId == mediaId) {
            Futures.immediateFuture(LibraryResult.ofItem(item, null))
        } else {
            Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_UNKNOWN))
        }
    }

    private fun getRootChildren(): List<MediaItem> = listOf(
        MediaItem.Builder()
            .setMediaId(PlaybackService.RECENT)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setIsPlayable(false)
                    .setIsBrowsable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .setTitle("Recently Played")
                    .build()
            )
            .build(),
        MediaItem.Builder()
            .setMediaId(PlaybackService.LIKED)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setIsPlayable(false)
                    .setIsBrowsable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .setTitle("Liked Songs")
                    .build()
            )
            .build(),
        MediaItem.Builder()
            .setMediaId(PlaybackService.PLAYLIST)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setIsPlayable(false)
                    .setIsBrowsable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .setTitle("Playlists")
                    .build()
            )
            .build(),
        MediaItem.Builder()
            .setMediaId(PlaybackService.ARTIST)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setIsPlayable(false)
                    .setIsBrowsable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .setTitle("Top Artists")
                    .build()
            )
            .build(),
    )

    private fun getLikedTracks(): List<MediaItem> {
        return try {
            val result = spClient.getUserCollection("tracks") ?: return emptyList()
            parseTracksFromSearchResult(result)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun getRecentTracks(): List<MediaItem> {
        return try {
            val result = spClient.getUserCollection("recent") ?: return emptyList()
            parseTracksFromSearchResult(result)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun getPlaylists(): List<MediaItem> {
        return try {
            val rootlist = spClient.getRootlist() ?: return emptyList()
            rootlist.mapIndexed { index, uri ->
                val parts = uri.removePrefix("spotify:playlist:").split(":")
                val playlistId = if (parts.isNotEmpty()) parts[0] else uri

                MediaItem.Builder()
                    .setMediaId("playlist:$playlistId")
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setIsPlayable(false)
                            .setIsBrowsable(true)
                            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                            .setTitle("Playlist ${index + 1}")
                            .build()
                    )
                    .build()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun getTopArtists(): List<MediaItem> {
        return try {
            val result = spClient.getUserTop("artists") ?: return emptyList()
            parseArtistsFromResult(result)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseTracksFromSearchResult(json: String): List<MediaItem> {
        return try {
            val trackIds = extractTrackIds(json)
            trackIds.mapNotNull { trackId ->
                try {
                    val trackJson = spClient.getTrackData(trackId) ?: return emptyList()
                    val track = kotlinx.serialization.json.Json.decodeFromString<Track>(trackJson)
                    track.toMediaItem()
                } catch (e: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseArtistsFromResult(json: String): List<MediaItem> {
        return try {
            val regex = Regex("""spotify:artist:([a-zA-Z0-9]+)""")
            regex.findAll(json).map { match ->
                val artistId = match.groupValues[1]
                MediaItem.Builder()
                    .setMediaId("artist:$artistId")
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setIsPlayable(false)
                            .setIsBrowsable(true)
                            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                            .setTitle("Artist")
                            .build()
                    )
                    .build()
            }.toList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun extractTrackIds(json: String): List<String> {
        val regex = Regex("""spotify:track:([a-zA-Z0-9]+)""")
        return regex.findAll(json).map { it.groupValues[1] }.toList()
    }
}

private fun Track.toMediaItem(isPlayable: Boolean = true, isBrowsable: Boolean = false) =
    MediaItem.Builder()
        .setMediaId(id)
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(name)
                .setSubtitle(artists.joinToString { it.name })
                .setArtist(artists.joinToString { it.name })
                .setAlbumTitle(album?.name)
                .setArtworkUri(album?.getCover(CoverSize.LARGE)?.let { ALBUM_COVER_URL + it.uri }
                    ?.toUri())
                .setIsPlayable(isPlayable)
                .setIsBrowsable(isBrowsable)
                .setMediaType(MEDIA_TYPE_MUSIC)
                .build()
        )
        .build()
