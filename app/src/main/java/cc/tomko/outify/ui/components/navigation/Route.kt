package cc.tomko.outify.ui.components.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface Route : NavKey {
    @Serializable
    data object HomeScreen : Route, NavKey

    @Serializable
    data object SearchScreen : Route, NavKey

    @Serializable
    data object RecommendationsScreen : Route, NavKey

    @Serializable
    data class LikedScreen(val scrollToIndex: Int = -1) : Route, NavKey

    @Serializable
    data object LibraryScreen : Route, NavKey

    @Serializable
    data class PlaylistScreen(val playlistUri: String) : Route, NavKey

    /**
     * When we know only the Track uri and want to open the Album Screen
     */
    @Serializable
    data class AlbumScreen(val albumUri: String) : Route, NavKey

    @Serializable
    data class TrackScreen(val trackUri: String) : Route, NavKey

    @Serializable
    data class ArtistScreen(val artistUri: String) : Route, NavKey

    @Serializable
    data class ProfileScreen(val profileUri: String) : Route, NavKey

    /**
     * Screen for Podcast shows
     */
    @Serializable
    data class ShowScreen(val showUri: String) : Route, NavKey

    // Settings
    @Serializable
    data object SettingsScreen : Route, NavKey

    @Serializable
    data object InterfaceSettings : Route, NavKey

    @Serializable
    data object PlaybackSettings : Route, NavKey

    @Serializable
    data object AppearanceSettings : Route, NavKey

    @Serializable
    data object GestureSettings : Route, NavKey

    @Serializable
    data object AboutScreen : Route, NavKey

    @Serializable
    data object AccountsScreen : Route, NavKey

    @Serializable
    data object MiscSettings : Route, NavKey

    @Serializable
    data object DebugScreen : Route, NavKey
}