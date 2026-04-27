@file:OptIn(ExperimentalWasmJsInterop::class)
package org.nostr.nostrord.notifications

import kotlin.js.ExperimentalWasmJsInterop

@JsFun(
    """(onFocus, onBlur) => {
        window.addEventListener('focus', onFocus);
        window.addEventListener('blur', onBlur);
        document.addEventListener('visibilitychange', function() {
            if (document.hidden) { onBlur(); } else { onFocus(); }
        });
        return document.hidden ? false : true;
    }"""
)
private external fun jsInstallFocusListeners(
    onFocus: () -> Unit,
    onBlur: () -> Unit,
): Boolean

actual fun installPlatformFocusListeners(tracker: FocusTracker) {
    val initialFocused = jsInstallFocusListeners(
        onFocus = { tracker.setFocused(true) },
        onBlur = { tracker.setFocused(false) },
    )
    tracker.setFocused(initialFocused)
}
