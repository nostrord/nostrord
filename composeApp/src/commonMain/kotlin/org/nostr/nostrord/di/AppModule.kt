package org.nostr.nostrord.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.nostr.nostrord.network.AuthManager
import org.nostr.nostrord.network.NostrRepository
import org.nostr.nostrord.network.managers.ConnectionManager
import org.nostr.nostrord.network.managers.ConnectionStats
import org.nostr.nostrord.network.managers.GroupManager
import org.nostr.nostrord.network.managers.LiveCursorStore
import org.nostr.nostrord.network.managers.MetadataManager
import org.nostr.nostrord.network.managers.OutboxManager
import org.nostr.nostrord.network.managers.PendingEventManager
import org.nostr.nostrord.network.managers.RelayMetadataManager
import org.nostr.nostrord.network.managers.SessionManager
import org.nostr.nostrord.network.managers.AdaptiveConfig
import org.nostr.nostrord.network.managers.MuxSubscriptionTracker
import org.nostr.nostrord.network.managers.UnreadManager
import org.nostr.nostrord.network.outbox.EventDeduplicator
import org.nostr.nostrord.network.outbox.RelayListManager
import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.nostr.Nip27
import org.nostr.nostrord.notifications.FocusTracker
import org.nostr.nostrord.notifications.NotificationPermission
import org.nostr.nostrord.notifications.NotificationRequest
import org.nostr.nostrord.notifications.NotificationService
import org.nostr.nostrord.notifications.playNotificationSound
import org.nostr.nostrord.settings.FeatureFlags
import org.nostr.nostrord.settings.NotificationSettings
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

    val connStats: ConnectionStats by lazy { ConnectionStats() }

    // Lazy initialization of dependencies
    val relayListManager: RelayListManager by lazy {
        RelayListManager(
            bootstrapRelays = RelayListManager.DEFAULT_BOOTSTRAP_RELAYS,
            connectionManager = connectionManager
        )
    }

    val connectionManager: ConnectionManager by lazy {
        ConnectionManager(scope = appScope, connStats = connStats, adaptiveConfig = adaptiveConfig)
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

    val muxTracker: MuxSubscriptionTracker by lazy { MuxSubscriptionTracker() }

    val adaptiveConfig: AdaptiveConfig by lazy {
        AdaptiveConfig(connStats = connStats, scope = appScope)
    }

    val groupManager: GroupManager by lazy {
        GroupManager(
            connectionManager = connectionManager,
            scope = appScope,
            pendingEventManager = pendingEventManager,
            liveCursorStore = liveCursorStore,
            connStats = connStats,
            muxTracker = muxTracker,
            adaptiveConfig = adaptiveConfig,
            onNewMessagesFlushed = { groupId, newMessages ->
                unreadManager.onMessagesFlushed(groupId, newMessages)
            },
        )
    }

    val metadataManager: MetadataManager by lazy {
        MetadataManager(
            connectionManager = connectionManager,
            outboxManager = outboxManager,
            scope = appScope
        )
    }

    val focusTracker: FocusTracker by lazy { FocusTracker() }

    val notificationService: NotificationService by lazy { NotificationService() }

    val unreadManager: UnreadManager by lazy {
        UnreadManager(
            // groupManager.isGroupJoined() is scoped to the active primary relay —
            // wrong for our purpose: we need cross-relay membership so notifications
            // fire for joined groups on background relays too. Scan all relay buckets.
            isJoined = { groupId ->
                groupManager.joinedGroupsByRelay.value.values.any { groupId in it }
            },
            isRestricted = { groupId -> groupManager.restrictedGroups.value.containsKey(groupId) },
            isAppFocused = { focusTracker.isAppFocused.value },
            onUnreadIncrement = { groupId, message, _ ->
                // Sound — gated by the user-facing toggle in Settings → Notifications.
                // Platform actuals no-op on unsupported targets (iOS for now).
                if (notificationSettings.soundEnabled.value) {
                    playNotificationSound()
                }

                // Desktop popup — web-only; gated on platform support, granted permission,
                // and the user's toggle. The browser itself decides whether to surface
                // the popup based on tab focus.
                if (notificationSettings.systemNotificationsEnabled.value &&
                    notificationService.isSupported() &&
                    notificationService.permission.value == NotificationPermission.Granted) {
                    val groupName = groupManager.groups.value.firstOrNull { it.id == groupId }?.name
                        ?: groupId.take(8)
                    val authorName = displayLabelFor(message.pubkey, prefixAt = false)
                        ?: (message.pubkey.take(8) + "…")
                    val preview = resolveMentionsForNotification(message.content).take(120)

                    notificationService.notify(
                        NotificationRequest(
                            groupId = groupId,
                            title = groupName,
                            body = "$authorName: $preview",
                        )
                    )
                }
            },
        )
    }

    val relayMetadataManager: RelayMetadataManager by lazy {
        RelayMetadataManager(scope = appScope)
    }

    val featureFlags: FeatureFlags by lazy { FeatureFlags() }

    val notificationSettings: NotificationSettings by lazy { NotificationSettings() }

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
            connStats = connStats,
            scope = appScope
        )
    }

    /**
     * Get the singleton NostrRepository instance.
     * This provides backward compatibility with code that uses NostrRepository directly.
     */
    fun getRepository(): NostrRepository = nostrRepository

    /**
     * Look up a human-readable label for [pubkey] from the metadata cache.
     * Prefers the NIP-01 `name` (short handle), falls back to `display_name`.
     * Returns null if no metadata is cached, so callers can decide a fallback
     * (e.g. truncated pubkey) and trigger an async fetch.
     */
    private fun displayLabelFor(pubkey: String, prefixAt: Boolean): String? {
        val meta = metadataManager.userMetadata.value[pubkey] ?: return null
        val name = meta.name?.takeIf { it.isNotBlank() }
            ?: meta.displayName?.takeIf { it.isNotBlank() }
            ?: return null
        return if (prefixAt) "@$name" else name
    }

    /**
     * Replace `nostr:npub1…` / `nostr:nprofile1…` (and their bare bech32 forms)
     * inside [content] with `@<name>` resolved from the metadata cache. When a
     * referenced pubkey isn't cached, fall back to a short bech32 (`@npub1abc…`)
     * and kick off an async fetch so the next notification can resolve it.
     *
     * Other entity types (note/nevent/naddr) are left untouched — turning event
     * references into a readable summary needs the event itself, which isn't
     * available here.
     */
    private fun resolveMentionsForNotification(content: String): String {
        val matches = Nip27.findReferenceMatches(content)
        if (matches.isEmpty()) return content
        val sb = StringBuilder()
        var cursor = 0
        val toFetch = mutableSetOf<String>()
        for ((range, ref) in matches) {
            val pubkey = when (val e = ref.entity) {
                is Nip19.Entity.Npub -> e.pubkey
                is Nip19.Entity.Nprofile -> e.pubkey
                else -> null
            } ?: continue
            sb.append(content, cursor, range.first)
            val label = displayLabelFor(pubkey, prefixAt = false)
            if (label != null) {
                sb.append('@').append(label)
            } else {
                toFetch += pubkey
                sb.append('@').append(ref.bech32.take(12)).append('…')
            }
            cursor = range.last + 1
        }
        sb.append(content, cursor, content.length)
        if (toFetch.isNotEmpty()) {
            appScope.launch { nostrRepository.requestUserMetadata(toFetch) }
        }
        return sb.toString()
    }
}
