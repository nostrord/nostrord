package org.nostr.nostrord.storage.cache

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import org.nostr.nostrord.storage.cache.db.CacheDb

/**
 * Holds the application context for the cache database, set once in NostrordApplication
 * (same pattern as SecureStorage.initialize). AndroidSqliteDriver needs a Context to open
 * the device SQLite file.
 */
object CacheStoreAndroid {
    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    internal fun requireContext(): Context = appContext ?: error("CacheStoreAndroid not initialized. Call CacheStoreAndroid.initialize(context) first.")
}

/** AndroidSqliteDriver creates and migrates the schema from the device SQLite engine. */
actual fun createCacheStore(): CacheStore {
    val driver = AndroidSqliteDriver(CacheDb.Schema, CacheStoreAndroid.requireContext(), "nostrord-cache.db")
    return SqlDelightCacheStore(driver)
}
