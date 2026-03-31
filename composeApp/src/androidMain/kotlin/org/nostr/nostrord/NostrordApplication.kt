package org.nostr.nostrord

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build.VERSION.SDK_INT
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.memory.MemoryCache
import okio.Path.Companion.toOkioPath
import org.nostr.nostrord.network.managers.AndroidNetworkMonitorInit
import org.nostr.nostrord.storage.SecureStorage

/**
 * Custom Application class that registers Coil's animated GIF decoders.
 *
 * WHY this is required:
 * Coil 3 does not include GIF animation support by default. The `coil-gif` artifact
 * provides two decoders:
 *   - AnimatedImageDecoder: uses Android's native ImageDecoder API (API 28+, most efficient)
 *   - GifDecoder:           pure-Coil software decoder for API < 28 devices
 *
 * Without this registration, Coil decodes GIFs using its generic BitmapDecoder,
 * which only reads the first frame — producing a static image.
 *
 * By implementing SingletonImageLoader.Factory here, we ensure the animated decoders
 * are wired into every AsyncImage call across the entire app, including chat images,
 * the full-screen viewer, avatars, and any future image usage.
 */
class NostrordApplication : Application(), SingletonImageLoader.Factory {

    override fun onCreate() {
        super.onCreate()
        SecureStorage.initialize(applicationContext)
        AndroidNetworkMonitorInit.initialize(applicationContext)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                // AnimatedImageDecoder is hardware-accelerated via the platform ImageDecoder
                // API. GifDecoder is a pure-software fallback for older API levels.
                // Both are provided by the coil-gif artifact.
                if (SDK_INT >= 28) {
                    add(AnimatedImageDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            // Adaptive memory cache: use 1/6 of available app memory (clamped 64–256 MB).
            // Low-RAM devices get a smaller cache; high-end devices benefit from more.
            .memoryCache {
                val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val memoryMb = am.memoryClass.toLong() // per-app heap limit in MB
                val cacheMb = (memoryMb / 6).coerceIn(64, 256)
                MemoryCache.Builder()
                    .maxSizeBytes(cacheMb * 1024 * 1024)
                    .build()
            }
            // Persistent disk cache so images survive app restarts without re-downloading.
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes(150L * 1024 * 1024) // 150 MB
                    .build()
            }
            .build()
    }
}
