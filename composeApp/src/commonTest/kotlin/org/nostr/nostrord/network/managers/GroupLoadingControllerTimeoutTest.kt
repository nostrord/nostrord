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
 * Tests for NOSTR-008 timeout handling.
 *
 * Initial-load timeouts yield Error(TIMEOUT) or Error(PARTIAL_TIMEOUT) with the cursor
 * preserved when messages were received — never HasMore, so the load screen does not
 * auto-paginate against a flaky relay.
 *
 * Pagination timeouts (a scroll-back REQ that never got its EOSE, e.g. the relay dropped
 * the sub during rapid group switching) revert to HasMore, keeping the cursor frontier, so
 * the user's next scroll retries instead of the group being stranded (hasMore=false) until
 * an app restart.
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
    fun `pagination timeout reverts to HasMore so the group is not stranded`() = runTest {
        val scope = TestScope(testScheduler)
        val c = GroupLoadingController(groupId = "g".repeat(64), scope = scope, timeoutMs = 50L)
        // Reach HasMore via an initial load that completes with EOSE.
        val initSub = c.startInitialLoad()!!
        c.trackMessage(initSub, timestamp = 1_700_000_100L, eventId = "a".repeat(64))
        c.handleEose(initSub, relayUrl = "wss://relay.example.com")
        assertTrue(c.state.value is GroupLoadingState.HasMore)

        // Start a scroll-back page, then let it time out with no EOSE (relay dropped the sub).
        val pageSub = c.startPagination()!!.first
        assertNotNull(pageSub)
        advanceTimeBy(200)
        runCurrent()

        val state = c.state.value
        assertTrue(
            state is GroupLoadingState.HasMore,
            "a pagination timeout must revert to HasMore, not strand the group on Error, got $state",
        )
        scope.cancel()
    }

    @Test
    fun `disconnect keeps a loaded group HasMore so pagination is not stranded`() = runTest {
        val scope = TestScope(testScheduler)
        val c = GroupLoadingController(groupId = "g".repeat(64), scope = scope, timeoutMs = 50L)
        val subId = c.startInitialLoad()!!
        c.trackMessage(subId, timestamp = 1_700_000_000L, eventId = "a".repeat(64))
        c.handleEose(subId, relayUrl = "wss://relay.example.com")
        assertTrue(c.state.value is GroupLoadingState.HasMore)

        // A disconnect/reconnect must not zap a loaded group to Idle: the cursor is a timestamp
        // bookmark, not tied to the socket, so the next scroll resumes from the same frontier.
        c.handleDisconnect()
        assertTrue(
            c.state.value is GroupLoadingState.HasMore,
            "disconnect must keep a loaded group HasMore, got ${c.state.value}",
        )
        c.handleReconnect()
        assertTrue(
            c.state.value is GroupLoadingState.HasMore,
            "reconnect must keep a loaded group HasMore, got ${c.state.value}",
        )
        scope.cancel()
    }

    @Test
    fun `disconnect resets a never-loaded group to Idle`() = runTest {
        val scope = TestScope(testScheduler)
        val c = GroupLoadingController(groupId = "g".repeat(64), scope = scope, timeoutMs = 50L)
        c.startInitialLoad() // InitialLoading, no EOSE yet -> no cursor to preserve
        c.handleDisconnect()
        assertTrue(
            c.state.value is GroupLoadingState.Idle,
            "a group still on its initial load must reset to Idle so reconnect re-loads, got ${c.state.value}",
        )
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
    fun `deferred timeout keeps InitialLoading through the AUTH wait and arms after the REQ`() = runTest {
        // Models a private group on a remote signer: the caller enters InitialLoading
        // immediately (skeletons) but defers the load timeout until the NIP-42 AUTH wait
        // completes and the REQ is on the wire. The timeout must NOT expire during the wait.
        val scope = TestScope(testScheduler)
        val c = GroupLoadingController(groupId = "g".repeat(64), scope = scope, timeoutMs = 50L)
        val subId = c.startInitialLoad(armTimeout = false)
        assertNotNull(subId)

        // Well past timeoutMs, but the timeout was never armed: still loading, no false Error.
        advanceTimeBy(500)
        runCurrent()
        assertTrue(
            c.state.value is GroupLoadingState.InitialLoading,
            "deferred timeout must not expire during the AUTH wait, got ${c.state.value}",
        )

        // REQ sent → arm the timeout. Now it expires on a silent/stalled read.
        c.armInitialTimeout(subId)
        advanceTimeBy(200)
        runCurrent()
        assertTrue(
            c.state.value is GroupLoadingState.Error,
            "once armed, a stalled initial read must time out, got ${c.state.value}",
        )
        scope.cancel()
    }

    @Test
    fun `armInitialTimeout is a no-op once the load has settled`() = runTest {
        // If the read already reached EOSE (HasMore) before arming, arming must not re-start
        // a timeout that would later clobber the settled state.
        val scope = TestScope(testScheduler)
        val c = GroupLoadingController(groupId = "g".repeat(64), scope = scope, timeoutMs = 50L)
        val subId = c.startInitialLoad(armTimeout = false)!!
        c.trackMessage(subId, timestamp = 1_700_000_000L, eventId = "a".repeat(64))
        assertTrue(c.handleEose(subId, relayUrl = "wss://relay.example.com"))
        assertTrue(c.state.value is GroupLoadingState.HasMore)

        c.armInitialTimeout(subId)
        advanceTimeBy(500)
        runCurrent()
        assertTrue(
            c.state.value is GroupLoadingState.HasMore,
            "arming after settle must not arm a timeout, got ${c.state.value}",
        )
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

    @Test
    fun `holdInitialLoadForReauth keeps an in-flight initial load in InitialLoading`() = runTest {
        // Models the homepage path: the first read races the relay's auth-required CLOSE.
        // Holding must keep the state in InitialLoading (skeletons), NOT settle to empty,
        // so resubscribeAfterAuth can replay the read post-AUTH.
        val scope = TestScope(testScheduler)
        val c = GroupLoadingController(groupId = "g".repeat(64), scope = scope, timeoutMs = 10_000L)
        val subId = c.startInitialLoad()!!

        assertTrue(c.holdInitialLoadForReauth(subId), "an in-flight initial load must be held")
        assertTrue(
            c.state.value is GroupLoadingState.InitialLoading,
            "held load must stay InitialLoading, got ${c.state.value}",
        )
        scope.cancel()
    }

    @Test
    fun `holdInitialLoadForReauth is a no-op once the load has settled`() = runTest {
        val scope = TestScope(testScheduler)
        val c = GroupLoadingController(groupId = "g".repeat(64), scope = scope, timeoutMs = 10_000L)
        val subId = c.startInitialLoad()!!
        c.trackMessage(subId, timestamp = 1_700_000_000L, eventId = "f".repeat(64))
        assertTrue(c.handleEose(subId, relayUrl = "wss://relay.example.com"))

        assertTrue(!c.holdInitialLoadForReauth(subId), "a settled load must not be held")
        assertTrue(c.state.value is GroupLoadingState.HasMore)
        scope.cancel()
    }

    @Test
    fun `holdInitialLoadForReauth does not hold a pagination load`() = runTest {
        val scope = TestScope(testScheduler)
        val c = GroupLoadingController(groupId = "g".repeat(64), scope = scope, timeoutMs = 10_000L)
        val subId = c.startInitialLoad()!!
        c.trackMessage(subId, timestamp = 1_700_000_000L, eventId = "f".repeat(64))
        c.handleEose(subId, relayUrl = "wss://relay.example.com") // -> HasMore
        val (pageSubId, _) = c.startPagination()!!

        assertTrue(!c.holdInitialLoadForReauth(pageSubId), "pagination must settle normally, not be held")
        scope.cancel()
    }

    private suspend fun reachHasMore(c: GroupLoadingController) {
        val subId = c.startInitialLoad()!!
        c.trackMessage(subId, timestamp = 1_700_000_000L, eventId = "a".repeat(64))
        c.handleEose(subId, relayUrl = "wss://relay.example.com")
        assertTrue(c.state.value is GroupLoadingState.HasMore)
    }

    @Test
    fun `repeated zero-event pagination timeouts stall the group`() = runTest {
        val scope = TestScope(testScheduler)
        val c = GroupLoadingController(groupId = "g".repeat(64), scope = scope, timeoutMs = 50L)
        reachHasMore(c)

        // Two unanswered rounds still revert to HasMore (the NOSTR-008 behavior)...
        repeat(2) {
            c.startPagination()!!
            advanceTimeBy(200)
            runCurrent()
            assertTrue(c.state.value is GroupLoadingState.HasMore, "round ${it + 1} should revert to HasMore")
        }
        // ...the third stalls: with the view parked at the top, each revert re-fires the
        // scroll trigger, so an unresponsive relay would loop timeout windows forever.
        c.startPagination()!!
        advanceTimeBy(200)
        runCurrent()
        assertTrue(
            c.state.value is GroupLoadingState.Stalled,
            "third consecutive zero-event timeout must stall, got ${c.state.value}",
        )
        // Stalled gates the auto-trigger: hasMore is false and startPagination refuses.
        assertTrue(!c.state.value.hasMore)
        assertNull(c.startPagination(), "auto pagination must not resume from Stalled")
        scope.cancel()
    }

    @Test
    fun `retryStalled resumes exactly one attempt from the stalled frontier`() = runTest {
        val scope = TestScope(testScheduler)
        val c = GroupLoadingController(groupId = "g".repeat(64), scope = scope, timeoutMs = 50L)
        reachHasMore(c)
        repeat(3) {
            c.startPagination()!!
            advanceTimeBy(200)
            runCurrent()
        }
        val stalled = c.state.value
        assertTrue(stalled is GroupLoadingState.Stalled)

        assertTrue(c.retryStalled(), "retryStalled must resume from Stalled")
        val resumed = c.state.value
        assertTrue(resumed is GroupLoadingState.HasMore)
        assertEquals(stalled.cursor, resumed.cursor, "retry must keep the stalled frontier")

        // The retry attempt itself timing out unanswered re-stalls immediately.
        c.startPagination()!!
        advanceTimeBy(200)
        runCurrent()
        assertTrue(
            c.state.value is GroupLoadingState.Stalled,
            "an unanswered retry must re-stall, got ${c.state.value}",
        )
        // And retryStalled is a no-op outside Stalled.
        c.retryStalled()
        c.startPagination()!!
        assertTrue(!c.retryStalled(), "retryStalled must be a no-op while Paginating")
        scope.cancel()
    }

    @Test
    fun `completed page resets the stall counter`() = runTest {
        val scope = TestScope(testScheduler)
        // pageSize = 1 so a one-message page completes as HasMore, not Exhausted.
        val c = GroupLoadingController(groupId = "g".repeat(64), scope = scope, pageSize = 1, timeoutMs = 50L)
        reachHasMore(c)

        // Two unanswered rounds, then a page that completes: the relay is answering again.
        repeat(2) {
            c.startPagination()!!
            advanceTimeBy(200)
            runCurrent()
        }
        val (pageSub, _) = c.startPagination()!!
        c.trackMessage(pageSub, timestamp = 1_600_000_000L, eventId = "b".repeat(64))
        c.handleEose(pageSub, relayUrl = "wss://relay.example.com")
        assertTrue(c.state.value is GroupLoadingState.HasMore)

        // The counter reset: two more unanswered rounds still revert to HasMore.
        repeat(2) {
            c.startPagination()!!
            advanceTimeBy(200)
            runCurrent()
            assertTrue(c.state.value is GroupLoadingState.HasMore, "counter must have reset after the completed page")
        }
        scope.cancel()
    }
}
