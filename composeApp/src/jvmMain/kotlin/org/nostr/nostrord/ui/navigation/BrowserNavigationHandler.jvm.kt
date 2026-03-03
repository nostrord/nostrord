package org.nostr.nostrord.ui.navigation

import androidx.compose.runtime.Composable
import org.nostr.nostrord.ui.Screen

@Composable
actual fun BrowserNavigationHandler(
    currentScreen: Screen,
    onBack: () -> Unit,
    onForward: () -> Unit
) {
    // No browser history on desktop JVM
}
