package org.nostr.nostrord.network.managers

import org.nostr.nostrord.utils.epochMillis

/**
 * Lightweight in-memory diagnostics for relay connections and subscriptions.
 * Data never leaves the process — no persistence, no transmission, no user data.
 * Queryable via [getStats] and [getSummary] for debugging.
 */
class ConnectionStats {

    data class RelayStats(
        var connectCount: Int = 0,
        var disconnectCount: Int = 0,
        var lastReconnectMs: Long = 0,
        var totalReconnectMs: Long = 0,
        var subscriptionsSent: Int = 0,
        var subscriptionsAvoided: Int = 0,
        var requestsAvoided: Int = 0,
        var eventsReceived: Long = 0,
        var eventsDeduplicated: Long = 0,
        var failureCount: Int = 0,
        var stateConflicts: Int = 0
    )

    private val stats = mutableMapOf<String, RelayStats>()
    private val connectStartTimes = mutableMapOf<String, Long>()

    private fun statsFor(relayUrl: String): RelayStats =
        stats.getOrPut(relayUrl) { RelayStats() }

    fun onConnecting(relayUrl: String) {
        connectStartTimes[relayUrl] = epochMillis()
    }

    fun onConnected(relayUrl: String) {
        val s = statsFor(relayUrl)
        s.connectCount++
        connectStartTimes.remove(relayUrl)?.let { start ->
            s.lastReconnectMs = epochMillis() - start
            s.totalReconnectMs += s.lastReconnectMs
        }
    }

    fun onDisconnected(relayUrl: String) {
        statsFor(relayUrl).disconnectCount++
    }

    fun onConnectFailed(relayUrl: String) {
        statsFor(relayUrl).failureCount++
        connectStartTimes.remove(relayUrl)
    }

    fun onSubscriptionSent(relayUrl: String) {
        statsFor(relayUrl).subscriptionsSent++
    }

    fun onSubscriptionAvoided(relayUrl: String) {
        statsFor(relayUrl).subscriptionsAvoided++
    }

    fun onEventReceived(relayUrl: String) {
        statsFor(relayUrl).eventsReceived++
    }

    fun onEventDeduplicated(relayUrl: String) {
        statsFor(relayUrl).eventsDeduplicated++
    }

    fun onRequestAvoided(relayUrl: String) {
        statsFor(relayUrl).requestsAvoided++
    }

    fun onStateConflict(relayUrl: String) {
        statsFor(relayUrl).stateConflicts++
    }

    fun getStats(): Map<String, RelayStats> = stats.toMap()

    fun getSummary(): String = buildString {
        if (stats.isEmpty()) {
            append("No relay stats yet")
            return@buildString
        }
        for ((url, s) in stats) {
            append(url.takeLast(30))
            append(": conn=${s.connectCount}")
            append(" disc=${s.disconnectCount}")
            append(" fail=${s.failureCount}")
            if (s.connectCount > 0) {
                append(" avgReconn=${s.totalReconnectMs / s.connectCount}ms")
            }
            append(" subs=${s.subscriptionsSent}")
            append(" subsAvoided=${s.subscriptionsAvoided}")
            if (s.requestsAvoided > 0) append(" reqsAvoided=${s.requestsAvoided}")
            append(" events=${s.eventsReceived}")
            append(" dedup=${s.eventsDeduplicated}")
            if (s.stateConflicts > 0) append(" conflicts=${s.stateConflicts}")
            append("\n")
        }
    }
}
