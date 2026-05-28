package org.nostr.nostrord.web.auth

import kotlinx.coroutines.CancellationException
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.nostr.KeyPair
import org.nostr.nostrord.nostr.Nip07
import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.utils.Result

/**
 * Real-API auth helpers for the web login surfaces (LoginScreen + AddAccountSheet). Each
 * login function returns null on success or a user-facing error string; on success the
 * shared `nostrRepository.isLoggedIn` StateFlow flips and the auth gate swaps to the shell.
 * Adding an account reuses the same calls — `loginSuspend` warm-swaps the active session.
 */
object WebAuth {
    private const val HEX_CHARS = "0123456789abcdefABCDEF"

    /** Accepts an nsec or a 64-char hex private key; returns the hex, or null if invalid. */
    private fun parsePrivateKeyHex(input: String): String? {
        val s = input.trim()
        return when {
            s.startsWith("nsec1") -> (Nip19.decode(s) as? Nip19.Entity.Nsec)?.privkey
            s.length == 64 && s.all { it in HEX_CHARS } -> s.lowercase()
            else -> null
        }
    }

    suspend fun loginWithPrivateKey(input: String, isNewIdentity: Boolean = false): String? {
        val hex = parsePrivateKeyHex(input) ?: return "Invalid private key"
        val pub =
            try {
                KeyPair.fromPrivateKeyHex(hex).publicKeyHex
            } catch (e: Throwable) {
                return "Invalid private key"
            }
        return AppModule.nostrRepository.loginSuspend(hex, pub, isNewIdentity).errorMessage()
    }

    suspend fun loginWithBunker(url: String): String? = when (val r = AppModule.nostrRepository.loginWithBunker(url.trim())) {
        is Result.Success -> null
        is Result.Error -> r.error.message.ifBlank { "Failed to connect to bunker" }
    }

    suspend fun loginWithExtension(): String? {
        val pubkey =
            try {
                Nip07.getPublicKey()
            } catch (c: CancellationException) {
                throw c
            } catch (e: Throwable) {
                return "Extension login failed"
            }
        return AppModule.nostrRepository.loginWithNip07(pubkey).errorMessage()
    }

    suspend fun logout() {
        AppModule.nostrRepository.logout()
    }

    private fun Result<Unit>.errorMessage(): String? = when (this) {
        is Result.Success -> null
        is Result.Error -> error.message.ifBlank { "Login failed" }
    }
}
