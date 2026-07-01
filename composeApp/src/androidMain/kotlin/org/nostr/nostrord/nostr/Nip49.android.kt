package org.nostr.nostrord.nostr

import java.text.Normalizer

internal actual fun nfkcNormalize(input: String): String = Normalizer.normalize(input, Normalizer.Form.NFKC)

// Android keys are protected by the hardware-backed Keystore (AES-256-GCM).
actual val ncryptsecStorageApplicable: Boolean = false
