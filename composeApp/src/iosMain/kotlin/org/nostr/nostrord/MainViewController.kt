package org.nostr.nostrord

import androidx.compose.ui.window.ComposeUIViewController
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.svg.SvgDecoder
import okio.Path.Companion.toPath
import org.nostr.nostrord.ui.util.ImageLoadEventListener
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
            // Logs load failures (NOSTRORD_IMG) so avatar/photo regressions are visible in logs.
            .eventListener(ImageLoadEventListener)
            .components {
                // Decodes data:image/svg+xml avatars (the data: fetcher is built in since 3.1).
                add(SvgDecoder.Factory())
            }
            .memoryCache {
                // 20% of available memory (matches Android): keeps frequently-shown avatars
                // resident so they aren't refetched over a long session, where each refetch is
                // a chance to hit a transient failure that falls back to a placeholder.
                MemoryCache.Builder().maxSizePercent(context, 0.20).build()
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
