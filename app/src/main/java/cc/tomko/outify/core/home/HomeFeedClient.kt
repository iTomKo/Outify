package cc.tomko.outify.core.home

import cc.tomko.outify.core.SpClient
import cc.tomko.outify.core.model.Track
import cc.tomko.outify.data.repository.CollectionSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.TimeZone
import javax.inject.Inject

@Serializable
data class HomeItem(
    val uri: String,
    val kind: String,
    val title: String,
    val subtitle: String = "",
    val imageUrl: String? = null,
    val track: Track? = null,
)

@Serializable
data class HomeSection(val id: String, val title: String, val items: List<HomeItem>)

@Serializable
data class HomeFeed(
    val session: CollectionSession,
    val recent: List<HomeItem> = emptyList(),
    val pickedAlbums: List<HomeItem> = emptyList(),
    val sections: List<HomeSection> = emptyList(),
    val warnings: List<String> = emptyList(),
)

interface HomeFeedClient {
    suspend fun home(expected: CollectionSession): HomeFeed
}

class NativeHomeFeedClient @Inject constructor(private val client: SpClient, private val json: Json) : HomeFeedClient {
    override suspend fun home(expected: CollectionSession): HomeFeed = withContext(Dispatchers.IO) {
        val raw = requireNotNull(client.getHomeFeed(json.encodeToString(expected), TimeZone.getDefault().id))
        json.decodeFromString<HomeFeed>(raw).also { check(it.session == expected) { "Home playback login changed" } }
    }
}
