package org.nostr.nostrord.network

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
        val error = org.nostr.nostrord.utils.AppError.Network.PublishRejected(results.summarizeFailures())
        assertTrue(error.message.contains("no-write-access"))
        assertTrue(error.message.startsWith("Publish rejected by all relays"))
    }
}
