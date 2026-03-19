package org.nostr.nostrord.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.nostr.nostrord.network.FakeNostrRepository
import org.nostr.nostrord.ui.screens.login.LoginViewModel
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
class LoginViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // -------------------------------------------------------------------------
    // loginWithPrivateKey
    // -------------------------------------------------------------------------

    @Test
    fun `loginWithPrivateKey success calls onResult with success`() = runTest {
        val fake = FakeNostrRepository()
        val vm = LoginViewModel(fake)

        var result: kotlin.Result<Unit>? = null
        vm.loginWithPrivateKey("privkey", "pubkey") { result = it }
        testDispatcher.scheduler.advanceUntilIdle()

        assertNotNull(result)
        assertTrue(result!!.isSuccess)
        assertTrue(fake.calls.contains("loginSuspend"))
    }

    @Test
    fun `loginWithPrivateKey failure calls onResult with failure`() = runTest {
        val fake = FakeNostrRepository()
        fake.loginSuspendAction = { _, _ -> Result.Error(AppError.Unknown("bad key")) }
        val vm = LoginViewModel(fake)

        var result: kotlin.Result<Unit>? = null
        vm.loginWithPrivateKey("bad", "bad") { result = it }
        testDispatcher.scheduler.advanceUntilIdle()

        assertNotNull(result)
        assertTrue(result!!.isFailure)
        assertEquals("bad key", result!!.exceptionOrNull()?.message)
    }

    // -------------------------------------------------------------------------
    // loginWithNip07 (pubkey provided externally)
    // -------------------------------------------------------------------------

    @Test
    fun `loginWithNip07 success delegates to repo`() = runTest {
        val fake = FakeNostrRepository()
        val vm = LoginViewModel(fake)

        var result: kotlin.Result<Unit>? = null
        vm.loginWithNip07("deadbeef") { result = it }
        testDispatcher.scheduler.advanceUntilIdle()

        assertNotNull(result)
        assertTrue(result!!.isSuccess)
        assertTrue(fake.calls.contains("loginWithNip07"))
    }

    @Test
    fun `loginWithNip07 failure propagates exception`() = runTest {
        val fake = FakeNostrRepository()
        fake.loginWithNip07Action = { Result.Error(AppError.Unknown("nip07 failed")) }
        val vm = LoginViewModel(fake)

        var result: kotlin.Result<Unit>? = null
        vm.loginWithNip07("pubkey") { result = it }
        testDispatcher.scheduler.advanceUntilIdle()

        assertNotNull(result)
        assertTrue(result!!.isFailure)
    }

    // -------------------------------------------------------------------------
    // authUrl passthrough
    // -------------------------------------------------------------------------

    @Test
    fun `authUrl reflects repo state`() = runTest {
        val fake = FakeNostrRepository()
        val vm = LoginViewModel(fake)

        assertNull(vm.authUrl.value)
        fake._authUrl.value = "https://auth.example.com"
        assertEquals("https://auth.example.com", vm.authUrl.value)
    }

    @Test
    fun `clearAuthUrl delegates to repo`() = runTest {
        val fake = FakeNostrRepository()
        fake._authUrl.value = "https://auth.example.com"
        val vm = LoginViewModel(fake)

        vm.clearAuthUrl()
        assertNull(fake._authUrl.value)
    }
}
