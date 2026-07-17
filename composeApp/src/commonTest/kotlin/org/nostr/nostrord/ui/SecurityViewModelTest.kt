package org.nostr.nostrord.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
    private val pubHex = "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d"
    private val privBytes = "67dea2ed018072d675f5415ecfaed7d2597555e202d85b3d65ea4e58d2d92ffa".hexToByteArray()
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

    private class Store(var ncryptsec: String?, var plainKey: String? = null)

    private fun vm(
        store: Store,
        protectApplicable: Boolean = true,
    ): SecurityViewModel = SecurityViewModel(
        pubkey = pubHex,
        readNcryptsec = { store.ncryptsec },
        writeNcryptsec = { _, nc -> store.ncryptsec = nc },
        readPlainKey = { store.plainKey },
        clearPlainKey = { store.plainKey = null },
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
