package org.nostr.nostrord

class JsPlatform : Platform {
    override val name: String = "Web with Kotlin/JS"
}

actual fun getPlatform(): Platform = JsPlatform()

actual val isAndroid: Boolean = false
actual val isHandheldPlatform: Boolean = false
actual val platformDisplayName: String = "Nostrord Web"

// Desktop web (mouse) auto-focuses; mobile/touch web does not, to avoid popping
// the on-screen keyboard on group entry. Coarse pointer = touch device.
actual val autoFocusTextInput: Boolean =
    !runCatching { kotlinx.browser.window.matchMedia("(pointer: coarse)").matches }.getOrDefault(false)
