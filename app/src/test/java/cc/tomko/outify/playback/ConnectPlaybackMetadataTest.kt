package cc.tomko.outify.playback

import cc.tomko.outify.core.connect.ConnectPlayback
import cc.tomko.outify.core.connect.ConnectTarget
import cc.tomko.outify.core.connect.hydrateConnectMetadata
import cc.tomko.outify.core.connect.remoteCommand
import cc.tomko.outify.core.model.PlayableAudio
import cc.tomko.outify.core.model.Track
import cc.tomko.outify.core.model.toPlayableAudio
import cc.tomko.outify.data.repository.CollectionSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectPlaybackMetadataTest {
    private fun playback() = ConnectPlayback(
        target = ConnectTarget(CollectionSession("account", "login"), 7, 4, 2, "speaker", "pending-playback"),
        localDeviceId = "outify", deviceName = "Speaker", uri = "spotify:track:abc",
        durationMs = 180000, positionMs = 12000, sampledAtMs = System.currentTimeMillis(),
        speed = 1.0, playing = true, buffering = false, shuffle = false,
        repeatContext = false, repeatTrack = false, allowed = listOf("seek_to", "skip_next"),
    )

    private fun audio(uri: String = "spotify:track:abc") = PlayableAudio(
        id = uri.substringAfterLast(':'), uri = uri, name = "Resolved title", covers = emptyList(), duration = 180000,
    )

    @Test fun `empty and failed reads retry without another track or snapshot change`() = runTest {
        val holder = PlaybackStateHolder()
        val snapshot = playback()
        holder.applyConnectPlayback(snapshot)
        val snapshots = MutableStateFlow<ConnectPlayback?>(snapshot)
        var reads = 0
        var failures = 0
        backgroundScope.launch {
            hydrateConnectMetadata(snapshots, holder, resolve = {
                when (++reads) {
                    1 -> null
                    2 -> throw IOException("temporary metadata failure")
                    else -> audio()
                }
            }, onFailure = { failures++ })
        }
        runCurrent()
        assertEquals(1, reads)
        advanceTimeBy(1000); runCurrent()
        assertEquals(2, reads)
        advanceTimeBy(2000); runCurrent()
        assertEquals("Resolved title", holder.state.value.currentAudio?.name)
        assertEquals(3, reads)
        assertEquals(1, failures)
        assertFalse(holder.localPlaybackAllowed)
        advanceTimeBy(30000); runCurrent()
        assertEquals(3, reads)
    }

    @Test fun `playback ID settling during metadata lookup does not cancel or discard its result`() = runTest {
        val holder = PlaybackStateHolder()
        val first = playback()
        holder.applyConnectPlayback(first)
        val snapshots = MutableStateFlow<ConnectPlayback?>(first)
        val result = CompletableDeferred<PlayableAudio>()
        var reads = 0
        backgroundScope.launch {
            hydrateConnectMetadata(snapshots, holder, resolve = { ++reads; result.await() })
        }
        runCurrent()
        val settled = first.copy(target = first.target.copy(trackKey = "settled-playback|queue-uid"))
        holder.applyConnectPlayback(settled)
        snapshots.value = settled
        runCurrent()
        assertEquals(1, reads)
        result.complete(audio())
        runCurrent()
        assertEquals("Resolved title", holder.state.value.currentAudio?.name)
        assertEquals(settled.target, holder.state.value.remoteTarget)
        // Metadata is reusable, but seeks must still carry an exact playback-instance guard.
        assertNotEquals(remoteCommand(first, "seek_to", 1000)?.target, remoteCommand(settled, "seek_to", 1000)?.target)
        val tick = settled.copy(positionMs = 15000, target = settled.target.copy(trackKey = "another-uid"))
        holder.applyConnectPlayback(tick)
        snapshots.value = tick
        runCurrent()
        assertEquals("Resolved title", holder.state.value.currentAudio?.name)
        assertEquals(1, reads)
    }

    @Test fun `late metadata cannot cross account runtime connection ownership device or track changes`() = runTest {
        val first = playback()
        val replacements = listOf(
            first.copy(target = first.target.copy(session = CollectionSession("other-account", "other-login"))),
            first.copy(target = first.target.copy(runtime = 8)),
            first.copy(target = first.target.copy(connection = 5)),
            first.copy(target = first.target.copy(ownership = 3)),
            first.copy(target = first.target.copy(deviceId = "another-speaker")),
            first.copy(uri = "spotify:track:def", target = first.target.copy(trackKey = "next-track")),
        )
        for (next in replacements) {
            val holder = PlaybackStateHolder()
            holder.applyConnectPlayback(first)
            val snapshots = MutableStateFlow<ConnectPlayback?>(first)
            val oldResult = CompletableDeferred<PlayableAudio>()
            val newResult = CompletableDeferred<PlayableAudio>()
            var reads = 0
            val job = backgroundScope.launch {
                hydrateConnectMetadata(snapshots, holder, resolve = {
                    if (++reads == 1) withContext(NonCancellable) { oldResult.await() } else newResult.await()
                })
            }
            runCurrent()
            holder.applyConnectPlayback(next)
            snapshots.value = next
            runCurrent()
            oldResult.complete(audio().copy(name = "Stale title"))
            runCurrent()
            assertNotEquals("Stale title", holder.state.value.currentAudio?.name)
            newResult.complete(audio(next.uri))
            runCurrent()
            assertEquals("Resolved title", holder.state.value.currentAudio?.name)
            assertEquals(next.target, holder.state.value.remoteTarget)
            job.cancel()
        }
    }

    @Test fun `disconnect cancels pending metadata retries`() = runTest {
        val holder = PlaybackStateHolder()
        val first = playback()
        holder.applyConnectPlayback(first)
        val snapshots = MutableStateFlow<ConnectPlayback?>(first)
        var reads = 0
        backgroundScope.launch {
            hydrateConnectMetadata(snapshots, holder, resolve = { ++reads; null })
        }
        runCurrent()
        snapshots.value = null
        holder.connectUnavailable()
        runCurrent()
        advanceTimeBy(60000); runCurrent()
        assertEquals(1, reads)
        assertNull(holder.state.value.currentAudio)
    }

    @Test fun `local position metadata repairs same type track changes and unresolved placeholders`() {
        val holder = PlaybackStateHolder()
        val local = playback().copy(target = playback().target.copy(deviceId = "outify"))
        holder.applyConnectPlayback(local)
        assertTrue(holder.needsLocalMetadata(local.uri))
        val track = Track.dummy().copy(id = "abc", uri = local.uri)
        holder.setAudio(track.toPlayableAudio())
        assertFalse(holder.needsLocalMetadata(local.uri))
        assertTrue(holder.needsLocalMetadata("spotify:track:def"))
        // A stale snapshot lookup cannot overwrite a newer decoder track.
        holder.setLocalSnapshotAudio("spotify:track:def", audio("spotify:track:def"))
        assertEquals(track.name, holder.state.value.currentAudio?.name)
        holder.applyConnectPlayback(playback())
        assertFalse(holder.needsLocalMetadata(local.uri))
    }
}
