package cc.tomko.outify.core.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class EpisodeType {
    FULL,TRAILER,BONUS
}

@Serializable
@Immutable
data class Episode(
    val id: String,
    val uri: String,
    val name: String,
    val duration: Long,
    val description: String,
    val number: Int,
    @SerialName("publish_time")
    val publishTime: Long, // Unix epoch
    val covers: List<Cover> = emptyList(),
    val language: String,
    @SerialName("is_explicit")
    val isExplicit: Boolean,
    @SerialName("show_name")
    val showName: String,
    val keywords: List<String>,
    @SerialName("allow_background_playback")
    val allowBackgroundPlayback: Boolean,
    @SerialName("external_url")
    val externalUrl: String,
    @SerialName("episode_type")
    val episodeType: EpisodeType,
    @SerialName("has_music_and_talk")
    val hasMusicAndTalk: Boolean,
    @SerialName("is_audiobook_chapter")
    val isAudiobookChapter: Boolean,
) {

}

fun Episode.toSpotifyUri(): SpotifyUri =
    SpotifyUri.Episode(id)

fun Episode.toOutifyUri(): OutifyUri =
    OutifyUri.fromUriString(uri)
