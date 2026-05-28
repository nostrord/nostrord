package org.nostr.nostrord.auth

import kotlinx.serialization.Serializable

/**
 * A persisted user identity the app can sign as.
 *
 * The hex [pubkey] doubles as the account id: re-adding the same npub points
 * back at the same persisted state instead of creating a duplicate slot.
 */
@Serializable
data class Account(
    val pubkey: String,
    val label: String,
    val authMethod: AuthMethod,
    val addedAt: Long,
) {
    val id: String get() = pubkey
}

@Serializable
enum class AuthMethod {
    LOCAL,
    BUNKER,
    NIP07,
}

/**
 * Body line for the "Log out?" confirmation dialog, tailored to the user's current
 * auth method. The generic "private key or bunker URL" text confused NIP-07 users
 * (no key to keep, only a browser extension to reconnect) and Bunker users
 * (irrelevant private-key mention). Returning per-method copy from one place keeps
 * native and web in sync.
 */
fun logoutConfirmBody(method: AuthMethod): String = when (method) {
    AuthMethod.LOCAL -> "You will need your private key to log back in."
    AuthMethod.BUNKER -> "You will need your bunker URL to log back in."
    AuthMethod.NIP07 -> "You will need to reconnect your browser extension to log back in."
}

/**
 * Copy for the per-account confirmation dialog the MeMenu shows before
 * [org.nostr.nostrord.auth.AccountManager.removeAccount]. Title / body /
 * confirm-button label all branch on whether the account is the currently active
 * one, mirroring native `RemoveAccountDialog` in MeMenu.kt. Centralised here so
 * native + web share the exact same wording (and stay in sync if it changes).
 */
fun removeAccountDialogTitle(isActive: Boolean, accountLabel: String): String = if (isActive) "Sign out of \"$accountLabel\"?" else "Remove account?"

/**
 * What gets wiped, in the user's own terms:
 *  - LOCAL: the private key the app stores locally — "Credentials".
 *  - BUNKER: only the connection to the remote signer — the key stays on the bunker.
 *  - NIP-07: nothing key-shaped at all — the extension owns the key, the app just
 *    keeps a reference + caches.
 */
private fun erasedSubject(method: AuthMethod, accountLabel: String): String = when (method) {
    AuthMethod.LOCAL -> "Credentials and local data for \"$accountLabel\""
    AuthMethod.BUNKER -> "Bunker connection and local data for \"$accountLabel\""
    AuthMethod.NIP07 -> "Local data for \"$accountLabel\""
}

/** Tail sentence for the active-without-fallback case — "how do I get back in". */
private fun signInAgainHint(method: AuthMethod): String = when (method) {
    AuthMethod.LOCAL -> "You'll need to sign in again to continue."
    AuthMethod.BUNKER -> "You'll need your bunker URL to sign in again."
    AuthMethod.NIP07 -> "Your browser extension keeps your key. Reconnect it to sign in again."
}

fun removeAccountDialogBody(
    isActive: Boolean,
    accountLabel: String,
    fallbackLabel: String?,
    method: AuthMethod,
): String {
    val erased = "${erasedSubject(method, accountLabel)} will be erased on this device."
    return when {
        isActive && fallbackLabel != null -> "$erased You'll switch to \"$fallbackLabel\"."
        isActive -> "$erased ${signInAgainHint(method)}"
        else -> erased
    }
}

fun removeAccountConfirmLabel(isActive: Boolean): String = if (isActive) "Sign out" else "Remove"

fun removeAccountBusyLabel(isActive: Boolean): String = if (isActive) "Signing out…" else "Removing…"
