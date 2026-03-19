package org.nostr.nostrord.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.nostr.nostrord.network.FakeNostrRepository
import org.nostr.nostrord.ui.screens.relay.RelayViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class RelayViewModelTest {

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
    fun `currentRelayUrl reflects repo state`() = runTest {
        val fake = FakeNostrRepository()
        fake._currentRelayUrl.value = "wss://initial.relay"
        val vm = RelayViewModel(fake)

        assertEquals("wss://initial.relay", vm.currentRelayUrl.value)
    }

    @Test
    fun `switchRelay updates repo currentRelayUrl`() = runTest {
        val fake = FakeNostrRepository()
        val vm = RelayViewModel(fake)

        vm.switchRelay("wss://new.relay")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("wss://new.relay", fake._currentRelayUrl.value)
    }

    @Test
    fun `currentRelayUrl updates when repo value changes`() = runTest {
        val fake = FakeNostrRepository()
        val vm = RelayViewModel(fake)

        fake._currentRelayUrl.value = "wss://updated.relay"

        assertEquals("wss://updated.relay", vm.currentRelayUrl.value)
    }
}
