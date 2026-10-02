package cc.tomko.outify.data.repository

import cc.tomko.outify.core.SpClient
import cc.tomko.outify.core.model.LyricLine
import cc.tomko.outify.core.model.LyricsResponse
import cc.tomko.outify.core.model.Track
import cc.tomko.outify.data.remote.LrcLibClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import javax.inject.Inject

class PlayerRepository @Inject constructor(
    private val spClient: SpClient,
    private val json: Json,
    private val settingsRepository: SettingsRepository,
    private val lrcLibClient: LrcLibClient,
) {
    suspend fun getLyrics(track: Track?, timeoutMs: Long = 2000L): List<LyricLine> {
        val lyrics = getSpotifyLyrics(track, timeoutMs)
        if (lyrics.isNotEmpty() || track == null) return lyrics
        if (!settingsRepository.lyricsFallbackEnabled.first()) return lyrics

        return lrcLibClient.getLyrics(track)
    }

    private suspend fun getSpotifyLyrics(track: Track?, timeoutMs: Long): List<LyricLine> =
        withContext(Dispatchers.IO) {
            val id = track?.id ?: return@withContext emptyList()

            val raw: String = try {
                withTimeout(timeoutMs) {
                    spClient.getLyrics(id) ?: ""
                }
            } catch (e: Exception) {
                e.printStackTrace()
                return@withContext emptyList()
            }

            return@withContext try {
                val response: LyricsResponse = json.decodeFromString(raw)
                response.lyrics.lines
                    .filter { it.words.isNotBlank() }
                    .map {
                        LyricLine(
                            timestampMs = it.startTimeMs.toLong(),
                            text = it.words
                        )
                    }
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }
        }
}
