package org.nostr.nostrord.ui.navigation

import androidx.compose.runtime.Composable
import org.nostr.nostrord.ui.Screen

@Composable
actual fun BrowserNavigationHandler(
    currentScreen: Screen,
    selectedRelayUrl: String,
    onUrlNavigation: (relayUrl: String, groupId: String?, inviteCode: String?) -> Unit
) {
    // No browser history on desktop JVM
}
