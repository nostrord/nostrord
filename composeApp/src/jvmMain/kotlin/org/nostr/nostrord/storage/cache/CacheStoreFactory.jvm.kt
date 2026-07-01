package org.nostr.nostrord.storage.cache

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.nostr.nostrord.storage.cache.db.CacheDb
import java.io.File
import java.util.Properties

/**
 * Desktop cache store: a SQLite file under the user's home dir. Passing the schema to the
 * driver makes SQLDelight create it on first run and migrate it on version bumps.
 */
actual fun createCacheStore(): CacheStore {
    val dir = File(System.getProperty("user.home"), ".nostrord").apply { mkdirs() }
    // busy_timeout makes a connection wait for a held write lock instead of failing immediately
    // with SQLITE_BUSY when group and DM writes hit the file concurrently.
    val props = Properties().apply { setProperty("busy_timeout", "5000") }
    val driver = JdbcSqliteDriver("jdbc:sqlite:${File(dir, "cache.db").absolutePath}", props, CacheDb.Schema)
    return SqlDelightCacheStore(driver)
}
