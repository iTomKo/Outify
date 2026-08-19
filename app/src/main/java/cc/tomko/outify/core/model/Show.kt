package cc.tomko.outify.core.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ShowMediaType {
   MIXED, AUDIO, VIDEO
}

@Serializable
enum class ConsumptionOrder {
    SEQUENTIAL, EPISODIC, RECENT
}

@Serializable
@Immutable
data class Show(
    val id: String,
    val uri: String,
    val name: String,
    val description: String,
    val publisher: String,
    val language: String,
    @SerialName("is_explicit")
    val isExplicit: Boolean,
    val covers: List<Cover> = emptyList(),
    val episodes: List<String>, // List of URIs
    val keywords: List<String>,
    @SerialName("media_type")
    val mediaType: ShowMediaType,
    @SerialName("consumption_order")
    val consumptionOrder: ConsumptionOrder,
    @SerialName("trailer_uri")
    val trailerUri: String?,
    @SerialName("has_music_and_talk")
    val hasMusicAndTalk: Boolean,
    @SerialName("is_audiobook")
    val isAudiobook: Boolean,
) {

}

fun Show.toSpotifyUri(): SpotifyUri =
    SpotifyUri.Show(id)

fun Show.toOutifyUri(): OutifyUri =
    OutifyUri.fromUriString(uri)

fun Show.getCover(size: CoverSize): Cover? =
    covers.firstOrNull { it.size == size.asInt() }
