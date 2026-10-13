package cc.tomko.outify.data.dao

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * SQLite builds before 3.32 (Android 11 and older) cap bound parameters at 999,
 * so `IN (:ids)` with more ids throws "too many SQL variables".
 */
internal const val SQL_IN_CHUNK_SIZE = 900

internal suspend fun <K, T> List<K>.chunkedQuery(
    query: suspend (List<K>) -> List<T>,
): List<T> = chunked(SQL_IN_CHUNK_SIZE).flatMap { query(it) }

internal fun <K, T> List<K>.chunkedObserve(
    observe: (List<K>) -> Flow<List<T>>,
): Flow<List<T>> {
    if (isEmpty()) return observe(this)
    val flows = chunked(SQL_IN_CHUNK_SIZE).map(observe)
    if (flows.size == 1) return flows.single()
    return combine(flows) { parts -> parts.flatMap { it } }
}
