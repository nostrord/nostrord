package org.nostr.nostrord.storage

actual object PassphraseSettings {
    actual val isApplicable: Boolean = true

    actual fun usesKeychain(): Boolean = SecureStorage.usesKeychain()

    actual fun usesPassphrase(): Boolean = SecureStorage.usesPassphrase()

    actual fun changePassphrase(
        current: String,
        new: String,
    ): Boolean = SecureStorage.changePassphrase(current, new)
}
