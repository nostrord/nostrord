package org.nostr.nostrord.ui.components.media

import android.annotation.SuppressLint
import android.content.Context
import android.os.StatFs
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * Disk-backed video byte cache. ExoPlayer streams from disk after the first watch, so
 * re-opening a video (re-mount, fullscreen toggle, app restart) is instant instead of
 * re-buffering from the network. Idea borrowed from Amethyst's `VideoCache`.
 *
 * Sizing: targets 10% of currently-available disk, clamped to [MIN_BYTES, MAX_BYTES].
 * LRU eviction is a good approximation of FIFO for Nostr feeds (rarely re-watch old).
 *
 * Must be initialized once from [org.nostr.nostrord.NostrordApplication.onCreate] before
 * any video player is constructed.
 */
@SuppressLint("UnsafeOptInUsageError")
object VideoCache {
    private const val CACHE_DIR_NAME = "video_cache"
    private const val CACHE_SIZE_PERCENT = 0.10
    private const val MIN_BYTES = 256L * 1024 * 1024 // 256 MB
    private const val MAX_BYTES = 1L * 1024 * 1024 * 1024 // 1 GB

    private var simpleCache: SimpleCache? = null
    private var databaseProvider: StandaloneDatabaseProvider? = null
    private var factory: CacheDataSource.Factory? = null

    /**
     * Initialize the disk cache. [upstreamFactory] is the network source used when a byte
     * range isn't cached yet — defaults to [DefaultHttpDataSource], but if/when the app
     * gains a shared OkHttp client (for Tor, proxy, TLS pinning, etc.) wrap it with
     * Media3's `OkHttpDataSource.Factory` and pass it here.
     */
    @Synchronized
    fun initialize(
        context: Context,
        // allowCrossProtocolRedirects: media CDNs (nostr.build, Blossom mirrors)
        // often redirect, sometimes across http/https; without this the load
        // fails on the redirect instead of following it.
        upstreamFactory: DataSource.Factory =
            DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true),
    ) {
        if (simpleCache != null) return
        val cacheDir = File(context.cacheDir, CACHE_DIR_NAME).apply { mkdirs() }
        val provider = StandaloneDatabaseProvider(context)
        val cache =
            SimpleCache(
                cacheDir,
                LeastRecentlyUsedCacheEvictor(calculateCacheSize(cacheDir)),
                provider,
            )
        databaseProvider = provider
        simpleCache = cache
        factory =
            CacheDataSource
                .Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(upstreamFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    fun dataSourceFactory(): CacheDataSource.Factory = factory ?: error("VideoCache not initialized — call VideoCache.initialize() in Application.onCreate")

    private fun calculateCacheSize(cacheDir: File): Long {
        val available = runCatching { StatFs(cacheDir.absolutePath).availableBytes }.getOrDefault(0L)
        val target = (available * CACHE_SIZE_PERCENT).toLong()
        return target.coerceIn(MIN_BYTES, MAX_BYTES)
    }
}
