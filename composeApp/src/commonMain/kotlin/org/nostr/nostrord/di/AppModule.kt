package org.nostr.nostrord.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.nostr.nostrord.auth.Account
import org.nostr.nostrord.auth.AccountManager
import org.nostr.nostrord.auth.AccountSessionFactory
import org.nostr.nostrord.auth.AccountStore
import org.nostr.nostrord.auth.ActiveAccountManager
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
import org.nostr.nostrord.utils.epochSeconds

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

    // One-shot transient messages surfaced to the user (snackbar). Used for
    // events the user did not explicitly trigger, e.g. a bunker session that
    // got revoked while the user was sitting on another screen.
    private val _systemMessages = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 4)
    val systemMessages: kotlinx.coroutines.flow.SharedFlow<String> = _systemMessages

    val authManager: AuthManager by lazy {
        AuthManager(accountStore).also { am ->
            am.onSessionInvalidated = { invalidatedPubkey ->
                handleSessionInvalidated(invalidatedPubkey)
            }
        }
    }

    val accountSessionFactory: AccountSessionFactory by lazy {
        AccountSessionFactory(appScope)
    }

    val accountManager: AccountManager by lazy {
        AccountManager(accountStore, authManager, accountSessionFactory)
    }

    /**
     * Bunker permission revoked / NIP-07 session lost / etc. Try to keep the
     * user inside the app by switching to another signed-in account.
     *
     * If a fallback account activates, the invalidated account stays in the
     * AccountStore with credentials intact so the user can re-pair later.
     * If NO fallback works (single-account install, or every other account
     * also broken), perform a full teardown: close NIP-29 sockets, cancel
     * in-flight coroutines, clear ActiveAccountManager, wipe the dead
     * account from the store. Without this the app sits in a zombie state
     * with isLoggedIn=false but live WebSockets still answering AUTH
     * challenges and heartbeat-driven sign attempts on a disposed signer,
     * which froze the browser tab.
     */
    private suspend fun handleSessionInvalidated(invalidatedPubkey: String?) {
        if (invalidatedPubkey.isNullOrBlank()) {
            _systemMessages.emit("Session ended. Please sign in.")
            fullTeardown()
            return
        }
        val invalidatedLabel = accountStore.get(invalidatedPubkey)?.label
            ?: "previous account"
        val candidates = accountStore.accounts.value
            .filter { it.pubkey != invalidatedPubkey }
            .sortedByDescending { it.addedAt }

        for (candidate in candidates) {
            val result = accountManager.switchAccount(candidate.id)
            if (result.isSuccess) {
                _systemMessages.emit(
                    "$invalidatedLabel disconnected. Switched to ${candidate.label}."
                )
                return
            }
        }

        _systemMessages.emit("Couldn't reconnect to $invalidatedLabel.")
        fullTeardown()
    }

    /**
     * Close all relay sockets, cancel in-flight coroutines, clear per-account
     * caches and the ActiveAccountManager session, and wipe the dead account
     * from the store. Routes the UI to the login screen via the isLoggedIn /
     * isBunkerVerifying flags that nostrRepository.logout() resets.
     */
    private suspend fun fullTeardown() {
        try { nostrRepository.logout() } catch (_: Throwable) {}
        try { applyActiveAccountChange(null) } catch (_: Throwable) {}
    }

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

    // Unix-seconds timestamp of the most recent account activation. Events
    // with `createdAt < switchInstantSeconds` are catch-up: they predate the
    // user's current session of this account, so they enter the in-app feed
    // but do NOT play sound or fire OS popups. Set on every activation in
    // [applyActiveAccountChange].
    @kotlin.concurrent.Volatile
    private var switchInstantSeconds: Long = 0L

    private fun isRealtime(eventCreatedAt: Long): Boolean =
        eventCreatedAt >= switchInstantSeconds

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
                    val realtime = isRealtime(message.createdAt)
                    // Sound — gated by the user-facing toggle in Settings → Notifications,
                    // and suppressed for catch-up events (older than the current activation)
                    // so a switch-in doesn't trigger a burst of sounds.
                    // Platform actuals no-op on unsupported targets (iOS for now).
                    if (realtime && notificationSettings.soundEnabled.value) {
                        playNotificationSound()
                    }
                    // Desktop popup — web-only; gated on platform support, granted permission,
                    // the user's toggle, and realtime so catch-up events don't pop.
                    if (realtime &&
                        notificationSettings.systemNotificationsEnabled.value &&
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
                val realtime = isRealtime(message.createdAt)
                if (realtime && notificationSettings.soundEnabled.value) {
                    playNotificationSound()
                }
                if (realtime &&
                    notificationSettings.systemNotificationsEnabled.value &&
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
                val realtime = isRealtime(message.createdAt)
                if (realtime && notificationSettings.soundEnabled.value) {
                    playNotificationSound()
                }
                if (realtime &&
                    notificationSettings.systemNotificationsEnabled.value &&
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
                    val realtime = isRealtime(reaction.createdAt)
                    if (realtime && notificationSettings.soundEnabled.value) {
                        playNotificationSound()
                    }
                    if (realtime &&
                        notificationSettings.systemNotificationsEnabled.value &&
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
     * Build and activate an [AccountSession] for the currently active account.
     *
     * Must be called after every successful login and after every cold-start
     * session restore — i.e. anywhere AuthManager has just loaded credentials
     * for an account. The session reuses AuthManager's KeyPair / Nip46Client
     * so there is exactly one credential instance per active account.
     *
     * Idempotent: if no active account exists in [accountStore] or AuthManager
     * has not loaded credentials yet, the existing session is left unchanged.
     */
    suspend fun activateSessionForActiveAccount() {
        val account = accountStore.active ?: return
        val session = accountSessionFactory.build(account, authManager) ?: return
        ActiveAccountManager.activate(session)
    }

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
        // Record the activation instant before any state is cleared. Events
        // with createdAt < this value are treated as catch-up (feed only,
        // no sound/popup). Set to 0 on logout so realtime gating is moot.
        switchInstantSeconds = if (account != null) epochSeconds() else 0L
        // Cancel any OS popups still in flight from the previous account so a
        // notification scheduled for A doesn't surface after B is active.
        try { notificationService.cancelAllPending() } catch (_: Throwable) {}
        groupManager.clear()
        unreadManager.clear()
        notificationHistoryStore.clear()
        // OutboxManager owns the per-account kind:10009 relay list and the
        // user's NIP-65 relay list. Without clearing here, the previous
        // account's relays linger in the rail until a new kind:10009 arrives
        // (and for accounts that never published one, they linger forever).
        outboxManager.clear()
        // Pending events are per-account (signed by the previous account's
        // signer). Replaying them against the new account is wrong.
        pendingEventManager.clear()
        // The "current relay" pointer is per-account in storage but
        // ConnectionManager only reads it on loadSavedRelay(). Clear here so
        // the rail's "current" slot doesn't keep showing the previous
        // account's relay before reloadForActiveAccount runs.
        connectionManager.clearCurrentRelay()

        if (account != null) {
            groupManager.setCurrentPubkey(account.pubkey)
            pendingEventManager.setCurrentPubkey(account.pubkey)
            unreadManager.initialize(account.pubkey)
            notificationHistoryStore.initialize(account.pubkey)
            // Restore the new account's saved relay (no-op if they don't have
            // one yet — a freshly added account starts with a blank rail).
            connectionManager.loadSavedRelay()
        } else {
            // Logout path: clear the active session so signing is impossible.
            ActiveAccountManager.clear()
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
