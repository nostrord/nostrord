package org.nostr.nostrord.ui.util

import androidx.compose.ui.input.pointer.PointerIcon

/**
 * Cursor for drag-reorder handles: the platform "move" cursor where the OS has one
 * (desktop AWT); the hand elsewhere (touch platforms have no hover cursor anyway).
 */
expect val grabPointerIcon: PointerIcon
