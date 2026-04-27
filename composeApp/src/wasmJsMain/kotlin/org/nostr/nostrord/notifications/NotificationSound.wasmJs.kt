@file:OptIn(ExperimentalWasmJsInterop::class)
package org.nostr.nostrord.notifications

import kotlin.js.ExperimentalWasmJsInterop

@JsFun(
    """() => {
        try {
            var a = new Audio('message-incoming.mp3');
            a.volume = 0.6;
            a.play();
        } catch (e) { /* autoplay blocked before user gesture */ }
    }"""
)
private external fun jsPlaySound()

actual fun playNotificationSound() {
    jsPlaySound()
}
