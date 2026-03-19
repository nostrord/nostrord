package org.nostr.nostrord.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.nostr.nostrord.network.FakeNostrRepository
import org.nostr.nostrord.ui.screens.home.HomeViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

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
    fun `groups starts empty`() = runTest {
        val vm = HomeViewModel(FakeNostrRepository())
        assertTrue(vm.groups.value.isEmpty())
    }

    @Test
    fun `joinedGroups reflects repo state`() = runTest {
        val fake = FakeNostrRepository()
        fake._joinedGroups.value = setOf("group-1", "group-2")
        val vm = HomeViewModel(fake)

        assertEquals(setOf("group-1", "group-2"), vm.joinedGroups.value)
    }

    @Test
    fun `getPublicKey returns null when not logged in`() = runTest {
        val vm = HomeViewModel(FakeNostrRepository())
        assertNull(vm.getPublicKey())
    }

    @Test
    fun `getPublicKey returns repo public key`() = runTest {
        val fake = FakeNostrRepository()
        fake.fakePublicKey = "deadbeef"
        val vm = HomeViewModel(fake)

        assertEquals("deadbeef", vm.getPublicKey())
    }

    @Test
    fun `unreadCounts reflects repo state`() = runTest {
        val fake = FakeNostrRepository()
        fake._unreadCounts.value = mapOf("group-1" to 5)
        val vm = HomeViewModel(fake)

        assertEquals(5, vm.unreadCounts.value["group-1"])
    }
}
