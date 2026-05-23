package org.nostr.nostrord.ui.components.chat

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable

/**
 * Whether messages should support native text selection.
 *
 * Enabled on desktop (mouse-driven) where drag-to-select is the norm. Disabled on
 * mobile (Android, iOS, and coarse-pointer web) so a long-press shows only the app
 * context menu instead of stacking the native "Copy / Select all" toolbar on top.
 */
expect val messagesTextSelectionEnabled: Boolean

/**
 * Wraps [content] in a [SelectionContainer] only when [messagesTextSelectionEnabled].
 * On mobile it is a pass-through, which disables native text selection for messages.
 */
@Composable
fun MessageSelectionContainer(content: @Composable () -> Unit) {
    if (messagesTextSelectionEnabled) {
        SelectionContainer { content() }
    } else {
        content()
    }
}
