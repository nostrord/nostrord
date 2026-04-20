package org.nostr.nostrord.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.nostr.nostrord.network.FakeNostrRepository
import org.nostr.nostrord.ui.screens.profile.EditProfileViewModel
import org.nostr.nostrord.utils.AppError
import org.nostr.nostrord.utils.Result
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class EditProfileViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `saveProfile success calls onResult with success`() = runTest {
        val fake = FakeNostrRepository()
        val vm = EditProfileViewModel(fake)

        var result: kotlin.Result<Unit>? = null
        vm.saveProfile("Alice", "alice", "bio", null, null, null, null, null) { result = it }
        testDispatcher.scheduler.advanceUntilIdle()

        assertNotNull(result)
        assertTrue(result!!.isSuccess)
    }

    @Test
    fun `saveProfile failure calls onResult with failure`() = runTest {
        val fake = FakeNostrRepository()
        fake.updateProfileMetadataAction = { _, _, _, _, _, _, _, _ ->
            Result.Error(AppError.Unknown("server error"))
        }
        val vm = EditProfileViewModel(fake)

        var result: kotlin.Result<Unit>? = null
        vm.saveProfile("Alice", null, null, null, null, null, null, null) { result = it }
        testDispatcher.scheduler.advanceUntilIdle()

        assertNotNull(result)
        assertTrue(result!!.isFailure)
        assertEquals("server error", result!!.exceptionOrNull()?.message)
    }

    @Test
    fun `getPublicKey returns repo public key`() = runTest {
        val fake = FakeNostrRepository()
        fake.fakePublicKey = "cafebabe"
        val vm = EditProfileViewModel(fake)

        assertEquals("cafebabe", vm.getPublicKey())
    }

    @Test
    fun `getPublicKey returns null when not logged in`() = runTest {
        val vm = EditProfileViewModel(FakeNostrRepository())
        assertNull(vm.getPublicKey())
    }
}
