@file:OptIn(ExperimentalWasmJsInterop::class)
package org.nostr.nostrord.notifications

import kotlin.js.ExperimentalWasmJsInterop

@JsFun("(title) => { document.title = title; }")
private external fun jsSetTitle(title: String)

actual fun setDocumentTitle(title: String) {
    jsSetTitle(title)
}
