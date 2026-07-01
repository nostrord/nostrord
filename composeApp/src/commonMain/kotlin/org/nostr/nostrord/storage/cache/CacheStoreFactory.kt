package org.nostr.nostrord.storage.cache

/**
 * Platform factory for the production [CacheStore]: SQLDelight-backed on the native targets
 * (Android / JVM / iOS), IndexedDB-backed on web. Each actual builds its own driver/store;
 * the shared [SqlDelightCacheStore] holds the query logic for the native three.
 */
expect fun createCacheStore(): CacheStore
