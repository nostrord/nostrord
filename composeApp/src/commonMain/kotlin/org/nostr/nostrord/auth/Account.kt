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
