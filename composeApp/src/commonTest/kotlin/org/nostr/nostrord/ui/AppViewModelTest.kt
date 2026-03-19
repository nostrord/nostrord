package org.nostr.nostrord.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.nostr.nostrord.AppViewModel
import org.nostr.nostrord.network.FakeNostrRepository
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelTest {

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
    fun `isInitialized starts false`() = runTest {
        val fake = FakeNostrRepository()
        fake.initializeAction = {}  // don't flip to true
        val vm = AppViewModel(fake)
        assertFalse(vm.isInitialized.value)
    }

    @Test
    fun `isInitialized becomes true after initialize completes`() = runTest {
        val fake = FakeNostrRepository()
        val vm = AppViewModel(fake)
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.isInitialized.value)
    }

    @Test
    fun `initialize is called on construction`() = runTest {
        val fake = FakeNostrRepository()
        AppViewModel(fake)
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(fake.calls.contains("initialize"))
    }

    @Test
    fun `isLoggedIn reflects repo state`() = runTest {
        val fake = FakeNostrRepository()
        val vm = AppViewModel(fake)
        assertFalse(vm.isLoggedIn.value)
        fake._isLoggedIn.value = true
        assertTrue(vm.isLoggedIn.value)
    }
}
