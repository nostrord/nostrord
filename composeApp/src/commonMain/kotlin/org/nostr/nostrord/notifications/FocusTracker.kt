package org.nostr.nostrord.notifications

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks whether the app window/tab is currently focused. Drives the
 * "active group + unfocused → still notify" branch in UnreadManager.
 * Web is wired via [installPlatformFocusListeners]; Android/iOS via Lifecycle
 * events in App.kt; JVM stays at the default (always focused).
 */
class FocusTracker {
    private val _isAppFocused = MutableStateFlow(true)
    val isAppFocused: StateFlow<Boolean> = _isAppFocused.asStateFlow()

    fun setFocused(focused: Boolean) {
        _isAppFocused.value = focused
    }
}

/** Web hooks `document.visibilitychange` + `window.focus/blur`; other platforms no-op. */
expect fun installPlatformFocusListeners(tracker: FocusTracker)
