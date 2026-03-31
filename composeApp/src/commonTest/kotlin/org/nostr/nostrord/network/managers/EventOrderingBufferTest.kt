package org.nostr.nostrord.network.managers

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.nostr.nostrord.network.NostrGroupClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class EventOrderingBufferTest {

    private fun msg(id: String, createdAt: Long = 1000L) = NostrGroupClient.NostrMessage(
        id = id,
        pubkey = "pub1",
        content = "test",
        createdAt = createdAt,
        kind = 9,
        tags = emptyList()
    )

    // ========================================================================
    // Dynamic window — Checklist item 5 (buffer adapts)
    // ========================================================================

    @Test
    fun `dynamic window provider is respected`() = runTest {
        var currentWindow = 50L
        val flushed = mutableListOf<Pair<String, Int>>()

        val buffer = EventOrderingBuffer(
            scope = this,
            windowProvider = { currentWindow },
        ) { groupId, messages ->
            flushed.add(groupId to messages.size)
        }

        // Enqueue a message with 50ms window
        buffer.enqueue("group1", msg("m1"))
        advanceTimeBy(60)

        assertEquals(1, flushed.size, "Should flush after 50ms window")
        assertEquals("group1", flushed[0].first)

        // Now increase window to 200ms
        currentWindow = 200L
        flushed.clear()

        buffer.enqueue("group1", msg("m2"))
        advanceTimeBy(100)
        assertEquals(0, flushed.size, "Should NOT flush at 100ms with 200ms window")

        advanceTimeBy(110)
        assertEquals(1, flushed.size, "Should flush after 200ms window")
    }

    @Test
    fun `messages are sorted by createdAt in flush`() = runTest {
        val flushed = mutableListOf<List<NostrGroupClient.NostrMessage>>()

        val buffer = EventOrderingBuffer(
            scope = this,
            windowProvider = { 50L },
        ) { _, messages ->
            flushed.add(messages)
        }

        buffer.enqueue("g1", msg("m3", createdAt = 3000))
        buffer.enqueue("g1", msg("m1", createdAt = 1000))
        buffer.enqueue("g1", msg("m2", createdAt = 2000))

        advanceTimeBy(60)

        assertEquals(1, flushed.size)
        val batch = flushed[0]
        assertEquals(listOf(1000L, 2000L, 3000L), batch.map { it.createdAt })
    }

    @Test
    fun `immediate flush when buffer reaches maxBufferSize`() = runTest {
        val flushed = mutableListOf<Int>()

        val buffer = EventOrderingBuffer(
            scope = this,
            windowProvider = { 5000L }, // very long window
            maxBufferSize = 5,
        ) { _, messages ->
            flushed.add(messages.size)
        }

        // Send 5 messages — should flush immediately
        repeat(5) { i -> buffer.enqueue("g1", msg("m$i")) }

        // Don't advance time — flush should happen on the next coroutine dispatch
        advanceTimeBy(1)

        assertEquals(1, flushed.size, "Should flush immediately at maxBufferSize")
        assertEquals(5, flushed[0])
    }

    @Test
    fun `flushAll drains all pending buffers`() = runTest {
        val flushed = mutableListOf<String>()

        val buffer = EventOrderingBuffer(
            scope = this,
            windowProvider = { 5000L },
        ) { groupId, _ ->
            flushed.add(groupId)
        }

        buffer.enqueue("g1", msg("m1"))
        buffer.enqueue("g2", msg("m2"))
        buffer.enqueue("g3", msg("m3"))

        // Nothing flushed yet (long window)
        advanceTimeBy(1)
        assertEquals(0, flushed.size)

        buffer.flushAll()
        advanceTimeBy(1)

        assertEquals(3, flushed.size, "flushAll should drain all groups")
        assertTrue(flushed.containsAll(listOf("g1", "g2", "g3")))
    }
}
