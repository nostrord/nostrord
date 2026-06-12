package org.nostr.nostrord.nostr

internal actual fun nfkcNormalize(input: String): String = input.asDynamic().normalize("NFKC") as String
