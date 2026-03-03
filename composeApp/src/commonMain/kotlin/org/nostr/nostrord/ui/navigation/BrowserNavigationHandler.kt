package org.nostr.nostrord.ui.navigation

import androidx.compose.runtime.Composable
import org.nostr.nostrord.ui.Screen

/**
 * Syncs app navigation with the browser's history API on web targets.
 *
 * On JS/WasmJS: pushes state to `window.history` on app navigation
 * and listens for `popstate` to handle browser back/forward buttons.
 *
 * On all other platforms: no-op.
 */
@Composable
expect fun BrowserNavigationHandler(
    currentScreen: Screen,
    onBack: () -> Unit,
    onForward: () -> Unit
)
