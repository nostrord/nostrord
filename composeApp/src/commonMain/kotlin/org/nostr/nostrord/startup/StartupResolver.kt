package org.nostr.nostrord.startup

import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.storage.SecureStorage
import org.nostr.nostrord.ui.Screen

/**
 * Resolves the application startup state from persisted data.
 *
 * This resolver is called ONCE during bootstrap, BEFORE any UI is created.
 * It produces a deterministic, single initial state that the UI consumes.
 *
 * IMPORTANT: This must be called AFTER NostrRepository.initialize() completes,
 * but BEFORE any navigation UI is rendered.
 */
object StartupResolver {

    /**
     * External launch context that overrides persisted state.
     * Set by platform code before App() is called.
     *
     * Examples: deep links, notification taps, shortcuts
     */
    var externalLaunchContext: ExternalLaunchContext? = null
        private set

    /**
     * Set an external launch context that overrides persisted state.
     * Must be called BEFORE App() is rendered.
     */
    fun setExternalLaunchContext(context: ExternalLaunchContext) {
        externalLaunchContext = context
    }

    /**
     * Clear external launch context after it has been consumed.
     */
    fun clearExternalLaunchContext() {
        externalLaunchContext = null
    }

    /**
     * Relay URL from external launch context (if any).
     * Read by NostrRepository.initialize() to merge into relay list before connecting.
     */
    val deepLinkRelayUrl: String?
        get() = when (val ctx = externalLaunchContext) {
            is ExternalLaunchContext.OpenGroup -> ctx.relayUrl
            is ExternalLaunchContext.OpenRelay -> ctx.relayUrl
            else -> null
        }

    /**
     * Resolves the initial screen for an authenticated user.
     *
     * Precedence:
     * 1. External launch context (deep link, notification)
     * 2. Persisted last viewed group
     * 3. Default home screen
     *
     * @param pubkey The authenticated user's public key
     */
    fun resolveInitialScreen(pubkey: String): ResolvedScreen {
        // Priority 1: External launch context overrides everything
        val external = externalLaunchContext
        if (external != null) {
            clearExternalLaunchContext() // Consume it
            return when (external) {
                is ExternalLaunchContext.OpenGroup -> ResolvedScreen(
                    screen = Screen.Group(external.groupId, external.groupName),
                    relayUrl = external.relayUrl,
                    inviteCode = external.inviteCode,
                    messageId = external.messageId
                )
                is ExternalLaunchContext.OpenRelay -> ResolvedScreen(
                    screen = Screen.Home,
                    relayUrl = external.relayUrl
                )
                is ExternalLaunchContext.OpenHome -> ResolvedScreen(screen = Screen.Home)
            }
        }

        // Priority 2: Restore persisted group state
        try {
            val lastGroup = SecureStorage.getLastViewedGroup(pubkey)
            if (lastGroup != null) {
                val (groupId, groupName) = lastGroup
                // Validate the group ID is not empty/corrupted
                if (groupId.isNotBlank()) {
                    return ResolvedScreen(Screen.Group(groupId, groupName), restoredFromPersistence = true)
                }
            }
        } catch (e: Exception) {
            // Storage error - clear corrupted state and fall through to default
            try {
                SecureStorage.clearLastViewedGroup(pubkey)
            } catch (_: Exception) {
                // Ignore cleanup errors
            }
        }

        // Priority 3: Default to home screen
        return ResolvedScreen(Screen.Home)
    }

    /**
     * Computes the full AppStartState based on current initialization and auth status.
     *
     * @param isInitialized Whether NostrRepository has finished initializing
     * @param isLoggedIn Whether the user is authenticated
     * @return The resolved startup state
     */
    fun resolve(isInitialized: Boolean, isLoggedIn: Boolean): AppStartState {
        // Not yet initialized - must wait
        if (!isInitialized) {
            return AppStartState.Initializing
        }

        // Not logged in - show login
        if (!isLoggedIn) {
            return AppStartState.Unauthenticated
        }

        // Authenticated - resolve initial screen
        val pubkey = AppModule.nostrRepository.getPublicKey()
        if (pubkey == null) {
            // Edge case: logged in but no pubkey (shouldn't happen, but handle gracefully)
            return AppStartState.Authenticated(
                initialScreen = Screen.Home,
                restoredFromPersistence = false
            )
        }

        val resolved = resolveInitialScreen(pubkey)
        return AppStartState.Authenticated(
            initialScreen = resolved.screen,
            restoredFromPersistence = resolved.restoredFromPersistence,
            deepLinkRelayUrl = resolved.relayUrl,
            deepLinkInviteCode = resolved.inviteCode,
            deepLinkMessageId = resolved.messageId
        )
    }
}

/**
 * External launch contexts that override persisted state.
 */
data class ResolvedScreen(
    val screen: Screen,
    val restoredFromPersistence: Boolean = false,
    val relayUrl: String? = null,
    val inviteCode: String? = null,
    val messageId: String? = null
)

sealed class ExternalLaunchContext {
    data class OpenGroup(
        val groupId: String,
        val groupName: String?,
        val relayUrl: String? = null,
        val inviteCode: String? = null,
        val messageId: String? = null
    ) : ExternalLaunchContext()
    data class OpenRelay(val relayUrl: String) : ExternalLaunchContext()
    data object OpenHome : ExternalLaunchContext()
}
