package org.nostr.nostrord

import androidx.compose.ui.window.ComposeUIViewController
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import okio.Path.Companion.toPath
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController {
    // Explicit Coil loader so iOS gets a sized memory cache and a PERSISTENT disk cache in
    // the app caches directory (Coil's default disk cache is ephemeral), matching Android/JVM
    // so avatars survive app restarts without re-downloading.
    SingletonImageLoader.setSafe { context ->
        ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder().maxSizePercent(context, 0.15).build()
            }
            .apply {
                val cachesDir =
                    NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true)
                        .firstOrNull() as? String
                if (cachesDir != null) {
                    diskCache {
                        DiskCache.Builder()
                            .directory("$cachesDir/nostrord_image_cache".toPath())
                            .maxSizeBytes(150L * 1024 * 1024) // 150 MB
                            .build()
                    }
                }
            }
            .build()
    }
    return ComposeUIViewController { App() }
}
