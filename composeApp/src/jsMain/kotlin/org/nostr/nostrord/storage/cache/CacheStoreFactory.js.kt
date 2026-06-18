package org.nostr.nostrord.storage.cache

/**
 * Web cache store. SQLDelight has no stable web driver and the app is Kotlin/JS (not WASM),
 * so the web backend is IndexedDB. Until that lands it falls back to the in-memory store,
 * which keeps the seam working (just non-persistent) on web.
 */
actual fun createCacheStore(): CacheStore = InMemoryCacheStore()
