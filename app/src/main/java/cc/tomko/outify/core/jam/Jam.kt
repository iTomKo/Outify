package cc.tomko.outify.core.jam

import kotlinx.serialization.Serializable

@Serializable
data class JamMember(
    val id: String,
    val name: String,
    val image: String? = null,
    val host: Boolean = false,
    val you: Boolean = false,
)

@Serializable
data class Jam(
    val members: List<JamMember> = emptyList(),
    val owner: Boolean = false,
    /** Shareable invitation URL. Available only to the owner. */
    val link: String? = null,
)

class JamException(message: String, cause: Throwable? = null) : Exception(message, cause)
