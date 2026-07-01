package org.nostr.nostrord.ui.window

import androidx.compose.ui.Modifier

/** No-op: iOS has no side mouse buttons. */
actual fun Modifier.onBackForwardMouseButtons(
    onBack: () -> Unit,
    onForward: () -> Unit,
): Modifier = this
