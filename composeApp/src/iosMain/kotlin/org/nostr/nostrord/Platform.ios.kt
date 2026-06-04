package org.nostr.nostrord

import platform.UIKit.UIDevice

class IOSPlatform : Platform {
    override val name: String = UIDevice.currentDevice.systemName() + " " + UIDevice.currentDevice.systemVersion
}

actual fun getPlatform(): Platform = IOSPlatform()

actual val isAndroid: Boolean = false
actual val isLinuxDesktop: Boolean = false
actual val isHandheldPlatform: Boolean = true
actual val platformDisplayName: String = "Nostrord iOS"
actual val autoFocusTextInput: Boolean = false
