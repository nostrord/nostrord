package org.nostr.nostrord.ui.components.chat

// Disabled on desktop: a SelectionContainer installs a native right-click "Copy"
// popup that shadows the app's own message context menu. Copying is handled by the
// context menu's "Copy Text" action instead.
actual val messagesTextSelectionEnabled: Boolean = false
