package cc.tomko.outify.core.connect

import cc.tomko.outify.data.repository.CollectionSession
import kotlinx.serialization.Serializable

@Serializable
data class ConnectTarget(
    val session: CollectionSession,
    val runtime: Long,
    val connection: Long,
    val ownership: Long,
    val deviceId: String,
    val trackKey: String,
)

/** Catalog metadata survives playback/queue UID changes, unlike a seek command. */
internal fun ConnectTarget.metadataScope(): ConnectTarget = copy(trackKey = "")

internal data class ConnectMetadataKey(val scope: ConnectTarget, val uri: String)

internal fun ConnectPlayback.metadataKey(): ConnectMetadataKey = ConnectMetadataKey(target.metadataScope(), uri)

@Serializable
data class ConnectPlayback(
    val target: ConnectTarget,
    val localDeviceId: String,
    val deviceName: String,
    val uri: String,
    val durationMs: Long,
    val positionMs: Long,
    val sampledAtMs: Long,
    val speed: Double,
    val playing: Boolean,
    val buffering: Boolean,
    val shuffle: Boolean,
    val repeatContext: Boolean,
    val repeatTrack: Boolean,
    val volume: Int? = null,
    val allowed: List<String> = emptyList(),
) {
    val isLocal: Boolean get() = target.deviceId.isNotBlank() && target.deviceId == localDeviceId
    val isRemote: Boolean get() = target.deviceId.isNotBlank() && !isLocal
}

@Serializable
data class ConnectPlay(val contextUri: String, val trackUri: String? = null, val shuffle: Boolean? = null)

@Serializable
data class ConnectCommand(
    val target: ConnectTarget,
    val action: String,
    val value: Long? = null,
    val play: ConnectPlay? = null,
)

sealed interface PlaybackRoute {
    data object Local : PlaybackRoute
    data object Unavailable : PlaybackRoute
    data class Remote(val command: ConnectCommand) : PlaybackRoute
}

fun playbackRoute(localAllowed: Boolean, snapshot: ConnectPlayback?, action: String, value: Long? = null): PlaybackRoute {
    if (localAllowed && snapshot?.isLocal == true) return PlaybackRoute.Local
    if (localAllowed) return PlaybackRoute.Unavailable
    return remoteCommand(snapshot, action, value)?.let { PlaybackRoute.Remote(it) } ?: PlaybackRoute.Unavailable
}

/** Capture the target at the gesture, before any dispatcher/debounce delay. */
fun remoteCommand(snapshot: ConnectPlayback?, action: String, value: Long? = null): ConnectCommand? {
    if (snapshot?.isRemote != true || action !in snapshot.allowed) return null
    if (action == "play") return null // Requires a validated context, not a transport-only command.
    if (action == "volume" && (value == null || value !in 0L..65535L)) return null
    if (action == "seek_to" && (value == null || value !in 0L..snapshot.durationMs)) return null
    return ConnectCommand(snapshot.target, action, value)
}

/** Track/context selection follows known ownership; it is never an implicit transfer. */
fun playbackLoadRoute(snapshot: ConnectPlayback?, play: ConnectPlay): PlaybackRoute {
    if (snapshot == null) return PlaybackRoute.Unavailable
    if (snapshot.isLocal || snapshot.target.deviceId.isBlank()) return PlaybackRoute.Local
    if ("play" !in snapshot.allowed || play.contextUri.isBlank()) return PlaybackRoute.Unavailable
    return PlaybackRoute.Remote(ConnectCommand(snapshot.target, "play", play = play))
}

fun dispatchPlaybackLoad(
    snapshot: ConnectPlayback?,
    play: ConnectPlay,
    submitRemote: (ConnectCommand) -> Boolean,
    loadLocal: () -> Boolean,
): Boolean = when (val route = playbackLoadRoute(snapshot, play)) {
    PlaybackRoute.Local -> loadLocal()
    PlaybackRoute.Unavailable -> false
    is PlaybackRoute.Remote -> submitRemote(route.command) // Failure is final, never local fallback.
}
