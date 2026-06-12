package org.nostr.nostrord.nostr

import java.text.Normalizer

internal actual fun nfkcNormalize(input: String): String = Normalizer.normalize(input, Normalizer.Form.NFKC)

// Desktop keys are protected by the OS keychain (or the passphrase fallback).
actual val ncryptsecStorageApplicable: Boolean = false
