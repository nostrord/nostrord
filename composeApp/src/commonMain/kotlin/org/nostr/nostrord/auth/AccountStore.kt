package org.nostr.nostrord.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.nostr.nostrord.nostr.KeyPair
import org.nostr.nostrord.storage.SecureStorage
import org.nostr.nostrord.storage.saveBunkerClientPrivateKeyFor
import org.nostr.nostrord.storage.saveBunkerUrlFor
import org.nostr.nostrord.storage.savePrivateKeyFor
import org.nostr.nostrord.utils.epochMillis

/**
 * Persistent registry of [Account]s the user has signed in with, plus a pointer
 * to the currently active one.
 *
 * Backed by SecureStorage's generic string prefs. Credentials themselves live
 * in separate pubkey-keyed slots (see SecureStorage `*For(pubkey)` APIs).
 */
class AccountStore {
    private val json = Json { ignoreUnknownKeys = true }

    private val _accounts = MutableStateFlow(loadAccounts())
    val accounts: StateFlow<List<Account>> = _accounts.asStateFlow()

    private val _activeId = MutableStateFlow(loadActiveId())
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    init {
        // An existing multi-account user (upgraded into this build with accounts
        // already persisted) must also be marked initialized, so a later full
        // logout can't let [migrateFromLegacyIfNeeded] resurrect them from a
        // stale legacy slot. upsert() covers freshly added accounts.
        if (_accounts.value.isNotEmpty()) {
            SecureStorage.saveStringPref(KEY_INITIALIZED, "1")
        }
    }

    val active: Account? get() {
        val id = _activeId.value ?: return null
        return _accounts.value.firstOrNull { it.id == id }
    }

    fun get(id: String): Account? = _accounts.value.firstOrNull { it.id == id }

    /**
     * Insert a new account or update an existing one with the same pubkey.
     * Returns the canonical [Account] now stored.
     */
    fun upsert(account: Account): Account {
        val merged =
            _accounts.updateAndGet { current ->
                val existing = current.firstOrNull { it.id == account.id }
                if (existing == null) {
                    current + account
                } else {
                    current.map { if (it.id == account.id) account else it }
                }
            }
        persistAccounts(merged)
        // Once the multi-account store has ever held an account, the legacy
        // global credential slots are vestigial. Mark initialized so a later
        // full logout (which empties the list) can't let
        // [migrateFromLegacyIfNeeded] resurrect an account from a stale legacy
        // slot on the next launch.
        SecureStorage.saveStringPref(KEY_INITIALIZED, "1")
        return account
    }

    /**
     * Remove the account. If it was active, [activeId] is cleared; callers are
     * expected to pick a new active (or log out) afterwards.
     */
    fun remove(id: String) {
        var didRemove = false
        val updated =
            _accounts.updateAndGet { current ->
                if (current.none { it.id == id }) {
                    current
                } else {
                    didRemove = true
                    current.filterNot { it.id == id }
                }
            }
        if (!didRemove) return
        persistAccounts(updated)
        if (_activeId.value == id) {
            _activeId.value = null
            SecureStorage.saveStringPref(KEY_ACTIVE_ID, "")
        }
    }

    fun setActive(id: String?) {
        if (id != null && _accounts.value.none { it.id == id }) return
        _activeId.value = id
        SecureStorage.saveStringPref(KEY_ACTIVE_ID, id ?: "")
    }

    private fun loadAccounts(): List<Account> {
        val raw = SecureStorage.getStringPref(KEY_ACCOUNTS_LIST, "")
        if (raw.isBlank()) return emptyList()
        return try {
            json.decodeFromString(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun loadActiveId(): String? {
        val raw = SecureStorage.getStringPref(KEY_ACTIVE_ID, "")
        return raw.takeIf { it.isNotBlank() }
    }

    private fun persistAccounts(list: List<Account>) {
        try {
            SecureStorage.saveStringPref(KEY_ACCOUNTS_LIST, json.encodeToString(list))
        } catch (e: Exception) {
            println("[AccountStore] persistAccounts failed: ${e.message}")
        }
    }

    /**
     * One-shot migration from the pre-multi-account world to the new model.
     *
     * If [accounts] is empty AND a legacy credential slot is populated, this
     * creates an Account from the most recently relevant credential and copies
     * the credential into a pubkey-keyed slot. The legacy slot is LEFT INTACT
     * so the existing single-account auth path keeps working until Phase 2
     * rewires it. Re-running this method is a no-op once any account exists.
     */
    fun migrateFromLegacyIfNeeded() {
        if (_accounts.value.isNotEmpty()) return
        // The store has held an account before (it was just emptied by a full
        // logout). The legacy slots are stale leftovers — never rebuild an
        // account from them, or logging out the last account would silently
        // sign back in on the next launch.
        if (SecureStorage.getStringPref(KEY_INITIALIZED, "").isNotBlank()) return
        val now = epochMillis()

        // Order matches AuthManager.restoreSession() precedence:
        // NIP-07 → bunker → local private key.
        val nip07Pubkey = SecureStorage.getNip07UserPubkey()
        if (!nip07Pubkey.isNullOrBlank()) {
            val account =
                Account(
                    pubkey = nip07Pubkey,
                    label = "Account 1",
                    authMethod = AuthMethod.NIP07,
                    addedAt = now,
                )
            upsert(account)
            setActive(account.id)
            return
        }

        val bunkerUrl = SecureStorage.getBunkerUrl()
        val bunkerPubkey = SecureStorage.getBunkerUserPubkey()
        if (!bunkerUrl.isNullOrBlank() && !bunkerPubkey.isNullOrBlank()) {
            SecureStorage.saveBunkerUrlFor(bunkerPubkey, bunkerUrl)
            SecureStorage.getBunkerClientPrivateKey()?.let { client ->
                SecureStorage.saveBunkerClientPrivateKeyFor(bunkerPubkey, client)
            }
            val account =
                Account(
                    pubkey = bunkerPubkey,
                    label = "Account 1",
                    authMethod = AuthMethod.BUNKER,
                    addedAt = now,
                )
            upsert(account)
            setActive(account.id)
            return
        }

        val privKey = SecureStorage.getPrivateKey()
        if (!privKey.isNullOrBlank()) {
            val derivedPubkey =
                try {
                    KeyPair.fromPrivateKeyHex(privKey).publicKeyHex
                } catch (_: Exception) {
                    return
                }
            SecureStorage.savePrivateKeyFor(derivedPubkey, privKey)
            val account =
                Account(
                    pubkey = derivedPubkey,
                    label = "Account 1",
                    authMethod = AuthMethod.LOCAL,
                    addedAt = now,
                )
            upsert(account)
            setActive(account.id)
        }
    }

    private companion object {
        const val KEY_ACCOUNTS_LIST = "accounts_list_v1"
        const val KEY_ACTIVE_ID = "active_account_id_v1"
        const val KEY_INITIALIZED = "multi_account_initialized_v1"
    }
}
