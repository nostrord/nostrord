package org.nostr.nostrord.storage.cache

/**
 * Web cache store. SQLDelight has no stable web driver and the app is Kotlin/JS (not WASM),
 * so the web backend is IndexedDB.
 */
actual fun createCacheStore(): CacheStore = IndexedDbCacheStore()
