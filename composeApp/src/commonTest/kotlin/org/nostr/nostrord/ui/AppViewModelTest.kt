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
        // Drain any coroutines launched on Dispatchers.Main (e.g. viewModelScope
        // jobs from AppViewModel.init) before resetMain — otherwise an unrun
        // task can race with the dispatcher swap and surface as
        // UncaughtExceptionsBeforeTest in a later test.
        testDispatcher.scheduler.advanceUntilIdle()
        Dispatchers.resetMain()
    }

    @Test
    fun `isInitialized starts false`() = runTest {
        val fake = FakeNostrRepository()
        fake.initializeAction = {} // don't flip to true
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

    @Test
    fun `needsOnboarding is true only while the kind10009 group list is empty`() = runTest {
        val fake = FakeNostrRepository()
        val vm = AppViewModel(fake)
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.needsOnboarding.value)

        fake._joinedGroupsByRelay.value = mapOf("wss://relay.example.com" to setOf("group1"))
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(vm.needsOnboarding.value)

        // Relays listed but every group set empty still counts as "no groups".
        fake._joinedGroupsByRelay.value = mapOf("wss://relay.example.com" to emptySet())
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.needsOnboarding.value)
    }

    @Test
    fun `skipOnboarding flips the session override`() = runTest {
        val fake = FakeNostrRepository()
        val vm = AppViewModel(fake)
        assertFalse(vm.onboardingSkipped.value)
        vm.skipOnboarding()
        assertTrue(vm.onboardingSkipped.value)
    }

    @Test
    fun `switching account re-pends the onboarding decision until the new list resolves`() = runTest {
        val fake = FakeNostrRepository()
        fake._activePubkey.value = "a".repeat(64)
        fake._joinedGroupsByRelay.value = mapOf("wss://a" to setOf("g1"))
        val vm = AppViewModel(fake)
        testDispatcher.scheduler.advanceUntilIdle()
        // Account A has groups: resolved, so neither pending (loading) nor onboarding.
        assertFalse(vm.onboardingDecisionPending.value)
        assertFalse(vm.needsOnboarding.value)

        // Switch to B whose list is empty and unresolved: the decision re-pends (loading
        // screen), never flashing the wizard. runCurrent so the resolve grace doesn't fire.
        fake._activePubkey.value = "b".repeat(64)
        fake._joinedGroupsByRelay.value = emptyMap()
        testDispatcher.scheduler.runCurrent()
        assertTrue(vm.onboardingDecisionPending.value)
        assertFalse(vm.needsOnboarding.value)

        // B's list resolves still-empty (grace elapses): now onboarding, no longer pending.
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(vm.onboardingDecisionPending.value)
        assertTrue(vm.needsOnboarding.value)
    }
}
