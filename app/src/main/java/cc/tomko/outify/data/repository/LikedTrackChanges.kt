package cc.tomko.outify.data.repository

/** Null means unchanged; an empty list means removal/reordering without additions. */
internal fun addedLikedTrackUris(remote: List<String>, cachedIds: List<String>): List<String>? {
    val remoteIds = remote.map { it.removePrefix("spotify:track:") }
    if (remoteIds == cachedIds) return null
    val cached = cachedIds.toHashSet()
    return remote.filterIndexed { index, _ -> remoteIds[index] !in cached }
}
