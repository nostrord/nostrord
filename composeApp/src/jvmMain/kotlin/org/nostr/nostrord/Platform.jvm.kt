package org.nostr.nostrord

class JVMPlatform : Platform {
    override val name: String = "Java ${System.getProperty("java.version")}"
}

actual fun getPlatform(): Platform = JVMPlatform()

actual val isAndroid: Boolean = false
actual val isHandheldPlatform: Boolean = false
actual val platformDisplayName: String = "Nostrord Desktop"
actual val autoFocusTextInput: Boolean = true
