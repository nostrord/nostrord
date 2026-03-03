package org.nostr.nostrord.ui.navigation

import androidx.compose.runtime.Composable

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // iOS uses swipe-back gesture handled by the system
}
