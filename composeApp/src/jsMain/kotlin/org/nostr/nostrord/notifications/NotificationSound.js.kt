package org.nostr.nostrord.notifications

actual fun playNotificationSound() {
    try {
        val audio = js("new Audio('message-incoming.mp3')")
        audio.volume = 0.6
        audio.play()
    } catch (_: Throwable) {
        // Autoplay may be blocked before the user interacts with the page — ignore.
    }
}
