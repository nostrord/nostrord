package org.nostr.nostrord.storage

// iOS protects keys with the Keychain (OS-level encryption), so there is no
// app-level passphrase flow to expose.
actual object PassphraseSettings {
    actual val isApplicable: Boolean = false

    actual fun usesKeychain(): Boolean = true

    actual fun usesPassphrase(): Boolean = false

    actual fun changePassphrase(
        current: String,
        new: String,
    ): Boolean = false
}
