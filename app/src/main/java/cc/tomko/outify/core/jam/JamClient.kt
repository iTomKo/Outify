package cc.tomko.outify.core.jam

import cc.tomko.outify.ui.components.bottomsheet.jam.JamListenTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide Jam access. Native code owns the watcher across session rebuilds.
 * Operations suspend without blocking the calling thread. Invitation info/join
 * return null on failure and expose the reason through [lastError]; other operations
 * throw [JamException]. Cancellation does not undo an already-sent Spotify request.
 */
@Singleton
class JamClient internal constructor(
    private val json: Json,
    private val backend: JamBackend,
) {
    @Inject
    constructor(json: Json) : this(json, JamNative)

    private val _state = MutableStateFlow<Jam?>(null)
    val state: StateFlow<Jam?> = _state.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var listener: JamCallback? = null

    /**
     * Called after libInit; registers once and replays native state. Native startup
     * restores current membership with a read-only lookup; it never starts a Jam.
     */
    @Synchronized
    fun initialize() {
        if (listener != null) return
        val callback = object : JamCallback {
            override fun onChanged(json: String) {
                synchronized(this@JamClient) {
                    if (listener !== this) return
                    try {
                        _state.value = this@JamClient.json.decodeFromString<Jam?>(json)
                        _lastError.value = null
                    } catch (error: Exception) {
                        _lastError.value = "Cannot decode Jam state: ${error.message}"
                    }
                }
            }

            override fun onError(reason: String) {
                synchronized(this@JamClient) {
                    if (listener === this) _lastError.value = reason
                }
            }
        }
        listener = callback
        var registered = false
        try {
            registered = backend.registerListener(callback)
            if (!registered) {
                throw failure("Cannot register Jam listener; initialize librespot first")
            }
        } finally {
            if (!registered) listener = null
        }
    }

    /** Detaches this client, without leaving the Spotify Jam. May be initialized again. */
    @Synchronized
    fun close() {
        if (listener == null) return
        listener = null
        backend.unregisterListener()
        _state.value = null
        _lastError.value = null
    }

    /** Starts a Jam, or returns the existing one. */
    suspend fun start(): Jam = request(backend::start)
        ?: throw failure("Spotify returned no Jam after starting one")

    /** Refreshes membership; null means this account is not in a Jam. */
    suspend fun current(): Jam? = request(backend::current)

    /** Owners end the Jam for everyone; guests leave it. No Jam is a no-op. */
    suspend fun leave() {
        request(backend::leave)
    }

    /** Host kicks member with given ID */
    suspend fun kick(id: String) {
        request { callback -> backend.kick(id, callback) }
    }

    /** Reads an invitation without joining it; null on failure, with details in [lastError]. */
    suspend fun info(token: String): Jam? =
        requestOrNull("Spotify returned no Jam invitation information") { callback ->
            backend.info(token, callback)
        }

    /** Joins an invitation; null on failure, with details in [lastError]. */
    suspend fun join(token: String, joinMode: JamListenTarget): Jam? =
        requestOrNull("Spotify did not join invited Jam") { callback ->
            backend.join(token, joinMode.rustValue, callback)
        }

    /**
     * Returns the owner's invitation link, starting a Jam if none exists.
     * Guests cannot generate invitations. Sharing/opening the URL is up to callers.
     */
    suspend fun invite(): String {
        val jam = current() ?: start()
        if (!jam.owner) throw failure("Only the Jam owner can invite others")
        return jam.link?.takeIf { it.isNotBlank() }
            ?: throw failure("Spotify did not provide a Jam invitation link")
    }

    private suspend fun requestOrNull(
        emptyMessage: String,
        send: (JamRequestCallback) -> Boolean,
    ): Jam? = try {
        request(send).also { if (it == null) _lastError.value = emptyMessage }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        _lastError.value = error.message ?: emptyMessage
        null
    }

    private suspend fun request(send: (JamRequestCallback) -> Boolean): Jam? {
        initialize()
        return suspendCancellableCoroutine { continuation ->
            val callback = object : JamRequestCallback {
                override fun onSuccess(json: String) {
                    if (!continuation.isActive) return
                    val result = try {
                        Result.success(this@JamClient.json.decodeFromString<Jam?>(json))
                    } catch (error: Exception) {
                        Result.failure(failure("Cannot decode Jam response", error))
                    }
                    // State is updated only by the listener. A request callback
                    // queued before teardown must not restore a stale snapshot.
                    continuation.resumeWith(result)
                }

                override fun onFailure(reason: String) {
                    if (continuation.isActive) {
                        continuation.resumeWith(Result.failure(failure(reason)))
                    }
                }
            }
            try {
                if (!send(callback)) callback.onFailure("Native Jam request was rejected")
            } catch (error: Exception) {
                callback.onFailure(error.message ?: "Cannot send Jam request")
            }
        }
    }

    private fun failure(message: String, cause: Throwable? = null): JamException {
        _lastError.value = message
        return JamException(message, cause)
    }
}
