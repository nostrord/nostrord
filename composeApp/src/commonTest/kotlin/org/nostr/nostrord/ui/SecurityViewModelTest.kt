package org.nostr.nostrord.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.nostr.nostrord.nostr.KeyPair
import org.nostr.nostrord.nostr.Nip49
import org.nostr.nostrord.ui.screens.settings.SecurityViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalStdlibApi::class)
class SecurityViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val privHex = "67dea2ed018072d675f5415ecfaed7d2597555e202d85b3d65ea4e58d2d92ffa"
    private val privBytes = privHex.hexToByteArray()

    // Derived, not hardcoded: the legacy-slot guard only clears a key that derives this pubkey.
    private val pubHex = KeyPair.fromPrivateKeyHex(privHex).publicKeyHex
    private val oldPassword = "old password"

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        testDispatcher.scheduler.advanceUntilIdle()
        Dispatchers.resetMain()
    }

    private class Store(
        var ncryptsec: String?,
        var plainKey: String? = null,
        var legacyKey: String? = null,
    )

    private fun vm(
        store: Store,
        protectApplicable: Boolean = true,
    ): SecurityViewModel = SecurityViewModel(
        pubkey = pubHex,
        readNcryptsec = { store.ncryptsec },
        writeNcryptsec = { _, nc -> store.ncryptsec = nc },
        readPlainKey = { store.plainKey },
        clearPlainKey = { store.plainKey = null },
        readLegacyPlainKey = { store.legacyKey },
        clearLegacyPlainKey = { store.legacyKey = null },
        protectApplicable = protectApplicable,
        cryptoDispatcher = testDispatcher,
    )

    @Test
    fun `isPasswordProtected reflects whether an ncryptsec is stored`() {
        assertTrue(vm(Store(Nip49.encrypt(privBytes, oldPassword))).isPasswordProtected.value)
        assertFalse(vm(Store(null)).isPasswordProtected.value)
    }

    @Test
    fun `change rotates the password and keeps the same key`() = runTest {
        val store = Store(Nip49.encrypt(privBytes, oldPassword))
        val model = vm(store)
        model.setCurrent(oldPassword)
        model.setNew("a brand new password")
        model.setConfirm("a brand new password")
        model.changePassword()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(model.success.value)
        assertNull(model.error.value)
        // Old password no longer works; the new one decrypts back to the same key.
        assertNull(Nip49.decrypt(store.ncryptsec!!, oldPassword))
        assertTrue(Nip49.decrypt(store.ncryptsec!!, "a brand new password").contentEquals(privBytes))
    }

    @Test
    fun `wrong current password is rejected and nothing is written`() = runTest {
        val original = Nip49.encrypt(privBytes, oldPassword)
        val store = Store(original)
        val model = vm(store)
        model.setCurrent("not the password")
        model.setNew("a brand new password")
        model.setConfirm("a brand new password")
        model.changePassword()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("Current password is incorrect.", model.error.value)
        assertFalse(model.success.value)
        assertEquals(original, store.ncryptsec)
    }

    @Test
    fun `mismatched confirmation is rejected`() {
        val model = vm(Store(Nip49.encrypt(privBytes, oldPassword)))
        model.setCurrent(oldPassword)
        model.setNew("a brand new password")
        model.setConfirm("different")
        model.changePassword()
        assertEquals("The new passwords do not match.", model.error.value)
    }

    @Test
    fun `short new password is rejected`() {
        val model = vm(Store(Nip49.encrypt(privBytes, oldPassword)))
        model.setCurrent(oldPassword)
        model.setNew("short")
        model.setConfirm("short")
        model.changePassword()
        assertTrue(model.error.value != null)
    }

    @Test
    fun `canProtect only for a plain key on an applicable platform`() {
        val plainHex = privBytes.toHexString()
        assertTrue(vm(Store(ncryptsec = null, plainKey = plainHex)).canProtect)
        assertFalse(vm(Store(ncryptsec = null, plainKey = plainHex), protectApplicable = false).canProtect)
        assertFalse(vm(Store(ncryptsec = null, plainKey = null)).canProtect)
        assertFalse(vm(Store(ncryptsec = Nip49.encrypt(privBytes, oldPassword), plainKey = plainHex)).canProtect)
    }

    @Test
    fun `protect encrypts the plain key, drops the plaintext slot and flips protected`() = runTest {
        val store = Store(ncryptsec = null, plainKey = privBytes.toHexString())
        val model = vm(store)
        model.setNew("a brand new password")
        model.setConfirm("a brand new password")
        model.protectWithPassword()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(model.success.value)
        assertNull(model.error.value)
        assertTrue(model.isPasswordProtected.value)
        assertFalse(model.canProtect)
        assertNull(store.plainKey)
        assertTrue(Nip49.decrypt(store.ncryptsec!!, "a brand new password").contentEquals(privBytes))
    }

    @Test
    fun `protect also clears the legacy global slot so restore cannot bypass the password`() = runTest {
        val plainHex = privBytes.toHexString()
        val store = Store(ncryptsec = null, plainKey = plainHex, legacyKey = plainHex)
        val model = vm(store)
        model.setNew("a brand new password")
        model.setConfirm("a brand new password")
        model.protectWithPassword()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(model.success.value)
        assertNull(store.plainKey)
        assertNull(store.legacyKey)
        assertTrue(Nip49.decrypt(store.ncryptsec!!, "a brand new password").contentEquals(privBytes))
    }

    @Test
    fun `protect covers a key present only in the legacy slot and spares another account's`() = runTest {
        // Another account's key in the legacy slot must survive untouched.
        val otherKey = "0000000000000000000000000000000000000000000000000000000000000001"
        val storeOther = Store(ncryptsec = null, plainKey = privBytes.toHexString(), legacyKey = otherKey)
        val modelOther = vm(storeOther)
        modelOther.setNew("a brand new password")
        modelOther.setConfirm("a brand new password")
        modelOther.protectWithPassword()
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(modelOther.success.value)
        assertEquals(otherKey, storeOther.legacyKey)

        // Legacy-only residue (per-account slot empty) is still protectable.
        val storeLegacyOnly = Store(ncryptsec = null, plainKey = null, legacyKey = privBytes.toHexString())
        val model = vm(storeLegacyOnly)
        assertTrue(model.canProtect)
        model.setNew("a brand new password")
        model.setConfirm("a brand new password")
        model.protectWithPassword()
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(model.success.value)
        assertNull(storeLegacyOnly.legacyKey)
        assertTrue(Nip49.decrypt(storeLegacyOnly.ncryptsec!!, "a brand new password").contentEquals(privBytes))
    }

    @Test
    fun `protect rejects a mismatched confirmation and keeps the plain key`() {
        val store = Store(ncryptsec = null, plainKey = privBytes.toHexString())
        val model = vm(store)
        model.setNew("a brand new password")
        model.setConfirm("different")
        model.protectWithPassword()
        assertEquals("The passwords do not match.", model.error.value)
        assertNull(store.ncryptsec)
        assertTrue(store.plainKey != null)
    }

    @Test
    fun `protect is refused when already protected`() {
        val original = Nip49.encrypt(privBytes, oldPassword)
        val store = Store(ncryptsec = original, plainKey = null)
        val model = vm(store)
        model.setNew("a brand new password")
        model.setConfirm("a brand new password")
        model.protectWithPassword()
        assertTrue(model.error.value != null)
        assertEquals(original, store.ncryptsec)
    }
}
