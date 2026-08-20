package cc.tomko.outify.data.database.show

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation
import cc.tomko.outify.core.model.ConsumptionOrder
import cc.tomko.outify.core.model.Cover
import cc.tomko.outify.core.model.CoverSize
import cc.tomko.outify.core.model.Episode
import cc.tomko.outify.core.model.EpisodeType
import cc.tomko.outify.core.model.Show
import cc.tomko.outify.core.model.ShowMediaType
import cc.tomko.outify.core.model.asInt
import cc.tomko.outify.data.database.EpisodeEntity
import cc.tomko.outify.data.database.ShowEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

data class ShowWithEpisodes(
    @Embedded val show: ShowEntity,
    @Relation(
        parentColumn = "showId",
        entityColumn = "episodeId",
        associateBy = Junction(
            value = ShowEpisodeCrossRef::class,
            parentColumn = "showId",
            entityColumn = "episodeId"
        )
    )
    val episodes: List<EpisodeEntity>,
)

fun ShowWithEpisodes.toDomain(): Show {
    val covers = listOfNotNull(
        show.smallCoverUri?.takeIf { it.isNotBlank() }?.let {
            Cover(
                uri = it, size = CoverSize.SMALL.asInt(),
                width = 64,
                height = 64
            )
        },
        show.mediumCoverUri?.takeIf { it.isNotBlank() }?.let {
            Cover(
                uri = it, size = CoverSize.MEDIUM.asInt(),
                width = 300,
                height = 300
            )
        },
        show.largeCoverUri?.takeIf { it.isNotBlank() }?.let {
            Cover(
                uri = it, size = CoverSize.LARGE.asInt(),
                width = 640,
                height = 640
            )
        }
    )

    val keywords = try {
        kotlinx.serialization.json.Json.parseToJsonElement(show.keywordsJson)
            .jsonArray.map { it.jsonPrimitive.content }
    } catch (_: Exception) {
        emptyList()
    }

    return Show(
        id = show.showId,
        uri = show.uri,
        name = show.name,
        description = show.description,
        publisher = show.publisher,
        language = show.language,
        isExplicit = show.isExplicit,
        covers = covers,
        episodes = episodes.map { it.uri },
        keywords = keywords,
        mediaType = try { ShowMediaType.valueOf(show.mediaType) } catch (_: Exception) { ShowMediaType.AUDIO },
        consumptionOrder = try { ConsumptionOrder.valueOf(show.consumptionOrder) } catch (_: Exception) { ConsumptionOrder.RECENT },
        trailerUri = show.trailerUri,
        hasMusicAndTalk = show.hasMusicAndTalk,
        isAudiobook = show.isAudiobook,
    )
}

fun EpisodeEntity.toDomain(): Episode {
    val covers = listOfNotNull(
        smallCoverUri?.takeIf { it.isNotBlank() }?.let {
            Cover(
                uri = it, size = CoverSize.SMALL.asInt(),
                width = 64,
                height = 64
            )
        },
        mediumCoverUri?.takeIf { it.isNotBlank() }?.let {
            Cover(
                uri = it, size = CoverSize.MEDIUM.asInt(),
                width = 300,
                height = 300
            )
        },
        largeCoverUri?.takeIf { it.isNotBlank() }?.let {
            Cover(
                uri = it, size = CoverSize.LARGE.asInt(),
                width = 640,
                height = 640
            )
        }
    )

    val keywords = try {
        Json.parseToJsonElement(keywordsJson)
            .jsonArray.map { it.jsonPrimitive.content }
    } catch (_: Exception) {
        emptyList()
    }

    return Episode(
        id = episodeId,
        uri = uri,
        name = name,
        duration = duration,
        description = description,
        number = number,
        publishTime = publishTime,
        covers = covers,
        language = language,
        isExplicit = isExplicit,
        showName = showName,
        showUri = showUri,
        keywords = keywords,
        allowBackgroundPlayback = allowBackgroundPlayback,
        externalUrl = externalUrl,
        episodeType = try { EpisodeType.valueOf(episodeType) } catch (_: Exception) { EpisodeType.FULL },
        hasMusicAndTalk = hasMusicAndTalk,
        isAudiobookChapter = isAudiobookChapter,
    )
}
