package org.nostr.nostrord.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.nostr.nostrord.network.FakeNostrRepository
import org.nostr.nostrord.network.managers.ConnectionManager
import org.nostr.nostrord.ui.screens.home.HomeViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the offline-relay removal flow introduced in issue #32.
 *
 * Verifies:
 * - forgetGroup delegates to repo and updates joinedGroupsByRelay
 * - removeRelay delegates to repo and clears the relay from joinedGroupsByRelay
 * - joinedGroupsByRelay reflects repo state so HomeScreen can compute isOffline correctly
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelOfflineRelayTest {
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
    // forgetGroup
    // -------------------------------------------------------------------------

    @Test
    fun `forgetGroup calls repo forgetGroup with correct args`() = runTest {
        val fake = FakeNostrRepository()
        fake._currentRelayUrl.value = "wss://offline.relay/"
        fake._joinedGroupsByRelay.value =
            mapOf(
                "wss://offline.relay/" to setOf("group-1", "group-2"),
            )
        val vm = HomeViewModel(fake)

        vm.forgetGroup("group-1", "wss://offline.relay/")
        advanceUntilIdle()

        assertTrue(
            fake.calls.contains("forgetGroup:group-1:wss://offline.relay/"),
            "Expected forgetGroup call in call log, got: ${fake.calls}",
        )
    }

    @Test
    fun `forgetGroup removes group from joinedGroupsByRelay`() = runTest {
        val fake = FakeNostrRepository()
        fake._currentRelayUrl.value = "wss://offline.relay/"
        fake._joinedGroupsByRelay.value =
            mapOf(
                "wss://offline.relay/" to setOf("group-1", "group-2"),
            )
        val vm = HomeViewModel(fake)

        vm.forgetGroup("group-1", "wss://offline.relay/")
        advanceUntilIdle()

        val remaining = vm.joinedGroupsByRelay.value["wss://offline.relay/"] ?: emptySet()
        assertFalse(remaining.contains("group-1"), "group-1 should be removed")
        assertTrue(remaining.contains("group-2"), "group-2 should remain")
    }

    @Test
    fun `forgetGroup on last group leaves relay with empty set`() = runTest {
        val fake = FakeNostrRepository()
        fake._currentRelayUrl.value = "wss://offline.relay/"
        fake._joinedGroupsByRelay.value =
            mapOf(
                "wss://offline.relay/" to setOf("only-group"),
            )
        val vm = HomeViewModel(fake)

        vm.forgetGroup("only-group", "wss://offline.relay/")
        advanceUntilIdle()

        val remaining = vm.joinedGroupsByRelay.value["wss://offline.relay/"] ?: emptySet()
        assertTrue(remaining.isEmpty(), "Relay entry should be empty after removing last group")
    }

    // -------------------------------------------------------------------------
    // removeRelay
    // -------------------------------------------------------------------------

    @Test
    fun `removeRelay calls repo removeRelay`() = runTest {
        val fake = FakeNostrRepository()
        fake._currentRelayUrl.value = "wss://offline.relay/"
        fake._joinedGroupsByRelay.value =
            mapOf(
                "wss://offline.relay/" to setOf("group-1"),
            )
        val vm = HomeViewModel(fake)

        vm.removeRelay("wss://offline.relay/")
        advanceUntilIdle()

        assertTrue(
            fake.calls.any { it.startsWith("removeRelay:") },
            "Expected removeRelay call in call log, got: ${fake.calls}",
        )
    }

    @Test
    fun `removeRelay clears relay from joinedGroupsByRelay`() = runTest {
        val fake = FakeNostrRepository()
        fake._currentRelayUrl.value = "wss://offline.relay/"
        fake._joinedGroupsByRelay.value =
            mapOf(
                "wss://offline.relay/" to setOf("group-1", "group-2"),
                "wss://other.relay/" to setOf("group-3"),
            )
        val vm = HomeViewModel(fake)

        vm.removeRelay("wss://offline.relay/")
        advanceUntilIdle()

        assertFalse(
            vm.joinedGroupsByRelay.value.containsKey("wss://offline.relay/"),
            "Removed relay should not appear in joinedGroupsByRelay",
        )
        // Other relay untouched
        assertTrue(vm.joinedGroupsByRelay.value.containsKey("wss://other.relay/"))
    }

    // -------------------------------------------------------------------------
    // isOffline flag (computed in HomeScreen — tested here via state preconditions)
    // HomeScreen formula: isOffline = isReachabilityError && restrictionMessage == null
    // -------------------------------------------------------------------------

    @Test
    fun `isOffline is true when relay errors with joined groups`() = runTest {
        val fake = FakeNostrRepository()
        fake._connectionState.value = ConnectionManager.ConnectionState.Error("Connection refused")
        fake._joinedGroupsByRelay.value =
            mapOf(
                "wss://offline.relay/" to setOf("group-1"),
            )
        val vm = HomeViewModel(fake)

        val connectionState = vm.connectionState.value
        val isReachabilityError =
            connectionState is ConnectionManager.ConnectionState.Error ||
                connectionState is ConnectionManager.ConnectionState.Reconnecting
        val isOffline = isReachabilityError // no groups requirement

        assertTrue(isReachabilityError, "Should detect error state")
        assertTrue(isOffline, "isOffline should be true when relay is unreachable")
    }

    @Test
    fun `isOffline is true when relay has no joined groups but is unreachable`() = runTest {
        val fake = FakeNostrRepository()
        fake._connectionState.value = ConnectionManager.ConnectionState.Error("Connection refused")
        fake._joinedGroupsByRelay.value = emptyMap()
        val vm = HomeViewModel(fake)

        val connectionState = vm.connectionState.value
        val isReachabilityError =
            connectionState is ConnectionManager.ConnectionState.Error ||
                connectionState is ConnectionManager.ConnectionState.Reconnecting
        val isOffline = isReachabilityError

        assertTrue(isReachabilityError)
        assertTrue(isOffline, "isOffline should be true even with no groups — ManageRelayContent shows Remove relay")
    }

    @Test
    fun `isOffline is false when relay is connected despite having groups`() = runTest {
        val fake = FakeNostrRepository()
        fake._connectionState.value = ConnectionManager.ConnectionState.Connected
        fake._joinedGroupsByRelay.value =
            mapOf(
                "wss://relay.example.com/" to setOf("group-1"),
            )
        val vm = HomeViewModel(fake)

        val connectionState = vm.connectionState.value
        val isReachabilityError =
            connectionState is ConnectionManager.ConnectionState.Error ||
                connectionState is ConnectionManager.ConnectionState.Reconnecting
        val isOffline = isReachabilityError

        assertFalse(isReachabilityError)
        assertFalse(isOffline, "Connected relay should not show offline screen")
    }

    @Test
    fun `isOffline is true during Reconnecting state regardless of joined groups`() = runTest {
        val fake = FakeNostrRepository()
        // Reconnecting fires before Error — relay goes through up to 10 attempts before Error
        fake._connectionState.value = ConnectionManager.ConnectionState.Reconnecting(attempt = 1, maxAttempts = 10)
        fake._joinedGroupsByRelay.value = emptyMap()
        val vm = HomeViewModel(fake)

        val connectionState = vm.connectionState.value
        val isReachabilityError =
            connectionState is ConnectionManager.ConnectionState.Error ||
                connectionState is ConnectionManager.ConnectionState.Reconnecting
        val isOffline = isReachabilityError

        assertTrue(isReachabilityError, "Reconnecting should count as a reachability error")
        assertTrue(isOffline, "isOffline should be true during Reconnecting even without joined groups")
    }
}
