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
import org.nostr.nostrord.nostr.Nip49
import org.nostr.nostrord.storage.SecureStorage
import org.nostr.nostrord.storage.getEncryptedPrivateKeyFor
import org.nostr.nostrord.storage.saveEncryptedPrivateKeyFor

/**
 * Change the password of a password-protected (ncryptsec) account, shared by the web and native
 * Security settings so the two stay identical. An account logged in with an ncryptsec keeps only
 * that ncryptsec on disk and is unlocked per session; this rotates the password by decrypting the
 * stored ncryptsec with the current one (which also verifies it) and re-encrypting under the new
 * one (NIP-49). The underlying key never changes, so the running session is unaffected; the new
 * password applies at the next unlock.
 *
 * This is distinct from the desktop-only app-store passphrase ([org.nostr.nostrord.storage.PassphraseSettings]).
 *
 * The storage and crypto seams are injectable so the logic is testable without SecureStorage.
 */
class SecurityViewModel(
    private val pubkey: String? = AppModule.nostrRepository.getPublicKey(),
    private val readNcryptsec: (String) -> String? = { SecureStorage.getEncryptedPrivateKeyFor(it) },
    private val writeNcryptsec: (String, String) -> Unit = { pk, nc -> SecureStorage.saveEncryptedPrivateKeyFor(pk, nc) },
    private val cryptoDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    /** True when the active account stores its key as a password-protected ncryptsec. */
    val isPasswordProtected: Boolean = pubkey?.let { readNcryptsec(it) != null } ?: false

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

    fun setCurrent(value: String) = edit { _current.value = value }

    fun setNew(value: String) = edit { _new.value = value }

    fun setConfirm(value: String) = edit { _confirm.value = value }

    private inline fun edit(block: () -> Unit) {
        block()
        _error.value = null
        _success.value = false
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
                    }
                },
                onFailure = { _error.value = "Could not change the password." },
            )
        }
    }

    private companion object {
        const val MIN_PASSPHRASE = 8
    }
}
