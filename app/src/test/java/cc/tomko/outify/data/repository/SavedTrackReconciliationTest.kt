package cc.tomko.outify.data.repository

import cc.tomko.outify.data.database.track.LikedTrackEntity
import cc.tomko.outify.data.metadata.SavedTrack
import org.junit.Assert.*
import org.junit.Test

class SavedTrackReconciliationTest {
    @Test
    fun `membership reconciliation updates only changed rows and keeps Spotify dates`() {
        val cached = listOf(row("a", 0.0, 10_000), row("b", 1.0, 20_000))
        val change = reconcileSavedTracks(
            listOf(SavedTrack(uri("a"), 10), SavedTrack(uri("c"), 30)), cached, 999_000,
        )
        assertEquals(listOf(row("c", 1.0, 30_000)), change.upserts)
        assertEquals(listOf("b"), change.removedIds)
        assertEquals(listOf(uri("c")), change.addedUris)
    }

    @Test
    fun `unchanged collection does not rewrite the table`() {
        val cached = listOf(row("a", 0.0, 10_000))
        val change = reconcileSavedTracks(listOf(SavedTrack(uri("a"), 10)), cached, 99_000)
        assertFalse(change.changed)
    }

    @Test
    fun `missing dates retain cached dates instead of turning every like into a new one`() {
        val cached = listOf(row("a", 0.0, 10_000))
        val change = reconcileSavedTracks(
            listOf(SavedTrack(uri("a")), SavedTrack(uri("b"))), cached, 50_000,
        )
        assertEquals(listOf(row("b", 1.0, 50_000)), change.upserts)
    }

    @Test
    fun `reordering preserves timestamps and genuine empty snapshots remove membership`() {
        val cached = listOf(row("a", 0.0, 10_000), row("b", 1.0, 20_000))
        val change = reconcileSavedTracks(
            listOf(SavedTrack(uri("b")), SavedTrack(uri("a"))), cached, 50_000,
        )
        assertEquals(listOf(row("b", 0.0, 20_000), row("a", 1.0, 10_000)), change.upserts)
        assertTrue(change.addedUris.isEmpty())
        assertEquals(listOf("a", "b"), reconcileSavedTracks(emptyList(), cached, 50_000).removedIds)
    }

    @Test
    fun `delta duplicates are idempotent and additions go to the top without rewriting other rows`() {
        val cached = listOf(row("a", 0.0, 10_000), row("b", 1.0, 20_000))
        val change = applyTrackChanges(cached, listOf(
            CollectionTrackChange(uri("a"), false),
            CollectionTrackChange(uri("c"), false),
            CollectionTrackChange(uri("c"), false),
            CollectionTrackChange(uri("b"), true),
        ), 50_000)
        assertEquals(listOf(row("c", -1.0, 50_000)), change.upserts)
        assertEquals(listOf("b"), change.removedIds)
        assertEquals(listOf(uri("c")), change.addedUris)
    }

    @Test
    fun `delta order handles remove then readd and add then remove`() {
        val cached = listOf(row("a", 0.0, 10_000))
        val change = applyTrackChanges(cached, listOf(
            CollectionTrackChange(uri("a"), true),
            CollectionTrackChange(uri("a"), false),
            CollectionTrackChange(uri("b"), false),
            CollectionTrackChange(uri("b"), true),
        ), 50_000)
        assertEquals(listOf(row("a", -1.0, 50_000)), change.upserts)
        assertTrue(change.removedIds.isEmpty())
        assertEquals(listOf(uri("a")), change.addedUris)
    }

    private fun uri(id: String) = "spotify:track:$id"
    private fun row(id: String, position: Double, date: Long) = LikedTrackEntity(id, position, date)
}
