package org.nostr.nostrord.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.nostr.nostrord.network.FakeNostrRepository
import org.nostr.nostrord.nostr.Nip49
import org.nostr.nostrord.ui.screens.backup.BackupViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalStdlibApi::class)
class BackupViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    // Arbitrary but valid 32-byte hex keys (not a matching pair; only the encodings are under test).
    private val pubHex = "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d"
    private val privHex = "67dea2ed018072d675f5415ecfaed7d2597555e202d85b3d65ea4e58d2d92ffa"

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        testDispatcher.scheduler.advanceUntilIdle()
        Dispatchers.resetMain()
    }

    private fun vm(
        pub: String? = pubHex,
        priv: String? = privHex,
    ): BackupViewModel {
        val fake = FakeNostrRepository()
        fake.fakePublicKey = pub
        fake.fakePrivateKey = priv
        return BackupViewModel(fake, authMethod = null, cryptoDispatcher = testDispatcher)
    }

    @Test
    fun `public ids cycle npub nprofile hex`() {
        val ids = vm().publicIds
        assertEquals(listOf("npub", "nprofile", "hex"), ids.map { it.label })
        assertTrue(ids[0].value.startsWith("npub1"))
        assertTrue(ids[1].value.startsWith("nprofile1"))
        assertEquals(pubHex, ids[2].value)
    }

    @Test
    fun `canExportPrivate is false without a local key`() {
        assertFalse(vm(priv = null).canExportPrivate)
        assertTrue(vm().canExportPrivate)
    }

    @Test
    fun `private direct ids are nsec then hex`() {
        val ids = vm().privateDirectIds()
        assertEquals(listOf("nsec", "hex"), ids.map { it.label })
        assertTrue(ids[0].value.startsWith("nsec1"))
        assertEquals(privHex, ids[1].value)
    }

    @Test
    fun `encrypt rejects a short passphrase`() = runTest {
        val model = vm()
        model.setPassphrase("short")
        model.encrypt()
        testDispatcher.scheduler.advanceUntilIdle()
        assertNull(model.ncryptsec.value)
        assertTrue(model.error.value != null)
    }

    @Test
    fun `encrypt produces an ncryptsec that decrypts back to the key`() = runTest {
        val model = vm()
        model.setPassphrase("correct horse")
        model.encrypt()
        testDispatcher.scheduler.advanceUntilIdle()

        val ncryptsec = model.ncryptsec.value
        assertTrue(ncryptsec != null && ncryptsec.startsWith("ncryptsec1"))
        assertNull(model.error.value)
        val decrypted = Nip49.decrypt(ncryptsec, "correct horse")
        assertTrue(decrypted != null && decrypted.contentEquals(privHex.hexToByteArray()))
    }

    @Test
    fun `changing the passphrase invalidates a generated ncryptsec`() = runTest {
        val model = vm()
        model.setPassphrase("first passphrase")
        model.encrypt()
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(model.ncryptsec.value != null)

        model.setPassphrase("second passphrase")
        assertNull(model.ncryptsec.value)
    }

    @Test
    fun `hide clears reveal passphrase and ncryptsec`() = runTest {
        val model = vm()
        model.reveal()
        model.setPassphrase("a passphrase")
        model.encrypt()
        testDispatcher.scheduler.advanceUntilIdle()

        model.hide()
        assertFalse(model.revealed.value)
        assertEquals("", model.passphrase.value)
        assertNull(model.ncryptsec.value)
    }
}
