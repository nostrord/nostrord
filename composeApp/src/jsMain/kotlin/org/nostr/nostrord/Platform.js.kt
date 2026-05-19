package org.nostr.nostrord

class JsPlatform : Platform {
    override val name: String = "Web with Kotlin/JS"
}

actual fun getPlatform(): Platform = JsPlatform()

actual val isAndroid: Boolean = false
actual val isHandheldPlatform: Boolean = false
actual val platformDisplayName: String = "Nostrord Web"
