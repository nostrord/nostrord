package org.nostr.nostrord.auth

import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.AuthManager
import org.nostr.nostrord.nostr.KeyPair
import org.nostr.nostrord.storage.SecureStorage
import org.nostr.nostrord.storage.clearAllCredentialsForAccount
import org.nostr.nostrord.storage.clearRelayListFor
import org.nostr.nostrord.storage.savePrivateKeyFor
import org.nostr.nostrord.utils.epochMillis

/**
 * High-level coordinator for multi-account operations: add, switch, remove.
 *
 * The switch sequence is implemented (and documented inline) in [switchAccount];
 * a failed credential load leaves the current session intact.
 */
class AccountManager(
    private val accountStore: AccountStore,
    private val authManager: AuthManager,
    private val sessionFactory: AccountSessionFactory,
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
     * Activate [accountId]: load credentials, atomically install a new
     * [AccountSession], reset per-account caches, and reissue relay
     * subscriptions.
     *
     * Order matters here. AuthManager.useAccount creates the key pair / bunker
     * client used for signing; the session factory then wraps THAT instance.
     * Doing this in the opposite order would create two `Nip46Client`s for the
     * same bunker, racing for the relay connection and causing one of them to
     * fail with "already connected", which trips the bunker reconnect logout.
     */
    suspend fun switchAccount(accountId: String): Result<Unit> {
        val target = accountStore.get(accountId)
            ?: return Result.failure(IllegalArgumentException("No such account: $accountId"))

        if (accountStore.activeId.value == accountId) return Result.success(Unit)

        // Phase 1: validate + load credentials. useAccount uses the
        // validate-before-teardown pattern internally, so a missing slot
        // returns false without disturbing the active session.
        val ok = authManager.useAccount(target)
        if (!ok) return Result.failure(IllegalStateException("Could not load credentials for $accountId"))

        // Phase 2: wrap AuthManager's now-loaded credentials in an AccountSession.
        // The signer reuses the same KeyPair / Nip46Client instance AuthManager
        // owns, so there is exactly one active credential per account.
        val newSession = sessionFactory.build(target, authManager)
            ?: return Result.failure(IllegalStateException("Could not build session for $accountId"))

        // Phase 3: atomic swap — old session's scope and signer are cancelled here.
        ActiveAccountManager.activate(newSession)

        // Phase 4: reset per-account in-memory caches.
        AppModule.applyActiveAccountChange(target)

        // Phase 5: reissue relay subscriptions with the new identity.
        AppModule.nostrRepository.reloadForActiveAccount()

        return Result.success(Unit)
    }

    /**
     * Wipe credentials and per-account caches for [accountId].
     *
     * When the removed account was active:
     *  - If any other account remains, automatically switch to the first one.
     *    Removing one of several accounts should not punt the user back to the
     *    login screen — they still have signed-in identities to fall back on.
     *  - If none remain, log out and clear the active session.
     *
     * Returns the account the user is now active as (or null if logged out).
     */
    suspend fun removeAccount(accountId: String): Account? {
        val account = accountStore.get(accountId) ?: return accountStore.active
        val wasActive = accountStore.activeId.value == accountId
        // Pick the fallback BEFORE mutating the store so the list is still
        // intact. Most-recently-added first feels closer to "the other account
        // the user just used" than alphabetical / id order.
        val fallback = if (wasActive) {
            accountStore.accounts.value
                .filter { it.id != accountId }
                .maxByOrNull { it.addedAt }
        } else null

        SecureStorage.clearAllCredentialsForAccount(account.pubkey)
        SecureStorage.clearAllJoinedGroupsForAccount(account.pubkey)
        SecureStorage.clearAllJoinedGroupMetadataForAccount(account.pubkey)
        SecureStorage.clearAllMessagesForAccount(account.pubkey)
        SecureStorage.clearPendingEvents(account.pubkey)
        SecureStorage.clearLastViewedGroup(account.pubkey)
        SecureStorage.clearRelayListFor(account.pubkey)

        accountStore.remove(accountId)

        if (!wasActive) return accountStore.active

        if (fallback != null) {
            // Reuse switchAccount so the new identity goes through the same
            // session-swap + cache-reset path as a manual switch. Failure here
            // (e.g. fallback credentials were never persisted) drops the user
            // to the logged-out state — better than a half-active session.
            val result = switchAccount(fallback.id)
            if (result.isSuccess) return fallback
        }

        authManager.logout()
        AppModule.applyActiveAccountChange(null)
        return null
    }
}
