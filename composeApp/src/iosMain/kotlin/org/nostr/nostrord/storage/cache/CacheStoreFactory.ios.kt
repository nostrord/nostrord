package org.nostr.nostrord.storage.cache

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import org.nostr.nostrord.storage.cache.db.CacheDb

/**
 * iOS cache store. NativeSqliteDriver creates and migrates the schema from the bundled
 * Kotlin/Native SQLite. NOTE: written but not compiled in the Linux CI for this branch —
 * verify on macOS (Xcode) before relying on it.
 */
actual fun createCacheStore(): CacheStore {
    val driver = NativeSqliteDriver(CacheDb.Schema, "nostrord-cache.db")
    return SqlDelightCacheStore(driver)
}
