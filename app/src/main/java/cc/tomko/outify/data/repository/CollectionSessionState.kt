package cc.tomko.outify.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class CollectionSession(val accountId: String, val sessionId: String) {
    fun validate(): CollectionSession = apply {
        require(accountId.isNotBlank() && sessionId.isNotBlank()) { "Invalid collection session identity" }
    }
}

data class CollectionLease(val session: CollectionSession, val generation: Long)

class StaleCollectionSessionException : CancellationException("Collection session was replaced or stopped")

/** A cancelled blocking JNI read can still complete. Its lease must not authorize writes. */
@Singleton
class CollectionSessionState @Inject constructor() {
    private var generation = 0L
    private val _current = MutableStateFlow<CollectionLease?>(null)
    val current: StateFlow<CollectionLease?> = _current.asStateFlow()

    @Synchronized
    fun activate(session: CollectionSession): CollectionLease {
        val lease = CollectionLease(session.validate(), ++generation)
        _current.value = lease
        return lease
    }

    @Synchronized
    fun invalidate(): CollectionLease? {
        val previous = _current.value
        ++generation
        _current.value = null
        return previous
    }

    fun requireCurrent(): CollectionLease = current.value ?: throw StaleCollectionSessionException()

    fun ensureCurrent(lease: CollectionLease) {
        if (current.value != lease) throw StaleCollectionSessionException()
    }

    fun ensureSource(lease: CollectionLease, source: CollectionSession) {
        ensureCurrent(lease)
        if (source != lease.session) throw StaleCollectionSessionException()
    }
}
