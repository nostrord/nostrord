package org.nostr.nostrord

import android.os.Build

class AndroidPlatform : Platform {
    override val name: String = "Android ${Build.VERSION.SDK_INT}"
}

actual fun getPlatform(): Platform = AndroidPlatform()

actual val isAndroid: Boolean = true
actual val isLinuxDesktop: Boolean = false
actual val isHandheldPlatform: Boolean = true
actual val platformDisplayName: String = "Nostrord Android"
actual val autoFocusTextInput: Boolean = false
