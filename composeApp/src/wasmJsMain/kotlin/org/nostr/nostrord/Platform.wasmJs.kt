package org.nostr.nostrord

class WasmPlatform : Platform {
    override val name: String = "Web with Kotlin/Wasm"
}

actual fun getPlatform(): Platform = WasmPlatform()

actual val isAndroid: Boolean = false
actual val isHandheldPlatform: Boolean = false
actual val platformDisplayName: String = "Nostrord Web"
