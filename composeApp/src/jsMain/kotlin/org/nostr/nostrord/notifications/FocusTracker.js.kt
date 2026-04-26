package org.nostr.nostrord.notifications

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.events.Event

actual fun installPlatformFocusListeners(tracker: FocusTracker) {
    val onFocus: (Event) -> Unit = { tracker.setFocused(true) }
    val onBlur: (Event) -> Unit = { tracker.setFocused(false) }
    val onVisibility: (Event) -> Unit = {
        val hidden = js("document.hidden").unsafeCast<Boolean>()
        tracker.setFocused(!hidden)
    }
    window.addEventListener("focus", onFocus)
    window.addEventListener("blur", onBlur)
    document.addEventListener("visibilitychange", onVisibility)

    // Seed initial state in case the tab was launched already hidden.
    val hidden = js("document.hidden").unsafeCast<Boolean>()
    tracker.setFocused(!hidden)
}
