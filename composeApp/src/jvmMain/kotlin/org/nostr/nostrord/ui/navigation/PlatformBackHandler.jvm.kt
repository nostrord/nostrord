package org.nostr.nostrord.ui.navigation

import androidx.compose.runtime.Composable

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // No system back button on desktop — handled via keyboard shortcuts
}
