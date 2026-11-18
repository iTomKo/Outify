package cc.tomko.outify.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CurrentUserProfile(
    val username: String,
    @SerialName("display_name")
    val displayName: String?,
    val images: List<Image>,
)

@Serializable
data class ExternalUrls(
    val spotify: String
)

@Serializable
data class Followers(
    val href: String?,
    val total: Int
)

@Serializable
data class Image(
    val url: String,
    val height: Int,
    val width: Int
)
