package org.nostr.nostrord.storage

actual object PassphraseSettings {
    actual val isApplicable: Boolean = false

    actual fun usesKeychain(): Boolean = false

    actual fun usesPassphrase(): Boolean = false

    actual fun changePassphrase(
        current: String,
        new: String,
    ): Boolean = false
}
