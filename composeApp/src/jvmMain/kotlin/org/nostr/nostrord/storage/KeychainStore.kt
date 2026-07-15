package org.nostr.nostrord.storage

import com.github.javakeyring.BackendNotSupportedException
import com.github.javakeyring.Keyring
import com.github.javakeyring.PasswordAccessException
import java.util.Base64

internal object KeychainStore {
    private const val SERVICE = "org.nostr.nostrord"
    private const val ACCOUNT_MASTER_KEY = "master_encryption_key"

    private val keyring: Keyring? by lazy {
        // Unit tests must not touch the real OS keyring: java-keyring's Secret Service
        // connection (dbus-java) has a reader thread that races its own executor shutdown,
        // and the uncaught RejectedExecutionException poisons a LATER kotlinx runTest as
        // UncaughtExceptionsBeforeTest — an intermittent, cross-class suite failure. The
        // Gradle Test tasks set this property; SecureStorage falls back to its
        // no-keychain path.
        if (System.getProperty("nostrord.disableKeychain") == "true") return@lazy null
        try {
            Keyring.create()
        } catch (_: BackendNotSupportedException) {
            null
        } catch (_: Throwable) {
            null
        }
    }

    fun isAvailable(): Boolean = keyring != null

    fun getMasterKey(): ByteArray? {
        val ring = keyring ?: return null
        return try {
            val b64 = ring.getPassword(SERVICE, ACCOUNT_MASTER_KEY) ?: return null
            Base64.getDecoder().decode(b64)
        } catch (_: PasswordAccessException) {
            null
        } catch (_: Throwable) {
            null
        }
    }

    fun setMasterKey(key: ByteArray): Boolean {
        val ring = keyring ?: return false
        return try {
            ring.setPassword(SERVICE, ACCOUNT_MASTER_KEY, Base64.getEncoder().encodeToString(key))
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun deleteMasterKey() {
        val ring = keyring ?: return
        try {
            ring.deletePassword(SERVICE, ACCOUNT_MASTER_KEY)
        } catch (_: Throwable) {
        }
    }
}
