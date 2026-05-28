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

/**
 * Replace the browser URL with the bare pathname, dropping any `?relay=…&group=…`
 * query. Called on logout so the login screen doesn't show a stale deep link
 * from the previous session (BrowserNavigationHandler is unmounted while
 * unauthenticated, so the URL would otherwise stay frozen).
 *
 * No-op on native platforms.
 */
expect fun clearBrowserUrlQuery()

/**
 * Register a web-only raw touch listener that opens the navigation drawer on a
 * left-edge left-to-right swipe (issue #77).
 *
 * On JS/WasmJS this attaches `touchstart`/`touchmove`/`touchend` listeners on
 * `window` in the CAPTURE phase with `{ passive: false }`, so the gesture is
 * detected before it reaches the Compose canvas — the Compose pointer-pass
 * approach loses this race over the scrolling chat list on mobile browsers.
 * Only horizontal swipes that START within ~24px of the left edge act; vertical
 * scrolls and non-edge touches are left untouched so chat scrolling, the
 * scroll-to-bottom FAB, message taps, and the right-edge member-sheet swipe all
 * keep working. When triggered it calls [onOpen] once and preventDefault's the
 * gesture so Compose doesn't double-handle it.
 *
 * No-op on native platforms (they use the Compose ancestor gesture in App.kt).
 *
 * @return a dispose lambda that removes the listeners.
 */
expect fun registerLeftEdgeSwipeToOpen(onOpen: () -> Unit): () -> Unit
