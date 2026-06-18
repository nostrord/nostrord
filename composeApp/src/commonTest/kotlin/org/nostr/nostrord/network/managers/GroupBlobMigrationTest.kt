package org.nostr.nostrord.network.managers

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.nostr.nostrord.storage.SecureStorage
import org.nostr.nostrord.storage.cache.InMemoryCacheStore
import org.nostr.nostrord.storage.clearMessageBlobMigratedFor
import org.nostr.nostrord.storage.isMessageBlobMigratedFor
import org.nostr.nostrord.storage.setMessageBlobMigratedFor
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val MIGRATE_PUBKEY = "00000000000000000000000000000000000000000000000000000000ba5eba11"
private const val MIGRATE_RELAY = "wss://example.com"
private const val MIGRATE_GROUP = "blob-group"

@OptIn(ExperimentalCoroutinesApi::class)
class GroupBlobMigrationTest {
    @BeforeTest
    fun reset() {
        SecureStorage.clearMessageBlobMigratedFor(MIGRATE_PUBKEY)
        SecureStorage.clearAllMessagesForAccount(MIGRATE_PUBKEY)
    }

    @AfterTest
    fun cleanup() {
        SecureStorage.clearMessageBlobMigratedFor(MIGRATE_PUBKEY)
        SecureStorage.clearAllMessagesForAccount(MIGRATE_PUBKEY)
    }

    @Test
    fun `seeds the cache from the legacy message blob once`() = runTest {
        val scope = TestScope(testScheduler)
        val cache = InMemoryCacheStore()
        val manager = GroupManager(connectionManager = ConnectionManager(scope), scope = scope, cacheStore = cache)
        manager.setCurrentPubkey(MIGRATE_PUBKEY)
        // A legacy last-100 blob from before the CacheStore existed.
        SecureStorage.saveMessagesForGroup(
            MIGRATE_PUBKEY,
            MIGRATE_GROUP,
            """[{"id":"m1","pubkey":"p","content":"hi","createdAt":1,"kind":9,"tags":[]},""" +
                """{"id":"m2","pubkey":"p","content":"yo","createdAt":2,"kind":9,"tags":[]}]""",
        )
        // The group is known (restored joined groups drive the migration scope).
        manager.updateAllRelayJoinedGroups(mapOf(MIGRATE_RELAY to setOf(MIGRATE_GROUP)))

        manager.migrateMessageBlobsToCache(MIGRATE_PUBKEY)
        advanceUntilIdle()

        assertEquals(listOf("m1", "m2"), cache.loadLatest(MIGRATE_PUBKEY, MIGRATE_GROUP, 100).map { it.id })
        assertTrue(SecureStorage.isMessageBlobMigratedFor(MIGRATE_PUBKEY), "migration flag should be set")

        scope.cancel()
    }

    @Test
    fun `is a no-op once already migrated`() = runTest {
        val scope = TestScope(testScheduler)
        val cache = InMemoryCacheStore()
        val manager = GroupManager(connectionManager = ConnectionManager(scope), scope = scope, cacheStore = cache)
        manager.setCurrentPubkey(MIGRATE_PUBKEY)
        SecureStorage.setMessageBlobMigratedFor(MIGRATE_PUBKEY)
        SecureStorage.saveMessagesForGroup(
            MIGRATE_PUBKEY,
            MIGRATE_GROUP,
            """[{"id":"m1","pubkey":"p","content":"hi","createdAt":1,"kind":9,"tags":[]}]""",
        )
        manager.updateAllRelayJoinedGroups(mapOf(MIGRATE_RELAY to setOf(MIGRATE_GROUP)))

        manager.migrateMessageBlobsToCache(MIGRATE_PUBKEY)
        advanceUntilIdle()

        assertTrue(cache.loadLatest(MIGRATE_PUBKEY, MIGRATE_GROUP, 100).isEmpty(), "already-migrated account is skipped")

        scope.cancel()
    }
}
