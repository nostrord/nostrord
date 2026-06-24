package org.nostr.nostrord.ui.window

import androidx.compose.ui.Modifier

/**
 * Desktop side-mouse-button navigation: the X1 (back) and X2 (forward) buttons drive
 * [onBack] / [onForward]. A no-op on Android and iOS, which have no such buttons.
 */
expect fun Modifier.onBackForwardMouseButtons(
    onBack: () -> Unit,
    onForward: () -> Unit,
): Modifier
