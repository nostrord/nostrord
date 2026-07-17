package org.nostr.nostrord.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.nostr.KeyPair
import org.nostr.nostrord.nostr.Nip49
import org.nostr.nostrord.nostr.hexToByteArray
import org.nostr.nostrord.nostr.ncryptsecStorageApplicable
import org.nostr.nostrord.storage.SecureStorage
import org.nostr.nostrord.storage.clearPrivateKeyFor
import org.nostr.nostrord.storage.getEncryptedPrivateKeyFor
import org.nostr.nostrord.storage.getPrivateKeyFor
import org.nostr.nostrord.storage.saveEncryptedPrivateKeyFor

/**
 * Password protection of the active account's key, shared by the web and native Security
 * settings so the two stay identical.
 *
 * Two modes over the same fields:
 *  - [changePassword] rotates the password of an already-protected (ncryptsec) account by
 *    decrypting the stored ncryptsec with the current password (which also verifies it) and
 *    re-encrypting under the new one (NIP-49). The underlying key never changes, so the
 *    running session is unaffected; the new password applies at the next unlock.
 *  - [protectWithPassword] opts a plain-key account into protection after the fact, where
 *    [canProtect]: the stored key is wrapped as an ncryptsec and the plaintext slot is
 *    dropped, exactly what the login-time "protect with a password" option does. Only
 *    offered where plaintext at rest is the alternative ([ncryptsecStorageApplicable],
 *    i.e. the web); platforms with secure storage don't need it.
 *
 * This is distinct from the desktop-only app-store passphrase ([org.nostr.nostrord.storage.PassphraseSettings]).
 *
 * The storage and crypto seams are injectable so the logic is testable without SecureStorage.
 */
