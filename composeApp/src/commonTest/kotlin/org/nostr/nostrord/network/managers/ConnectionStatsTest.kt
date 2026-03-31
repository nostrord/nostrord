package org.nostr.nostrord.network.managers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConnectionStatsTest {

    // ========================================================================
    // 2. Resilience — request avoidance tracking (Checklist item 2)
    // ========================================================================

    @Test
    fun `onRequestAvoided increments counter`() {
        val stats = ConnectionStats()
        stats.onRequestAvoided("wss://relay.test")
        stats.onRequestAvoided("wss://relay.test")
        stats.onRequestAvoided("wss://relay.test")

        val relay = stats.getStats()["wss://relay.test"]!!
        assertEquals(3, relay.requestsAvoided)
    }

    @Test
    fun `onSubscriptionAvoided increments counter`() {
        val stats = ConnectionStats()
        stats.onSubscriptionAvoided("wss://relay.test")
        stats.onSubscriptionAvoided("wss://relay.test")

        val relay = stats.getStats()["wss://relay.test"]!!
        assertEquals(2, relay.subscriptionsAvoided)
    }

    @Test
    fun `connect and disconnect tracking`() {
        val stats = ConnectionStats()
        stats.onConnecting("wss://relay.test")
        stats.onConnected("wss://relay.test")
        stats.onDisconnected("wss://relay.test")

        val relay = stats.getStats()["wss://relay.test"]!!
        assertEquals(1, relay.connectCount)
        assertEquals(1, relay.disconnectCount)
    }

    @Test
    fun `reconnect timing is tracked`() {
        val stats = ConnectionStats()
        stats.onConnecting("wss://relay.test")
        // onConnected computes duration from onConnecting timestamp
        stats.onConnected("wss://relay.test")

        val relay = stats.getStats()["wss://relay.test"]!!
        assertTrue(relay.lastReconnectMs >= 0)
        assertTrue(relay.totalReconnectMs >= 0)
    }

    @Test
    fun `failure count tracks failed connections`() {
        val stats = ConnectionStats()
        stats.onConnecting("wss://relay.test")
        stats.onConnectFailed("wss://relay.test")
        stats.onConnecting("wss://relay.test")
        stats.onConnectFailed("wss://relay.test")

        val relay = stats.getStats()["wss://relay.test"]!!
        assertEquals(2, relay.failureCount)
    }

    @Test
    fun `state conflict tracking`() {
        val stats = ConnectionStats()
        stats.onStateConflict("wss://relay.test")

        val relay = stats.getStats()["wss://relay.test"]!!
        assertEquals(1, relay.stateConflicts)
    }

    @Test
    fun `getSummary includes requestsAvoided when non-zero`() {
        val stats = ConnectionStats()
        stats.onConnecting("wss://relay.test")
        stats.onConnected("wss://relay.test")
        stats.onRequestAvoided("wss://relay.test")

        val summary = stats.getSummary()
        assertTrue(summary.contains("reqsAvoided=1"), "Summary should include requestsAvoided: $summary")
    }

    @Test
    fun `per-relay stats are independent`() {
        val stats = ConnectionStats()
        stats.onConnecting("wss://relay-a")
        stats.onConnected("wss://relay-a")
        stats.onRequestAvoided("wss://relay-a")
        stats.onRequestAvoided("wss://relay-a")

        stats.onConnecting("wss://relay-b")
        stats.onConnectFailed("wss://relay-b")

        val a = stats.getStats()["wss://relay-a"]!!
        val b = stats.getStats()["wss://relay-b"]!!

        assertEquals(1, a.connectCount)
        assertEquals(2, a.requestsAvoided)
        assertEquals(0, b.connectCount)
        assertEquals(1, b.failureCount)
    }
}
