package org.nostr.nostrord.network.managers

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AdaptiveConfigTest {

    /**
     * Helper: creates an AdaptiveConfig with a TestScope, runs assertions,
     * then cancels the background adaptation loop to avoid UncompletedCoroutinesError.
     */
    private fun adaptiveTest(block: suspend TestScope.(AdaptiveConfig, ConnectionStats) -> Unit) = runTest {
        val stats = ConnectionStats()
        val childScope = TestScope(testScheduler)
        val config = AdaptiveConfig(connStats = stats, scope = childScope)
        try {
            block(config, stats)
        } finally {
            childScope.cancel()
        }
    }

    // ========================================================================
    // 5. Auto-Adaptation — Checklist item 5
    // ========================================================================

    @Test
    fun `defaults are safe starting values`() = adaptiveTest { config, _ ->
        assertEquals(AdaptiveConfig.DEFAULT_COOLDOWN_MS, config.requestCooldownMs)
        assertEquals(AdaptiveConfig.DEFAULT_BUFFER_MS, config.bufferWindowMs)
        assertFalse(config.prefetchEnabled)
        assertEquals(2, config.maxBackgroundWork)
    }

    @Test
    fun `stable network yields low cooldown and prefetch enabled`() = adaptiveTest { config, _ ->
        // Simulate low-latency relay connections
        config.recordRelayLatency("wss://fast.relay", 50)
        config.recordRelayLatency("wss://fast.relay", 60)
        config.recordRelayLatency("wss://fast.relay", 40)

        // Trigger adaptation cycle
        advanceTimeBy(AdaptiveConfig.TUNE_INTERVAL_MS + 100)

        assertTrue(config.requestCooldownMs <= 600, "Cooldown should be low on stable network, was ${config.requestCooldownMs}")
        assertTrue(config.bufferWindowMs <= 100, "Buffer should be small when idle, was ${config.bufferWindowMs}")
        assertTrue(config.prefetchEnabled, "Prefetch should be enabled on stable network")
        assertEquals(AdaptiveConfig.Stability.GOOD, config.networkStability)
    }

    @Test
    fun `frequent reconnects increase cooldown and disable prefetch`() = adaptiveTest { config, _ ->
        // Simulate 5 reconnects in rapid succession (unstable)
        repeat(5) { config.recordReconnect() }

        advanceTimeBy(AdaptiveConfig.TUNE_INTERVAL_MS + 100)

        assertTrue(config.requestCooldownMs >= 2000, "Cooldown should be high on unstable network, was ${config.requestCooldownMs}")
        assertFalse(config.prefetchEnabled, "Prefetch should be disabled on unstable network")
        assertEquals(0, config.maxBackgroundWork, "Background work should be suppressed on unstable network")
        assertEquals(AdaptiveConfig.Stability.UNSTABLE, config.networkStability)
    }

    @Test
    fun `high event throughput increases buffer window`() = adaptiveTest { config, _ ->
        // Simulate burst: 50+ events per second
        repeat(6) { config.recordEventBurst(50) }

        advanceTimeBy(AdaptiveConfig.TUNE_INTERVAL_MS + 100)

        assertTrue(config.bufferWindowMs >= 150, "Buffer should be large during burst, was ${config.bufferWindowMs}")
    }

    @Test
    fun `low event rate reduces buffer window for near-instant render`() = adaptiveTest { config, _ ->
        // Simulate idle: very few events
        config.recordEventBurst(2)

        advanceTimeBy(AdaptiveConfig.TUNE_INTERVAL_MS + 100)

        assertTrue(config.bufferWindowMs <= 50, "Buffer should be minimal when idle, was ${config.bufferWindowMs}")
    }

    @Test
    fun `degraded network yields moderate cooldown`() = adaptiveTest { config, _ ->
        // 3 reconnects = degraded
        repeat(3) { config.recordReconnect() }

        advanceTimeBy(AdaptiveConfig.TUNE_INTERVAL_MS + 100)

        assertEquals(AdaptiveConfig.Stability.DEGRADED, config.networkStability)
        assertTrue(config.requestCooldownMs in 1200..1800, "Cooldown should be moderate, was ${config.requestCooldownMs}")
        assertEquals(1, config.maxBackgroundWork)
    }

    // ========================================================================
    // Relay scoring
    // ========================================================================

    @Test
    fun `fastestRelay returns relay with lowest latency`() = adaptiveTest { config, _ ->
        config.recordRelayLatency("wss://slow.relay", 500)
        config.recordRelayLatency("wss://fast.relay", 50)
        config.recordRelayLatency("wss://medium.relay", 200)

        val fastest = config.fastestRelay(listOf("wss://slow.relay", "wss://fast.relay", "wss://medium.relay"))
        assertEquals("wss://fast.relay", fastest)
    }

    @Test
    fun `relay latency uses rolling average`() = adaptiveTest { config, _ ->
        config.recordRelayLatency("wss://relay.test", 100)
        config.recordRelayLatency("wss://relay.test", 200)
        config.recordRelayLatency("wss://relay.test", 300)

        val avg = config.getRelayLatency("wss://relay.test")
        assertEquals(200, avg) // (100+200+300)/3 = 200
    }

    @Test
    fun `unknown relay returns MAX_VALUE latency`() = adaptiveTest { config, _ ->
        assertEquals(Long.MAX_VALUE, config.getRelayLatency("wss://unknown.relay"))
    }

    // ========================================================================
    // Signal eviction
    // ========================================================================

    @Test
    fun `network recovers after unstable period stabilizes`() = adaptiveTest { config, _ ->
        // Make it unstable
        repeat(5) { config.recordReconnect() }
        advanceTimeBy(AdaptiveConfig.TUNE_INTERVAL_MS + 100)
        assertEquals(AdaptiveConfig.Stability.UNSTABLE, config.networkStability)

        // Verify unstable parameters
        assertTrue(config.requestCooldownMs >= 2000)
        assertEquals(0, config.maxBackgroundWork)
    }
}
