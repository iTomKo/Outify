package cc.tomko.outify.core.model

sealed class SpotifyUri : OutifyUri() {
    data class Album(override val id: String) : SpotifyUri()
    data class Artist(override val id: String) : SpotifyUri()
    data class Episode(override val id: String) : SpotifyUri()
    data class Playlist(override val id: String, val user: String? = null) : SpotifyUri()
    data class Show(override val id: String) : SpotifyUri()
    data class Track(override val id: String) : SpotifyUri()
    data class Local(
        val artist: String,
        val albumTitle: String,
        val trackTitle: String,
        val durationSeconds: Long
    ) : SpotifyUri() {
        override val id: String
            get() = "$artist:$albumTitle:$trackTitle:$durationSeconds"
    }

    data class Unknown(override val id: String, val kind: String) : SpotifyUri()

    val itemType: String
        get() = when (this) {
            is Album -> "album"
            is Artist -> "artist"
            is Episode -> "episode"
            is Playlist -> "playlist"
            is Show -> "show"
            is Track -> "track"
            is Local -> "local"
            is Unknown -> kind
        }

    override fun toUriString(): String = when (this) {
        is Playlist -> user?.let { "spotify:user:$it:playlist:$id" } ?: "spotify:playlist:$id"
        else -> "spotify:$itemType:$id"
    }

    companion object {
        fun fromUriString(uri: String): SpotifyUri {
            val parts = uri.split(":")
            if (parts.size < 3 || parts[0] != "spotify") {
                return Unknown("", uri)
            }

            var username: String? = null
            var itemTypeIndex = 1

            if (parts.size > 3 && parts[1] == "user") {
                username = parts[2]
                itemTypeIndex = 3
            }

            if (parts.size <= itemTypeIndex) {
                return Unknown("", uri)
            }

            val itemType = parts[itemTypeIndex]
            val id = if (itemTypeIndex + 1 < parts.size) {
                parts.subList(itemTypeIndex + 1, parts.size).joinToString(":")
            } else {
                ""
            }

            return when (itemType) {
                "album" -> Album(id)
                "artist" -> Artist(id)
                "episode" -> Episode(id)
                "playlist" -> Playlist(id, username)
                "show" -> Show(id)
                "track" -> Track(id)
                "local" -> {
                    val localParts = id.split(":")
                    Local(
                        artist = localParts.getOrElse(0) { "" },
                        albumTitle = localParts.getOrElse(1) { "" },
                        trackTitle = localParts.getOrElse(2) { "" },
                        durationSeconds = localParts.getOrNull(3)?.toLongOrNull() ?: 0L
                    )
                }

                else -> Unknown(id, itemType)
            }
        }
    }

    override val isAlbum: Boolean get() = itemType == "album"
    override val isArtist: Boolean get() = itemType == "artist"
    override val isEpisode: Boolean get() = itemType == "episode"
    override val isPlaylist: Boolean get() = itemType == "playlist"
    override val isShow: Boolean get() = itemType == "show"
    override val isTrack: Boolean get() = itemType == "track"
    override val isLocal: Boolean get() = itemType == "local"
}

sealed class OutifyUri {
    abstract fun toUriString(): String
    open val id: String get() = toUriString().substringAfterLast(":")

    open val isAlbum: Boolean get() = false
    open val isArtist: Boolean get() = false
    open val isEpisode: Boolean get() = false
    open val isPlaylist: Boolean get() = false
    open val isShow: Boolean get() = false
    open val isTrack: Boolean get() = false
    open val isLocal: Boolean get() = false
    open val isLiked: Boolean get() = this is Liked
    open val isArtistLiked: Boolean get() = this is ArtistLiked

    data object Liked : OutifyUri() {
        override fun toUriString(): String = "outify:liked"
    }

    data class ArtistLiked(val artistId: String) : OutifyUri() {
        override fun toUriString(): String = "outify:liked:artist:$artistId"
    }

    override fun toString(): String {
        return this.toUriString()
    }

    companion object {

        fun fromUriString(uri: String): OutifyUri {
            val parts = uri.split(":").filter { it.isNotEmpty() }

            return when (parts.firstOrNull()) {
                "spotify" -> {
                    val spotifyUri = SpotifyUri.fromUriString(uri)
                    spotifyUri
                }

                "outify" -> {
                    when {
                        parts.size >= 4 && parts[1] == "liked" && parts[2] == "artist" -> {
                            ArtistLiked(parts[3])
                        }

                        parts.size >= 2 && parts[1] == "liked" -> Liked

                        parts.size >= 3 -> {
                            val kind = parts[1]
                            val id = parts[2]
                            SpotifyUri.Unknown(id, kind)
                        }

                        else -> SpotifyUri.Unknown("", "")
                    }
                }

                else -> {
                    if (parts.size >= 2) {
                        val kind = parts[0]
                        val id = parts[1]
                        SpotifyUri.Unknown(id, kind)
                    } else {
                        SpotifyUri.Unknown("", "")
                    }
                }
            }
        }
    }
}
