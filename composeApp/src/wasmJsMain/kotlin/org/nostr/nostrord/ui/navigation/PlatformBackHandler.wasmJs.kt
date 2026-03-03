package org.nostr.nostrord.ui.navigation

import androidx.compose.runtime.Composable

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // No system back button in WasmJS target
}
