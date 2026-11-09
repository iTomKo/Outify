package cc.tomko.outify.data.metadata

import cc.tomko.outify.data.repository.decodeCollectionChanges
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class SavedTrackTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val uri = "spotify:track:0000000000000000000001"

    @Test
    fun `collection snapshots retain their source account and native session`() {
        val snapshot = decodeCollectionSnapshot(
            """{"session":{"accountId":"me","sessionId":"native"},"tracks":[{"uri":"$uri","added_at":123}]}""", json,
        )
        assertEquals("me", snapshot.session.accountId)
        assertEquals("native", snapshot.session.sessionId)
        assertEquals(listOf(SavedTrack(uri, 123)), snapshot.tracks)
        for (raw in listOf("[]", """{"tracks":[]}""", """{"session":{"accountId":"","sessionId":"native"},"tracks":[]}""")) {
            try {
                decodeCollectionSnapshot(raw, json)
                fail("Unattributed snapshots must fail")
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test
    fun `saved collection preserves original timestamps and accepts an authoritative empty list`() {
        assertEquals(listOf(SavedTrack(uri, 1_700_000_000)), decodeSavedTracks(
            """[{"uri":"$uri","added_at":1700000000}]""", json,
        ))
        assertTrue(decodeSavedTracks("[]", json).isEmpty())
    }

    @Test
    fun `absent malformed duplicate and invalid responses cannot masquerade as empty membership`() {
        for (raw in listOf(null, "null", "", "{}", "[{}]", """[{"uri":"invalid"}]""", """[{"uri":"$uri"},{"uri":"$uri"}]""")) {
            try {
                decodeSavedTracks(raw, json)
                fail("Expected invalid collection to fail: $raw")
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test
    fun `delta decoding requires an explicit removal flag and a valid track URI`() {
        val changes = decodeCollectionChanges("""[{"uri":"$uri","removed":true}]""", json)
        assertTrue(changes.single().removed)
        for (raw in listOf("""[{"uri":"$uri"}]""", """[{"uri":"invalid","removed":true}]""")) {
            try {
                decodeCollectionChanges(raw, json)
                fail("Expected invalid delta to fail")
            } catch (_: IllegalArgumentException) { }
        }
    }
}
