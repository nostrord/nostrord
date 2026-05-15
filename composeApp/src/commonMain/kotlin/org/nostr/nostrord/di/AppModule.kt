package org.nostr.nostrord.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.nostr.nostrord.auth.Account
import org.nostr.nostrord.auth.AccountManager
import org.nostr.nostrord.auth.AccountStore
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
import org.nostr.nostrord.notifications.NotificationEntry
import org.nostr.nostrord.notifications.NotificationHistoryStore
import org.nostr.nostrord.notifications.NotificationPermission
import org.nostr.nostrord.notifications.NotificationRequest
import org.nostr.nostrord.notifications.NotificationService
import org.nostr.nostrord.notifications.NotificationType
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

    val accountStore: AccountStore by lazy { AccountStore() }

    val authManager: AuthManager by lazy { AuthManager(accountStore) }

    val accountManager: AccountManager by lazy { AccountManager(accountStore, authManager) }

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
            authManager = authManager,
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

    val notificationHistoryStore: NotificationHistoryStore by lazy { NotificationHistoryStore() }

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
            findMessageAuthor = { messageId ->
                groupManager.findMessageByIdAcrossGroups(messageId)?.second?.pubkey
            },
            onUnreadIncrement = { groupId, message, _ ->
                val selfPubkey = sessionManager.getPublicKey()
                if (selfPubkey == null || message.pubkey != selfPubkey) {
                    val relayUrl = groupManager.getLatestMessageRelayForGroup(groupId)
                        ?: groupManager.getRelayForGroup(groupId) ?: ""
                    val preview = resolveMentionsForNotification(message.content).take(120)
                    val groupName = groupDisplayName(groupId)
                    val relayName = relayDisplayName(relayUrl)
                    notificationHistoryStore.add(
                        NotificationEntry(
                            id = message.id,
                            type = NotificationType.MESSAGE,
                            groupId = groupId,
                            relayUrl = relayUrl,
                            actorPubkey = message.pubkey,
                            createdAt = message.createdAt,
                            preview = preview,
                            messageId = message.id,
                            groupName = groupName,
                            relayName = relayName,
                        )
                    )
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
                        val authorName = displayLabelFor(message.pubkey, prefixAt = false)
                            ?: (message.pubkey.take(8) + "…")
                        notificationService.notify(
                            NotificationRequest(
                                relayUrl = relayUrl,
                                groupId = groupId,
                                title = groupName,
                                body = "$authorName: $preview",
                                messageId = message.id,
                            )
                        )
                    }
                }
            },
            onReplyNotify = { groupId, message ->
                val relayUrl = groupManager.getLatestMessageRelayForGroup(groupId)
                    ?: groupManager.getRelayForGroup(groupId) ?: ""
                val preview = resolveMentionsForNotification(message.content).take(120)
                val groupName = groupDisplayName(groupId)
                val relayName = relayDisplayName(relayUrl)
                notificationHistoryStore.add(
                    NotificationEntry(
                        id = message.id,
                        type = NotificationType.REPLY,
                        groupId = groupId,
                        relayUrl = relayUrl,
                        actorPubkey = message.pubkey,
                        createdAt = message.createdAt,
                        preview = preview,
                        messageId = message.id,
                        groupName = groupName,
                        relayName = relayName,
                    )
                )
                if (notificationSettings.soundEnabled.value) {
                    playNotificationSound()
                }
                if (notificationSettings.systemNotificationsEnabled.value &&
                    notificationService.isSupported() &&
                    notificationService.permission.value == NotificationPermission.Granted) {
                    val authorName = displayLabelFor(message.pubkey, prefixAt = false)
                        ?: (message.pubkey.take(8) + "…")
                    notificationService.notify(
                        NotificationRequest(
                            relayUrl = relayUrl,
                            groupId = groupId,
                            title = groupName,
                            body = "$authorName replied to your message: $preview",
                            messageId = message.id,
                        )
                    )
                }
            },
            onMentionNotify = { groupId, message ->
                val relayUrl = groupManager.getLatestMessageRelayForGroup(groupId)
                    ?: groupManager.getRelayForGroup(groupId) ?: ""
                val preview = resolveMentionsForNotification(message.content).take(120)
                val groupName = groupDisplayName(groupId)
                val relayName = relayDisplayName(relayUrl)
                notificationHistoryStore.add(
                    NotificationEntry(
                        id = message.id,
                        type = NotificationType.MENTION,
                        groupId = groupId,
                        relayUrl = relayUrl,
                        actorPubkey = message.pubkey,
                        createdAt = message.createdAt,
                        preview = preview,
                        messageId = message.id,
                        groupName = groupName,
                        relayName = relayName,
                    )
                )
                if (notificationSettings.soundEnabled.value) {
                    playNotificationSound()
                }
                if (notificationSettings.systemNotificationsEnabled.value &&
                    notificationService.isSupported() &&
                    notificationService.permission.value == NotificationPermission.Granted) {
                    val authorName = displayLabelFor(message.pubkey, prefixAt = false)
                        ?: (message.pubkey.take(8) + "…")
                    notificationService.notify(
                        NotificationRequest(
                            relayUrl = relayUrl,
                            groupId = groupId,
                            title = groupName,
                            body = "$authorName mentioned you: $preview",
                            messageId = message.id,
                        )
                    )
                }
            },
            onReactionNotify = { groupId, reaction ->
                val selfPubkey = sessionManager.getPublicKey()
                if (selfPubkey == null || reaction.pubkey != selfPubkey) {
                    val relayUrl = groupManager.getLatestMessageRelayForGroup(groupId)
                        ?: groupManager.getRelayForGroup(groupId) ?: ""
                    val emoji = reaction.emoji.ifBlank { "+" }
                    val groupName = groupDisplayName(groupId)
                    val relayName = relayDisplayName(relayUrl)
                    notificationHistoryStore.add(
                        NotificationEntry(
                            id = reaction.id,
                            type = NotificationType.REACTION,
                            groupId = groupId,
                            relayUrl = relayUrl,
                            actorPubkey = reaction.pubkey,
                            createdAt = reaction.createdAt,
                            preview = emoji,
                            messageId = reaction.targetEventId,
                            emoji = emoji,
                            groupName = groupName,
                            relayName = relayName,
                        )
                    )
                    if (notificationSettings.soundEnabled.value) {
                        playNotificationSound()
                    }
                    if (notificationSettings.systemNotificationsEnabled.value &&
                        notificationService.isSupported() &&
                        notificationService.permission.value == NotificationPermission.Granted) {
                        val authorName = displayLabelFor(reaction.pubkey, prefixAt = false)
                            ?: (reaction.pubkey.take(8) + "…")
                        notificationService.notify(
                            NotificationRequest(
                                relayUrl = relayUrl,
                                groupId = groupId,
                                title = groupName,
                                body = "$authorName reacted $emoji to your message",
                                messageId = reaction.targetEventId,
                            )
                        )
                    }
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
            notificationHistoryStore = notificationHistoryStore,
            scope = appScope
        )
    }

    /**
     * Get the singleton NostrRepository instance.
     * This provides backward compatibility with code that uses NostrRepository directly.
     */
    fun getRepository(): NostrRepository = nostrRepository

    /**
     * Reset every per-account in-memory cache and rebind it to [account].
     *
     * Called by switchAccount (Phase 4) after the AuthManager has loaded the
     * new account's credentials. Pass null to fully tear down on logout.
     *
     * This does NOT touch connection sockets or shared caches (metadata,
     * relay metadata, dedup, live cursors). Re-subscribing pubkey-filtered
     * REQs is the caller's responsibility.
     */
    suspend fun applyActiveAccountChange(account: Account?) {
        groupManager.clear()
        unreadManager.clear()
        notificationHistoryStore.clear()

        if (account != null) {
            groupManager.setCurrentPubkey(account.pubkey)
            unreadManager.initialize(account.pubkey)
            notificationHistoryStore.initialize(account.pubkey)
        }
    }

    /**
     * Look up a human-readable label for [pubkey] from the metadata cache.
     * Prefers the NIP-01 `name` (short handle), falls back to `display_name`.
     * Returns null if no metadata is cached, so callers can decide a fallback
     * (e.g. truncated pubkey) and trigger an async fetch.
     */
    /**
     * Resolves a group's display name across every relay's cache. The
     * notification callbacks fire for groups on background relays too, so
     * `groupManager.groups` (active relay only) misses them and falls back to
     * the truncated id. Joined-group metadata is also restored from
     * [SecureStorage] at startup, so cached entries survive cold launches.
     */
    private fun groupDisplayName(groupId: String): String {
        val name = groupManager.groupsByRelay.value.values
            .firstNotNullOfOrNull { list ->
                list.firstOrNull { it.id == groupId }?.name?.takeIf { it.isNotBlank() }
            }
        return name ?: groupId.take(8)
    }

    /**
     * NIP-11 display name for [relayUrl] from the metadata cache. Returns null
     * when no entry exists so callers can decide between snapshot omission and
     * a URL-derived label.
     */
    private fun relayDisplayName(relayUrl: String): String? {
        if (relayUrl.isBlank()) return null
        return relayMetadataManager.relayMetadata.value[relayUrl]?.name?.takeIf { it.isNotBlank() }
    }

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
                // Hex fallback (matches Nip19.summary, SystemEventItem, and the
                // author-name fallback in the notification callbacks). Mixing
                // bech32 here was the only place in the app that showed npub
                // for unknown pubkeys, breaking the visual convention.
                sb.append('@').append(pubkey.take(8)).append('…')
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
