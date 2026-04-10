@file:OptIn(ExperimentalWasmJsInterop::class)
package org.nostr.nostrord.ui.navigation

import kotlin.js.ExperimentalWasmJsInterop

actual val platformHasBrowserNavigation: Boolean = true

@JsFun("() => window.history.back()")
private external fun jsHistoryBack()

@JsFun("() => window.history.forward()")
private external fun jsHistoryForward()

actual fun browserGoBack() { jsHistoryBack() }
actual fun browserGoForward() { jsHistoryForward() }

@JsFun("() => window.location.origin")
private external fun jsGetOrigin(): String

actual fun platformAppOrigin(): String? = jsGetOrigin()
