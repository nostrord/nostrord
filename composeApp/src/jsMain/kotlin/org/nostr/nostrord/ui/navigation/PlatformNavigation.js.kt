package org.nostr.nostrord.ui.navigation

import kotlinx.browser.window

actual val platformHasBrowserNavigation: Boolean = true
actual fun browserGoBack() { window.history.back() }
actual fun browserGoForward() { window.history.forward() }
actual fun platformAppOrigin(): String? = window.location.origin
