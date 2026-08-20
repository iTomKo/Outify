package cc.tomko.outify.core.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * Used as Episode x Track common part, so we can display it in Mini Player and Player Content
 */
@Serializable
@Immutable
data class PlayableAudio(
    val id: String,
    val uri: String,
    val name: String,
    val covers: List<Cover>,
    val duration: Long = 0,
    val explicit: Boolean = false,
    val artists: List<Artist>? = null, // For track
    val showName: String? = null, // For episode
    val sourceTrack: Track? = null,
    val sourceEpisode: Episode? = null,
) {
    fun isTrack(): Boolean {
        return uri.startsWith("spotify:track:")
    }

    fun isEpisode(): Boolean {
        return uri.startsWith("spotify:episode:")
    }
}

fun PlayableAudio.toOutifyUri() =
    OutifyUri.fromUriString(uri)

fun PlayableAudio.getCover(size: CoverSize): Cover? =
    covers.firstOrNull { it.size == size.asInt() }
