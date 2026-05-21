package org.nostr.nostrord.ui.navigation

actual val platformHasBrowserNavigation: Boolean = false

actual fun browserGoBack() {}

actual fun browserGoForward() {}

actual fun platformAppOrigin(): String? = null

actual fun clearBrowserUrlQuery() {}

// Native uses the Compose ancestor gesture in App.kt; nothing to register here.
actual fun registerLeftEdgeSwipeToOpen(onOpen: () -> Unit): () -> Unit = {}
