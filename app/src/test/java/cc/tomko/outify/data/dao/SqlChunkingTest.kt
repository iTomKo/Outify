package cc.tomko.outify.data.dao

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SqlChunkingTest {

    private val ids = (1..2050).map { "id$it" }

    @Test
    fun chunkedQuery_keepsEveryChunkUnderSqliteLimit() = runBlocking {
        val seen = mutableListOf<Int>()
        val result = ids.chunkedQuery { chunk ->
            seen += chunk.size
            chunk
        }
        assertTrue(seen.all { it <= 999 })
        assertEquals(listOf(900, 900, 250), seen)
        assertEquals(ids, result)
    }

    @Test
    fun chunkedObserve_mergesEveryChunk() = runBlocking {
        val seen = mutableListOf<Int>()
        val result = ids.chunkedObserve { chunk ->
            seen += chunk.size
            flowOf(chunk)
        }.first()
        assertTrue(seen.all { it <= 999 })
        assertEquals(ids, result)
    }

    @Test
    fun chunkedObserve_emptyInputStillEmits() = runBlocking {
        val result = emptyList<String>()
            .chunkedObserve { flowOf(emptyList<String>()) }
            .toList()
        assertEquals(listOf(emptyList<String>()), result)
    }
}
