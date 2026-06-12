package org.nostr.nostrord.nostr

import platform.Foundation.NSString
import platform.Foundation.precomposedStringWithCompatibilityMapping

@Suppress("CAST_NEVER_SUCCEEDS")
internal actual fun nfkcNormalize(input: String): String = (input as NSString).precomposedStringWithCompatibilityMapping
