package org.nostr.nostrord

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform

expect val isAndroid: Boolean

/**
 * True on platforms where the window size IS the device size (phone, tablet). On these,
 * responsive breakpoints should look at the smallest dimension so rotation doesn't trigger
 * a layout class change. False on desktop / browser where the user resizes the window
 * intentionally and width-based breakpoints match their intent.
 */
expect val isHandheldPlatform: Boolean

/**
 * Human-readable build identifier shown to remote signers during the
 * nostrconnect:// handshake. Persists in the signer's authorized-apps
 * list, so a user with Nostrord on several devices can tell them apart
 * (and revoke individually).
 */
expect val platformDisplayName: String
