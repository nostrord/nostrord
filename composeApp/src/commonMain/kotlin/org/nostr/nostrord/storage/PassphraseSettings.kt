package org.nostr.nostrord.storage

expect object PassphraseSettings {
    val isApplicable: Boolean
    fun usesKeychain(): Boolean
    fun usesPassphrase(): Boolean
    fun changePassphrase(current: String, new: String): Boolean
}