class SecurityViewModel(
    private val pubkey: String? = AppModule.nostrRepository.getPublicKey(),
    private val readNcryptsec: (String) -> String? = { SecureStorage.getEncryptedPrivateKeyFor(it) },
    private val writeNcryptsec: (String, String) -> Unit = { pk, nc -> SecureStorage.saveEncryptedPrivateKeyFor(pk, nc) },
    private val readPlainKey: (String) -> String? = { SecureStorage.getPrivateKeyFor(it) },
    private val clearPlainKey: (String) -> Unit = { SecureStorage.clearPrivateKeyFor(it) },
    private val readLegacyPlainKey: () -> String? = { SecureStorage.getPrivateKey() },
    private val clearLegacyPlainKey: () -> Unit = { SecureStorage.clearPrivateKey() },
    private val protectApplicable: Boolean = ncryptsecStorageApplicable,
    private val cryptoDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val _isPasswordProtected = MutableStateFlow(pubkey?.let { readNcryptsec(it) != null } ?: false)

    /** True when the active account stores its key as a password-protected ncryptsec. Flips after [protectWithPassword]. */
    val isPasswordProtected: StateFlow<Boolean> = _isPasswordProtected.asStateFlow()

    /** True when the active account keeps a plain key at rest here and can opt into protection. */
    val canProtect: Boolean
        get() =
            pubkey != null &&
                protectApplicable &&
                !_isPasswordProtected.value &&
                (readPlainKey(pubkey) != null || legacyPlainKeyFor(pubkey) != null)

    /**
     * The legacy single-slot plain key, only when it derives [pubkey]. Session restore falls
     * back to this slot (AuthManager.useAccount), so protection must wrap and clear it too or
     * the account keeps restoring without a password.
     */
    private fun legacyPlainKeyFor(pubkey: String): String? = readLegacyPlainKey()?.takeIf {
        runCatching { KeyPair.fromPrivateKeyHex(it).publicKeyHex }.getOrNull() == pubkey
    }

    private val _current = MutableStateFlow("")
    val current: StateFlow<String> = _current.asStateFlow()

    private val _new = MutableStateFlow("")
    val new: StateFlow<String> = _new.asStateFlow()

    private val _confirm = MutableStateFlow("")
    val confirm: StateFlow<String> = _confirm.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _success = MutableStateFlow(false)
    val success: StateFlow<Boolean> = _success.asStateFlow()

    private val _successMessage = MutableStateFlow<String?>(null)

    /** Feedback line for the last successful action; rendered below whichever form is showing. */
    val successMessage: StateFlow<String?> = _successMessage.asStateFlow()

    fun setCurrent(value: String) = edit { _current.value = value }

    fun setNew(value: String) = edit { _new.value = value }

    fun setConfirm(value: String) = edit { _confirm.value = value }

    private inline fun edit(block: () -> Unit) {
        block()
        _error.value = null
        _success.value = false
        _successMessage.value = null
    }

    fun changePassword() {
        if (_busy.value) return
        val pk = pubkey
        val stored = pk?.let { readNcryptsec(it) }
        if (pk == null || stored == null) {
            _error.value = "This account is not password-protected."
            return
        }
        val cur = _current.value
        val nw = _new.value
        if (nw.length < MIN_PASSPHRASE) {
            _error.value = "Use at least $MIN_PASSPHRASE characters."
            return
        }
        if (nw != _confirm.value) {
            _error.value = "The new passwords do not match."
            return
        }
        _error.value = null
        _success.value = false
        _busy.value = true
        viewModelScope.launch {
            val result =
                withContext(cryptoDispatcher) {
                    runCatching {
                        val key = Nip49.decrypt(stored, cur) ?: return@runCatching null
                        Nip49.encrypt(key, nw)
                    }
                }
            _busy.value = false
            result.fold(
                onSuccess = { newNcryptsec ->
                    if (newNcryptsec == null) {
                        _error.value = "Current password is incorrect."
                    } else {
                        writeNcryptsec(pk, newNcryptsec)
                        _current.value = ""
                        _new.value = ""
                        _confirm.value = ""
                        _success.value = true
                        _successMessage.value = "Password changed."
                    }
                },
                onFailure = { _error.value = "Could not change the password." },
            )
        }
    }

    /**
     * Protect the active plain-key account with a password: wraps the stored key as an
     * ncryptsec (NIP-49) under [new] and drops the plaintext slot. The running session
     * keeps its in-memory key; the password is required from the next session restore on.
     */
    fun protectWithPassword() {
        if (_busy.value) return
        val pk = pubkey
        val hex = pk?.let { readPlainKey(it) ?: legacyPlainKeyFor(it) }
        if (pk == null || hex == null || _isPasswordProtected.value) {
            _error.value = "There is no plain key to protect on this device."
            return
        }
        val nw = _new.value
        if (nw.length < MIN_PASSPHRASE) {
            _error.value = "Use at least $MIN_PASSPHRASE characters."
            return
        }
        if (nw != _confirm.value) {
            _error.value = "The passwords do not match."
            return
        }
        _error.value = null
        _success.value = false
        _busy.value = true
        viewModelScope.launch {
            val result =
                withContext(cryptoDispatcher) {
                    runCatching { Nip49.encrypt(hex.hexToByteArray(), nw) }
                }
            _busy.value = false
            result.fold(
                onSuccess = { ncryptsec ->
                    // Encrypted slot first, plaintext drop second: a crash in between
                    // leaves both readable rather than neither.
                    writeNcryptsec(pk, ncryptsec)
                    clearPlainKey(pk)
                    if (legacyPlainKeyFor(pk) != null) clearLegacyPlainKey()
                    _new.value = ""
                    _confirm.value = ""
                    _isPasswordProtected.value = true
                    _success.value = true
                    _successMessage.value = "Password set. It will be asked for the next time the app starts."
                },
                onFailure = { _error.value = "Could not protect the key." },
            )
        }
    }

    private companion object {
        const val MIN_PASSPHRASE = 6
    }
}
