package org.nostr.nostrord.auth

import kotlin.jvm.JvmInline

/**
 * Strongly typed account identifier backed by the account's hex pubkey.
 *
 * Prevents accidentally passing a relay URL, message ID, or raw string where
 * an account ID is expected at the call site.
 */
@JvmInline
value class AccountId(
    val value: String,
) {
    override fun toString(): String = value
}
