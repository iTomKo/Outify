package cc.tomko.outify.core.model

import kotlinx.serialization.Serializable

@Serializable
data class LyricsResponse(
    val lyrics: Lyrics
)

@Serializable
data class Lyrics(
    val syncType: String,
    val lines: List<RawLyricLine>
)

@Serializable
data class RawLyricLine(
    val startTimeMs: String,
    val words: String,
    val endTimeMs: String? = null
)

data class LyricLine(
    val timestampMs: Long,
    val text: String
)