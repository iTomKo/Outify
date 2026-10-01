package cc.tomko.outify.data.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class LrcParserTest {

    @Test
    fun `parses two-digit fraction timestamps`() {
        val lines = LrcParser.parseSynced("[00:12.34] First line\n[01:02.50]Second line")

        assertEquals(2, lines.size)
        assertEquals(12_340L, lines[0].timestampMs)
        assertEquals("First line", lines[0].text)
        assertEquals(62_500L, lines[1].timestampMs)
        assertEquals("Second line", lines[1].text)
    }

    @Test
    fun `parses three-digit fraction and colon separators`() {
        val lines = LrcParser.parseSynced("[00:05.123]A\n[00:07:45]B")

        assertEquals(5_123L, lines[0].timestampMs)
        assertEquals(7_450L, lines[1].timestampMs)
    }

    @Test
    fun `skips blank lines and keeps output sorted`() {
        val lines = LrcParser.parseSynced("[00:20.00]Late\n[00:15.00]\n[00:10.00]Early\n\nplain junk")

        assertEquals(listOf(10_000L, 20_000L), lines.map { it.timestampMs })
        assertEquals(listOf("Early", "Late"), lines.map { it.text })
    }

    @Test
    fun `expands repeated timestamps on one line`() {
        val lines = LrcParser.parseSynced("[00:01.00][00:03.00]Chorus")

        assertEquals(listOf(1_000L, 3_000L), lines.map { it.timestampMs })
        assertTrue(lines.all { it.text == "Chorus" })
    }
}

class LrcLibMatchingTest {

    private fun track(
        duration: Double,
        synced: String? = null,
        instrumental: Boolean = false,
    ) = LrcLibTrack(
        duration = duration,
        instrumental = instrumental,
        syncedLyrics = synced,
    )

    @Test
    fun `skips rows without synced lyrics`() {
        val unsynced = track(200.0)
        val syncedNear = track(203.0, synced = "[00:01.00]x")

        assertEquals(syncedNear, pickBestMatch(listOf(unsynced, syncedNear), durationSeconds = 200))
    }

    @Test
    fun `discards candidates outside the duration tolerance`() {
        val tooLong = track(210.0, synced = "[00:01.00]x")
        val tooShort = track(190.0, synced = "[00:01.00]y")

        assertNull(pickBestMatch(listOf(tooLong, tooShort), durationSeconds = 200))
    }

    @Test
    fun `picks the closest duration`() {
        val far = track(204.0, synced = "[00:01.00]x")
        val near = track(201.0, synced = "[00:01.00]y")

        assertEquals(near, pickBestMatch(listOf(far, near), durationSeconds = 200))
    }

    @Test
    fun `ignores instrumental and malformed rows`() {
        val instrumental = track(200.0, synced = "[00:01.00]x", instrumental = true)
        val malformed = track(200.0, synced = "not timestamped")

        assertNull(pickBestMatch(listOf(instrumental, malformed), durationSeconds = 200))
    }

    @Test
    fun `instrumental row has no lines`() {
        assertTrue(track(200.0, synced = "[00:01.00]x", instrumental = true).syncedLines().isEmpty())
    }
}

class LrcLibSelectionTest {
    private val unsynced = LrcLibTrack(duration = 200.0)
    private val synced = LrcLibTrack(duration = 201.0, syncedLyrics = "[00:01.00]Timed")

    @Test
    fun `exact synced lyrics never search`() = runBlocking {
        val lines = resolveLrcLib(synced, 200) { error("unexpected search") }

        assertEquals(1_000L, lines.single().timestampMs)
    }

    @Test
    fun `exact match without synced lyrics searches once`() = runBlocking {
        var searches = 0
        val lines = resolveLrcLib(unsynced, 200) { searches++; listOf(synced) }

        assertEquals(1, searches)
        assertEquals("Timed", lines.single().text)
    }

    @Test
    fun `missing exact match falls back to search`() = runBlocking {
        assertEquals("Timed", resolveLrcLib(null, 200) { listOf(synced) }.single().text)
    }

    @Test
    fun `no usable search result yields empty list`() = runBlocking {
        assertTrue(resolveLrcLib(unsynced, 200) { emptyList() }.isEmpty())
        assertTrue(resolveLrcLib(null, 200) { listOf(synced.copy(duration = 220.0)) }.isEmpty())
    }

    @Test
    fun `instrumental exact match never searches`() = runBlocking {
        val instrumental = synced.copy(instrumental = true)

        assertTrue(resolveLrcLib(instrumental, 200) { error("unexpected search") }.isEmpty())
    }

    @Test
    fun `search failure propagates to the caller`() = runBlocking {
        try {
            resolveLrcLib(unsynced, 200) { throw IOException("offline") }
            fail("failure swallowed")
        } catch (_: IOException) {
        }
    }

    @Test
    fun `search cancellation propagates`() = runBlocking {
        try {
            resolveLrcLib(unsynced, 200) { throw CancellationException("cancel") }
            fail("cancellation swallowed")
        } catch (_: CancellationException) {
        }
    }
}
