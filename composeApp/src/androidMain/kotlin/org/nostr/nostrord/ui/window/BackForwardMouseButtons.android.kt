package org.nostr.nostrord.ui.window

import androidx.compose.ui.Modifier

/** No-op: Android has no side mouse buttons in this UI. */
actual fun Modifier.onBackForwardMouseButtons(
    onBack: () -> Unit,
    onForward: () -> Unit,
): Modifier = this
