package org.nostr.nostrord.ui.navigation

/**
 * Whether the platform provides its own browser-style back/forward navigation.
 * True on JS/WasmJS (browser arrows), false on native platforms.
 */
expect val platformHasBrowserNavigation: Boolean

/**
 * Trigger the browser's native back navigation.
 * On JS/WasmJS calls window.history.back(). No-op on other platforms.
 */
expect fun browserGoBack()

/**
 * Trigger the browser's native forward navigation.
 * On JS/WasmJS calls window.history.forward(). No-op on other platforms.
 */
expect fun browserGoForward()

/**
 * The app's base URL (origin) for building shareable links.
 * On JS/WasmJS returns `window.location.origin` (e.g. "https://web.nostrord.com").
 * On native platforms returns null (invite links are web-only).
 */
expect fun platformAppOrigin(): String?
