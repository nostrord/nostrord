package org.nostr.nostrord.ui.navigation

import androidx.compose.runtime.Composable
import org.nostr.nostrord.ui.Screen

/**
 * Syncs app navigation with the browser's history API on web targets.
 *
 * On JS/WasmJS: pushes state to `window.history` on app navigation
 * and listens for `popstate` to handle browser back/forward buttons.
 * Uses URL-based navigation: on popstate, the URL query params are parsed
 * and applied directly via [onUrlNavigation].
 *
 * On all other platforms: no-op.
 *
 * @param onUrlNavigation Called on popstate with the relay URL and optional group ID
 *   parsed from the browser URL.
 */
@Composable
expect fun BrowserNavigationHandler(
    currentScreen: Screen,
    selectedRelayUrl: String,
    onUrlNavigation: (relayUrl: String, groupId: String?) -> Unit
)
