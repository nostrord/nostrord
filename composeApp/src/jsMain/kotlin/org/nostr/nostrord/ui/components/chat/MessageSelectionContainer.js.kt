package org.nostr.nostrord.ui.components.chat

import kotlinx.browser.window

/** True when the browser's primary pointer is coarse (touch), i.e. a mobile device. */
internal fun isCoarsePointer(): Boolean = runCatching { window.matchMedia("(pointer: coarse)").matches }.getOrDefault(false)

actual val messagesTextSelectionEnabled: Boolean = !isCoarsePointer()
