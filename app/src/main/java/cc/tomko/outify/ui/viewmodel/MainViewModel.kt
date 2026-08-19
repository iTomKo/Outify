package cc.tomko.outify.ui.viewmodel

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Queue
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.Icon
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.tomko.outify.core.RadioResult
import cc.tomko.outify.core.SpClient
import cc.tomko.outify.core.Spirc.SpircWrapper
import cc.tomko.outify.core.model.Track
import cc.tomko.outify.core.model.toSpotifyUri
import cc.tomko.outify.data.repository.InterfaceSettings
import cc.tomko.outify.data.repository.LikedRepository
import cc.tomko.outify.data.repository.SettingsRepository
import cc.tomko.outify.data.setting.EpisodeSwipeActionHandler
import cc.tomko.outify.data.setting.GestureSetting
import cc.tomko.outify.data.setting.SwipeActionHandler
import cc.tomko.outify.playback.PlaybackStateHolder
import cc.tomko.outify.ui.GlobalPopupController
import cc.tomko.outify.ui.PopupSpec
import cc.tomko.outify.ui.notifications.InAppNotificationController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val playbackStateHolder: PlaybackStateHolder,
    private val spirc: SpircWrapper,
    private val settingsRepository: SettingsRepository,
    private val spClient: SpClient,
    private val likedRepository: LikedRepository,
    private val json: Json,
) : ViewModel() {
    val swipeSettings: Flow<List<GestureSetting>> =
        settingsRepository.interfaceSettings.map { it.gestureSettings }

    val interfaceSettings: Flow<InterfaceSettings> =
        settingsRepository.interfaceSettings

    val episodeSwipeActionHandler = object : EpisodeSwipeActionHandler {
        override fun addToQueue(uri: String) {
            this@MainViewModel.addToQueue(uri)
        }

        override fun playNext(uri: String) {
            this@MainViewModel.playNext(uri)
        }

        override fun favorite(episodeUri: String) {
            this@MainViewModel.favorite(episodeUri)
        }
    }

    val swipeActionHandler = object : SwipeActionHandler {
        override fun addToQueue(uri: String) {
            this@MainViewModel.addToQueue(uri)
        }

        override fun playNext(uri: String) {
            this@MainViewModel.playNext(uri)
        }

        override fun startRadio(track: Track) {
            this@MainViewModel.startRadio(track)
        }

        override fun favorite(trackUri: String) {
            this@MainViewModel.favorite(trackUri)
        }

        override fun addToPlaylist(track: Track) {
            this@MainViewModel.addToPlaylist(track)
        }

        override fun trackInfo(track: Track) {
            openTrackInfo(track)
        }
    }

    val currentTrack: StateFlow<Track?> = playbackStateHolder.state
        .map { it.currentTrack }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null
        )

    fun addToQueue(uri: String) {
        spirc.addToQueue(uri)
        InAppNotificationController.show(
            "Added to queue",
            { Icon(Icons.Default.Queue, contentDescription = "Added to queue") },
            1000L
        )
    }

    fun playNext(uri: String) {
        spirc.playNext(uri)
        InAppNotificationController.show(
            "Inserted to queue",
            { Icon(Icons.Default.Queue, contentDescription = "Inserted to queue") },
            1000L
        )
    }

    fun startRadio(track: Track) {
        spirc.startRadio(track.toSpotifyUri(), false)
        playbackStateHolder.setTrack(track)
        InAppNotificationController.show(
            "Radio started",
            { Icon(Icons.Default.Radio, contentDescription = "Radio started") },
            1000L
        )
    }

    fun getRadioUri(track: Track): String? {
        val jsonResult = spClient.getRadioForTrack(track.uri) ?: return null
        val result: RadioResult = json.decodeFromString(jsonResult)

        if (result.total == 0 || result.mediaItems.isEmpty()) {
            return null
        }

        return result.mediaItems.first().uri
    }

    fun addToPlaylist(track: Track) {
        GlobalPopupController.show(PopupSpec.AddToPlaylist(listOf(track)))
    }

    fun addToPlaylist(tracks: List<Track>) {
        GlobalPopupController.show(PopupSpec.AddToPlaylist(tracks))
    }

    fun favorite(trackUri: String) {
        viewModelScope.launch {
            val trackId = trackUri.substringAfterLast(":")
            val wasLiked = likedRepository.isLiked(trackId)

            // Optimistic UI update
            if (wasLiked) {
                // Remove from liked
                likedRepository.removeLiked(trackId)
            } else {
                // Add to liked
                likedRepository.addLiked(trackId)
            }

            // Make the API call
            val success = if (wasLiked) {
                spClient.deleteItems(arrayOf(trackUri))
            } else {
                spClient.saveItems(arrayOf(trackUri))
            }

            // Rollback if failed
            if (!success) {
                if (wasLiked) {
                    // Restore to liked
                    likedRepository.addLiked(trackId)
                } else {
                    // Restore to not liked
                    likedRepository.removeLiked(trackId)
                }
                InAppNotificationController.show(
                    "Failed to update favorite",
                    durationMillis = 2000L
                )
            }
        }
    }

    fun openTrackInfo(track: Track) {
        viewModelScope.launch {
            val likedIndex = try {
                likedRepository.getTrackIndex(track.uri)
            } catch (_: Exception) {
                -1
            }

            val isLiked = likedRepository.isLiked(track.id)

            GlobalPopupController.show(
                PopupSpec.TrackInfo(
                    track,
                    likedTrackIndex = if (likedIndex >= 0) likedIndex else null,
                    isLiked = isLiked
                )
            )
        }
    }
}