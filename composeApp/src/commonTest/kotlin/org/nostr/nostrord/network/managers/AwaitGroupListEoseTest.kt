package org.nostr.nostrord.network.managers

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for NOSTR-006: GroupManager.awaitGroupListEose suspends until the
 * relay's group-list EOSE arrives, with a wall-clock fallback for degraded
 * relays. The previous implementation used a fixed delay(2_000) in
 * NostrRepository — these tests pin the new EOSE-driven behaviour.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AwaitGroupListEoseTest {
    private val relayUrl = "wss://example.com"

    private fun makeManager(scope: TestScope): GroupManager {
        val connManager = ConnectionManager(scope)
        return GroupManager(connectionManager = connManager, scope = scope)
    }

    @Test
    fun `returns true immediately when relay already EOSEd`() = runTest {
        val scope = TestScope(testScheduler)
        val manager = makeManager(scope)
        // Populate the complete-load set by feeding a legacy "group-list" EOSE.
        manager.handleEoseSuspend("group-list", relayUrl)

        var result: Boolean? = null
        scope.launch { result = manager.awaitGroupListEose(relayUrl, timeoutMs = 100) }
        runCurrent()

        assertTrue(result == true, "should return true synchronously when relay is already EOSEd")
        scope.cancel()
    }

    @Test
    fun `suspends until EOSE arrives then returns true`() = runTest {
        val scope = TestScope(testScheduler)
        val manager = makeManager(scope)

        val deferred = scope.async { manager.awaitGroupListEose(relayUrl, timeoutMs = 10_000) }
        runCurrent()
        assertFalse(deferred.isCompleted, "should still be suspended before EOSE")

        // Fire the EOSE.
        manager.handleEoseSuspend("group-list", relayUrl)
        runCurrent()

        assertTrue(deferred.isCompleted)
        assertTrue(deferred.await(), "should return true after EOSE")
        scope.cancel()
    }

    @Test
    fun `returns false on wall-clock fallback when relay never EOSEs`() = runTest {
        val scope = TestScope(testScheduler)
        val manager = makeManager(scope)

        val deferred = scope.async { manager.awaitGroupListEose(relayUrl, timeoutMs = 500) }
        runCurrent()
        assertFalse(deferred.isCompleted)

        advanceTimeBy(600)
        runCurrent()

        assertTrue(deferred.isCompleted)
        assertFalse(deferred.await(), "fallback timeout returns false for degraded relays")
        scope.cancel()
    }

    @Test
    fun `EOSE for unrelated relay does not wake the await`() = runTest {
        val scope = TestScope(testScheduler)
        val manager = makeManager(scope)

        val deferred = scope.async { manager.awaitGroupListEose(relayUrl, timeoutMs = 10_000) }
        runCurrent()

        manager.handleEoseSuspend("group-list", "wss://other.relay")
        runCurrent()

        assertFalse(deferred.isCompleted, "EOSE for a different relay must not complete the await")
        scope.cancel()
    }
}
