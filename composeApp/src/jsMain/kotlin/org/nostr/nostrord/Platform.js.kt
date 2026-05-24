package org.nostr.nostrord

class JsPlatform : Platform {
    override val name: String = "Web with Kotlin/JS"
}

actual fun getPlatform(): Platform = JsPlatform()

actual val isAndroid: Boolean = false
actual val isHandheldPlatform: Boolean = false
actual val platformDisplayName: String = "Nostrord Web"

// Desktop web (mouse) auto-focuses; mobile/touch web does not, to avoid popping
// the on-screen keyboard on group entry. Mirrors messagesTextSelectionEnabled.
actual val autoFocusTextInput: Boolean =
    !org.nostr.nostrord.ui.components.chat.isCoarsePointer()
