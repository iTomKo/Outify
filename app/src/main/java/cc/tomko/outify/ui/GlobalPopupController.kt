package cc.tomko.outify.ui

import cc.tomko.outify.core.model.Artist
import cc.tomko.outify.core.model.Playlist
import cc.tomko.outify.core.model.PlaylistFolder
import cc.tomko.outify.core.model.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

object GlobalPopupController {
    private val _popups = MutableStateFlow<List<PopupSpec>>(emptyList())
    val popups: StateFlow<List<PopupSpec>> = _popups

    fun show(popup: PopupSpec) {
        _popups.value = _popups.value + popup
    }

    fun dismiss(popupId: String) {
        _popups.value = _popups.value.filterNot { it.id == popupId }
    }

    fun dismissAll() {
        _popups.value = emptyList()
    }

    fun dismiss() {
        _popups.value = _popups.value.dropLast(1)
    }
}

sealed class PopupSpec(
    open val id: String = UUID.randomUUID().toString(),
) {
    data class TrackInfo(
        val track: Track,
        val action: (() -> Unit)? = null,
        val likedTrackIndex: Int? = null,
        val isLiked: Boolean = false,
        override val id: String = UUID.randomUUID().toString(),
    ) : PopupSpec(id)

    data class Lyrics(
        val track: Track,
        val shouldFollowCurrentTrack: Boolean = true,
        override val id: String = UUID.randomUUID().toString(),
    ) : PopupSpec(id)

    data class TrackRecommendation(
        val seed: List<Track> = emptyList(),

        override val id: String = UUID.randomUUID().toString(),
    ) : PopupSpec(id)

    data class AddToWidgetInfo(
        val track: Track,
        override val id: String = UUID.randomUUID().toString(),
    ) : PopupSpec(id)

    data class PlaylistInfo(
        val playlist: Playlist,
        val artworkUrl: String?,
        val action: (() -> Unit)? = null,
        override val id: String = UUID.randomUUID().toString(),
    ) : PopupSpec(id)

    data class ArtistInfo(
        val artist: Artist,
        val isSaved: Boolean = false,
        val onToggleSave: (() -> Unit)? = null,
        override val id: String = UUID.randomUUID().toString(),
    ) : PopupSpec(id)

    data class AuthResult(
        val isSuccess: Boolean,
        val message: String = if (isSuccess) "Login successful!" else "Login failed",
        val errorDetails: String? = null,
        val onDismiss: (() -> Unit)? = null,
        override val id: String = UUID.randomUUID().toString(),
    ) : PopupSpec(id)

    data class AddToPlaylist(
        val tracks: List<Track>,
        override val id: String = UUID.randomUUID().toString(),
    ) : PopupSpec(id)

    data class CreatePlaylist(
        override val id: String = UUID.randomUUID().toString(),
    ) : PopupSpec(id)

    data class ModifyPlaylist(
        override val id: String = UUID.randomUUID().toString(),
        val playlistId: String? = null,
        val name: String = "",
        val description: String = "",
        val public: Boolean = true,
        val collaborative: Boolean = false,
    ) : PopupSpec(id)

    data class PlaybackDevices(
        override val id: String = UUID.randomUUID().toString(),
    ) : PopupSpec(id)

    data class CreateFolder(
        override val id: String = UUID.randomUUID().toString(),
    ) : PopupSpec(id)

    data class EditFolder(
        val folder: PlaylistFolder,
        override val id: String = UUID.randomUUID().toString(),
    ) : PopupSpec(id)

    data object NotificationPermission : PopupSpec()

    data object BatteryOptimization : PopupSpec()
}