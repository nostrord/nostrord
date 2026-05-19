package org.nostr.nostrord.network

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.nostr.nostrord.utils.AppError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PublishResultExtensionsTest {
    private val eventId = "a".repeat(64)

    @Test
    fun `empty list summarises as all-zero with unknown reason`() {
        val summary = emptyList<PublishResult>().summarizeFailures()
        assertEquals("rejected=0 timeout=0 error=0 first=unknown", summary)
    }

    @Test
    fun `successes alone summarise as all-zero with unknown reason`() {
        val results = listOf(
            PublishResult.Success(eventId, "ok"),
            PublishResult.Success(eventId, null),
        )
        assertEquals("rejected=0 timeout=0 error=0 first=unknown", results.summarizeFailures())
    }

    @Test
    fun `rejected captures first reason and counts`() {
        val results = listOf(
            PublishResult.Success(eventId, null),
            PublishResult.Rejected(eventId, "auth-required"),
            PublishResult.Rejected(eventId, "blocked"),
        )
        val summary = results.summarizeFailures()
        assertEquals("rejected=2 timeout=0 error=0 first=auth-required", summary)
    }

    @Test
    fun `mixed failure types are counted independently`() {
        val results = listOf(
            PublishResult.Timeout(eventId),
            PublishResult.Rejected(eventId, "denied"),
            PublishResult.Error(eventId, Exception("boom")),
            PublishResult.Timeout(eventId),
        )
        val summary = results.summarizeFailures()
        // first non-null reason is the timeout literal "timeout"
        assertEquals("rejected=1 timeout=2 error=1 first=timeout", summary)
    }

    @Test
    fun `error exception message is preferred over later reasons`() {
        val results = listOf(
            PublishResult.Error(eventId, Exception("network down")),
            PublishResult.Rejected(eventId, "blocked"),
        )
        val summary = results.summarizeFailures()
        assertEquals("rejected=1 timeout=0 error=1 first=network down", summary)
    }

    @Test
    fun `error with null exception message falls through to next reason`() {
        val results = listOf(
            PublishResult.Error(eventId, Exception()),
            PublishResult.Rejected(eventId, "blocked"),
        )
        // Exception with no message — firstReason should still take it (null) and
        // assign on the first non-Success entry, then NOT overwrite on the next.
        // Implementation assigns firstReason once; null assignment is the "no message"
        // case so we expect "unknown" fallback only when nothing else fills it.
        val summary = results.summarizeFailures()
        // The exception path sets firstReason to null (no message), which leaves it
        // unset; the next iteration (Rejected) then fills it with "blocked".
        assertEquals("rejected=1 timeout=0 error=1 first=blocked", summary)
    }

    @Test
    fun `publish rejected error type carries the summary in its message`() {
        val results = listOf(PublishResult.Rejected(eventId, "no-write-access"))
        val error = AppError.Network.PublishRejected(results.summarizeFailures())
        assertTrue(error.message.contains("no-write-access"))
        assertTrue(error.message.startsWith("Publish rejected by all relays"))
    }

    /**
     * NOSTR-004 end-to-end: drives the same recipe NostrRepository.updateProfileMetadata
     * and publishRelayList use — parallel `sendAndAwaitOkOrError` across multiple
     * clients, `none { Success }` check, AppError.Network.PublishRejected construction.
     *
     * Disconnected NostrGroupClient instances short-circuit sendAndAwaitOk to
     * PublishResult.Error("Not connected"), so this exercises the all-fail branch
     * without needing a fake relay.
     */
    @Test
    fun `publish recipe surfaces PublishRejected when no relay accepts`() = runTest {
        val clients = listOf(
            NostrGroupClient("wss://disconnected-1.example"),
            NostrGroupClient("wss://disconnected-2.example"),
            NostrGroupClient("wss://disconnected-3.example"),
        )
        val message = """["EVENT",{"id":"$eventId","kind":0,"content":"{}"}]"""

        val results = clients.map { client ->
            async { client.sendAndAwaitOkOrError(message, eventId) }
        }.awaitAll()

        assertEquals(3, results.size)
        assertTrue(
            results.all { it is PublishResult.Error },
            "disconnected clients must short-circuit to Error, got $results",
        )
        assertTrue(results.none { it is PublishResult.Success })

        val error = AppError.Network.PublishRejected(results.summarizeFailures())
        assertTrue(error.message.contains("error=3"), "expected 3 errors, got: ${error.message}")
        assertTrue(error.message.contains("Not connected"), "expected first reason captured, got: ${error.message}")
    }

    @Test
    fun `publish recipe returns Success-bearing results when any client succeeds`() {
        // Mixed list — the production code's `none { Success }` check is the gate
        // we want to verify; build the list directly since synthesising a Success
        // from a real disconnected client is not possible.
        val results = listOf<PublishResult>(
            PublishResult.Error(eventId, Exception("Not connected")),
            PublishResult.Success(eventId, "accepted"),
            PublishResult.Rejected(eventId, "auth-required"),
        )
        assertEquals(false, results.none { it is PublishResult.Success })
        // summarizeFailures should still report the failures for diagnostic logging,
        // but the gate stops the caller from building a PublishRejected error.
        val summary = results.summarizeFailures()
        assertTrue(summary.contains("rejected=1"))
        assertTrue(summary.contains("error=1"))
    }
}
