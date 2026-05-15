package org.nostr.nostrord.auth

import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.AuthManager
import org.nostr.nostrord.nostr.KeyPair
import org.nostr.nostrord.storage.SecureStorage
import org.nostr.nostrord.storage.clearAllCredentialsForAccount
import org.nostr.nostrord.storage.savePrivateKeyFor
import org.nostr.nostrord.utils.epochMillis

/**
 * High-level coordinator for multi-account operations.
 *
 * Wraps [AccountStore] and [AuthManager] to expose the three user-visible
 * actions: add a new account without disturbing the active session, switch
 * which account is active, and remove an account.
 *
 * Subscription resync (closing pubkey-filtered REQs on the current relay
 * and re-issuing with the new pubkey) is intentionally NOT performed here:
 * the cheapest correct behavior would be a full primary-relay reconnect,
 * which makes switch latency noticeable; we defer that to the UI layer
 * (Phase 5) which can trigger it explicitly. In the meantime, signing and
 * persisted state are correctly scoped, and the next natural reconnect
 * (network blip, app foreground) will resubscribe with the new pubkey.
 */
class AccountManager(
    private val accountStore: AccountStore,
    private val authManager: AuthManager,
) {

    /**
     * Add a new local-key account WITHOUT switching to it.
     *
     * Fails if the derived pubkey already has an Account in the store
     * (re-adding the same nsec is a no-op the UI can surface as "already
     * added; switch to it?").
     */
    fun addLocalAccount(privateKeyHex: String, label: String? = null): Result<Account> {
        return try {
            val pubkey = KeyPair.fromPrivateKeyHex(privateKeyHex).publicKeyHex
            if (accountStore.get(pubkey) != null) {
                return Result.failure(IllegalStateException("Account already exists for this key"))
            }
            SecureStorage.savePrivateKeyFor(pubkey, privateKeyHex)
            val account = Account(
                pubkey = pubkey,
                label = label?.takeIf { it.isNotBlank() }
                    ?: "Account ${accountStore.accounts.value.size + 1}",
                authMethod = AuthMethod.LOCAL,
                addedAt = epochMillis(),
            )
            accountStore.upsert(account)
            Result.success(account)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Activate [accountId]: load its credentials into [AuthManager] and
     * reset every per-account in-memory cache. The new pubkey is what will
     * sign future events.
     */
    suspend fun switchAccount(accountId: String): Result<Unit> {
        val target = accountStore.get(accountId)
            ?: return Result.failure(IllegalArgumentException("No such account: $accountId"))

        if (accountStore.activeId.value == accountId) return Result.success(Unit)

        val ok = authManager.useAccount(target)
        if (!ok) return Result.failure(IllegalStateException("Could not load credentials for $accountId"))

        AppModule.applyActiveAccountChange(target)
        return Result.success(Unit)
    }

    /**
     * Wipe credentials and per-account caches for [accountId]. If it was
     * the active account, the user is logged out and runtime caches reset.
     */
    suspend fun removeAccount(accountId: String) {
        val account = accountStore.get(accountId) ?: return
        val wasActive = accountStore.activeId.value == accountId

        SecureStorage.clearAllCredentialsForAccount(account.pubkey)
        SecureStorage.clearAllJoinedGroupsForAccount(account.pubkey)
        SecureStorage.clearAllJoinedGroupMetadataForAccount(account.pubkey)
        SecureStorage.clearAllMessagesForAccount(account.pubkey)
        SecureStorage.clearPendingEvents(account.pubkey)
        SecureStorage.clearLastViewedGroup(account.pubkey)

        accountStore.remove(accountId)

        if (wasActive) {
            authManager.logout()
            AppModule.applyActiveAccountChange(null)
        }
    }
}
