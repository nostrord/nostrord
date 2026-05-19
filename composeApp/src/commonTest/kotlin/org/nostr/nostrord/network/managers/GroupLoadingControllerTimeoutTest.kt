package org.nostr.nostrord.network.managers

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for NOSTR-008: timeout never produces HasMore.
 *
 * Pre-change behavior: handleTimeout transitioned to HasMore when at least one
 * message had arrived, which made the UI auto-paginate against a flaky relay.
 * Post-change: timeout always yields Error(TIMEOUT) or Error(PARTIAL_TIMEOUT)
 * with the cursor preserved when messages were received.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupLoadingControllerTimeoutTest {
    @Test
    fun `timeout with no messages yields Error TIMEOUT and null cursor`() = runTest {
        val scope = TestScope(testScheduler)
        val c = GroupLoadingController(groupId = "g".repeat(64), scope = scope, timeoutMs = 50L)
        val subId = c.startInitialLoad()
        assertNotNull(subId)
        advanceTimeBy(200)
        runCurrent()

        val state = c.state.value
        assertTrue(state is GroupLoadingState.Error, "expected Error, got $state")
        assertEquals(GroupLoadingState.ErrorReason.TIMEOUT, state.reason)
        assertNull(state.cursor, "no messages means no cursor to preserve")
        scope.cancel()
    }

    @Test
    fun `timeout after partial delivery yields PARTIAL_TIMEOUT with advanced cursor`() = runTest {
        val scope = TestScope(testScheduler)
        val c = GroupLoadingController(groupId = "g".repeat(64), scope = scope, timeoutMs = 50L)
        val subId = c.startInitialLoad()
        assertNotNull(subId)

        c.trackMessage(subId, timestamp = 1_700_000_000L, eventId = "e".repeat(64))

        advanceTimeBy(200)
        runCurrent()

        val state = c.state.value
        assertTrue(state is GroupLoadingState.Error, "expected Error, got $state")
        assertEquals(
            GroupLoadingState.ErrorReason.PARTIAL_TIMEOUT,
            state.reason,
            "partial delivery must surface as PARTIAL_TIMEOUT, not HasMore",
        )
        assertNotNull(state.cursor, "cursor must be preserved so retry can resume")
        assertEquals(1, state.cursor!!.totalReceived)
        scope.cancel()
    }

    @Test
    fun `timeout never produces HasMore even with many messages`() = runTest {
        val scope = TestScope(testScheduler)
        val c = GroupLoadingController(groupId = "g".repeat(64), scope = scope, timeoutMs = 50L)
        val subId = c.startInitialLoad()!!
        repeat(10) { i ->
            c.trackMessage(subId, timestamp = 1_700_000_000L - i, eventId = i.toString().padStart(64, '0'))
        }
        advanceTimeBy(200)
        runCurrent()

        val state = c.state.value
        assertTrue(state is GroupLoadingState.Error, "timeout must never become HasMore (NOSTR-008)")
        scope.cancel()
    }

    @Test
    fun `EOSE before timeout transitions to HasMore`() = runTest {
        val scope = TestScope(testScheduler)
        val c = GroupLoadingController(groupId = "g".repeat(64), scope = scope, timeoutMs = 10_000L)
        val subId = c.startInitialLoad()!!
        c.trackMessage(subId, timestamp = 1_700_000_000L, eventId = "f".repeat(64))

        val handled = c.handleEose(subId, relayUrl = "wss://relay.example.com")
        assertTrue(handled)

        val state = c.state.value
        // After EOSE with at least one message on the initial load, the state
        // goes to HasMore (initial load never marks Exhausted directly).
        assertTrue(state is GroupLoadingState.HasMore, "EOSE-driven path still produces HasMore")
        scope.cancel()
    }
}
