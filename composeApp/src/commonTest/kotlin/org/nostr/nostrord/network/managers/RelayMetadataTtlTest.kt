package org.nostr.nostrord.network.managers

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RelayMetadataTtlTest {
    private val now = 1_000_000_000L

    @Test
    fun `a document fetched within the soft TTL is fresh`() {
        assertTrue(RelayMetadataManager.isWithinSoftTtl(now - 60, now)) // 1 min old
        assertTrue(RelayMetadataManager.isWithinSoftTtl(now - 23 * 60 * 60, now)) // 23h old
    }

    @Test
    fun `a document older than the soft TTL is stale`() {
        assertFalse(RelayMetadataManager.isWithinSoftTtl(now - 25 * 60 * 60, now)) // 25h old
        assertFalse(RelayMetadataManager.isWithinSoftTtl(now - RelayMetadataManager.SOFT_TTL_SECONDS, now))
    }

    @Test
    fun `a never-fetched relay is never fresh`() {
        assertFalse(RelayMetadataManager.isWithinSoftTtl(null, now))
    }
}
