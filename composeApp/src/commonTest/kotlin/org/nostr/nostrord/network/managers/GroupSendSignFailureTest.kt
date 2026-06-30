package org.nostr.nostrord.network.managers

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.nostr.nostrord.nostr.Event
import org.nostr.nostrord.storage.SecureStorage
import org.nostr.nostrord.storage.cache.InMemoryCacheStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SF_PUBKEY = "00000000000000000000000000000000000000000000000000000000deadbeef"
private const val SF_GROUP = "sign-fail-group"

@OptIn(ExperimentalCoroutinesApi::class)
class GroupSendSignFailureTest {
    @AfterTest
    fun cleanup() = SecureStorage.clearAllMessagesForAccount(SF_PUBKEY)

    private fun manager(scope: TestScope) = GroupManager(connectionManager = ConnectionManager(scope), scope = scope, cacheStore = InMemoryCacheStore())
        .apply { setCurrentPubkey(SF_PUBKEY) }

    private suspend fun GroupManager.sendFailing() = sendMessage(
        groupId = SF_GROUP,
        content = "hello",
        pubKey = SF_PUBKEY,
        signEvent = { throw RuntimeException("bunker timeout") },
    )

    @Test
    fun `a signing failure keeps the optimistic message and offers retry`() = runTest {
        val scope = TestScope(testScheduler)
        val manager = manager(scope)

        manager.sendFailing()
        advanceUntilIdle()

        // The bubble stays on screen (not lost) and resolves to a Failed status that
        // carries the unsigned event so retry can re-sign, not a discarded send.
        val eventId = manager.getMessagesForGroup(SF_GROUP).single().id
        val failed = manager.messageStatus.value[eventId]
        assertTrue(failed is GroupManager.MessageStatus.Failed, "sign failure should mark the message Failed")
        assertNull(failed.eventJson, "no signed JSON exists for a signing failure")
        assertNotNull(failed.unsignedEvent, "retry needs the unsigned event to re-sign")
        assertEquals("bunker timeout", failed.reason)

        scope.cancel()
    }

    @Test
    fun `retry re-signs a previously unsigned failure`() = runTest {
        val scope = TestScope(testScheduler)
        val manager = manager(scope)

        manager.sendFailing()
        advanceUntilIdle()
        val eventId = manager.getMessagesForGroup(SF_GROUP).single().id
        assertTrue(manager.messageStatus.value[eventId] is GroupManager.MessageStatus.Failed)

        // Retry with a signer that now succeeds re-signs and attempts delivery; with no
        // relay client the message stays Sending rather than Failed (it is queued).
        var signed = false
        manager.retrySend(eventId) { event: Event ->
            signed = true
            event.copy(id = event.calculateId(), sig = "ff".repeat(64))
        }
        advanceUntilIdle()

        assertTrue(signed, "retry must re-run the signer")
        assertEquals(GroupManager.MessageStatus.Sending, manager.messageStatus.value[eventId])

        scope.cancel()
    }

    @Test
    fun `a thread send signing failure also keeps the bubble and offers retry`() = runTest {
        val scope = TestScope(testScheduler)
        val manager = manager(scope)

        manager.createThread(
            groupId = SF_GROUP,
            title = "topic",
            content = "hello",
            pubKey = SF_PUBKEY,
            signEvent = { throw RuntimeException("bunker timeout") },
        )
        advanceUntilIdle()

        val root = manager.threadRoots.value[SF_GROUP]?.single()
        assertNotNull(root, "the optimistic thread root stays on screen")
        val failed = manager.messageStatus.value[root.id]
        assertTrue(failed is GroupManager.MessageStatus.Failed, "thread sign failure should mark it Failed")
        assertNotNull(failed.unsignedEvent, "thread retry needs the unsigned event to re-sign")

        scope.cancel()
    }

    @Test
    fun `dismiss drops a failed message`() = runTest {
        val scope = TestScope(testScheduler)
        val manager = manager(scope)

        manager.sendFailing()
        advanceUntilIdle()
        val eventId = manager.getMessagesForGroup(SF_GROUP).single().id

        manager.dismissFailed(SF_GROUP, eventId)

        assertTrue(manager.getMessagesForGroup(SF_GROUP).isEmpty(), "dismiss removes the bubble")
        assertNull(manager.messageStatus.value[eventId], "dismiss clears the status")

        scope.cancel()
    }
}
