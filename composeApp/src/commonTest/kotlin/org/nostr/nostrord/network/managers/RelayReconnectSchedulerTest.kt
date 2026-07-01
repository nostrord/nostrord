package org.nostr.nostrord.network.managers

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 2 of the relay-pool-fold: RelayReconnectScheduler is now the single reconnect
 * driver for every pool relay, focused included, so it gets its first direct test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RelayReconnectSchedulerTest {
    private val relayUrl = "wss://example.com"

    @Test
    fun `onAttempt reports every scheduled retry in order, past the fast phase`() = runTest {
        val scope = TestScope(testScheduler)
        val attempts = mutableListOf<Int>()
        val scheduler = RelayReconnectScheduler(
            scope = scope,
            isRelayActive = { true },
            doReconnect = { false },
        )
        scheduler.schedule(
            relayUrl,
            priority = RelayReconnectScheduler.Priority.ACTIVE,
            onAttempt = { attempts.add(it) },
        )
        advanceTimeBy(200_000L)
        runCurrent()

        assertTrue(attempts.size > RelayReconnectScheduler.MAX_FAST_ATTEMPTS)
        assertEquals((1..attempts.size).toList(), attempts)

        // doReconnect never succeeds, so the scheduler retries forever (by design — see
        // the slow-retry-loop test) — cancel it or runTest's trailing drain never idles.
        scope.cancel()
    }

    @Test
    fun `stops retrying once doReconnect succeeds`() = runTest {
        val scope = TestScope(testScheduler)
        var calls = 0
        val scheduler = RelayReconnectScheduler(
            scope = scope,
            isRelayActive = { true },
            doReconnect = {
                calls++
                calls >= 3
            },
        )
        scheduler.schedule(relayUrl, priority = RelayReconnectScheduler.Priority.ACTIVE)

        advanceTimeBy(60_000L)
        runCurrent()
        assertEquals(3, calls)

        // No further attempts even after more time passes.
        advanceTimeBy(120_000L)
        runCurrent()
        assertEquals(3, calls)
    }

    @Test
    fun `slow phase stops retrying once isRelayActive turns false`() = runTest {
        val scope = TestScope(testScheduler)
        var active = true
        var calls = 0
        val scheduler = RelayReconnectScheduler(
            scope = scope,
            isRelayActive = { active },
            doReconnect = {
                calls++
                false
            },
        )
        scheduler.schedule(relayUrl, priority = RelayReconnectScheduler.Priority.BACKGROUND)

        // Drive well past the fast phase (BACKGROUND caps at 30s/attempt with jitter).
        advanceTimeBy(300_000L)
        runCurrent()
        val callsAfterFastPhase = calls
        assertTrue(callsAfterFastPhase >= RelayReconnectScheduler.MAX_FAST_ATTEMPTS)

        active = false
        // One more flat 30s slow-phase tick should now stop instead of retrying again.
        advanceTimeBy(RelayReconnectScheduler.SLOW_RETRY_DELAY_MS + 1_000L)
        runCurrent()
        assertEquals(callsAfterFastPhase, calls)
    }

    @Test
    fun `ACTIVE priority retries more often than BACKGROUND in the same window`() = runTest {
        var activeCalls = 0
        val activeScope = TestScope(testScheduler)
        RelayReconnectScheduler(
            scope = activeScope,
            isRelayActive = { true },
            doReconnect = {
                activeCalls++
                false
            },
        ).schedule(relayUrl, priority = RelayReconnectScheduler.Priority.ACTIVE)

        var backgroundCalls = 0
        val backgroundScope = TestScope(testScheduler)
        RelayReconnectScheduler(
            scope = backgroundScope,
            isRelayActive = { true },
            doReconnect = {
                backgroundCalls++
                false
            },
        ).schedule(relayUrl, priority = RelayReconnectScheduler.Priority.BACKGROUND)

        advanceTimeBy(60_000L)
        runCurrent()

        assertTrue(activeCalls > backgroundCalls)

        // Neither doReconnect ever succeeds, so both retry forever — cancel or runTest's
        // trailing drain never idles.
        activeScope.cancel()
        backgroundScope.cancel()
    }

    @Test
    fun `caps concurrent doReconnect calls at MAX_CONCURRENT`() = runTest {
        val scope = TestScope(testScheduler)
        var running = 0
        var maxObservedRunning = 0
        val releaseGate = CompletableDeferred<Unit>()
        val scheduler = RelayReconnectScheduler(
            scope = scope,
            isRelayActive = { true },
            doReconnect = {
                running++
                maxObservedRunning = maxOf(maxObservedRunning, running)
                releaseGate.await()
                running--
                true
            },
        )
        val relays = List(RelayReconnectScheduler.MAX_CONCURRENT + 2) { "wss://relay$it.example.com" }
        relays.forEach { scheduler.schedule(it, priority = RelayReconnectScheduler.Priority.ACTIVE) }

        advanceTimeBy(20_000L)
        runCurrent()
        assertEquals(RelayReconnectScheduler.MAX_CONCURRENT, maxObservedRunning)

        releaseGate.complete(Unit)
        runCurrent()
    }
}
