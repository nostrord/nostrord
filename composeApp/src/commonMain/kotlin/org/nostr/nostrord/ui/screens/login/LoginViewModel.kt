package org.nostr.nostrord.ui.screens.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.nostr.nostrord.network.NostrRepositoryApi
import org.nostr.nostrord.nostr.Nip07
import org.nostr.nostrord.nostr.Nip46Client
import org.nostr.nostrord.utils.toKotlinResult

class LoginViewModel(
    private val repo: NostrRepositoryApi,
) : ViewModel() {
    val authUrl: StateFlow<String?> = repo.authUrl

    private val _qrUri = MutableStateFlow<String?>(null)
    val qrUri: StateFlow<String?> = _qrUri.asStateFlow()

    private var qrJob: Job? = null

    /**
     * The active QR-flow Nip46Client. Tracked here so [cancelQrSession] can
     * tear its WebSocket connections down — the durable response subscription
     * stays open until disconnect, so simply cancelling [qrJob] is not enough.
     */
    private var qrClient: Nip46Client? = null

    fun clearAuthUrl() = repo.clearAuthUrl()

    fun loginWithPrivateKey(
        privKey: String,
        pubKey: String,
        isNewIdentity: Boolean = false,
        onResult: (Result<Unit>) -> Unit,
    ) {
        viewModelScope.launch {
            onResult(repo.loginSuspend(privKey, pubKey, isNewIdentity).toKotlinResult())
        }
    }

    fun loginWithNip07(
        pubkey: String,
        onResult: (Result<Unit>) -> Unit,
    ) {
        viewModelScope.launch {
            onResult(repo.loginWithNip07(pubkey).toKotlinResult())
        }
    }

    fun loginWithNip07Extension(onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            try {
                val pubkey = Nip07.getPublicKey() // platform call — can throw if extension unavailable
                onResult(repo.loginWithNip07(pubkey).toKotlinResult())
            } catch (e: Exception) {
                onResult(Result.failure(e))
            }
        }
    }

    fun loginWithBunker(
        url: String,
        onResult: (Result<Unit>) -> Unit,
    ) {
        viewModelScope.launch {
            onResult(repo.loginWithBunker(url).map { }.toKotlinResult())
        }
    }

    fun startQrSession(
        onConnected: () -> Unit,
        onError: (String?) -> Unit,
    ) {
        qrJob?.cancel()
        qrClient?.disconnect()
        qrClient = null
        _qrUri.value = null
        qrJob =
            viewModelScope.launch {
                try {
                    val (uri, client) = repo.createNostrConnectSession()
                    qrClient = client
                    _qrUri.value = uri
                    repo.completeNostrConnectLogin(client)
                    qrClient = null
                    onConnected()
                } catch (e: Exception) {
                    qrClient?.disconnect()
                    qrClient = null
                    val message =
                        when {
                            e.message?.contains("cancelled", ignoreCase = true) == true -> null
                            else -> "Connection failed: ${e.message}"
                        }
                    onError(message)
                }
            }
    }

    fun cancelQrSession() {
        qrJob?.cancel()
        qrClient?.disconnect()
        qrClient = null
        _qrUri.value = null
    }

    override fun onCleared() {
        super.onCleared()
        qrJob?.cancel()
        qrClient?.disconnect()
        qrClient = null
    }
}
