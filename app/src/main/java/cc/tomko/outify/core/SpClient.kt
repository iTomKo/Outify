package cc.tomko.outify.core

import androidx.annotation.StringDef
import cc.tomko.outify.core.model.DevicesResponse
import cc.tomko.outify.data.metadata.NativeError
import cc.tomko.outify.data.metadata.NativeErrorHandler
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SpClient @Inject constructor() {
    companion object {
        private const val TAG = "SpClient"

        const val TRACKS = "tracks"
        const val ALBUMS = "albums"
        const val EPISODES = "episodes"
        const val SHOWS = "shows"

        @StringDef(TRACKS, ALBUMS, EPISODES, SHOWS)
        @Retention(AnnotationRetention.SOURCE)
        annotation class SavedItemType
    }

    /**
     * Returns currently logged-in user's username
     * Requires playback login
     */
    external fun username(): String?

    external fun getCurrentUserProfile(): String?
    external fun search(
        query: String,
        type: String,
        offset: Int = -1,
        pages: Int = -1
    ): Array<String>

    external fun searchContext(
        query: String,
        type: String,
    ): Array<String>

    external fun getUserCollection(query: String? = null): String?

    /**
     * Adds given uris to users library
     */
    external fun saveItems(uris: Array<String>): Boolean

    /**
     * Removes given uris from users library
     */
    external fun deleteItems(uris: Array<String>): Boolean

    /**
     * Get uris of items saved in users library
     */
    external fun getSavedItems(@SavedItemType itemType: String = TRACKS): String

    /**
     * Get episode URIs and their show URIs saved in users library.
     * Returns semicolon-separated pairs of "episodeUri,showUri"
     */
    external fun getSavedEpisodeItems(): String

    /**
     * Get episode details (show URI, resume point, etc.) via the Spotify Web API.
     * Returns JSON with showUri, fullyPlayed, resumePositionMs.
     */
    external fun getEpisodeDetails(episodeId: String): String

    /**
     * Get the current user's top artists or tracks based on calculated affinity.
     * Default type = artists
     * Possible types: artists, tracks
     * https://developer.spotify.com/documentation/web-api/reference/get-users-top-artists-and-tracks
     */
    external fun getUserTop(type: String? = null, timeRange: String = "medium_range"): String?

    /**
     * Gets the available devices to stream playback from.
     * In format of [cc.tomko.outify.core.model.DevicesResponse]
     */
    external fun getDevices(): String?

    /**
     * Gets the count of available devices to stream playback from.
     */
    fun getDeviceCount(): Int =
        getDevices()?.let { Json.decodeFromString<DevicesResponse>(it).devices.size } ?: 0

    /**
     * Transfers current playback device to the one with given ID
     */
    external fun transferPlaybackDevice(deviceId: String): Boolean

    /**
     * Check if user has authenticated with Spotify via OAuth.
     * Returns true if authenticated, false otherwise.
     */
    external fun isOAuthAuthenticated(): Boolean

    /**
     * Returns oauth scopes (if any) separated by space
     * May throw exception when error occurs
     */
    external fun getOAuthScope(): String?

    /**
     * Adds tracks in array into the playlist by given id
     */
    external fun addToPlaylist(playlist_id: String, track_uris: Array<String>): Boolean

    /**
     * Removes tracks in array from the playlist by given id
     */
    external fun deleteFromPlaylist(playlist_id: String, track_uris: Array<String>): Boolean

    /**
     * Creates new playlist.
     * For it to be collaborative, public has to be false
     *
     * Returns ID of the new playlist
     */
    external fun createPlaylist(
        name: String,
        description: String = "",
        public: Boolean,
        collaborative: Boolean
    ): String?

    /**
     * Modifies playlist
     * For it to be collaborative, public has to be false
     *
     * Returns status code
     */
    external fun modifyPlaylist(
        playlistId: String,
        name: String,
        description: String = "",
        public: Boolean,
        collaborative: Boolean
    ): Int

    /**
     * Retrieves the metadata for singular track by its ID
     */
    external fun getTrackData(id: String): String?

    external fun getRootlist(): Array<String>

    /**
     * Returns JSON of `total` and `mediaItems` - containing object of `uri` holding URI to the radio playlist
     */
    external fun getRadioForTrack(trackUri: String): String?

    /**
     * Returns lyrics for track id
     */
    external fun getLyrics(trackId: String): String?

    /**
     * Starts the OAuth flow for SpotifyClient user authentication.
     * Returns the authorization URL to be opened in a webview.
     */
    external fun startOAuthFlow(): String

    /**
     * Completes the OAuth flow by exchanging the authorization code for tokens.
     * Call this after the user completes authorization.
     * Returns JSON: {"success":true} on success, {"error":{"type":"...","message":"..."}} on failure.
     */
    external fun completeOAuthFlow(code: String): String

    /**
     * Deletes the credentials file for Spotify Client
     */
    external fun logout(): Boolean

    /**
     * Resets the Spotify Client's transient OAuth state.
     * Call after logging in so the client reloads the fresh credentials on the
     * next request.
     */
    external fun reset(): Boolean

    fun checkAndHandleError(result: String, context: String = ""): String {
        if (result.startsWith("{")) {
            NativeErrorHandler.handleErrorJson(result, context)?.let {
                throw SpClientException(it.message, it)
            }
        }
        return result
    }
}

class SpClientException(
    message: String,
    val error: NativeError
) : Exception(message)

@Serializable
data class RadioResult(
    val total: Int,
    val mediaItems: List<RadioMediaItem>,
)

@Serializable
data class RadioMediaItem(
    val uri: String
)

@Serializable
data class EpisodeDetails(
    @SerialName("show_uri")
    val showUri: String,
    @SerialName("fully_played")
    val fullyPlayed: Boolean = false,
    @SerialName("resume_position_ms")
    val resumePositionMs: Long = 0,
) {
    companion object {
        fun fromJson(json: String): EpisodeDetails =
            Json.decodeFromString(json)
    }
}