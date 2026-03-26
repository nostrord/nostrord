package org.nostr.nostrord.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.nostr.nostrord.network.AuthManager
import org.nostr.nostrord.network.NostrRepository
import org.nostr.nostrord.network.managers.ConnectionManager
import org.nostr.nostrord.network.managers.GroupManager
import org.nostr.nostrord.network.managers.LiveCursorStore
import org.nostr.nostrord.network.managers.MetadataManager
import org.nostr.nostrord.network.managers.OutboxManager
import org.nostr.nostrord.network.managers.PendingEventManager
import org.nostr.nostrord.network.managers.RelayMetadataManager
import org.nostr.nostrord.network.managers.SessionManager
import org.nostr.nostrord.network.managers.UnreadManager
import org.nostr.nostrord.network.outbox.EventDeduplicator
import org.nostr.nostrord.network.outbox.RelayListManager
import org.nostr.nostrord.storage.SecureStorage

/**
 * Simple dependency injection container.
 * Provides singleton instances of all managers and the repository.
 */
object AppModule {
    // Coroutine scope for the entire app
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Global event deduplicator — single instance shared across all managers.
    // Runs TTL eviction once per hour so long sessions don't accumulate stale entries.
    val eventDeduplicator: EventDeduplicator by lazy {
        EventDeduplicator().also { dedup ->
            appScope.launch {
                while (true) {
                    delay(60 * 60 * 1000L) // 1 hour
                    dedup.evictExpired()
                }
            }
        }
    }

    // Lazy initialization of dependencies
    val relayListManager: RelayListManager by lazy {
        RelayListManager(
            bootstrapRelays = RelayListManager.DEFAULT_BOOTSTRAP_RELAYS,
            connectionManager = connectionManager
        )
    }

    val connectionManager: ConnectionManager by lazy {
        ConnectionManager(scope = appScope)
    }

    val sessionManager: SessionManager by lazy {
        SessionManager(
            authManager = AuthManager,
            scope = appScope
        )
    }

    val outboxManager: OutboxManager by lazy {
        OutboxManager(
            connectionManager = connectionManager,
            relayListManager = relayListManager,
            scope = appScope
        )
    }

    val pendingEventManager: PendingEventManager by lazy {
        PendingEventManager(
            connectionManager = connectionManager,
            scope = appScope
        )
    }

    val liveCursorStore: LiveCursorStore by lazy {
        LiveCursorStore()
    }

    val groupManager: GroupManager by lazy {
        GroupManager(
            connectionManager = connectionManager,
            scope = appScope,
            pendingEventManager = pendingEventManager,
            liveCursorStore = liveCursorStore
        )
    }

    val metadataManager: MetadataManager by lazy {
        MetadataManager(
            connectionManager = connectionManager,
            outboxManager = outboxManager,
            scope = appScope
        )
    }

    val unreadManager: UnreadManager by lazy {
        UnreadManager()
    }

    val relayMetadataManager: RelayMetadataManager by lazy {
        RelayMetadataManager(scope = appScope)
    }

    val nostrRepository: NostrRepository by lazy {
        NostrRepository(
            connectionManager = connectionManager,
            sessionManager = sessionManager,
            groupManager = groupManager,
            metadataManager = metadataManager,
            outboxManager = outboxManager,
            unreadManager = unreadManager,
            pendingEventManager = pendingEventManager,
            relayMetadataManager = relayMetadataManager,
            liveCursorStore = liveCursorStore,
            scope = appScope
        )
    }

    /**
     * Get the singleton NostrRepository instance.
     * This provides backward compatibility with code that uses NostrRepository directly.
     */
    fun getRepository(): NostrRepository = nostrRepository
}
