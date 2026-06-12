package org.nostr.nostrord.nostr

internal actual fun nfkcNormalize(input: String): String = input.asDynamic().normalize("NFKC") as String

// Web SecureStorage is localStorage: encrypted-at-rest keys are a real gain here.
actual val ncryptsecStorageApplicable: Boolean = true
