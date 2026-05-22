@file:OptIn(ExperimentalWasmJsInterop::class)

package org.nostr.nostrord.ui.components.chat

import kotlin.js.ExperimentalWasmJsInterop

@JsFun("() => (typeof window !== 'undefined' && window.matchMedia && window.matchMedia('(pointer: coarse)').matches) || false")
private external fun jsIsCoarsePointer(): Boolean

/** True when the browser's primary pointer is coarse (touch), i.e. a mobile device. */
internal fun isCoarsePointer(): Boolean = jsIsCoarsePointer()

actual val messagesTextSelectionEnabled: Boolean = !isCoarsePointer()
