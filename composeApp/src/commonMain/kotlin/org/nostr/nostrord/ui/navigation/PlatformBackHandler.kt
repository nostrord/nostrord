package org.nostr.nostrord.ui.navigation

import androidx.compose.runtime.Composable

/**
 * Platform-specific back button handler.
 * On Android, delegates to the system back button via BackHandler.
 * On other platforms, this is a no-op.
 */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)
