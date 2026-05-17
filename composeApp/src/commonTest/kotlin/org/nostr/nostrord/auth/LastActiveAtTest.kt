package org.nostr.nostrord.auth

import org.nostr.nostrord.storage.SecureStorage
import org.nostr.nostrord.storage.clearLastActiveAt
import org.nostr.nostrord.storage.getLastActiveAt
import org.nostr.nostrord.storage.saveLastActiveAt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round-trip tests for the per-account "last active at" timestamp used to
 * compute a catch-up `since` on account switch.
 */
class LastActiveAtTest {
    private val pubA = "a".repeat(64)
    private val pubB = "b".repeat(64)

    @AfterTest
    fun tearDown() {
        SecureStorage.clearLastActiveAt(pubA)
        SecureStorage.clearLastActiveAt(pubB)
    }

    @Test
    fun `save and get round-trip for a single pubkey`() {
        SecureStorage.saveLastActiveAt(pubA, 1_700_000_000L)
        assertEquals(1_700_000_000L, SecureStorage.getLastActiveAt(pubA))
    }

    @Test
    fun `get returns zero for an unknown pubkey`() {
        // No prior save and AfterTest of previous tests clears it.
        assertEquals(0L, SecureStorage.getLastActiveAt(pubB))
    }

    @Test
    fun `pubkeys are independent`() {
        SecureStorage.saveLastActiveAt(pubA, 1_700_000_000L)
        SecureStorage.saveLastActiveAt(pubB, 1_800_000_000L)
        assertEquals(1_700_000_000L, SecureStorage.getLastActiveAt(pubA))
        assertEquals(1_800_000_000L, SecureStorage.getLastActiveAt(pubB))
    }

    @Test
    fun `blank pubkey is a no-op for both read and write`() {
        SecureStorage.saveLastActiveAt("", 12345L)
        assertEquals(0L, SecureStorage.getLastActiveAt(""))
    }

    @Test
    fun `clear erases the value`() {
        SecureStorage.saveLastActiveAt(pubA, 1_700_000_000L)
        SecureStorage.clearLastActiveAt(pubA)
        assertEquals(0L, SecureStorage.getLastActiveAt(pubA))
    }

    @Test
    fun `successive saves overwrite forward and backward`() {
        // No monotonicity guarantee at the storage layer — the heartbeat is
        // expected to advance forward, but the API does not enforce it.
        SecureStorage.saveLastActiveAt(pubA, 100L)
        SecureStorage.saveLastActiveAt(pubA, 50L)
        assertEquals(50L, SecureStorage.getLastActiveAt(pubA))
        SecureStorage.saveLastActiveAt(pubA, 200L)
        assertEquals(200L, SecureStorage.getLastActiveAt(pubA))
        assertTrue(SecureStorage.getLastActiveAt(pubA) > 0L)
    }
}
