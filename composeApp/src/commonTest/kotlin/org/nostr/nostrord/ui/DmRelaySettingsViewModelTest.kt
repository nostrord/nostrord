package org.nostr.nostrord.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.nostr.nostrord.network.FakeNostrRepository
import org.nostr.nostrord.ui.screens.settings.DmRelaySettingsViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class DmRelaySettingsViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        testDispatcher.scheduler.advanceUntilIdle()
        Dispatchers.resetMain()
    }

    @Test
    fun `publish normalizes urls drops dupes and forwards to the repo`() = runTest {
        val repo = FakeNostrRepository()
        val vm = DmRelaySettingsViewModel(repo)

        vm.publish(listOf("relay.example.com/", "wss://relay.example.com", "wss://nos.lol"))
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("wss://relay.example.com", "wss://nos.lol"), repo.myDmRelays.value)
        assertNull(vm.error.value)
    }

    @Test
    fun `publishing an empty list surfaces an error and does not call the repo`() = runTest {
        val repo = FakeNostrRepository()
        val vm = DmRelaySettingsViewModel(repo)

        vm.publish(listOf("   "))
        testDispatcher.scheduler.advanceUntilIdle()

        assertNotNull(vm.error.value)
        assertEquals(emptyList(), repo.myDmRelays.value)
    }
}
