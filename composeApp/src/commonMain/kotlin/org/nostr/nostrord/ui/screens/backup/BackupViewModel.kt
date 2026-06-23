package org.nostr.nostrord.ui.screens.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.nostr.nostrord.auth.AuthMethod
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.NostrRepositoryApi
import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.nostr.Nip49
import org.nostr.nostrord.ui.Identifier
import org.nostr.nostrord.ui.nprofileRelayHints

/**
 * Shared state + actions for the "Backup keys" screen, so the Compose and web UIs render the same
 * thing instead of drifting. Both expose the keys through the cycling identifier field:
 *  - the public key cycles npub / nprofile / hex (non-sensitive, shown immediately);
 *  - the private key (LOCAL accounts only) is reveal-gated, then cycles nsec / hex / ncryptsec.
 *
 * ncryptsec (NIP-49) is a password-encrypted export and so can't be a plain cycle value: the UI
 * collects a passphrase and calls [encrypt], which fills [ncryptsec]. Bunker (NIP-46) and NIP-07
 * accounts hold no local key, so [canExportPrivate] is false and the UI shows an explainer instead.
 */
class BackupViewModel(
    private val repo: NostrRepositoryApi = AppModule.nostrRepository,
    val authMethod: AuthMethod? = AppModule.accountStore.active?.authMethod,
    private val cryptoDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    /** npub (default) / nprofile / hex. Empty when signed out. */
    val publicIds: List<Identifier> =
        repo.getPublicKey()?.let { hex ->
            val hints = nprofileRelayHints(repo.getRelayListForPubkey(hex).orEmpty())
            buildList {
                runCatching { Nip19.encodeNpub(hex) }.getOrNull()?.let { add(Identifier("npub", it)) }
                runCatching { Nip19.encodeNprofile(hex, hints) }.getOrNull()?.let { add(Identifier("nprofile", it)) }
                add(Identifier("hex", hex))
            }
        } ?: emptyList()

    /** Only LOCAL accounts hold the key; bunker / NIP-07 sign remotely and expose nothing. */
    val canExportPrivate: Boolean = repo.getPrivateKey() != null

    private val _revealed = MutableStateFlow(false)
    val revealed: StateFlow<Boolean> = _revealed.asStateFlow()

    private val _passphrase = MutableStateFlow("")
    val passphrase: StateFlow<String> = _passphrase.asStateFlow()

    private val _ncryptsec = MutableStateFlow<String?>(null)
    val ncryptsec: StateFlow<String?> = _ncryptsec.asStateFlow()

    private val _encrypting = MutableStateFlow(false)
    val encrypting: StateFlow<Boolean> = _encrypting.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun reveal() {
        _revealed.value = true
    }

    /** Re-mask everything, dropping any generated ncryptsec and its passphrase. */
    fun hide() {
        _revealed.value = false
        _passphrase.value = ""
        _ncryptsec.value = null
        _error.value = null
    }

    fun setPassphrase(value: String) {
        _passphrase.value = value
        _error.value = null
        // A changed passphrase invalidates a previously generated ncryptsec.
        _ncryptsec.value = null
    }

    /** Direct (unencrypted) private formats: nsec then hex. Only meaningful after [reveal]. */
    fun privateDirectIds(): List<Identifier> {
        val hex = repo.getPrivateKey() ?: return emptyList()
        return buildList {
            runCatching { Nip19.encodeNsec(hex) }.getOrNull()?.let { add(Identifier("nsec", it)) }
            add(Identifier("hex", hex))
        }
    }

    /** NIP-49 encrypt the private key under the current [passphrase] into an ncryptsec1 string. */
    fun encrypt() {
        if (_encrypting.value) return
        val hex = repo.getPrivateKey() ?: return
        val pw = _passphrase.value
        if (pw.length < MIN_PASSPHRASE) {
            _error.value = "Use at least $MIN_PASSPHRASE characters."
            return
        }
        _error.value = null
        _encrypting.value = true
        viewModelScope.launch {
            val result =
                withContext(cryptoDispatcher) {
                    runCatching { Nip49.encrypt(hex.hexToByteArray(), pw) }
                }
            _encrypting.value = false
            result.fold(
                onSuccess = { _ncryptsec.value = it },
                onFailure = { _error.value = "Could not encrypt the key." },
            )
        }
    }

    private companion object {
        const val MIN_PASSPHRASE = 6
    }
}
